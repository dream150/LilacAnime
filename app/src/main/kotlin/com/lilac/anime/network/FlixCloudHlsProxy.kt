package com.lilac.anime.network

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Local HTTP proxy for FlixCloud HLS.
 *
 * The FlixCloud page itself creates a per-page __pk in WASM. WebView supplies that
 * live value to this proxy. The proxy then:
 *  - decrypts Base64/XOR M3U8 responses with that __pk;
 *  - rewrites every playlist URI to this local proxy;
 *  - removes the fake PNG/WebP segment wrapper and applies the FlixCloud segment XOR.
 *
 * Range is deliberately NOT forwarded upstream. FlixCloud's fake segment endpoints
 * return a complete wrapped object; forwarding a byte range would make the wrapper
 * stripping/XOR start at the wrong byte.
 */
object FlixCloudHlsProxy : Closeable {
    /** Fallback retained only for diagnostics/backward compatibility; normal playback never uses it. */
    const val DEFAULT_PK_BASE64 = "cebMPaA5RqAA4xO7OD97NjEuxtbAuHKev7kCh24guJw="

    private const val TAG = "FlixProxy"

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private data class Session(
        val pkBase64: String,
        val headers: Map<String, String>
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var port: Int = -1

    @Synchronized
    private fun ensureStarted() {
        if (server?.isClosed == false && port > 0) return
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = socket
        port = socket.localPort
        Log.d(TAG, "SERVER_STARTED port=$port")
        thread(name = "FlixCloudHlsProxy", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    val clientSocket = socket.accept()
                    thread(name = "FlixCloudHlsProxyClient", isDaemon = true) {
                        handle(clientSocket)
                    }
                } catch (_: Throwable) {
                    if (!socket.isClosed) Thread.yield()
                }
            }
        }
    }

    fun createProxyUrl(
        upstreamUrl: String,
        pkBase64: String,
        headers: String? = null
    ): String {
        require(upstreamUrl.startsWith("http://", true) || upstreamUrl.startsWith("https://", true))
        require(pkBase64.isNotBlank()) { "FlixCloud __pk is empty" }
        ensureStarted()

        val id = UUID.randomUUID().toString().replace("-", "")
        sessions[id] = Session(
            pkBase64 = pkBase64.trim(),
            headers = parseHeaders(headers)
        )
        val encoded = Base64.encodeToString(
            upstreamUrl.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        val proxy = "http://127.0.0.1:$port/__flix/$id/$encoded"
        Log.d(TAG, "SESSION_CREATED id=$id pkChars=${pkBase64.length} upstream=$upstreamUrl")
        return proxy
    }

    fun isProxyUrl(url: String): Boolean =
        url.startsWith("http://127.0.0.1:") && url.contains("/__flix/")

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 2) return

            val method = parts[0]
            if (!method.equals("GET", true) && !method.equals("HEAD", true)) {
                writeResponse(output, 405, "text/plain; charset=utf-8", "Method Not Allowed".toByteArray())
                return
            }

            // Consume the HTTP request headers. We intentionally do not forward Range.
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }

            val uri = runCatching { URI("http://127.0.0.1${parts[1]}") }.getOrNull()
            if (uri == null) {
                writeResponse(output, 400, "text/plain; charset=utf-8", "Bad Request".toByteArray())
                return
            }

            val pathParts = uri.path.split('/').filter { it.isNotEmpty() }
            if (pathParts.size < 3 || pathParts[0] != "__flix") {
                writeResponse(output, 404, "text/plain; charset=utf-8", "Not Found".toByteArray())
                return
            }

            val sessionId = pathParts[1]
            val session = sessions[sessionId]
            if (session == null) {
                writeResponse(output, 410, "text/plain; charset=utf-8", "Session expired".toByteArray())
                return
            }

            val encodedUrl = pathParts.drop(2).joinToString("/")
            val upstreamUrl = runCatching {
                String(
                    Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP),
                    StandardCharsets.UTF_8
                )
            }.getOrNull()
            if (upstreamUrl.isNullOrBlank()) {
                writeResponse(output, 400, "text/plain; charset=utf-8", "Bad target".toByteArray())
                return
            }

            val kind = responseKind(upstreamUrl)
            Log.d(TAG, "REQUEST session=$sessionId kind=$kind upstream=$upstreamUrl")

            val requestBuilder = Request.Builder().url(upstreamUrl).get()
            // Preserve the headers captured from the real WebView request. Range is excluded.
            val forwardNames = setOf(
                "User-Agent", "Referer", "Origin", "Cookie",
                "Accept", "Accept-Language"
            )
            for ((name, value) in session.headers) {
                if (name in forwardNames && value.isNotBlank()) {
                    requestBuilder.header(name, value)
                }
            }

            val response = runCatching { client.newCall(requestBuilder.build()).execute() }
                .getOrElse {
                    Log.e(TAG, "UPSTREAM_EXCEPTION kind=$kind url=$upstreamUrl", it)
                    writeResponse(output, 502, "text/plain; charset=utf-8", "Upstream error".toByteArray())
                    return
                }

            response.use { upstream ->
                val body = upstream.body?.bytes() ?: ByteArray(0)
                Log.d(TAG, "UPSTREAM_RESPONSE kind=$kind status=${upstream.code} bytes=${body.size} type=${upstream.header("Content-Type")} url=$upstreamUrl")

                if (!upstream.isSuccessful) {
                    writeResponse(
                        output,
                        upstream.code,
                        upstream.header("Content-Type") ?: "text/plain; charset=utf-8",
                        body
                    )
                    return
                }

                val decoded = runCatching {
                    decodeResponse(
                        upstreamUrl = upstreamUrl,
                        body = body,
                        pkBase64 = session.pkBase64,
                        sessionId = sessionId
                    )
                }.getOrElse { error ->
                    Log.e(TAG, "DECODE_EXCEPTION kind=$kind url=$upstreamUrl", error)
                    writeResponse(
                        output,
                        502,
                        "text/plain; charset=utf-8",
                        "FLIX_DECODE_FAILED: ${error.message ?: error::class.java.simpleName}".toByteArray(StandardCharsets.UTF_8)
                    )
                    return
                }

                val contentType = when (kind) {
                    Kind.M3U8 -> "application/vnd.apple.mpegurl"
                    Kind.SEGMENT -> "video/mp4"
                    Kind.KEY -> "application/octet-stream"
                    else -> upstream.header("Content-Type") ?: "application/octet-stream"
                }

                writeResponse(
                    output = output,
                    code = 200,
                    contentType = contentType,
                    body = if (method.equals("HEAD", true)) ByteArray(0) else decoded,
                    extraHeaders = mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Cache-Control" to "no-store",
                        "Accept-Ranges" to "none"
                    )
                )
                Log.d(TAG, "PROXY_RESPONSE kind=$kind bytes=${decoded.size} url=$upstreamUrl")
            }
        }
    }

    private enum class Kind { M3U8, SEGMENT, KEY, OTHER }

    private fun responseKind(url: String): Kind {
        val path = runCatching { URI(url).path.lowercase() }.getOrDefault(url.substringBefore('?').lowercase())
        return when {
            path.endsWith(".m3u8") -> Kind.M3U8
            path.endsWith(".png") || path.endsWith(".webp") -> Kind.SEGMENT
            path.endsWith("key.bin") -> Kind.KEY
            else -> Kind.OTHER
        }
    }

    private fun decodeResponse(
        upstreamUrl: String,
        body: ByteArray,
        pkBase64: String,
        sessionId: String
    ): ByteArray {
        return when (responseKind(upstreamUrl)) {
            Kind.M3U8 -> decodeManifest(body, pkBase64, upstreamUrl, sessionId)
            Kind.SEGMENT -> decodeFragment(body)
            else -> body
        }
    }

    private fun decodeManifest(
        body: ByteArray,
        pkBase64: String,
        upstreamUrl: String,
        sessionId: String
    ): ByteArray {
        val raw = body.toString(StandardCharsets.ISO_8859_1).trim()
        if (raw.startsWith("#EXTM3U")) {
            Log.d(TAG, "M3U8_ALREADY_PLAIN url=$upstreamUrl")
            return rewriteManifest(raw, upstreamUrl, sessionId).toByteArray(StandardCharsets.UTF_8)
        }

        val encrypted = runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrElse {
            throw IllegalStateException("M3U8 is not valid Base64")
        }
        val pk = runCatching { Base64.decode(pkBase64.trim(), Base64.DEFAULT) }.getOrElse {
            throw IllegalStateException("__pk is not valid Base64")
        }
        if (pk.isEmpty()) throw IllegalStateException("__pk is empty")

        val plain = ByteArray(encrypted.size)
        for (i in encrypted.indices) {
            plain[i] = (encrypted[i].toInt() xor pk[i % pk.size].toInt()).toByte()
        }

        val manifest = plain.toString(StandardCharsets.UTF_8).trim()
        if (!manifest.startsWith("#EXTM3U")) {
            val preview = manifest.take(80).replace('\n', ' ')
            throw IllegalStateException("M3U8 decrypt failed; first=${preview}")
        }

        Log.d(TAG, "M3U8_DECRYPTED url=$upstreamUrl chars=${manifest.length} first=${manifest.lineSequence().firstOrNull().orEmpty()}")
        return rewriteManifest(manifest, upstreamUrl, sessionId)
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun rewriteManifest(
        manifest: String,
        baseUrl: String,
        sessionId: String
    ): String {
        return manifest.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                // Rewrite URI="..." on any HLS tag, not only KEY/MAP/MEDIA. This covers
                // EXT-X-I-FRAMES-ONLY and future FlixCloud playlist variants as well.
                if (trimmed.contains("URI=\"", true)) {
                    line.replace(Regex("""URI=\"([^\"]+)\"""", RegexOption.IGNORE_CASE)) { match ->
                        val resolved = resolve(baseUrl, match.groupValues[1])
                        "URI=\"${toProxyUrl(sessionId, resolved)}\""
                    }
                } else {
                    line
                }
            } else {
                toProxyUrl(sessionId, resolve(baseUrl, trimmed))
            }
        }
    }

    private fun toProxyUrl(sessionId: String, upstreamUrl: String): String {
        val encoded = Base64.encodeToString(
            upstreamUrl.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        return "http://127.0.0.1:$port/__flix/$sessionId/$encoded"
    }

    private fun resolve(baseUrl: String, value: String): String {
        return runCatching { URI(baseUrl).resolve(value).toString() }.getOrElse { value }
    }

    private fun decodeFragment(body: ByteArray): ByteArray {
        var payload: ByteArray? = null

        // FlixCloud wraps some segments in RIFF/WEBP and some in PNG. The first
        // 12/8 bytes are an outer wrapper, not media data.
        if (body.size >= 12 &&
            body[0] == 'R'.code.toByte() && body[1] == 'I'.code.toByte() &&
            body[2] == 'F'.code.toByte() && body[3] == 'F'.code.toByte() &&
            body[8] == 'W'.code.toByte() && body[9] == 'E'.code.toByte() &&
            body[10] == 'B'.code.toByte() && body[11] == 'P'.code.toByte()
        ) {
            payload = body.copyOfRange(12, body.size)
        } else if (
            body.size >= 8 &&
            body[0] == 137.toByte() && body[1] == 80.toByte() &&
            body[2] == 78.toByte() && body[3] == 71.toByte() &&
            body[4] == 13.toByte() && body[5] == 10.toByte() &&
            body[6] == 26.toByte() && body[7] == 10.toByte()
        ) {
            payload = body.copyOfRange(8, body.size)
        }

        if (payload == null) return body
        if (payload!!.isEmpty() || payload!![0] == 'G'.code.toByte()) {
            return payload!!
        }

        val key = byteArrayOf(
            157.toByte(), 42.toByte(), 241.toByte(), 71.toByte(),
            179.toByte(), 142.toByte(), 92.toByte(), 112.toByte(),
            166.toByte(), 25.toByte(), 228.toByte(), 59.toByte(),
            216.toByte(), 98.toByte(), 15.toByte(), 197.toByte()
        )
        for (i in payload!!.indices) {
            payload!![i] = (payload!![i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        Log.d(TAG, "SEGMENT_DECRYPTED bytes=${payload!!.size} first=${payload!!.take(4).joinToString(" ") { "%02x".format(it) }}")
        return payload!!
    }

    private fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.lineSequence()
            .mapNotNull {
                val p = it.indexOf(':')
                if (p <= 0) null else it.substring(0, p).trim() to it.substring(p + 1).trim()
            }
            .toMap()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString(StandardCharsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        code: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val reason = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            410 -> "Gone"
            502 -> "Bad Gateway"
            else -> "HTTP"
        }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            for ((k, v) in extraHeaders) append("$k: $v\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        output.write(head)
        output.write(body)
        output.flush()
    }

    @Synchronized
    override fun close() {
        sessions.clear()
        runCatching { server?.close() }
        server = null
        port = -1
        Log.d(TAG, "SERVER_STOPPED")
    }
}
