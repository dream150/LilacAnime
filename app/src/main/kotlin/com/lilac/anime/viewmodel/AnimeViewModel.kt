package com.lilac.anime.viewmodel

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

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lilac.anime.data.*
import com.lilac.anime.data.offline.MpvOfflineStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
class AnimeViewModel : ViewModel() {
    private val appContext: Context by lazy { AppContextHolder.context }
    private val repository = AnimeRepository()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline

    private val _downloadedIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds

    private val _downloadProgressMap = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgressMap: StateFlow<Map<String, Float>> = _downloadProgressMap

    var homeAnime by mutableStateOf<List<Anime>>(emptyList())
        private set

    var allAnime by mutableStateOf<List<Anime>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var reAnimeSearchResults by mutableStateOf<List<Anime>>(emptyList())
        private set
    var reAnimeSearchLoading by mutableStateOf(false)
        private set
    var linkkfSchedule by mutableStateOf<Map<Int, List<Anime>>>(emptyMap())
        private set
    var linkkfScheduleLoading by mutableStateOf(false)
        private set

    var linkkfFormatTags by mutableStateOf<List<LinkkfApiClient.FilterTag>>(emptyList())
        private set
    var linkkfGenreTags by mutableStateOf<List<LinkkfApiClient.FilterTag>>(emptyList())
        private set
    var linkkfYearTags by mutableStateOf<List<LinkkfApiClient.FilterTag>>(emptyList())
        private set
    var linkkfFilterResults by mutableStateOf<List<Anime>>(emptyList())
        private set
    var linkkfFilterPage by mutableIntStateOf(1)
        private set
    var linkkfFilterTotalPages by mutableIntStateOf(1)
        private set
    var linkkfFilterTotalResults by mutableIntStateOf(0)
        private set
    var linkkfFilterLoading by mutableStateOf(false)
        private set

    var linkkfPvTrailers by mutableStateOf<List<Anime>>(emptyList())
        private set
    var linkkfMovies by mutableStateOf<List<Anime>>(emptyList())
        private set
    var linkkf16Plus by mutableStateOf<List<Anime>>(emptyList())
        private set
    var linkkfHomeSectionsLoading by mutableStateOf(false)
        private set

    var linkkfDetailAnimeId by mutableStateOf<String?>(null)
        private set
    var linkkfEpisodeServers by mutableStateOf<List<LinkkfApiClient.EpisodeServer>>(emptyList())
        private set
    var linkkfSelectedServerId by mutableIntStateOf(-1)
        private set
    var linkkfViewStats by mutableStateOf<LinkkfApiClient.ViewStats?>(null)
        private set
    var linkkfRelatedSeries by mutableStateOf<List<LinkkfApiClient.RelatedSeries>>(emptyList())
        private set
    var linkkfDetailExtrasLoading by mutableStateOf(false)
        private set

    private var reAnimeSearchJob: Job? = null
    private var linkkfDetailExtrasJob: Job? = null

    fun loadLinkkfHomeSchedule(force: Boolean = false) {
        if (linkkfScheduleLoading) return
        if (!force && linkkfSchedule.isNotEmpty()) return
        linkkfScheduleLoading = true
        viewModelScope.launch {
            val ids = listOf(21189, 21190, 21191, 21192, 21193, 21194, 21195)
            try {
                val loaded = withContext(Dispatchers.IO) {
                    ids.map { id -> id to repository.getLinkkfSchedule(id, 50) }.toMap()
                }
                linkkfSchedule = loaded
            } catch (e: Exception) {
                Log.e("LinkkfAPI", "SCHEDULE_FAILED", e)
            } finally {
                linkkfScheduleLoading = false
            }
        }
    }

    fun loadLinkkfFilterTags() {
        if (linkkfFormatTags.isNotEmpty() || linkkfGenreTags.isNotEmpty() || linkkfYearTags.isNotEmpty()) return
        viewModelScope.launch {
            try {
                // Keep this sequential: it works with the lightweight coroutine
                // environment used by CodeAssist and avoids an unnecessary
                // dependency on coroutineScope/async here.
                val format = withContext(Dispatchers.IO) {
                    repository.getLinkkfFilterTags("anime-seasontype")
                }
                val genres = withContext(Dispatchers.IO) {
                    repository.getLinkkfFilterTags("anigenres")
                }
                val years = withContext(Dispatchers.IO) {
                    repository.getLinkkfFilterTags("anime-seasonys")
                }
                linkkfFormatTags = format
                linkkfGenreTags = genres
                linkkfYearTags = years.reversed()
            } catch (e: Exception) {
                Log.e("LinkkfAPI", "FILTER_TAGS_FAILED", e)
            }
        }
    }

    fun loadLinkkfHomeSections(force: Boolean = false) {
        if (linkkfHomeSectionsLoading) return
        if (!force && (linkkfPvTrailers.isNotEmpty() || linkkfMovies.isNotEmpty() || linkkf16Plus.isNotEmpty())) return
        linkkfHomeSectionsLoading = true
        viewModelScope.launch {
            try {
                val pv = withContext(Dispatchers.IO) { repository.getLinkkfSeasonType(5086, 4) }
                val movies = withContext(Dispatchers.IO) { repository.getLinkkfSeasonType(5061, 4) }
                val adult16 = withContext(Dispatchers.IO) { repository.getLinkkfSeasonType(5085, 4) }
                linkkfPvTrailers = pv
                linkkfMovies = movies
                linkkf16Plus = adult16
            } catch (e: Exception) {
                Log.e("LinkkfAPI", "HOME_SECTIONS_FAILED", e)
            } finally {
                linkkfHomeSectionsLoading = false
            }
        }
    }

    fun loadLinkkfFilteredAnime(
        page: Int = 1,
        formatIds: List<Int> = emptyList(),
        genreIds: List<Int> = emptyList(),
        yearIds: List<Int> = emptyList()
    ) {
        if (linkkfFilterLoading) return
        linkkfFilterLoading = true
        viewModelScope.launch {
            try {
                val result = repository.getLinkkfFilteredAnime(page, 20, formatIds, genreIds, yearIds)
                linkkfFilterResults = result.items
                linkkfFilterPage = result.page
                linkkfFilterTotalPages = result.totalPages
                linkkfFilterTotalResults = result.totalResults
            } catch (e: Exception) {
                Log.e("LinkkfAPI", "FILTER_FAILED", e)
                linkkfFilterResults = emptyList()
            } finally {
                linkkfFilterLoading = false
            }
        }
    }

    var isAllAnimeLoading by mutableStateOf(false)
        private set

    private var isAllAnimeFullyLoaded = false
    private var allAnimeLoadJob: Job? = null
    private var catalogLoadGeneration: Long = 0L
    var sourceRevision by mutableIntStateOf(0)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var playerSettings by mutableStateOf(PlayerSettings())
        private set

    private val detailCache = mutableStateMapOf<String, Anime>()
    private val animeCache = mutableMapOf<String, Anime>()

    private val episodeCache = mutableStateMapOf<String, List<Episode>>()
    private val dubEpisodeCache = mutableStateMapOf<String, List<Episode>>()
    private val episodeLoading = mutableStateMapOf<String, Boolean>()
    
    private val isOfflineOnlyCache = mutableStateMapOf<String, Boolean>()

    var library by mutableStateOf<Set<String>>(emptySet())
        private set

    var watchHistory by mutableStateOf<List<WatchProgress>>(emptyList())
        private set

    // PlayerScreen must wait until persisted watch history has been loaded.
    // Otherwise the resume effect can run once with an empty history and never
    // seek to the saved position when the history arrives afterward.
    var watchHistoryLoaded by mutableStateOf(false)
        private set

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        startProgressTracking()
    }

    private fun startProgressTracking() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val progressMap = mutableMapOf<String, Float>()
                var hasActiveDownloads = false

                // New downloads are owned by the mpv-native downloader.
                MpvOfflineStore.listStatuses(appContext).forEach { status ->
                    if (status.state == "downloading" || status.state == "queued") {
                        hasActiveDownloads = true
                        progressMap[status.id] = status.progress
                    }
                }

                _downloadProgressMap.value = progressMap
                _downloadedIds.value = fetchDownloadedIdsInternal(appContext)
                delay(if (hasActiveDownloads) 300L else 1000L)
            }
        }
    }

    private fun fetchDownloadedIdsInternal(context: Context): Set<String> {
        val ids = mutableSetOf<String>()

        // The native mpv downloader owns the actual offline files. Do not rely
        // solely on metadata.json: older/in-progress migrations can leave a
        // perfectly valid MP4 behind even when the status record is stale.
        MpvOfflineStore.listStatuses(context)
            .filter { it.state == "completed" }
            .forEach { status ->
                if (MpvOfflineStore.isCompleted(context, status.id.substringBefore("::"), status.episodeId)) {
                    ids += status.id
                }
            }

        // Also discover completed files directly. This makes the UI recover
        // immediately after process death or a status-write race.
        MpvOfflineStore.root(context).listFiles()?.forEach { dir ->
            val meta = File(dir, "metadata.json")
            if (!meta.isFile) return@forEach
            runCatching {
                val obj = org.json.JSONObject(meta.readText())
                val id = obj.optString("id")
                val episodeId = obj.optString("episodeId")
                val animeId = id.substringBefore("::").takeIf { it.isNotBlank() }
                    ?: return@runCatching
                if (id.isNotBlank() && episodeId.isNotBlank() &&
                    MpvOfflineStore.isCompleted(context, animeId, episodeId)
                ) {
                    ids += id
                }
            }
        }

        return ids
    }

    fun monitorNetwork(context: Context) {
        if (networkCallback != null) return
        
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(activeNetwork)
        val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        _isOffline.value = !isConnected

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isOffline.value = false
            }

            override fun onLost(network: Network) {
                _isOffline.value = true
            }
        }
        
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)

        refreshDownloads()
    }

    fun refreshDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadedIds.value = fetchDownloadedIdsInternal(appContext)
        }
    }

    fun isEpisodeDownloaded(animeId: String, episodeNumber: Int): Boolean {
        return _downloadedIds.value.contains("${animeId}_${episodeNumber}") ||
            MpvOfflineStore.listStatuses(appContext).any { it.id == "${animeId}::${animeId}_ep_${episodeNumber}" && it.state == "completed" }
    }

    fun isEpisodeDownloaded(animeId: String, episode: Episode): Boolean {
        val local = MpvOfflineStore.isCompleted(appContext, animeId, episode.id)
        return local ||
            _downloadedIds.value.contains(offlineDownloadId(animeId, episode)) ||
            _downloadedIds.value.contains(episode.id) ||
            (episode.displayNumber == episode.number.toString() &&
                _downloadedIds.value.contains("${animeId}_${episode.number}"))
    }

    fun searchReAnime(query: String) {
        if (playerSettings.videoSourcePreference != "reanime") return
        val q = query.trim()
        reAnimeSearchJob?.cancel()
        if (q.isEmpty()) {
            reAnimeSearchResults = emptyList()
            reAnimeSearchLoading = false
            return
        }
        reAnimeSearchJob = viewModelScope.launch {
            delay(250L)
            reAnimeSearchLoading = true
            try {
                val results = withContext(Dispatchers.IO) { repository.searchAnime(q, "reanime") }
                reAnimeSearchResults = results
            } catch (e: Exception) {
                Log.e("ReAnimeSearch", "SEARCH_FAILED query=$q", e)
                reAnimeSearchResults = emptyList()
            } finally {
                reAnimeSearchLoading = false
            }
        }
    }

    fun loadAnime(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val lib = OfflineStore.getLibrary(context)
            val history = OfflineStore.getWatchHistory(context)
            val settings = OfflineStore.getPlayerSettings(context)
            val source = settings.videoSourcePreference
            // Home is intentionally NEVER served from the catalog cache.
            // Every home entry/re-entry asks the source for a fresh preview.
            withContext(Dispatchers.Main) {
                library = lib
                watchHistory = history
                watchHistoryLoaded = true
                playerSettings = settings
                homeAnime = emptyList()
                loading = true
                error = null
            }

            if (_isOffline.value) {
                withContext(Dispatchers.Main) { loading = false }
                return@launch
            }

            try {
                val firstPageList = repository.getHomeAnimeList(source)
                if (firstPageList.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        homeAnime = firstPageList.take(10)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (homeAnime.isEmpty()) error = e.message ?: "목록을 불러오지 못했습니다."
                }
            } finally {
                withContext(Dispatchers.Main) { loading = false }
            }

            // IMPORTANT: home preview is completely independent from the full catalog cache.
            // Do not load, refresh, or write the catalog here. All/Search loads it separately.
        }
    }

    fun updatePlayerSettings(context: Context, newSettings: PlayerSettings) {
        val sourceChanged = playerSettings.videoSourcePreference != newSettings.videoSourcePreference
        playerSettings = newSettings
        if (sourceChanged) sourceRevision++
        viewModelScope.launch(Dispatchers.IO) {
            OfflineStore.savePlayerSettings(context, newSettings)
            if (sourceChanged && !_isOffline.value) {
                withContext(Dispatchers.Main) {
                    homeAnime = emptyList()
                    allAnime = emptyList()
                    detailCache.clear()
                    animeCache.clear()
                    episodeCache.clear()
                    dubEpisodeCache.clear()
                    episodeLoading.clear()
                    isOfflineOnlyCache.clear()
                    isAllAnimeFullyLoaded = false
                    allAnimeLoadJob?.cancel()
                    allAnimeLoadJob = null
                    isAllAnimeLoading = false
                    loading = true
                    error = null
                }
                try {
                    val first = repository.getHomeAnimeList(newSettings.videoSourcePreference)
                    withContext(Dispatchers.Main) {
                        homeAnime = first.take(10)
                        loading = false
                    }
                    // Home preview is memory-only. Do not touch the persisted full-catalog cache.
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        loading = false
                        error = e.message ?: "영상 소스 목록을 불러오지 못했습니다."
                    }
                }
            }
        }
    }

    /**
     * Loads ONLY the persisted full catalog for normal All/Search entry.
     * The home preview is intentionally not involved in this function.
     * Network is used only when no persisted catalog exists, or on explicit refresh.
     */
    fun loadAllAnime(
        source: String = playerSettings.videoSourcePreference,
        forceRefresh: Boolean = false
    ) {
        // Loading state is in-memory, while the TTL is persisted.  Do not let
        // isAllAnimeFullyLoaded bypass the persisted TTL after 12 hours.
        if (!forceRefresh && isAllAnimeLoading) return

        if (forceRefresh) {
            catalogLoadGeneration++
            allAnimeLoadJob?.cancel()
            allAnimeLoadJob = null
            isAllAnimeLoading = false
            isAllAnimeFullyLoaded = false
        }

        val generation = catalogLoadGeneration
        isAllAnimeLoading = true

        val job = viewModelScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) {
                    OfflineStore.getSavedAnimeList(appContext, source)
                }
                val fresh = cached.isNotEmpty() && withContext(Dispatchers.IO) {
                    OfflineStore.isAnimeListCacheFresh(appContext, source)
                }

                // A persisted timestamp is the source of truth for catalog TTL.
                // The home preview is never timestamped, so it cannot accidentally
                // become a TTL hit.
                val cacheLooksComplete = cached.isNotEmpty()

                if (cached.isNotEmpty() && (allAnime.isEmpty() || forceRefresh)) {
                    withContext(Dispatchers.Main) {
                        if (generation == catalogLoadGeneration) {
                            allAnime = cached
                            cached.forEach { animeCache[it.id] = it }
                        }
                    }
                }

                // All/Search must be cache-first. Once a persisted catalog exists,
                // do not silently hit the network just because the TTL expired.
                // Network refresh is reserved for the explicit refresh action
                // (forceRefresh=true) or the very first load when no cache exists.
                if (!forceRefresh && cacheLooksComplete) {
                    Log.d(
                        "AnimeCatalog",
                        "CACHE_HIT source=$source size=${cached.size} fresh=$fresh"
                    )
                    if (generation == catalogLoadGeneration) {
                        isAllAnimeFullyLoaded = true
                    }
                    return@launch
                }

                Log.d(
                    "AnimeCatalog",
                    "NETWORK_REQUEST source=$source force=$forceRefresh cached=${cached.size} fresh=$fresh complete=$cacheLooksComplete"
                )

                var latestList: List<Anime> = emptyList()
                repository.getAllAnimeListFlow(source).collect { list ->
                    latestList = list
                    if (generation == catalogLoadGeneration) {
                        withContext(Dispatchers.Main) {
                            if (generation == catalogLoadGeneration) {
                                allAnime = list
                                list.forEach { animeCache[it.id] = it }
                            }
                        }
                    }
                }

                if (generation != catalogLoadGeneration) return@launch

                // getAllAnimeListFlow() is the full-catalog loader and already
                // paginates until the source stops returning pages. Therefore a
                // non-empty completed result is a valid TTL snapshot; do not use
                // an arbitrary item-count threshold that can prevent the cache
                // timestamp from ever being written (especially for Re:Anime).
                if (latestList.isNotEmpty()) {
                    OfflineStore.saveAnimeList(
                        appContext,
                        latestList,
                        source,
                        markFresh = true
                    )
                    Log.d(
                        "AnimeCatalog",
                        "NETWORK_SUCCESS source=$source size=${latestList.size}"
                    )
                    isAllAnimeFullyLoaded = true
                } else {
                    Log.w(
                        "AnimeCatalog",
                        "NETWORK_INCOMPLETE source=$source size=${latestList.size}; keeping cache if available"
                    )
                    // Do not mark an incomplete preview as a fresh full catalog.
                    if (cached.isNotEmpty() && cached.size > latestList.size) {
                        withContext(Dispatchers.Main) {
                            if (generation == catalogLoadGeneration) allAnime = cached
                        }
                    }
                    isAllAnimeFullyLoaded = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == catalogLoadGeneration) {
                    Log.e("AnimeCatalog", "NETWORK_FAILED source=$source", e)
                    isAllAnimeFullyLoaded = allAnime.isNotEmpty()
                }
            } finally {
                if (generation == catalogLoadGeneration) {
                    isAllAnimeLoading = false
                    allAnimeLoadJob = null
                }
            }
        }
        allAnimeLoadJob = job
    }

    /** Pull-to-refresh. It bypasses the cache TTL but keeps the old list visible. */
    fun refreshAnime(@Suppress("UNUSED_PARAMETER") context: Context? = null) {
        if (_isOffline.value) return
        val source = playerSettings.videoSourcePreference
        Log.d("AnimeCatalog", "MANUAL_REFRESH source=$source")
        loadAllAnime(source = source, forceRefresh = true)
    }

    fun loadLinkkfEpisodeServers(anime: Anime, force: Boolean = false) {
        if (playerSettings.videoSourcePreference != "linkkf") return
        if (!force && linkkfDetailAnimeId == anime.id && linkkfEpisodeServers.isNotEmpty()) return
        viewModelScope.launch {
            try {
                val servers = withContext(Dispatchers.IO) { repository.getLinkkfEpisodeServers(anime.id) }
                if (servers.isNotEmpty()) {
                    linkkfEpisodeServers = servers
                    if (linkkfSelectedServerId <= 0 || servers.none { it.id == linkkfSelectedServerId }) {
                        linkkfSelectedServerId = servers.first().id
                    }
                    episodeCache[anime.id] = servers.firstOrNull { it.id == linkkfSelectedServerId }?.episodes
                        ?: servers.first().episodes
                    isOfflineOnlyCache[anime.id] = false
                }
            } catch (e: Exception) {
                Log.w("LinkkfDetail", "EPISODE_SERVERS_FAILED id=${anime.id}", e)
            }
        }
    }

    fun selectLinkkfEpisodeServer(anime: Anime, serverId: Int) {
        val server = linkkfEpisodeServers.firstOrNull { it.id == serverId } ?: return
        linkkfSelectedServerId = serverId
        episodeCache[anime.id] = server.episodes
        isOfflineOnlyCache[anime.id] = false
    }

    fun getLinkkfEpisodeServers(animeId: String): List<LinkkfApiClient.EpisodeServer> =
        if (linkkfDetailAnimeId == animeId) linkkfEpisodeServers else emptyList()

    fun loadLinkkfDetailExtras(anime: Anime, force: Boolean = false) {
        if (playerSettings.videoSourcePreference != "linkkf") return
        if (!force && linkkfDetailAnimeId == anime.id) return
        linkkfDetailExtrasJob?.cancel()
        linkkfDetailExtrasLoading = true
        linkkfDetailAnimeId = anime.id
        linkkfViewStats = null
        linkkfRelatedSeries = emptyList()
        linkkfDetailExtrasJob = viewModelScope.launch {
            try {
                val stats = withContext(Dispatchers.IO) { repository.getLinkkfViewStats(anime.id) }
                val related = withContext(Dispatchers.IO) { repository.getLinkkfRelatedSeries(anime) }
                linkkfViewStats = stats
                linkkfRelatedSeries = related
                // Match the web page: record the view after the page has been visible
                // for a short period, then refresh the counters.
                delay(9000L)
                if (isActive) {
                    withContext(Dispatchers.IO) { repository.recordLinkkfView(anime.id) }
                    linkkfViewStats = withContext(Dispatchers.IO) { repository.getLinkkfViewStats(anime.id) }
                }
            } catch (e: Exception) {
                Log.w("LinkkfDetail", "DETAIL_EXTRAS_FAILED id=${anime.id}", e)
            } finally {
                linkkfDetailExtrasLoading = false
            }
        }
    }

    fun cacheAnime(anime: Anime) {
        animeCache[anime.id] = anime
        detailCache[anime.id] = anime
    }

    fun loadAnimeDetail(
        target: Anime,
        force: Boolean = false,
        onLoaded: ((Anime) -> Unit)? = null
    ) {
        if (!force && detailCache.containsKey(target.id)) {
            detailCache[target.id]?.let { onLoaded?.invoke(it) }
            return
        }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.getAnimeDetail(target, playerSettings.videoSourcePreference)
                }
                detailCache[result.id] = result
                animeCache[result.id] = result
                homeAnime = homeAnime.map { if (it.id == result.id) result else it }
                allAnime = allAnime.map { if (it.id == result.id) result else it }
                onLoaded?.invoke(result)
            } catch (e: Exception) {
                Log.w("AnimeDetail", "DETAIL_REFRESH_FAILED id=${target.id}", e)
            }
        }
    }

    suspend fun getDownloadedAnimeList(context: Context): List<Anime> = withContext(Dispatchers.IO) {
        val downloadedAnimeIds = _downloadedIds.value.mapNotNull { id ->
            when {
                "::" in id -> id.substringBefore("::").takeIf { it.isNotBlank() }
                "_dub_ep_" in id -> id.substringBefore("_dub_ep_").takeIf { it.isNotBlank() }
                "_ep_" in id -> id.substringBefore("_ep_").takeIf { it.isNotBlank() }
                else -> id.substringBeforeLast("_").takeIf { it.isNotBlank() }
            }
        }.toSet()

        val allAvailableAnime = (homeAnime + allAnime + detailCache.values + animeCache.values).distinctBy { it.id }
        val resultMap = allAvailableAnime.filter { it.id in downloadedAnimeIds }.associateBy { it.id }.toMutableMap()

        for (animeId in downloadedAnimeIds) {
            if (!resultMap.containsKey(animeId)) {
                val storedAnime = OfflineStore.getAnime(context, animeId)
                if (storedAnime != null) {
                    resultMap[animeId] = storedAnime
                } else {
                    resultMap[animeId] = Anime(
                        id = animeId,
                        title = "오프라인 저장 항목 ($animeId)",
                        poster = "",
                        description = "오프라인 상태에서 다운로드된 콘텐츠입니다.",
                        genres = listOf("오프라인")
                    )
                }
            }
        }

        resultMap.values.toList()
    }

    fun getAnime(context: Context, id: String): Anime? {
        return detailCache[id] 
            ?: animeCache[id] 
            ?: homeAnime.firstOrNull { it.id == id }
            ?: allAnime.firstOrNull { it.id == id }
    }

    fun episodes(anime: Anime): List<Episode> {
        return episodeCache[anime.id] ?: emptyList()
    }

    fun dubEpisodes(anime: Anime): List<Episode> {
        return dubEpisodeCache[anime.id] ?: emptyList()
    }

    fun isEpisodesLoading(anime: Anime): Boolean {
        return episodeLoading[anime.id] == true
    }

    fun loadEpisodes(
        context: Context,
        anime: Anime,
        force: Boolean = false
    ) {
        val currentList = episodeCache[anime.id]
        val isOfflineOnly = isOfflineOnlyCache[anime.id] ?: false

        if (_isOffline.value) {
            loadOfflineEpisodes(context, anime)
            return
        }

        if (!force && !currentList.isNullOrEmpty() && !isOfflineOnly) {
            return
        }

        episodeLoading[anime.id] = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val targetAnime = detailCache[anime.id] ?: repository.getAnimeDetail(anime, playerSettings.videoSourcePreference).also {
                    withContext(Dispatchers.Main) {
                        detailCache[it.id] = it
                        animeCache[it.id] = it
                    }
                }

                val result = if (playerSettings.videoSourcePreference == "linkkf" && linkkfEpisodeServers.isNotEmpty()) {
                    linkkfEpisodeServers.firstOrNull { it.id == linkkfSelectedServerId }?.episodes
                        ?: linkkfEpisodeServers.first().episodes
                } else {
                    repository.getEpisodes(targetAnime, playerSettings.videoSourcePreference)
                }
                
                withContext(Dispatchers.Main) {
                    if (result.isNotEmpty()) {
                        episodeCache[anime.id] = result
                        isOfflineOnlyCache[anime.id] = false
                    } else {
                        loadOfflineEpisodes(context, anime)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadOfflineEpisodes(context, anime)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    episodeLoading[anime.id] = false
                }
            }
        }
    }

    fun loadOfflineEpisodes(context: Context, anime: Anime) {
        viewModelScope.launch(Dispatchers.IO) {
            val storedEpisodes = OfflineStore.getEpisodesForAnime(context, anime.id)
            if (storedEpisodes.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    episodeCache[anime.id] = storedEpisodes
                    isOfflineOnlyCache[anime.id] = true
                }
                return@launch
            }

            val downloadedEpisodeIds = _downloadedIds.value
                .filter { it.startsWith("${anime.id}::") || it.startsWith("${anime.id}_ep_") || it.startsWith("${anime.id}_dub_ep_") }
                .sortedWith(compareByDescending<String> { Regex("(\\d+)").find(it.substringAfterLast("_ep_"))?.value?.toIntOrNull() ?: 0 }.thenByDescending { it })

            val stored = OfflineStore.getEpisodesForAnime(context, anime.id)
            val offlineList = downloadedEpisodeIds.mapNotNull { rawId ->
                val id = rawId.substringAfter("::", rawId)
                stored.firstOrNull { it.id == id }
                    ?: Regex("^(?:${Regex.escape(anime.id)}_(?:dub_)?ep_)(.+)$").find(id)?.let { m ->
                        val label = m.groupValues[1]
                        val num = Regex("^\\d+").find(label)?.value?.toIntOrNull() ?: return@mapNotNull null
                        Episode(id = id, number = num, title = "${label}화", displayNumber = label)
                    }
            }

            withContext(Dispatchers.Main) {
                episodeCache[anime.id] = offlineList
                isOfflineOnlyCache[anime.id] = true
            }
        }
    }

    fun loadDubEpisodes(anime: Anime) {
        if (dubEpisodeCache.containsKey(anime.id)) return

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.getDubEpisodes(anime, playerSettings.videoSourcePreference)
                }
                dubEpisodeCache[anime.id] = result
            } catch (_: Exception) {
                dubEpisodeCache[anime.id] = emptyList()
            }
        }
    }

    fun deleteDownload(context: Context, anime: Anime, episodeNumber: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val ep = OfflineStore.getEpisode(context, anime.id, episodeNumber)
            deleteDownloadInternal(context, anime, "${anime.id}_${episodeNumber}", ep)
        }
    }

    fun deleteDownload(context: Context, anime: Anime, episode: Episode) {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = OfflineStore.getEpisode(context, anime.id, episode)
            deleteDownloadInternal(context, anime, offlineDownloadId(anime.id, episode), stored)
        }
    }

    private suspend fun deleteDownloadInternal(
        context: Context,
        anime: Anime,
        downloadId: String,
        ep: Episode?
    ) {
        ep?.let { episode ->
            listOf("linkkf", "kairan", "csora").forEach { source ->
                try { SubtitleStore.delete(context, anime.id, episode.displayNumber, episode.number, source) } catch (_: Exception) { }
            }
            episode.vttUrl?.let { path -> if (path.startsWith("/")) File(path).takeIf(File::isFile)?.delete() }
        }

        val removeIntent = android.content.Intent(context.applicationContext, LilacDownloadService::class.java).apply {
            action = LilacDownloadService.ACTION_REMOVE
            putExtra(LilacDownloadService.EXTRA_ANIME_ID, anime.id)
            putExtra(LilacDownloadService.EXTRA_EPISODE_ID, ep?.id ?: downloadId.substringAfter("::"))
        }
        try {
            context.applicationContext.startService(removeIntent)
        } catch (_: Exception) {
            // The mpv download service owns the local file cleanup.
        }

        if (ep != null) {
            OfflineStore.removeEpisode(context, anime.id, ep)
        } else if (downloadId.startsWith("${anime.id}_")) {
            val legacyNumber = downloadId.substringAfterLast('_').toIntOrNull()
            if (legacyNumber != null) {
                OfflineStore.removeEpisode(context, anime.id, legacyNumber)
            }
        }

        refreshDownloads()
        if (_isOffline.value) {
            loadOfflineEpisodes(context, anime)
        }
    }

    fun toggleLibrary(context: Context, animeId: String) {
        library = if (animeId in library) library - animeId else library + animeId
        val updated = library
        viewModelScope.launch(Dispatchers.IO) {
            OfflineStore.saveLibrary(context, updated)
        }
    }

    fun isInLibrary(animeId: String): Boolean {
        return animeId in library
    }

    fun updateProgress(context: Context, animeId: String, episodeNumber: Int, progress: Float, episodeKey: String = episodeNumber.toString()) {
        val filtered = watchHistory.filterNot { it.animeId == animeId && it.episodeKey.equals(episodeKey, ignoreCase = true) }
        val updatedItem = WatchProgress(animeId = animeId, episodeNumber = episodeNumber, progress = progress, episodeKey = episodeKey)
        val newList = listOf(updatedItem) + filtered
        watchHistory = newList
        viewModelScope.launch(Dispatchers.IO) { OfflineStore.saveWatchHistory(context, newList) }
    }

    fun getLatestProgress(animeId: String): WatchProgress? = watchHistory.firstOrNull { it.animeId == animeId }

    fun getProgress(animeId: String, episodeKey: String, episodeNumber: Int): WatchProgress? =
        watchHistory.firstOrNull {
            it.animeId == animeId &&
                it.episodeNumber == episodeNumber &&
                (it.episodeKey.equals(episodeKey, ignoreCase = true) ||
                    (it.episodeKey == it.episodeNumber.toString() && episodeKey == episodeNumber.toString()))
        }

    fun getProgress(animeId: String, episodeNumber: Int): WatchProgress? =
        watchHistory.firstOrNull { it.animeId == animeId && it.episodeNumber == episodeNumber && it.episodeKey == episodeNumber.toString() }

    fun deleteWatchHistory(context: Context, animeIds: Set<String>) {
        if (animeIds.isEmpty()) return
        val newList = watchHistory.filterNot { it.animeId in animeIds }
        watchHistory = newList
        viewModelScope.launch(Dispatchers.IO) {
            OfflineStore.saveWatchHistory(context, newList)
        }
    }

    fun clearWatchHistory(context: Context) {
        watchHistory = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            OfflineStore.saveWatchHistory(context, emptyList())
        }
    }
    // 다운로드 취소 및 상태/파일 정리
    fun cancelDownload(context: Context, animeId: String, episodeNumber: Int) {
        val anime = Anime(id = animeId, title = "", poster = "", backdrop = "", description = "")
        val episode = Episode(
            id = "${animeId}_ep_${episodeNumber}",
            number = episodeNumber,
            title = "${episodeNumber}화"
        )
        cancelDownload(context, anime, episode)
    }

    fun cancelDownload(context: Context, anime: Anime, episode: Episode) {
        val downloadKey = offlineDownloadId(anime.id, episode)


        // ViewModel 진행률 맵에서 제거
        _downloadProgressMap.update { currentMap -> currentMap - downloadKey }

        // 오프라인 메타데이터/자막 파일 정리
        deleteDownload(context, anime, episode)
    }
}

// ==========================================
// 1. DataStore 싱글톤 선언 및 테마 저장 키
// ==========================================
val Context.dataStore by preferencesDataStore(name = "theme_settings")
val THEME_KEY = stringPreferencesKey("theme_mode")

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

// 색상 값 정의
val Lilac = Color(0xFFC8A2C8)
val LilacDark = Color(0xFF9A7B9A)
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val LightBackground = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFF5F5F5)

