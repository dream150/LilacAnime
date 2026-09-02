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
        private const val BATCH_SIZE = 8
        private const val PAGE_RETRY_COUNT = 3
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
            // Keep Animenosub sequential because concurrent catalog requests are
            // more likely to trigger its protection. Do not stop on one empty page.
            var consecutiveEmptyPages = 0
            for (page in 1..100) {
                val url = if (page == 1) ANIMENOSUB_BASE_URL else "$ANIMENOSUB_BASE_URL/page/$page/"
                val list = try {
                    val document = getDocument(source, url, ANIMENOSUB_BASE_URL + "/")
                    AnimenosubParser.parseAnimeList(document)
                } catch (_: Exception) {
                    emptyList()
                }
                if (list.isEmpty()) {
                    consecutiveEmptyPages++
                    // A single failed/empty response must not truncate the catalog.
                    if (consecutiveEmptyPages >= 3) break
                } else {
                    consecutiveEmptyPages = 0
                    list.forEach { result[it.id] = it }
                    emit(result.values.toList())
                }
                kotlinx.coroutines.delay(200L)
            }
            return@flow
        }

        // Fetch page 1 first. It gives us both the first batch of titles and, when
        // available, the site's actual last pagination page. This avoids the old
        // fixed 50-page/2-empty-batch cutoff which could silently truncate the catalog.
        val firstDocument = try {
            getDocument(source, LINKKF_LIST_URL, "https://linkkf.tv/")
        } catch (e: Exception) {
            throw e
        }
        val firstList = LinkkfParser.parseAnimeList(firstDocument)
        firstList.forEach { result[it.id] = it }
        if (result.isNotEmpty()) emit(result.values.toList())

        val advertisedLastPage = LinkkfParser.parseLastCatalogPage(firstDocument)

        // The pagination rendered by Linkkf is authoritative. Do NOT impose an
        // artificial maximum page count: if the site advertises page 138, fetch
        // through page 138. A missing/failed page is retried and never treated as
        // the end of the catalog.
        val maxPage = if (advertisedLastPage > 1) {
            advertisedLastPage
        } else {
            // Fallback for responses where the pagination block was stripped by a
            // proxy/cache. Discover the end without assuming a fixed catalog size.
            discoverLastCatalogPage(source, startPage = 2)
        }

        var page = 2
        while (page <= maxPage) {
            val batchEnd = minOf(page + BATCH_SIZE - 1, maxPage)
            val batch = coroutineScope {
                (page..batchEnd).map { pageNumber ->
                    async(Dispatchers.IO) {
                        val url = "$LINKKF_LIST_URL" + "page/$pageNumber/"
                        repeat(PAGE_RETRY_COUNT) { attempt ->
                            try {
                                val document = getDocument(source, url, "https://linkkf.tv/")
                                val list = LinkkfParser.parseAnimeList(document)
                                if (list.isNotEmpty()) return@async list
                            } catch (_: Exception) {
                                // Retry transient HTTP/cache failures.
                            }
                            if (attempt + 1 < PAGE_RETRY_COUNT) {
                                kotlinx.coroutines.delay(350L * (attempt + 1))
                            }
                        }
                        emptyList()
                    }
                }.awaitAll()
            }

            batch.flatten().forEach { result[it.id] = it }
            if (batch.any { it.isNotEmpty() }) {
                emit(result.values.toList())
            }

            page = batchEnd + 1
            kotlinx.coroutines.delay(200L)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun discoverLastCatalogPage(source: String, startPage: Int): Int {
        suspend fun hasItems(page: Int): Boolean {
            val url = "$LINKKF_LIST_URL" + "page/$page/"
            repeat(PAGE_RETRY_COUNT) { attempt ->
                try {
                    val document = getDocument(source, url, "https://linkkf.tv/")
                    if (LinkkfParser.parseAnimeList(document).isNotEmpty()) return true
                } catch (_: Exception) {
                    // Retry transient HTTP/cache failures.
                }
                if (attempt + 1 < PAGE_RETRY_COUNT) {
                    kotlinx.coroutines.delay(350L * (attempt + 1))
                }
            }
            return false
        }

        // Find an upper bound first, then binary-search the last non-empty page.
        // Catalog pages are expected to be contiguous.
        var low = startPage - 1
        var high = maxOf(startPage, 2)
        while (hasItems(high)) {
            low = high
            high *= 2
            if (high > 4096) return low
        }

        while (low + 1 < high) {
            val mid = low + (high - low) / 2
            if (hasItems(mid)) low = mid else high = mid
        }
        return low
    }

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
