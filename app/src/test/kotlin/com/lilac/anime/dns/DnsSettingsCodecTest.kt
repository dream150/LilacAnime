package com.lilac.anime.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsSettingsCodecTest {

    /** DataStore 왕복을 흉내낸다. */
    private fun persist(settings: DnsSettings): DnsSettings {
        val stored = DnsSettingsCodec.encode(settings)
        return DnsSettingsCodec.decode { key -> stored[key] }
    }

    @Test
    fun defaultsAreSystemAndDisabled() {
        val settings = DnsSettingsCodec.decode { null }
        assertEquals(DnsProviders.SYSTEM, settings.profileId)
        assertEquals(DnsMode.SYSTEM, settings.mode)
        assertFalse(settings.enabled)
        assertTrue(settings.fallbackToSystem)
    }

    @Test
    fun roundTripPreservesCustomDohSettings() {
        val original = DnsSettings(
            enabled = true,
            profileId = DnsProviders.CUSTOM,
            mode = DnsMode.DOH,
            primary = "1.1.1.1",
            secondary = "1.0.0.1",
            dohUrl = "https://cloudflare-dns.com/dns-query",
            bootstrap = "1.1.1.1",
            fallbackToSystem = false,
        )
        assertEquals(original, persist(original))
    }

    @Test
    fun systemSelectionAlwaysDisablesVpn() {
        val stored = persist(
            DnsSettings(
                enabled = true,
                profileId = DnsProviders.SYSTEM,
                mode = DnsMode.SYSTEM,
            )
        )
        assertFalse(stored.enabled)
        assertEquals(DnsMode.SYSTEM, stored.mode)
    }

    @Test
    fun unknownProfileIdFallsBackToSystem() {
        val stored = mapOf(
            DnsSettingsCodec.KEY_ENABLED to "true",
            DnsSettingsCodec.KEY_PROFILE_ID to "something-else",
        )
        val decoded = DnsSettingsCodec.decode { stored[it] }
        assertEquals(DnsProviders.SYSTEM, decoded.profileId)
        assertFalse(decoded.enabled)
    }

    @Test
    fun corruptModeFallsBackToUdpForNonSystemProfile() {
        val stored = mapOf(
            DnsSettingsCodec.KEY_PROFILE_ID to DnsProviders.CLOUDFLARE,
            DnsSettingsCodec.KEY_MODE to "NOT_A_MODE",
        )
        val decoded = DnsSettingsCodec.decode { stored[it] }
        assertEquals(DnsMode.UDP, decoded.mode)
    }

    @Test
    fun enabledButInvalidCustomSettingsAreDisabled() {
        val broken = DnsSettings(
            enabled = true,
            profileId = DnsProviders.CUSTOM,
            mode = DnsMode.UDP,
            primary = "not-an-ip",
        )
        val stored = persist(broken)
        assertFalse(stored.enabled)
        // 입력값은 보존되어 사용자가 수정할 수 있어야 한다.
        assertEquals("not-an-ip", stored.primary)
        assertEquals(DnsProviders.CUSTOM, stored.profileId)
    }

    @Test
    fun disabledSettingsResolveToSystemProfile() {
        val settings = DnsSettings(enabled = false, profileId = DnsProviders.GOOGLE, mode = DnsMode.UDP)
        val profile = DnsProfileResolver.resolve(settings)
        assertEquals(DnsProviders.SYSTEM, profile.id)
        assertEquals(DnsMode.SYSTEM, profile.mode)
        assertFalse(profile.usesVpn)
    }

    @Test
    fun enabledBuiltInProfileKeepsPresetAddresses() {
        val settings = DnsSettings(enabled = true, profileId = DnsProviders.QUAD9, mode = DnsMode.UDP)
        val profile = DnsProfileResolver.resolve(settings)
        assertEquals("9.9.9.9", profile.primary)
        assertEquals("149.112.112.112", profile.secondary)
        assertEquals(DnsMode.UDP, profile.mode)
        assertTrue(profile.usesVpn)
    }

    @Test
    fun builtInProfileCanSwitchProtocolWithoutLosingAddresses() {
        val settings = DnsSettings(enabled = true, profileId = DnsProviders.CLOUDFLARE, mode = DnsMode.DOH)
        val profile = DnsProfileResolver.resolve(settings)
        assertEquals(DnsMode.DOH, profile.mode)
        assertEquals("https://cloudflare-dns.com/dns-query", profile.dohUrl)
        assertEquals("1.1.1.1", profile.primary)
    }

    @Test
    fun providerTransitionsKeepConsistentState() {
        // SYSTEM -> Cloudflare
        var settings = persist(DnsSettingsCodec.decode { null })
        assertFalse(settings.enabled)

        settings = persist(
            settings.copy(
                enabled = true,
                profileId = DnsProviders.CLOUDFLARE,
                mode = DnsMode.UDP,
            )
        )
        assertEquals(DnsProviders.CLOUDFLARE, settings.profileId)
        assertTrue(settings.enabled)
        assertEquals(DnsMode.UDP, settings.mode)

        // Cloudflare -> Google
        settings = persist(settings.copy(profileId = DnsProviders.GOOGLE))
        assertEquals(DnsProviders.GOOGLE, settings.profileId)
        assertTrue(settings.enabled)

        // Google -> Custom (입력 전에는 활성화되지 않는다)
        settings = persist(settings.copy(profileId = DnsProviders.CUSTOM, enabled = true))
        assertEquals(DnsProviders.CUSTOM, settings.profileId)
        assertFalse(settings.enabled)

        // Custom 값 입력 후 활성화
        settings = persist(settings.copy(primary = "9.9.9.9", enabled = true))
        assertTrue(settings.enabled)
        assertEquals("9.9.9.9", settings.primary)

        // Custom -> SYSTEM
        settings = persist(
            settings.copy(
                profileId = DnsProviders.SYSTEM,
                mode = DnsMode.SYSTEM,
                enabled = false,
            )
        )
        assertEquals(DnsProviders.SYSTEM, settings.profileId)
        assertFalse(settings.enabled)
    }
}
