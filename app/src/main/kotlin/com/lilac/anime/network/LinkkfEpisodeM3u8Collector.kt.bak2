package com.lilac.anime.network

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
import com.lilac.anime.data.subtitle.*
import com.lilac.anime.player.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.detail.*
import com.lilac.anime.ui.home.*
import com.lilac.anime.ui.navigation.*
import com.lilac.anime.ui.search.*
import com.lilac.anime.ui.settings.*
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
        // Exact Referer observed on the subtitle resource request.
        val subtitleUrls: Map<String, String> = emptyMap(),
        val subtitleReferers: Map<String, String> = emptyMap(),
        val failedEpisodeIds: Set<String> = emptySet()
    )

    suspend fun collect(
        context: Context,
        episodes: List<Episode>,
        waitForSubtitle: Boolean = false,
        onSubtitleFound: (episodeId: String, url: String, referer: String?) -> Unit = { _, _, _ -> },
        onStatus: (String) -> Unit = {}
    ): Result = suspendCancellableCoroutine { continuation ->
        val targets = episodes
            .filter { !it.videoUrl.isNullOrBlank() }
            .take(MAX_WEBVIEWS)

        if (targets.isEmpty()) {
            continuation.resume(Result(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptySet()))
            return@suspendCancellableCoroutine
        }

        val urls = LinkedHashMap<String, String>()
        val referers = LinkedHashMap<String, String>()
        val failed = LinkedHashSet<String>()
        val subtitleUrls = LinkedHashMap<String, String>()
        val subtitleReferers = LinkedHashMap<String, String>()
        val completed = AtomicInteger(0)
        val webViews = ArrayList<WebView>(targets.size)
        var finished = false

        fun finishIfDone() {
            if (finished) return
            if (completed.get() < targets.size) return
            finished = true
            Log.d(TAG, "M3U8_COLLECTION_COMPLETE success=${urls.size} referers=${referers.size} failed=${failed.size}")
            val resultSnapshot = Result(urls.toMap(), referers.toMap(), subtitleUrls.toMap(), subtitleReferers.toMap(), failed.toSet())
            if (waitForSubtitle) {
                webViews.forEach { it.stopLoading(); it.destroy() }
                webViews.clear()
            } else {
                // Do not destroy immediately: the subtitle request is frequently emitted
                // just after index.m3u8. This is not a playback delay because the continuation
                // is resumed first; cleanup only happens in the background.
                val cleanupViews = webViews.toList()
                mainHandler.postDelayed({
                    cleanupViews.forEach { runCatching { it.stopLoading(); it.destroy() } }
                    webViews.removeAll(cleanupViews.toSet())
                }, 5_000L)
            }
            if (continuation.isActive) continuation.resume(resultSnapshot)
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
            if (continuation.isActive) continuation.resume(Result(urls.toMap(), referers.toMap(), subtitleUrls.toMap(), subtitleReferers.toMap(), failed.toSet()))
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
                        private var playerPageNavigated = false

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

                            // Keep subtitle discovery independent from the video Referer.
                            // The Referer attached to the actual VTT request is the only
                            // value stored as subtitleReferer. Never substitute the video
                            // playhd3.php URL here.
                            if (path.endsWith(".vtt") || path.endsWith(".srt")) {
                                synchronized(urls) {
                                    if (!subtitleUrls.containsKey(episode.id)) {
                                        subtitleUrls[episode.id] = url
                                        // Match the streaming extractor: Chromium may omit
                                        // Referer from WebResourceRequest.requestHeaders, but
                                        // the same WebView has already observed the playhd3.php
                                        // page that caused the subtitle request.
                                        val subtitleRef = requestReferer?.trim()?.takeIf { it.isNotBlank() }
                                            ?: lastPlayHdUrl?.trim()?.takeIf { it.isNotBlank() }
                                        subtitleRef?.let { subtitleReferers[episode.id] = it }
                                        onSubtitleFound(episode.id, url, subtitleRef)
                                        Log.d(TAG, "M3U8_WEBVIEW_${index + 1}_SUBTITLE_FOUND episode=${episode.number} url=$url subtitleReferer=${subtitleRef ?: "<none>"}")
                                        onStatus("M3U8_WEBVIEW_${index + 1}_SUBTITLE_FOUND episode=${episode.number} url=$url subtitleReferer=${subtitleRef ?: "<none>"}")
                                    }
                                }
                            }

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
                                    if (waitForSubtitle) {
                                        // Download resolution may need the VTT URL in the same
                                        // Result. Give the player a short grace period, but do not
                                        // make normal streaming pay this cost.
                                        mainHandler.postDelayed({ finishIfDone() }, 1_500L)
                                    } else {
                                        // Streaming must start as soon as the media URL is known.
                                        // The WebView remains alive briefly in the background so a
                                        // VTT request that follows the m3u8 can still be captured.
                                        finishIfDone()
                                    }
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

                            // linkkf.app's watch page does not embed the player URL in the
                            // DOM. Resolve the episode token through apilink2.php, then load
                            // the generated playhd3.php page. This mirrors the real browser
                            // chain: linkkf.app -> kf.carsstore365.com -> playhd3.php -> m3u8.
                            val finishedHost = runCatching {
                                Uri.parse(url.orEmpty()).host?.lowercase()
                            }.getOrNull()
                            if (!playerPageNavigated &&
                                (finishedHost == "linkkf.app" ||
                                    finishedHost == "www.linkkf.app" ||
                                    finishedHost == "kf.carsstore365.com")
                            ) {
                                val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
                                val path = uri?.path.orEmpty()
                                val parts = path.trimEnd('/').split("/")
                                val upIndex = parts.indexOfLast { it == "up" }
                                val postId = if (upIndex >= 0 && upIndex + 1 < parts.size) parts[upIndex + 1] else ""
                                val slugRaw = uri?.getQueryParameter("slug").orEmpty().trim()
                                val slug = slugRaw.toIntOrNull()?.toString() ?: slugRaw.lowercase()

                                if (postId.isNotBlank() && slug.isNotBlank()) {
                                    playerPageNavigated = true
                                    val token = "$postId" + "v" + slug
                                    Thread {
                                        try {
                                            val resolver = OkHttpClient.Builder()
                                                .followRedirects(true)
                                                .followSslRedirects(true)
                                                .connectTimeout(10, TimeUnit.SECONDS)
                                                .readTimeout(15, TimeUnit.SECONDS)
                                                .build()
                                            val apiUrl =
                                                "https://emdlinkkf.5imgdarr.top/apilink2.php?data=" +
                                                    java.net.URLEncoder.encode(token, "UTF-8")
                                            val request = Request.Builder()
                                                .url(apiUrl)
                                                .header("User-Agent", UA)
                                                .header("Accept", "application/json")
                                                .header("Referer", "https://kf.carsstore365.com/")
                                                .build()
                                            resolver.newCall(request).execute().use { response ->
                                                val root = JSONObject(response.body?.string().orEmpty())
                                                val data = root.optJSONArray("data")
                                                var playerUrl: String? = null
                                                var fallback: String? = null
                                                if (data != null) {
                                                    for (i in 0 until data.length()) {
                                                        val item = data.optJSONObject(i) ?: continue
                                                        val server = item.optString("server").trim().uppercase()
                                                        val link = item.optString("link").trim()
                                                        if (link.isBlank()) continue
                                                        if (server == "NR-HD") playerUrl = link
                                                        if (fallback == null) fallback = link
                                                    }
                                                }
                                                val resolved = playerUrl ?: fallback
                                                if (response.isSuccessful && !resolved.isNullOrBlank()) {
                                                    lastPlayHdUrl = resolved
                                                    Log.d(TAG, "M3U8_WEBVIEW_${index + 1}_PLAYHD_API episode=${episode.number} url=$resolved")
                                                    onStatus("M3U8_WEBVIEW_${index + 1}_PLAYHD_API episode=${episode.number} url=$resolved")
                                                    mainHandler.post { view?.loadUrl(resolved) }
                                                } else {
                                                    playerPageNavigated = false
                                                }
                                            }
                                        } catch (t: Throwable) {
                                            playerPageNavigated = false
                                            Log.e(TAG, "M3U8_WEBVIEW_${index + 1}_PLAYER_RESOLVE_FAILED episode=${episode.number}", t)
                                        }
                                    }.start()
                                }
                            }

                            // Some Linkkf pages inject the player after onPageFinished.
                            // Poll the DOM, performance resource list, and HTML for a short
                            // period so we don't miss the generated playhd3.php URL.
                            fun pollPlayerResources(attempt: Int) {
                                if (finished || attempt > 12) return
                                view?.evaluateJavascript(
                                    """(function(){var a=[];try{a=a.concat(Array.from(document.querySelectorAll('iframe[src],video[src],source[src],a[href],[data-src]')).map(function(x){return x.src||x.href||x.getAttribute('data-src')||'';}));}catch(e){} try{a=a.concat(performance.getEntriesByType('resource').map(function(x){return x.name||'';}));}catch(e){} try{var h=document.documentElement.innerHTML;var m=h.match(/https?:\\/\\/[^\"' ]+\\/r2\\/playhd3\\.php[^\"' <]*/i);if(m)a.push(m[0]);}catch(e){} return a.filter(Boolean).join('\\n');})()""",
                                    { raw ->
                                        val decoded = raw.orEmpty().trim('"')
                                            .replace("\\u003d", "=")
                                            .replace("\\u0026", "&")
                                            .replace("\\/", "/")
                                        val candidates = decoded.split('\n').filter { it.isNotBlank() }
                                        val playerUrl = candidates.firstOrNull { candidate ->
                                            runCatching {
                                                val uri = Uri.parse(candidate)
                                                val host = uri.host?.lowercase().orEmpty()
                                                val path = uri.path.orEmpty().lowercase()
                                                (host == "play.sub3.top" || host == "playv2.sub3.top") &&
                                                    path.contains("playhd3.php")
                                            }.getOrDefault(false)
                                        }
                                        if (playerUrl != null && !playerPageNavigated) {
                                            playerPageNavigated = true
                                            lastPlayHdUrl = playerUrl
                                            Log.d(TAG, "M3U8_WEBVIEW_${index + 1}_PLAYHD_POLLED episode=${episode.number} url=$playerUrl")
                                            onStatus("M3U8_WEBVIEW_${index + 1}_PLAYHD_POLLED episode=${episode.number} url=$playerUrl")
                                            view?.loadUrl(playerUrl)
                                        }
                                    }
                                )
                                mainHandler.postDelayed({ pollPlayerResources(attempt + 1) }, 500L)
                            }
                            pollPlayerResources(0)

                            // Some Linkkf pages expose a generated play.php/playhd3.php
                            // URL instead of immediately issuing the media request. Open that
                            // player URL in this hidden WebView so the actual index.m3u8 request
                            // (and its Referer) can be observed.
                            view?.evaluateJavascript(
                                """(function(){return Array.from(document.querySelectorAll('iframe[src],video[src],source[src],a[href],[data-src]')).map(function(x){return x.src || x.href || x.getAttribute('data-src') || '';}).filter(Boolean).join('\\n');})()""",
                                { raw ->
                                    val decoded = raw.orEmpty()
                                        .trim('"')
                                        .replace("\\u003d", "=")
                                        .replace("\\u0026", "&")
                                    decoded.split('\n').filter { it.isNotBlank() }.forEach { candidate ->
                                        Log.d(TAG, "M3U8_WEBVIEW_${index + 1}_MEDIA_ELEMENT episode=${episode.number} page=$pageUrl media=$candidate")
                                        onStatus("M3U8_WEBVIEW_${index + 1}_MEDIA_ELEMENT episode=${episode.number} url=$candidate")
                                    }

                                    if (!playerPageNavigated) {
                                        val playerUrl = decoded.split('\n').firstOrNull { candidate ->
                                            runCatching {
                                                val uri = Uri.parse(candidate)
                                                val host = uri.host?.lowercase().orEmpty()
                                                val path = uri.path.orEmpty().lowercase()
                                                (host == "play.sub3.top" || host == "playv2.sub3.top") &&
                                                    (path.contains("play.php") || path.contains("playhd3.php"))
                                            }.getOrDefault(false)
                                        }
                                        if (playerUrl != null) {
                                            playerPageNavigated = true
                                            Log.d(TAG, "M3U8_WEBVIEW_${index + 1}_PLAYER_PAGE episode=${episode.number} url=$playerUrl")
                                            onStatus("M3U8_WEBVIEW_${index + 1}_PLAYER_PAGE episode=${episode.number} url=$playerUrl")
                                            view?.loadUrl(playerUrl)
                                        }
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
                            url?.let {
                                if (it.contains("/r2/playhd3.php", ignoreCase = true)) lastPlayHdUrl = it
                                report(it, null)
                            }
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
