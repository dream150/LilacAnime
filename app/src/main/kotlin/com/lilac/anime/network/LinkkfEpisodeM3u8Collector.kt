package com.lilac.anime.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lilac.anime.Episode
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Collects Linkkf's real stream URLs from episode watch pages.
 *
 * Linkkf episode.videoUrl is a WATCH PAGE URL, not the media URL.  The actual
 * stream request is made by the page/player and is observed from WebView's
 * shouldInterceptRequest callback.  Linkkf currently exposes the playable
 * stream as an index.m3u8 URL, so we intentionally look for that exact path.
 *
 * Up to MAX_WEBVIEWS pages are loaded concurrently. WebView instances are
 * created/used on Android's main thread; the WebView networking itself is
 * concurrent. This is safer than creating WebViews on arbitrary worker threads.
 */
object LinkkfEpisodeM3u8Collector {
    private const val TAG = "EpisodeChapters"
    private const val MAX_WEBVIEWS = 5
    private const val TIMEOUT_MS = 30_000L
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val mainHandler = Handler(Looper.getMainLooper())

    data class Result(
        val urls: Map<String, String>,
        // Referer actually observed on the browser request that fetched the M3U8.
        // This is preferable to hard-coding https://playv2.sub3.top/ because
        // Linkkf may generate a per-episode playhd3.php URL.
        val referers: Map<String, String>,
        val failedEpisodeIds: Set<String>
    )

    suspend fun collect(
        context: Context,
        episodes: List<Episode>,
        onStatus: (String) -> Unit = {}
    ): Result = suspendCancellableCoroutine { continuation ->
        val targets = episodes
            .filter { !it.videoUrl.isNullOrBlank() }
            .take(MAX_WEBVIEWS)

        if (targets.isEmpty()) {
            continuation.resume(Result(emptyMap(), emptyMap(), emptySet()))
            return@suspendCancellableCoroutine
        }

        val urls = LinkedHashMap<String, String>()
        val referers = LinkedHashMap<String, String>()
        val failed = LinkedHashSet<String>()
        val completed = AtomicInteger(0)
        val webViews = ArrayList<WebView>(targets.size)
        var finished = false

        fun finishIfDone() {
            if (finished) return
            if (completed.get() < targets.size) return
            finished = true
            Log.d(TAG, "M3U8_COLLECTION_COMPLETE success=${urls.size} referers=${referers.size} failed=${failed.size}")
            webViews.forEach { it.stopLoading(); it.destroy() }
            webViews.clear()
            if (continuation.isActive) continuation.resume(Result(urls.toMap(), referers.toMap(), failed.toSet()))
        }

        fun finishWithTimeout() {
            if (finished) return
            finished = true
            targets.forEach { target ->
                if (!urls.containsKey(target.id)) failed += target.id
            }
            Log.d(TAG, "M3U8_COLLECTION_TIMEOUT success=${urls.size} referers=${referers.size} failed=${failed.size}")
            webViews.forEach { it.stopLoading(); it.destroy() }
            webViews.clear()
            if (continuation.isActive) continuation.resume(Result(urls.toMap(), referers.toMap(), failed.toSet()))
        }

        mainHandler.postDelayed({ finishWithTimeout() }, TIMEOUT_MS)

        targets.forEachIndexed { index, episode ->
            val pageUrl = episode.videoUrl ?: return@forEachIndexed
            mainHandler.post {
                if (finished) return@post
                onStatus(
                    "M3U8_WEBVIEW_${index + 1}_START episode=${episode.number} " +
                        "display=${episode.displayNumber} id=${episode.id}"
                )
                onStatus("M3U8_WEBVIEW_${index + 1}_LOAD_PAGE episode=${episode.number} pageUrl=$pageUrl")
                Log.d(TAG, "M3U8_WEBVIEW_${index + 1}_LOAD episode=${episode.number} display=${episode.displayNumber} id=${episode.id} page=$pageUrl")

                @SuppressLint("SetJavaScriptEnabled")
                val webView = WebView(context.applicationContext).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        cacheMode = WebSettings.LOAD_DEFAULT
                        userAgentString = UA
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        // Linkkf's player is opened through a generated playhd3.php URL.
                        // Depending on the WebView/Chromium version, the Referer header of
                        // the subsequent m3u8 request is not always exposed through
                        // WebResourceRequest.requestHeaders. Keep the most recently
                        // observed playhd3.php URL as a per-WebView fallback.
                        private var lastPlayHdUrl: String? = null

                        private fun observePlayerUrl(url: String?) {
                            val value = url?.trim().orEmpty()
                            if (value.contains("/r2/playhd3.php", ignoreCase = true)) {
                                lastPlayHdUrl = value
                                Log.d(TAG, "M3U8_WEBVIEW_${index + 1}_PLAYHD_OBSERVED episode=${episode.number} url=$value")
                                onStatus("M3U8_WEBVIEW_${index + 1}_PLAYHD_OBSERVED episode=${episode.number} url=$value")
                            }
                        }

                        private fun report(url: String, requestReferer: String? = null) {
                            val path = runCatching { Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
                            if (!path.endsWith("/index.m3u8") && !path.endsWith("index.m3u8")) return
                            synchronized(urls) {
                                if (!urls.containsKey(episode.id)) {
                                    urls[episode.id] = url

                                    // WebView exposes the headers of the actual resource request.
                                    // For Linkkf this is commonly the generated playhd3.php URL,
                                    // e.g. https://playv2.sub3.top/r2/playhd3.php?... . Preserve
                                    // it exactly instead of replacing it with the host root.
                                    val observedReferer = requestReferer
                                        ?.trim()
                                        ?.takeIf { it.isNotBlank() }
                                        ?: lastPlayHdUrl
                                            ?.trim()
                                            ?.takeIf { it.isNotBlank() }
                                    if (observedReferer != null) {
                                        referers[episode.id] = observedReferer
                                    }

                                    Log.d(TAG, "M3U8_WEBVIEW_${index + 1}_FOUND episode=${episode.number} display=${episode.displayNumber} page=$pageUrl m3u8=$url referer=${observedReferer ?: "<none>"}")
                                    onStatus("M3U8_WEBVIEW_${index + 1}_FOUND episode=${episode.number} page=$pageUrl m3u8=$url referer=${observedReferer ?: "<none>"}")
                                    completed.incrementAndGet()
                                    mainHandler.post { finishIfDone() }
                                }
                            }
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            observePlayerUrl(url)
                            Log.d(TAG, "M3U8_WEBVIEW_${index + 1}_PAGE_START episode=${episode.number} url=$url")
                            onStatus("M3U8_WEBVIEW_${index + 1}_PAGE_START episode=${episode.number} url=$url")
                            super.onPageStarted(view, url, favicon)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            observePlayerUrl(url)
                            Log.d(TAG, "M3U8_WEBVIEW_${index + 1}_PAGE_FINISHED episode=${episode.number} url=$url")
                            onStatus("M3U8_WEBVIEW_${index + 1}_PAGE_FINISHED episode=${episode.number} url=$url")
                            // Do not navigate to the iframe ourselves. Loading the watch page
                            // is enough for the embedded player to issue its media request.
                            view?.evaluateJavascript(
                                """(function(){return Array.from(document.querySelectorAll('iframe[src],video[src],source[src]')).map(function(x){return x.src;}).join('\\n');})()""",
                                { raw ->
                                    val decoded = raw.orEmpty()
                                        .trim('"')
                                        .replace("\\u003d", "=")
                                        .replace("\\u0026", "&")
                                    decoded.split('\n').filter { it.isNotBlank() }.forEach { candidate ->
                                        Log.d(TAG, "M3U8_WEBVIEW_${index + 1}_MEDIA_ELEMENT episode=${episode.number} page=$pageUrl media=$candidate")
                                    onStatus("M3U8_WEBVIEW_${index + 1}_MEDIA_ELEMENT episode=${episode.number} url=$candidate")
                                    }
                                }
                            )
                            super.onPageFinished(view, url)
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            val requested = request?.url?.toString().orEmpty()
                            val code = errorResponse?.statusCode ?: -1
                            if (requested.contains("m3u8", true) || requested.contains("index.m3u8", true)) {
                                Log.d(TAG, "M3U8_WEBVIEW_${index + 1}_HTTP_ERROR episode=${episode.number} page=$pageUrl code=$code url=$requested")
                                onStatus("M3U8_WEBVIEW_${index + 1}_HTTP_ERROR episode=${episode.number} code=$code url=$requested")
                            }
                            super.onReceivedHttpError(view, request, errorResponse)
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            request?.let {
                                val url = it.url?.toString() ?: return@let
                                observePlayerUrl(url)
                                val requestReferer = it.requestHeaders.entries.firstOrNull { entry ->
                                    entry.key.equals("Referer", ignoreCase = true)
                                }?.value
                                report(url, requestReferer)
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        @Suppress("DEPRECATION")
                        override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                            url?.let { report(it, null) }
                            return super.shouldInterceptRequest(view, url)
                        }
                    }
                }
                webViews += webView
                webView.loadUrl(pageUrl)
            }
        }

        continuation.invokeOnCancellation {
            mainHandler.post {
                finished = true
                webViews.forEach { it.stopLoading(); it.destroy() }
                webViews.clear()
            }
        }
    }
}
