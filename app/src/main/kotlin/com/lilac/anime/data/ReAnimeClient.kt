package com.lilac.anime.data

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit

class ReAnimeClient {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun getDocument(url: String, referer: String = BASE_URL + "/"): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html")
            .header("Accept-Language", "en-US")
            .header("Referer", referer)
            .header("Upgrade-Insecure-Requests", "1")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Re:Anime HTTP " + response.code)
            }

            val html = response.body?.string().orEmpty()
            if (html.isBlank()) {
                throw IOException("Re:Anime empty response")
            }

            return Jsoup.parse(html, url)
        }
    }

    /**
     * Current Re:ANIME API.
     *
     * Search and catalog requests use:
     * https://reanime.to/api/v1/search
     *
     * limit and offset are passed through unchanged.
     */
    fun getApiV1Search(
        query: String = "",
        limit: Int = 36,
        offset: Int = 0
    ): String {
        val builder = HttpUrl.Builder()
            .scheme("https")
            .host("reanime.to")
            .addPathSegment("api")
            .addPathSegment("v1")
            .addPathSegment("search")

        if (query.isNotBlank()) {
            builder.addQueryParameter("q", query)
        }

        builder.addQueryParameter("limit", limit.toString())
        builder.addQueryParameter("offset", offset.toString())

        val url = builder.build().toString()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US")
            .header("Referer", BASE_URL + "/search?limit=" + limit + "&offset=" + offset)
            .build()

        android.util.Log.d(TAG, "API_V1_SEARCH url=" + url)

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            android.util.Log.d(
                TAG,
                "API_V1_SEARCH_RESULT code=" + response.code +
                    " length=" + body.length
            )

            if (!response.isSuccessful) {
                throw IOException(
                    "Re:Anime API v1 HTTP " + response.code +
                        ": " + body.take(300)
                )
            }

            if (body.isBlank()) {
                throw IOException("Re:Anime API v1 empty response")
            }

            return body
        }
    }


    /** Resolve the actual FlixCloud player links for a specific anime/episode. */
    fun getFlixServers(anilistId: Int, episodeNumber: Int): String {
        val url = "$BASE_URL/api/flix/$anilistId/$episodeNumber"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US")
            .header("Referer", BASE_URL + "/")
            .build()

        android.util.Log.d(TAG, "FLIX_RESOLVE url=$url")
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            android.util.Log.d(TAG, "FLIX_RESOLVE_RESULT code=${response.code} length=${body.length}")
            if (!response.isSuccessful || body.isBlank()) {
                throw IOException("Re:Anime Flix API HTTP ${response.code}: ${body.take(300)}")
            }
            return body
        }
    }

    fun searchAnime(
        query: String,
        limit: Int = 36,
        offset: Int = 0
    ): String {
        return getApiV1Search(query, limit, offset)
    }

    fun catalogAnime(
        limit: Int = 36,
        offset: Int = 0
    ): String {
        return getApiV1Search("", limit, offset)
    }

    companion object {
        const val BASE_URL = "https://reanime.to"

        private const val TAG = "ReAnime"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
