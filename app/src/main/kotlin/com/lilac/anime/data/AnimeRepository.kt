package com.lilac.anime.data

import com.lilac.anime.Anime
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

    companion object {
        private const val LINKKF_BASE_URL = "https://linkkf.tv"
        private const val LINKKF_LIST_URL = "$LINKKF_BASE_URL/list/2/"
        private const val ANIMENOSUB_BASE_URL = "https://animenosub.to"
        private const val BATCH_SIZE = 5
    }


    suspend fun getHomeAnimeList(source: String = "linkkf"): List<Anime> {
        return if (source == "animenosub") {
            val document = getDocument(source, ANIMENOSUB_BASE_URL, ANIMENOSUB_BASE_URL + "/")
            AnimenosubParser.parseAnimeList(document)
        } else {
            val document = getDocument(source, LINKKF_LIST_URL)
            LinkkfParser.parseAnimeList(document)
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
                    val document = getDocument(source, url, ANIMENOSUB_BASE_URL + "/")
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

        var batchStart = 1
        var emptyBatches = 0
        while (emptyBatches < 2) {
            val batchEnd = batchStart + BATCH_SIZE - 1
            val pageResults = coroutineScope {
                (batchStart..batchEnd).map { page ->
                    async(Dispatchers.IO) {
                        val url = if (page == 1) LINKKF_LIST_URL else "$LINKKF_LIST_URL" + "page/$page/"
                        try {
                            val document = getDocument(source, url, "https://linkkf.tv/")
                            page to LinkkfParser.parseAnimeList(document)
                        } catch (_: Exception) {
                            page to emptyList<Anime>()
                        }
                    }
                }.awaitAll().sortedBy { it.first }
            }
            val hadData = pageResults.any { it.second.isNotEmpty() }
            if (!hadData) emptyBatches++ else emptyBatches = 0
            for ((_, list) in pageResults) list.forEach { result[it.id] = it }
            if (result.isNotEmpty()) emit(result.values.toList())
            batchStart += BATCH_SIZE
        }
    }.flowOn(Dispatchers.IO)

    private fun getDocument(source: String, url: String, referer: String = if (source == "animenosub") ANIMENOSUB_BASE_URL + "/" else "https://linkkf.tv/") =
        if (source == "animenosub") animenosubClient.getDocument(url, referer) else linkkfClient.getDocument(url, referer)

    suspend fun getAnimeDetail(anime: Anime, source: String = "linkkf"): Anime {
        val document = getDocument(source, anime.detailUrl, if (source == "animenosub") ANIMENOSUB_BASE_URL + "/" else "https://linkkf.tv/" )
        return if (source == "animenosub") {
            val parsed = AnimenosubParser.parseAnimeDetail(document, anime)
            parsed.copy(
                episodes = parsed.episodes.ifEmpty { anime.episodes },
                dubEpisodes = parsed.dubEpisodes.ifEmpty { anime.dubEpisodes }
            )
        } else {
            val parsed = LinkkfParser.parseAnimeDetail(document, anime)
            parsed.copy(
                episodes = parsed.episodes.ifEmpty { anime.episodes },
                dubEpisodes = parsed.dubEpisodes.ifEmpty { anime.dubEpisodes }
            )
        }
    }

    suspend fun getEpisodes(anime: Anime, source: String = "linkkf"): List<Episode> {
        val document = getDocument(source, anime.detailUrl, if (source == "animenosub") ANIMENOSUB_BASE_URL + "/" else "https://linkkf.tv/" )
        return if (source == "animenosub") AnimenosubParser.parseEpisodes(document, anime)
        else LinkkfParser.parseEpisodes(document, anime)
    }

    suspend fun getDubEpisodes(anime: Anime, source: String = "linkkf"): List<Episode> {
        val document = getDocument(source, anime.detailUrl, if (source == "animenosub") ANIMENOSUB_BASE_URL + "/" else "https://linkkf.tv/" )
        return if (source == "animenosub") AnimenosubParser.parseDubEpisodes(document, anime)
        else LinkkfParser.parseDubEpisodes(document, anime)
    }
}
