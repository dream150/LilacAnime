package com.lilac.anime.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lilac.anime.Episode
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Re:ANIME-only resolver. LinkKF playback does not enter this object. */
object ReAnimePlayerResolver {
    private const val TAG = "ReAnimePlayerResolver"
    private const val BASE = "https://reanime.to"
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val TIMEOUT_MS = 30_000L

    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    data class Result(
        val m3u8Url: String?,
        val referer: String?,
        val headers: String?,
        val subtitleUrl: String? = null,
        val subtitleReferer: String? = null
    )

    suspend fun resolve(context: Context, episode: Episode): Result {
        val page = episode.videoUrl.orEmpty()
        val parsed = runCatching { Uri.parse(page) }.getOrNull()
        val path = parsed?.path.orEmpty()
        val slug = when {
            "/watch/" in path -> path.substringAfter("/watch/").substringBefore('/').trim()
            "/anime/" in path -> path.substringAfter("/anime/").substringBefore('/').trim()
            else -> ""
        }
        val ep = parsed?.getQueryParameter("ep")?.toIntOrNull() ?: episode.number
        if (slug.isBlank() || ep <= 0) {
            return Result(null, null, null)
        }

        val flixUrl = findFlixUrl(slug, ep) ?: return Result(null, null, null)
        android.util.Log.d(TAG, "FLIX_URL episode=$ep url=$flixUrl")
        return captureWebView(context, flixUrl)
    }

    private fun findFlixUrl(slug: String, episode: Int): String? {
        // Current Re:ANIME exposes the episode links from /api/watch/{slug}/{ep}.
        // /api/flix/{anilist}/{ep} is kept as a fallback for older responses.
        val watch = getJson("$BASE/api/watch/${Uri.encode(slug)}/$episode")
        val links = watch?.optJSONArray("episode_links")
        val candidates = mutableListOf<Pair<String, String>>()
        if (links != null) {
            for (i in 0 until links.length()) {
                val item = links.optJSONObject(i) ?: continue
                val url = item.optString("dataLink").trim()
                    .ifBlank { item.optString("link").trim() }
                if (url.startsWith("https://flixcloud.cc/e/", true)) {
                    candidates += item.optString("serverName").trim() to url
                }
            }
        }
        pickServer(candidates)?.let { return it }

        val anime = watch?.optJSONObject("anime")
        val anilist = anime?.optInt("anilist", 0)?.takeIf { it > 0 }
            ?: anime?.optInt("anilist_id", 0)?.takeIf { it > 0 }
        if (anilist != null) {
            val flix = getJson("$BASE/api/flix/$anilist/$episode")
            val servers = flix?.optJSONArray("servers")
            val fallback = mutableListOf<Pair<String, String>>()
            if (servers != null) {
                for (i in 0 until servers.length()) {
                    val item = servers.optJSONObject(i) ?: continue
                    val url = item.optString("dataLink").trim()
                    if (url.startsWith("https://flixcloud.cc/e/", true)) {
                        fallback += item.optString("serverName").trim() to url
                    }
                }
            }
            pickServer(fallback)?.let { return it }
        }
        return null
    }

    private fun pickServer(candidates: List<Pair<String, String>>): String? {
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.first.equals("HD-2", true) }?.second
            ?: candidates.firstOrNull { it.first.contains("HD-2", true) }?.second
            ?: candidates.firstOrNull { it.first.equals("HD-1", true) }?.second
            ?: candidates.firstOrNull { it.first.contains("HD-1", true) }?.second
            ?: candidates.firstOrNull()?.second
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "$BASE/")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) throw IOException("HTTP ${response.code}")
            JSONObject(body)
        }
    }.onFailure { android.util.Log.e(TAG, "API_FAILED url=$url", it) }.getOrNull()

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureWebView(context: Context, flixUrl: String): Result =
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var finished = false
            var bestM3u8: String? = null
            var m3u8Referer: String? = null
            var m3u8Headers: String? = null
            var subtitle: String? = null
            var subtitleReferer: String? = null
            val seen = linkedSetOf<String>()

            fun choose(urls: Collection<String>): String? {
                return urls.firstOrNull { u ->
                    val uri = runCatching { Uri.parse(u) }.getOrNull()
                    val path = uri?.path.orEmpty().lowercase()
                    u.contains(".m3u8", true) &&
                        !path.contains("/api/m3u8/") &&
                        (path.endsWith("master.m3u8") || path.contains("/master"))
                } ?: urls.firstOrNull { u ->
                    val path = runCatching { Uri.parse(u).path.orEmpty().lowercase() }.getOrDefault("")
                    u.contains(".m3u8", true) && !path.contains("/api/m3u8/")
                }
            }

            fun complete() {
                if (finished) return
                val selected = bestM3u8 ?: choose(seen)
                if (selected.isNullOrBlank()) return
                finished = true
                val result = Result(selected, m3u8Referer ?: flixUrl, m3u8Headers, subtitle, subtitleReferer)
                main.post {
                    runCatching { webView?.stopLoading() }
                    runCatching { webView?.destroy() }
                    webView = null
                }
                if (continuation.isActive) continuation.resume(result)
            }

            fun timeout() {
                if (finished) return
                finished = true
                val result = Result(choose(seen), m3u8Referer ?: flixUrl, m3u8Headers, subtitle, subtitleReferer)
                main.post {
                    runCatching { webView?.stopLoading() }
                    runCatching { webView?.destroy() }
                    webView = null
                }
                if (continuation.isActive) continuation.resume(result)
            }

            continuation.invokeOnCancellation {
                main.post {
                    runCatching { webView?.stopLoading() }
                    runCatching { webView?.destroy() }
                    webView = null
                }
            }

            main.post {
                if (finished) return@post
                val view = WebView(context.applicationContext)
                webView = view
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = UA
                }

                fun inspect(url: String, requestHeaders: Map<String, String> = emptyMap()) {
                    val path = runCatching { Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
                    val ref = requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }
                        ?.value?.trim()?.takeIf { it.isNotBlank() }
                    if (path.endsWith(".vtt") || path.endsWith(".srt") || url.contains(".vtt?", true) || url.contains(".srt?", true)) {
                        if (subtitle == null) {
                            subtitle = url
                            subtitleReferer = ref ?: flixUrl
                        }
                    }
                    if (!url.contains(".m3u8", true) && !path.contains("m3u8", true)) return
                    if (path.contains("/api/m3u8/")) return
                    if (seen.add(url)) {
                        val headers = requestHeaders.entries.filter { it.key.isNotBlank() && it.value.isNotBlank() }
                        val ua = headers.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: UA
                        val origin = headers.firstOrNull { it.key.equals("Origin", true) }?.value
                        m3u8Referer = ref ?: m3u8Referer ?: flixUrl
                        m3u8Headers = buildList {
                            add("User-Agent: $ua")
                            m3u8Referer?.let { add("Referer: $it") }
                            origin?.let { add("Origin: $it") }
                            headers.firstOrNull { it.key.equals("Cookie", true) }?.value?.let { add("Cookie: $it") }
                        }.joinToString("\n")
                        bestM3u8 = choose(seen)
                        android.util.Log.d(TAG, "M3U8_CAPTURED url=$url selected=${bestM3u8 ?: "<pending>"}")
                        complete()
                    }
                }

                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val u = request?.url?.toString().orEmpty()
                        if (request?.isForMainFrame != true) return false
                        val host = runCatching { Uri.parse(u).host?.lowercase().orEmpty() }.getOrDefault("")
                        return host.isNotBlank() && host != "flixcloud.cc" && host != "www.flixcloud.cc" && !host.endsWith(".flixcloud.cc")
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val u = request?.url?.toString().orEmpty()
                        if (u.isNotBlank()) inspect(u, request?.requestHeaders.orEmpty())
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // The browser itself knows the post-decryption HLS URL. Performance
                        // entries are used as a second capture path because the HLS request
                        // may be made by fetch/XHR and not expose all headers to WebView.
                        view?.evaluateJavascript("""(function(){try{return JSON.stringify(performance.getEntriesByType('resource').map(function(e){return e.name||''}).filter(function(u){return /\\.m3u8(?:\\?|$)/i.test(u)}));}catch(e){return '[]';}})()""") { raw ->
                            runCatching {
                                val json = org.json.JSONArray(raw.orEmpty().trim('"').replace("\\\"", "\"").replace("\\/", "/"))
                                for (i in 0 until json.length()) inspect(json.optString(i))
                            }
                        }
                        super.onPageFinished(view, url)
                    }
                }

                view.loadUrl(flixUrl, mapOf("Referer" to "$BASE/", "User-Agent" to UA))
                main.postDelayed({ timeout() }, TIMEOUT_MS)
            }
        }
}
