package com.lilac.anime.dns

/**
 * DNS 설정 입력값 검증.
 *
 * Android 의존성이 없어 JVM 단위 테스트에서 그대로 검증할 수 있다.
 * `InetAddress.getByName()` 은 hostname 을 DNS 로 조회해 버리므로 사용하지 않고
 * 직접 파싱한다.
 */
object DnsValidation {

    fun isValidIpv4(raw: String): Boolean {
        val parts = raw.trim().split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            if (part.isEmpty() || part.length > 3) return@all false
            if (!part.all { it in '0'..'9' }) return@all false
            // "01" 같은 8진수 해석 모호성을 막기 위해 선행 0 을 허용하지 않는다.
            if (part.length > 1 && part[0] == '0') return@all false
            val value = part.toIntOrNull() ?: return@all false
            value in 0..255
        }
    }

    fun isValidIpv6(raw: String): Boolean {
        var text = raw.trim()
        if (text.length < 2) return false
        if (text.startsWith("[") && text.endsWith("]")) text = text.substring(1, text.length - 1)
        if (text.contains('%')) return false // zone id 는 지원하지 않는다
        if (text.contains(":::")) return false
        if (!text.contains(':')) return false

        val compressed = text.contains("::")
        val parts: List<String>
        if (compressed) {
            if (text.indexOf("::") != text.lastIndexOf("::")) return false
            val index = text.indexOf("::")
            val head = text.substring(0, index)
            val tail = text.substring(index + 2)
            val headParts = if (head.isEmpty()) emptyList() else head.split(':')
            val tailParts = if (tail.isEmpty()) emptyList() else tail.split(':')
            if (headParts.any { it.isEmpty() } || tailParts.any { it.isEmpty() }) return false
            parts = headParts + tailParts
            if (parts.size >= 8) return false
        } else {
            parts = text.split(':')
            if (parts.size != 8) return false
        }

        var groups = 0
        parts.forEachIndexed { index, part ->
            val isLast = index == parts.lastIndex
            if (isLast && part.contains('.')) {
                if (!isValidIpv4(part)) return false
                groups += 2
            } else {
                if (part.isEmpty() || part.length > 4) return false
                if (!part.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return false
                groups += 1
            }
        }
        return if (compressed) groups <= 7 else groups == 8
    }

    fun isValidIp(raw: String): Boolean = isValidIpv4(raw) || isValidIpv6(raw)

    fun isValidHostname(raw: String): Boolean {
        val text = raw.trim().trimEnd('.')
        if (text.isEmpty() || text.length > 253) return false
        val labels = text.split('.')
        // bootstrap/DoH 용도이므로 완전한 FQDN 만 허용한다.
        if (labels.size < 2) return false
        return labels.all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                !label.startsWith("-") && !label.endsWith("-") &&
                label.all { it.isAsciiLetterOrDigit() || it == '-' }
        }
    }

    /**
     * RFC 8484 DoH endpoint 검증.
     *
     * 평문 HTTP endpoint 는 명시적으로 허용하지 않는다.
     */
    fun isValidDohUrl(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 2048) return false
        if (!text.startsWith("https://", ignoreCase = true)) return false
        val rest = text.substring("https://".length)
        if (rest.isEmpty()) return false

        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        if (authority.isEmpty()) return false
        if (authority.contains('@')) return false // userinfo 금지

        val host: String
        val portText: String?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close <= 0) return false
            host = authority.substring(1, close)
            val rest2 = authority.substring(close + 1)
            portText = if (rest2.startsWith(":")) rest2.substring(1) else null
            if (rest2.isNotEmpty() && !rest2.startsWith(":")) return false
        } else {
            host = authority.substringBefore(':')
            portText = if (authority.contains(':')) authority.substringAfter(':') else null
        }

        if (host.isEmpty()) return false
        if (!isValidHostname(host) && !isValidIpv4(host) && !isValidIpv6(host)) return false

        if (portText != null) {
            val port = portText.toIntOrNull() ?: return false
            if (port !in 1..65535) return false
        }
        return true
    }

    /** 사용자가 직접 입력하는 값에 대한 검증. */
    fun validateProfile(profile: DnsProfile): List<String> = when (profile.mode) {
        DnsMode.SYSTEM -> emptyList()

        DnsMode.UDP -> buildList {
            if (profile.primary.isNullOrBlank()) {
                add("기본 DNS 주소를 입력하세요.")
            } else if (!isValidIp(profile.primary)) {
                add("기본 DNS 주소가 올바르지 않습니다.")
            }
            if (!profile.secondary.isNullOrBlank() && !isValidIp(profile.secondary)) {
                add("보조 DNS 주소가 올바르지 않습니다.")
            }
        }

        DnsMode.DOH -> buildList {
            if (profile.dohUrl.isNullOrBlank()) {
                add("DoH URL을 입력하세요.")
            } else if (!isValidDohUrl(profile.dohUrl)) {
                add("DoH URL은 https:// 형식의 주소여야 합니다.")
            }
            // DoH 를 쓰더라도 UDP fallback 주소가 있으면 검증한다.
            if (!profile.primary.isNullOrBlank() && !isValidIp(profile.primary)) {
                add("기본 DNS 주소가 올바르지 않습니다.")
            }
            if (!profile.secondary.isNullOrBlank() && !isValidIp(profile.secondary)) {
                add("보조 DNS 주소가 올바르지 않습니다.")
            }
            if (!profile.bootstrap.isNullOrBlank() && !isValidIp(profile.bootstrap)) {
                add("Bootstrap DNS는 IP 주소여야 합니다.")
            }
        }
    }

    fun validate(settings: DnsSettings): List<String> {
        if (settings.profileId == DnsProviders.SYSTEM && settings.mode == DnsMode.SYSTEM) {
            return emptyList()
        }
        return validateProfile(DnsProfileResolver.resolve(settings.copy(enabled = true)))
    }

    /** 저장 직전에 정규화한다. (앞뒤 공백 제거, 빈 값은 null) */
    fun normalize(settings: DnsSettings): DnsSettings = settings.copy(
        primary = settings.primary?.trim()?.takeIf { it.isNotEmpty() },
        secondary = settings.secondary?.trim()?.takeIf { it.isNotEmpty() },
        dohUrl = settings.dohUrl?.trim()?.takeIf { it.isNotEmpty() },
        bootstrap = settings.bootstrap?.trim()?.takeIf { it.isNotEmpty() },
    )
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
