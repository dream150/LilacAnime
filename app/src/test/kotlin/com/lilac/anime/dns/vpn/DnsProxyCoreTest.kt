package com.lilac.anime.dns.vpn

import com.lilac.anime.dns.DnsCache
import com.lilac.anime.dns.DnsMessageCodec
import com.lilac.anime.dns.DnsType
import com.lilac.anime.dns.TestDns
import com.lilac.anime.dns.resolver.DnsResolver
import com.lilac.anime.dns.resolver.DnsResolverChain
import com.lilac.anime.dns.resolver.FakeResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsProxyCoreTest {

    private fun core(
        cache: DnsCache,
        stats: DnsStats,
        resolver: DnsResolver,
    ) = DnsProxyCore(
        cache = cache,
        resolversProvider = { DnsResolverChain(listOf(resolver)) },
        stats = stats,
    )

    private fun answerFor(ttl: Long = 600): (ByteArray, Int) -> ByteArray? = { query, length ->
        TestDns.response(
            id = DnsMessageCodec.u16(query, 0),
            questionName = "example.com",
            answers = listOf(TestDns.Answer(DnsType.A, ttl, TestDns.aRecord("1.2.3.4"))),
            compressAnswerNames = true,
        )
    }

    @Test
    fun firstQueryGoesToResolverAndSecondIsServedFromCache() = runBlocking {
        val cache = DnsCache()
        val stats = DnsStats()
        val resolver = FakeResolver("fake", answerFor())
        val proxy = core(cache, stats, resolver)

        val first = TestDns.query("example.com", DnsType.A, id = 0x0101)
        val firstResponse = proxy.handleQuery(first, first.size)
        assertNotNull(firstResponse)
        assertEquals(1, resolver.calls)

        val second = TestDns.query("example.com", DnsType.A, id = 0x0202)
        val secondResponse = requireNotNull(proxy.handleQuery(second, second.size))

        // 캐시 적중 -> resolver 호출이 늘지 않는다.
        assertEquals(1, resolver.calls)
        // 캐시 응답의 트랜잭션 ID 는 새 질의 것을 따른다.
        assertEquals(0x0202, DnsMessageCodec.u16(secondResponse, 0))

        val snapshot = stats.snapshot(cache.size)
        assertEquals(2L, snapshot.queries)
        assertEquals(1L, snapshot.cacheHits)
        assertEquals(1L, snapshot.replies)
    }

    @Test
    fun returnsNullWhenResolverFails() = runBlocking {
        val cache = DnsCache()
        val stats = DnsStats()
        val proxy = core(cache, stats, FakeResolver("failing"))

        val query = TestDns.query("example.com", DnsType.A)
        assertNull(proxy.handleQuery(query, query.size))
        assertEquals(1L, stats.snapshot(0).failures)
    }

    @Test
    fun dropsQueryShorterThanHeader() = runBlocking {
        val stats = DnsStats()
        val proxy = core(DnsCache(), stats, FakeResolver("fake", answerFor()))

        val short = ByteArray(5)
        assertNull(proxy.handleQuery(short, short.size))
        assertEquals(1L, stats.snapshot(0).dropped)
    }

    @Test
    fun doesNotCacheWhenQuestionSectionIsMissing() = runBlocking {
        val cache = DnsCache()
        val stats = DnsStats()
        val resolver = FakeResolver("fake") { query, length -> query.copyOf(length) }
        val proxy = core(cache, stats, resolver)

        // QDCOUNT = 0 인 헤더만 있는 메시지
        val headerOnly = ByteArray(12)
        proxy.handleQuery(headerOnly, headerOnly.size)
        proxy.handleQuery(headerOnly, headerOnly.size)

        assertEquals(2, resolver.calls)
        assertEquals(0, cache.size)
    }

    @Test
    fun malformedButCorrectlySizedQueryIsForwarded() = runBlocking {
        val stats = DnsStats()
        val resolver = FakeResolver("fake") { query, length -> query.copyOf(length) }
        val proxy = core(DnsCache(), stats, resolver)

        // QDCOUNT = 1 이지만 question 데이터가 없는 메시지 (파싱 실패)
        val malformed = ByteArray(12).also { it[5] = 1 }
        val response = proxy.handleQuery(malformed, malformed.size)

        assertNotNull(response)
        assertEquals(1, resolver.calls)
    }

    @Test
    fun originalQueryBytesAreNotMutatedOnCacheHit() = runBlocking {
        val cache = DnsCache()
        val stats = DnsStats()
        val resolver = FakeResolver("fake", answerFor())
        val proxy = core(cache, stats, resolver)

        val query = TestDns.query("example.com", DnsType.A, id = 0x0A0A)
        proxy.handleQuery(query, query.size)
        proxy.handleQuery(query, query.size)

        // 첫 질의 버퍼는 그대로 유지된다.
        assertEquals(0x0A0A, DnsMessageCodec.u16(query, 0))
    }
}
