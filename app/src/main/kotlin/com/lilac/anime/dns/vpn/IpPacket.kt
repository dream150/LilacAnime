package com.lilac.anime.dns.vpn

/**
 * TUN 에서 읽은 IP 패킷.
 *
 * DNS 만 다루므로 단편화 패킷은 재조립하지 않고 버린다.
 */
data class TunnelPacket(
    val ipVersion: Int,
    val protocol: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
    val payloadLength: Int,
)

/**
 * IPv4/IPv6 + UDP 파싱/생성 + 체크섬.
 *
 * Android 의존성이 없어 JVM 단위 테스트로 왕복 검증이 가능하다.
 */
object IpPacket {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val DNS_PORT = 53

    const val IPV4_HEADER_SIZE = 20
    const val IPV6_HEADER_SIZE = 40
    const val UDP_HEADER_SIZE = 8

    private const val IPV4_FLAG_MORE_FRAGMENTS = 0x2000
    private const val IPV4_FRAGMENT_OFFSET_MASK = 0x1FFF

    /** DNS 응답이 들어갈 수 있는 패킷 크기 상한. */
    fun maxUdpPayload(mtu: Int, ipVersion: Int): Int =
        mtu - (if (ipVersion == 4) IPV4_HEADER_SIZE else IPV6_HEADER_SIZE) - UDP_HEADER_SIZE

    /**
     * 패킷을 해석한다. UDP/TCP 가 아니거나 단편화 패킷이면 null.
     */
    fun parse(buffer: ByteArray, length: Int): TunnelPacket? {
        if (length < IPV4_HEADER_SIZE || length > buffer.size) return null
        val version = (buffer[0].toInt() and 0xF0) ushr 4
        return when (version) {
            4 -> parseIpv4(buffer, length)
            6 -> parseIpv6(buffer, length)
            else -> null
        }
    }

    private fun parseIpv4(buffer: ByteArray, length: Int): TunnelPacket? {
        if (length < IPV4_HEADER_SIZE) return null
        val headerLength = (buffer[0].toInt() and 0x0F) * 4
        if (headerLength < IPV4_HEADER_SIZE || headerLength > length) return null

        val totalLength = u16(buffer, 2)
        if (totalLength < headerLength || totalLength > length) return null

        val flagsAndOffset = u16(buffer, 6)
        // 단편화된 패킷은 재조립하지 않는다.
        if (flagsAndOffset and IPV4_FLAG_MORE_FRAGMENTS != 0) return null
        if (flagsAndOffset and IPV4_FRAGMENT_OFFSET_MASK != 0) return null

        val protocol = buffer[9].toInt() and 0xFF
        return parseTransport(
            buffer = buffer,
            ipVersion = 4,
            protocol = protocol,
            transportOffset = headerLength,
            end = totalLength,
            sourceAddress = buffer.copyOfRange(12, 16),
            destinationAddress = buffer.copyOfRange(16, 20),
        )
    }

    private fun parseIpv6(buffer: ByteArray, length: Int): TunnelPacket? {
        if (length < IPV6_HEADER_SIZE) return null
        val payloadLength = u16(buffer, 4)
        val nextHeader = buffer[6].toInt() and 0xFF
        // extension header 는 처리하지 않는다.
        // 확장 헤더를 UDP 로 잘못 해석하지 않도록 UDP/TCP 외에는 버린다.
        if (nextHeader != PROTO_UDP && nextHeader != PROTO_TCP) return null
        val end = (IPV6_HEADER_SIZE + payloadLength).coerceAtMost(length)
        return parseTransport(
            buffer = buffer,
            ipVersion = 6,
            protocol = nextHeader,
            transportOffset = IPV6_HEADER_SIZE,
            end = end,
            sourceAddress = buffer.copyOfRange(8, 24),
            destinationAddress = buffer.copyOfRange(24, 40),
        )
    }

    private fun parseTransport(
        buffer: ByteArray,
        ipVersion: Int,
        protocol: Int,
        transportOffset: Int,
        end: Int,
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
    ): TunnelPacket? {
        if (transportOffset + 8 > end) return null
        val sourcePort = u16(buffer, transportOffset)
        val destinationPort = u16(buffer, transportOffset + 2)

        if (protocol == PROTO_UDP) {
            val udpLength = u16(buffer, transportOffset + 4)
            if (udpLength < UDP_HEADER_SIZE) return null
            val payloadStart = transportOffset + UDP_HEADER_SIZE
            val payloadEnd = (transportOffset + udpLength).coerceAtMost(end)
            if (payloadEnd < payloadStart) return null
            val payloadLength = payloadEnd - payloadStart
            return TunnelPacket(
                ipVersion = ipVersion,
                protocol = protocol,
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                payload = buffer.copyOfRange(payloadStart, payloadEnd),
                payloadLength = payloadLength,
            )
        }

        // TCP 는 payload 를 만들지 않는다. (연결 거부/무시 판단용 정보만 필요)
        return TunnelPacket(
            ipVersion = ipVersion,
            protocol = protocol,
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            payload = ByteArray(0),
            payloadLength = 0,
        )
    }

    /**
     * 요청 패킷에 대한 UDP 응답 패킷을 만든다.
     *
     * 주소/포트를 뒤집고 체크섬을 다시 계산한다. checksum 0 은 IPv6 에서 금지되므로
     * 0xFFFF 로 대체한다.
     */
    fun buildUdpReply(request: TunnelPacket, payload: ByteArray, payloadLength: Int): ByteArray? {
        val payloadSize = payloadLength.coerceIn(0, payload.size)
        val udpLength = UDP_HEADER_SIZE + payloadSize
        return when (request.ipVersion) {
            4 -> buildIpv4Reply(request, payload, payloadSize, udpLength)
            6 -> buildIpv6Reply(request, payload, payloadSize, udpLength)
            else -> null
        }
    }

    private fun buildIpv4Reply(
        request: TunnelPacket,
        payload: ByteArray,
        payloadSize: Int,
        udpLength: Int,
    ): ByteArray? {
        if (request.sourceAddress.size != 4 || request.destinationAddress.size != 4) return null
        val totalLength = IPV4_HEADER_SIZE + udpLength
        val out = ByteArray(totalLength)

        out[0] = 0x45
        out[1] = 0
        writeU16(out, 2, totalLength)
        writeU16(out, 4, 0)
        writeU16(out, 6, 0x4000) // DF
        out[8] = 64 // TTL
        out[9] = PROTO_UDP.toByte()
        // 체크섬 자리(10,11)는 나중에 채운다.
        request.destinationAddress.copyInto(out, 12)
        request.sourceAddress.copyInto(out, 16)

        val headerChecksum = checksum(sumWords(out, 0, IPV4_HEADER_SIZE))
        writeU16(out, 10, headerChecksum)

        writeU16(out, 20, request.destinationPort)
        writeU16(out, 22, request.sourcePort)
        writeU16(out, 24, udpLength)
        writeU16(out, 26, 0)
        payload.copyInto(out, 28, 0, payloadSize)

        val udpChecksum = udpChecksumIpv4(out, 12, 16, 20, udpLength)
        writeU16(out, 26, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return out
    }

    private fun buildIpv6Reply(
        request: TunnelPacket,
        payload: ByteArray,
        payloadSize: Int,
        udpLength: Int,
    ): ByteArray? {
        if (request.sourceAddress.size != 16 || request.destinationAddress.size != 16) return null
        val totalLength = IPV6_HEADER_SIZE + udpLength
        val out = ByteArray(totalLength)

        out[0] = 0x60
        out[1] = 0
        out[2] = 0
        out[3] = 0
        writeU16(out, 4, udpLength)
        out[6] = PROTO_UDP.toByte()
        out[7] = 64 // hop limit
        request.destinationAddress.copyInto(out, 8)
        request.sourceAddress.copyInto(out, 24)

        writeU16(out, 40, request.destinationPort)
        writeU16(out, 42, request.sourcePort)
        writeU16(out, 44, udpLength)
        writeU16(out, 46, 0)
        payload.copyInto(out, 48, 0, payloadSize)

        val udpChecksum = udpChecksumIpv6(out, 8, 24, 40, udpLength)
        writeU16(out, 46, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return out
    }

    /** IPv4 UDP 체크섬. 결과가 0 이면 호출자가 0xFFFF 로 대체한다. */
    fun udpChecksumIpv4(
        packet: ByteArray,
        sourceOffset: Int,
        destinationOffset: Int,
        udpOffset: Int,
        udpLength: Int,
    ): Int {
        var sum = 0
        sum = sumWords(packet, sourceOffset, sourceOffset + 4, sum)
        sum = sumWords(packet, destinationOffset, destinationOffset + 4, sum)
        sum += PROTO_UDP
        sum += udpLength
        sum = sumWords(packet, udpOffset, udpOffset + udpLength, sum)
        return checksum(sum)
    }

    /** IPv6 UDP 체크섬(필수). */
    fun udpChecksumIpv6(
        packet: ByteArray,
        sourceOffset: Int,
        destinationOffset: Int,
        udpOffset: Int,
        udpLength: Int,
    ): Int {
        var sum = 0
        sum = sumWords(packet, sourceOffset, sourceOffset + 16, sum)
        sum = sumWords(packet, destinationOffset, destinationOffset + 16, sum)
        sum += (udpLength ushr 16) and 0xFFFF
        sum += udpLength and 0xFFFF
        sum += PROTO_UDP
        sum = sumWords(packet, udpOffset, udpOffset + udpLength, sum)
        return checksum(sum)
    }

    /** 표준 one's complement 체크섬 검증. 유효하면 true. */
    fun verifyChecksum(packet: ByteArray, start: Int, end: Int): Boolean =
        checksum(sumWords(packet, start, end)) == 0

    fun verifyIpv4HeaderChecksum(packet: ByteArray): Boolean {
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < IPV4_HEADER_SIZE || headerLength > packet.size) return false
        return verifyChecksum(packet, 0, headerLength)
    }

    private fun sumWords(block: ByteArray, start: Int, end: Int, initial: Int = 0): Int {
        var sum = initial
        var index = start
        val limit = end.coerceAtMost(block.size)
        while (index + 1 < limit) {
            sum += ((block[index].toInt() and 0xFF) shl 8) or (block[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < limit) {
            sum += (block[index].toInt() and 0xFF) shl 8
        }
        return sum
    }

    private fun checksum(sum: Int): Int {
        var folded = sum
        while (folded ushr 16 != 0) {
            folded = (folded and 0xFFFF) + (folded ushr 16)
        }
        return folded.inv() and 0xFFFF
    }

    private fun u16(buffer: ByteArray, offset: Int): Int {
        if (offset + 2 > buffer.size) return 0
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }

    private fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
        if (offset + 2 > buffer.size) return
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }
}
