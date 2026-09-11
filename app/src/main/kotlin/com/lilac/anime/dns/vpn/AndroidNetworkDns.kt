package com.lilac.anime.dns.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress

/**
 * VPN 바깥(underlying) 네트워크의 DNS 서버를 찾는다.
 *
 * 핵심: 우리 VPN 의 프록시 주소(10.111.222.2 / fd00:...:2)를 절대 돌려주면 안 된다.
 * 그 주소로 질의하면 다시 우리 TUN 으로 들어와 무한 루프가 된다.
 */
object AndroidNetworkDns {

    fun underlyingServers(context: Context): List<InetAddress> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()

        val found = LinkedHashSet<InetAddress>()
        val networks = try {
            manager.allNetworks
        } catch (_: Throwable) {
            emptyArray()
        }

        for (network in networks) {
            val capabilities = try {
                manager.getNetworkCapabilities(network)
            } catch (_: Throwable) {
                null
            } ?: continue

            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue

            val properties = try {
                manager.getLinkProperties(network)
            } catch (_: Throwable) {
                null
            } ?: continue

            properties.dnsServers.forEach { address ->
                if (!DnsVpnAddresses.isProxyAddress(address)) found += address
            }
        }

        return found.toList()
    }

    /** 현재 기본 네트워크가 VPN 인지 (다른 VPN 충돌 안내용). */
    fun hasActiveForeignVpn(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = try {
            manager.activeNetwork
        } catch (_: Throwable) {
            null
        } ?: return false
        val capabilities = try {
            manager.getNetworkCapabilities(network)
        } catch (_: Throwable) {
            null
        } ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
