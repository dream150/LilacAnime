package com.lilac.anime.data

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
import com.lilac.anime.data.subtitle.*
import com.lilac.anime.network.*
import com.lilac.anime.player.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.detail.*
import com.lilac.anime.ui.home.*
import com.lilac.anime.ui.navigation.*
import com.lilac.anime.ui.search.*
import com.lilac.anime.ui.settings.*
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

import android.content.Context
import com.lilac.anime.Anime
import com.lilac.anime.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds anime -> genres from Linkkf's own class/tag listing pages.
 * No per-anime detail page is requested here.
 */
object LinkkfGenreIndexRepository {
    private const val BASE = "https://linkkf.tv"
    private const val LIST_BASE = "$BASE/list/2/"
    private const val SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val MAX_PARALLEL_GENRES = 3

    // The current Linkkf Anime navigation exposes these class/tag searches.
    private val GENRES = listOf(
        "Action", "Webtoon", "Mystery", "Romance", "Sci-Fi", "Slice of Life",
        "Sports", "Adventure", "Avant Garde", "Boys Love", "Comedy", "Drama",
        "Fantasy", "Girls Love", "Gourmet", "Horror", "Supernatural", "Suspense",
        "Seinen", "Shoujo", "Shounen", "Military", "Music", "School", "CN Animation"
    )

    /**
     * Returns the list immediately with cached tags. If the tag index is stale,
     * it is refreshed from Linkkf class pages and the result is enriched.
     */
    suspend fun enrich(list: List<Anime>): List<Anime> {
        if (list.isEmpty()) return list
        val context = AppContextHolder.context
        val cached = applyCache(context, list)
        val missing = cached.filter { it.genres.isEmpty() && it.detailUrl.isNotBlank() }
        if (missing.isEmpty() || !LinkkfGenreIndexCache.canSync(context, SYNC_INTERVAL_MS)) {
            return cached
        }

        syncOnePagePerGenre(context, missing.map { it.detailUrl }.toSet())
        return applyCache(context, list)
    }

    private suspend fun syncOnePagePerGenre(context: Context, targetUrls: Set<String>) {
        if (targetUrls.isEmpty()) return

        // Small concurrency is intentional: unlike the old implementation this is
        // at most one request per genre per sync window, not one request per anime.
        GENRES.chunked(MAX_PARALLEL_GENRES).forEach { batch ->
            coroutineScope {
                batch.map { genre ->
                    async(Dispatchers.IO) {
                        scanGenrePage(context, genre, targetUrls)
                    }
                }.awaitAll()
            }
            delay(250L)
        }
        LinkkfGenreIndexCache.markSync(context)
    }

    private fun scanGenrePage(context: Context, genre: String, targetUrls: Set<String>) {
        val client = LinkkfClient()
        val page = LinkkfGenreIndexCache.nextPage(context, genre)
        val encoded = URLEncoder.encode(genre, StandardCharsets.UTF_8.toString()).replace("+", "%20")
        val url = if (page == 1) {
            "$LIST_BASE" + "class/$encoded/"
        } else {
            "$LIST_BASE" + "class/$encoded/page/$page/"
        }

        runCatching {
            val document = client.getDocument(url, "$BASE/")
            val animeList = LinkkfParser.parseAnimeList(document)
            if (animeList.isNotEmpty()) {
                animeList.forEach { anime ->
                    if (anime.detailUrl.isNotBlank()) {
                        LinkkfGenreIndexCache.merge(context, anime.detailUrl, genre)
                    }
                }
                LinkkfGenreIndexCache.markPage(context, genre, page)
            }
        }
    }

    private fun applyCache(context: Context, list: List<Anime>): List<Anime> = list.map { anime ->
        val tags = LinkkfGenreIndexCache.genresFor(context, anime.detailUrl)
        if (tags.isEmpty()) anime else anime.copy(genres = tags)
    }
}
