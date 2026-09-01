package com.lilac.anime

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
import com.lilac.anime.StreamQuality

/**
 * Hidden WebView stream detector. It never exposes the provider page to the user.
 * For Animenosub we only permit the source page and its embedded player host as
 * top-level navigation, preventing ad redirects from taking over the app.
 */
@Composable
fun StreamUrlExtractor(
    targetUrl: String,
    onQualitiesFound: (List<StreamQuality>) -> Unit,
    onSubtitleFound: (String) -> Unit,
    onAuthRequired: () -> Unit = {},
    allowedHosts: Set<String> = emptySet(),
    restartKey: Any? = null
) {
    val detectedUrls = remember(targetUrl, restartKey) { linkedSetOf<String>() }
    var isSubtitleFound by remember(targetUrl, restartKey) { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    key(targetUrl, restartKey) {
        AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
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

                val targetHost = runCatching { android.net.Uri.parse(targetUrl).host?.lowercase() }.getOrNull()
                val inferredHosts = when {
                    targetHost == "animenosub.to" || targetHost == "www.animenosub.to" ->
                        setOf("animenosub.to", "www.animenosub.to")
                    targetHost == "linkkf.tv" || targetHost == "www.linkkf.tv" || targetHost == "linkkf.tckopke.com" ->
                        setOf("linkkf.tv", "www.linkkf.tv", "linkkf.tckopke.com", "tckopke.com", "www.tckopke.com")
                    else -> emptySet()
                }
                val safeHosts = (allowedHosts + inferredHosts + listOfNotNull(targetHost)).map { it.lowercase() }.toSet()
                var iframeHosts = emptySet<String>()

                fun isAllowedNavigation(url: String): Boolean {
                    val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
                    if (safeHosts.isEmpty()) return true
                    return host in safeHosts || host in iframeHosts
                }

                fun reportM3u8(url: String) {
                    val path = runCatching { android.net.Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
                    if (!path.endsWith(".m3u8")) return
                    if (!detectedUrls.add(url)) return
                    android.util.Log.d("AnimenosubStream", "M3U8_FOUND $url")
                    val urls = detectedUrls.toList()
                    mainHandler.post {
                        // Prefer a master playlist and keep the exact query string,
                        // e.g. master.m3u8?t=... . Do not rewrite or strip tokens.
                        val ordered = urls.sortedWith(compareByDescending<String> {
                            runCatching { android.net.Uri.parse(it).lastPathSegment?.contains("master", true) == true }.getOrDefault(false)
                        })
                        val qualities = ordered.mapIndexed { index, u ->
                            val path = runCatching { android.net.Uri.parse(u).path.orEmpty().lowercase() }.getOrDefault("")
                            val label = when {
                                path.contains("1080") -> "1080p"
                                path.contains("720") -> "720p"
                                path.contains("480") -> "480p"
                                path.contains("360") -> "360p"
                                path.contains("master") -> "Auto"
                                else -> "Stream ${index + 1}"
                            }
                            StreamQuality(label, u)
                        }.distinctBy { it.url }
                        onQualitiesFound(qualities)
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
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

                        if (!isSubtitleFound && path.endsWith(".vtt")) {
                            isSubtitleFound = true
                            mainHandler.post { onSubtitleFound(url) }
                        }
                        reportM3u8(url)
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Discover the actual embedded player host without navigating
                        // to it ourselves. Ads opened by the player cannot become the
                        // app's main frame because of shouldOverrideUrlLoading above.
                        view?.evaluateJavascript(
                            """(function(){return Array.from(document.querySelectorAll('iframe[src]')).map(function(x){return x.src;}).join('\\n');})()""",
                            { raw ->
                                val decoded = raw.orEmpty().trim('"').replace("\\u003d", "=").replace("\\u0026", "&")
                                iframeHosts = (iframeHosts + decoded.split('\n').mapNotNull {
                                    runCatching { android.net.Uri.parse(it).host?.lowercase() }.getOrNull()
                                }.toSet()).toSet()
                            }
                        )
                        super.onPageFinished(view, url)
                    }
                }

                loadUrl(targetUrl)
            }
        },
        modifier = Modifier.size(0.dp)
        )
    }
}
