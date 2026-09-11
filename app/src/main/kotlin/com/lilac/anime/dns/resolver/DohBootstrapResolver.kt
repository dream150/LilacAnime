package com.lilac.anime.dns.resolver

import com.lilac.anime.dns.DnsAnswer
import com.lilac.anime.dns.DnsMessageCodec
import com.lilac.anime.dns.DnsQueryBuilder
import com.lilac.anime.dns.DnsRcode
import com.lilac.anime.dns.DnsType
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * DoH endpoint hostname 을 IP 로 바꾸는 bootstrap resolver.
 *
 * circular dependency 방지가 목적이다:
 *
 *   DoH URL hostname -> (이 resolver) -> IP -> HTTPS -> DoH
 *
 * 우선순위: 설정된 bootstrap IP -> provider IP -> 시스템(underlying) DNS.
 * 어느 경우에도 hostname 을 OS resolver 로 풀지 않는다. 그렇게 하면 우리 VPN 을
 * 다시 거쳐 무한 루프가 된다.
 */
class DohBootstrapResolver(
    bootstrapServers: List<InetAddress>,
    private val fallbackServerProvider: (() -> List<InetAddress>)? = null,
    private val protector: SocketProtector = SocketProtector.NONE,
    private val timeoutMillis: Int = 2500,
    private val cacheTtlMillis: Long = 300_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) : Dns {

    private class Entry(val addresses: List<InetAddress>, val expiresAtMillis: Long)

    private val configuredServers: List<InetAddress> = bootstrapServers
    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        // IP 리터럴이면 DNS 질의 자체가 필요 없다.
        IpAddresses.literal(hostname)?.let { return listOf(it) }

        val now = clock()
        cache[hostname]?.takeIf { it.expiresAtMillis > now }?.let { return it.addresses }

        val servers = resolveServers()
        if (servers.isEmpty()) throw UnknownHostException(hostname)

        val addresses = queryAddresses(servers, hostname)
        if (addresses.isEmpty()) {
            cache.remove(hostname)
            throw UnknownHostException(hostname)
        }
        cache[hostname] = Entry(addresses, now + cacheTtlMillis)
        return addresses
    }

    fun clearCache() = cache.clear()

    private fun resolveServers(): List<InetAddress> {
        if (configuredServers.isNotEmpty()) return configuredServers
        val provider = fallbackServerProvider ?: return emptyList()
        return try {
            provider()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun queryAddresses(servers: List<InetAddress>, hostname: String): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        for (type in intArrayOf(DnsType.A, DnsType.AAAA)) {
            val query = DnsQueryBuilder.build(
                id = ((clock() and 0xFFFF).toInt()),
                name = hostname,
                type = type,
            ) ?: continue

            for (server in servers) {
                val response = UdpDnsExchange.query(
                    server = server,
                    query = query,
                    length = query.size,
                    timeoutMillis = timeoutMillis,
                    protector = protector,
                    log = log,
                ) ?: continue

                val parsed = DnsMessageCodec.parse(response, response.size) ?: continue
                if (parsed.rcode != DnsRcode.NO_ERROR) continue
                parsed.answers.forEach { answer ->
                    addressOf(response, answer)?.let { out += it }
                }
                if (out.isNotEmpty()) break
            }
        }
        return out.toList()
    }

    private fun addressOf(buffer: ByteArray, answer: DnsAnswer): InetAddress? {
        val length = answer.rdataLength
        val expected = when (answer.type) {
            DnsType.A -> 4
            DnsType.AAAA -> 16
            else -> return null
        }
        if (length != expected) return null
        if (answer.rdataOffset < 0 || answer.rdataOffset + length > buffer.size) return null
        val bytes = buffer.copyOfRange(answer.rdataOffset, answer.rdataOffset + length)
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }
}
