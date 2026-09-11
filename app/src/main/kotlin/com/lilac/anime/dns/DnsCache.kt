package com.lilac.anime.dns

/**
 * 메모리 DNS 캐시.
 *
 * - positive / negative 응답을 모두 캐시한다.
 * - upstream TTL 을 존중하고 상/하한을 둔다.
 * - 크기 제한(LRU)을 둔다.
 * - 앱 재시작 후 유지하지 않는다. (초기 구현 범위)
 */
class DnsCache(
    private val maxEntries: Int = 512,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        const val MIN_POSITIVE_TTL_SECONDS = 5L
        const val MAX_POSITIVE_TTL_SECONDS = 6L * 60L * 60L
        const val NEGATIVE_TTL_SECONDS = 30L

        /** 응답을 캐시하면 안 되는 rcode. */
        private fun isCacheableRcode(rcode: Int): Boolean =
            rcode == DnsRcode.NO_ERROR || rcode == DnsRcode.NXDOMAIN
    }

    class Entry(
        private val raw: ByteArray,
        val length: Int,
        val storedAtMillis: Long,
        val expiresAtMillis: Long,
        private val ttlOffsets: IntArray,
        val negative: Boolean,
    ) {
        fun isExpired(now: Long): Boolean = now >= expiresAtMillis

        /** 새 트랜잭션 ID 로 복사하고 경과 시간만큼 TTL 을 줄여 돌려준다. */
        fun materialize(newId: Int, now: Long): ByteArray {
            val copy = raw.copyOf()
            val elapsedSeconds = ((now - storedAtMillis) / 1000L).coerceAtLeast(0L)
            DnsMessageCodec.rewriteIdAndAgeTtls(copy, length, newId, ttlOffsets, elapsedSeconds)
            return copy
        }
    }

    data class Stats(val hits: Long, val misses: Long, val stores: Long, val entries: Int)

    private val lock = Any()

    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }

    private var hits = 0L
    private var misses = 0L
    private var stores = 0L

    fun get(key: String): Entry? = synchronized(lock) {
        val entry = entries[key]
        if (entry == null) {
            misses++
            return@synchronized null
        }
        if (entry.isExpired(clock())) {
            entries.remove(key)
            misses++
            return@synchronized null
        }
        hits++
        entry
    }

    /**
     * 응답을 캐시한다.
     *
     * @return 실제로 저장했으면 true
     */
    fun put(key: String, response: ByteArray, length: Int, message: DnsMessage): Boolean =
        synchronized(lock) {
            if (!isCacheableRcode(message.rcode)) return@synchronized false
            // TC 응답은 불완전하므로 캐시하지 않는다.
            if (message.truncated) return@synchronized false
            if (length < DnsMessageCodec.HEADER_SIZE || length > response.size) return@synchronized false

            val now = clock()
            val ttlSeconds = if (message.isNegative) {
                minOf(message.minTtl ?: NEGATIVE_TTL_SECONDS, NEGATIVE_TTL_SECONDS)
                    .coerceAtLeast(1L)
            } else {
                (message.minTtl ?: MIN_POSITIVE_TTL_SECONDS)
                    .coerceIn(MIN_POSITIVE_TTL_SECONDS, MAX_POSITIVE_TTL_SECONDS)
            }

            entries[key] = Entry(
                raw = response.copyOf(length),
                length = length,
                storedAtMillis = now,
                expiresAtMillis = now + ttlSeconds * 1000L,
                ttlOffsets = message.answers.filter { it.type != DnsType.OPT }
                    .map { it.ttlOffset }
                    .toIntArray(),
                negative = message.isNegative,
            )
            stores++
            true
        }

    fun clear() = synchronized(lock) {
        entries.clear()
        hits = 0
        misses = 0
        stores = 0
    }

    /** TTL 만료된 항목만 지운다. 네트워크 변경 시 새 경로로 다시 해석하도록 사용한다. */
    fun purgeExpired() = synchronized(lock) {
        val now = clock()
        entries.entries.removeAll { it.value.isExpired(now) }
    }

    val size: Int get() = synchronized(lock) { entries.size }

    fun stats(): Stats = synchronized(lock) {
        Stats(hits = hits, misses = misses, stores = stores, entries = entries.size)
    }
}
