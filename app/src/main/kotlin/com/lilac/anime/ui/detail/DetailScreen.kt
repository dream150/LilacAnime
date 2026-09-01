package com.lilac.anime

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.media3.exoplayer.offline.Download
import com.lilac.anime.ui.AnimeImage
import com.lilac.anime.data.*
import com.lilac.anime.data.subtitle.KairanSubtitleResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
@Composable
fun DetailScreen(
    vm: AnimeViewModel,
    anime: Anime,
    back: () -> Unit,
    playEpisode: (Episode) -> Unit
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
    val episodes = vm.episodes(currentAnime)
    val episodesLoading = vm.isEpisodesLoading(currentAnime)

    // 회차가 많은 작품은 페이지 단위로 표시하고, 최신화순/오래된화순을 전환할 수 있다.
    var newestFirst by remember(currentAnime.id) { mutableStateOf(false) }
    var episodePage by remember(currentAnime.id) { mutableIntStateOf(0) }
    val episodePageSize = 50

    LaunchedEffect(currentAnime.id) {
        newestFirst = OfflineStore.getEpisodeSortOrder(context, currentAnime.id)
        episodePage = 0
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
    val visibleEpisodes = remember(sortedEpisodes, episodePage) {
        val from = (episodePage * episodePageSize).coerceAtMost(sortedEpisodes.size)
        val to = (from + episodePageSize).coerceAtMost(sortedEpisodes.size)
        sortedEpisodes.subList(from, to)
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
                direct
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

    // 이미 영상이 다운로드된 에피소드에도 Linkkf VTT와 Kairan ASS를 모두 보충한다.
    suspend fun repairDownloadedSubtitles(ep: Episode) {
        if (!vm.isEpisodeDownloaded(currentAnime.id, ep)) return

        val stored = OfflineStore.getEpisode(context, currentAnime.id, ep)
        val legacyPath = stored?.vttUrl
        val linkkfReady = withContext(Dispatchers.IO) {
            SubtitleStore.get(context, currentAnime.id, ep.displayNumber, ep.number, "linkkf")
        } ?: legacyPath?.takeIf { File(it).isFile && (it.endsWith(".vtt", true) || it.endsWith(".srt", true)) }
        val kairanReady = withContext(Dispatchers.IO) {
            SubtitleStore.get(context, currentAnime.id, ep.displayNumber, ep.number, "kairan")
        } ?: findLocalKairanAssSubtitle(context, currentAnime.title, ep.number, legacyPath)
        val csoraReady = withContext(Dispatchers.IO) {
            SubtitleStore.get(context, currentAnime.id, ep.displayNumber, ep.number, "csora")
        }

        if (linkkfReady != null && kairanReady != null && csoraReady != null) return

        Log.d(
            "Subtitle",
            "REPAIR_BOTH_START anime=${currentAnime.id} episode=${ep.number} " +
                "linkkf=${linkkfReady != null} kairan=${kairanReady != null}"
        )

        try {
            var linkkfPath = linkkfReady
            if (linkkfPath == null) {
                val (_, extractedVtt) = extractEpisodeInfo(ep)
                if (!extractedVtt.isNullOrBlank()) {
                    linkkfPath = downloadSubtitleFile(
                        context = context,
                        animeId = currentAnime.id,
                        episodeNumber = ep.number,
                        episodeKey = ep.displayNumber,
                        vttUrl = extractedVtt
                    )
                }
            }

            var kairanPath = kairanReady
            if (kairanPath == null) {
                kairanPath = try {
                    when (val result = KairanSubtitleService.findSubtitle(context, currentAnime.title, ep.number, ep.displayNumber)) {
                        is KairanSubtitleResult.DirectFile -> result.path
                        null -> null
                    }
                } catch (e: Exception) {
                    Log.w("Kairan", "OFFLINE_ASS_REPAIR_FAILED episode=${ep.number}", e)
                    null
                }
            }

            var csoraPath = csoraReady
            if (csoraPath == null) {
                csoraPath = try {
                    when (val result = CsoraSubtitleService.findSubtitle(context, currentAnime.title, ep.number, ep.displayNumber)) {
                        is KairanSubtitleResult.DirectFile -> result.path
                        null -> null
                    }
                } catch (e: Exception) {
                    Log.w("Csora", "OFFLINE_ASS_REPAIR_FAILED episode=${ep.number}", e)
                    null
                }
            }

            SubtitleStore.save(context, currentAnime.id, ep.displayNumber, ep.number, "linkkf", linkkfPath)
            SubtitleStore.save(context, currentAnime.id, ep.displayNumber, ep.number, "kairan", kairanPath)
            SubtitleStore.save(context, currentAnime.id, ep.displayNumber, ep.number, "csora", csoraPath)

            if (linkkfPath != null || kairanPath != null) {
                val currentStored = OfflineStore.getEpisode(context, currentAnime.id, ep)
                OfflineStore.saveEpisode(
                    context,
                    currentAnime.id,
                    (currentStored ?: ep).copy(
                        videoUrl = currentStored?.videoUrl ?: ep.videoUrl,
                        vttUrl = linkkfPath ?: kairanPath ?: csoraPath ?: currentStored?.vttUrl ?: ep.vttUrl
                    )
                )
            }
            Log.d(
                "Subtitle",
                "REPAIR_BOTH_DONE episode=${ep.number} linkkf=${linkkfPath != null} kairan=${kairanPath != null}"
            )
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
        // 버튼 클릭 자체가 정상적으로 들어왔는지 즉시 사용자에게 알린다.
        // URL 추출/WebView가 지연되어도 이 Toast는 먼저 표시되어야 한다.
        Toast.makeText(context, "${ep.displayNumber}화 다운로드 준비 중...", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.Main) {
            try {
                val extracted = withTimeoutOrNull(20_000L) { extractEpisodeInfo(ep) }
                activeExtractEpisode = null
                activeExtractTargetUrl = null
                currentExtractDeferred?.cancel()
                currentExtractDeferred = null
                val (qualities, vttUrl) = extracted ?: Pair(emptyList(), null)
                if (qualities.isEmpty()) {
                    Toast.makeText(context, "${ep.displayNumber}화의 다운로드 주소를 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val selectedQuality = if (qualities.size > 1) {
                    awaitQualitySelection(ep, qualities)
                } else {
                    qualities.first()
                }

                // 영상 다운로드는 자막 서버보다 먼저 Media3 큐에 등록한다.
                startEpisodeDownload(context, currentAnime.id, currentAnime.title, ep, selectedQuality.url)
                Toast.makeText(context, "${ep.displayNumber}화 (${selectedQuality.label}) 다운로드를 시작합니다.", Toast.LENGTH_SHORT).show()


                withContext(Dispatchers.IO) {
                    // 영상 메타데이터는 다운로드 큐 등록 직후 저장한다. 자막 서버가 실패/지연되어도
                    // 영상 다운로드 자체와 오프라인 회차 상태가 영향을 받지 않게 한다.
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
                            episodeKey = ep.displayNumber, vttUrl = vttUrl
                        )
                    } catch (e: Exception) {
                        Log.w("OfflineDownload", "LINKKF_SUBTITLE_FAILED episode=${ep.displayNumber}", e)
                        null
                    }
                    val localKairanPath = try {
                        when (val result = KairanSubtitleService.findSubtitle(context, currentAnime.title, ep.number, ep.displayNumber)) {
                            is KairanSubtitleResult.DirectFile -> result.path
                            null -> null
                        }
                    } catch (e: Exception) {
                        Log.w("Kairan", "OFFLINE_ASS_PRELOAD_FAILED episode=${ep.number}", e)
                        null
                    }
                    SubtitleStore.save(context, currentAnime.id, ep.displayNumber, ep.number, "linkkf", localLinkkfPath)
                    SubtitleStore.save(context, currentAnime.id, ep.displayNumber, ep.number, "kairan", localKairanPath)

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

        scope.launch(Dispatchers.Main) {
            for ((index, ep) in targetEpisodes.withIndex()) {
                if (!isBatchDownloading) break // 다운로드 중단 플래그 체크

                batchCurrentIndex = index + 1
                try {
                    val (qualities, vttUrl) = extractEpisodeInfo(ep)
                    if (qualities.isNotEmpty() && isBatchDownloading) {
                        val selectedQuality = if (qualities.size > 1) {
                            awaitQualitySelection(ep, qualities)
                        } else {
                            qualities.first()
                        }

                        // 자막 처리 전에 영상 다운로드를 먼저 큐에 등록한다.
                        startEpisodeDownload(context, currentAnime.id, currentAnime.title, ep, selectedQuality.url)

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
                                    episodeKey = ep.displayNumber, vttUrl = vttUrl
                                )
                            } catch (e: Exception) {
                                Log.w("OfflineDownload", "LINKKF_SUBTITLE_FAILED episode=${ep.displayNumber}", e)
                                null
                            }
                            val localKairanPath = try {
                                when (val result = KairanSubtitleService.findSubtitle(context, currentAnime.title, ep.number, ep.displayNumber)) {
                                    is KairanSubtitleResult.DirectFile -> result.path
                                    null -> null
                                }
                            } catch (e: Exception) {
                                Log.w("Kairan", "OFFLINE_ASS_PRELOAD_FAILED episode=${ep.number}", e)
                                null
                            }
                            SubtitleStore.save(context, currentAnime.id, ep.displayNumber, ep.number, "linkkf", localLinkkfPath)
                            SubtitleStore.save(context, currentAnime.id, ep.displayNumber, ep.number, "kairan", localKairanPath)

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

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(280.dp)) {
                    AnimeImage(
                        model = currentAnime.backdrop,
                        contentDescription = currentAnime.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
                    IconButton(onClick = back, modifier = Modifier.padding(top = 12.dp, start = 8.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로가기", tint = Color.White)
                    }
                }
            }

            item {
                Column(Modifier.padding(20.dp)) {
                    Row {
                        AnimeImage(
                            model = currentAnime.poster,
                            contentDescription = currentAnime.title,
                            modifier = Modifier.size(width = 110.dp, height = 160.dp).clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(currentAnime.title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            Spacer(Modifier.height(8.dp))
                            Text("${episodes.size}화", color = LilacDark)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                currentAnime.genres.joinToString(" · "),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = lastPlayedEpisode != null && (!isOffline || vm.isEpisodeDownloaded(currentAnime.id, lastPlayedEpisode)),
                            onClick = {
                                lastPlayedEpisode?.let(playEpisode)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Lilac, contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(5.dp))
                            Text("재생", color = Color.White)
                        }

                        OutlinedButton(onClick = { vm.toggleLibrary(context, currentAnime.id) }) {
                            Icon(if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
                            Spacer(Modifier.width(5.dp))
                            Text(if (saved) "저장됨" else "내 목록", color = MaterialTheme.colorScheme.onBackground)
                        }

                        if (!isOffline && episodes.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
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
                            ) {
                                Icon(
                                    if (isBatchDownloading) Icons.Default.Close else Icons.Default.DownloadForOffline,
                                    contentDescription = null,
                                    tint = if (isBatchDownloading) Color.Red else Lilac
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    if (isBatchDownloading) "취소 ($batchCurrentIndex/$batchTotalCount)" else "전체 저장",
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text(currentAnime.description, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
                }
            }

            if (episodesLoading) {
                item {
                    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Lilac)
                    }
                }
            } else if (episodes.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "에피소드",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Lilac.copy(alpha = 0.08f))
                                .padding(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    newestFirst = true
                                    episodePage = 0
                                    scope.launch { OfflineStore.saveEpisodeSortOrder(context, currentAnime.id, true) }
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (newestFirst) Lilac else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Text("최신화순", fontSize = 12.sp, fontWeight = if (newestFirst) FontWeight.Bold else FontWeight.Normal)
                            }
                            TextButton(
                                onClick = {
                                    newestFirst = false
                                    episodePage = 0
                                    scope.launch { OfflineStore.saveEpisodeSortOrder(context, currentAnime.id, false) }
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (!newestFirst) Lilac else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Text("오래된화순", fontSize = 12.sp, fontWeight = if (!newestFirst) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            enabled = episodePage > 0,
                            onClick = { episodePage-- }
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "이전 회차 페이지")
                        }
                        Text(
                            "${episodePage + 1} / $episodePageCount",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                        IconButton(
                            enabled = episodePage < episodePageCount - 1,
                            onClick = { episodePage++ }
                        ) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "다음 회차 페이지")
                        }
                    }
                }

                items(visibleEpisodes) { ep ->
                    val downloadKey = offlineDownloadId(currentAnime.id, ep)
                    val isDownloaded = vm.isEpisodeDownloaded(currentAnime.id, ep)
                    val downloadingProgress = downloadProgressMap[downloadKey]
                    val epProgress = vm.getProgress(currentAnime.id, ep.displayNumber, ep.number)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDownloaded) Lilac.copy(alpha = 0.15f) else Color.Transparent)
                            .clickableNoIndication {
                                if (isDownloaded || !isOffline) playEpisode(ep)
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Lilac.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(ep.displayNumber, color = Lilac, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(ep.title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            if (isDownloaded) {
                                Text("오프라인 시청 가능", color = LilacDark, fontSize = 12.sp)
                            } else if (downloadingProgress != null) {
                                val percentText = (downloadingProgress * 100).toInt()
                                Text("다운로드 중... $percentText%", color = Lilac, fontSize = 12.sp)
                            }

                            if (epProgress != null && epProgress.progress > 0f) {
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { epProgress.progress },
                                    modifier = Modifier.fillMaxWidth(0.8f).height(3.dp),
                                    color = Lilac
                                )
                            }
                        }

                        when {
                            isDownloaded -> {
                                IconButton(
                                    onClick = { vm.deleteDownload(context, anime, ep) }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            downloadingProgress != null -> {
                                // 다운로드 진행 중: Circular Progress + 클릭 시 다운로드 취소
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clickable {
                                            vm.cancelDownload(context, currentAnime, ep)
                                            Toast.makeText(context, "${ep.number}화 다운로드가 취소되었습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                ) {
                                    CircularProgressIndicator(
                                        progress = { downloadingProgress ?: 0f },
                                        modifier = Modifier.fillMaxSize(),
                                        color = Lilac,
                                        strokeWidth = 3.dp
                                    )
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "취소",
                                        tint = Lilac,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            !isOffline -> {
                                IconButton(
                                    enabled = !isBatchDownloading && activeExtractEpisode == null,
                                    onClick = { processSingleDownload(ep) }
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "다운로드", tint = Lilac)
                                }
                            }
                            else -> {
                                Icon(Icons.Default.CloudOff, contentDescription = "오프라인", tint = Color.Gray)
                            }
                        }
                    }
                }
            } else {
                item {
                    Text("에피소드가 없습니다.", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }

        // 실제 추출기(StreamUrlExtractor) 호출 브릿지
        activeExtractEpisode?.let { ep ->
            val target = activeExtractTargetUrl
            if (!target.isNullOrBlank()) {
                var extractedVtt: String? = null
                Box(modifier = Modifier.size(1.dp).alpha(0f)) {
                    StreamUrlExtractor(
                        targetUrl = target,
                        onSubtitleFound = { vttUrl -> extractedVtt = vttUrl },
                        onQualitiesFound = { qualities ->
                            currentExtractDeferred?.complete(Pair(qualities, extractedVtt))
                        },
                        allowedHosts = if (vm.playerSettings.videoSourcePreference == "animenosub") {
                            setOf("animenosub.to", "www.animenosub.to")
                        } else emptySet()
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
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Lilac)
                            ) {
                                Text("${quality.label} 선택", color = Color.White)
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

