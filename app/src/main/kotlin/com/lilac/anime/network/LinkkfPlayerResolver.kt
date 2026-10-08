package com.lilac.anime.network

import android.content.Context
import android.util.Log
import com.lilac.anime.Episode
import com.lilac.anime.data.LinkkfClient
import com.lilac.anime.data.LinkkfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Direct Linkkf watch-page resolver.
 *
 * HAR finding:
 *   linkani.tv/watch/... HTML
 *       -> var player_aaaa = {...}
 *       -> actual_url/url = aniplayer1.site/.../index.m3u8
 *       -> subtitle_url = aniplayer1.site/.../sub.vtt
 *
 * There is no need to drive the site's JavaScript player inside a WebView for
 * normal playback.  Resolving the server-rendered player_aaaa object is both
 * faster and much less fragile.
 */
object LinkkfPlayerResolver {
    private const val TAG = "LinkkfPlayerResolver"

    data class Result(
        val episodeId: String,
        val m3u8Url: String?,
        val referer: String?,
        val headers: String?,
        val subtitleUrl: String? = null,
        val subtitleReferer: String? = null,
        val playerUrl: String? = null
    )

    suspend fun resolve(context: Context, episode: Episode): Result =
        withContext(Dispatchers.IO) {
            resolvePage(context, episode.id, episode.videoUrl.orEmpty())
        }

    suspend fun resolvePage(
        context: Context,
        episodeId: String,
        watchPageUrl: String
    ): Result = withContext(Dispatchers.IO) {
        if (watchPageUrl.isBlank()) return@withContext Result(
            episodeId, null, null, null
        )

        val pageUrl = normalizeWatchUrl(watchPageUrl)
        var lastError: Throwable? = null

        // A transient CDN/site response must not turn into a permanent player
        // failure. The watch page is cheap to fetch and contains a freshly signed
        // HLS URL, so retry the complete page->player_aaaa resolution instead of
        // retrying an already-issued (possibly stale) media URL.
        repeat(3) { attempt ->
            try {
                val document = LinkkfClient().getDocument(pageUrl)
                val watch = LinkkfParser.parseWatch(document)

                if (watch == null) {
                    lastError = IllegalStateException("player_aaaa not found")
                    Log.w(TAG, "PLAYER_DATA_NOT_FOUND attempt=${attempt + 1} episode=$episodeId page=$pageUrl")
                } else {
                    val stream = watch.streamUrl
                        ?.takeIf { it.contains(".m3u8", true) }
                        ?.trim()

                    if (!stream.isNullOrBlank()) {
                        // These are the exact browser-context headers observed in
                        // linkani.tv HAR for both HLS and VTT requests.
                        val mediaHeaders = buildString {
                            append("Origin: https://linkani.tv\n")
                            append("Referer: https://linkani.tv/\n")
                            append("User-Agent: ")
                            append(MEDIA_USER_AGENT)
                        }

                        Log.d(
                            TAG,
                            "PLAYER_RESOLVED episode=$episodeId attempt=${attempt + 1} " +
                                "stream=$stream subtitle=${watch.subtitleUrl ?: "<none>"} page=$pageUrl"
                        )

                        return@withContext Result(
                            episodeId = episodeId,
                            m3u8Url = stream,
                            referer = "https://linkani.tv/",
                            headers = mediaHeaders,
                            subtitleUrl = watch.subtitleUrl,
                            subtitleReferer = "https://linkani.tv/",
                            playerUrl = pageUrl
                        )
                    }
                    lastError = IllegalStateException("player_aaaa has no m3u8 url")
                    Log.w(TAG, "PLAYER_M3U8_MISSING attempt=${attempt + 1} episode=$episodeId page=$pageUrl")
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                lastError = t
                Log.w(TAG, "PLAYER_RESOLVE_RETRY attempt=${attempt + 1} episode=$episodeId page=$pageUrl", t)
            }

            if (attempt < 2) {
                Thread.sleep(if (attempt == 0) 350L else 900L)
            }
        }

        Log.e(TAG, "PLAYER_RESOLVE_FAILED episode=$episodeId page=$pageUrl", lastError)
        Result(episodeId, null, null, null, playerUrl = pageUrl)
    }

    private const val MEDIA_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun normalizeWatchUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return LinkkfParser.BASE_URL + if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }
}
