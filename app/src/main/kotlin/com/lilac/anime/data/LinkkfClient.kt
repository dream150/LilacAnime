package com.lilac.anime.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the current Linkkf site.
 *
 * IMPORTANT: this is intentionally independent from linkkf.app/linkkf.tv's old
 * JSON API.  The current site captured in the supplied HAR is linkani.tv and
 * its catalog/detail/watch data is server-rendered HTML.
 */
class LinkkfClient {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun getDocument(url: String, referer: String = LinkkfParser.BASE_URL + "/"): Document {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .header("Referer", referer)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw LinkkfHttpException(response.code)
                    val html = response.body?.string().orEmpty()
                    if (html.isBlank()) throw IOException("Linkkf 빈 응답")
                    return Jsoup.parse(html, url)
                }
            } catch (e: Exception) {
                last = e
                if (!retryable(e) || attempt == 2) throw e
                Thread.sleep(longArrayOf(500L, 1200L)[attempt])
            }
        }
        throw last ?: IOException("Linkkf 요청 실패")
    }

    fun getHtml(url: String, referer: String = LinkkfParser.BASE_URL + "/"): String =
        getDocument(url, referer).html()

    private fun retryable(e: Exception): Boolean =
        e is IOException || e is SocketTimeoutException ||
            (e is LinkkfHttpException && e.code in 408..599)

    private class LinkkfHttpException(val code: Int) : IOException("HTTP $code")

    companion object {
        const val BASE_URL = LinkkfParser.BASE_URL
        const val LIST_BASE_URL = "$BASE_URL/list/2/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
