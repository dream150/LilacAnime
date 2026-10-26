package com.lilac.anime.data

import com.lilac.anime.*
import com.lilac.anime.data.offline.*

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
import kotlinx.coroutines.withContext

class AnimeRepository {
    private val linkkfApi = LinkkfApiClient()
    private val animenosubClient = AnimenosubHttpClient()
    private val reAnimeClient = ReAnimeClient()
    private val reAnimeHarClient = ReAnimeHarClient()
    private val miruroClient = MiruroClient()
    private val ohli24Client = Ohli24Client()

    companion object {
        private const val LINKKF_BASE_URL = LinkkfApiClient.WEB_BASE
        private const val LINKKF_LIST_URL = "$LINKKF_BASE_URL/"
        private const val ANIMENOSUB_BASE_URL = "https://animenosub.to"
        private const val REANIME_BASE_URL = "https://reanime.to"
        private const val BATCH_SIZE = 5
    }

    suspend fun getHomeAnimeList(source: String = "linkkf"): List<Anime> {
        return when (source) {
            "miruro" -> withContext(Dispatchers.IO) { miruroClient.catalog().first }
            "ohli24" -> withContext(Dispatchers.IO) { ohli24Client.catalog(1) }
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

        if (source == "miruro") {
            var cursor = ""
            var consecutiveFailures = 0
            // The desktop catalog follows the API cursor until has_more/next_cursor says it is done.
            // Track successfully processed cursors rather than using a fixed page-count cap, so a
            // repeated/cyclic cursor cannot create an infinite request loop.
            val processedCursors = HashSet<String>()
            while (consecutiveFailures < 3) {
                var page: Pair<List<Anime>, String?>? = null
                repeat(3) { attempt ->
                    if (page != null) return@repeat
                    try {
                        android.util.Log.d("MiruroCatalog", "PAGE_REQUEST cursor=${cursor.ifBlank { "<first>" }} attempt=${attempt + 1}")
                        page = miruroClient.catalog(cursor = cursor)
                    } catch (e: Exception) {
                        android.util.Log.e("MiruroCatalog", "PAGE_FAILED cursor=$cursor attempt=${attempt + 1}", e)
                        if (attempt < 2) kotlinx.coroutines.delay(350L * (attempt + 1))
                    }
                }
                if (page == null) {
                    consecutiveFailures++
                    android.util.Log.e("MiruroCatalog", "CURSOR_RETRY cursor=$cursor failures=$consecutiveFailures/3")
                    // Keep the last valid cursor and retry it on the next outer iteration.
                    continue
                }
                consecutiveFailures = 0
                val pageData = page ?: break
                if (cursor.isNotBlank() && !processedCursors.add(cursor)) {
                    android.util.Log.w("MiruroCatalog", "STOP_REPEATED_CURSOR cursor=$cursor loaded=${result.size}")
                    break
                }
                pageData.first.forEach { result[it.id] = it }
                if (pageData.first.isNotEmpty()) {
                    emit(result.values.toList())
                    android.util.Log.d("MiruroCatalog", "PAGE_LOADED cursor=${cursor.ifBlank { "<first>" }} pageSize=${pageData.first.size} total=${result.size}")
                }
                val next = pageData.second
                if (next.isNullOrBlank()) {
                    android.util.Log.d("MiruroCatalog", "CATALOG_COMPLETE total=${result.size} reason=no_next_cursor")
                    break
                }
                if (next == cursor || next in processedCursors) {
                    android.util.Log.w("MiruroCatalog", "STOP_CURSOR_CYCLE current=$cursor next=$next loaded=${result.size}")
                    break
                }
                cursor = next
                kotlinx.coroutines.delay(120L)
            }
            if (consecutiveFailures >= 3) {
                android.util.Log.e("MiruroCatalog", "STOP_AFTER_FAILURES cursor=$cursor loaded=${result.size}")
            }
            return@flow
        }

        if (source == "ohli24") {
            var page = 1
            var emptyPages = 0
            var consecutiveFailures = 0
            while (page <= 120 && consecutiveFailures < 3) {
                var items: List<Anime>? = null
                repeat(3) { attempt ->
                    if (items != null) return@repeat
                    try {
                        items = withContext(Dispatchers.IO) { ohli24Client.catalog(page) }
                    } catch (e: Exception) {
                        android.util.Log.e("Ohli24Catalog", "PAGE_FAILED page=$page attempt=${attempt + 1}", e)
                        if (attempt < 2) kotlinx.coroutines.delay(350L * (attempt + 1))
                    }
                }
                if (items == null) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) {
                        android.util.Log.e("Ohli24Catalog", "STOP_AFTER_FAILURES page=$page")
                        break
                    }
                    // Retry the same page on the next loop; never skip catalog entries due to a transient error.
                    continue
                }
                consecutiveFailures = 0
                val pageItems = items.orEmpty()
                if (pageItems.isEmpty()) {
                    emptyPages++
                    if (emptyPages >= 2) break
                } else {
                    emptyPages = 0
                    pageItems.forEach { result[it.id] = it }
                    emit(result.values.toList())
                }
                page++
                kotlinx.coroutines.delay(200L)
            }
            var consecutiveMovieFailures = 0
            var moviePage = 1
            while (moviePage <= 20 && consecutiveMovieFailures < 3) {
                var movies: List<Anime>? = null
                repeat(3) { attempt ->
                    if (movies != null) return@repeat
                    try {
                        movies = withContext(Dispatchers.IO) { ohli24Client.movies(moviePage) }
                    } catch (e: Exception) {
                        android.util.Log.w("Ohli24Catalog", "MOVIE_PAGE_FAILED page=$moviePage attempt=${attempt + 1}", e)
                        if (attempt < 2) kotlinx.coroutines.delay(300L * (attempt + 1))
                    }
                }
                if (movies == null) {
                    consecutiveMovieFailures++
                    continue
                }
                consecutiveMovieFailures = 0
                val movieItems = movies.orEmpty()
                if (movieItems.isEmpty()) break
                movieItems.forEach { result[it.id] = it }
                emit(result.values.toList())
                moviePage++
                kotlinx.coroutines.delay(150L)
            }
            return@flow
        }

        if (source == "animenosub") {
            var emptyPages = 0
            var consecutiveFailures = 0
            for (page in 1..50) {
                val url = if (page == 1) ANIMENOSUB_BASE_URL else "$ANIMENOSUB_BASE_URL/page/$page/"
                var list: List<Anime>? = null
                repeat(3) { attempt ->
                    if (list != null) return@repeat
                    try {
                        val document = animenosubClient.getDocument(url, ANIMENOSUB_BASE_URL + "/")
                        list = AnimenosubParser.parseAnimeList(document)
                    } catch (e: Exception) {
                        android.util.Log.w("AnimenosubCatalog", "PAGE_FAILED page=$page attempt=${attempt + 1}", e)
                        if (attempt < 2) kotlinx.coroutines.delay(300L * (attempt + 1))
                    }
                }
                if (list == null) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) break
                    continue
                }
                consecutiveFailures = 0
                val pageItems = list.orEmpty()
                if (pageItems.isEmpty()) {
                    emptyPages++
                    if (emptyPages >= 2) break
                } else {
                    emptyPages = 0
                    pageItems.forEach { result[it.id] = it }
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
        var consecutiveFailures = 0
        while (page <= 351 && consecutiveFailures < 3) {
            var items: List<Anime>? = null
            repeat(3) { attempt ->
                if (items != null) return@repeat
                try {
                    android.util.Log.d("LinkkfAPI", "CATALOG_REQUEST page=$page limit=12 attempt=${attempt + 1}")
                    items = linkkfApi.getHome(page = page, limit = 12)
                    android.util.Log.d("LinkkfAPI", "CATALOG_PAGE page=$page size=${items?.size ?: 0}")
                } catch (e: Exception) {
                    android.util.Log.e("LinkkfAPI", "CATALOG_FAILED page=$page attempt=${attempt + 1}", e)
                    if (attempt < 2) kotlinx.coroutines.delay(300L * (attempt + 1))
                }
            }
            if (items == null) {
                consecutiveFailures++
                // Skip a repeatedly failing page instead of treating a network error as end-of-catalog.
                page++
                continue
            }
            consecutiveFailures = 0
            val pageItems = items.orEmpty()
            if (pageItems.isEmpty()) break
            pageItems.forEach { result[it.id] = it }
            emit(result.values.toList())
            if (pageItems.size < 12) break
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
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return when (source) {
            "miruro" -> withContext(Dispatchers.IO) { miruroClient.catalog(query = trimmed).first }
            "ohli24" -> withContext(Dispatchers.IO) { ohli24Client.catalog(query = trimmed) }
            "reanime" -> ReAnimeHarParser.parseSearch(reAnimeHarClient.search(query = trimmed, limit = 36, offset = 0))
            "animenosub" -> {
                val document = animenosubClient.getDocument("$ANIMENOSUB_BASE_URL/?s=${java.net.URLEncoder.encode(trimmed, "UTF-8")}", "$ANIMENOSUB_BASE_URL/")
                AnimenosubParser.parseAnimeList(document)
            }
            "linkkf" -> withContext(Dispatchers.IO) { linkkfApi.search(trimmed) }
            else -> emptyList()
        }
    }

    suspend fun getAnimeDetail(anime: Anime, source: String = "linkkf"): Anime {
        if (source == "miruro") return withContext(Dispatchers.IO) { miruroClient.detail(anime) }
        if (source == "ohli24") return withContext(Dispatchers.IO) { ohli24Client.detail(anime) }
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
        // getAnime() already parses the episode tabs from the detail page. Re-fetching
        // getEpisodes() used to request and parse the same page a second time, and a
        // transient failure on that second request could erase a valid episode list.
        val episodes = apiAnime.episodes.ifEmpty { linkkfApi.getEpisodes(anime.id) }
        AnimeGenreCache.put(AppContextHolder.context, source, apiAnime.detailUrl, apiAnime.genres)
        return apiAnime.copy(
            // Prefer the full detail-page synopsis; use the catalog synopsis only if
            // the detail page really has no description.
            description = apiAnime.description.ifBlank { anime.description },
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
        if (source == "miruro") return anime.episodes.ifEmpty { withContext(Dispatchers.IO) { miruroClient.detail(anime).episodes } }
        if (source == "ohli24") return anime.episodes.ifEmpty { withContext(Dispatchers.IO) { ohli24Client.detail(anime).episodes } }
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
