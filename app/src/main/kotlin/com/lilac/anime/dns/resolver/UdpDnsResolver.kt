package com.lilac.anime.dns.resolver

import com.lilac.anime.dns.DnsMessageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP/53 질의 1회.
 *
 * bootstrap 처럼 코루틴 밖(OkHttp 의 blocking 루프)에서도 써야 하므로 non-suspend 로 둔다.
 */
object UdpDnsExchange {
    const val DNS_PORT = 53
    const val DEFAULT_MAX_RESPONSE = 4096

    fun query(
        server: InetAddress,
        query: ByteArray,
        length: Int,
        timeoutMillis: Int,
        protector: SocketProtector,
        maxResponseBytes: Int = DEFAULT_MAX_RESPONSE,
        log: (String) -> Unit = {},
    ): ByteArray? {
        if (length < DnsMessageCodec.HEADER_SIZE || length > query.size) return null
        var socket: DatagramSocket? = null
        return try {
            val created = DatagramSocket()
            socket = created
            protector.protect(created)
            created.soTimeout = timeoutMillis
            created.send(DatagramPacket(query, length, server, DNS_PORT))

            val buffer = ByteArray(maxResponseBytes)
            val packet = DatagramPacket(buffer, buffer.size)
            created.receive(packet)

            val size = packet.length
            if (size < DnsMessageCodec.HEADER_SIZE) {
                null
            } else if (DnsMessageCodec.u16(buffer, 0) != DnsMessageCodec.u16(query, 0)) {
                // 트랜잭션 ID 불일치 = 지연 도착/스푸핑 응답
                log("UDP_ID_MISMATCH")
                null
            } else {
                buffer.copyOf(size)
            }
        } catch (t: Throwable) {
            log("UDP_QUERY_FAILED ${t.javaClass.simpleName}")
            null
        } finally {
            runCatching { socket?.close() }
        }
    }
}

/**
 * 일반 DNS resolver.
 *
 * Primary 실패 시 Secondary 로 넘어가며, 서버별로 retry 를 수행한다.
 */
class UdpDnsResolver(
    override val label: String,
    private val servers: List<InetAddress>,
    private val protector: SocketProtector = SocketProtector.NONE,
    private val timeoutMillis: Int = 2500,
    private val attemptsPerServer: Int = 2,
    private val maxResponseBytes: Int = UdpDnsExchange.DEFAULT_MAX_RESPONSE,
    private val log: (String) -> Unit = {},
) : DnsResolver {

    override suspend fun resolve(query: ByteArray, length: Int): ByteArray? {
        if (servers.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            for (server in servers) {
                repeat(attemptsPerServer) {
                    val response = UdpDnsExchange.query(
                        server = server,
                        query = query,
                        length = length,
                        timeoutMillis = timeoutMillis,
                        protector = protector,
                        maxResponseBytes = maxResponseBytes,
                        log = log,
                    )
                    if (response != null) return@withContext response
                }
            }
            null
        }
    }
}
