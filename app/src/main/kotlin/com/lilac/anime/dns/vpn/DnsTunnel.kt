package com.lilac.anime.dns.vpn

import android.os.ParcelFileDescriptor
import com.lilac.anime.dns.DnsMessageCodec
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** TUN 입출력 추상화. 테스트에서 fake 로 교체할 수 있다. */
interface TunnelIo {
    /** blocking read. 데이터가 없으면 대기한다. 닫히면 예외. */
    fun read(buffer: ByteArray): Int
    fun write(packet: ByteArray, length: Int)
    fun close()
}

class ParcelTunnelIo(private val descriptor: ParcelFileDescriptor) : TunnelIo {

    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)

    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(packet: ByteArray, length: Int) {
        output.write(packet, 0, length)
        output.flush()
    }

    override fun close() {
        // 스트림을 먼저 닫으면 fd 가 닫히면서 blocking read 가 풀린다.
        runCatching { input.close() }
        runCatching { output.close() }
        // 스트림이 이미 fd 를 닫았으므로 여기서 예외가 날 수 있다. 무시한다.
        runCatching { descriptor.close() }
    }
}

/**
 * DNS 전용 TUN 루프.
 *
 * VPN 은 DNS 주소(/32, /128)만 라우팅하므로 이 TUN 에는 DNS 트래픽만 들어온다.
 * 들어온 UDP:53 질의를 resolver 로 전달하고, 응답 패킷을 만들어 다시 쓴다.
 */
class DnsTunnel(
    private val core: DnsProxyCore,
    private val io: TunnelIo,
    private val mtu: Int,
    private val dnsAddressV4: ByteArray?,
    private val dnsAddressV6: ByteArray?,
    private val stats: DnsStats,
    private val maxConcurrent: Int = 8,
    private val onFatal: (String) -> Unit = {},
    private val log: (String) -> Unit = {},
) {

    private val executor: ExecutorService =
        Executors.newFixedThreadPool(maxConcurrent.coerceAtLeast(2)) { runnable ->
            Thread(runnable, "lilac-dns-worker").apply { isDaemon = true }
        }

    private val writeLock = Any()

    @Volatile
    private var running = false

    private var reader: Thread? = null

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        reader = Thread({ readLoop() }, "lilac-dns-tun").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { io.close() }
        runCatching { executor.shutdownNow() }
        runCatching { reader?.join(1000L) }
        reader = null
    }

    private fun readLoop() {
        val buffer = ByteArray(mtu.coerceAtLeast(1500))
        while (running) {
            val read = try {
                io.read(buffer)
            } catch (t: Throwable) {
                if (running) {
                    log("TUN_READ_FAILED ${t.javaClass.simpleName}")
                    onFatal("TUN_READ_FAILED")
                }
                break
            }
            if (read <= 0) continue

            val packet = IpPacket.parse(buffer, read) ?: continue
            if (packet.destinationPort != IpPacket.DNS_PORT) {
                stats.onDropped()
                continue
            }
            if (!isOurDnsAddress(packet)) {
                stats.onDropped()
                continue
            }
            // TCP:53 은 DNS VPN 에서 처리하지 않는다. (연결을 만들지 않고 버린다)
            if (packet.protocol != IpPacket.PROTO_UDP) {
                stats.onDropped()
                log("DNS_TCP_DROPPED")
                continue
            }
            if (packet.payloadLength <= 0) continue

            val query = packet.payload
            try {
                executor.execute { handleQuery(packet, query) }
            } catch (_: RejectedExecutionException) {
                // 종료 중
            }
        }
    }

    private fun handleQuery(request: TunnelPacket, query: ByteArray) {
        try {
            val response = runBlocking { core.handleQuery(query, query.size) } ?: return
            var payload = response

            val maxPayload = IpPacket.maxUdpPayload(mtu, request.ipVersion)
            if (payload.size > maxPayload) {
                payload = truncateForTransport(response, maxPayload) ?: run {
                    stats.onDropped()
                    return
                }
                stats.onTruncated()
            }

            val reply = IpPacket.buildUdpReply(request, payload, payload.size) ?: return
            synchronized(writeLock) { io.write(reply, reply.size) }
        } catch (t: Throwable) {
            log("REPLY_FAILED ${t.javaClass.simpleName}")
        }
    }

    /**
     * MTU 를 넘는 응답은 TC 비트를 세운 응답으로 대체한다.
     *
     * TCP fallback 은 지원하지 않으므로 이 경우 해당 조회는 실패할 수 있다.
     * (알려진 제한 사항)
     */
    private fun truncateForTransport(response: ByteArray, maxPayload: Int): ByteArray? {
        val parsed = DnsMessageCodec.parse(response, response.size) ?: return null
        val questionEnd = parsed.questionEndOffset
        if (questionEnd < DnsMessageCodec.HEADER_SIZE || questionEnd > maxPayload) return null
        return DnsMessageCodec.truncate(response, response.size, questionEnd)
    }

    private fun isOurDnsAddress(packet: TunnelPacket): Boolean = when (packet.ipVersion) {
        4 -> dnsAddressV4?.let { packet.destinationAddress.contentEquals(it) } == true
        6 -> dnsAddressV6?.let { packet.destinationAddress.contentEquals(it) } == true
        else -> false
    }
}
