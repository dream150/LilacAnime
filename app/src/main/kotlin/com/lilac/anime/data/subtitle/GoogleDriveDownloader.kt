package com.lilac.anime

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Google Drive downloader used by Csora.
 *
 * Blogger normally stores links as Drive viewer URLs (/file/d/<id>/view). A viewer
 * page is HTML, not the subtitle itself, so this resolver extracts the file id and
 * performs the Drive download flow while preserving cookies and confirmation tokens.
 */
object GoogleDriveDownloader {
    private const val TAG = "GoogleDrive"
    private const val MAX_REDIRECTS = 10

    private data class Response(
        val code: Int,
        val finalUrl: String,
        val headers: Map<String, List<String>>,
        val bytes: Long
    )

    fun download(originalUrl: String, target: File, userAgent: String): Boolean {
        val normalized = normalizeUrl(originalUrl)
        val fileId = extractFileId(normalized)

        if (fileId == null) {
            target.delete()
            val response = fetch(normalized, target, userAgent, linkedMapOf())
            return response != null && isRealFile(target, response.headers)
        }

        val resourceKey = queryValue(normalized, "resourcekey")
        val suffix = resourceKey?.takeIf { it.isNotBlank() }?.let {
            "&resourcekey=${encode(it)}"
        }.orEmpty()

        // Keep the original viewer URL in the flow as well. Some shared Csora ZIP
        // files require Drive's viewer page to establish cookies or expose a signed
        // download URL before the actual binary can be fetched.
        val candidates = listOf(
            normalized,
            "https://drive.usercontent.google.com/download?id=${encode(fileId)}&export=download&confirm=t$suffix",
            "https://drive.google.com/uc?export=download&id=${encode(fileId)}$suffix",
            "https://drive.google.com/u/0/uc?export=download&id=${encode(fileId)}$suffix"
        ).distinct()

        val cookies = linkedMapOf<String, String>()
        for (candidate in candidates) {
            target.delete()
            try {
                val first = fetch(candidate, target, userAgent, cookies)
                if (first == null || target.length() == 0L) {
                    target.delete()
                    continue
                }

                if (isRealFile(target, first.headers)) {
                    Log.d(TAG, "DOWNLOAD_OK id=$fileId bytes=${target.length()} url=${first.finalUrl}")
                    return true
                }

                // Large/shared Drive files can first return a warning/confirmation
                // page. Parse its form/link and retry in the same cookie session.
                val html = readSmallText(target)
                target.delete()
                val followUps = linkedSetOf<String>()
                parseConfirmationUrl(html, first.finalUrl)?.let(followUps::add)
                extractDownloadUrlFromHtml(html, first.finalUrl)?.let(followUps::add)
                extractEmbeddedDownloadUrl(html, first.finalUrl)?.let(followUps::add)

                for (confirmUrl in followUps) {
                    target.delete()
                    val second = fetch(confirmUrl, target, userAgent, cookies)
                    if (second != null && isRealFile(target, second.headers)) {
                        Log.d(TAG, "DOWNLOAD_CONFIRM_OK id=$fileId bytes=${target.length()} url=${second.finalUrl}")
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "CANDIDATE_FAILED url=$candidate error=${e.message}")
            }
            target.delete()
        }

        Log.w(TAG, "DOWNLOAD_FAILED id=$fileId source=$originalUrl")
        return false
    }

    private fun fetch(
        initialUrl: String,
        target: File,
        userAgent: String,
        cookies: MutableMap<String, String>
    ): Response? {
        var current = initialUrl
        for (redirect in 0 until MAX_REDIRECTS) {
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Referer", "https://drive.google.com/")
                if (cookies.isNotEmpty()) {
                    connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }

                val code = connection.responseCode
                storeCookies(connection.headerFields, cookies)
                val headers = connection.headerFields.filterKeys { it != null }

                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    current = resolveUrl(current, location)
                    continue
                }

                if (code !in 200..299) {
                    Log.d(TAG, "HTTP $code url=$current")
                    return null
                }

                FileOutputStream(target).use { output ->
                    connection.inputStream.use { input -> input.copyTo(output) }
                }
                return Response(code, current, headers, target.length())
            } finally {
                connection.disconnect()
            }
        }
        Log.d(TAG, "TOO_MANY_REDIRECTS url=$initialUrl")
        return null
    }

    private fun storeCookies(headers: Map<String?, List<String>>, cookies: MutableMap<String, String>) {
        headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
            .forEach { raw ->
                val first = raw.substringBefore(';')
                val index = first.indexOf('=')
                if (index > 0) cookies[first.substring(0, index)] = first.substring(index + 1)
            }
    }

    private fun extractFileId(url: String): String? {
        val patterns = listOf(
            Regex("/file/d/([^/?#]+)", RegexOption.IGNORE_CASE),
            Regex("/d/([^/?#]+)", RegexOption.IGNORE_CASE),
            Regex("[?&]id=([^&#]+)", RegexOption.IGNORE_CASE),
            Regex("[?&]fileId=([^&#]+)", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(url)?.groupValues?.getOrNull(1)?.let { decode(it) }?.takeIf { it.isNotBlank() }
        }
    }

    private fun parseConfirmationUrl(html: String, baseUrl: String): String? {
        // Modern Drive warning page: form action plus hidden inputs.
        val formRegex = Regex(
            """<form\b[^>]*action=[\"']([^\"']+)[\"'][^>]*>(.*?)</form>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val inputRegex = Regex(
            """<input\b[^>]*name=[\"']([^\"']+)[\"'][^>]*>""",
            RegexOption.IGNORE_CASE
        )
        val valueRegex = Regex("""value=[\"']([^\"']*)[\"']""", RegexOption.IGNORE_CASE)

        formRegex.findAll(html).forEach { form ->
            val body = form.groupValues[2]
            val action = htmlDecode(form.groupValues[1])
            val actionLooksRight = action.contains("download", true) || body.contains("confirm", true)
            if (!actionLooksRight) return@forEach

            val params = mutableListOf<Pair<String, String>>()
            inputRegex.findAll(body).forEach { input ->
                val tag = input.value
                val name = htmlDecode(input.groupValues[1])
                val value = valueRegex.find(tag)?.groupValues?.getOrNull(1)?.let(::htmlDecode).orEmpty()
                if (name.isNotBlank()) params += name to value
            }
            if (params.isNotEmpty()) {
                val query = params.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }
                val resolved = resolveUrl(baseUrl, action)
                return resolved + if (resolved.contains('?')) "&$query" else "?$query"
            }
        }

        // Fallback direct links containing confirm= or export=download.
        val href = Regex("""href=[\"']([^\"']+(?:confirm=|export=download)[^\"']*)[\"']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
        return href?.let { resolveUrl(baseUrl, htmlDecode(it)) }
    }

    private fun extractDownloadUrlFromHtml(html: String, baseUrl: String): String? {
        val patterns = listOf(
            Regex("""https://drive\.usercontent\.google\.com/download[^\"'\\<> ]+""", RegexOption.IGNORE_CASE),
            Regex("""https://drive\.googleusercontent\.com/download[^\"'\\<> ]+""", RegexOption.IGNORE_CASE),
            Regex("""https://drive\.google\.com/uc\?[^\"'\\<> ]+""", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { regex ->
            regex.find(html)?.value?.let { return htmlDecode(it.replace("\\u003d", "=").replace("\\u0026", "&")) }
        }
        return null
    }

    /**
     * The current Drive viewer sometimes embeds a signed download endpoint in JSON
     * rather than a normal anchor/form. Csora's ZIP links are especially likely to
     * use this representation, so decode the common escaped variants too.
     */
    private fun extractEmbeddedDownloadUrl(html: String, baseUrl: String): String? {
        val normalized = html
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val patterns = listOf(
            Regex("""["'](https?://[^"'\s\\]+(?:export=download|confirm=)[^"'\s\\]*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](/[^"'\s\\]*(?:uc\?|download)[^"'\s\\]*)["']""", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { regex ->
            regex.find(normalized)?.groupValues?.getOrNull(1)?.let { value ->
                return resolveUrl(baseUrl, value)
            }
        }
        return null
    }

    private fun isRealFile(file: File, headers: Map<String, List<String>>): Boolean {
        if (!file.isFile || file.length() < 32) return false
        if (looksLikeHtml(file)) return false

        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.firstOrNull()?.lowercase().orEmpty()
        if (contentType.contains("text/html")) return false
        return true
    }

    private fun looksLikeHtml(file: File): Boolean {
        val text = readSmallText(file).trimStart().lowercase()
        return text.startsWith("<html") || text.startsWith("<!doctype") || text.startsWith("<head") ||
            text.contains("google drive") && (text.contains("download") || text.contains("sign in"))
    }

    private fun readSmallText(file: File): String = try {
        file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(32_768)
            val count = reader.read(buffer)
            if (count <= 0) "" else String(buffer, 0, count)
        }
    } catch (_: Exception) { "" }

    private fun queryValue(url: String, key: String): String? {
        return try {
            val query = URI(url).rawQuery ?: return null
            query.split('&').firstNotNullOfOrNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) return@firstNotNullOfOrNull null
                val name = decode(part.substring(0, index))
                if (!name.equals(key, true)) return@firstNotNullOfOrNull null
                decode(part.substring(index + 1))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeUrl(value: String): String = htmlDecode(value)
        .replace("\\u003d", "=")
        .replace("\\u0026", "&")
        .trim()

    private fun resolveUrl(base: String, value: String): String = try {
        URI(base).resolve(value).toString()
    } catch (_: Exception) { value }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun decode(value: String): String = try { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) } catch (_: Exception) { value }
    private fun htmlDecode(value: String): String = value
        .replace("&amp;", "&")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
}
