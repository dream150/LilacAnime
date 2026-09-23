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
    private val linkkfApi = LinkkfApiClient()
    private val animenosubClient = AnimenosubHttpClient()
    private val reAnimeClient = ReAnimeClient()

    companion object {
        private const val LINKKF_BASE_URL = LinkkfApiClient.WEB_BASE
        private const val LINKKF_LIST_URL = "$LINKKF_BASE_URL/"
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
            else -> linkkfApi.getHome(page = 1, limit = 12)
        }
    }

    fun getAllAnimeListFlow(source: String = "linkkf"): Flow<List<Anime>> = flow {
        val result = LinkedHashMap<String, Anime>()

        if (source == "animenosub") {
            var emptyPages = 0
            for (page in 1..50) {
                val url = if (page == 1) ANIMENOSUB_BASE_URL else "$ANIMENOSUB_BASE_URL/page/$page/"
                val list = try {
                    val document = animenosubClient.getDocument(url, ANIMENOSUB_BASE_URL + "/")
                    AnimenosubParser.parseAnimeList(document)
                } catch (_: Exception) { emptyList() }
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
            while (emptyPages < 2) {
                val page = try {
                    android.util.Log.d("ReAnime", "CATALOG_REQUEST offset=$offset limit=36")
                    val parsed = ReAnimeParser.parseAnimeApi(reAnimeClient.catalogAnime(limit = 36, offset = offset))
                    android.util.Log.d("ReAnime", "CATALOG_PAGE offset=$offset size=${parsed.size}")
                    parsed
                } catch (e: Exception) {
                    android.util.Log.e("ReAnime", "CATALOG_FAILED offset=$offset", e)
                    emptyList()
                }
                if (page.isEmpty()) emptyPages++ else {
                    emptyPages = 0
                    page.forEach { result[it.id] = it }
                    emit(result.values.toList())
                    if (page.size < 36) break
                }
                offset += 36
                kotlinx.coroutines.delay(150L)
            }
            return@flow
        }

        // linkkf.app exposes the full catalog through filter.php pagination.
        // Do not scrape /list/ pages: the site no longer uses the old the previous site markup
        // HTML card structure.
        var page = 1
        while (page <= 351) {
            val items = try {
                android.util.Log.d("LinkkfAPI", "CATALOG_REQUEST page=$page limit=12")
                val parsed = linkkfApi.getHome(page = page, limit = 12)
                android.util.Log.d("LinkkfAPI", "CATALOG_PAGE page=$page size=${parsed.size}")
                parsed
            } catch (e: Exception) {
                android.util.Log.e("LinkkfAPI", "CATALOG_FAILED page=$page", e)
                emptyList()
            }
            if (items.isEmpty()) break
            items.forEach { result[it.id] = it }
            emit(result.values.toList())
            if (items.size < 12) break
            page++
            kotlinx.coroutines.delay(100L)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun getLinkkfSchedule(categoryTagId: Int, limit: Int = 50): List<Anime> =
        kotlinx.coroutines.withContext(Dispatchers.IO) { linkkfApi.getSchedule(categoryTagId, limit) }

    suspend fun getLinkkfSeasonType(tagId: Int, limit: Int = 4): List<Anime> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            linkkfApi.getFilteredAnime(page = 1, limit = limit, seasonTypeIds = listOf(tagId)).items
        }

    suspend fun getLinkkfFilterTags(taxonomy: String): List<LinkkfApiClient.FilterTag> =
        kotlinx.coroutines.withContext(Dispatchers.IO) { linkkfApi.getFilterTags(taxonomy) }

    suspend fun getLinkkfFilteredAnime(
        page: Int = 1,
        limit: Int = 20,
        seasonTypeIds: List<Int> = emptyList(),
        genreIds: List<Int> = emptyList(),
        yearIds: List<Int> = emptyList()
    ): LinkkfApiClient.FilterResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
        linkkfApi.getFilteredAnime(page, limit, seasonTypeIds, genreIds, yearIds)
    }

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

        if (source == "animenosub") {
            val document = animenosubClient.getDocument(anime.detailUrl, ANIMENOSUB_BASE_URL + "/")
            val parsed = AnimenosubParser.parseAnimeDetail(document, anime)
            AnimeGenreCache.put(AppContextHolder.context, source, anime.detailUrl, parsed.genres)
            return parsed.copy(
                episodes = parsed.episodes.ifEmpty { anime.episodes },
                dubEpisodes = parsed.dubEpisodes.ifEmpty { anime.dubEpisodes }
            )
        }

        val apiAnime = linkkfApi.getAnime(anime.id) ?: anime
        val episodes = linkkfApi.getEpisodes(anime.id)
        AnimeGenreCache.put(AppContextHolder.context, source, apiAnime.detailUrl, apiAnime.genres)
        return apiAnime.copy(
            description = anime.description,
            episodes = episodes,
            dubEpisodes = emptyList()
        )
    }

    suspend fun getLinkkfEpisodeServers(postId: String): List<LinkkfApiClient.EpisodeServer> =
        kotlinx.coroutines.withContext(Dispatchers.IO) { linkkfApi.getEpisodeServers(postId) }

    suspend fun getLinkkfViewStats(postId: String): LinkkfApiClient.ViewStats? =
        kotlinx.coroutines.withContext(Dispatchers.IO) { linkkfApi.getViewStats(postId) }

    suspend fun recordLinkkfView(postId: String): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) { linkkfApi.recordView(postId) }

    suspend fun getLinkkfRelatedSeries(anime: Anime): List<LinkkfApiClient.RelatedSeries> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            linkkfApi.getRelatedSeries(anime.seriesTagIds, anime.id)
        }

    suspend fun getEpisodes(anime: Anime, source: String = "linkkf"): List<Episode> {
        if (source == "reanime") {
            val slug = anime.detailUrl
                .substringAfter("/anime/", "")
                .substringBefore("?")
                .substringBefore("/")
                .trim()
            if (slug.isBlank()) return emptyList()
            val episodeUrl = REANIME_BASE_URL + "/watch/" + slug + "?ep=1"
            val document = reAnimeClient.getDocument(episodeUrl, anime.detailUrl)
            return ReAnimeParser.parseEpisodes(document, anime)
        }
        if (source == "animenosub") {
            val document = animenosubClient.getDocument(anime.detailUrl, ANIMENOSUB_BASE_URL + "/")
            return AnimenosubParser.parseEpisodes(document, anime)
        }
        return linkkfApi.getEpisodes(anime.id)
    }

    suspend fun getDubEpisodes(anime: Anime, source: String = "linkkf"): List<Episode> {
        if (source == "reanime") return emptyList()
        if (source == "animenosub") {
            val document = animenosubClient.getDocument(anime.detailUrl, ANIMENOSUB_BASE_URL + "/")
            return AnimenosubParser.parseDubEpisodes(document, anime)
        }
        return emptyList()
    }

}
