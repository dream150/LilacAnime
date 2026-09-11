package com.lilac.anime.dns.resolver

import com.lilac.anime.dns.DnsMessageCodec
import com.lilac.anime.dns.DnsValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * VpnService TUN 으로 socket 이 다시 들어가지 않도록 보호하는 SocketFactory.
 */
@Suppress("DEPRECATION")
class ProtectingSocketFactory(
    private val protector: SocketProtector,
    private val delegate: SocketFactory = SocketFactory.getDefault(),
) : SocketFactory() {

    override fun createSocket(): Socket = guard(delegate.createSocket())

    override fun createSocket(host: String, port: Int): Socket =
        guard(delegate.createSocket(host, port))

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = guard(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        guard(delegate.createSocket(host, port))

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = guard(delegate.createSocket(address, port, localAddress, localPort))

    private fun guard(socket: Socket): Socket {
        runCatching { protector.protect(socket) }
        return socket
    }
}

/**
 * DNS-over-HTTPS (RFC 8484) resolver.
 *
 * - POST + `application/dns-message`
 * - TLS 검증을 우회하지 않는다. (커스텀 SSL socket factory 를 쓰지 않고 기본값 유지)
 * - redirect 를 따라가지 않는다. 질의가 다른 host 로 새는 것을 막기 위함이다.
 * - hostname 해석은 반드시 bootstrap resolver 를 통한다.
 */
class DohDnsResolver(
    override val label: String,
    private val dohUrl: String,
    bootstrapDns: Dns,
    protector: SocketProtector = SocketProtector.NONE,
    providedClient: OkHttpClient? = null,
    private val log: (String) -> Unit = {},
) : DnsResolver {

    private val client: OkHttpClient = providedClient ?: buildClient(bootstrapDns, protector)

    override suspend fun resolve(query: ByteArray, length: Int): ByteArray? {
        if (length < DnsMessageCodec.HEADER_SIZE || length > query.size) return null
        if (!DnsValidation.isValidDohUrl(dohUrl)) {
            log("DOH_INVALID_URL")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val body = query.copyOf(length).toRequestBody(MEDIA_TYPE)
                val request = Request.Builder()
                    .url(dohUrl)
                    .post(body)
                    .header("Accept", MEDIA_TYPE_VALUE)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log("DOH_HTTP_${response.code}")
                        return@withContext null
                    }
                    val bytes = response.body?.bytes() ?: return@withContext null
                    if (bytes.size < DnsMessageCodec.HEADER_SIZE || bytes.size > MAX_RESPONSE) {
                        log("DOH_BAD_LENGTH")
                        return@withContext null
                    }
                    if (DnsMessageCodec.u16(bytes, 0) != DnsMessageCodec.u16(query, 0)) {
                        log("DOH_ID_MISMATCH")
                        return@withContext null
                    }
                    val parsed = DnsMessageCodec.parse(bytes, bytes.size)
                    if (parsed == null || !parsed.isResponse) {
                        log("DOH_MALFORMED")
                        return@withContext null
                    }
                    bytes
                }
            } catch (t: Throwable) {
                // TLS/HTTP/네트워크 오류 모두 여기서 흡수한다. (앱 crash 금지)
                log("DOH_FAILED ${t.javaClass.simpleName}")
                null
            }
        }
    }

    companion object {
        private const val MEDIA_TYPE_VALUE = "application/dns-message"
        private val MEDIA_TYPE = MEDIA_TYPE_VALUE.toMediaType()
        private const val MAX_RESPONSE = 64 * 1024

        fun buildClient(bootstrapDns: Dns, protector: SocketProtector): OkHttpClient =
            OkHttpClient.Builder()
                .dns(bootstrapDns)
                .socketFactory(ProtectingSocketFactory(protector))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
    }
}
