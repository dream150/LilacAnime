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

import com.lilac.anime.Anime
import com.lilac.anime.AppContextHolder
import com.lilac.anime.Episode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class AnimeRepository {
    private val linkkfClient = LinkkfClient()
    private val animenosubClient = AnimenosubHttpClient()
    private val reAnimeClient = ReAnimeClient()

    companion object {
        private const val LINKKF_BASE_URL = "https://linkkf.tv"
        private const val LINKKF_LIST_URL = "$LINKKF_BASE_URL/list/2/"
        private const val ANIMENOSUB_BASE_URL = "https://animenosub.to"
        private const val REANIME_BASE_URL = "https://reanime.to"
        private const val BATCH_SIZE = 5
    }


    suspend fun getHomeAnimeList(source: String = "linkkf"): List<Anime> {
        return when (source) {
            "animenosub" -> {
                val document = animenosubClient.getDocument(ANIMENOSUB_BASE_URL, ANIMENOSUB_BASE_URL + "/")
                AnimenosubParser.parseAnimeList(document)
            }
            "reanime" -> ReAnimeParser.parseAnimeApi(reAnimeClient.catalogAnime(36, 0))
            else -> {
            val document = linkkfClient.getDocument(LINKKF_LIST_URL)
            LinkkfGenreIndexRepository.enrich(LinkkfParser.parseAnimeList(document))
            }
        }
    }

    fun getAllAnimeListFlow(source: String = "linkkf"): Flow<List<Anime>> = flow {
        val result = LinkedHashMap<String, Anime>()

        if (source == "animenosub") {
            // Animenosub is protected more aggressively when several pages are
            // requested in parallel. Fetch its catalog sequentially and tolerate
            // transient empty/error pages instead of stopping the entire catalog.
            var emptyPages = 0
            for (page in 1..50) {
                val url = if (page == 1) ANIMENOSUB_BASE_URL else "$ANIMENOSUB_BASE_URL/page/$page/"
                val list = try {
                    val document = animenosubClient.getDocument(url, ANIMENOSUB_BASE_URL + "/")
                    AnimenosubParser.parseAnimeList(document)
                } catch (_: Exception) {
                    emptyList()
                }
                if (list.isEmpty()) {
                    emptyPages++
                    if (emptyPages >= 2) break
                } else {
                    emptyPages = 0
                    list.forEach { result[it.id] = it }
                    emit(result.values.toList())
                }
                kotlinx.coroutines.delay(250L)
            }
            return@flow
        }

        if (source == "reanime") {
            var offset = 0
            var emptyPages = 0

            while (emptyPages < 2 && offset < 5000) {
                val page = try {
                    ReAnimeParser.parseAnimeApi(
                        reAnimeClient.catalogAnime(
                            limit = 36,
                            offset = offset
                        )
                    )
                } catch (e: Exception) {
                    android.util.Log.e(
                        "ReAnime",
                        "CATALOG_FAILED offset=" + offset,
                        e
                    )
                    emptyList()
                }

                android.util.Log.d(
                    "ReAnime",
                    "CATALOG_RESULT offset=" + offset +
                        " count=" + page.size
                )

                if (page.isEmpty()) {
                    emptyPages++
                } else {
                    emptyPages = 0
                    page.forEach { anime ->
                        result[anime.id] = anime
                    }
                    emit(result.values.toList())

                    if (page.size < 36) {
                        break
                    }
                }

                offset += 36
                kotlinx.coroutines.delay(150L)
            }

            return@flow
        }

        var batchStart = 1
        var emptyBatches = 0
        while (emptyBatches < 2) {
            val batchEnd = batchStart + BATCH_SIZE - 1
            val pageResults = coroutineScope {
                (batchStart..batchEnd).map { page ->
                    async(Dispatchers.IO) {
                        val url = if (page == 1) LINKKF_LIST_URL else "$LINKKF_LIST_URL" + "page/$page/"
                        try {
                            val document = linkkfClient.getDocument(url, "https://linkkf.tv/")
                            page to LinkkfParser.parseAnimeList(document)
                        } catch (_: Exception) {
                            page to emptyList<Anime>()
                        }
                    }
                }.awaitAll().sortedBy { it.first }
            }
            val hadData = pageResults.any { it.second.isNotEmpty() }
            if (!hadData) emptyBatches++ else emptyBatches = 0
            val batchAnime = pageResults.flatMap { it.second }
            val enrichedBatch = if (source == "linkkf") {
                LinkkfGenreIndexRepository.enrich(batchAnime)
            } else {
                batchAnime
            }
            enrichedBatch.forEach { result[it.id] = it }
            if (result.isNotEmpty()) emit(result.values.toList())
            batchStart += BATCH_SIZE
        }
    }.flowOn(Dispatchers.IO)

    suspend fun searchAnime(query: String, source: String = "linkkf"): List<Anime> {
        if (source != "reanime") return emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return ReAnimeParser.parseAnimeApi(reAnimeClient.searchAnime(trimmed, 36, 0))
    }

    suspend fun getAnimeDetail(anime: Anime, source: String = "linkkf"): Anime {
        if (source == "reanime") {
            android.util.Log.d("ReAnime", "DETAIL_REQUEST id=" + anime.id + " detailUrl=" + anime.detailUrl)
            return ReAnimeParser.parseAnimeDetail(
                reAnimeClient.getDocument(anime.detailUrl, REANIME_BASE_URL + "/"),
                anime
            ).copy(episodes = getEpisodes(anime, source))
        }
        val document = if (source == "animenosub") animenosubClient.getDocument(anime.detailUrl, ANIMENOSUB_BASE_URL + "/") else linkkfClient.getDocument(anime.detailUrl, "https://linkkf.tv/")
        return if (source == "animenosub") {
            val parsed = AnimenosubParser.parseAnimeDetail(document, anime)
            AnimeGenreCache.put(AppContextHolder.context, source, anime.detailUrl, parsed.genres)
            parsed.copy(
                episodes = parsed.episodes.ifEmpty { anime.episodes },
                dubEpisodes = parsed.dubEpisodes.ifEmpty { anime.dubEpisodes }
            )
        } else {
            val parsed = LinkkfParser.parseAnimeDetail(document, anime)
            AnimeGenreCache.put(AppContextHolder.context, source, anime.detailUrl, parsed.genres)
            parsed.copy(
                episodes = parsed.episodes.ifEmpty { anime.episodes },
                dubEpisodes = parsed.dubEpisodes.ifEmpty { anime.dubEpisodes }
            )
        }
    }

    suspend fun getEpisodes(anime: Anime, source: String = "linkkf"): List<Episode> {
        if (source == "reanime") {
            // Re:ANIME's /anime/{slug} page contains the metadata, while the
            // rendered episode list is available on /watch/{slug}?ep=1.
            // Use the watch page as the episode index source.
            val slug = anime.detailUrl
                .substringAfter("/anime/", "")
                .substringBefore("?")
                .substringBefore("/")
                .trim()

            if (slug.isBlank()) return emptyList()

            val episodeUrl = REANIME_BASE_URL + "/watch/" + slug + "?ep=1"
            android.util.Log.d("ReAnime", "EPISODES_REQUEST url=" + episodeUrl)

            val document = reAnimeClient.getDocument(episodeUrl, anime.detailUrl)
            val episodes = ReAnimeParser.parseEpisodes(document, anime)

            android.util.Log.d(
                "ReAnime",
                "EPISODES_RESULT slug=" + slug + " count=" + episodes.size
            )

            return episodes
        }
        val document = if (source == "animenosub") animenosubClient.getDocument(anime.detailUrl, ANIMENOSUB_BASE_URL + "/") else linkkfClient.getDocument(anime.detailUrl, "https://linkkf.tv/")
        return if (source == "animenosub") AnimenosubParser.parseEpisodes(document, anime)
        else LinkkfParser.parseEpisodes(document, anime)
    }

    suspend fun getDubEpisodes(anime: Anime, source: String = "linkkf"): List<Episode> {
        if (source == "reanime") return emptyList()
        val document = if (source == "animenosub") animenosubClient.getDocument(anime.detailUrl, ANIMENOSUB_BASE_URL + "/") else linkkfClient.getDocument(anime.detailUrl, "https://linkkf.tv/")
        return if (source == "animenosub") AnimenosubParser.parseDubEpisodes(document, anime)
        else LinkkfParser.parseDubEpisodes(document, anime)
    }
}
