package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import com.lilac.anime.Anime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Produces a subtitle-provider search title while keeping Re:Anime season
 * identity separate from the visible Korean title.
 */
object SubtitleTitleResolver {
    private const val TAG = "SubtitleTitleResolver"

    suspend fun resolve(context: Context, anime: Anime): String = withContext(Dispatchers.IO) {
        val baseCandidates = buildList {
            anime.title.trim().takeIf { it.isNotBlank() }?.let(::add)
            anime.english.trim().takeIf { it.isNotBlank() }?.let(::add)
            anime.romaji.trim().takeIf { it.isNotBlank() }?.let(::add)
            anime.native.trim().takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()

        val korean = TmdbTitleResolver.resolveBest(context, baseCandidates)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val visibleBase = korean ?: baseCandidates.firstOrNull() ?: anime.title.trim()
        val season = seasonSearchSuffix(anime)
        val result = if (season.isBlank()) visibleBase else "$visibleBase $season"

        Log.d(
            TAG,
            "TMDB title=[${visibleBase}] season=[${anime.season}] seasonYear=${anime.seasonYear} searchTitle=[$result]"
        )
        result
    }

    /** Used by callers that need the Korean display title only. */
    suspend fun resolveDisplayTitle(context: Context, anime: Anime): String = withContext(Dispatchers.IO) {
        val candidates = buildList {
            anime.title.trim().takeIf { it.isNotBlank() }?.let(::add)
            anime.english.trim().takeIf { it.isNotBlank() }?.let(::add)
            anime.romaji.trim().takeIf { it.isNotBlank() }?.let(::add)
            anime.native.trim().takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()
        TmdbTitleResolver.resolveBest(context, candidates)?.takeIf { it.isNotBlank() } ?: anime.title.trim()
    }

    private fun seasonSearchSuffix(anime: Anime): String {
        val number = anime.seasonNumber
            ?: listOf(
                Regex("(?i)\\bseason\\s*(\\d+)\\b"),
                Regex("(?i)\\bpart\\s*(\\d+)\\b"),
                Regex("(?i)\\bcour\\s*(\\d+)\\b"),
                Regex("(?i)\\bs(\\d+)\\b"),
                Regex("(\\d+)\\s*기\\b")
            ).firstNotNullOfOrNull { pattern ->
                pattern.find(anime.title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: pattern.find(anime.english)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: pattern.find(anime.romaji)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }

        return number?.takeIf { it > 1 }?.let { "시즌 $it" }.orEmpty()
    }
}
