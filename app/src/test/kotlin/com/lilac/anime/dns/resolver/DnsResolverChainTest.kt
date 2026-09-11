package com.lilac.anime.dns.resolver

import com.lilac.anime.dns.DnsMode
import com.lilac.anime.dns.DnsProfile
import com.lilac.anime.dns.DnsProviders
import com.lilac.anime.dns.DnsType
import com.lilac.anime.dns.TestDns
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsResolverChainTest {

    private fun query() = TestDns.query("example.com", DnsType.A)

    @Test
    fun fallsBackToNextResolver() = runBlocking {
        val failing = FakeResolver("primary") { _, _ -> null }
        val working = FakeResolver("secondary") { q, l -> q.copyOf(l) }

        val chain = DnsResolverChain(listOf(failing, working))
        val q = query()

        assertNotNull(chain.resolve(q, q.size))
        assertEquals(1, failing.calls)
        assertEquals(1, working.calls)
    }

    @Test
    fun returnsNullWhenAllResolversFail() = runBlocking {
        val chain = DnsResolverChain(listOf(FakeResolver("a"), FakeResolver("b")))
        val q = query()
        assertNull(chain.resolve(q, q.size))
    }

    @Test
    fun resolverExceptionDoesNotEscape() = runBlocking {
        val throwing = object : DnsResolver {
            override val label = "boom"
            override suspend fun resolve(query: ByteArray, length: Int): ByteArray? =
                throw IllegalStateException("boom")
        }
        val working = FakeResolver("ok") { q, l -> q.copyOf(l) }

        val chain = DnsResolverChain(listOf(throwing, working))
        val q = query()
        assertNotNull(chain.resolve(q, q.size))
    }

    @Test
    fun emptyChainResolvesToNull() = runBlocking {
        val q = query()
        assertNull(DnsResolverChain(emptyList()).resolve(q, q.size))
    }

    @Test
    fun systemResolverWithoutServersFailsSafely() = runBlocking {
        val resolver = SystemDnsResolver(serversProvider = { emptyList() })
        val q = query()
        assertNull(resolver.resolve(q, q.size))
    }

    @Test
    fun systemResolverSwallowsProviderFailures() = runBlocking {
        val resolver = SystemDnsResolver(serversProvider = { throw IllegalStateException("no network") })
        val q = query()
        assertNull(resolver.resolve(q, q.size))
    }

    @Test
    fun factoryUsesOnlySelectedProviderWhenFallbackDisabled() {
        val profile = DnsProviders.builtIn(DnsProviders.CLOUDFLARE)!!

        val withoutFallback = DnsResolverFactory.build(
            profile = profile,
            fallbackToSystem = false,
            protector = SocketProtector.NONE,
            systemDnsProvider = { emptyList() },
        )
        assertEquals(listOf("udp"), withoutFallback.labels)

        val withFallback = DnsResolverFactory.build(
            profile = profile,
            fallbackToSystem = true,
            protector = SocketProtector.NONE,
            systemDnsProvider = { emptyList() },
        )
        assertEquals(listOf("udp", "system"), withFallback.labels)
    }

    @Test
    fun factoryBuildsDohChainWithUdpFallback() {
        val profile = DnsProviders.builtIn(DnsProviders.GOOGLE)!!.copy(mode = DnsMode.DOH)

        val chain = DnsResolverFactory.build(
            profile = profile,
            fallbackToSystem = false,
            protector = SocketProtector.NONE,
            systemDnsProvider = { emptyList() },
        )
        assertEquals(listOf("doh", "udp-fallback"), chain.labels)
    }

    @Test
    fun factorySkipsDohWhenUrlIsMissing() {
        val profile = DnsProfile(
            id = DnsProviders.CUSTOM,
            name = "사용자 지정",
            mode = DnsMode.DOH,
            primary = "1.1.1.1",
            dohUrl = null,
            builtIn = false,
        )

        val chain = DnsResolverFactory.build(
            profile = profile,
            fallbackToSystem = false,
            protector = SocketProtector.NONE,
            systemDnsProvider = { emptyList() },
        )
        assertEquals(listOf("udp-fallback"), chain.labels)
    }

    @Test
    fun factoryProducesEmptyChainForSystemProfile() {
        val profile = DnsProviders.builtIn(DnsProviders.SYSTEM)!!
        val chain = DnsResolverFactory.build(
            profile = profile,
            fallbackToSystem = false,
            protector = SocketProtector.NONE,
            systemDnsProvider = { emptyList() },
        )
        assertTrue(chain.isEmpty)
    }

    @Test
    fun ipAddressHelperRejectsHostnames() {
        assertNotNull(IpAddresses.literal("1.1.1.1"))
        assertNotNull(IpAddresses.literal("2001:4860:4860::8888"))
        assertNull(IpAddresses.literal("dns.google"))
        assertNull(IpAddresses.literal(null))
        assertNull(IpAddresses.literal("  "))
        assertEquals(2, IpAddresses.literals(listOf("1.1.1.1", "1.0.0.1", "dns.google")).size)
    }
}
