package com.lilac.anime.network

import android.content.Context
import android.util.Log
import com.lilac.anime.Episode
import com.lilac.anime.core.model.ChapterSkipSegment
import com.lilac.anime.data.offline.OfflineStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for OP/ED skip resolution.
 *
 * ONLINE playback:
 *   AniSkip only.
 *
 * OFFLINE playback:
 *   1. AniSkip timestamps saved next to the downloaded episode.
 *   2. If no AniSkip data exists, use the local audio-fingerprint analyzer.
 *
 * Analyzer results are cached separately from AniSkip data so a missing AniSkip
 * result can never be mistaken for a real AniSkip timestamp.
 */
object OpEdSkipResolver {
    private const val TAG = "OpEdSkipResolver"

    suspend fun resolveOnline(
        title: String,
        episodeNumber: Int,
        anilistId: Int?,
        malId: Int? = null
    ): List<ChapterSkipSegment> {
        return runCatching {
            OnlineAniSkipService.getSkipSegments(
                title = title,
                episodeNumber = episodeNumber,
                episodeLengthSeconds = 0,
                anilistId = anilistId,
                malId = malId
            )
        }.onFailure {
            Log.w(TAG, "ONLINE_ANISKIP_FAILED title=$title episode=$episodeNumber", it)
        }.getOrElse { emptyList() }
    }

    suspend fun resolveOffline(
        context: Context,
        animeId: String,
        episode: Episode,
        episodes: List<Episode>
    ): List<ChapterSkipSegment> = withContext(Dispatchers.IO) {
        val savedAniSkip = OfflineStore.getChapterSkipSegments(
            context,
            animeId,
            episode.id
        )
        if (savedAniSkip.isNotEmpty()) {
            Log.d(TAG, "OFFLINE_ANISKIP_HIT anime=$animeId episode=${episode.number} segments=${savedAniSkip.size}")
            return@withContext savedAniSkip
        }

        Log.d(TAG, "OFFLINE_ANISKIP_MISS anime=$animeId episode=${episode.number}; START_ANALYZER")
        val analyzed = runCatching {
            LinkkfChapterService.detectSkipSegmentsOffline(
                context = context,
                animeId = animeId,
                currentEpisode = episode,
                episodes = episodes,
                episodeDurationSeconds = 0,
                onStatus = { Log.d(TAG, it) }
            )
        }.onFailure {
            Log.e(TAG, "OFFLINE_ANALYZER_FAILED anime=$animeId episode=${episode.number}", it)
        }.getOrElse { emptyList() }

        Log.d(TAG, "OFFLINE_ANALYZER_DONE anime=$animeId episode=${episode.number} segments=${analyzed.size}")
        analyzed
    }
}
