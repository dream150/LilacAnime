package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import com.lilac.anime.Anime
import com.lilac.anime.Episode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Collects every subtitle source that can be used before playback starts.
 * Provider-specific details stay here so PlayerScreen only deals with choices.
 */
object SubtitleDiscoveryService {
    private const val TAG = "SubtitleDiscovery"

    enum class Source { ANISSIA, JIMAKU, REANIME, LINKKF, CACHED }

    data class Choice(
        val source: Source,
        val label: String,
        val language: String,
        val title: String,
        val path: String? = null,
        val url: String? = null,
        val jimaku: JimakuSubtitleService.SubtitleOption? = null,
        val anissia: AnissiaSubtitleService.SubtitleOption? = null,
        val reAnime: SubtitleTrack? = null
    ) {
        val key: String
            get() = "${source.name}|${title.trim().lowercase()}|${language.trim().lowercase()}|${path.orEmpty()}|${url.orEmpty()}"
    }

    suspend fun discover(
        context: Context,
        anime: Anime,
        episode: Episode,
        koreanTitle: String,
        linkkfSubtitleUrl: String? = null,
        reAnimeTracks: List<SubtitleTrack> = emptyList()
    ): List<Choice> = coroutineScope {
        // Offline subtitle assets always win. Do this check BEFORE starting any
        // provider request so a downloaded episode never causes unnecessary
        // Kairan/Csora/Jimaku network discovery.
        val cachedChoices = withContext(Dispatchers.IO) {
            SubtitleStore.list(context, anime.id, episode.displayNumber, episode.number)
                .filter { !it.ignored && File(it.path).isFile }
                .map { saved ->
                    val source = when (saved.source.lowercase()) {
                        "anissia" -> Source.ANISSIA
                        "kairan", "csora" -> Source.CACHED
                        "jimaku" -> Source.JIMAKU
                        "reanime" -> Source.REANIME
                        "linkkf" -> Source.LINKKF
                        else -> Source.CACHED
                    }
                    Choice(
                        source = source,
                        label = when (source) {
                            Source.ANISSIA -> "Anissia"
                            Source.JIMAKU -> "Jimaku"
                            Source.REANIME -> "Re:Anime"
                            Source.LINKKF -> "Linkkf"
                            Source.CACHED -> "저장됨"
                        },
                        language = if (source == Source.ANISSIA) "한국어" else "원문",
                        title = File(saved.path).name,
                        path = saved.path
                    )
                }
                .distinctBy { it.path }
        }

        if (cachedChoices.isNotEmpty()) {
            Log.d(TAG, "OFFLINE_SUBTITLE_HIT episode=${episode.displayNumber} count=${cachedChoices.size}; skip network discovery")
            return@coroutineScope cachedChoices
        }

        val anissia = async(Dispatchers.IO) {
            runCatching {
                AnissiaSubtitleService.listEpisodeSubtitles(
                    context, koreanTitle, episode.number, episode.displayNumber, anime.seasonNumber, anime.id
                )
            }.onFailure { Log.w(TAG, "ANISSIA_FAILED", it) }.getOrDefault(emptyList())
        }
        val jimaku = async(Dispatchers.IO) {
            runCatching {
                JimakuSubtitleService.listEpisodeSubtitles(
                    context, anime, episode.number, episode.displayNumber
                )
            }.onFailure { Log.w(TAG, "JIMAKU_FAILED", it) }.getOrDefault(emptyList())
        }

        val result = mutableListOf<Choice>()
        val anissiaOptions = anissia.await()
        val jimakuOptions = jimaku.await()
        Log.d(TAG, "SOURCE_RESULTS episode=${episode.displayNumber} anissia=${anissiaOptions.size} jimaku=${jimakuOptions.size} reAnime=${reAnimeTracks.size} linkkf=${!linkkfSubtitleUrl.isNullOrBlank()}")

        anissiaOptions.forEach { option ->
            result += Choice(
                source = Source.ANISSIA, label = "Anissia", language = "한국어",
                title = option.name, path = option.cachedPath, url = option.website, anissia = option
            )
        }

        jimakuOptions.forEach { option ->
            result += Choice(
                source = Source.JIMAKU, label = "Jimaku", language = "원문",
                title = option.name, url = option.url, jimaku = option
            )
        }

        reAnimeTracks.forEach { track ->
            if (track.url.isNotBlank()) {
                result += Choice(
                    source = Source.REANIME, label = "Re:Anime",
                    language = track.language.ifBlank { "원문" },
                    title = track.label.ifBlank { "Re:Anime 자막" },
                    url = track.url, reAnime = track
                )
            }
        }

        if (!linkkfSubtitleUrl.isNullOrBlank()) {
            result += Choice(Source.LINKKF, "Linkkf", "원문", "기본 자막", url = linkkfSubtitleUrl)
        }

        result.distinctBy { it.key }
            .also { Log.d(TAG, "DISCOVERY_DONE anime=${anime.id} episode=${episode.displayNumber} count=${it.size}") }
    }

    fun resolvePreferredKoreanTitle(anime: Anime, resolvedTitle: String?): String {
        return resolvedTitle?.trim()?.takeIf { it.isNotBlank() }
            ?: anime.title.trim()
    }
}
