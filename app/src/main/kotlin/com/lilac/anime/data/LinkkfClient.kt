package com.lilac.anime.data

import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * Network client for Linkkf.
 *
 * Mobile networks can temporarily fail while switching between LTE/5G cells,
 * IPv4/IPv6 routes, or when the carrier connection is first established.
 * Keep the request conservative but retry transient failures instead of
 * turning a single failed request into an empty home screen.
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

    fun getDocument(url: String): Document {
        var lastError: Exception? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return requestDocument(url)
            } catch (e: Exception) {
                lastError = e
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS - 1) {
                    throw e
                }
                // Give LTE/5G routing and DNS a moment to recover before retrying.
                Thread.sleep(RETRY_DELAYS_MS[attempt])
            }
        }

        throw lastError ?: IOException("네트워크 요청에 실패했습니다.")
    }

    private fun requestDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; K) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Referer", "https://linkkf.tv/")
            .build()

        client.newCall(request).execute().use { response ->
            val code = response.code
            if (!response.isSuccessful) {
                throw LinkkfHttpException(code)
            }

            val html = response.body?.string()
                ?: throw IOException("응답 본문이 없습니다.")

            if (html.isBlank()) {
                throw IOException("빈 응답을 받았습니다.")
            }

            return Jsoup.parse(html, url)
        }
    }

    private fun isRetryable(error: Exception): Boolean {
        if (error is LinkkfHttpException) {
            return error.code == 408 || error.code == 425 || error.code == 429 || error.code in 500..599
        }
        return error is IOException || error is SocketTimeoutException
    }

    private class LinkkfHttpException(val code: Int) : IOException("HTTP $code")

    companion object {
        private const val MAX_ATTEMPTS = 3
        private val RETRY_DELAYS_MS = longArrayOf(700L, 1600L)
    }
}
