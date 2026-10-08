package com.lilac.anime.network

import android.content.Context
import com.lilac.anime.Episode

/**
 * Batch compatibility facade.
 *
 * The old collector created several WebViews only to observe a request that is
 * now explicitly present in Linkkf's server-rendered watch HTML.  Resolve each
 * watch page directly instead.
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
        val failed = linkedSetOf<String>()

        episodes.forEach { episode ->
            onStatus("LINKKF_RESOLVE_START episode=${episode.displayNumber}")
            val result = runCatching {
                LinkkfPlayerResolver.resolve(context, episode)
            }.getOrNull()

            val video = result?.m3u8Url
            if (video.isNullOrBlank()) {
                failed += episode.id
                onStatus("LINKKF_RESOLVE_FAILED episode=${episode.displayNumber}")
                return@forEach
            }

            urls[episode.id] = video
            result.referer?.let { refs[episode.id] = it }

            val subtitle = result.subtitleUrl
            if (!subtitle.isNullOrBlank()) {
                subtitles[episode.id] = subtitle
                result.subtitleReferer?.let { subtitleRefs[episode.id] = it }
                onSubtitleFound(episode.id, subtitle, result.subtitleReferer)
            }

            onStatus(
                "LINKKF_RESOLVE_OK episode=${episode.displayNumber} " +
                    "m3u8=$video subtitle=${subtitle ?: "<none>"}"
            )
        }

        return Result(
            urls = urls,
            referers = refs,
            subtitleUrls = subtitles,
            subtitleReferers = subtitleRefs,
            failedEpisodeIds = failed
        )
    }
}
