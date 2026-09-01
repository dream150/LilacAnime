package com.lilac.anime.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** HTTP client dedicated to Animenosub. LinkkfClient remains untouched. */
class AnimenosubHttpClient {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun getDocument(url: String, referer: String = BASE_URL + "/"): Document {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9,ko;q=0.7")
                    .header("Referer", referer)
                    .header("Upgrade-Insecure-Requests", "1")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Animenosub HTTP ${response.code}")
                    val html = response.body?.string().orEmpty()
                    if (html.isBlank()) throw IOException("Animenosub empty response")
                    return Jsoup.parse(html, url)
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2 && (e is IOException || e is SocketTimeoutException)) {
                    Thread.sleep(if (attempt == 0) 600L else 1200L)
                } else if (attempt >= 2) {
                    throw e
                }
            }
        }
        throw lastError ?: IOException("Animenosub request failed")
    }

    companion object {
        const val BASE_URL = "https://animenosub.to"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
