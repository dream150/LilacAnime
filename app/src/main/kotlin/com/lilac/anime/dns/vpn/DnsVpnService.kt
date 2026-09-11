package com.lilac.anime.dns.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lilac.anime.dns.DnsCache
import com.lilac.anime.dns.DnsController
import com.lilac.anime.dns.DnsMode
import com.lilac.anime.dns.DnsProfile
import com.lilac.anime.dns.DnsProviders
import com.lilac.anime.dns.DnsRunState
import com.lilac.anime.dns.DnsRuntimeState
import com.lilac.anime.dns.DnsRuntimeStatus
import com.lilac.anime.dns.DnsSettings
import com.lilac.anime.dns.DnsSettingsRepository
import com.lilac.anime.dns.DnsStatusReason
import com.lilac.anime.dns.DnsValidation
import com.lilac.anime.dns.resolver.DnsResolverChain
import com.lilac.anime.dns.resolver.DnsResolverFactory
import com.lilac.anime.dns.resolver.SocketProtector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.Socket

/**
 * DNS 전용 VPN.
 *
 * 전체 트래픽을 프록시하지 않는다. VPN 인터페이스에는 DNS 프록시 주소(/32, /128)만
 * 라우팅하고, 그 주소로 들어온 UDP:53 질의만 선택한 resolver 로 전달한다.
 * 나머지 트래픽(스트리밍, 다운로드, Cast, mDNS)은 기존 네트워크를 그대로 쓴다.
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.lilac.anime.dns.action.START"
        const val ACTION_STOP = "com.lilac.anime.dns.action.STOP"
        const val ACTION_RELOAD = "com.lilac.anime.dns.action.RELOAD"

        private const val TAG = "LilacDns"
        private const val CHANNEL_ID = "lilac_dns"
        private const val NOTIFICATION_ID = 4211
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * onDestroy 에서 cancel 하지 않는 scope.
     *
     * onRevoke 직후 onDestroy 가 실행되면서 [scope] 가 종료되므로,
     * "DNS 끔" 상태 저장은 여기서 해야 유실되지 않는다.
     */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val protector = object : SocketProtector {
        override fun protect(socket: Socket): Boolean =
            runCatching { this@DnsVpnService.protect(socket) }.getOrDefault(false)

        override fun protect(socket: DatagramSocket): Boolean =
            runCatching { this@DnsVpnService.protect(socket) }.getOrDefault(false)
    }

    private val cache = DnsCache()
    private val stats = DnsStats()

    @Volatile
    private var chain: DnsResolverChain? = null

    private var tunnel: DnsTunnel? = null
    private var statsJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var activeProfile: DnsProfile = DnsProviders.builtIn(DnsProviders.SYSTEM)!!

    /** 진행 중인 터널이 사용하는 설정. resolver 체인을 만들 때 참조한다. */
    @Volatile
    private var activeSettings: DnsSettings = DnsSettings()

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onCreate() {
        super.onCreate()
        DnsController.isServiceRunning = true
        createChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdownTunnel()
                stopForegroundCompat()
                DnsRuntimeState.set(
                    DnsRuntimeStatus(state = DnsRunState.OFF, reason = DnsStatusReason.SERVICE_STOPPED)
                )
                stopSelfResult(startId)
                return START_NOT_STICKY
            }

            ACTION_RELOAD -> {
                if (!promoteToForeground()) {
                    DnsRuntimeState.set(
                        DnsRuntimeStatus(
                            state = DnsRunState.ERROR,
                            reason = DnsStatusReason.FOREGROUND_SERVICE_UNAVAILABLE,
                        )
                    )
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                scope.launch { establish() }
                return START_STICKY
            }

            // ACTION_START 또는 시스템 재시작(intent == null)
            else -> {
                if (!promoteToForeground()) {
                    DnsRuntimeState.set(
                        DnsRuntimeStatus(
                            state = DnsRunState.ERROR,
                            reason = DnsStatusReason.FOREGROUND_SERVICE_UNAVAILABLE,
                        )
                    )
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                DnsRuntimeState.update {
                    it.copy(state = DnsRunState.STARTING, reason = DnsStatusReason.STARTING)
                }
                scope.launch { establish() }
                return START_STICKY
            }
        }
    }

    /** 사용자가 시스템 설정에서 VPN 을 끊은 경우. 다른 VPN 을 강제로 종료하지 않는다. */
    override fun onRevoke() {
        shutdownTunnel()
        stopForegroundCompat()
        // onRevoke 직후 onDestroy 가 scope 를 cancel 하므로 별도 scope 에서 저장한다.
        persistScope.launch {
            // 사용자의 의도를 존중해서 설정도 꺼 둔다.
            val repository = DnsSettingsRepository(this@DnsVpnService)
            repository.save(repository.current().copy(enabled = false))
        }
        DnsRuntimeState.set(
            DnsRuntimeStatus(state = DnsRunState.OFF, reason = DnsStatusReason.REVOKED)
        )
        stopSelf()
    }

    override fun onDestroy() {
        shutdownTunnel()
        unregisterNetworkCallback()
        DnsController.isServiceRunning = false
        if (DnsRuntimeState.current.state != DnsRunState.ERROR) {
            DnsRuntimeState.update {
                it.copy(state = DnsRunState.OFF, reason = DnsStatusReason.DISABLED)
            }
        }
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- setup

    private suspend fun establish() {
        val settings = try {
            DnsSettingsRepository(this).current()
        } catch (t: Throwable) {
            logEvent("SETTINGS_LOAD_FAILED ${t.javaClass.simpleName}")
            DnsSettings()
        }

        val profile = settings.activeProfile
        activeProfile = profile
        activeSettings = settings

        if (!settings.enabled || profile.mode == DnsMode.SYSTEM) {
            shutdownTunnel()
            stopForegroundCompat()
            DnsRuntimeState.set(
                DnsRuntimeStatus(state = DnsRunState.OFF, reason = DnsStatusReason.DISABLED)
            )
            stopSelf()
            return
        }

        val errors = DnsValidation.validate(settings)
        if (errors.isNotEmpty()) {
            publishInvalid()
            stopSelf()
            return
        }

        if (tunnel?.isRunning == true) {
            // 이미 동작 중 -> resolver 체인만 새 설정으로 교체한다.
            invalidateResolvers()
            promoteToForeground()
            publishRunning()
            return
        }

        // 네트워크가 없더라도 터널은 올린다.
        // upstream 이 없으면 질의는 실패하고, 네트워크가 돌아오면 network callback 이
        // resolver 체인을 다시 만들기 때문에 자동으로 회복된다.
        val descriptor = try {
            buildVpnInterface()?.establish()
        } catch (t: Throwable) {
            logEvent("ESTABLISH_FAILED ${t.javaClass.simpleName}")
            null
        }

        if (descriptor == null) {
            // establish() 가 null 이면 보통 다른 VPN 이 점유 중이다.
            // 다른 VPN 을 강제로 종료하지 않고 사용자에게 안내만 한다.
            val foreignVpn = AndroidNetworkDns.hasActiveForeignVpn(this)
            logEvent("ESTABLISH_NULL_CONFLICT foreignVpn=$foreignVpn")
            publishError(
                if (foreignVpn) DnsStatusReason.ANOTHER_VPN_ACTIVE else DnsStatusReason.TUNNEL_ERROR
            )
            stopForegroundCompat()
            stopSelf()
            return
        }

        val core = DnsProxyCore(
            cache = cache,
            resolversProvider = { currentChain() },
            stats = stats,
            log = ::logEvent,
        )
        val started = DnsTunnel(
            core = core,
            io = ParcelTunnelIo(descriptor),
            mtu = DnsVpnAddresses.MTU,
            dnsAddressV4 = DnsVpnAddresses.proxyV4Bytes,
            dnsAddressV6 = DnsVpnAddresses.proxyV6Bytes,
            stats = stats,
            onFatal = { reason ->
                logEvent("TUNNEL_FATAL $reason")
                scope.launch {
                    shutdownTunnel()
                    publishError(DnsStatusReason.TUNNEL_ERROR)
                }
            },
            log = ::logEvent,
        )
        tunnel = started
        started.start()
        startStatsLoop()
        publishRunning()
    }

    private fun buildVpnInterface(): Builder = Builder()
        .setSession("LilacAnime DNS")
        .setMtu(DnsVpnAddresses.MTU)
        .addAddress(DnsVpnAddresses.TUN_ADDRESS_V4, DnsVpnAddresses.PREFIX_V4)
        .addDnsServer(DnsVpnAddresses.PROXY_ADDRESS_V4)
        .addRoute(DnsVpnAddresses.PROXY_ADDRESS_V4, DnsVpnAddresses.PREFIX_V4)
        .addAddress(DnsVpnAddresses.TUN_ADDRESS_V6, DnsVpnAddresses.PREFIX_V6)
        .addDnsServer(DnsVpnAddresses.PROXY_ADDRESS_V6)
        .addRoute(DnsVpnAddresses.PROXY_ADDRESS_V6, DnsVpnAddresses.PREFIX_V6)
        // 기본은 non-blocking 이므로 반드시 blocking 으로 설정해야 read 가 대기한다.
        .setBlocking(true)
        .also { builder ->
            // 우리 앱 자신은 제외하지 않는다. Linkkf/Animenosub/다운로드도
            // 선택한 DNS 를 쓰게 하는 것이 목적이다.
            openAppIntent()?.let { intent ->
                runCatching { builder.setConfigureIntent(intent) }
            }
        }

    private fun currentChain(): DnsResolverChain {
        chain?.let { return it }
        synchronized(this) {
            chain?.let { return it }
            val built = DnsResolverFactory.build(
                profile = activeProfile,
                // 사용자가 fallback 을 끄면 시스템 DNS 로 넘어가지 않는다.
                fallbackToSystem = activeSettings.fallbackToSystem,
                protector = protector,
                systemDnsProvider = { AndroidNetworkDns.underlyingServers(this) },
                log = ::logEvent,
            )
            chain = built
            return built
        }
    }

    /** 네트워크가 바뀌면 upstream 해석 결과와 캐시를 버린다. */
    private fun invalidateResolvers() {
        chain = null
        cache.clear()
        logEvent("RESOLVERS_INVALIDATED")
    }

    // ---------------------------------------------------------- connectivity

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // VPN 자신은 제외해야 Wi-Fi <-> LTE 전환을 감지할 수 있다.
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                invalidateResolvers()
            }

            override fun onLost(network: Network) {
                invalidateResolvers()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                // 새로운 DNS 서버/DNS6 정보가 반영되면 다시 해석한다.
                invalidateResolvers()
            }
        }
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { logEvent("NETWORK_CALLBACK_FAILED") }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
    }

    // ------------------------------------------------------------- lifecycle

    private fun shutdownTunnel() {
        statsJob?.cancel()
        statsJob = null
        runCatching { tunnel?.stop() }
        tunnel = null
        chain = null
        cache.clear()
        stats.reset()
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(2_000L)
                if (tunnel?.isRunning != true) break
                publishRunning()
            }
        }
    }

    private fun publishRunning() {
        val snapshot = stats.snapshot(cache.size)
        DnsRuntimeState.set(
            DnsRuntimeStatus(
                state = DnsRunState.ACTIVE,
                reason = DnsStatusReason.RUNNING,
                profileId = activeProfile.id,
                profileName = activeProfile.name,
                mode = activeProfile.mode,
                queries = snapshot.queries,
                cacheHits = snapshot.cacheHits,
                failures = snapshot.failures,
                cacheEntries = snapshot.cacheEntries,
            )
        )
    }

    private fun publishInvalid() {
        logEvent("INVALID_SETTINGS")
        DnsRuntimeState.set(
            DnsRuntimeStatus(
                state = DnsRunState.ERROR,
                reason = DnsStatusReason.INVALID_SETTINGS,
                profileId = activeProfile.id,
                profileName = activeProfile.name,
                mode = activeProfile.mode,
            )
        )
    }

    private fun publishError(reason: DnsStatusReason) {
        DnsRuntimeState.set(
            DnsRuntimeStatus(
                state = DnsRunState.ERROR,
                reason = reason,
                profileId = activeProfile.id,
                profileName = activeProfile.name,
                mode = activeProfile.mode,
            )
        )
    }

    // ---------------------------------------------------------- notification

    private fun promoteToForeground(): Boolean {
        val notification = buildNotification(activeProfile.name)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
            } else {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
            }
            true
        } catch (t: Throwable) {
            // FGS 승격이 실패해도 앱은 죽이지 않는다.
            logEvent("START_FOREGROUND_FAILED ${t.javaClass.simpleName}")
            false
        }
    }

    private fun stopForegroundCompat() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    private fun buildNotification(profileName: String): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LilacAnime DNS")
            .setContentText("$profileName DNS 사용 중")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        openAppIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LilacAnime DNS",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "선택한 DNS provider 사용 상태"
            setShowBadge(false)
        }
        runCatching { manager.createNotificationChannel(channel) }
    }

    private fun logEvent(message: String) {
        Log.d(TAG, message)
    }
}
