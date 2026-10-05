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

    enum class Source { JIMAKU, REANIME, KAIRAN, CSORA, LINKKF, CACHED }

    data class Choice(
        val source: Source,
        val label: String,
        val language: String,
        val title: String,
        val path: String? = null,
        val url: String? = null,
        val jimaku: JimakuSubtitleService.SubtitleOption? = null,
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
        val kairan = async(Dispatchers.IO) {
            runCatching {
                KairanSubtitleService.findSubtitle(
                    context, koreanTitle, episode.number, episode.displayNumber
                )
            }.onFailure { Log.w(TAG, "KAIRAN_FAILED", it) }.getOrNull()
        }
        val csora = async(Dispatchers.IO) {
            runCatching {
                CsoraSubtitleService.findSubtitle(
                    context, koreanTitle, episode.number, episode.displayNumber
                )
            }.onFailure { Log.w(TAG, "CSORA_FAILED", it) }.getOrNull()
        }
        val jimaku = async(Dispatchers.IO) {
            runCatching {
                JimakuSubtitleService.listEpisodeSubtitles(
                    context, anime, episode.number, episode.displayNumber
                )
            }.onFailure { Log.w(TAG, "JIMAKU_FAILED", it) }.getOrDefault(emptyList())
        }

        val result = mutableListOf<Choice>()
        val kairanResult = kairan.await()
        val csoraResult = csora.await()
        val jimakuOptions = jimaku.await()
        Log.d(TAG, "SOURCE_RESULTS episode=${episode.displayNumber} kairan=${kairanResult != null} csora=${csoraResult != null} jimaku=${jimakuOptions.size} reAnime=${reAnimeTracks.size} linkkf=${!linkkfSubtitleUrl.isNullOrBlank()}")

        (kairanResult as? KairanSubtitleResult.DirectFile)?.path
            ?.takeIf { File(it).isFile }
            ?.let {
                result += Choice(Source.KAIRAN, "Kairan", "한국어", koreanTitle, path = it)
                Log.d(TAG, "SOURCE_READY source=KAIRAN path=$it")
            }

        (csoraResult as? KairanSubtitleResult.DirectFile)?.path
            ?.takeIf { File(it).isFile }
            ?.let {
                result += Choice(Source.CSORA, "Csora", "한국어", koreanTitle, path = it)
                Log.d(TAG, "SOURCE_READY source=CSORA path=$it")
            }

        jimakuOptions.forEach { option ->
            result += Choice(
                source = Source.JIMAKU,
                label = "Jimaku",
                language = "원문",
                title = option.name,
                url = option.url,
                jimaku = option
            )
        }

        reAnimeTracks.forEach { track ->
            if (track.url.isNotBlank()) {
                result += Choice(
                    source = Source.REANIME,
                    label = "Re:Anime",
                    language = track.language.ifBlank { "원문" },
                    title = track.label.ifBlank { "Re:Anime 자막" },
                    url = track.url,
                    reAnime = track
                )
            }
        }

        if (!linkkfSubtitleUrl.isNullOrBlank()) {
            result += Choice(Source.LINKKF, "Linkkf", "원문", "기본 자막", url = linkkfSubtitleUrl)
        }

        // Previously downloaded assets are always valid candidates even when a provider
        // is temporarily offline. This makes episode changes work without a network hit.
        SubtitleStore.list(context, anime.id, episode.displayNumber, episode.number)
            .filter { !it.ignored && File(it.path).isFile }
            .forEach { saved ->
                val source = when (saved.source.lowercase()) {
                    "kairan" -> Source.KAIRAN
                    "csora" -> Source.CSORA
                    "jimaku" -> Source.JIMAKU
                    "reanime" -> Source.REANIME
                    "linkkf" -> Source.LINKKF
                    else -> Source.CACHED
                }
                result += Choice(
                    source = source,
                    label = saved.source.uppercase(),
                    language = if (source == Source.KAIRAN || source == Source.CSORA) "한국어" else "원문",
                    title = File(saved.path).name,
                    path = saved.path
                )
            }

        result.distinctBy { it.key }
            .also { Log.d(TAG, "DISCOVERY_DONE anime=${anime.id} episode=${episode.displayNumber} count=${it.size}") }
    }

    fun resolvePreferredKoreanTitle(anime: Anime, resolvedTitle: String?): String {
        return resolvedTitle?.trim()?.takeIf { it.isNotBlank() }
            ?: anime.title.trim()
    }
}
