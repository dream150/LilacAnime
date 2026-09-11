package com.lilac.anime.dns

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsValidationTest {

    @Test
    fun ipv4Validation() {
        assertTrue(DnsValidation.isValidIpv4("1.1.1.1"))
        assertTrue(DnsValidation.isValidIpv4("255.255.255.255"))
        assertTrue(DnsValidation.isValidIpv4("0.0.0.0"))
        assertTrue(DnsValidation.isValidIpv4(" 8.8.4.4 "))

        assertFalse(DnsValidation.isValidIpv4("256.1.1.1"))
        assertFalse(DnsValidation.isValidIpv4("1.1.1"))
        assertFalse(DnsValidation.isValidIpv4("1.1.1.1.1"))
        assertFalse(DnsValidation.isValidIpv4("1.1.1.01"))
        assertFalse(DnsValidation.isValidIpv4("a.b.c.d"))
        assertFalse(DnsValidation.isValidIpv4("-1.1.1.1"))
        assertFalse(DnsValidation.isValidIpv4(""))
        assertFalse(DnsValidation.isValidIpv4("1.1.1.1:53"))
    }

    @Test
    fun ipv6Validation() {
        assertTrue(DnsValidation.isValidIpv6("::"))
        assertTrue(DnsValidation.isValidIpv6("::1"))
        assertTrue(DnsValidation.isValidIpv6("2001:db8::1"))
        assertTrue(DnsValidation.isValidIpv6("2001:0db8:0000:0000:0000:0000:0000:0001"))
        assertTrue(DnsValidation.isValidIpv6("::ffff:192.168.0.1"))
        assertTrue(DnsValidation.isValidIpv6("fe80::1"))
        assertTrue(DnsValidation.isValidIpv6("[2001:db8::1]"))
        assertTrue(DnsValidation.isValidIpv6("FD00:1:FD00:1:FD00:1:FD00:1"))

        assertFalse(DnsValidation.isValidIpv6("::: "))
        assertFalse(DnsValidation.isValidIpv6(":::"))
        assertFalse(DnsValidation.isValidIpv6("1::2::3"))
        assertFalse(DnsValidation.isValidIpv6("1:2:3:4:5:6:7"))
        assertFalse(DnsValidation.isValidIpv6("1:2:3:4:5:6:7:8:9"))
        assertFalse(DnsValidation.isValidIpv6("fe80::1%eth0"))
        assertFalse(DnsValidation.isValidIpv6("gggg::1"))
        assertFalse(DnsValidation.isValidIpv6("12345::1"))
        assertFalse(DnsValidation.isValidIpv6(""))
    }

    @Test
    fun ipValidationAcceptsBothFamilies() {
        assertTrue(DnsValidation.isValidIp("1.1.1.1"))
        assertTrue(DnsValidation.isValidIp("2001:4860:4860::8888"))
        assertFalse(DnsValidation.isValidIp("dns.google"))
    }

    @Test
    fun hostnameValidation() {
        assertTrue(DnsValidation.isValidHostname("dns.google"))
        assertTrue(DnsValidation.isValidHostname("cloudflare-dns.com"))
        assertTrue(DnsValidation.isValidHostname("a.b.c.d"))
        assertTrue(DnsValidation.isValidHostname("dns.google."))

        assertFalse(DnsValidation.isValidHostname("localhost"))
        assertFalse(DnsValidation.isValidHostname("-bad.example.com"))
        assertFalse(DnsValidation.isValidHostname("bad-.example.com"))
        assertFalse(DnsValidation.isValidHostname("bad_host.example.com"))
        assertFalse(DnsValidation.isValidHostname(""))
        assertFalse(DnsValidation.isValidHostname("a".repeat(64) + ".example.com"))
    }

    @Test
    fun dohUrlValidation() {
        assertTrue(DnsValidation.isValidDohUrl("https://cloudflare-dns.com/dns-query"))
        assertTrue(DnsValidation.isValidDohUrl("https://dns.google/dns-query"))
        assertTrue(DnsValidation.isValidDohUrl("https://dns.google"))
        assertTrue(DnsValidation.isValidDohUrl("https://dns.google:443/dns-query"))
        assertTrue(DnsValidation.isValidDohUrl("https://1.1.1.1/dns-query"))
        assertTrue(DnsValidation.isValidDohUrl("https://[2606:4700:4700::1111]/dns-query"))

        // 평문 HTTP 는 허용하지 않는다.
        assertFalse(DnsValidation.isValidDohUrl("http://dns.google/dns-query"))
        assertFalse(DnsValidation.isValidDohUrl("https://user:pass@dns.google/dns-query"))
        assertFalse(DnsValidation.isValidDohUrl("https://dns.google:99999/dns-query"))
        assertFalse(DnsValidation.isValidDohUrl("https://dns.google:abc/dns-query"))
        assertFalse(DnsValidation.isValidDohUrl("https://"))
        assertFalse(DnsValidation.isValidDohUrl("https://localhost/dns-query"))
        assertFalse(DnsValidation.isValidDohUrl(""))
        assertFalse(DnsValidation.isValidDohUrl("dns.google"))
    }

    @Test
    fun systemProfileNeedsNoValidation() {
        val settings = DnsSettings(profileId = DnsProviders.SYSTEM, mode = DnsMode.SYSTEM)
        assertTrue(DnsValidation.validate(settings).isEmpty())
    }

    @Test
    fun builtInProfilesAreValidForBothProtocols() {
        for (profile in DnsProviders.builtIn) {
            if (profile.id == DnsProviders.SYSTEM) continue
            assertTrue(
                "${profile.id} UDP",
                DnsValidation.validate(DnsSettings(profileId = profile.id, mode = DnsMode.UDP)).isEmpty(),
            )
            assertTrue(
                "${profile.id} DoH",
                DnsValidation.validate(DnsSettings(profileId = profile.id, mode = DnsMode.DOH)).isEmpty(),
            )
        }
    }

    @Test
    fun customUdpRequiresPrimaryAddress() {
        val missing = DnsSettings(
            profileId = DnsProviders.CUSTOM,
            mode = DnsMode.UDP,
            primary = null,
        )
        assertFalse(DnsValidation.validate(missing).isEmpty())

        val invalid = missing.copy(primary = "1.1.1")
        assertFalse(DnsValidation.validate(invalid).isEmpty())

        val invalidSecondary = missing.copy(primary = "1.1.1.1", secondary = "nope")
        assertFalse(DnsValidation.validate(invalidSecondary).isEmpty())

        val valid = missing.copy(primary = "1.1.1.1", secondary = "1.0.0.1")
        assertTrue(DnsValidation.validate(valid).isEmpty())
    }

    @Test
    fun customDohRequiresHttpsUrl() {
        val missing = DnsSettings(profileId = DnsProviders.CUSTOM, mode = DnsMode.DOH)
        assertFalse(DnsValidation.validate(missing).isEmpty())

        val plainHttp = missing.copy(dohUrl = "http://example.com/dns-query")
        assertFalse(DnsValidation.validate(plainHttp).isEmpty())

        val invalidBootstrap = missing.copy(
            dohUrl = "https://example.com/dns-query",
            bootstrap = "bootstrap.example.com",
        )
        assertFalse(DnsValidation.validate(invalidBootstrap).isEmpty())

        val valid = missing.copy(
            dohUrl = "https://example.com/dns-query",
            bootstrap = "1.1.1.1",
        )
        assertTrue(DnsValidation.validate(valid).isEmpty())
    }

    @Test
    fun normalizeTrimsAndDropsEmptyValues() {
        val normalized = DnsValidation.normalize(
            DnsSettings(
                profileId = DnsProviders.CUSTOM,
                mode = DnsMode.UDP,
                primary = "  1.1.1.1  ",
                secondary = "   ",
                dohUrl = "",
                bootstrap = null,
            )
        )
        assertTrue(normalized.primary == "1.1.1.1")
        assertTrue(normalized.secondary == null)
        assertTrue(normalized.dohUrl == null)
        assertTrue(normalized.bootstrap == null)
    }

    @Test
    fun invalidIpv6IsNotTreatedAsHostname() {
        assertFalse(DnsValidation.isValidHostname("2001:db8::1"))
        assertFalse(DnsValidation.isValidDohUrl("https://[not-an-ip]/dns-query"))
    }
}
