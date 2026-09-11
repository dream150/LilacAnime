package com.lilac.anime.dns.vpn

import com.lilac.anime.dns.resolver.IpAddresses
import java.net.InetAddress

/**
 * DNS 전용 VPN 의 주소/MTU 정의.
 *
 * 전체 트래픽을 프록시하지 않는다. TUN 주소와 **DNS 프록시 주소만** 라우팅해서
 * 다른 인터넷 트래픽은 평소처럼 기기 네트워크로 나가게 한다.
 *
 * - 10.111.222.1/32  : TUN 인터페이스 주소
 * - 10.111.222.2     : 앱들이 DNS 서버로 인식하는 프록시 주소 (이 /32 만 TUN 으로 라우팅)
 * - IPv6 도 동일한 구조 (AAAA 질의도 가로채기 위함)
 */
object DnsVpnAddresses {
    const val TUN_ADDRESS_V4 = "10.111.222.1"
    const val PROXY_ADDRESS_V4 = "10.111.222.2"
    const val TUN_ADDRESS_V6 = "fd00:1:fd00:1:fd00:1:fd00:1"
    const val PROXY_ADDRESS_V6 = "fd00:1:fd00:1:fd00:1:fd00:2"
    const val PREFIX_V4 = 32
    const val PREFIX_V6 = 128
    const val MTU = 1500

    val tunV4: InetAddress = requireNotNull(IpAddresses.literal(TUN_ADDRESS_V4))
    val proxyV4: InetAddress = requireNotNull(IpAddresses.literal(PROXY_ADDRESS_V4))
    val proxyV4Bytes: ByteArray get() = proxyV4.address
    val tunV6: InetAddress = requireNotNull(IpAddresses.literal(TUN_ADDRESS_V6))
    val proxyV6: InetAddress = requireNotNull(IpAddresses.literal(PROXY_ADDRESS_V6))
    val proxyV6Bytes: ByteArray get() = proxyV6.address

    /** 우리 DNS 프록시 주소인지. (underlying DNS 서버 목록에서 제외할 때 사용) */
    fun isProxyAddress(address: InetAddress): Boolean =
        address == proxyV4 || address == proxyV6
}
