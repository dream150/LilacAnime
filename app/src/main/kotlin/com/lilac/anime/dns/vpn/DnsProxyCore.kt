package com.lilac.anime.dns.vpn

import com.lilac.anime.dns.DnsCache
import com.lilac.anime.dns.DnsMessageCodec
import com.lilac.anime.dns.resolver.DnsResolverChain

/**
 * DNS 질의 -> 응답 처리의 순수 코어.
 *
 * TUN I/O 와 분리되어 있어 가짜 resolver 로 단위 테스트할 수 있다.
 */
class DnsProxyCore(
    private val cache: DnsCache,
    /** 네트워크 변경 시 체인을 다시 만들 수 있도록 provider 로 받는다. */
    private val resolversProvider: () -> DnsResolverChain,
    private val stats: DnsStats,
    private val log: (String) -> Unit = {},
) {

    /**
     * 캐시를 먼저 보고, 없으면 resolver 체인으로 전달한다.
     *
     * @return raw DNS 응답 bytes. 실패하면 null.
     */
    suspend fun handleQuery(query: ByteArray, length: Int): ByteArray? {
        if (length < DnsMessageCodec.HEADER_SIZE || length > query.size) {
            stats.onDropped()
            return null
        }
        stats.onQuery()

        // 파싱에 실패해도 응답을 만들어내지 않고 upstream 에 그대로 전달한다.
        val parsed = DnsMessageCodec.parse(query, length)
        val key = parsed?.let { DnsMessageCodec.cacheKey(it) }
        val transactionId = DnsMessageCodec.u16(query, 0)

        if (key != null) {
            val hit = cache.get(key)
            if (hit != null) {
                stats.onCacheHit()
                return hit.materialize(transactionId, System.currentTimeMillis())
            }
        }

        val response = try {
            resolversProvider().resolve(query, length)
        } catch (t: Throwable) {
            log("RESOLVE_FAILED ${t.javaClass.simpleName}")
            null
        }

        if (response == null) {
            stats.onFailure()
            return null
        }
        stats.onReply()

        if (key != null) {
            val parsedResponse = try {
                DnsMessageCodec.parse(response, response.size)
            } catch (_: Throwable) {
                null
            }
            if (parsedResponse != null) {
                cache.put(key, response, response.size, parsedResponse)
            }
        }
        return response
    }
}
