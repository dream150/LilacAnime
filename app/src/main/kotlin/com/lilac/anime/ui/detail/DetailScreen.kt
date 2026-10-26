package com.lilac.anime.ui.detail

import com.lilac.anime.*
import com.lilac.anime.core.model.*
import com.lilac.anime.data.offline.*
import com.lilac.anime.data.subtitle.*
import com.lilac.anime.data.subtitle.translation.providers.LocalAiTranslationRuntime
import com.lilac.anime.network.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.navigation.*
import com.lilac.anime.viewmodel.*

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.ui.AnimeImage
import com.lilac.anime.data.*
import com.lilac.anime.data.subtitle.downloadSubtitleFile
import com.lilac.anime.data.subtitle.AnissiaSubtitleService
import com.lilac.anime.data.subtitle.SubtitleTitleResolver
import com.lilac.anime.data.subtitle.JimakuSubtitleService
import com.lilac.anime.network.LinkkfRequestContextStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
/** Finds an already-downloaded AniSIA subtitle for the requested episode. */
private suspend fun findLocalAnissiaSubtitle(
    context: Context,
    title: String,
    episodeNumber: Int,
    storedPath: String?
): String? = withContext(Dispatchers.IO) {
    fun isSubtitle(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val file = File(path)
        return file.isFile && file.extension.lowercase(java.util.Locale.ROOT) in setOf("ass", "ssa", "srt", "vtt", "smi")
    }

    if (isSubtitle(storedPath)) return@withContext storedPath

    val titleKey = title.trim()

    SubtitleStore.get(
        context = context,
        animeId = titleKey,
        episodeKey = episodeNumber.toString(),
        episodeNumber = episodeNumber,
        source = "anissia"
    )?.takeIf(::isSubtitle)?.let { return@withContext it }

    val titleRoot = context.filesDir.resolve("anissia_subtitles").resolve(titleKey)
    if (!titleRoot.isDirectory) return@withContext null

    titleRoot.walkTopDown()
        .filter { it.isFile && it.extension.lowercase(java.util.Locale.ROOT) in setOf("ass", "ssa", "srt", "vtt", "smi") }
        .filter { SubtitleStore.subtitleMatchesEpisode(it.absolutePath, episodeNumber) }
        .maxByOrNull { it.lastModified() }
        ?.absolutePath
}

/**
 * Copies every subtitle asset already discovered by each provider into the
 * anime-id keyed offline store used by the player. Provider stores use their
 * own cache keys (Linkkf=animeId, Kairan/Csora=normalized title), so simply
 * saving the selected path is not enough to make every discovered file
 * available offline.
 */
private data class OfflineSubtitleAssets(
    val jimakuPaths: List<String>,
    val reAnimePaths: List<String>
)

private suspend fun downloadAllOfflineSubtitleSources(
    context: Context,
    anime: Anime,
    episode: Episode,
    linkkfVttUrl: String?,
    linkkfReferer: String?
): OfflineSubtitleAssets = withContext(Dispatchers.IO) {
    val jimakuPaths = mutableListOf<String>()
    val reAnimePaths = mutableListOf<String>()

    runCatching {
        val options = JimakuSubtitleService.listEpisodeSubtitles(context, anime, episode.number, episode.displayNumber)
        for (option in options) {
            JimakuSubtitleService.downloadSelectedSubtitle(
                context, anime, episode.number, episode.displayNumber, option
            )?.let { jimakuPaths += it }
        }
        android.util.Log.d("OfflineDownload", "JIMAKU_ALL episode=${episode.displayNumber} count=${jimakuPaths.size}")
    }.onFailure { android.util.Log.w("OfflineDownload", "JIMAKU_ALL_FAILED episode=${episode.displayNumber}", it) }

    runCatching {
        val resolved = com.lilac.anime.network.ReAnimePlayerResolver.resolve(context, episode, anime.anilistId)
        resolved.subtitleUrl?.takeIf { it.isNotBlank() }?.let { url ->
            downloadSubtitleFile(
                context = context, animeId = anime.id, episodeNumber = episode.number,
                episodeKey = episode.displayNumber, vttUrl = url,
                referer = resolved.subtitleReferer ?: resolved.referer, source = "reanime"
            )?.let { reAnimePaths += it }
        }
    }.onFailure { android.util.Log.w("OfflineDownload", "REANIME_SUBTITLE_FAILED episode=${episode.displayNumber}", it) }

    OfflineSubtitleAssets(jimakuPaths.distinct(), reAnimePaths.distinct())
}

private suspend fun persistOfflineSubtitleAssets(
    context: Context,
    animeId: String,
    title: String,
    episodeKey: String,
    episodeNumber: Int,
    linkkfPath: String?,
    anissiaPath: String?,
    jimakuPaths: Collection<String> = emptyList(),
    reAnimePaths: Collection<String> = emptyList()
) = withContext(Dispatchers.IO) {
    /**
     * Provider services use a title-based cache directory for Kairan/Csora,
     * while the player uses the real anime id.  Bridge both stores here.
     *
     * Important: do not delete or replace candidate subtitle files.  Every
     * subtitle asset found for this episode is registered in SubtitleStore so
     * the offline player can present all available tracks.
     */
    val titleKey = title.trim()
    fun safeEpisodeKey(value: String): String = value.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")

    val providerRoots = mapOf(
        "linkkf" to context.filesDir.resolve("linkkf_subtitles"),
        "anissia" to context.filesDir.resolve("anissia_subtitles").resolve(titleKey).resolve(safeEpisodeKey(episodeKey)),
        "jimaku" to context.filesDir.resolve("jimaku_subtitles"),
        "reanime" to context.filesDir.resolve("reanime_subtitles")
    )
    val primaries = mapOf(
        "linkkf" to linkkfPath,
        "anissia" to anissiaPath
    )

    fun isSubtitleFile(file: File): Boolean {
        if (!file.isFile) return false
        return file.extension.lowercase(java.util.Locale.ROOT) in
            setOf("vtt", "srt", "ass", "ssa")
    }

    fun collectFromEpisodeDir(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter(::isSubtitleFile)
            .map { it.absolutePath }
            .filter { SubtitleStore.subtitleMatchesEpisode(it, episodeKey, episodeNumber) }
            .distinct()
            .toList()
    }

    for ((source, root) in providerRoots) {
        try {
            val discovered = linkedSetOf<String>()
            if (source == "jimaku") discovered += jimakuPaths.filter { File(it).isFile }
            if (source == "reanime") discovered += reAnimePaths.filter { File(it).isFile }

            // First include everything already registered in the provider's
            // own SubtitleStore key. This also preserves older installations.
            val providerStoreKey = if (source == "linkkf" || source == "anissia") animeId else titleKey
            SubtitleStore.list(
                context = context,
                animeId = providerStoreKey,
                episodeKey = episodeKey,
                episodeNumber = episodeNumber
            )
                .filter { it.episodeMatch && File(it.path).isFile }
                .forEach { discovered += File(it.path).absolutePath }

            // Then scan the actual provider cache directory. This is the
            // important part: candidates that were downloaded but were not
            // selected as the primary subtitle are still retained offline.
            discovered += collectFromEpisodeDir(root)

            primaries[source]?.let { primary ->
                if (File(primary).isFile && SubtitleStore.subtitleMatchesEpisode(primary, episodeKey, episodeNumber)) {
                    discovered.add(File(primary).absolutePath)
                }
            }

            if (discovered.isEmpty()) continue

            val primary = primaries[source]
                ?.let { File(it).takeIf(File::isFile)?.absolutePath }
                ?.takeIf { it in discovered }
                ?: discovered.first()

            SubtitleStore.saveAll(
                context = context,
                animeId = animeId,
                episodeKey = episodeKey,
                episodeNumber = episodeNumber,
                source = source,
                paths = discovered,
                primaryPath = primary
            )

            Log.d(
                "OfflineDownload",
                "SUBTITLE_ASSETS_PERSISTED source=$source episode=$episodeKey count=${discovered.size} root=${root.absolutePath}"
            )
        } catch (e: Exception) {
            Log.w(
                "OfflineDownload",
                "SUBTITLE_ASSETS_PERSIST_FAILED source=$source episode=$episodeKey",
                e
            )
        }
    }
}

@Composable
fun DetailScreen(
    vm: AnimeViewModel,
    anime: Anime,
    back: () -> Unit,
    playEpisode: (Episode) -> Unit,
    openRelated: (Anime) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isOffline by vm.isOffline.collectAsState()
    var detailAnime by remember(anime.id) { mutableStateOf(anime) }

    LaunchedEffect(anime.id, isOffline, vm.sourceRevision) {
        vm.loadAnimeDetail(
            target = detailAnime,
            force = !isOffline,
            onLoaded = { refreshed -> detailAnime = refreshed }
        )
        vm.loadEpisodes(context, detailAnime, force = !isOffline)
    }

    val currentAnime = detailAnime
    val saved = vm.isInLibrary(currentAnime.id)
    val isLinkkf = vm.playerSettings.videoSourcePreference == "linkkf"
    val isReAnime = vm.playerSettings.videoSourcePreference == "reanime"
    val viewStats = vm.linkkfViewStats
    val relatedSeries = vm.linkkfRelatedSeries
    var detailTab by remember(currentAnime.id, isReAnime) { mutableIntStateOf(if (isReAnime) 3 else 0) }
    var episodeQuery by remember(currentAnime.id) { mutableStateOf("") }
    var showCover by remember(currentAnime.id) { mutableStateOf(false) }
    val episodes = vm.episodes(currentAnime)
    val episodesLoading = vm.isEpisodesLoading(currentAnime)

    // 회차가 많은 작품은 페이지 단위로 표시하고, 최신화순/오래된화순을 전환할 수 있다.
    var newestFirst by remember(currentAnime.id) { mutableStateOf(false) }
    var episodePage by remember(currentAnime.id) { mutableIntStateOf(0) }
    val episodePageSize = if (isReAnime) 100 else 50

    LaunchedEffect(currentAnime.id) {
        newestFirst = OfflineStore.getEpisodeSortOrder(context, currentAnime.id)
        episodePage = 0
        episodeQuery = ""
    }

    LaunchedEffect(currentAnime.id) {
        launch(Dispatchers.IO) {
            runCatching { LocalAiTranslationRuntime.warmForAnime(context, currentAnime.id) }
                .onFailure { Log.w("LocalAiPreload", "DETAIL_PRELOAD_FAILED anime=${currentAnime.id}", it) }
        }
    }

    LaunchedEffect(currentAnime.id, vm.sourceRevision) {
        if (isLinkkf && !isOffline) {
            vm.loadLinkkfEpisodeServers(currentAnime, force = true)
            vm.loadLinkkfDetailExtras(currentAnime, force = true)
        }
    }

    val sortedEpisodes = remember(episodes, newestFirst) {
        if (newestFirst) {
            episodes.sortedWith(
                compareByDescending<Episode> { it.number }
                    .thenByDescending { it.displayNumber.lowercase() }
            )
        } else {
            episodes.sortedWith(
                compareBy<Episode> { it.number }
                    .thenBy { it.displayNumber.lowercase() }
            )
        }
    }

    // 재생 버튼은 최신 회차가 아니라 사용자가 이 작품에서 가장 최근에 재생한 회차를 사용한다.
    // 시청 기록이 없으면 기존처럼 목록의 첫 회차(정렬 기준상 최신 회차)를 사용한다.
    val lastPlayedProgress = vm.getLatestProgress(currentAnime.id)
    val lastPlayedEpisode = remember(episodes, lastPlayedProgress) {
        lastPlayedProgress?.let { progress ->
            episodes.firstOrNull { it.displayNumber.equals(progress.episodeKey, ignoreCase = true) }
                ?: episodes.firstOrNull { it.number == progress.episodeNumber }
        } ?: sortedEpisodes.firstOrNull()
    }

    val episodePageCount = remember(sortedEpisodes.size) {
        ((sortedEpisodes.size + episodePageSize - 1) / episodePageSize).coerceAtLeast(1)
    }
    LaunchedEffect(sortedEpisodes.size, newestFirst) {
        episodePage = episodePage.coerceIn(0, (episodePageCount - 1).coerceAtLeast(0))
    }
    val visibleEpisodes = remember(sortedEpisodes, episodePage, episodeQuery) {
        val from = (episodePage * episodePageSize).coerceAtMost(sortedEpisodes.size)
        val to = (from + episodePageSize).coerceAtMost(sortedEpisodes.size)
        sortedEpisodes.subList(from, to).filter { ep ->
            episodeQuery.isBlank() || ep.displayNumber.contains(episodeQuery.trim(), ignoreCase = true) || ep.title.contains(episodeQuery.trim(), ignoreCase = true)
        }
    }

    val downloadProgressMap by vm.downloadProgressMap.collectAsState()

    // 배치 다운로드 상태 관리
    var isBatchDownloading by remember { mutableStateOf(false) }
    var batchTotalCount by remember { mutableIntStateOf(0) }
    var batchCurrentIndex by remember { mutableIntStateOf(0) }

    // 추출 UI 제어용 에피소드 저장 상태
    var activeExtractEpisode by remember { mutableStateOf<Episode?>(null) }

    // 비동기 URL 추출용 Deferred 관리
    var currentExtractDeferred by remember { mutableStateOf<CompletableDeferred<Pair<List<StreamQuality>, String?>>?>(null) }
    var activeExtractTargetUrl by remember { mutableStateOf<String?>(null) }
    val animenosubClient = remember { AnimenosubClient() }

    // 비동기 URL 추출 함수
    suspend fun extractEpisodeInfo(ep: Episode): Pair<List<StreamQuality>, String?> {
        val selectedSource = vm.playerSettings.videoSourcePreference
        if (selectedSource == "miruro" || selectedSource == "ohli24") {
            val resolved = withContext(Dispatchers.IO) {
                if (selectedSource == "miruro") com.lilac.anime.data.MiruroClient().resolve(ep)
                else com.lilac.anime.data.Ohli24Client().resolve(ep)
            }
            val stream = resolved.m3u8Url?.takeIf { it.isNotBlank() }
            if (stream != null) {
                resolved.referer?.let { LinkkfRequestContextStore.save(context, currentAnime.id, ep.id, it) }
                resolved.subtitleReferer?.let { LinkkfRequestContextStore.saveSubtitle(context, currentAnime.id, ep.id, it) }
                resolved.subtitleUrl?.let { LinkkfRequestContextStore.saveSubtitleUrl(context, currentAnime.id, ep.id, it) }
                return listOf(StreamQuality("자동", stream, resolved.referer, resolved.headers)) to resolved.subtitleUrl
            }
            return emptyList<StreamQuality>() to null
        }
        // Current Linkkf (linkani.tv) exposes the actual HLS/VTT URLs in the
        // server-rendered watch page. Resolve that directly; do not mount the
        // generic WebView extractor for Linkkf.
        if (vm.playerSettings.videoSourcePreference == "linkkf") {
            val resolved = runCatching {
                LinkkfPlayerResolver.resolve(context, ep)
            }.getOrNull()
            if (!resolved?.m3u8Url.isNullOrBlank()) {
                resolved?.subtitleUrl?.let {
                    LinkkfRequestContextStore.saveSubtitleUrl(context, currentAnime.id, ep.id, it)
                }
                return listOf(
                    StreamQuality(
                        "자동",
                        resolved!!.m3u8Url,
                        resolved.referer,
                        resolved.headers
                    )
                ) to resolved.subtitleUrl
            }
        }

        // 이미 직접 재생 가능한 미디어 URL이면 WebView 추출을 거치지 않는다.
        val direct = if (vm.playerSettings.videoSourcePreference == "linkkf") {
            ep.videoUrl?.takeIf {
                it.startsWith("http://", true) || it.startsWith("https://", true)
            }
        } else {
            null
        }
        if (direct != null && (direct.contains(".m3u8", true) || direct.contains(".mp4", true))) {
            val mimeQuality = StreamQuality(
                if (direct.contains("1080", true)) "1080p" else "자동",
                direct,
                LinkkfRequestContextStore.get(context, currentAnime.id, ep.id)
            )
            return Pair(listOf(mimeQuality), null)
        }

        val targetUrl = if (vm.playerSettings.videoSourcePreference == "animenosub") {
            ep.videoUrl ?: withContext(Dispatchers.IO) {
                animenosubClient.resolveEpisodeUrl(currentAnime.title, ep.displayNumber)
            }
        } else {
            ep.videoUrl
        }

        if (targetUrl.isNullOrBlank()) {
            return Pair(emptyList(), null)
        }

        val deferred = CompletableDeferred<Pair<List<StreamQuality>, String?>>()
        currentExtractDeferred = deferred
        activeExtractEpisode = ep
        activeExtractTargetUrl = targetUrl

        val result = deferred.await()

        activeExtractEpisode = null
        activeExtractTargetUrl = null
        currentExtractDeferred = null
        // Animenosub is a video source only. Keep Linkkf subtitle discovery independent.
        return if (vm.playerSettings.videoSourcePreference == "animenosub") {
            result.first to null
        } else {
            result
        }
    }

    /**
     * Download-specific Linkkf resolver. Do not depend on the hidden Compose
     * StreamUrlExtractor callback race: the collector observes the actual browser
     * network requests and returns video URL + video Referer + subtitle URL +
     * subtitle Referer as one result.
     */
    suspend fun extractLinkkfDownloadInfo(ep: Episode): Pair<List<StreamQuality>, String?> {
        val selectedSource = vm.playerSettings.videoSourcePreference
        if (selectedSource == "miruro" || selectedSource == "ohli24") {
            return try {
                val resolved = withContext(Dispatchers.IO) {
                    if (selectedSource == "miruro") com.lilac.anime.data.MiruroClient().resolve(ep)
                    else com.lilac.anime.data.Ohli24Client().resolve(ep)
                }
                val stream = resolved.m3u8Url?.takeIf { it.isNotBlank() }
                if (stream == null) return emptyList<StreamQuality>() to null
                resolved.referer?.let { LinkkfRequestContextStore.save(context, currentAnime.id, ep.id, it) }
                resolved.subtitleReferer?.let { LinkkfRequestContextStore.saveSubtitle(context, currentAnime.id, ep.id, it) }
                resolved.subtitleUrl?.let { LinkkfRequestContextStore.saveSubtitleUrl(context, currentAnime.id, ep.id, it) }
                Log.i("OfflineDownload", "${selectedSource.uppercase()}_RESOLVED episode=${ep.displayNumber} subtitle=${resolved.subtitleUrl != null}")
                listOf(StreamQuality("자동", stream, resolved.referer, resolved.headers)) to resolved.subtitleUrl
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e("OfflineDownload", "${selectedSource.uppercase()}_RESOLVE_FAILED episode=${ep.displayNumber}", t)
                emptyList<StreamQuality>() to null
            }
        }
        val direct = ep.videoUrl?.takeIf {
            (it.startsWith("http://", true) || it.startsWith("https://", true)) &&
                (it.contains(".m3u8", true) || it.contains(".mp4", true))
        }
        if (direct != null) {
            // The episode may already contain a resolved M3U8 when the download button
            // is pressed. In that case we must NOT skip Linkkf's subtitle context: the
            // streaming WebView may already have captured the VTT URL in the shared store
            // even though Episode.vttUrl itself is still null.
            val storedVtt = LinkkfRequestContextStore.getSubtitleUrl(
                context, currentAnime.id, ep.id
            )
            val resolvedVtt = ep.vttUrl?.takeIf { it.isNotBlank() } ?: storedVtt
            resolvedVtt?.let {
                LinkkfRequestContextStore.saveSubtitleUrl(context, currentAnime.id, ep.id, it)
            }
            Log.d(
                "OfflineDownload",
                "LINKKF_DIRECT_SOURCE episode=${ep.displayNumber} video=$direct " +
                    "subtitle=${resolvedVtt ?: "<none>"}"
            )
            return listOf(
                StreamQuality(
                    if (direct.contains("1080", true)) "1080p" else "자동",
                    direct,
                    LinkkfRequestContextStore.get(context, currentAnime.id, ep.id)
                )
            ) to resolvedVtt
        }

        val pageUrl = ep.videoUrl?.takeIf { it.isNotBlank() } ?: return emptyList<StreamQuality>() to null
        val resolved = runCatching {
            LinkkfPlayerResolver.resolve(context, ep)
        }.getOrNull() ?: run {
            Log.e("OfflineDownload", "LINKKF_VIDEO_URL_NOT_FOUND episode=${ep.displayNumber} page=$pageUrl")
            return emptyList<StreamQuality>() to null
        }

        val video = resolved.m3u8Url
        if (video.isNullOrBlank()) {
            Log.e("OfflineDownload", "LINKKF_VIDEO_URL_NOT_FOUND episode=${ep.displayNumber} page=$pageUrl")
            return emptyList<StreamQuality>() to null
        }

        val subtitleUrl = resolved.subtitleUrl
            ?: LinkkfRequestContextStore.getSubtitleUrl(context, currentAnime.id, ep.id)
            ?: ep.vttUrl

        LinkkfRequestContextStore.saveSubtitleUrl(context, currentAnime.id, ep.id, subtitleUrl)
        resolved.referer?.let { LinkkfRequestContextStore.save(context, currentAnime.id, ep.id, it) }
        resolved.subtitleReferer?.let {
            LinkkfRequestContextStore.saveSubtitle(context, currentAnime.id, ep.id, it)
        }

        Log.d(
            "OfflineDownload",
            "LINKKF_DOWNLOAD_SOURCE episode=${ep.displayNumber} video=$video " +
                "subtitle=${subtitleUrl ?: "<none>"}"
        )

        return listOf(StreamQuality("자동", video, resolved.referer, resolved.headers)) to subtitleUrl
    }

    // 이미 영상이 다운로드된 에피소드에도 Linkkf VTT와 Kairan ASS를 모두 보충한다.
    suspend fun repairDownloadedSubtitles(ep: Episode) {
        if (!vm.isEpisodeDownloaded(currentAnime.id, ep)) return

        val stored = OfflineStore.getEpisode(context, currentAnime.id, ep)
        val legacyPath = stored?.vttUrl
        val linkkfReady = withContext(Dispatchers.IO) {
            SubtitleStore.get(context, currentAnime.id, ep.displayNumber, ep.number, "linkkf")
        } ?: legacyPath?.takeIf { File(it).isFile && (it.endsWith(".vtt", true) || it.endsWith(".srt", true)) }
        val anissiaReady = withContext(Dispatchers.IO) {
            SubtitleStore.get(context, currentAnime.id, ep.displayNumber, ep.number, "anissia")
        } ?: findLocalAnissiaSubtitle(context, currentAnime.title, ep.number, legacyPath)
        if (linkkfReady != null && anissiaReady != null) return

        Log.d(
            "Subtitle",
            "REPAIR_BOTH_START anime=${currentAnime.id} episode=${ep.number} " +
                "linkkf=${linkkfReady != null} anissia=${anissiaReady != null}"
        )

        try {
            var linkkfPath = linkkfReady
            if (linkkfPath == null) {
                val (_, extractedVtt) = extractEpisodeInfo(ep)
                val originalReferer = LinkkfRequestContextStore.get(context, currentAnime.id, ep.id)
                if (!extractedVtt.isNullOrBlank()) {
                    linkkfPath = downloadSubtitleFile(
                        context = context,
                        animeId = currentAnime.id,
                        episodeNumber = ep.number,
                        episodeKey = ep.displayNumber,
                        vttUrl = extractedVtt,
                        referer = LinkkfRequestContextStore.getSubtitle(context, currentAnime.id, ep.id)
                            ?: originalReferer
                    )
                }
            }

            val anissiaPath = anissiaReady ?: try {
                AnissiaSubtitleService.findSubtitle(
                    context, SubtitleTitleResolver.resolve(context, currentAnime),
                    ep.number, ep.displayNumber, currentAnime.seasonNumber, currentAnime.id
                )
            } catch (e: Exception) {
                Log.w("AniSIA", "OFFLINE_SUBTITLE_REPAIR_FAILED episode=${ep.number}", e)
                null
            }

            persistOfflineSubtitleAssets(
                context = context, animeId = currentAnime.id, title = currentAnime.title,
                episodeKey = ep.displayNumber, episodeNumber = ep.number,
                linkkfPath = linkkfPath, anissiaPath = anissiaPath
            )

            if (linkkfPath != null || anissiaPath != null) {
                val currentStored = OfflineStore.getEpisode(context, currentAnime.id, ep)
                OfflineStore.saveEpisode(
                    context,
                    currentAnime.id,
                    (currentStored ?: ep).copy(
                        videoUrl = currentStored?.videoUrl ?: ep.videoUrl,
                        vttUrl = linkkfPath ?: anissiaPath ?: currentStored?.vttUrl ?: ep.vttUrl
                    )
                )
            }
            Log.d(
                "Subtitle",
                "REPAIR_DONE episode=${ep.number} linkkf=${linkkfPath != null} anissia=${anissiaPath != null}"
            )
        } catch (e: CancellationException) {
            // The detail screen can leave composition while subtitle repair is still
            // running (for example when entering the player). That is expected and
            // must not be reported as a subtitle failure.
            Log.d("Subtitle", "REPAIR_BOTH_CANCELLED episode=${ep.number}")
            throw e
        } catch (e: Exception) {
            Log.e("Subtitle", "REPAIR_BOTH_FAILED episode=${ep.number}", e)
        }
    }

    // 화질 선택 대기용 Deferred 및 Dialog 상태 관리
    var pendingQualitiesDialog by remember { mutableStateOf<List<StreamQuality>?>(null) }
    var pendingDialogTargetEpisode by remember { mutableStateOf<Episode?>(null) }
    var currentQualityDeferred by remember { mutableStateOf<CompletableDeferred<StreamQuality>?>(null) }

    // 화질 선택 대기 함수 (2개 이상 화질 감지 시 호출)
    suspend fun awaitQualitySelection(ep: Episode, qualities: List<StreamQuality>): StreamQuality {
        val deferred = CompletableDeferred<StreamQuality>()
        currentQualityDeferred = deferred
        pendingDialogTargetEpisode = ep
        pendingQualitiesDialog = qualities

        val selected = deferred.await()

        pendingQualitiesDialog = null
        pendingDialogTargetEpisode = null
        currentQualityDeferred = null
        return selected
    }

    // 단일 에피소드 다운로드 프로세스
    fun processSingleDownload(ep: Episode) {
        if (isReAnime) {
            Toast.makeText(context, "${ep.displayNumber}화 다운로드 준비 중...", Toast.LENGTH_SHORT).show()
            scope.launch(Dispatchers.Main) {
                try {
                    val resolved = ReAnimePlayerResolver.resolve(context, ep, currentAnime.anilistId)
                    val streamUrl = resolved.m3u8Url
                    val flixCloudPk = resolved.flixCloudPk
                    if (streamUrl.isNullOrBlank() || flixCloudPk.isNullOrBlank()) {
                        Log.w(
                            "OfflineDownload",
                            "REANIME_RESOLVE_INCOMPLETE episode=${ep.displayNumber} " +
                                "m3u8=${!streamUrl.isNullOrBlank()} pkChars=${flixCloudPk?.length ?: 0}"
                        )
                        Toast.makeText(context, "Re:ANIME 보안 스트림 정보를 가져오지 못했습니다.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        OfflineStore.saveAnime(context, currentAnime)
                        OfflineStore.saveEpisode(
                            context = context,
                            animeId = currentAnime.id,
                            episode = ep.copy(videoUrl = streamUrl, vttUrl = resolved.subtitleUrl.orEmpty())
                        )
                    }
                    runCatching {
                        val allSubtitleAssets = downloadAllOfflineSubtitleSources(
                            context, currentAnime, ep.copy(videoUrl = ep.videoUrl), null, resolved.referer
                        )
                        persistOfflineSubtitleAssets(
                            context = context, animeId = currentAnime.id, title = currentAnime.title,
                            episodeKey = ep.displayNumber, episodeNumber = ep.number,
                            linkkfPath = null, anissiaPath = null,
                            jimakuPaths = allSubtitleAssets.jimakuPaths,
                            reAnimePaths = allSubtitleAssets.reAnimePaths
                        )
                    }.onFailure {
                        Log.w("OfflineDownload", "REANIME_ALL_SUBTITLES_FAILED episode=${ep.displayNumber}", it)
                    }
                    // Re:ANIME streaming and offline use the same FlixCloud HLS material,
                    // but the offline worker runs outside the WebView. Make sure the proxy
                    // session receives the browser-equivalent headers even when Android's
                    // WebView did not expose all request headers through shouldInterceptRequest.
                    val offlineStreamHeaders = buildString {
                        append(resolved.headers.orEmpty())
                        fun addHeader(name: String, value: String?) {
                            if (value.isNullOrBlank()) return
                            val exists = lineSequence().any {
                                it.substringBefore(':').trim().equals(name, ignoreCase = true)
                            }
                            if (!exists) {
                                if (isNotEmpty()) append('\n')
                                append(name).append(": ").append(value.trim())
                            }
                        }
                        addHeader("Referer", resolved.referer)
                        addHeader(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                        )
                        addHeader("Origin", resolved.referer?.let {
                            runCatching {
                                val uri = java.net.URI(it)
                                "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
                            }.getOrNull()
                        })
                    }.takeIf { it.isNotBlank() }

                    Log.i(
                        "OfflineDownload",
                        "REANIME_ENQUEUE episode=${ep.displayNumber} " +
                            "m3u8=${streamUrl.isNotBlank()} pkChars=${resolved.flixCloudPk?.length ?: 0} " +
                            "referer=${resolved.referer?.take(80) ?: "<none>"} " +
                            "headers=${offlineStreamHeaders?.lineSequence()?.map { it.substringBefore(':') }?.joinToString(",").orEmpty()}"
                    )
                    startEpisodeDownload(
                        context = context,
                        animeId = currentAnime.id,
                        animeTitle = currentAnime.title,
                        episode = ep.copy(videoUrl = streamUrl, vttUrl = resolved.subtitleUrl.orEmpty()),
                        streamUrl = streamUrl,
                        referer = resolved.referer,
                        subtitleUrl = resolved.subtitleUrl,
                        subtitleReferer = resolved.subtitleReferer ?: resolved.referer,
                        flixCloudPk = flixCloudPk,
                        streamHeaders = offlineStreamHeaders,
                        videoSource = "reanime"
                    )
                    Toast.makeText(context, "${ep.displayNumber}화 다운로드를 시작합니다.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("OfflineDownload", "REANIME_DOWNLOAD_FAILED episode=${ep.displayNumber}", e)
                    Toast.makeText(context, "${ep.displayNumber}화 다운로드를 시작하지 못했습니다: ${e.message.orEmpty()}", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        // 버튼 클릭 자체가 정상적으로 들어왔는지 즉시 사용자에게 알린다.
        // URL 추출/WebView가 지연되어도 이 Toast는 먼저 표시되어야 한다.
        Toast.makeText(context, "${ep.displayNumber}화 다운로드 준비 중...", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.Main) {
            try {
                val extracted = extractLinkkfDownloadInfo(ep)
                activeExtractEpisode = null
                activeExtractTargetUrl = null
                currentExtractDeferred?.cancel()
                currentExtractDeferred = null
                val (qualities, extractedVttUrl) = extracted
                val vttUrl = extractedVttUrl
                    ?: LinkkfRequestContextStore.getSubtitleUrl(context, currentAnime.id, ep.id)
                if (qualities.isEmpty()) {
                    Toast.makeText(context, "${ep.displayNumber}화의 다운로드 주소를 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val selectedQuality = if (qualities.size > 1) {
                    awaitQualitySelection(ep, qualities)
                } else {
                    qualities.first()
                }

                // WebView가 실제로 사용한 playhd3.php Referer를 회차별로 보존한다.
                selectedQuality.referer?.let { LinkkfRequestContextStore.save(context, currentAnime.id, ep.id, it) }
                val originalReferer = selectedQuality.referer
                    ?: LinkkfRequestContextStore.get(context, currentAnime.id, ep.id)
                // 서비스가 즉시 실행될 수 있으므로 회차 메타데이터를 먼저 기록한다.
                withContext(Dispatchers.IO) {
                    OfflineStore.saveAnime(context, currentAnime)
                    OfflineStore.saveEpisode(
                        context = context,
                        animeId = currentAnime.id,
                        episode = ep.copy(videoUrl = selectedQuality.url, vttUrl = vttUrl)
                    )
                }
                startEpisodeDownload(
                    context = context,
                    animeId = currentAnime.id,
                    animeTitle = currentAnime.title,
                    episode = ep,
                    streamUrl = selectedQuality.url,
                    referer = originalReferer,
                    subtitleUrl = vttUrl,
                    subtitleReferer = LinkkfRequestContextStore.getSubtitle(context, currentAnime.id, ep.id)
                        ?: originalReferer,
                    streamHeaders = selectedQuality.headers,
                    videoSource = vm.playerSettings.videoSourcePreference
                )
                Toast.makeText(context, "${ep.displayNumber}화 (${selectedQuality.label}) 다운로드를 시작합니다.", Toast.LENGTH_SHORT).show()

                withContext(Dispatchers.IO) {
                    // 영상 메타데이터는 다운로드 큐 등록 직후 저장한다. 자막 서버가 실패/지연되어도
                    // 영상 다운로드 자체와 오프라인 회차 상태가 영향을 받지 않게 한다.
                    OfflineStore.saveAnime(context, currentAnime)
                    OfflineStore.saveEpisode(
                        context = context,
                        animeId = currentAnime.id,
                        episode = ep.copy(videoUrl = selectedQuality.url)
                    )

                    // 오프라인 저장 시 Linkkf VTT와 Kairan ASS를 각각 별도로 저장한다.
                    // ASS는 vttUrl에 넣지 않는다. 오프라인에서는 VTT가 기본 자막으로 사용되고,
                    // Kairan ASS는 SubtitleStore/로컬 캐시에서 별도 선택할 수 있다.
                    val localLinkkfPath = try {
                        downloadSubtitleFile(
                            context = context, animeId = currentAnime.id, episodeNumber = ep.number,
                            episodeKey = ep.displayNumber, vttUrl = vttUrl,
                            referer = LinkkfRequestContextStore.getSubtitle(context, currentAnime.id, ep.id)
                                ?: LinkkfRequestContextStore.get(context, currentAnime.id, ep.id)
                        )
                    } catch (e: Exception) {
                        Log.w("OfflineDownload", "LINKKF_SUBTITLE_FAILED episode=${ep.displayNumber}", e)
                        null
                    }
                    val localAnissiaPath = try {
                        AnissiaSubtitleService.findSubtitle(
                            context, SubtitleTitleResolver.resolve(context, currentAnime),
                            ep.number, ep.displayNumber, currentAnime.seasonNumber, currentAnime.id
                        )
                    } catch (e: Exception) {
                        Log.w("AniSIA", "OFFLINE_SUBTITLE_PRELOAD_FAILED episode=${ep.number}", e)
                        null
                    }
                    val allSubtitleAssets = downloadAllOfflineSubtitleSources(
                        context, currentAnime, ep, vttUrl, originalReferer
                    )
                    persistOfflineSubtitleAssets(
                        context = context, animeId = currentAnime.id, title = currentAnime.title,
                        episodeKey = ep.displayNumber, episodeNumber = ep.number,
                        linkkfPath = localLinkkfPath, anissiaPath = localAnissiaPath,
                        jimakuPaths = allSubtitleAssets.jimakuPaths, reAnimePaths = allSubtitleAssets.reAnimePaths
                    )

                    if (localLinkkfPath != null) {
                        OfflineStore.saveEpisode(
                            context = context,
                            animeId = currentAnime.id,
                            episode = ep.copy(
                                videoUrl = selectedQuality.url,
                                vttUrl = localLinkkfPath
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("OfflineDownload", "SINGLE_DOWNLOAD_FAILED episode=${ep.displayNumber}", e)
                Toast.makeText(context, "${ep.displayNumber}화 다운로드를 시작하지 못했습니다.", Toast.LENGTH_SHORT).show()
                activeExtractEpisode = null
                activeExtractTargetUrl = null
                currentExtractDeferred?.cancel()
                currentExtractDeferred = null
            }
        }
    }

    // 전체 에피소드 다운로드 프로세스 (순차적 URL 추출 및 순차 다운로드 실행)
    fun processBatchDownload(targetEpisodes: List<Episode>) {
        if (targetEpisodes.isEmpty()) return
        isBatchDownloading = true
        batchTotalCount = targetEpisodes.size
        batchCurrentIndex = 0

        if (isReAnime) {
            scope.launch(Dispatchers.Main) {
                for ((index, ep) in targetEpisodes.withIndex()) {
                    if (!isBatchDownloading) break
                    batchCurrentIndex = index + 1
                    try {
                        val resolved = ReAnimePlayerResolver.resolve(context, ep, currentAnime.anilistId)
                        val streamUrl = resolved.m3u8Url
                        val pk = resolved.flixCloudPk
                        if (streamUrl.isNullOrBlank() || pk.isNullOrBlank()) {
                            Log.w("OfflineDownload", "REANIME_BATCH_RESOLVE_FAILED episode=${ep.displayNumber} m3u8=${!streamUrl.isNullOrBlank()} pkChars=${pk?.length ?: 0}")
                            continue
                        }

                        val headers = buildString {
                            append(resolved.headers.orEmpty())
                            fun addHeader(name: String, value: String?) {
                                if (value.isNullOrBlank()) return
                                val exists = lineSequence().any { it.substringBefore(':').trim().equals(name, true) }
                                if (!exists) {
                                    if (isNotEmpty()) append('\n')
                                    append(name).append(": ").append(value.trim())
                                }
                            }
                            addHeader("Referer", resolved.referer ?: "https://reanime.to/")
                            addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                            addHeader("Origin", "https://flixcloud.cc")
                        }.takeIf { it.isNotBlank() }

                        withContext(Dispatchers.IO) {
                            OfflineStore.saveAnime(context, currentAnime)
                            OfflineStore.saveEpisode(
                                context = context,
                                animeId = currentAnime.id,
                                episode = ep.copy(videoUrl = streamUrl, vttUrl = resolved.subtitleUrl.orEmpty())
                            )
                        }
                        startEpisodeDownload(
                            context = context,
                            animeId = currentAnime.id,
                            animeTitle = currentAnime.title,
                            episode = ep.copy(videoUrl = streamUrl, vttUrl = resolved.subtitleUrl.orEmpty()),
                            streamUrl = streamUrl,
                            referer = resolved.referer ?: "https://reanime.to/",
                            subtitleUrl = resolved.subtitleUrl,
                            subtitleReferer = resolved.subtitleReferer ?: resolved.referer,
                            flixCloudPk = pk,
                            streamHeaders = headers,
                            videoSource = "reanime"
                        )
                        Log.i("OfflineDownload", "REANIME_BATCH_ENQUEUED episode=${ep.displayNumber} pkChars=${pk.length}")
                    } catch (t: Throwable) {
                        Log.e("OfflineDownload", "REANIME_BATCH_FAILED episode=${ep.displayNumber}", t)
                    }
                }
                isBatchDownloading = false
            }
            return
        }

        scope.launch(Dispatchers.Main) {
            for ((index, ep) in targetEpisodes.withIndex()) {
                if (!isBatchDownloading) break // 다운로드 중단 플래그 체크

                batchCurrentIndex = index + 1
                try {
                    val (qualities, extractedVttUrl) = extractLinkkfDownloadInfo(ep)
                    val vttUrl = extractedVttUrl
                        ?: LinkkfRequestContextStore.getSubtitleUrl(context, currentAnime.id, ep.id)
                    if (qualities.isNotEmpty() && isBatchDownloading) {
                        val selectedQuality = if (qualities.size > 1) {
                            awaitQualitySelection(ep, qualities)
                        } else {
                            qualities.first()
                        }

                        selectedQuality.referer?.let { LinkkfRequestContextStore.save(context, currentAnime.id, ep.id, it) }
                        val originalReferer = selectedQuality.referer
                            ?: LinkkfRequestContextStore.get(context, currentAnime.id, ep.id)
                        // 서비스가 즉시 실행될 수 있으므로 회차 메타데이터를 먼저 기록한다.
                        withContext(Dispatchers.IO) {
                            OfflineStore.saveAnime(context, anime)
                            OfflineStore.saveEpisode(
                                context = context,
                                animeId = currentAnime.id,
                                episode = ep.copy(videoUrl = selectedQuality.url, vttUrl = vttUrl)
                            )
                        }
                        startEpisodeDownload(
                    context = context,
                    animeId = currentAnime.id,
                    animeTitle = currentAnime.title,
                    episode = ep,
                    streamUrl = selectedQuality.url,
                    referer = originalReferer,
                    subtitleUrl = vttUrl,
                    subtitleReferer = LinkkfRequestContextStore.getSubtitle(context, currentAnime.id, ep.id)
                        ?: originalReferer,
                    streamHeaders = selectedQuality.headers,
                    videoSource = vm.playerSettings.videoSourcePreference
                )

                        withContext(Dispatchers.IO) {
                            // 영상 다운로드 큐에 등록된 상태를 즉시 저장한다. 자막 오류가 영상 다운로드를 막지 않는다.
                            OfflineStore.saveAnime(context, anime)
                            OfflineStore.saveEpisode(
                                context = context,
                                animeId = currentAnime.id,
                                episode = ep.copy(videoUrl = selectedQuality.url)
                            )

                            // 오프라인 저장 시 Linkkf VTT와 Kairan ASS를 각각 별도로 저장한다.
                    // ASS는 vttUrl에 넣지 않는다. 오프라인에서는 VTT가 기본 자막으로 사용되고,
                    // Kairan ASS는 SubtitleStore/로컬 캐시에서 별도 선택할 수 있다.
                            val localLinkkfPath = try {
                                downloadSubtitleFile(
                                    context = context, animeId = currentAnime.id, episodeNumber = ep.number,
                                    episodeKey = ep.displayNumber, vttUrl = vttUrl,
                            referer = LinkkfRequestContextStore.getSubtitle(context, currentAnime.id, ep.id)
                                )
                            } catch (e: Exception) {
                                Log.w("OfflineDownload", "LINKKF_SUBTITLE_FAILED episode=${ep.displayNumber}", e)
                                null
                            }
                    val localAnissiaPath = try {
                        AnissiaSubtitleService.findSubtitle(
                            context, SubtitleTitleResolver.resolve(context, currentAnime),
                            ep.number, ep.displayNumber, currentAnime.seasonNumber, currentAnime.id
                        )
                    } catch (e: Exception) {
                        Log.w("AniSIA", "OFFLINE_SUBTITLE_PRELOAD_FAILED episode=${ep.number}", e)
                        null
                    }
                            val allSubtitleAssets = downloadAllOfflineSubtitleSources(
                                context, currentAnime, ep, vttUrl, originalReferer
                            )
                            persistOfflineSubtitleAssets(
                                context = context, animeId = currentAnime.id, title = currentAnime.title,
                                episodeKey = ep.displayNumber, episodeNumber = ep.number,
                                linkkfPath = localLinkkfPath, anissiaPath = localAnissiaPath,
                                jimakuPaths = allSubtitleAssets.jimakuPaths, reAnimePaths = allSubtitleAssets.reAnimePaths
                            )

                            if (localLinkkfPath != null) {
                                OfflineStore.saveEpisode(
                                    context = context,
                                    animeId = currentAnime.id,
                                    episode = ep.copy(
                                        videoUrl = selectedQuality.url,
                                        vttUrl = localLinkkfPath
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            isBatchDownloading = false
            Toast.makeText(context, "전체 다운로드 요청 처리가 완료되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // 기존 오프라인 영상도 두 자막 소스를 모두 보충한다. 영상은 건드리지 않는다.
    LaunchedEffect(currentAnime.id, episodes.size, isOffline) {
        if (isOffline) return@LaunchedEffect

        val downloadedEpisodes = episodes.filter {
            vm.isEpisodeDownloaded(currentAnime.id, it)
        }

        for (ep in downloadedEpisodes) {
            if (!isActive) break
            repairDownloadedSubtitles(ep)
        }
    }

    // 재생 버튼: 본 기록이 있으면 "EP.n 이어보기", 없으면 "재생"
    val playLabel = if (lastPlayedProgress != null && lastPlayedEpisode != null) {
        "EP.${lastPlayedEpisode.displayNumber} 이어보기"
    } else {
        "재생"
    }
    val canPlay = lastPlayedEpisode != null &&
        (!isOffline || vm.isEpisodeDownloaded(currentAnime.id, lastPlayedEpisode))

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // ── 상단 히어로 ──────────────────────────────────────────
            item {
                DetailHero(
                    anime = currentAnime,
                    episodeCount = episodes.size,
                    onBack = back,
                    onPosterClick = { showCover = true }
                )
            }

            // ── 장르 / 재생·보관·전체 저장 / 조회수 ──────────────────
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (currentAnime.genres.isNotEmpty()) {
                        GenreChips(currentAnime.genres)
                        Spacer(Modifier.height(16.dp))
                    }

                    DetailActions(
                        playLabel = playLabel,
                        canPlay = canPlay,
                        onPlay = { lastPlayedEpisode?.let(playEpisode) },
                        saved = saved,
                        onToggleSaved = { vm.toggleLibrary(context, currentAnime.id) },
                        showBatch = !isOffline && episodes.isNotEmpty(),
                        isBatchDownloading = isBatchDownloading,
                        batchIndex = batchCurrentIndex,
                        batchTotal = batchTotalCount,
                        onBatchClick = {
                            if (isBatchDownloading) {
                                // 전체 다운로드 진행 중 클릭 시 취소
                                isBatchDownloading = false
                                activeExtractEpisode = null
                                currentExtractDeferred?.cancel()
                                Toast.makeText(context, "전체 다운로드가 중단되었습니다.", Toast.LENGTH_SHORT).show()
                            } else {
                                val notDownloaded = episodes.filter { !vm.isEpisodeDownloaded(currentAnime.id, it) }
                                if (notDownloaded.isNotEmpty()) {
                                    processBatchDownload(notDownloaded)
                                } else {
                                    Toast.makeText(context, "모든 에피소드가 이미 다운로드되었습니다.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )

                    if (isLinkkf && viewStats != null) {
                        Spacer(Modifier.height(14.dp))
                        ViewStatsCard(
                            day = viewStats.day,
                            week = viewStats.week,
                            month = viewStats.month,
                            total = viewStats.total
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            // ── 정보 / 스토리 / 관련 작품 / (Re:Anime) 회차 탭 ─────────
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    DetailTabs(
                        selected = detailTab,
                        showEpisodeTab = isReAnime,
                        onSelect = { detailTab = it }
                    )
                    Spacer(Modifier.height(16.dp))
                    when (detailTab) {
                        0 -> DetailInfoContent(currentAnime)
                        1 -> ExpandableText(
                            currentAnime.description.replace("\r\n", "\n").trim().ifBlank { "설명이 없습니다." }
                        )
                        2 -> {
                            if (isReAnime && currentAnime.reAnimeRelated.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    currentAnime.reAnimeRelated.forEach { related ->
                                        val slug = related.id.removePrefix("reanime:")
                                        val relatedAnime = Anime(
                                            id = related.id,
                                            title = related.title,
                                            native = related.nativeTitle,
                                            poster = related.poster,
                                            backdrop = related.poster,
                                            format = related.format,
                                            year = related.seasonYear?.toString().orEmpty(),
                                            detailUrl = "https://reanime.to/anime/$slug"
                                        )
                                        RelatedEntryRow(
                                            title = related.title,
                                            subtitle = related.nativeTitle,
                                            meta = listOfNotNull(
                                                related.relationType.replace('_', ' ').lowercase()
                                                    .replaceFirstChar { it.uppercase() }
                                                    .takeIf { it.isNotBlank() },
                                                related.format.takeIf { it.isNotBlank() },
                                                related.seasonYear?.toString()
                                            ).joinToString(" · "),
                                            poster = related.poster,
                                            onClick = {
                                                vm.cacheAnime(relatedAnime)
                                                openRelated(relatedAnime)
                                            }
                                        )
                                    }
                                }
                            } else if (relatedSeries.isNotEmpty() && isLinkkf) {
                                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                    relatedSeries.forEach { series ->
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (relatedSeries.size > 1) {
                                                Text(series.name, style = MaterialTheme.typography.titleSmall)
                                            }
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                items(series.items.take(12)) { related ->
                                                    RelatedPosterCard(anime = related) {
                                                        vm.cacheAnime(related)
                                                        openRelated(related)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    "관련 작품 정보가 없습니다.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── 에피소드 목록 ────────────────────────────────────────
            if (!isReAnime || detailTab == 3) {
                if (episodesLoading) {
                    item {
                        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (episodes.isNotEmpty()) {
                    item {
                        EpisodeListHeader(
                            total = sortedEpisodes.size,
                            newestFirst = newestFirst,
                            onSortChange = { newest ->
                                newestFirst = newest
                                episodePage = 0
                                scope.launch { OfflineStore.saveEpisodeSortOrder(context, currentAnime.id, newest) }
                            }
                        )
                    }

                    if (isLinkkf && vm.linkkfEpisodeServers.size > 1) {
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                            ) {
                                items(vm.linkkfEpisodeServers) { server ->
                                    FilterChip(
                                        selected = vm.linkkfSelectedServerId == server.id,
                                        onClick = {
                                            episodePage = 0
                                            episodeQuery = ""
                                            vm.selectLinkkfEpisodeServer(currentAnime, server.id)
                                        },
                                        label = { Text(server.name) }
                                    )
                                }
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = episodeQuery,
                            onValueChange = { episodeQuery = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                            singleLine = true,
                            placeholder = { Text("회차 검색") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            shape = RoundedCornerShape(28.dp)
                        )
                    }

                    if (episodePageCount > 1) {
                        item {
                            EpisodePager(
                                page = episodePage,
                                pageCount = episodePageCount,
                                onPrev = { episodePage-- },
                                onNext = { episodePage++ }
                            )
                        }
                    }

                    items(visibleEpisodes) { ep ->
                        val downloadKey = offlineDownloadId(currentAnime.id, ep)
                        val isDownloaded = vm.isEpisodeDownloaded(currentAnime.id, ep)
                        val downloadingProgress = downloadProgressMap[downloadKey]
                        val epProgress = vm.getProgress(currentAnime.id, ep.displayNumber, ep.number)
                        val isLastPlayed = lastPlayedProgress != null && lastPlayedEpisode?.id == ep.id

                        EpisodeRow(
                            ep = ep,
                            isReAnime = isReAnime,
                            isDownloaded = isDownloaded,
                            downloadingProgress = downloadingProgress,
                            watchProgress = epProgress?.progress,
                            isLastPlayed = isLastPlayed,
                            isOffline = isOffline,
                            canDownload = ep.playable && !isBatchDownloading && downloadingProgress == null,
                            onPlay = {
                                if (ep.playable && (isDownloaded || !isOffline)) playEpisode(ep)
                            },
                            onDelete = { vm.deleteDownload(context, anime, ep) },
                            onCancelDownload = {
                                vm.cancelDownload(context, currentAnime, ep)
                                Toast.makeText(context, "${ep.number}화 다운로드가 취소되었습니다.", Toast.LENGTH_SHORT).show()
                            },
                            onDownload = { processSingleDownload(ep) }
                        )
                    }
                } else {
                    item {
                        Text(
                            "에피소드가 없습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (showCover) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showCover = false }) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AnimeImage(
                        model = currentAnime.backdrop.ifBlank { currentAnime.poster },
                        contentDescription = currentAnime.title,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                    IconButton(
                        onClick = { showCover = false },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "닫기", tint = Color.White)
                    }
                }
            }
        }

        // 실제 추출기(StreamUrlExtractor) 호출 브릿지
        activeExtractEpisode?.let { ep ->
            val target = activeExtractTargetUrl
            if (!target.isNullOrBlank()) {
                var extractedVtt: String? = null
                var capturedReferer: String? = LinkkfRequestContextStore.get(context, currentAnime.id, ep.id)
                Box(modifier = Modifier.size(1.dp).alpha(0f)) {
                    StreamUrlExtractor(
                        targetUrl = target,
                        onSubtitleFound = { vttUrl -> extractedVtt = vttUrl },
                        onSubtitleRefererFound = { vttUrl, referer ->
                            LinkkfRequestContextStore.saveSubtitle(context, currentAnime.id, ep.id, referer)
                            Log.d("Subtitle", "LINKKF_SUBTITLE_REFERER_CAPTURED episode=${ep.displayNumber} url=$vttUrl referer=$referer")
                        },
                        onRefererFound = { referer ->
                            capturedReferer = referer
                            LinkkfRequestContextStore.save(context, currentAnime.id, ep.id, referer)
                        },
                        onQualitiesFound = { qualities ->
                            val enriched = qualities.map { q ->
                                if (q.referer.isNullOrBlank() && !capturedReferer.isNullOrBlank()) q.copy(referer = capturedReferer) else q
                            }
                            currentExtractDeferred?.complete(Pair(enriched, extractedVtt))
                        },
                        allowedHosts = when (vm.playerSettings.videoSourcePreference) {
                            "animenosub" -> setOf("animenosub.to", "www.animenosub.to")
                            "reanime" -> setOf("reanime.to", "www.reanime.to", "flixcloud.cc", "www.flixcloud.cc")
                            else -> emptySet()
                        }
                    )
                }
            } else {
                currentExtractDeferred?.complete(Pair(emptyList(), null))
            }
        }

        // 2개 이상의 화질(m3u8)이 검색된 경우 띄우는 화질 선택 다이얼로그
        val qualitiesForDialog = pendingQualitiesDialog
        if (qualitiesForDialog != null) {
            AlertDialog(
                onDismissRequest = {
                    currentQualityDeferred?.complete(qualitiesForDialog.first())
                },
                title = { Text("다운로드 화질 선택 (${pendingDialogTargetEpisode?.displayNumber ?: ""}화)") },
                text = {
                    Column {
                        qualitiesForDialog.forEach { quality ->
                            Button(
                                onClick = {
                                    currentQualityDeferred?.complete(quality)
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Text("${quality.label} 선택")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        currentQualityDeferred?.complete(qualitiesForDialog.first())
                    }) {
                        Text("기본 화질 선택")
                    }
                }
            )
        }
    }
}

// ============================================================
// PLAYER
// ============================================================

const val USER_SUBTITLE_DIR = "user_subtitles"

