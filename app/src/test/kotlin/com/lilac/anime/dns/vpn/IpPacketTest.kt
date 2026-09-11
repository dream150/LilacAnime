package com.lilac.anime.dns.vpn

import com.lilac.anime.dns.DnsMessageCodec
import com.lilac.anime.dns.DnsType
import com.lilac.anime.dns.TestDns
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class IpPacketTest {

    private val clientV4 = "10.0.0.5"
    private val proxyV4 = DnsVpnAddresses.PROXY_ADDRESS_V4
    private val clientV6 = "fd00:abcd::5"
    private val proxyV6 = DnsVpnAddresses.PROXY_ADDRESS_V6

    // ------------------------------------------------------------ builders

    private fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private fun ipv4HeaderChecksum(header: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i + 1 < 20) {
            sum += ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun ipv4UdpPacket(
        sourcePort: Int = 40000,
        destinationPort: Int = 53,
        payload: ByteArray,
        flagsAndFragment: Int = 0x4000,
        protocol: Int = 17,
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val out = ByteArray(total)
        out[0] = 0x45
        writeU16(out, 2, total)
        writeU16(out, 4, 0)
        writeU16(out, 6, flagsAndFragment)
        out[8] = 64
        out[9] = protocol.toByte()
        InetAddress.getByName(clientV4).address.copyInto(out, 12)
        InetAddress.getByName(proxyV4).address.copyInto(out, 16)
        writeU16(out, 10, ipv4HeaderChecksum(out))
        writeU16(out, 20, sourcePort)
        writeU16(out, 22, destinationPort)
        writeU16(out, 24, 8 + payload.size)
        writeU16(out, 26, 0)
        payload.copyInto(out, 28)
        return out
    }

    private fun ipv6UdpPacket(
        sourcePort: Int = 40000,
        destinationPort: Int = 53,
        payload: ByteArray,
    ): ByteArray {
        val udpLength = 8 + payload.size
        val out = ByteArray(40 + udpLength)
        out[0] = 0x60
        writeU16(out, 4, udpLength)
        out[6] = 17
        out[7] = 64
        InetAddress.getByName(clientV6).address.copyInto(out, 8)
        InetAddress.getByName(proxyV6).address.copyInto(out, 24)
        writeU16(out, 40, sourcePort)
        writeU16(out, 42, destinationPort)
        writeU16(out, 44, udpLength)
        payload.copyInto(out, 48)
        return out
    }

    private fun dnsQueryPayload(): ByteArray = TestDns.query("example.com", DnsType.A, id = 0x4242)

    // --------------------------------------------------------------- tests

    @Test
    fun parsesIpv4UdpPacket() {
        val payload = dnsQueryPayload()
        val packet = ipv4UdpPacket(payload = payload)

        val parsed = requireNotNull(IpPacket.parse(packet, packet.size))
        assertEquals(4, parsed.ipVersion)
        assertEquals(IpPacket.PROTO_UDP, parsed.protocol)
        assertEquals(40000, parsed.sourcePort)
        assertEquals(53, parsed.destinationPort)
        assertArrayEquals(InetAddress.getByName(clientV4).address, parsed.sourceAddress)
        assertArrayEquals(InetAddress.getByName(proxyV4).address, parsed.destinationAddress)
        assertArrayEquals(payload, parsed.payload)
        assertEquals(payload.size, parsed.payloadLength)
    }

    @Test
    fun parsesIpv6UdpPacket() {
        val payload = dnsQueryPayload()
        val packet = ipv6UdpPacket(payload = payload)

        val parsed = requireNotNull(IpPacket.parse(packet, packet.size))
        assertEquals(6, parsed.ipVersion)
        assertEquals(IpPacket.PROTO_UDP, parsed.protocol)
        assertEquals(53, parsed.destinationPort)
        assertArrayEquals(InetAddress.getByName(clientV6).address, parsed.sourceAddress)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun rejectsFragmentedPackets() {
        val payload = dnsQueryPayload()

        // MF(More Fragments) 플래그
        val moreFragments = ipv4UdpPacket(payload = payload, flagsAndFragment = 0x2000)
        assertNull(IpPacket.parse(moreFragments, moreFragments.size))

        // fragment offset != 0
        val offset = ipv4UdpPacket(payload = payload, flagsAndFragment = 0x0001)
        assertNull(IpPacket.parse(offset, offset.size))
    }

    @Test
    fun rejectsIpv6WithExtensionHeader() {
        // IPv6 + hop-by-hop extension header (nextHeader = 0)
        val out = ByteArray(48)
        out[0] = 0x60
        writeU16(out, 4, 8)
        out[6] = 0 // hop-by-hop
        out[7] = 64
        InetAddress.getByName(clientV6).address.copyInto(out, 8)
        InetAddress.getByName(proxyV6).address.copyInto(out, 24)
        writeU16(out, 40, 40000)
        writeU16(out, 42, 53)
        assertNull(IpPacket.parse(out, out.size))
    }

    @Test
    fun parsesTcpWithoutPayload() {
        val packet = ipv4UdpPacket(payload = ByteArray(0), protocol = IpPacket.PROTO_TCP)
        val parsed = requireNotNull(IpPacket.parse(packet, packet.size))
        assertEquals(IpPacket.PROTO_TCP, parsed.protocol)
        assertEquals(0, parsed.payloadLength)
    }

    @Test
    fun returnsNullForGarbageOrUnsupportedVersion() {
        assertNull(IpPacket.parse(ByteArray(10), 10))

        val notIp = ByteArray(40)
        notIp[0] = 0x50 // version 5
        assertNull(IpPacket.parse(notIp, notIp.size))

        val ipv4 = ipv4UdpPacket(payload = dnsQueryPayload())
        // 선언된 length 보다 짧은 버퍼
        assertNull(IpPacket.parse(ipv4, 30))
    }

    @Test
    fun buildsValidIpv4Reply() {
        val payload = dnsQueryPayload()
        val request = requireNotNull(IpPacket.parse(ipv4UdpPacket(payload = payload), 28 + payload.size))

        val responsePayload = payload.copyOf()
        responsePayload[2] = (responsePayload[2].toInt() or 0x80).toByte() // QR 비트

        val reply = requireNotNull(
            IpPacket.buildUdpReply(request, responsePayload, responsePayload.size)
        )

        assertTrue(IpPacket.verifyIpv4HeaderChecksum(reply))

        val udpLength = 8 + responsePayload.size
        assertEquals(0, IpPacket.udpChecksumIpv4(reply, 12, 16, 20, udpLength))

        val parsed = requireNotNull(IpPacket.parse(reply, reply.size))
        assertEquals(4, parsed.ipVersion)
        assertEquals(53, parsed.sourcePort)
        assertEquals(40000, parsed.destinationPort)
        assertArrayEquals(InetAddress.getByName(proxyV4).address, parsed.sourceAddress)
        assertArrayEquals(InetAddress.getByName(clientV4).address, parsed.destinationAddress)
        assertArrayEquals(responsePayload, parsed.payload)
    }

    @Test
    fun buildsValidIpv6Reply() {
        val payload = dnsQueryPayload()
        val request = requireNotNull(IpPacket.parse(ipv6UdpPacket(payload = payload), 48 + payload.size))

        val reply = requireNotNull(IpPacket.buildUdpReply(request, payload, payload.size))

        val udpLength = 8 + payload.size
        // IPv6 는 UDP 체크섬이 필수이며 0 이면 안 된다.
        assertEquals(0, IpPacket.udpChecksumIpv6(reply, 8, 24, 40, udpLength))
        assertFalse(DnsMessageCodec.u16(reply, 46) == 0)

        val parsed = requireNotNull(IpPacket.parse(reply, reply.size))
        assertEquals(6, parsed.ipVersion)
        assertEquals(53, parsed.sourcePort)
        assertArrayEquals(InetAddress.getByName(clientV6).address, parsed.destinationAddress)
    }

    @Test
    fun buildReplyRejectsAddressFamilyMismatch() {
        val payload = dnsQueryPayload()
        val request = requireNotNull(IpPacket.parse(ipv4UdpPacket(payload = payload), 28 + payload.size))
        val bogus = request.copy(
            sourceAddress = ByteArray(16),
            destinationAddress = ByteArray(16),
        )
        assertNull(IpPacket.buildUdpReply(bogus, payload, payload.size))
    }

    @Test
    fun maxUdpPayloadAccountsForHeaders() {
        assertEquals(1500 - 20 - 8, IpPacket.maxUdpPayload(1500, 4))
        assertEquals(1500 - 40 - 8, IpPacket.maxUdpPayload(1500, 6))
    }
}
