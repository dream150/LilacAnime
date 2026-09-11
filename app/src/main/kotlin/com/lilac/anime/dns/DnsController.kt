package com.lilac.anime.dns

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.lilac.anime.dns.vpn.DnsVpnService

/**
 * DNS VPN 의 시작/중지/복구 지점.
 *
 * - Android 일반 앱은 시스템 DNS 를 직접 바꿀 수 없으므로 VpnService 를 쓴다.
 * - SYSTEM 모드에서는 VPN 을 만들지 않고 즉시 종료한다.
 */
object DnsController {

    /** 서비스가 같은 프로세스에서 살아 있는지. (상태 표시용) */
    @Volatile
    var isServiceRunning: Boolean = false
        internal set

    fun start(context: Context) {
        val intent = Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    /** 실행 중인 서비스에 설정 변경을 반영한다. */
    fun reload(context: Context) {
        val intent = Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_RELOAD)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    /**
     * 터널을 종료한다.
     *
     * stopService 를 쓰는 이유: startForegroundService 로 서비스를 시작하면 5초 안에
     * startForeground 를 호출해야 한다. 종료 경로는 그럴 필요가 없으므로
     * onDestroy 에서 정리하게 한다.
     */
    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, DnsVpnService::class.java)) }
        if (!isServiceRunning) {
            DnsRuntimeState.update {
                it.copy(state = DnsRunState.OFF, reason = DnsStatusReason.SERVICE_STOPPED)
            }
        }
    }

    /**
     * VPN 권한이 필요한지. null 이면 이미 승인된 상태다.
     * UI 는 이 Intent 를 ActivityResult 로 띄운다.
     */
    fun permissionIntent(context: Context): Intent? =
        runCatching { VpnService.prepare(context) }.getOrNull()

    /** 설정을 켠 상태로 시작할 때, 권한이 이미 있으면 서비스를 시작한다. */
    fun startIfPermitted(context: Context): Boolean {
        if (permissionIntent(context) != null) return false
        start(context)
        return true
    }

    /**
     * 앱 시작 시 호출.
     *
     * 무조건 VPN 을 켜지 않는다. 저장된 설정 / VPN 권한 / 서비스 상태를 모두
     * 확인한 뒤에만 복구한다.
     */
    suspend fun ensureStartedIfEnabled(context: Context): Boolean {
        val settings = DnsSettingsRepository(context).current()
        if (!settings.enabled) return false
        if (settings.activeProfile.mode == DnsMode.SYSTEM) return false
        if (isServiceRunning) return true
        if (permissionIntent(context) != null) {
            DnsRuntimeState.update {
                it.copy(
                    state = DnsRunState.OFF,
                    reason = DnsStatusReason.PERMISSION_REQUIRED,
                )
            }
            return false
        }
        start(context)
        return true
    }
}
