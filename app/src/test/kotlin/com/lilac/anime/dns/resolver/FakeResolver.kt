package com.lilac.anime.dns.resolver

internal class FakeResolver(
    override val label: String,
    private val handler: (ByteArray, Int) -> ByteArray? = { _, _ -> null },
) : DnsResolver {

    var calls: Int = 0
        private set

    override suspend fun resolve(query: ByteArray, length: Int): ByteArray? {
        calls++
        return handler(query, length)
    }
}
