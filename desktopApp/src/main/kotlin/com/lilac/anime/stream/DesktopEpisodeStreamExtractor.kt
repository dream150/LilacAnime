package com.lilac.anime.stream

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Desktop fallback extractor.
 *
 * The Android version observes WebView network requests. Desktop has no Android
 * WebView, so this implementation first inspects the target HTML and any HLS
 * playlist it can discover. It intentionally keeps the same quality heuristic:
 * /sd/ -> 720p, otherwise -> 1080p, and ignores URLs containing "ad".
 */
class DesktopEpisodeStreamExtractor : EpisodeStreamExtractor {
    override suspend fun extract(targetUrl: String): EpisodeStreamInfo {
        require(targetUrl.isNotBlank()) { "Target URL is empty" }

        if (isMediaUrl(targetUrl)) {
            return EpisodeStreamInfo(
                qualities = M3u8QualityResolver.resolve(listOf(targetUrl)),
                subtitleUrl = null
            )
        }

        val html = get(targetUrl)
        val base = URI(targetUrl)
        val candidates = LinkedHashSet<String>()
        var subtitle: String? = null

        for (raw in URL_REGEX.findAll(html).map { it.groupValues[1] }) {
            val decoded = decode(raw)
            val resolved = resolve(base, decoded)
            when {
                resolved.contains(".m3u8", ignoreCase = true) &&
                    !resolved.contains("ad", ignoreCase = true) -> candidates += resolved
                subtitle == null && resolved.contains(".vtt", ignoreCase = true) -> subtitle = resolved
            }
        }

        // Also catches quoted URLs containing escaped query parameters.
        for (raw in QUOTED_MEDIA_REGEX.findAll(html).map { it.groupValues[1] }) {
            val resolved = resolve(base, decode(raw))
            when {
                resolved.contains(".m3u8", ignoreCase = true) &&
                    !resolved.contains("ad", ignoreCase = true) -> candidates += resolved
                subtitle == null && resolved.contains(".vtt", ignoreCase = true) -> subtitle = resolved
            }
        }

        val qualities = M3u8QualityResolver.resolve(candidates.toList())

        // If a discovered URL is a master playlist, inspect it for variant URLs.
        val variants = LinkedHashSet<String>()
        for (quality in qualities) {
            runCatching {
                val playlist = get(quality.url)
                for (line in playlist.lineSequence()) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") &&
                        trimmed.contains(".m3u8", ignoreCase = true) &&
                        !trimmed.contains("ad", ignoreCase = true)) {
                        variants += resolve(URI(quality.url), trimmed)
                    }
                }
            }
        }

        val finalQualities = if (variants.isNotEmpty()) {
            M3u8QualityResolver.resolve(variants.toList())
        } else qualities

        return EpisodeStreamInfo(finalQualities, subtitle)
    }

    private fun get(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/vnd.apple.mpegurl,*/*")
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode} while requesting $url")
            }
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun resolve(base: URI, value: String): String {
        return runCatching { base.resolve(value).toString() }.getOrDefault(value)
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("\\u0026", "&"), StandardCharsets.UTF_8)

    private fun isMediaUrl(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) || url.contains(".mp4", ignoreCase = true)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val URL_REGEX = Regex("[\\\"']((?:https?:)?//[^\\\"']+|(?:/|\\./|\\../)[^\\\"']+\\.(?:m3u8|vtt)(?:\\?[^\\\"']*)?)")
        private val QUOTED_MEDIA_REGEX = Regex("[\\\"']([^\\\"']+\\.(?:m3u8|vtt)(?:\\?[^\\\"']*)?)[\\\"']", RegexOption.IGNORE_CASE)
    }
}
