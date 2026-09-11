package com.lilac.anime.dns

/**
 * LilacAnime DNS 기능의 도메인 모델.
 *
 * Android 일반 앱은 루팅 없이 시스템 DNS 설정을 직접 바꿀 수 없다. 이 기능은
 * [android.net.VpnService] 로 DNS 질의만 가로채는 로컬 proxy 를 만든다.
 */

enum class DnsMode {
    /** Android 기본 DNS 사용. VPN 을 만들지 않는다. */
    SYSTEM,

    /** UDP/53 기반 resolver (Primary -> Secondary -> System fallback). */
    UDP,

    /** DNS-over-HTTPS (RFC 8484) 기반 resolver. */
    DOH,
}

/** 사용자가 고를 수 있는 DNS provider 정의. */
data class DnsProfile(
    val id: String,
    val name: String,
    val mode: DnsMode,
    val primary: String? = null,
    val secondary: String? = null,
    val dohUrl: String? = null,
    /** DoH 서버 hostname 을 풀기 위한 bootstrap DNS (IP 문자열). */
    val bootstrap: String? = null,
    val builtIn: Boolean = true,
) {
    /** 이 profile 로 VPN 을 운영할 수 있는지. SYSTEM 은 VPN 대상이 아니다. */
    val usesVpn: Boolean get() = mode != DnsMode.SYSTEM
}

object DnsProviders {
    const val SYSTEM = "system"
    const val CLOUDFLARE = "cloudflare"
    const val GOOGLE = "google"
    const val QUAD9 = "quad9"
    const val ADGUARD = "adguard"
    const val CUSTOM = "custom"

    /**
     * 내장 provider 목록.
     *
     * DoH endpoint 와 IP 는 각 사업자의 공식 문서 기준이다.
     * - Cloudflare: https://developers.cloudflare.com/1.1.1.1/encryption/dns-over-https/
     * - Google: https://developers.google.com/speed/public-dns/docs/doh
     * - Quad9: https://www.quad9.net/service/service-addresses-and-features/
     * - AdGuard: https://adguard-dns.io/kb/general/dns-providers/
     */
    val builtIn: List<DnsProfile> = listOf(
        DnsProfile(
            id = SYSTEM,
            name = "시스템 기본",
            mode = DnsMode.SYSTEM,
        ),
        DnsProfile(
            id = CLOUDFLARE,
            name = "Cloudflare",
            mode = DnsMode.UDP,
            primary = "1.1.1.1",
            secondary = "1.0.0.1",
            dohUrl = "https://cloudflare-dns.com/dns-query",
            bootstrap = "1.1.1.1",
        ),
        DnsProfile(
            id = GOOGLE,
            name = "Google",
            mode = DnsMode.UDP,
            primary = "8.8.8.8",
            secondary = "8.8.4.4",
            dohUrl = "https://dns.google/dns-query",
            bootstrap = "8.8.8.8",
        ),
        DnsProfile(
            id = QUAD9,
            name = "Quad9",
            mode = DnsMode.UDP,
            primary = "9.9.9.9",
            secondary = "149.112.112.112",
            dohUrl = "https://dns.quad9.net/dns-query",
            bootstrap = "9.9.9.9",
        ),
        DnsProfile(
            id = ADGUARD,
            name = "AdGuard",
            mode = DnsMode.UDP,
            primary = "94.140.14.14",
            secondary = "94.140.15.15",
            dohUrl = "https://dns.adguard-dns.com/dns-query",
            bootstrap = "94.140.14.14",
        ),
    )

    fun builtIn(id: String?): DnsProfile? = builtIn.firstOrNull { it.id == id }

    fun isBuiltIn(id: String): Boolean = builtIn.any { it.id == id }
}

/**
 * 저장된 설정을 실제로 사용할 resolver 정의로 바꾼다.
 *
 * 기본값은 반드시 SYSTEM 이다.
 */
object DnsProfileResolver {
    fun resolve(settings: DnsSettings): DnsProfile {
        if (!settings.enabled) {
            return DnsProviders.builtIn(DnsProviders.SYSTEM)!!
        }
        if (settings.profileId == DnsProviders.CUSTOM) {
            return DnsProfile(
                id = DnsProviders.CUSTOM,
                name = "사용자 지정",
                mode = settings.mode,
                primary = settings.primary,
                secondary = settings.secondary,
                dohUrl = settings.dohUrl,
                bootstrap = settings.bootstrap,
                builtIn = false,
            )
        }
        val preset = DnsProviders.builtIn(settings.profileId)
            ?: return DnsProviders.builtIn(DnsProviders.SYSTEM)!!
        // 프로토콜만 사용자가 UDP/DOH 로 바꿀 수 있다.
        return preset.copy(mode = settings.mode, bootstrap = settings.bootstrap ?: preset.bootstrap)
    }
}
