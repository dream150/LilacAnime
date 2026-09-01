package com.lilac.anime.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Resolves an Animenosub episode page without changing the existing Linkkf catalog/source. */
class AnimenosubClient {
    /*
     * Authentication/session cookie bridging is intentionally disabled.
     * The current player path extracts the HLS URL directly from the WebView
     * network requests instead of copying WebView authentication state into OkHttp.
     *
     * @Volatile
     * private var webViewCookieHeader: String = ""
     *
     * fun setWebViewCookies(cookieHeader: String?) { ... }
     * fun hasWebViewCookies(): Boolean = webViewCookieHeader.isNotBlank()
     */

    private fun addCommonHeaders(builder: Request.Builder, referer: String = BASE_URL + "/") {
        builder.header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9,ko;q=0.7")
            .header("Referer", referer)
        // WebView authentication cookie forwarding is disabled.
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun resolveEpisodeUrl(title: String, displayNumber: String): String? {
        val candidates = linkedSetOf<String>()
        val slug = slugify(title)
        val epSlug = slugify(displayNumber)
        if (slug.isNotBlank() && epSlug.isNotBlank()) {
            candidates += "https://animenosub.to/${slug}-episode-${epSlug}/"
        }
        if (slug.isNotBlank() && displayNumber.toIntOrNull() != null) {
            candidates += "https://animenosub.to/${slug}-episode-${displayNumber.toInt()}/"
        }

        for (url in candidates) {
            if (isEpisodePage(url)) return url
        }

        return searchEpisodeUrl(title, displayNumber)
    }

    private fun isEpisodePage(url: String): Boolean {
        return try {
            val request = Request.Builder().url(url).also { addCommonHeaders(it) }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val html = response.body?.string().orEmpty()
                val doc = Jsoup.parse(html, url)
                val heading = doc.selectFirst("h1")?.text().orEmpty()
                heading.contains("Episode", ignoreCase = true) ||
                    doc.select("iframe").any { it.attr("src").isNotBlank() }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun searchEpisodeUrl(title: String, displayNumber: String): String? {
        return try {
            val query = URLEncoder.encode("$title Episode $displayNumber", "UTF-8")
            val url = "https://animenosub.to/?s=$query"
            val request = Request.Builder().url(url).also { addCommonHeaders(it) }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val doc = Jsoup.parse(response.body?.string().orEmpty(), url)
                val normalizedTitle = normalize(title)
                val number = normalizeEpisodeNumber(displayNumber)

                val links = doc.select("a[href]")
                    .mapNotNull { a ->
                        val href = a.absUrl("href").ifBlank { a.attr("href") }
                        val text = a.text().trim()
                        if (!href.startsWith("https://animenosub.to/")) return@mapNotNull null
                        if (href.endsWith("/category/") || href.contains("/page/")) return@mapNotNull null
                        val score = scoreCandidate(text, href, normalizedTitle, number)
                        if (score > 0) href to score else null
                    }
                    .sortedByDescending { it.second }

                links.firstOrNull()?.first
            }
        } catch (_: Exception) {
            null
        }
    }

    fun searchAnime(query: String): List<Pair<String, String>> {
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://animenosub.to/?s=$encoded"
            val request = Request.Builder().url(url).also { addCommonHeaders(it) }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val doc = Jsoup.parse(response.body?.string().orEmpty(), url)
                doc.select("a[href*='/anime/']").mapNotNull { a ->
                    val href = a.absUrl("href").trim()
                    val title = a.text().trim()
                    if (href.startsWith(BASE_URL + "/anime/") && title.isNotBlank()) title to href else null
                }.distinctBy { it.second }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun scoreCandidate(text: String, href: String, title: String, number: String): Int {
        val haystack = normalize("$text $href")
        var score = 0
        if (haystack.contains(title)) score += 10
        val compactTitle = title.replace("season", "")
        if (compactTitle.length > 8 && haystack.contains(compactTitle)) score += 5
        if (number.isNotBlank() && Regex("episode[- _]*${Regex.escape(number)}\\b").containsMatchIn(haystack)) score += 10
        if (number.isNotBlank() && haystack.contains("eps $number")) score += 8
        if (haystack.contains("episode")) score += 1
        return score
    }

    private fun normalizeEpisodeNumber(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^0-9a-z]"), "")

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace('&', ' ')
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun slugify(value: String): String = normalize(value).replace(' ', '-')

    companion object {
        private const val BASE_URL = "https://animenosub.to"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
