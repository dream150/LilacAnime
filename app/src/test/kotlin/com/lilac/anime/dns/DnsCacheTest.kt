package com.lilac.anime.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsCacheTest {

    private var now = 1_700_000_000_000L

    private fun newCache(maxEntries: Int = 8) = DnsCache(maxEntries = maxEntries, clock = { now })

    private fun positiveResponse(ttl: Long = 300): ByteArray = TestDns.response(
        id = 0x1234,
        questionName = "example.com",
        answers = listOf(TestDns.Answer(DnsType.A, ttl, TestDns.aRecord("1.1.1.1"))),
        compressAnswerNames = true,
    )

    private fun parse(bytes: ByteArray): DnsMessage =
        requireNotNull(DnsMessageCodec.parse(bytes, bytes.size))

    private fun put(cache: DnsCache, bytes: ByteArray, key: String = "example.com|1"): Boolean =
        cache.put(key, bytes, bytes.size, parse(bytes))

    @Test
    fun storesAndReturnsEntry() {
        val cache = newCache()
        val response = positiveResponse()
        assertTrue(put(cache, response))

        val entry = assertNotNull2(cache.get("example.com|1"))
        assertEquals(response.size, entry.length)
        assertFalse(entry.negative)
        assertEquals(1, cache.size)
    }

    @Test
    fun materializePatchesTransactionIdAndAgesTtl() {
        val cache = newCache()
        val response = positiveResponse(ttl = 300)
        put(cache, response)

        val entry = assertNotNull2(cache.get("example.com|1"))
        now += 10_000L
        val materialized = entry.materialize(0xBEEF, now)

        assertEquals(0xBEEF, DnsMessageCodec.u16(materialized, 0))
        val reparsed = parse(materialized)
        assertEquals(290L, reparsed.minTtl)
    }

    @Test
    fun entryExpiresWhenTtlElapses() {
        val cache = newCache()
        put(cache, positiveResponse(ttl = 300))

        now += 299_000L
        assertNotNull(cache.get("example.com|1"))

        now += 1_000L
        assertNull(cache.get("example.com|1"))
        assertEquals(0, cache.size)
    }

    @Test
    fun negativeResponseUsesShortTtl() {
        val cache = newCache()
        val nxdomain = TestDns.response(questionName = "nope.example", rcode = DnsRcode.NXDOMAIN)
        assertTrue(put(cache, nxdomain, key = "nope.example|1"))

        val entry = assertNotNull2(cache.get("nope.example|1"))
        assertTrue(entry.negative)

        now += 29_000L
        assertNotNull(cache.get("nope.example|1"))

        now += 2_000L
        assertNull(cache.get("nope.example|1"))
    }

    @Test
    fun servfailIsNotCached() {
        val cache = newCache()
        val servfail = TestDns.response(questionName = "example.com", rcode = DnsRcode.SERVFAIL)
        assertFalse(put(cache, servfail))
        assertEquals(0, cache.size)
    }

    @Test
    fun truncatedResponseIsNotCached() {
        val cache = newCache()
        val truncated = TestDns.response(
            questionName = "example.com",
            answers = listOf(TestDns.Answer(DnsType.A, 60, TestDns.aRecord("1.1.1.1"))),
            compressAnswerNames = true,
            truncated = true,
        )
        assertFalse(put(cache, truncated))
        assertEquals(0, cache.size)
    }

    @Test
    fun evictsEldestEntryWhenFull() {
        val cache = newCache(maxEntries = 2)
        put(cache, positiveResponse(), key = "a|1")
        put(cache, positiveResponse(), key = "b|1")
        put(cache, positiveResponse(), key = "c|1")

        assertEquals(2, cache.size)
        assertNull(cache.get("a|1"))
        assertNotNull(cache.get("b|1"))
        assertNotNull(cache.get("c|1"))
    }

    @Test
    fun clearRemovesAllEntries() {
        val cache = newCache()
        put(cache, positiveResponse())
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get("example.com|1"))
    }

    @Test
    fun statsTrackHitsAndMisses() {
        val cache = newCache()
        put(cache, positiveResponse())

        cache.get("example.com|1")
        cache.get("missing|1")

        val stats = cache.stats()
        assertEquals(1L, stats.hits)
        assertEquals(1L, stats.misses)
        assertEquals(1L, stats.stores)
        assertEquals(1, stats.entries)
    }

    private fun <T : Any> assertNotNull2(value: T?): T {
        assertNotNull(value)
        return requireNotNull(value)
    }
}
