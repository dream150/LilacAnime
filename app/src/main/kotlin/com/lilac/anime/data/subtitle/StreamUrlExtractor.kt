package com.lilac.anime.data.subtitle

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
import com.lilac.anime.network.*
import com.lilac.anime.player.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.detail.*
import com.lilac.anime.ui.home.*
import com.lilac.anime.ui.navigation.*
import com.lilac.anime.ui.search.*
import com.lilac.anime.ui.settings.*
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.lilac.anime.core.model.StreamQuality
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hidden WebView stream detector. It never exposes the provider page to the user.
 * For Animenosub we only permit the source page and its embedded player host as
 * top-level navigation, preventing ad redirects from taking over the app.
 */
data class SubtitleTrack(
    val url: String,
    val language: String,
    val label: String,
    val format: String
)

/**
 * Short-lived in-memory cache for the cheap Re:Anime /api/flix lookup.
 * The FlixCloud __pk and m3u8 are intentionally NOT cached because they belong
 * to a live player page/session. Caching only dataLink removes repeated API
 * latency when the user re-enters the player or revisits an episode.
 */
private object ReAnimeFlixLinkCache {
    private const val TTL_MS = 5 * 60 * 1000L
    private data class Entry(val url: String, val atMs: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(key: String): String? {
        val entry = entries[key] ?: return null
        if (System.currentTimeMillis() - entry.atMs > TTL_MS) {
            entries.remove(key, entry)
            return null
        }
        return entry.url
    }

    fun put(key: String, url: String) {
        entries[key] = Entry(url, System.currentTimeMillis())
    }
}

@Composable
fun StreamUrlExtractor(
    targetUrl: String,
    modifier: Modifier = Modifier,
    onQualitiesFound: (List<StreamQuality>) -> Unit,
    onSubtitleFound: (String) -> Unit,
    onSubtitleTracksFound: (List<SubtitleTrack>) -> Unit = {},
    onSubtitleRefererFound: (String, String) -> Unit = { _, _ -> },
    onRefererFound: (String) -> Unit = {},
    onAuthRequired: () -> Unit = {},
    onFlixCloudPkFound: (String) -> Unit = {},
    allowedHosts: Set<String> = emptySet(),
    restartKey: Any? = null,
    reAnimeAnilistId: Int? = null,
    reAnimeEpisodeNumber: Int? = null
) {
    val detectedUrls = remember(targetUrl, restartKey) { linkedSetOf<String>() }
    var isSubtitleFound by remember(targetUrl, restartKey) { mutableStateOf(false) }
    var subtitleTracksReported by remember(targetUrl, restartKey) { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // Every polling handler and resolver thread belongs to this extractor instance.
    // Without explicit cancellation, old episodes can keep touching a destroyed WebView
    // and steal/replace FlixCloud state from the next episode.
    val extractorHandlers = remember(targetUrl, restartKey) { mutableListOf<Handler>() }
    val extractorActive = remember(targetUrl, restartKey) { AtomicBoolean(true) }
    // Candidate index is owned by the composable session so every resolver callback
    // sees the same mutable value even when the WebView factory is recreated.
    var reAnimeCandidateIndex by remember(targetUrl, restartKey) { mutableIntStateOf(0) }
    var reAnimeActiveCandidate by remember(targetUrl, restartKey) { mutableStateOf<String?>(null) }
    val resolverThreads = remember(targetUrl, restartKey) { mutableListOf<Thread>() }
    // Explicitly destroy the hidden Chromium instance when this extractor leaves the
    // composition. Re:Anime can create one extractor per episode; retaining them
    // eventually exhausts WebView/Chromium resources and playback starts failing.
    val webViewRef = remember(targetUrl, restartKey) { mutableStateOf<WebView?>(null) }
    DisposableEffect(targetUrl, restartKey) {
        onDispose {
            extractorActive.set(false)
            mainHandler.removeCallbacksAndMessages(null)
            extractorHandlers.toList().forEach { runCatching { it.removeCallbacksAndMessages(null) } }
            resolverThreads.toList().forEach { runCatching { it.interrupt() } }
            extractorHandlers.clear()
            resolverThreads.clear()
            webViewRef.value?.let { view ->
                runCatching { view.stopLoading() }
                runCatching { view.loadUrl("about:blank") }
                runCatching { view.clearHistory() }
                runCatching { view.destroy() }
            }
            webViewRef.value = null
        }
    }

    key(targetUrl, restartKey) {
        AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                webViewRef.value = this
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                }

                // WebResourceClient.shouldInterceptRequest runs on a Chromium/background thread.
                // Never touch WebView/WebSettings from that callback. Capture the UA once on the
                // WebView thread and only use this immutable value from request interception.
                val capturedWebViewUserAgent = settings.userAgentString.orEmpty()

                val targetHost = runCatching { android.net.Uri.parse(targetUrl).host?.lowercase() }.getOrNull()
                val inferredHosts = when {
                    targetHost == "animenosub.to" || targetHost == "www.animenosub.to" ->
                        setOf("animenosub.to", "www.animenosub.to")
                    targetHost == "linkkf.tv" || targetHost == "www.linkkf.tv" ||
                        targetHost == "linkkf.tckopke.com" || targetHost == "linkkf.app" ||
                        targetHost == "www.linkkf.app" || targetHost == "kf.carsstore365.com" ->
                        setOf(
                            "linkkf.tv", "www.linkkf.tv",
                            "linkkf.app", "www.linkkf.app",
                            "kf.carsstore365.com",
                            "linkkf.tckopke.com", "tckopke.com", "www.tckopke.com"
                        )
                    targetHost == "reanime.to" || targetHost == "www.reanime.to" ->
                        setOf("reanime.to", "www.reanime.to", "flixcloud.cc", "www.flixcloud.cc")
                    else -> emptySet()
                }
                val safeHosts = (allowedHosts + inferredHosts + listOfNotNull(targetHost) +
                    if (
                        targetHost == "linkkf.tckopke.com" ||
                        targetHost == "linkkf.tv" || targetHost == "www.linkkf.tv" ||
                        targetHost == "linkkf.app" || targetHost == "www.linkkf.app" ||
                        targetHost == "kf.carsstore365.com"
                    )
                        setOf(
                            "play.sub3.top",
                            "playv2.sub3.top",
                            "emdlinkkf.5imgdarr.top",
                            "linkkf1.5imgdarr.top",
                            "linkkfep1.5imgdarr.top"
                        ) else emptySet()
                    ).map { it.lowercase() }.toSet()
                var iframeHosts = emptySet<String>()
                var playerPageNavigated = false
                var reanimeServerSelectionStarted = false
                var reanimeServerSelected = false
                var lastPlayHdReferer: String? = null
                var lastRequestReferer: String? = null
                var lastM3u8Referer: String? = null
                var lastM3u8Headers: String? = null
                var reanimeEncryptedMasterUrl: String? = null
                var reanimeDecryptedM3u8Reported = false
                var reanimeMasterFallbackPosted = false
                var flixCloudPk: String? = null
                var flixPkPollStarted = false
                var reanimeCandidates: List<String> = emptyList()
                var reanimeCandidateTimeout: Runnable? = null
                var reanimeStreamReady = false
                var linkkfPlayerResolved = false

                fun cancelReAnimeCandidateTimeout() {
                    reanimeCandidateTimeout?.let { mainHandler.removeCallbacks(it) }
                    reanimeCandidateTimeout = null
                }

                fun observePlayHd(url: String?) {
                    val value = url?.trim().orEmpty()
                    if (value.contains("/r2/playhd3.php", ignoreCase = true)) {
                        lastPlayHdReferer = value
                        Log.d("AnimenosubStream", "LINKKF_PLAYHD_REFERER_CAPTURED $value")
                        mainHandler.post { onRefererFound(value) }
                    }
                }

                fun isAllowedNavigation(url: String): Boolean {
                    val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
                    if (safeHosts.isEmpty()) return true
                    return host in safeHosts || host in iframeHosts
                }

                fun emitQualities() {
                    val hasFlixCloud = detectedUrls.any {
                        runCatching {
                            android.net.Uri.parse(it).host?.lowercase()?.contains("flixcloud.cc") == true
                        }.getOrDefault(false)
                    }
                    if (hasFlixCloud && flixCloudPk.isNullOrBlank()) return

                    val urls = detectedUrls.toList()
                    mainHandler.post {
                        if ((targetHost == "reanime.to" || targetHost == "www.reanime.to") &&
                            urls.isNotEmpty() && !flixCloudPk.isNullOrBlank()
                        ) {
                            reanimeStreamReady = true
                            cancelReAnimeCandidateTimeout()
                        }
                        val ordered = urls.sortedWith(
                            compareByDescending<String> {
                                runCatching {
                                    android.net.Uri.parse(it).lastPathSegment?.contains("master", true) == true
                                }.getOrDefault(false)
                            }
                        )
                        val qualities = ordered.mapIndexed { index, u ->
                            val uPath = runCatching {
                                android.net.Uri.parse(u).path.orEmpty().lowercase()
                            }.getOrDefault("")
                            val label = when {
                                uPath.contains("1080") -> "1080p"
                                uPath.contains("720") -> "720p"
                                uPath.contains("480") -> "480p"
                                uPath.contains("360") -> "360p"
                                uPath.contains("master") -> "Auto"
                                else -> "Stream ${index + 1}"
                            }
                            StreamQuality(
                                label = label,
                                url = u,
                                referer = lastM3u8Referer ?: lastPlayHdReferer,
                                headers = lastM3u8Headers,
                                flixCloudPk = flixCloudPk
                            )
                        }.distinctBy { it.url }
                        onQualitiesFound(qualities)
                    }
                }

                fun pollFlixCloudPk(view: WebView) {
                    if (flixPkPollStarted) return
                    flixPkPollStarted = true
                    var attempts = 0
                    val handler = Handler(Looper.getMainLooper())
                    extractorHandlers += handler
                    val poll = object : Runnable {
                        override fun run() {
                            if (!extractorActive.get() || flixCloudPk != null || attempts++ >= 40) return
                            view.evaluateJavascript(
                                """(function(){try{return window.__pk||''}catch(e){return ''}})()"""
                            ) { raw ->
                                val value = raw.orEmpty()
                                    .trim('"')
                                    .replace("\u003d", "=")
                                    .replace("\\/", "/")
                                    .trim()
                                if (value.isNotBlank() && value != "null") {
                                    flixCloudPk = value
                                    Log.d("ReAnimeStream", "FLIXCLOUD_PK_CAPTURED length=${value.length}")
                                    mainHandler.post { onFlixCloudPkFound(value) }
                                    emitQualities()
                                } else {
                                    handler.postDelayed(this, 250L)
                                }
                            }
                        }
                    }
                    handler.post(poll)
                }

                fun reportM3u8(url: String) {
                    val path = runCatching { android.net.Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
                    if (!path.endsWith(".m3u8")) return

                    val isReAnime = targetHost == "reanime.to" || targetHost == "www.reanime.to"

                    // FlixCloud returns encrypted M3U8 text to Chromium and decrypts it
                    // inside its JavaScript player. The WebView request interceptor sees
                    // the real encrypted URL, which is exactly what our local proxy needs.
                    // Do not wait for a second "decrypted" URL and do not use a fixed __pk.
                    if (!detectedUrls.add(url)) return

                    if (isReAnime) {
                        val active = reAnimeActiveCandidate
                        val requestRef = lastM3u8Referer.orEmpty()
                        if (active != null && requestRef.isNotBlank()) {
                            val activePath = runCatching { android.net.Uri.parse(active).path.orEmpty() }.getOrDefault("")
                            val refPath = runCatching { android.net.Uri.parse(requestRef).path.orEmpty() }.getOrDefault("")
                            if (activePath.isNotBlank() && refPath.isNotBlank() && activePath != refPath) {
                                Log.d("ReAnimeStream", "STALE_M3U8_IGNORED active=$active requestRef=$requestRef url=$url")
                                return
                            }
                        }
                        Log.d("ReAnimeStream", "FLIXCLOUD_M3U8_CAPTURED $url")
                    }

                    if (lastM3u8Headers.isNullOrBlank()) {
                        val capturedHeaders = buildList {
                            capturedWebViewUserAgent.takeIf { it.isNotBlank() }?.let { add("User-Agent: $it") }
                            lastM3u8Referer?.takeIf { it.isNotBlank() }?.let { add("Referer: $it") }
                        }.joinToString("\n")
                        lastM3u8Headers = capturedHeaders.takeIf { it.isNotBlank() }
                    }

                    Log.d(
                        "ReAnimeStream",
                        "M3U8_FOUND $url referer=${lastM3u8Referer ?: "<none>"} " +
                            "headers=${lastM3u8Headers?.replace("\n", " | ") ?: "<none>"}"
                    )

                    emitQualities()
                }

                fun pollReAnimeDecryptedM3u8(view: WebView) {
                    if (targetHost != "reanime.to" && targetHost != "www.reanime.to") return

                    var attempts = 0
                    val pollHandler = Handler(Looper.getMainLooper())
                    extractorHandlers += pollHandler
                    val poll = object : Runnable {
                        override fun run() {
                            if (!extractorActive.get() || reanimeDecryptedM3u8Reported || attempts++ >= 30) return

                            view.evaluateJavascript(
                                """(function(){
                                    try {
                                        var entries = performance.getEntriesByType('resource') || [];
                                        return entries.map(function(e){return e.name || '';})
                                            .filter(function(u){return /\\.m3u8(?:\\?|$)/i.test(u);})
                                            .join('\\n');
                                    } catch(e) {
                                        return '';
                                    }
                                })()"""
                            ) { raw ->
                                val decoded = raw.orEmpty()
                                    .replace("\\u003d", "=")
                                    .replace("\\u0026", "&")
                                    .replace("\\/", "/")
                                    .replace("\\n", "\n")

                                decoded.split('\n')
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                    .firstOrNull { candidate ->
                                        candidate != reanimeEncryptedMasterUrl &&
                                            !candidate.equals(reanimeEncryptedMasterUrl, true)
                                    }
                                    ?.let { candidate ->
                                        Log.d(
                                            "ReAnimeStream",
                                            "DECRYPTED_M3U8_PERFORMANCE_CAPTURE $candidate"
                                        )
                                        reportM3u8(candidate)
                                    }
                            }

                            if (!reanimeDecryptedM3u8Reported) {
                                pollHandler.postDelayed(this, 500L)
                            }
                        }
                    }
                    pollHandler.post(poll)
                }


                fun resolveLinkkfPlayer(view: WebView, pageUrl: String?) {
                    if (linkkfPlayerResolved) return
                    val host = runCatching { android.net.Uri.parse(pageUrl.orEmpty()).host?.lowercase() }.getOrNull()
                    if (host != "linkkf.app" && host != "www.linkkf.app" && host != "kf.carsstore365.com") return

                    val uri = runCatching { android.net.Uri.parse(pageUrl.orEmpty()) }.getOrNull() ?: return
                    val path = uri.path.orEmpty()
                    if (!path.contains("/up/") || !path.contains("/watch")) return

                    val parts = path.trimEnd('/').split("/")
                    val postId = parts.indexOfLast { it == "up" }.let { index ->
                        if (index >= 0 && index + 1 < parts.size) parts[index + 1] else ""
                    }.trim()
                    val slugRaw = uri.getQueryParameter("slug").orEmpty().trim()
                    if (postId.isBlank() || slugRaw.isBlank()) return

                    val slug = slugRaw.toIntOrNull()?.toString() ?: slugRaw.lowercase()
                    val token = "$postId" + "v" + slug

                    linkkfPlayerResolved = true
                    thread(name = "LinkkfPlayerResolve", isDaemon = true) {
                        try {
                            val apiUrl =
                                "https://emdlinkkf.5imgdarr.top/apilink2.php?data=" +
                                    java.net.URLEncoder.encode(token, "UTF-8")
                            val request = Request.Builder()
                                .url(apiUrl)
                                .header("User-Agent", capturedWebViewUserAgent)
                                .header("Accept", "application/json")
                                .header("Referer", "https://kf.carsstore365.com/")
                                .build()
                            val resolverClient = OkHttpClient.Builder()
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .connectTimeout(10, TimeUnit.SECONDS)
                                .readTimeout(15, TimeUnit.SECONDS)
                                .build()

                            resolverClient.newCall(request).execute().use { response ->
                                val body = response.body?.string().orEmpty()
                                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                                val root = JSONObject(body)
                                val data = root.optJSONArray("data")
                                    ?: throw IllegalStateException("Linkkf player data missing")

                                var preferred: String? = null
                                var fallback: String? = null
                                for (i in 0 until data.length()) {
                                    val item = data.optJSONObject(i) ?: continue
                                    val server = item.optString("server").trim().uppercase()
                                    val link = item.optString("link").trim()
                                    if (link.isBlank()) continue
                                    if (server == "NR-HD") preferred = link
                                    if (fallback == null) fallback = link
                                }
                                val playerUrl = preferred ?: fallback
                                    ?: throw IllegalStateException("Linkkf player link missing")

                                Log.d("AnimenosubStream", "LINKKF_PLAYER_RESOLVED token=$token url=$playerUrl")
                                mainHandler.post {
                                    // Loading the player after the watch page has been opened
                                    // preserves the browser's normal Referer chain.
                                    view.loadUrl(playerUrl)
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e("AnimenosubStream", "LINKKF_PLAYER_RESOLVE_FAILED token=$token", t)
                            linkkfPlayerResolved = false
                        }
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        observePlayHd(url)
                        val host = runCatching { android.net.Uri.parse(url.orEmpty()).host?.lowercase() }.getOrNull()
                        if (!host.isNullOrBlank()) {
                            // Linkkf's watch URL currently redirects to linkkf.tckopke.com.
                            // Keep the redirect inside the extractor instead of treating it as an ad.
                            if (targetHost == "linkkf.tv" || targetHost == "www.linkkf.tv") {
                                if (host == "linkkf.tckopke.com" || host == "tckopke.com" || host == "www.tckopke.com") {
                                    iframeHosts = iframeHosts + host
                                }
                            }
                        }
                        super.onPageStarted(view, url, favicon)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return true
                        if (request.isForMainFrame && !isAllowedNavigation(url)) {
                            return true
                        }
                        return false
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        val url = request?.url?.toString().orEmpty()
                        val code = errorResponse?.statusCode ?: -1
                        Log.d("AnimenosubStream", "HTTP_ERROR code=$code url=$url")
                        val path = runCatching { android.net.Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
                        if (code == 404 && path.contains("m3u8")) {
                            Log.d("AnimenosubStream", "M3U8_AUTH_REQUIRED url=$url")
                            mainHandler.post { onAuthRequired() }
                        }
                        super.onReceivedHttpError(view, request, errorResponse)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                        val path = runCatching { android.net.Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
                        val requestReferer = request?.requestHeaders?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value
                        if (!requestReferer.isNullOrBlank()) lastRequestReferer = requestReferer.trim()
                        if (path.endsWith(".m3u8")) {
                            // __pk lives in the FlixCloud player window. Chromium can
                            // report the actual fetch9 m3u8 request from an iframe, so
                            // do not require the request host itself to be flixcloud.cc.
                            if (flixCloudPk == null) {
                                mainHandler.post { view?.let { pollFlixCloudPk(it) } }
                            }
                            if (!requestReferer.isNullOrBlank()) {
                                lastM3u8Referer = requestReferer.trim()
                                android.util.Log.d("ReAnimeStream", "M3U8_REFERER_CAPTURED $lastM3u8Referer")
                            }
                            val requestCookie = request?.requestHeaders?.entries
                                ?.firstOrNull { it.key.equals("Cookie", true) }?.value
                            // Do not access WebView/CookieManager synchronously here. This callback
                            // is not guaranteed to run on the WebView/UI thread. Prefer headers
                            // supplied by Chromium and use the immutable UA captured above.
                            val cookie = requestCookie.orEmpty()
                            val headers = request?.requestHeaders.orEmpty()
                            val ua = headers.entries
                                .firstOrNull { it.key.equals("User-Agent", true) }?.value
                                ?.takeIf { it.isNotBlank() }
                                ?: capturedWebViewUserAgent
                            val origin = headers.entries
                                .firstOrNull { it.key.equals("Origin", true) }?.value
                                ?.trim()
                            lastM3u8Headers = buildList {
                                ua.takeIf { it.isNotBlank() }?.let { add("User-Agent: $it") }
                                lastM3u8Referer?.takeIf { it.isNotBlank() }?.let { add("Referer: $it") }
                                origin?.takeIf { it.isNotBlank() }?.let { add("Origin: $it") }
                                cookie.takeIf { it.isNotBlank() }?.let { add("Cookie: $it") }
                            }.joinToString("\n").takeIf { it.isNotBlank() }
                            android.util.Log.d("ReAnimeStream", "M3U8_REQUEST_HEADERS ${lastM3u8Headers?.replace("\n", " | ") ?: "<none>"}")
                        }
                        if (url.contains("/r2/playhd3.php", true)) {
                            lastPlayHdReferer = url
                            mainHandler.post { onRefererFound(url) }
                        }
                        if (!requestReferer.isNullOrBlank() && requestReferer.contains("/r2/playhd3.php", true)) {
                            lastPlayHdReferer = requestReferer.trim()
                            mainHandler.post { onRefererFound(lastPlayHdReferer!!) }
                        }

                        if (!isSubtitleFound && (path.endsWith(".vtt") || path.endsWith(".srt"))) {
                            // Re:ANIME exposes a complete subtitle array in the FlixCloud HTML.
                            // Wait for that manifest so an arbitrary first network request (often
                            // English) cannot silently become the selected track.
                            val isReAnimePlayer = targetHost == "reanime.to" || targetHost == "www.reanime.to" ||
                                url.contains("flixcloud.cc", ignoreCase = true)
                            if (!isReAnimePlayer) {
                                isSubtitleFound = true
                                val subtitleRef = requestReferer?.trim()?.takeIf { it.isNotBlank() }
                                    ?: lastPlayHdReferer?.trim()?.takeIf { it.isNotBlank() }
                                mainHandler.post {
                                    onSubtitleFound(url)
                                    subtitleRef?.let { onSubtitleRefererFound(url, it) }
                                }
                            }
                        }
                        reportM3u8(url)
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        val finishedHost = runCatching {
                            android.net.Uri.parse(url.orEmpty()).host?.lowercase()
                        }.getOrNull()
                        if (finishedHost == "flixcloud.cc" || finishedHost == "www.flixcloud.cc") {
                            view?.let { flixView ->
                                pollFlixCloudPk(flixView)
                                // Match the desktop port: do not wait for a human/server-button click.
                                // Start every video element and inspect JW/Artplayer resources immediately.
                                flixView.evaluateJavascript(
                                    """(function(){
                                        try {
                                            document.querySelectorAll('video').forEach(function(v){v.muted=true;v.play().catch(function(){});});
                                            var els=Array.from(document.querySelectorAll('button,[role=button],.play,.vjs-big-play-button,.jw-display-icon-container,.jw-icon-display'));
                                            var play=els.find(function(e){
                                                var t=((e.innerText||e.getAttribute('aria-label')||'')+' '+(e.className||'')).toLowerCase();
                                                return /play|watch|재생|jw-display|jw-icon-display/.test(t);
                                            });
                                            if(play&&!play.dataset.lilacClicked){play.dataset.lilacClicked='1';play.click();}
                                            var urls=[];
                                            try {
                                                var jw=window.jwplayer&&window.jwplayer();
                                                var item=jw&&jw.getPlaylistItem&&jw.getPlaylistItem();
                                                if(item){if(item.file)urls.push(item.file);(item.sources||[]).forEach(function(x){if(x.file)urls.push(x.file);});}
                                                if(jw&&jw.play)jw.play();
                                            } catch(e){}
                                            urls=urls.concat((performance.getEntriesByType('resource')||[]).map(function(e){return e.name||'';}));
                                            return urls.filter(function(u){return /\.(m3u8|mp4|webm)(?:\?|$)/i.test(u);}).join('\n');
                                        } catch(e){return '';}
                                    })()"""
                                ) { raw ->
                                    val decoded = raw.orEmpty().trim().trim('"')
                                        .replace("\\\"", "\"").replace("\\/", "/").replace("\\u0026", "&").replace("\\n", "\n")
                                    decoded.split('\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { reportM3u8(it) }
                                }
                            }
                        }
                        observePlayHd(url)
                        if (view != null) {
                            resolveLinkkfPlayer(view, url)
                        }
                        // FlixCloud embeds the complete subtitle track array in the player HTML.
                        // Re:ANIME can expose multiple languages; report all of them so the player
                        // can let the user choose instead of hard-coding Korean.
                        if (finishedHost == "flixcloud.cc" || finishedHost == "www.flixcloud.cc") {
                            view?.evaluateJavascript(
                                """(function(){try{var h=document.documentElement.innerHTML||"";var m=h.match(/subtitles:\[(.*?)\]/s);return m?m[1]:"";}catch(e){return "";}})()""".trimIndent()
                            ) { raw ->
                                val decoded = raw.orEmpty()
                                    .trim()
                                    .removeSurrounding("\"")
                                    .replace("\\\"", "\"")
                                    .replace("\\u003d", "=")
                                    .replace("\\u0026", "&")
                                    .replace("\\/", "/")
                                    .replace("\\u002F", "/")
                                // evaluateJavascript returns a JSON-escaped string. Decode that first,
                                // then parse each subtitle object independently so field ordering changes
                                // on FlixCloud do not hide the track list.
                                val objectRegex = Regex("""\{([^{}]*?url:"[^"]+"[^{}]*?)\}""")
                                val field = Regex("""(?:^|,)\s*(url|language|format):"([^"]*)"""")
                                val tracks = objectRegex.findAll(decoded).mapNotNull { objectMatch ->
                                    val fields = field.findAll(objectMatch.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
                                    val trackUrl = fields["url"].orEmpty().replace("\\/", "/").replace("\\u0026", "&")
                                    if (!trackUrl.startsWith("http", true)) return@mapNotNull null
                                    val language = fields["language"].orEmpty().ifBlank { "und" }
                                    val label = language
                                    val format = fields["format"].orEmpty().ifBlank { if (trackUrl.substringBefore('?').endsWith(".srt", true)) "srt" else "vtt" }
                                    SubtitleTrack(trackUrl, language, label, format)
                                }.distinctBy { it.url }.toList()
                                if (tracks.isNotEmpty() && !subtitleTracksReported) {
                                    subtitleTracksReported = true
                                    Log.d("ReAnimeStream", "SUBTITLE_TRACKS_FOUND count=${tracks.size} tracks=${tracks.joinToString { it.label + ":" + it.language }}")
                                    mainHandler.post { onSubtitleTracksFound(tracks) }
                                    val korean = tracks.firstOrNull {
                                        it.language.contains("kor", true) || it.label.contains("korean", true) || it.url.contains("_kor_", true)
                                    }
                                    if (!isSubtitleFound && korean != null) {
                                        isSubtitleFound = true
                                        mainHandler.post { onSubtitleFound(korean.url) }
                                    }
                                }
                            }
                        }

                        // Discover the actual embedded player host without navigating
                        // to it ourselves. Ads opened by the player cannot become the
                        // app's main frame because of shouldOverrideUrlLoading above.
                        view?.evaluateJavascript(
                            """(function(){return Array.from(document.querySelectorAll('iframe[src],video[src],source[src],a[href], [data-src]')).map(function(x){return x.src || x.href || x.getAttribute('data-src') || '';}).filter(Boolean).join('\n');})()""",
                            { raw ->
                                val decoded = raw.orEmpty()
                                    .trim('"')
                                    .replace("\\u003d", "=")
                                    .replace("\\u0026", "&")
                                val candidates = decoded.split('\n').filter { it.isNotBlank() }

                                // Re:ANIME watch pages expose the provider/server selector
                                // as client-side buttons. Merely loading /watch/... does not
                                // start the selected FlixCloud player, so the hidden WebView
                                // must activate a server before we can observe its HLS request.


                                iframeHosts = (iframeHosts + candidates.mapNotNull {
                                    runCatching { android.net.Uri.parse(it).host?.lowercase() }.getOrNull()
                                }.toSet()).toSet()

                                // New Linkkf watch pages expose the actual player as a
                                // play.php URL instead of the old direct iframe structure.
                                // Open that player page inside the hidden WebView so its
                                // media requests are observed immediately, rather than
                                // waiting for the 8-second fallback collector.
                                if (!playerPageNavigated) {
                                    val playerUrl = candidates.firstOrNull { candidate ->
                                        runCatching {
                                            val uri = android.net.Uri.parse(candidate)
                                            val host = uri.host?.lowercase().orEmpty()
                                            val path = uri.path.orEmpty().lowercase()
                                            (host == "play.sub3.top" || host == "playv2.sub3.top") &&
                                                (path.contains("play.php") || path.contains("playhd3.php"))
                                        }.getOrDefault(false)
                                    }
                                    if (playerUrl != null) {
                                        playerPageNavigated = true
                                        Log.d("AnimenosubStream", "LINKKF_PLAYER_PAGE $playerUrl")
                                        view?.loadUrl(playerUrl)
                                    }
                                }
                            }
                        )

                        if ((targetHost == "reanime.to" || targetHost == "www.reanime.to") &&
                            !url.orEmpty().contains("flixcloud.cc", ignoreCase = true)) {
                            view?.let { pollReAnimeDecryptedM3u8(it) }
                            // Re:ANIME can fire onPageFinished several times. Select a server only once
                            // so HD-2 does not get clicked again after every page-finished callback.
                            if (!reanimeServerSelectionStarted && !reanimeServerSelected) {
                                reanimeServerSelectionStarted = true
                                val pollHandler = Handler(Looper.getMainLooper())
                                extractorHandlers += pollHandler
                                var pollCount = 0
                                val poll = object : Runnable {
                                    override fun run() {
                                        if (!extractorActive.get() || pollCount++ >= 20 || view == null || reanimeServerSelected) return
                                        view.evaluateJavascript("""(function(){
                                            var els=Array.from(document.querySelectorAll('button,a,[role=button],[data-server],[data-provider]'));
                                            var labels=els.map(function(e){var t=(e.innerText||e.textContent||'').trim().replace(/\s+/g,' ');var d=((e.getAttribute('data-server')||'')+' '+(e.getAttribute('data-provider')||'')).trim();return t+' ['+d+']';}).filter(Boolean);
                                            var preferred=els.find(function(e){var t=(e.innerText||e.textContent||'').trim().toUpperCase();var d=((e.getAttribute('data-server')||'')+' '+(e.getAttribute('data-provider')||'')).toUpperCase();return t.indexOf('HD-2')>=0||d.indexOf('HD-2')>=0;}) || els.find(function(e){var t=(e.innerText||e.textContent||'').trim().toUpperCase();var d=((e.getAttribute('data-server')||'')+' '+(e.getAttribute('data-provider')||'')).toUpperCase();return t.indexOf('HD-1')>=0||d.indexOf('HD-1')>=0||t==='SUB';});
                                            if(preferred){if(preferred.dataset.lilacClicked==='1')return 'ALREADY_CLICKED|'+(preferred.innerText||preferred.textContent||'').trim();preferred.dataset.lilacClicked='1';preferred.click();return 'CLICKED|'+(preferred.innerText||preferred.textContent||'').trim()+'|BUTTONS='+labels.join(' || ');}return 'WAIT|BUTTONS='+labels.join(' || ');
                                        })()""") { result ->
                                            Log.d("ReAnimeStream", "SERVER_SELECT_RESULT attempt=$pollCount result=$result")
                                            if (result.contains("CLICKED")) {
                                                reanimeServerSelected = true
                                                Log.d("ReAnimeStream", "SERVER_SELECTED_ONCE attempt=$pollCount")
                                            } else if (!reanimeServerSelected && pollCount < 20) {
                                                pollHandler.postDelayed(this, 500L)
                                            }
                                        }
                                    }
                                }
                                pollHandler.post(poll)
                            } else {
                                Log.d("ReAnimeStream", "SERVER_SELECT_SKIPPED started=$reanimeServerSelectionStarted selected=$reanimeServerSelected")
                            }
                        }

                        super.onPageFinished(view, url)
                    }
                }

                // Re:ANIME: resolve the FlixCloud player for THIS anime/episode first.
                // Follow the desktop port's lifecycle: get every server candidate, try HD-2 first,
                // then HD-1/remaining candidates in fresh page sessions, and fail over quickly.
                val isReAnime = targetHost == "reanime.to" || targetHost == "www.reanime.to"
                if (isReAnime && reAnimeAnilistId != null && reAnimeEpisodeNumber != null) {
                    val webView = this
                    val resolverClient = OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .connectTimeout(8, TimeUnit.SECONDS)
                        .readTimeout(12, TimeUnit.SECONDS)
                        .build()

                    fun resetCandidateState() {
                        cancelReAnimeCandidateTimeout()
                        detectedUrls.clear()
                        flixCloudPk = null
                        flixPkPollStarted = false
                        reanimeEncryptedMasterUrl = null
                        reanimeDecryptedM3u8Reported = false
                        reanimeMasterFallbackPosted = false
                        lastM3u8Referer = null
                        lastM3u8Headers = null
                        reanimeStreamReady = false
                    }

                    fun loadReAnimeCandidate(reason: String) {
                        if (!extractorActive.get() || reAnimeCandidateIndex >= reanimeCandidates.size || reanimeStreamReady) return
                        resetCandidateState()
                        val candidate = reanimeCandidates[reAnimeCandidateIndex]
                        reAnimeActiveCandidate = candidate
                        Log.d(
                            "ReAnimeStream",
                            "FLIX_CANDIDATE_START index=$reAnimeCandidateIndex/${reanimeCandidates.size} reason=$reason url=$candidate"
                        )
                        // The desktop resolver creates a fresh browser session per server. AndroidView
                        // cannot cheaply replace the Composable WebView, so force a real navigation reset
                        // and clear page-local state before loading the next candidate.
                        runCatching { webView.stopLoading() }
                        runCatching { webView.loadUrl("about:blank") }
                        mainHandler.postDelayed({
                            if (extractorActive.get() && reAnimeActiveCandidate == candidate) {
                                webView.loadUrl(candidate, mapOf("Referer" to "https://reanime.to/"))
                            }
                        }, 80L)
                        val timeout = Runnable {
                            if (!extractorActive.get() || reanimeStreamReady) return@Runnable
                            Log.w(
                                "ReAnimeStream",
                                "FLIX_CANDIDATE_TIMEOUT index=$reAnimeCandidateIndex url=$candidate"
                            )
                            reAnimeCandidateIndex++
                            reAnimeActiveCandidate = null
                            if (reAnimeCandidateIndex < reanimeCandidates.size) {
                                loadReAnimeCandidate("timeout")
                            } else {
                                Log.e("ReAnimeStream", "FLIX_ALL_CANDIDATES_FAILED episode=$reAnimeEpisodeNumber")
                                mainHandler.post {
                                    onQualitiesFound(emptyList())
                                }
                            }
                        }
                        reanimeCandidateTimeout = timeout
                        // 12 s per candidate is enough for normal FlixCloud bootstrap while
                        // preventing a dead HD-2 server from blocking the next episode indefinitely.
                        mainHandler.postDelayed(timeout, 12_000L)
                    }

                    val resolverThread = thread(name = "ReAnimeFlixResolve", isDaemon = true) {
                        try {
                            if (!extractorActive.get()) return@thread
                            val apiUrl = "https://reanime.to/api/flix/$reAnimeAnilistId/$reAnimeEpisodeNumber"
                            val request = Request.Builder()
                                .url(apiUrl)
                                .header("User-Agent", capturedWebViewUserAgent)
                                .header("Accept", "application/json")
                                .header("Referer", "https://reanime.to/")
                                .build()
                            resolverClient.newCall(request).execute().use { response ->
                                val body = response.body?.string().orEmpty()
                                if (!response.isSuccessful) throw IllegalStateException("HTTP ${'$'}{response.code}")
                                val root = JSONObject(body)
                                val servers = root.optJSONArray("servers") ?: throw IllegalStateException("servers missing")
                                val candidates = buildList {
                                    for (i in 0 until servers.length()) {
                                        val item = servers.optJSONObject(i) ?: continue
                                        val link = item.optString("dataLink").trim()
                                        if (!link.startsWith("https://flixcloud.cc/e/", true)) continue
                                        add(item.optString("serverName").trim() to link)
                                    }
                                }
                                val ordered = candidates
                                    .sortedWith(compareBy<Pair<String, String>> {
                                        when {
                                            it.first.equals("HD-2", true) -> 0
                                            it.first.contains("HD-2", true) -> 1
                                            it.first.equals("HD-1", true) -> 2
                                            it.first.contains("HD-1", true) -> 3
                                            else -> 4
                                        }
                                    })
                                    .map { it.second }
                                    .distinct()
                                if (ordered.isEmpty()) throw IllegalStateException("No FlixCloud dataLink")
                                if (!extractorActive.get()) return@thread
                                mainHandler.post {
                                    if (!extractorActive.get()) return@post
                                    reanimeCandidates = ordered
                                    reAnimeCandidateIndex = 0
                                    Log.d("ReAnimeStream", "FLIX_CANDIDATES count=${'$'}{ordered.size} order=${'$'}{ordered.joinToString()}")
                                    loadReAnimeCandidate("api")
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e("ReAnimeStream", "FLIX_RESOLVE_FAILED episode=$reAnimeEpisodeNumber", t)
                            if (!extractorActive.get()) return@thread
                            mainHandler.post {
                                if (!extractorActive.get()) return@post
                                // Only use the watch page as a last-resort DOM fallback.
                                webView.loadUrl(targetUrl, mapOf("Referer" to "https://reanime.to/"))
                                mainHandler.postDelayed({
                                    if (!extractorActive.get() || reanimeStreamReady) return@postDelayed
                                    webView.evaluateJavascript(
                                        """(function(){try{var a=[].slice.call(document.querySelectorAll('a,[href],[data-url],[data-link],[data-server-url]'));return a.map(function(e){return e.href||e.getAttribute('data-url')||e.getAttribute('data-link')||e.getAttribute('data-server-url')||''}).filter(function(x){return /flixcloud\\.cc\\/e\\//i.test(x)}).join('\n');}catch(e){return '';}})()"""
                                    ) { raw ->
                                        val decoded = raw.orEmpty().trim().trim('"')
                                            .replace("\\\"", "\"")
                                            .replace("\\/", "/")
                                        val fallback = decoded.split('\n')
                                            .firstOrNull { it.startsWith("https://flixcloud.cc/e/", true) }
                                        if (!fallback.isNullOrBlank()) {
                                            reanimeCandidates = listOf(fallback)
                                            reAnimeCandidateIndex = 0
                                            loadReAnimeCandidate("dom-fallback")
                                        }
                                    }
                                }, 1200L)
                            }
                        }
                    }
                    resolverThreads += resolverThread
                } else {
                    Log.d("ReAnimeStream", "INITIAL_WEBVIEW_URL $targetUrl")
                    loadUrl(targetUrl)
                }
            }
        }
        )
    }
}
