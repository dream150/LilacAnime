package com.lilac.anime.network

import android.content.Context
import com.lilac.anime.Episode

/**
 * Compatibility facade for callers that still resolve several episodes at once.
 *
 * The old implementation mixed player navigation, URL guessing and WebView
 * capture in one very large object. All actual resolution now lives in
 * LinkkfPlayerResolver. This facade only adapts the result for downloads.
 */
object LinkkfEpisodeM3u8Collector {

    data class Result(
        val urls: Map<String, String>,
        val referers: Map<String, String>,
        val subtitleUrls: Map<String, String> = emptyMap(),
        val subtitleReferers: Map<String, String> = emptyMap(),
        val failedEpisodeIds: Set<String> = emptySet(),
        val headers: Map<String, String> = emptyMap()
    )

    suspend fun collect(
        context: Context,
        episodes: List<Episode>,
        waitForSubtitle: Boolean = false,
        onSubtitleFound: (episodeId: String, url: String, referer: String?) -> Unit = { _, _, _ -> },
        onStatus: (String) -> Unit = {}
    ): Result {
        val urls = linkedMapOf<String, String>()
        val refs = linkedMapOf<String, String>()
        val subtitles = linkedMapOf<String, String>()
        val subtitleRefs = linkedMapOf<String, String>()
        val headers = linkedMapOf<String, String>()
        val failed = linkedSetOf<String>()

        for (episode in episodes) {
            onStatus("LINKKF_RESOLVE_START episode=${episode.displayNumber}")
            val result = runCatching {
                LinkkfPlayerResolver.resolve(context, episode)
            }.getOrNull()

            if (result?.m3u8Url.isNullOrBlank()) {
                failed += episode.id
                onStatus("LINKKF_RESOLVE_FAILED episode=${episode.displayNumber}")
                continue
            }

            result!!.m3u8Url!!.let { urls[episode.id] = it }
            result.referer?.let { refs[episode.id] = it }
            result.headers?.let { headers[episode.id] = it }

            if (!result.subtitleUrl.isNullOrBlank()) {
                subtitles[episode.id] = result.subtitleUrl
                result.subtitleReferer?.let { subtitleRefs[episode.id] = it }
                onSubtitleFound(
                    episode.id,
                    result.subtitleUrl,
                    result.subtitleReferer
                )
            }

            onStatus("LINKKF_RESOLVE_OK episode=${episode.displayNumber} m3u8=${result.m3u8Url}")
        }

        return Result(
            urls = urls,
            referers = refs,
            subtitleUrls = subtitles,
            subtitleReferers = subtitleRefs,
            failedEpisodeIds = failed,
            headers = headers
        )
    }
}
