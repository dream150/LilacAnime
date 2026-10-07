package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import com.lilac.anime.Anime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SubtitleTitleResolver {
    private const val TAG = "SubtitleTitleResolver"

    suspend fun resolve(context: Context, anime: Anime): String = withContext(Dispatchers.IO) {
        val candidates = buildList {
            anime.title.trim().takeIf { it.isNotBlank() }?.let(::add)
            anime.english.trim().takeIf { it.isNotBlank() }?.let(::add)
            anime.romaji.trim().takeIf { it.isNotBlank() }?.let(::add)
            anime.native.trim().takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()


        TmdbTitleResolver.resolveBest(
            context,
            candidates
        )?.takeIf { it.isNotBlank() }?.let {
            Log.d(TAG, "TMDB queryCandidates=${candidates.joinToString(" | ")} korean=[$it]")
            return@withContext it
        }

        candidates.firstOrNull { NamuWikiTitleResolver.isHangulTitle(it) }?.let { return@withContext it }

        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            NamuWikiTitleResolver.resolve(context, candidate)?.takeIf { it.isNotBlank() }?.let {
                Log.d(TAG, "NAMUWIKI query=[$candidate] korean=[$it]")
                return@withContext it
            }
        }

        anime.title.trim()
    }
}
