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
    private val reAnimeHarClient = ReAnimeHarClient()

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
            "reanime" -> ReAnimeHarParser.parseSearch(reAnimeHarClient.search(limit = 36, offset = 0))
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
            var consecutiveFailures = 0
            while (consecutiveFailures < 3) {
                var page: List<Anime> = emptyList()
                repeat(3) { attempt ->
                    if (page.isNotEmpty()) return@repeat
                    try {
                        android.util.Log.d("ReAnimeHAR", "CATALOG_SEARCH offset=$offset limit=36 attempt=${attempt + 1}")
                        page = ReAnimeHarParser.parseSearch(
                            reAnimeHarClient.search(limit = 36, offset = offset)
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("ReAnimeHAR", "CATALOG_SEARCH_FAILED offset=$offset attempt=${attempt + 1}", e)
                        kotlinx.coroutines.delay(400L)
                    }
                }
                if (page.isEmpty()) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) break
                    continue
                }
                consecutiveFailures = 0
                page.forEach { result[it.id] = it }
                emit(result.values.toList())
                runCatching {
                    OfflineStore.mergeAnimeListCache(
                        AppContextHolder.context, page, source = "reanime", markFresh = false
                    )
                }
                if (page.size < 36) break
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

    /**
     * Re:Anime's lightweight catalog refresh. The API is newest-first, so a
     * 36-item page scan is normally enough to discover newly added titles.
     * Continue while pages contain new IDs; once a page is entirely composed
     * of cached IDs, the older part of the catalog is already synchronized.
     */
    suspend fun refreshReAnimeCatalogIncremental(
        cachedIds: Set<String>,
        maxPages: Int = 20
    ): List<Anime> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val discovered = LinkedHashMap<String, Anime>()
        var offset = 0
        var unchangedPages = 0

        repeat(maxPages) {
            val page = try {
                android.util.Log.d(
                    "ReAnime",
                    "INCREMENTAL_CATALOG_REQUEST offset=$offset limit=36"
                )
                ReAnimeHarParser.parseSearch(
                    reAnimeHarClient.search(limit = 36, offset = offset)
                )
            } catch (e: Exception) {
                android.util.Log.w(
                    "ReAnime",
                    "INCREMENTAL_CATALOG_FAILED offset=$offset",
                    e
                )
                return@withContext discovered.values.toList()
            }

            if (page.isEmpty()) return@withContext discovered.values.toList()

            var newOnPage = false
            page.forEach { anime ->
                if (!cachedIds.contains(anime.id) && !discovered.containsKey(anime.id)) {
                    discovered[anime.id] = anime
                    newOnPage = true
                }
            }

            android.util.Log.d(
                "ReAnime",
                "INCREMENTAL_CATALOG_PAGE offset=$offset size=${page.size} " +
                    "new=${if (newOnPage) discovered.size else 0}"
            )

            if (newOnPage) {
                unchangedPages = 0
            } else {
                unchangedPages++
                if (unchangedPages >= 1) {
                    return@withContext discovered.values.toList()
                }
            }

            if (page.size < 36) {
                return@withContext discovered.values.toList()
            }
            offset += 36
            kotlinx.coroutines.delay(120L)
        }

        discovered.values.toList()
    }

    suspend fun getLinkkfSchedule(categoryTagId: Int, limit: Int = 50): List<Anime> =
        kotlinx.coroutines.withContext(Dispatchers.IO) { linkkfApi.getSchedule(categoryTagId, limit) }

    suspend fun getLinkkfSeasonType(tagId: Int, limit: Int = 4): List<Anime> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            linkkfApi.getSeasonType(tagId, limit)
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

    suspend fun getReAnimeHomeData(): Triple<List<Anime>, List<Anime>, ReAnimeHarParser.Facets> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val top = runCatching { ReAnimeHarParser.parseTop(reAnimeHarClient.topAnime("today", 12)) }.getOrDefault(emptyList())
            val schedule = runCatching { ReAnimeHarParser.parseSchedule(reAnimeHarClient.schedule("Asia/Seoul", 0)) }.getOrDefault(emptyList())
            val facets = runCatching { ReAnimeHarParser.parseFacets(reAnimeHarClient.facets()) }.getOrDefault(ReAnimeHarParser.Facets())
            Triple(top, schedule, facets)
        }

    suspend fun searchReAnime(
        query: String = "",
        genre: List<String> = emptyList(),
        year: Int? = null,
        season: String? = null,
        status: String? = null,
        format: String? = null,
        tag: List<String> = emptyList(),
        character: List<String> = emptyList(),
        staff: List<String> = emptyList(),
        studio: List<String> = emptyList()
    ): List<Anime> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        ReAnimeHarParser.parseSearch(
            reAnimeHarClient.search(
                query = query, limit = 36, offset = 0, genre = genre, year = year,
                season = season, status = status, format = format, tag = tag,
                character = character, staff = staff, studio = studio
            )
        )
    }

    suspend fun searchAnime(query: String, source: String = "linkkf"): List<Anime> {
        if (source != "reanime") return emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return ReAnimeHarParser.parseSearch(reAnimeHarClient.search(query = trimmed, limit = 36, offset = 0))
    }

    suspend fun getAnimeDetail(anime: Anime, source: String = "linkkf"): Anime {
        if (source == "reanime") {
            val detailUrl = anime.detailUrl.trim().ifBlank {
                anime.id.removePrefix("reanime:").trim('/').let { "$REANIME_BASE_URL/anime/$it" }
            }
            val slug = detailUrl.substringAfter("/anime/").substringBefore("/").trim()
            if (slug.isBlank()) throw IllegalArgumentException("Re:Anime slug missing: ${anime.id}")
            android.util.Log.d("ReAnimeHAR", "DETAIL_DATA_REQUEST slug=$slug")
            val data = reAnimeHarClient.detailData(slug)
            val parsed = ReAnimeHarParser.parseDetail(data, anime.copy(detailUrl = detailUrl))
            val total = parsedEpisodesTotal(parsed, data)
            val watch = runCatching { reAnimeHarClient.watchData(slug) }.getOrDefault("")
            val episodes = ReAnimeHarParser.parseWatchEpisodes(watch, parsed, total)
            return parsed.copy(episodes = episodes)
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

    private fun parsedEpisodesTotal(anime: Anime, rawDetail: String): Int {
        // Anime.episodes is not populated until watch/__data.json is parsed.
        // Pull episodes_total directly from the HAR reference table through the
        // parser so the detail and watch requests stay source-accurate.
        return runCatching {
                val nodes = org.json.JSONObject(rawDetail).optJSONArray("nodes")
                var total = 0
                if (nodes != null) for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i) ?: continue
                    val data = node.optJSONArray("data") ?: continue
                    if (data.length() < 2) continue
                    val root = data.optJSONObject(0) ?: continue
                    val animeRef = root.optInt("anime", -1)
                    val obj = if (animeRef >= 0) data.optJSONObject(animeRef) else null
                    total = obj?.optInt("episodes_total", 0) ?: 0
                    if (total > 0) break
                }
                total
            }.getOrDefault(0)
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
            val slug = anime.detailUrl.substringAfter("/anime/").substringBefore("/").trim()
                .ifBlank { anime.id.removePrefix("reanime:").trim('/') }
            if (slug.isBlank()) return emptyList()
            android.util.Log.d("ReAnimeHAR", "EPISODES_DATA_REQUEST slug=$slug")
            val detail = reAnimeHarClient.detailData(slug)
            val parsed = ReAnimeHarParser.parseDetail(detail, anime)
            val watch = runCatching { reAnimeHarClient.watchData(slug) }.getOrDefault("")
            val total = parsedEpisodesTotal(parsed, detail)
            return ReAnimeHarParser.parseWatchEpisodes(watch, parsed, total)
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
