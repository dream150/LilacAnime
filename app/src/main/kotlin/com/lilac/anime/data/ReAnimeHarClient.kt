package com.lilac.anime.data

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Re:ANIME client based strictly on the requests observed in the supplied HAR.
 *
 * UI/catalog:
 *   GET /api/v1/search
 *
 * Detail/watch data:
 *   GET /anime/{slug}/__data.json
 *   GET /watch/{slug}/__data.json
 *
 * Playback metadata:
 *   GET /api/flix/{anilistId}/{episode}
 *
 * Auxiliary:
 *   GET /api/thumbnails/{anilistId}
 *   GET /api/v1/downloads/check
 */
class ReAnimeHarClient {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun search(
        query: String = "",
        limit: Int = 36,
        offset: Int = 0,
        genre: List<String> = emptyList(),
        year: Int? = null,
        season: String? = null,
        status: String? = null,
        format: String? = null,
        tag: List<String> = emptyList(),
        character: List<String> = emptyList(),
        staff: List<String> = emptyList(),
        studio: List<String> = emptyList()
    ): String = request("/api/v1/search") {
        if (query.isNotBlank()) it.addQueryParameter("q", query.trim())
        if (genre.isNotEmpty()) it.addQueryParameter("genre", genre.joinToString(","))
        year?.let { value -> it.addQueryParameter("year", value.toString()) }
        season?.takeIf { it.isNotBlank() }?.let { value -> it.addQueryParameter("season", value) }
        status?.takeIf { it.isNotBlank() }?.let { value -> it.addQueryParameter("status", value) }
        format?.takeIf { it.isNotBlank() }?.let { value -> it.addQueryParameter("format", value) }
        if (tag.isNotEmpty()) it.addQueryParameter("tag", tag.joinToString(","))
        if (character.isNotEmpty()) it.addQueryParameter("character", character.joinToString(","))
        if (staff.isNotEmpty()) it.addQueryParameter("staff", staff.joinToString(","))
        if (studio.isNotEmpty()) it.addQueryParameter("studio", studio.joinToString(","))
        it.addQueryParameter("limit", limit.toString())
        it.addQueryParameter("offset", offset.toString())
    }

    fun facets(): String = request("/api/v1/search") {
        it.addQueryParameter("facets", "true")
        it.addQueryParameter("limit", "0")
    }

    fun facetValues(kind: String, query: String = ""): String = request("/api/v1/search/facets/${kind.trim('/')}") {
        it.addQueryParameter("q", query)
    }

    fun topAnime(period: String = "today", limit: Int = 10): String =
        request("/api/v1/top/anime") {
            it.addQueryParameter("period", period)
            it.addQueryParameter("limit", limit.toString())
        }

    fun schedule(timeZone: String = "Asia/Seoul", week: Int = 0): String =
        request("/api/v1/schedule") {
            it.addQueryParameter("tz", timeZone)
            it.addQueryParameter("week", week.toString())
        }

    fun detailData(slug: String): String = request(
        "/anime/${slug.trim().trim('/').substringAfterLast("/anime/")}/__data.json"
    ) { it.addQueryParameter("x-appkit-invalidated", "001") }

    fun watchData(slug: String, episode: Int? = null): String = request(
        "/watch/${slug.trim().trim('/').substringAfterLast("/watch/")}/__data.json"
    ) {
        it.addQueryParameter("x-appkit-invalidated", "001")
        episode?.takeIf { value -> value > 0 }?.let { value ->
            it.addQueryParameter("ep", value.toString())
        }
    }

    fun flixServers(anilistId: Int, episode: Int): String =
        getJson("/api/flix/$anilistId/$episode")

    fun thumbnails(anilistId: Int): String =
        getJson("/api/thumbnails/$anilistId")

    fun downloadCheck(anilistId: Int?, malId: Int?, episode: Int): String =
        request("/api/v1/downloads/check") {
            anilistId?.let { value -> it.addQueryParameter("anilist_id", value.toString()) }
            malId?.let { value -> it.addQueryParameter("mal_id", value.toString()) }
            it.addQueryParameter("episode", episode.toString())
        }

    private fun getJson(path: String): String = request(path) {}

    private fun request(path: String, configure: (HttpUrl.Builder) -> Unit): String {
        val builder = HttpUrl.Builder()
            .scheme("https")
            .host("reanime.to")
        path.trim('/').split('/').filter { it.isNotBlank() }.forEach(builder::addPathSegment)
        configure(builder)
        val url = builder.build().toString()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", if (path.contains("api/v1/search")) "https://reanime.to/search" else "https://reanime.to/")
            .build()

        android.util.Log.d(TAG, "HAR_REQUEST $url")
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            android.util.Log.d(TAG, "HAR_RESPONSE code=${response.code} bytes=${body.length} url=$url")
            if (!response.isSuccessful) {
                throw IOException("Re:Anime HAR API HTTP ${response.code}: ${body.take(300)}")
            }
            if (body.isBlank()) throw IOException("Re:Anime HAR API empty response: $url")
            return body
        }
    }

    companion object {
        private const val TAG = "ReAnimeHAR"
        const val BASE_URL = "https://reanime.to"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
