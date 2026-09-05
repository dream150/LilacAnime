package com.lilac.anime

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.annotation.OptIn
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
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
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

    var isAllAnimeLoading by mutableStateOf(false)
        private set

    private var isAllAnimeFullyLoaded = false
    private var allAnimeLoadJob: Job? = null
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

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        startProgressTracking()
    }

    @OptIn(UnstableApi::class)
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

                // Keep the old Media3 index readable so existing offline downloads remain
                // visible and deletable after upgrading. It is not used for new downloads.
                try {
                    val cursor = LilacApplication.downloadManager.downloadIndex.getDownloads()
                    while (cursor.moveToNext()) {
                        val download = cursor.download
                        if (download.state == Download.STATE_DOWNLOADING || download.state == Download.STATE_QUEUED) {
                            hasActiveDownloads = true
                            val percent = if (download.percentDownloaded != C.PERCENTAGE_UNSET.toFloat())
                                (download.percentDownloaded / 100f).coerceAtLeast(0f) else 0f
                            progressMap.putIfAbsent(download.request.id, percent)
                        }
                    }
                    cursor.close()
                } catch (_: Exception) { }

                _downloadProgressMap.value = progressMap
                _downloadedIds.value = fetchDownloadedIdsInternal(appContext)
                delay(if (hasActiveDownloads) 300L else 1000L)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun fetchDownloadedIdsInternal(context: Context): Set<String> {
        val ids = mutableSetOf<String>()
        ids += MpvOfflineStore.listStatuses(context)
            .filter { it.state == "completed" && !it.videoPath.isNullOrBlank() }
            .map { it.id }

        try {
            val cursor = LilacApplication.downloadManager.downloadIndex.getDownloads()
            while (cursor.moveToNext()) {
                val download = cursor.download
                if (download.state == Download.STATE_COMPLETED) ids += download.request.id
            }
            cursor.close()
        } catch (_: Exception) { }
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

    fun loadAnime(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val lib = OfflineStore.getLibrary(context)
            val history = OfflineStore.getWatchHistory(context)
            val settings = OfflineStore.getPlayerSettings(context)
            val cachedList = OfflineStore.getSavedAnimeList(context, settings.videoSourcePreference)

            withContext(Dispatchers.Main) {
                library = lib
                watchHistory = history
                playerSettings = settings
                if (cachedList.isNotEmpty() && homeAnime.isEmpty()) {
                    homeAnime = cachedList.take(10)
                    allAnime = cachedList
                    cachedList.forEach { animeCache[it.id] = it }
                    loading = false
                } else if (homeAnime.isEmpty()) {
                    loading = true
                }
                error = null
            }

            if (!_isOffline.value) {
                try {
                    val firstPageList = repository.getHomeAnimeList(playerSettings.videoSourcePreference)
                    if (firstPageList.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            homeAnime = firstPageList.take(10)
                            if (allAnime.isEmpty()) allAnime = firstPageList
                            firstPageList.forEach { animeCache[it.id] = it }
                        }
                        OfflineStore.saveAnimeList(context, firstPageList, playerSettings.videoSourcePreference)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (homeAnime.isEmpty()) {
                            error = e.message ?: "목록을 불러오지 못했습니다."
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        loading = false
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    loading = false
                }
            }

            // Load the complete catalog in the background so SearchScreen does
            // not depend on the user visiting the All Anime tab first.
            if (!_isOffline.value) {
                loadAllAnime()
            }
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
                        allAnime = first
                        first.forEach { animeCache[it.id] = it }
                        loading = false
                    }
                    OfflineStore.saveAnimeList(context, first, newSettings.videoSourcePreference)
                    loadAllAnime(newSettings.videoSourcePreference)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        loading = false
                        error = e.message ?: "영상 소스 목록을 불러오지 못했습니다."
                    }
                }
            }
        }
    }

    fun loadAllAnime(source: String = playerSettings.videoSourcePreference) {
        if (isAllAnimeLoading || isAllAnimeFullyLoaded) return
        isAllAnimeLoading = true
        allAnimeLoadJob?.cancel()
        allAnimeLoadJob = viewModelScope.launch {
            try {
                repository.getAllAnimeListFlow(source).collect { list ->
                    allAnime = list
                    list.forEach { animeCache[it.id] = it }
                }
                isAllAnimeFullyLoaded = true
            } catch (_: Exception) {
            } finally {
                isAllAnimeLoading = false
                allAnimeLoadJob = null
            }
        }
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

                val result = repository.getEpisodes(targetAnime, playerSettings.videoSourcePreference)
                
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

    @OptIn(UnstableApi::class)
    fun deleteDownload(context: Context, anime: Anime, episodeNumber: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val ep = OfflineStore.getEpisode(context, anime.id, episodeNumber)
            deleteDownloadInternal(context, anime, "${anime.id}_${episodeNumber}", ep)
        }
    }

    @OptIn(UnstableApi::class)
    fun deleteDownload(context: Context, anime: Anime, episode: Episode) {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = OfflineStore.getEpisode(context, anime.id, episode)
            deleteDownloadInternal(context, anime, offlineDownloadId(anime.id, episode), stored)
            DownloadService.sendRemoveDownload(
                context, LegacyLilacDownloadService::class.java, episode.id, false
            )
            if (episode.displayNumber == episode.number.toString()) {
                // Remove the old download ID too when upgrading from the previous version.
                DownloadService.sendRemoveDownload(
                    context, LegacyLilacDownloadService::class.java,
                    "${anime.id}_${episode.number}", false
                )
            }
        }
    }

    @OptIn(UnstableApi::class)
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
            // The removal request is best-effort; Media3 cleanup below remains authoritative.
        }

        // Legacy Media3 download cleanup. New downloads never enter this index.
        DownloadService.sendRemoveDownload(
            context, LegacyLilacDownloadService::class.java, downloadId, false
        )

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

        // 1. 진행 중인 Media3 다운로드 취소
        DownloadService.sendRemoveDownload(
            context, LegacyLilacDownloadService::class.java, downloadKey, false
        )
        DownloadService.sendRemoveDownload(
            context, LegacyLilacDownloadService::class.java, episode.id, false
        )
        if (episode.displayNumber == episode.number.toString()) {
            // 이전 버전의 숫자형 다운로드도 함께 정리한다.
            DownloadService.sendRemoveDownload(
                context, LegacyLilacDownloadService::class.java,
                "${anime.id}_${episode.number}", false
            )
        }

        // 2. ViewModel 진행률 맵에서 제거
        _downloadProgressMap.update { currentMap -> currentMap - downloadKey }

        // 3. 오프라인 메타데이터/자막 파일 정리
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

