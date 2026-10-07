package com.lilac.anime.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.lilac.anime.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Re:ANIME-only playback/offline resolver.
 *
 * This is deliberately independent from the LinkKF extractor. The Re:ANIME path
 * follows the current FlixCloud HAR directly through ReAnimeFlixCloud.
 */
object ReAnimePlayerResolver {
    data class Result(
        val m3u8Url: String?,
        val referer: String?,
        val headers: String?,
        val subtitleUrl: String? = null,
        val subtitleReferer: String? = null,
        val flixCloudPk: String? = null
    )

    private const val REANIME = "https://reanime.to"
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    suspend fun resolve(context: Context, episode: Episode, anilistId: Int? = null): Result {
        val page = episode.videoUrl.orEmpty()
        val uri = runCatching { android.net.Uri.parse(page) }.getOrNull()
        val episodeNumber = uri?.getQueryParameter("ep")?.toIntOrNull() ?: episode.number
        if (episodeNumber <= 0) return Result(null, null, null)

        val aid = anilistId?.takeIf { it > 0 }
            ?: return Result(null, null, null)

        val flixUrl = withContext(Dispatchers.IO) {
            ReAnimeFlixCloud.resolveFlixUrl(aid, episodeNumber, UA)
        } ?: return Result(null, null, null)

        android.util.Log.d("ReAnimePlayerResolver", "FLIX_HAR_FLOW aid=$aid episode=$episodeNumber url=$flixUrl")
        return bootstrap(context, flixUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun bootstrap(context: Context, flixUrl: String): Result =
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var done = false

            fun destroy() {
                handler.removeCallbacksAndMessages(null)
                runCatching { webView?.stopLoading() }
                runCatching { webView?.loadUrl("about:blank") }
                runCatching { webView?.destroy() }
                webView = null
            }

            fun finish(result: Result) {
                if (done) return
                done = true
                destroy()
                if (continuation.isActive) continuation.resume(result)
            }

            continuation.invokeOnCancellation {
                handler.post { destroy() }
            }

            handler.post {
                if (done) return@post
                val view = WebView(context)
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

                ReAnimeFlixCloud.bootstrap(view, flixUrl) { result, error ->
                    if (done) return@bootstrap
                    if (result == null) {
                        android.util.Log.e("ReAnimePlayerResolver", "FLIX_HAR_BOOTSTRAP_FAILED $error")
                        finish(Result(null, null, null))
                    } else {
                        android.util.Log.d("ReAnimePlayerResolver", "FLIX_HAR_BOOTSTRAP_OK pk=${result.pk.length}")
                        finish(
                            Result(
                                m3u8Url = result.m3u8Url,
                                referer = "https://flixcloud.cc/",
                                headers = result.headers,
                                flixCloudPk = result.pk
                            )
                        )
                    }
                }
            }
        }
}
