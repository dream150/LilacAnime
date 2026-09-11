package com.lilac.anime.dns.resolver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * fallback 전용 시스템 resolver.
 *
 * 주의: 여기서 "시스템 DNS" 는 **VPN 바깥**(underlying) 네트워크의 DNS 서버다.
 * 우리 VPN 의 DNS 주소를 그대로 쓰면 자기 자신으로 되돌아오는 루프가 되므로,
 * 반드시 VPN 이 아닌 활성 네트워크의 DNS 서버 목록을 provider 가 골라줘야 한다.
 */
class SystemDnsResolver(
    private val serversProvider: () -> List<InetAddress>,
    private val protector: SocketProtector = SocketProtector.NONE,
    private val timeoutMillis: Int = 2000,
    private val log: (String) -> Unit = {},
) : DnsResolver {

    override val label: String = "system"

    override suspend fun resolve(query: ByteArray, length: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val servers = try {
                serversProvider()
            } catch (t: Throwable) {
                log("SYSTEM_DNS_LOOKUP_FAILED ${t.javaClass.simpleName}")
                emptyList()
            }
            if (servers.isEmpty()) {
                log("SYSTEM_DNS_UNAVAILABLE")
                return@withContext null
            }
            UdpDnsResolver(
                label = label,
                servers = servers,
                protector = protector,
                timeoutMillis = timeoutMillis,
                attemptsPerServer = 1,
                log = log,
            ).resolve(query, length)
        }
}
