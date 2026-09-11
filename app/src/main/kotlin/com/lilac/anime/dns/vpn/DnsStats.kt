package com.lilac.anime.dns.vpn

import java.util.concurrent.atomic.AtomicLong

/**
 * DNS 프록시 동작 카운터.
 *
 * hostname/질의 내용은 절대 저장하지 않는다. 개수만 센다.
 */
class DnsStats {

    data class Snapshot(
        val queries: Long,
        val cacheHits: Long,
        val replies: Long,
        val failures: Long,
        val truncated: Long,
        val dropped: Long,
        val cacheEntries: Int,
    )

    private val queries = AtomicLong()
    private val cacheHits = AtomicLong()
    private val replies = AtomicLong()
    private val failures = AtomicLong()
    private val truncated = AtomicLong()
    private val dropped = AtomicLong()

    fun onQuery() {
        queries.incrementAndGet()
    }

    fun onCacheHit() {
        cacheHits.incrementAndGet()
    }

    fun onReply() {
        replies.incrementAndGet()
    }

    fun onFailure() {
        failures.incrementAndGet()
    }

    fun onTruncated() {
        truncated.incrementAndGet()
    }

    fun onDropped() {
        dropped.incrementAndGet()
    }

    fun snapshot(cacheEntries: Int): Snapshot = Snapshot(
        queries = queries.get(),
        cacheHits = cacheHits.get(),
        replies = replies.get(),
        failures = failures.get(),
        truncated = truncated.get(),
        dropped = dropped.get(),
        cacheEntries = cacheEntries,
    )

    fun reset() {
        queries.set(0)
        cacheHits.set(0)
        replies.set(0)
        failures.set(0)
        truncated.set(0)
        dropped.set(0)
    }
}
