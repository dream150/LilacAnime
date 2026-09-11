package com.lilac.anime.dns.resolver

import com.lilac.anime.dns.DnsMode
import com.lilac.anime.dns.DnsProfile
import com.lilac.anime.dns.DnsValidation
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/**
 * VPN socket 보호.
 *
 * VpnService 가 만든 TUN 으로 앱 자신의 DNS 질의가 다시 들어가면 루프가 되므로,
 * upstream 통신 socket 은 반드시 protect 해야 한다.
 */
interface SocketProtector {
    fun protect(socket: Socket): Boolean
    fun protect(socket: DatagramSocket): Boolean

    companion object {
        val NONE: SocketProtector = object : SocketProtector {
            override fun protect(socket: Socket): Boolean = true
            override fun protect(socket: DatagramSocket): Boolean = true
        }
    }
}

/** raw DNS 질의 -> raw DNS 응답. 실패하면 null. */
interface DnsResolver {
    val label: String
    suspend fun resolve(query: ByteArray, length: Int): ByteArray?
}

/**
 * 여러 resolver 를 우선순위대로 시도한다.
 *
 * 개별 resolver 의 예외가 전체 DNS 를 죽이지 않도록 여기서 모두 흡수한다.
 */
class DnsResolverChain(private val resolvers: List<DnsResolver>) {

    val labels: List<String> get() = resolvers.map { it.label }

    val isEmpty: Boolean get() = resolvers.isEmpty()

    suspend fun resolve(query: ByteArray, length: Int): ByteArray? {
        for (resolver in resolvers) {
            val result = try {
                resolver.resolve(query, length)
            } catch (_: Throwable) {
                // resolver 실패는 다음 후보로 넘어간다. 앱 전체로 전파하지 않는다.
                null
            }
            if (result != null) return result
        }
        return null
    }
}

/** 문자열 IP 리터럴 파싱. hostname 조회를 절대 하지 않는다. */
object IpAddresses {
    fun literal(value: String?): InetAddress? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim().removePrefix("[").removeSuffix("]")
        if (!DnsValidation.isValidIp(trimmed)) return null
        return runCatching { InetAddress.getByName(trimmed) }.getOrNull()
    }

    fun literals(vararg values: String?): List<InetAddress> =
        values.mapNotNull { literal(it) }.distinct()

    fun literals(values: List<String?>): List<InetAddress> =
        values.mapNotNull { literal(it) }.distinct()
}

/**
 * 선택된 profile 로 실제 resolver 체인을 만든다.
 *
 * fallback 순서
 * - UDP  : primary -> secondary -> (설정 시) system
 * - DoH  : DoH -> (주소가 있으면) UDP -> (설정 시) system
 *
 * 사용자가 fallback 을 끄면 system resolver 를 체인에 넣지 않는다.
 */
object DnsResolverFactory {

    fun build(
        profile: DnsProfile,
        fallbackToSystem: Boolean,
        protector: SocketProtector,
        systemDnsProvider: () -> List<InetAddress>,
        log: (String) -> Unit = {},
    ): DnsResolverChain {
        val resolvers = mutableListOf<DnsResolver>()
        val udpServers = IpAddresses.literals(listOf(profile.primary, profile.secondary))

        when (profile.mode) {
            DnsMode.SYSTEM -> Unit

            DnsMode.UDP -> {
                if (udpServers.isNotEmpty()) {
                    resolvers += UdpDnsResolver(
                        label = "udp",
                        servers = udpServers,
                        protector = protector,
                        log = log,
                    )
                }
            }

            DnsMode.DOH -> {
                val dohUrl = profile.dohUrl
                if (!dohUrl.isNullOrBlank()) {
                    val bootstrapServers = IpAddresses.literals(
                        listOf(profile.bootstrap, profile.primary, profile.secondary)
                    )
                    val bootstrap = DohBootstrapResolver(
                        bootstrapServers = bootstrapServers,
                        fallbackServerProvider = systemDnsProvider,
                        protector = protector,
                        log = log,
                    )
                    resolvers += DohDnsResolver(
                        label = "doh",
                        dohUrl = dohUrl,
                        bootstrapDns = bootstrap,
                        protector = protector,
                        log = log,
                    )
                }
                // DoH 주소를 못 쓰는 상황을 위한 UDP 보조 경로.
                if (udpServers.isNotEmpty()) {
                    resolvers += UdpDnsResolver(
                        label = "udp-fallback",
                        servers = udpServers,
                        protector = protector,
                        log = log,
                    )
                }
            }
        }

        if (fallbackToSystem) {
            resolvers += SystemDnsResolver(
                serversProvider = systemDnsProvider,
                protector = protector,
                log = log,
            )
        }

        return DnsResolverChain(resolvers)
    }
}
