package com.lilac.anime

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.WindowManager
import android.widget.Toast
import android.widget.TextView
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import com.lilac.anime.data.*
import com.lilac.anime.network.LinkkfChapterService
import com.lilac.anime.network.OfflineOpEdResultStore
import com.lilac.anime.network.OfflineOpEdFingerprintStore
import com.lilac.anime.data.subtitle.KairanSubtitleResult
import com.lilac.anime.data.subtitle.SubtitleAssetUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
private fun formatChapterAnalysisStatus(raw: String): String {
    val parts = raw.split(' ')
    return when {
        raw == "ANALYSIS_START" || raw.startsWith("ANALYSIS_START") -> "OP/ED 분석 시작"
        raw.startsWith("ONLINE_FINGERPRINT_ANALYSIS_START") -> "온라인 OP/ED 분석 시작"
        raw.startsWith("ONLINE_FINGERPRINT_TEMPLATE_HIT") -> "저장된 OP/ED Fingerprint 불러오는 중..."
        raw.startsWith("ONLINE_FINGERPRINT_TRAINING_START") -> "OP/ED 학습용 에피소드 분석 중..."
        raw.startsWith("ONLINE_FINGERPRINT_M3U8_COLLECTION_COMPLETE") -> "학습 에피소드 스트림 확인 완료"
        raw.startsWith("M3U8_WEBVIEW_") && raw.contains("_START") -> "에피소드 스트림 확인 중..."
        raw.startsWith("M3U8_WEBVIEW_") && raw.contains("_LOAD_PAGE") -> "에피소드 페이지 불러오는 중..."
        raw.startsWith("M3U8_WEBVIEW_") && raw.contains("_FOUND") -> "스트림 주소 확인 완료"
        raw.startsWith("M3U8_WEBVIEW_") && raw.contains("_PAGE_START") -> "WebView 분석 중..."
        raw.startsWith("M3U8_WEBVIEW_") && raw.contains("_PAGE_FINISHED") -> "플레이어 요청 분석 중..."
        raw.startsWith("ONLINE_FINGERPRINT_REFERENCE_DOWNLOAD episode=") -> "학습 에피소드 전체 다운로드 중..."
        raw.startsWith("ONLINE_FINGERPRINT_REFERENCE_DOWNLOAD_OK") -> "학습 에피소드 다운로드 완료"
        raw.startsWith("ONLINE_FINGERPRINT_REFERENCE_FINGERPRINT_OK") -> "학습 에피소드 오디오 분석 완료"
        raw.startsWith("ONLINE_FINGERPRINT_TEMPLATE_SAVED") -> "OP/ED Fingerprint 저장 완료"
        raw.startsWith("FINGERPRINT_FULL_WINDOW") -> "현재 에피소드 전체 오디오 분석 중..."
        raw.startsWith("FINGERPRINT_CURRENT_FULL_OK") -> "현재 에피소드 오디오 분석 완료"
        raw.startsWith("FINGERPRINT_TEMPLATE_HIT") -> "OP/ED Fingerprint 비교 중..."
        raw.startsWith("FINGERPRINT_OP_SEARCH_START") -> "OP 구간 검색 중..."
        raw.startsWith("FINGERPRINT_OP_SCORE") -> "OP 후보 점수 계산 중..."
        raw.startsWith("FINGERPRINT_OP_MATCH") -> "✓ OP 구간 발견"
        raw.startsWith("FINGERPRINT_ED_SEARCH_RANGE") -> "ED 구간 검색 중..."
        raw.startsWith("FINGERPRINT_ED_SCORE") -> "ED 후보 점수 계산 중..."
        raw.startsWith("FINGERPRINT_ED_MATCH") -> "✓ ED 구간 발견"
        raw.startsWith("ONLINE_FINGERPRINT_ANALYSIS_FAILED") -> "✗ 온라인 OP/ED 분석 실패: ${raw.removePrefix("ONLINE_FINGERPRINT_ANALYSIS_FAILED ")}"
        raw.startsWith("ONLINE_FINGERPRINT_TRAINING_FAILED") -> "✗ OP/ED 학습 실패: ${raw.removePrefix("ONLINE_FINGERPRINT_TRAINING_FAILED ")}"
        raw.startsWith("CURRENT_FRONT_DOWNLOAD start=") -> "현재 에피소드 앞부분 다운로드 중..."
        raw.startsWith("CURRENT_FRONT_DOWNLOAD_OK") -> "✓ 현재 에피소드 앞부분 다운로드 완료"
        raw.startsWith("CURRENT_FRONT_DOWNLOAD_FAILED") -> "✗ 현재 에피소드 앞부분 다운로드 실패"
        raw.startsWith("CURRENT_BACK_DOWNLOAD start=") -> "현재 에피소드 뒷부분 다운로드 중..."
        raw.startsWith("CURRENT_BACK_DOWNLOAD_OK") -> "✓ 현재 에피소드 뒷부분 다운로드 완료"
        raw.startsWith("CURRENT_BACK_DOWNLOAD_FAILED") -> "✗ 현재 에피소드 뒷부분 다운로드 실패"
        raw == "CURRENT_FRONT_FINGERPRINT_START" -> "앞부분 오디오 Fingerprint 생성 중..."
        raw.startsWith("CURRENT_FRONT_FINGERPRINT_OK") -> "✓ 앞부분 Fingerprint 생성 완료"
        raw.startsWith("CURRENT_FRONT_FINGERPRINT_FAILED") -> "✗ 앞부분 Fingerprint 생성 실패"
        raw == "CURRENT_BACK_FINGERPRINT_START" -> "뒷부분 오디오 Fingerprint 생성 중..."
        raw.startsWith("CURRENT_BACK_FINGERPRINT_OK") -> "✓ 뒷부분 Fingerprint 생성 완료"
        raw.startsWith("CURRENT_BACK_FINGERPRINT_FAILED") -> "✗ 뒷부분 Fingerprint 생성 실패"
        raw.startsWith("COMPARISON_EPISODES_FOUND") -> "✓ 비교 에피소드 ${parts.firstOrNull { it.startsWith("count=") }?.substringAfter('=') ?: "0"}개 발견"
        raw.startsWith("CANDIDATE_") && raw.contains("_START") -> "비교 에피소드 ${parts.lastOrNull()?.removePrefix("episode=") ?: "?"} 분석 시작"
        raw.contains("_OP_DOWNLOAD episode=") -> "OP 비교 구간 다운로드 중..."
        raw.contains("_OP_DOWNLOAD_OK") -> "✓ OP 비교 구간 다운로드 완료"
        raw.contains("_OP_DOWNLOAD_FAILED") -> "✗ OP 비교 구간 다운로드 실패"
        raw.contains("_OP_FINGERPRINT_START") -> "OP 오디오 Fingerprint 생성 중..."
        raw.contains("_OP_FINGERPRINT_OK") -> "✓ OP Fingerprint 생성 완료"
        raw.contains("_OP_FINGERPRINT_FAILED") -> "✗ OP Fingerprint 생성 실패"
        raw.contains("_OP_MATCH") -> "✓ OP 공통 구간 후보 발견"
        raw.contains("_OP_NO_MATCH") -> "OP 공통 구간 없음"
        raw.contains("_ED_DOWNLOAD episode=") -> "ED 비교 구간 다운로드 중..."
        raw.contains("_ED_DOWNLOAD_OK") -> "✓ ED 비교 구간 다운로드 완료"
        raw.contains("_ED_DOWNLOAD_FAILED") -> "✗ ED 비교 구간 다운로드 실패"
        raw.contains("_ED_FINGERPRINT_START") -> "ED 오디오 Fingerprint 생성 중..."
        raw.contains("_ED_FINGERPRINT_OK") -> "✓ ED Fingerprint 생성 완료"
        raw.contains("_ED_FINGERPRINT_FAILED") -> "✗ ED Fingerprint 생성 실패"
        raw.contains("_ED_MATCH") -> "✓ ED 공통 구간 후보 발견"
        raw.contains("_ED_NO_MATCH") -> "ED 공통 구간 없음"
        raw.startsWith("COMPARISON_COMPLETE") -> "에피소드 비교 완료 · 최종 판정 중..."
        raw.startsWith("OP_DETECTED") -> "✓ OP 감지 완료"
        raw.startsWith("ED_DETECTED") -> "✓ ED 감지 완료"
        raw == "OP_NOT_DETECTED" -> "OP를 감지하지 못했습니다"
        raw == "ED_NOT_DETECTED" -> "ED를 감지하지 못했습니다"
        raw.startsWith("ANALYSIS_COMPLETE") -> "✓ OP/ED 분석 완료"
        raw.startsWith("ANALYSIS_FAILED") -> "✗ OP/ED 분석 실패: ${raw.removePrefix("ANALYSIS_FAILED ")}"
        else -> raw
    }
}

@Composable
private fun SettingToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.055f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Lilac, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.scale(0.82f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Lilac,
                    uncheckedThumbColor = Color.LightGray,
                    uncheckedTrackColor = Color.DarkGray
                )
            )
        }
    }
}

private fun loadSubtitleFontsForSource(
    context: Context,
    subtitlePath: String,
    source: String,
    assHandler: AssHandler,
    selectedFontPath: String? = null
): Int {
    val subtitleFile = File(subtitlePath)
    val candidates = linkedSetOf<File>()
    subtitleFile.parentFile?.let { parent -> candidates += File(parent, "fonts") }

    if (source == "csora") {
        val root = File(context.filesDir, "csora_subtitles")
        subtitleFile.parentFile?.name?.let { titleKey -> candidates += File(root, "$titleKey/fonts") }
    } else if (source == "kairan") {
        val root = File(context.filesDir, "kairan_subtitles/fonts")
        // Kairan keeps the subtitle itself flat for backwards compatibility.
        // Its extracted fonts are stored under fonts/<normalized-title>.
        val base = subtitleFile.nameWithoutExtension
        val titleKey = Regex("^(.*)_\\d+(?:_.*)?$")
            .find(base)?.groupValues?.getOrNull(1)
            ?: base.substringBeforeLast("_")
        candidates += File(root, titleKey)
    }

    fun collectFonts(dir: File): List<File> =
        if (!dir.isDirectory) emptyList() else dir.walkTopDown()
            .filter { file -> file.isFile && file.extension.lowercase(Locale.ROOT) in setOf("ttf", "otf", "ttc") }
            .toList()

    val fontFiles = candidates.flatMap(::collectFonts).distinctBy { it.absolutePath }
    var loaded = 0
    for (font in fontFiles) {
        try {
            val bytes = font.readBytes()
            assHandler.addFont(font.name, bytes)
            assHandler.addFont(font.nameWithoutExtension, bytes)
            loaded++
            Log.d("Subtitle", "ASS_FONT_LOADED source=$source name=${font.name} size=${font.length()}")
        } catch (e: Exception) {
            Log.w("Subtitle", "ASS_FONT_LOAD_FAILED source=$source name=${font.name}", e)
        }
    }
    if (fontFiles.isEmpty()) Log.d("Subtitle", "ASS_FONT_NONE source=$source subtitle=$subtitlePath")
    return loaded
}

private fun isLocalUserSubtitlePath(path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    val file = File(path)
    if (!file.isFile) return false
    val normalized = file.absolutePath.replace('\\', '/')
    return normalized.contains("/${USER_SUBTITLE_DIR}/") &&
        (normalized.endsWith(".ass", true) || normalized.endsWith(".ssa", true) ||
         normalized.endsWith(".srt", true) || normalized.endsWith(".vtt", true))
}

private fun userSubtitleDirectory(context: Context): File =
    File(context.filesDir, USER_SUBTITLE_DIR).apply { mkdirs() }

private fun userSubtitleFile(context: Context, animeId: String, episodeNumber: Int, extension: String, episodeKey: String = episodeNumber.toString()): File {
    val safeKey = episodeKey.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")
    return File(userSubtitleDirectory(context), "${animeId}_${safeKey}.${extension.lowercase(Locale.ROOT)}")
}

fun findLocalKairanAssSubtitle(
    context: Context,
    title: String,
    episodeNumber: Int,
    storedPath: String? = null
): String? {
    fun isAss(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return (lower.endsWith(".ass") || lower.endsWith(".ssa")) && File(path).isFile
    }

    if (!storedPath.isNullOrBlank() && isAss(storedPath)) {
        return storedPath
    }

    val dir = File(context.filesDir, "kairan_subtitles")
    if (!dir.isDirectory) return null

    val safe = KairanSubtitleService.normalizeTitleForFile(title)
        .replace(' ', '_')
        .ifBlank { "subtitle" }
        .take(60)

    val exactNames = listOf(
        "${safe}_${episodeNumber}.ass",
        "${safe}_${episodeNumber}.ssa"
    )
    for (name in exactNames) {
        val file = File(dir, name)
        if (file.isFile) return file.absolutePath
    }

    return dir.listFiles()?.firstOrNull { file ->
        if (!file.isFile) return@firstOrNull false
        val n = file.name.lowercase(Locale.ROOT)
        (n.endsWith(".ass") || n.endsWith(".ssa")) &&
            n.contains("_${episodeNumber}") &&
            n.startsWith(safe.lowercase(Locale.ROOT))
    }?.absolutePath
}

@SuppressLint("SourceLockedOrientationActivity")
@OptIn(UnstableApi::class)
private class KeyboardPlayerView(context: Context) : PlayerView(context) {
    var seekSeconds: Int = 10

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val p = player
            val delta = seekSeconds.coerceAtLeast(1) * 1000L
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    p?.seekTo((p.currentPosition - delta).coerceAtLeast(0L))
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    val target = p?.currentPosition?.plus(delta) ?: return true
                    val duration = p.duration
                    p.seekTo(if (duration != C.TIME_UNSET && duration > 0L) minOf(target, duration) else target)
                    return true
                }
                android.view.KeyEvent.KEYCODE_SPACE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    p?.let { if (it.isPlaying) it.pause() else it.play() }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@SuppressLint("SourceLockedOrientationActivity")
@OptIn(UnstableApi::class)
private class VttStrokeTextView(context: Context) : TextView(context) {
    var outlineWidthPx: Float = 0f
    var outlineColor: Int = android.graphics.Color.BLACK

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        includeFontPadding = true
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (outlineWidthPx <= 0.01f || text.isNullOrEmpty()) {
            super.onDraw(canvas)
            return
        }
        val originalStyle = paint.style
        val originalStroke = paint.strokeWidth
        val originalColor = currentTextColor

        // Use the VTT glyph itself as the mask: a black stroke is drawn first,
        // then the original glyph is drawn over it. This makes the border grow
        // outward instead of eating into the subtitle fill.
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = outlineWidthPx * 2f
        paint.strokeJoin = android.graphics.Paint.Join.ROUND
        paint.strokeCap = android.graphics.Paint.Cap.ROUND
        setTextColor(outlineColor)
        super.onDraw(canvas)

        paint.style = android.graphics.Paint.Style.FILL
        paint.strokeWidth = originalStroke
        setTextColor(originalColor)
        super.onDraw(canvas)

        paint.style = originalStyle
    }
}

private fun isHttp404(error: PlaybackException): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            Log.e("AnimenosubPlayer", "HTTP_RESPONSE code=${cause.responseCode} url=${cause.dataSpec.uri}")
            if (cause.responseCode == 404) return true
        }
        cause = cause.cause
    }
    return false
}

private fun isAnimenosubM3u8(url: String?): Boolean {
    val value = url.orEmpty().lowercase(Locale.ROOT)
    return value.contains(".m3u8") || value.contains("m3u8")
}

@Composable
fun PlayerScreen(
    anime: Anime,
    episode: Episode,
    vm: AnimeViewModel,
    back: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = context as? Activity
    val isOffline by vm.isOffline.collectAsState()
    val isInPictureInPicture = MainActivity.isInPictureInPicture

    // 재생을 시작한 작품은 시청기록에서 카탈로그 전체 목록 없이도 복원할 수 있도록
    // 작품 메타데이터를 영구 캐시한다.
    LaunchedEffect(anime.id) {
        vm.cacheAnime(context, anime)
    }
    
    var currentEpisode by remember(episode) { mutableStateOf(episode) }
    
    var isFullScreen by rememberSaveable { mutableStateOf(true) }
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var resolvedVideoPageUrl by remember { mutableStateOf<String?>(null) }
    var subtitlesUrl by remember { mutableStateOf<String?>(null) }
    // Linkkf VTT 주소는 한 번 발견되면 자막 소스를 Kairan으로 바꿔도 유지한다.
    // 그래야 다시 Linkkf VTT를 선택했을 때 재탐색 없이 즉시 전환할 수 있다.
    var linkkfSubtitleUrl by remember(anime.id, currentEpisode.number) { mutableStateOf<String?>(currentEpisode.vttUrl) }
    var subtitleSource by remember { mutableStateOf("none") }
    var kairanSubtitleResolved by remember { mutableStateOf(false) }
    var csoraSubtitleResolved by remember { mutableStateOf(false) }
    var discoveredCsoraAssPath by remember { mutableStateOf<String?>(null) }
    var showCsoraAssPrompt by remember { mutableStateOf(false) }
    // 재생 중 백그라운드에서 Kairan ASS를 찾았을 때만 조용히 표시하는 안내창 상태
    var discoveredKairanAssPath by remember(anime.id, currentEpisode.number) { mutableStateOf<String?>(null) }
    var showKairanAssPrompt by remember(anime.id, currentEpisode.number) { mutableStateOf(false) }
    var kairanAssPromptHandled by remember(anime.id, currentEpisode.number) { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    
    var isAutoPlayEnabled by rememberSaveable { mutableStateOf(true) }
    var isAutoSkipEnabled by rememberSaveable { mutableStateOf(true) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var isPlayerLocked by rememberSaveable { mutableStateOf(false) }
    var showLockedButton by remember { mutableStateOf(false) }
    var lockedButtonRequest by remember { mutableIntStateOf(0) }
    var chapterSkipSegments by remember { mutableStateOf<List<ChapterSkipSegment>>(emptyList()) }
    var activeChapterSkipSegment by remember { mutableStateOf<ChapterSkipSegment?>(null) }
    var chapterAnalysisStatus by remember { mutableStateOf<String?>(null) }
    var chapterAnalysisVisible by remember { mutableStateOf(false) }
    var buttonChapterSkipSegment by remember { mutableStateOf<ChapterSkipSegment?>(null) }
    var chapterSkipEnteredAtMs by remember { mutableLongStateOf(-1L) }
    var skippedChapterSkipKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var skipEpisodeKey by remember { mutableStateOf<String?>(null) }
    var suppressProgressSaveForEpisode by remember { mutableStateOf<String?>(null) }

    var subtitleSizePercent by rememberSaveable { mutableFloatStateOf(vm.playerSettings.subtitleSize) }
    var subtitleSizeText by rememberSaveable { mutableStateOf(vm.playerSettings.subtitleSize.toInt().toString()) }
    var syncOffsetMs by rememberSaveable { mutableLongStateOf(vm.playerSettings.syncOffsetMs) }
    var subtitleBottomPaddingFraction by rememberSaveable {
        mutableFloatStateOf(vm.playerSettings.subtitleBottomPaddingFraction)
    }
    var subtitleSourcePreference by rememberSaveable {
        mutableStateOf(vm.playerSettings.subtitleSourcePreference)
    }
    var syncOffsetText by rememberSaveable {
        mutableStateOf(vm.playerSettings.syncOffsetMs.toString())
    }
    var isVttStyleEnabled by rememberSaveable { mutableStateOf(true) }
    var vttBold by rememberSaveable { mutableStateOf(vm.playerSettings.vttBold) }
    var vttOutlineWidth by rememberSaveable { mutableFloatStateOf(vm.playerSettings.vttOutlineWidth) }
    var vttCueText by remember { mutableStateOf("") }
    var vttOverlayRef by remember { mutableStateOf<VttStrokeTextView?>(null) }
    var customTypeface by remember { mutableStateOf<Typeface?>(null) }
    var customFontName by remember { mutableStateOf<String?>(null) }
    var selectedSubtitleFontPath by remember(anime.id, subtitleSource) { mutableStateOf<String?>(null) }

    LaunchedEffect(anime.id, subtitleSource) {
        selectedSubtitleFontPath = if (subtitleSource == "kairan" || subtitleSource == "csora") {
            SubtitleStore.getSelectedFont(context, anime.id, subtitleSource)
        } else null
    }

    var parsedStreamingQualities by remember { mutableStateOf<List<StreamQuality>>(emptyList()) }
    var selectedStreamingQuality by remember { mutableStateOf<StreamQuality?>(null) }
    var pendingSeekPositionMs by remember { mutableLongStateOf(-1L) }
    // 수동 회차 전환 시 새 회차의 기록을 미리 고정한다.
    var pendingEpisodeProgress by remember { mutableStateOf<Float?>(null) }
    var pendingEpisodeProgressEpisodeId by remember { mutableStateOf<String?>(null) }

    var exoQualities by remember { mutableStateOf<List<ExoVideoQualityOption>>(emptyList()) }
    var selectedQualityOption by remember { mutableStateOf<ExoVideoQualityOption?>(null) }
    var showPlayerSettingsDialog by remember { mutableStateOf(false) }
    var discoveredSubtitleFonts by remember { mutableStateOf<List<SubtitleAssetUtil.FontInfo>>(emptyList()) }
    var savedSubtitles by remember { mutableStateOf<List<SubtitleStore.SavedSubtitle>>(emptyList()) }
    var showSubtitleManager by remember { mutableStateOf(false) }
    var hasSavedOpEdAnalysis by remember(anime.id, currentEpisode.id) { mutableStateOf(false) }

    LaunchedEffect(anime.id, currentEpisode.id) {
        hasSavedOpEdAnalysis = withContext(Dispatchers.IO) {
            OfflineOpEdResultStore.load(context, anime.id, currentEpisode.id) != null
        }
    }

    LaunchedEffect(anime.title, subtitlesUrl, subtitleSource) {
        discoveredSubtitleFonts = withContext(Dispatchers.IO) {
            (SubtitleAssetUtil.listFonts(context, anime.title, "kairan") +
                SubtitleAssetUtil.listFonts(context, anime.title, "csora"))
                .distinctBy { it.path }
        }
    }

    // 재생 속도는 전역 플레이어 설정에 저장되어 플레이어/일반 설정 화면에서 함께 사용한다.
    val playbackSpeed = vm.playerSettings.playbackSpeed
    val playbackSpeedOptions = remember {
        listOf(0.1f, 0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val tempFile = File.createTempFile("custom_font", ".ttf", context.cacheDir)
                FileOutputStream(tempFile).use { output -> inputStream?.copyTo(output) }
                customTypeface = Typeface.createFromFile(tempFile)
                customFontName = "커스텀 폰트 적용됨"
                Toast.makeText(context, "폰트가 적용되었습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "폰트를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val subtitleFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val displayName = runCatching {
                    context.contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull() ?: "subtitle"

                val lowerName = displayName.lowercase(Locale.ROOT)
                val extension = when {
                    lowerName.endsWith(".ass") -> "ass"
                    lowerName.endsWith(".ssa") -> "ssa"
                    lowerName.endsWith(".srt") -> "srt"
                    lowerName.endsWith(".vtt") -> "vtt"
                    else -> null
                }

                if (extension == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "ASS, SSA, SRT, VTT 자막만 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val target = userSubtitleFile(context, anime.id, currentEpisode.number, extension, currentEpisode.displayNumber)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("자막 파일을 열 수 없습니다.")

                val updatedEpisode = currentEpisode.copy(vttUrl = target.absolutePath)
                OfflineStore.saveEpisode(
                    context = context,
                    animeId = anime.id,
                    episode = updatedEpisode
                )
                withContext(Dispatchers.Main) {
                    currentEpisode = updatedEpisode
                    subtitlesUrl = target.absolutePath
                    subtitleSource = "user"
                    kairanSubtitleResolved = true
                    Toast.makeText(
                        context,
                        "${currentEpisode.number}화 사용자 자막을 적용했습니다.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("Subtitle", "USER_SUBTITLE_IMPORT_FAILED", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "자막 파일을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val episodeList = remember(anime.id) { 
        vm.episodes(anime).sortedWith(compareBy<Episode> { it.number }.thenBy { it.displayNumber })
    }
    val currentIndex = remember(episodeList, currentEpisode.id, currentEpisode.displayNumber) {
        val exactIdIndex = episodeList.indexOfFirst { it.id == currentEpisode.id }
        if (exactIdIndex >= 0) exactIdIndex
        else episodeList.indexOfFirst {
            it.displayNumber.equals(currentEpisode.displayNumber, ignoreCase = true) &&
                it.number == currentEpisode.number
        }
    }
    val prevEpisode = remember(episodeList, currentIndex) {
        if (currentIndex > 0) episodeList.getOrNull(currentIndex - 1) else null
    }
    val nextEpisode = remember(episodeList, currentIndex) {
        if (currentIndex >= 0 && currentIndex < episodeList.size - 1) episodeList.getOrNull(currentIndex + 1) else null
    }

    val currentNextEpisode by rememberUpdatedState(nextEpisode)
    val currentAutoPlay by rememberUpdatedState(isAutoPlayEnabled)
    val currentEpisodeState by rememberUpdatedState(currentEpisode)

    val isDownloaded = remember(anime.id, currentEpisode.id, currentEpisode.displayNumber) {
        vm.isEpisodeDownloaded(anime.id, currentEpisode)
    }

    var offlineEp by remember { mutableStateOf<Episode?>(null) }
    LaunchedEffect(anime.id, currentEpisode.id, currentEpisode.displayNumber) {
        offlineEp = OfflineStore.getEpisode(context, anime.id, currentEpisode)
    }

    // 다운로드된 회차를 온라인 상태에서 재생하는 경우에도 VTT가 로컬에 없으면
    // 원본 Linkkf VTT 주소를 이용해 즉시 로컬 자막을 보충한다. ASS가 존재하더라도
    // VTT 캐시는 별도로 유지하며, 이후 자막 소스 선택에서 항상 사용할 수 있다.
    LaunchedEffect(
        anime.id,
        currentEpisode.id,
        currentEpisode.number,
        currentEpisode.displayNumber,
        isDownloaded,
        isOffline,
        offlineEp?.vttUrl,
        currentEpisode.vttUrl,
        linkkfSubtitleUrl,
        subtitleSourcePreference
    ) {
        if (!isDownloaded || isOffline) return@LaunchedEffect

        val existingLocalVtt = withContext(Dispatchers.IO) {
            SubtitleStore.get(
                context,
                anime.id,
                currentEpisode.displayNumber,
                currentEpisode.number,
                "linkkf"
            )
        }?.takeIf { File(it).isFile && (it.endsWith(".vtt", true) || it.endsWith(".srt", true)) }
            ?: offlineEp?.vttUrl?.takeIf {
                File(it).isFile && (it.endsWith(".vtt", true) || it.endsWith(".srt", true))
            }

        if (existingLocalVtt != null) {
            Log.d("Subtitle", "OFFLINE_EPISODE_VTT_ALREADY_LOCAL episode=${currentEpisode.number} path=$existingLocalVtt")
            return@LaunchedEffect
        }

        var remoteVtt = sequenceOf(
            linkkfSubtitleUrl,
            currentEpisode.vttUrl,
            offlineEp?.vttUrl
        ).firstOrNull {
            !it.isNullOrBlank() &&
                (it.startsWith("http://") || it.startsWith("https://")) &&
                !it.startsWith("file://")
        }

        // 다운로드된 영상은 이미 로컬 m3u8/mp4를 가지고 있으므로
        // StreamUrlExtractor가 다시 실행되지 않는다. 이 경우 현재 Episode에
        // VTT URL이 없으면 Linkkf 회차 페이지를 다시 조회해서 VTT를 찾는다.
        if (remoteVtt.isNullOrBlank()) {
            val linkkfEpisode = try {
                vm.findLinkkfEpisode(anime, currentEpisode)
            } catch (e: Exception) {
                Log.w("Subtitle", "OFFLINE_EPISODE_LINKKF_LOOKUP_FAILED episode=${currentEpisode.number}", e)
                null
            }
            remoteVtt = linkkfEpisode?.vttUrl?.takeIf {
                it.startsWith("http://") || it.startsWith("https://")
            }
            if (!remoteVtt.isNullOrBlank()) {
                linkkfSubtitleUrl = remoteVtt
                Log.d(
                    "Subtitle",
                    "OFFLINE_EPISODE_VTT_FOUND_FROM_LINKKF episode=${currentEpisode.number} url=$remoteVtt"
                )
            }
        }

        remoteVtt = remoteVtt ?: return@LaunchedEffect

        Log.d(
            "Subtitle",
            "OFFLINE_EPISODE_VTT_DOWNLOAD_START episode=${currentEpisode.number} url=$remoteVtt"
        )

        val downloadedPath = try {
            downloadSubtitleFile(
                context = context,
                animeId = anime.id,
                episodeNumber = currentEpisode.number,
                episodeKey = currentEpisode.displayNumber,
                vttUrl = remoteVtt
            )
        } catch (e: Exception) {
            Log.w("Subtitle", "OFFLINE_EPISODE_VTT_DOWNLOAD_FAILED episode=${currentEpisode.number}", e)
            null
        }

        if (downloadedPath.isNullOrBlank()) return@LaunchedEffect

        withContext(Dispatchers.IO) {
            SubtitleStore.save(
                context, anime.id, currentEpisode.displayNumber, currentEpisode.number,
                "linkkf", downloadedPath
            )
            val savedEpisode = offlineEp ?: OfflineStore.getEpisode(context, anime.id, currentEpisode)
            if (savedEpisode != null) {
                OfflineStore.saveEpisode(
                    context = context,
                    animeId = anime.id,
                    episode = savedEpisode.copy(vttUrl = downloadedPath)
                )
            }
        }

        linkkfSubtitleUrl = remoteVtt
        subtitlesUrl = downloadedPath
        subtitleSource = "linkkf-vtt"
        Log.d(
            "Subtitle",
            "OFFLINE_EPISODE_VTT_DOWNLOAD_DONE episode=${currentEpisode.number} path=$downloadedPath"
        )
    }

    // 재생 화면에서는 Android 상태표시줄/내비게이션바를 완전히 숨긴다.
    // 사용자가 위에서 아래로 스와이프해 시스템 상태표시줄을 잠깐 띄운 뒤에도
    // 재생 화면이 다시 immersive 상태로 돌아가도록 시스템 UI 변경을 감시한다.
    DisposableEffect(activity) {
        val window = activity?.window
        if (window == null) {
            onDispose { }
        } else {
            val decorView = window.decorView
            val controller = WindowCompat.getInsetsController(window, decorView)
            val hiddenTypes = WindowInsetsCompat.Type.statusBars() or
                WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.captionBar()

            fun hidePlayerSystemBars() {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(hiddenTypes)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }

            WindowCompat.setDecorFitsSystemWindows(window, false)

            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hidePlayerSystemBars()

            @Suppress("DEPRECATION")
            decorView.setOnSystemUiVisibilityChangeListener {
                // 상태표시줄이 사용자의 스와이프로 다시 나타난 경우 즉시 다시 숨긴다.
                hidePlayerSystemBars()
            }

            ViewCompat.setOnApplyWindowInsetsListener(decorView) { view, insets ->
                if (insets.isVisible(hiddenTypes)) {
                    view.post { hidePlayerSystemBars() }
                }
                insets
            }

            onDispose {
                @Suppress("DEPRECATION")
                decorView.setOnSystemUiVisibilityChangeListener(null)
                ViewCompat.setOnApplyWindowInsetsListener(decorView, null)
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
        }
    }

    // Animenosub 인증/쿠키 로직은 현재 비활성화한다.
    // 스트림은 StreamUrlExtractor의 WebView에서 직접 탐지하고,
    // 사용자가 PlayerScreen을 나가려는 경우에만 기존 Back 동작을 수행한다.
    BackHandler { back() }

    // Restore a user-imported subtitle saved in OfflineStore even when the
    // current episode itself is not downloaded. This makes the custom subtitle
    // available again after leaving/reopening the player and also during offline playback.
    LaunchedEffect(anime.id, currentEpisode.displayNumber) {
        val stored = withContext(Dispatchers.IO) {
            OfflineStore.getEpisode(context, anime.id, currentEpisode)
        }
        val storedSubtitle = stored?.vttUrl
        if (isLocalUserSubtitlePath(storedSubtitle)) {
            currentEpisode = currentEpisode.copy(vttUrl = storedSubtitle)
            subtitlesUrl = storedSubtitle
            subtitleSource = "user"
            Log.d("Subtitle", "RESTORE_USER_SUBTITLE path=$storedSubtitle episode=${currentEpisode.number}")
        }
    }

    LaunchedEffect(currentEpisode, isDownloaded, offlineEp) {
        isLoading = true
        streamUrl = null
        subtitlesUrl = null
        linkkfSubtitleUrl = currentEpisode.vttUrl
        subtitleSource = "none"
        kairanSubtitleResolved = false
        parsedStreamingQualities = emptyList()
        selectedStreamingQuality = null
        exoQualities = emptyList()
        selectedQualityOption = null
        pendingSeekPositionMs = -1L
        chapterSkipSegments = emptyList()
        activeChapterSkipSegment = null
        buttonChapterSkipSegment = null
        chapterAnalysisStatus = null
        chapterAnalysisVisible = false
        chapterSkipEnteredAtMs = -1L
        skippedChapterSkipKeys = emptySet()
        skipEpisodeKey = null
        suppressProgressSaveForEpisode = null
        
        val targetUrl = if (isDownloaded) {
            offlineEp?.videoUrl ?: currentEpisode.videoUrl
        } else if (!isOffline) {
            if (vm.playerSettings.videoSourcePreference == "animenosub") {
                // Animenosub episode links are already resolved by AnimenosubParser.
                // Use the exact episode URL stored on the Episode instead of resolving
                // the series/title again. This is important for pages whose player is
                // embedded directly in the episode page/iframe.
                currentEpisode.videoUrl
            } else {
                currentEpisode.videoUrl
            }
        } else {
            null
        }
        resolvedVideoPageUrl = targetUrl

        if (!targetUrl.isNullOrBlank()) {
            if (targetUrl.contains(".m3u8") || targetUrl.contains(".mp4") || isDownloaded) {
                streamUrl = targetUrl

                val storedSubtitle = offlineEp?.vttUrl ?: currentEpisode.vttUrl
                // A subtitle stored inside the episode record is already associated with
                // this episode. Do not reject a valid VTT merely because its filename
                // does not contain an episode number.
                val localStoredSubtitle = storedSubtitle?.takeIf { path ->
                    path.startsWith("/") && File(path).isFile
                }
                val localUserSubtitle = localStoredSubtitle?.takeIf { isLocalUserSubtitlePath(it) }
                val localLinkkf = withContext(Dispatchers.IO) {
                    SubtitleStore.get(context, anime.id, currentEpisode.displayNumber, currentEpisode.number, "linkkf")
                } ?: localStoredSubtitle?.takeIf { it.endsWith(".vtt", true) || it.endsWith(".srt", true) }
                val localKairan = withContext(Dispatchers.IO) {
                    SubtitleStore.get(context, anime.id, currentEpisode.displayNumber, currentEpisode.number, "kairan")
                } ?: findLocalKairanAssSubtitle(context, anime.title, currentEpisode.number, localStoredSubtitle)
                val localCsora = withContext(Dispatchers.IO) {
                    SubtitleStore.get(context, anime.id, currentEpisode.displayNumber, currentEpisode.number, "csora")
                }
                subtitlesUrl = when {
                    localUserSubtitle != null -> localUserSubtitle
                    subtitleSourcePreference == "linkkf" -> localLinkkf
                    subtitleSourcePreference == "kairan" -> localKairan ?: localCsora ?: localLinkkf
                    subtitleSourcePreference == "csora" -> localCsora ?: localKairan ?: localLinkkf
                    else -> localLinkkf
                }
                subtitleSource = when {
                    localUserSubtitle != null -> "user"
                    subtitleSourcePreference == "linkkf" && localLinkkf != null -> "linkkf-vtt"
                    subtitlesUrl == localKairan && localKairan != null -> "kairan"
                    subtitlesUrl == localCsora && localCsora != null -> "csora"
                    subtitlesUrl == localLinkkf && localLinkkf != null -> "linkkf-vtt"
                    else -> "none"
                }
                kairanSubtitleResolved = true
                isLoading = false
            }
        } else if (isOffline) {
            isLoading = false
        }
    }

    // Animenosub 인증 WebView 자동 표시 로직은 비활성화했다.
    // 스트림 탐색은 아래 StreamUrlExtractor가 즉시 수행한다.

    LaunchedEffect(anime.id, anime.title, currentEpisode.displayNumber, isOffline, isDownloaded, currentEpisode.vttUrl, linkkfSubtitleUrl, subtitleSourcePreference) {
        kairanSubtitleResolved = false

        val userSubtitle = currentEpisode.vttUrl?.takeIf { isLocalUserSubtitlePath(it) }
        if (userSubtitle != null) {
            subtitlesUrl = userSubtitle
            subtitleSource = "user"
            kairanSubtitleResolved = true
            Log.d("Subtitle", "USE_USER_SUBTITLE path=$userSubtitle episode=${currentEpisode.number}")
            return@LaunchedEffect
        }

        val storedSubtitle = offlineEp?.vttUrl ?: currentEpisode.vttUrl
        val localStoredSubtitle = storedSubtitle?.takeIf { path ->
            if (!path.startsWith("/") || !File(path).isFile) return@takeIf false
            val ext = File(path).extension.lowercase(java.util.Locale.ROOT)
            if (ext == "vtt" || ext == "srt") {
                // A VTT/SRT stored in the episode record is already associated with this episode.
                true
            } else {
                SubtitleStore.pathMatchesEpisode(path, currentEpisode.number)
            }
        }

        if (isOffline || isDownloaded) {
            val localLinkkf = withContext(Dispatchers.IO) {
                SubtitleStore.get(context, anime.id, currentEpisode.displayNumber, currentEpisode.number, "linkkf")
            } ?: localStoredSubtitle?.takeIf {
                it.endsWith(".vtt", true) || it.endsWith(".srt", true) || it.contains("/sub_${anime.id}_${currentEpisode.displayNumber}.")
            }
            val localKairan = withContext(Dispatchers.IO) {
                SubtitleStore.get(context, anime.id, currentEpisode.displayNumber, currentEpisode.number, "kairan")
            } ?: findLocalKairanAssSubtitle(context, anime.title, currentEpisode.number, localStoredSubtitle)
            val localCsora = withContext(Dispatchers.IO) {
                SubtitleStore.get(context, anime.id, currentEpisode.displayNumber, currentEpisode.number, "csora")
            }
            when (subtitleSourcePreference) {
                "kairan" -> {
                    subtitlesUrl = localKairan ?: localCsora ?: localLinkkf
                    subtitleSource = when { localKairan != null -> "kairan"; localCsora != null -> "csora"; localLinkkf != null -> "linkkf-vtt"; else -> "none" }
                }
                "csora" -> {
                    subtitlesUrl = localCsora ?: localKairan ?: localLinkkf
                    subtitleSource = when { localCsora != null -> "csora"; localKairan != null -> "kairan"; localLinkkf != null -> "linkkf-vtt"; else -> "none" }
                }
                else -> {
                    // Linkkf VTT를 명시적으로 선택한 경우 ASS로 절대 자동 대체하지 않는다.
                    // 로컬 VTT가 아직 없다면 위의 다운로드 effect가 온라인 VTT를 받아온다.
                    subtitlesUrl = localLinkkf
                    subtitleSource = if (localLinkkf != null) "linkkf-vtt" else "none"
                    Log.d(
                        "Subtitle",
                        "LINKKF_VTT_SELECTED episode=${currentEpisode.number} local=${localLinkkf != null}"
                    )
                }
            }
            kairanSubtitleResolved = true
            return@LaunchedEffect
        }

        // 스트리밍에서는 선택한 소스를 사용한다. Kairan은 필요할 때 캐시하고,
        // Linkkf는 StreamUrlExtractor가 발견한 VTT 주소를 사용한다.
        if (subtitleSourcePreference == "kairan") {
            val result = try {
                withContext(Dispatchers.IO) {
                    KairanSubtitleService.findSubtitle(context, anime.title, currentEpisode.number, currentEpisode.displayNumber)
                }
            } catch (e: Exception) {
                Log.w("Kairan", "SUBTITLE_SEARCH_FAILED episode=${currentEpisode.number}", e)
                null
            }

            when (result) {
                is KairanSubtitleResult.DirectFile -> {
                    subtitlesUrl = result.path
                    subtitleSource = "kairan"
                    Log.d("Kairan", "USE_KAIRAN path=${result.path} episode=${currentEpisode.number}")
                }
                null -> {
                    val fallback = linkkfSubtitleUrl ?: currentEpisode.vttUrl
                    subtitlesUrl = fallback
                    subtitleSource = if (!fallback.isNullOrBlank()) "linkkf-vtt" else "none"
                    Log.d("Kairan", "NO_KAIRAN_SUBTITLE episode=${currentEpisode.number}; USE_LINKKF_VTT_FALLBACK=${!fallback.isNullOrBlank()}")
                }
            }
        } else if (subtitleSourcePreference == "csora") {
            // Switching to Csora must replace the currently displayed subtitle immediately.
            subtitleSource = "none"
            subtitlesUrl = null
            val result = try {
                withContext(Dispatchers.IO) {
                    CsoraSubtitleService.findSubtitle(context, anime.title, currentEpisode.number, currentEpisode.displayNumber)
                }
            } catch (e: Exception) {
                Log.w("Csora", "SUBTITLE_SEARCH_FAILED episode=${currentEpisode.number}", e)
                null
            }
            if (result is KairanSubtitleResult.DirectFile) {
                subtitlesUrl = result.path
                subtitleSource = "csora"
                Log.d("Csora", "USE_CSORA path=${result.path} episode=${currentEpisode.number}")
            } else {
                val fallback = linkkfSubtitleUrl ?: currentEpisode.vttUrl
                subtitlesUrl = fallback
                subtitleSource = if (!fallback.isNullOrBlank()) "linkkf-vtt" else "none"
                Log.d("Csora", "NO_CSORA_SUBTITLE episode=${currentEpisode.number}; USE_LINKKF_VTT_FALLBACK=${!fallback.isNullOrBlank()}")
            }
        } else {
            val linkkf = linkkfSubtitleUrl ?: currentEpisode.vttUrl
            subtitlesUrl = linkkf
            subtitleSource = if (!linkkf.isNullOrBlank()) "linkkf-vtt" else "none"
        }

        kairanSubtitleResolved = true
    }

    // 기본 자막이 Linkkf일 때도 재생을 막지 않고 Kairan ASS를 백그라운드에서 찾는다.
    // 발견되면 로컬 캐시에 저장된 경로를 유지하고, 사용자에게만 짧게 전환 여부를 묻는다.
    LaunchedEffect(anime.id, anime.title, currentEpisode.displayNumber, isOffline, isDownloaded) {
        if (isOffline || isDownloaded || kairanAssPromptHandled) return@LaunchedEffect
        if (subtitleSource == "user" || subtitleSourcePreference == "kairan") return@LaunchedEffect

        val result = try {
            withContext(Dispatchers.IO) {
                KairanSubtitleService.findSubtitle(context, anime.title, currentEpisode.number, currentEpisode.displayNumber)
            }
        } catch (e: Exception) {
            Log.w("Kairan", "BACKGROUND_ASS_SEARCH_FAILED episode=${currentEpisode.number}", e)
            null
        }

        if (result is KairanSubtitleResult.DirectFile) {
            discoveredKairanAssPath = result.path
            kairanAssPromptHandled = true
            if (subtitleSource != "kairan") {
                showKairanAssPrompt = true
                Log.d("Kairan", "BACKGROUND_ASS_FOUND path=${result.path} episode=${currentEpisode.number}")
            }
        }
    }

    // 안내는 너무 빨리 사라지지 않도록 6초 동안 표시한다.
    LaunchedEffect(showKairanAssPrompt) {
        if (showKairanAssPrompt) {
            delay(6000L)
            showKairanAssPrompt = false
        }
    }

    // Csora ASS도 Kairan과 동일하게 백그라운드에서 발견하면 안내한다.
    // 현재 자막을 즉시 바꾸지 않고 사용자가 선택할 수 있도록 한다.
    LaunchedEffect(anime.id, anime.title, currentEpisode.displayNumber, isOffline, isDownloaded) {
        if (isOffline || isDownloaded || csoraSubtitleResolved) return@LaunchedEffect
        if (subtitleSource == "user" || subtitleSourcePreference == "csora") return@LaunchedEffect

        val result = try {
            withContext(Dispatchers.IO) {
                CsoraSubtitleService.findSubtitle(context, anime.title, currentEpisode.number, currentEpisode.displayNumber)
            }
        } catch (e: Exception) {
            Log.w("Csora", "BACKGROUND_ASS_SEARCH_FAILED episode=${currentEpisode.number}", e)
            null
        }

        if (result is KairanSubtitleResult.DirectFile) {
            discoveredCsoraAssPath = result.path
            if (subtitleSource != "csora") {
                showCsoraAssPrompt = true
                Log.d("Csora", "BACKGROUND_ASS_FOUND path=${result.path} episode=${currentEpisode.number}")
            }
        }
        csoraSubtitleResolved = true
    }

    LaunchedEffect(showCsoraAssPrompt) {
        if (showCsoraAssPrompt) {
            delay(6000L)
            showCsoraAssPrompt = false
        }
    }

    // ASS/SSA is rendered by libass instead of Media3's normal SubtitleView.
    // OVERLAY_OPEN_GL keeps the libass bitmap on a dedicated overlay path so
    // positioning, styles, animations, karaoke, borders, shadows, etc. stay
    // faithful to the original ASS script.
    val assHandler = remember(context) {
        AssHandler(
            renderType = AssRenderType.OVERLAY_OPEN_GL,
            config = AssHandlerConfig(
                maxRenderPixels = 0
            )
        )
    }

    val assSubtitleParserFactory = remember(assHandler) {
        AssSubtitleParserFactory(assHandler)
    }

    val trackSelector = remember(context) {
        DefaultTrackSelector(context)
    }

    val exoPlayer = remember(context, trackSelector, assHandler) {
        val defaultRenderersFactory = DefaultRenderersFactory(context)
        val assRenderersFactory = AssRenderersFactory(
            assHandler = assHandler,
            renderersFactory = defaultRenderersFactory
        )

        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setRenderersFactory(assRenderersFactory)
            .build()
    }

    DisposableEffect(exoPlayer, assHandler) {
        Log.d("Subtitle", "LIBASS_INIT renderType=OVERLAY_OPEN_GL maxRenderPixels=0")
        assHandler.init(exoPlayer)
        onDispose {
            Log.d("Subtitle", "LIBASS_RELEASE")
            assHandler.release()
        }
    }

    // 속도 변경은 Media3/ExoPlayer에 즉시 반영한다.
    LaunchedEffect(exoPlayer, playbackSpeed) {
        exoPlayer.setPlaybackSpeed(playbackSpeed)
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                val text = cueGroup.cues
                    .asSequence()
                    .mapNotNull { it.text?.toString()?.takeIf { value -> value.isNotBlank() } }
                    .joinToString("\n")
                vttCueText = text
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                MainActivity.isVideoPlaying = isPlaying
                val window = activity?.window
                if (isPlaying) {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            MainActivity.isVideoPlaying = false
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 다음화/이전화 버튼은 단순히 currentEpisode만 바꾸지 않고,
    // 현재 재생 상태를 먼저 저장한 뒤 새 회차의 기록을 로드하도록 한다.
    // PlayerView의 controller가 이전 ForwardingPlayer 인스턴스를 잠깐 잡고 있어도
    // 실제 회차 전환은 항상 이 경로를 거치게 한다.
    fun switchEpisodeFromPlayer(target: Episode) {
        if (target.id == currentEpisode.id) return

        val duration = exoPlayer.duration
        if (duration > 0L && duration != C.TIME_UNSET) {
            val progress = (exoPlayer.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            vm.updateProgress(
                context = context,
                animeId = anime.id,
                episodeNumber = currentEpisode.number,
                episodeKey = currentEpisode.displayNumber,
                progress = progress,
                episodeId = currentEpisode.id
            )
        }

        Log.d(
            "PlayerEpisode",
            "MANUAL_SWITCH fromId=${currentEpisode.id} fromNo=${currentEpisode.number} fromKey=${currentEpisode.displayNumber} " +
                "toId=${target.id} toNo=${target.number} toKey=${target.displayNumber}"
        )
        // 새 회차 기록은 현재 플레이어 상태와 분리해서 전환 전에 확정한다.
        pendingEpisodeProgressEpisodeId = target.id
        pendingEpisodeProgress = vm.getProgress(
            anime.id,
            target.displayNumber,
            target.number,
            target.id
        )?.progress
        Log.d(
            "PlayerEpisode",
            "TARGET_PROGRESS targetId=${target.id} targetNo=${target.number} " +
                "targetKey=${target.displayNumber} progress=${pendingEpisodeProgress}"
        )
        pendingSeekPositionMs = -1L
        exoPlayer.stop()
        currentEpisode = target
    }

    val forwardingPlayer = remember(exoPlayer, prevEpisode, nextEpisode) {
        object : ForwardingPlayer(exoPlayer) {
            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> nextEpisode != null
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> prevEpisode != null
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun hasNextMediaItem(): Boolean = nextEpisode != null
            override fun hasPreviousMediaItem(): Boolean = prevEpisode != null

            override fun seekToNext() { nextEpisode?.let(::switchEpisodeFromPlayer) }
            override fun seekToPrevious() { prevEpisode?.let(::switchEpisodeFromPlayer) }
            override fun seekToNextMediaItem() { nextEpisode?.let(::switchEpisodeFromPlayer) }
            override fun seekToPreviousMediaItem() { prevEpisode?.let(::switchEpisodeFromPlayer) }
        }
    }

    DisposableEffect(currentEpisode) {
        val episodeNumberForSave = currentEpisode.number
        onDispose {
            if (suppressProgressSaveForEpisode == currentEpisode.id) return@onDispose
            val duration = exoPlayer.duration
            if (duration > 0 && duration != C.TIME_UNSET) {
                val progress = (exoPlayer.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                vm.updateProgress(
                    context = context,
                    animeId = anime.id,
                    episodeNumber = episodeNumberForSave,
                    episodeKey = currentEpisode.displayNumber,
                    progress = progress,
                    episodeId = currentEpisode.id
                )
            }
        }
    }

    // PlayerScreen이 실제로 종료될 때만 화면 방향과 시스템 바를 복원한다.
    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(
                    window,
                    window.decorView
                ).show(WindowInsetsCompat.Type.systemBars())
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    // ExoPlayer lifecycle은 화면 방향과 분리해서 관리한다.
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    DisposableEffect(exoPlayer, currentEpisode.number, streamUrl) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val completedEpisode = currentEpisodeState.number
                    suppressProgressSaveForEpisode = currentEpisodeState.id
                    vm.updateProgress(
                        context = context,
                        animeId = anime.id,
                        episodeNumber = completedEpisode,
                        episodeKey = currentEpisodeState.displayNumber,
                        progress = 0f,
                        episodeId = currentEpisodeState.id
                    )
                    if (currentAutoPlay && currentNextEpisode != null) {
                        currentEpisode = currentNextEpisode!!
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val qualityList = mutableListOf<ExoVideoQualityOption>()
                qualityList.add(ExoVideoQualityOption("자동 (Auto)", 0, 0, isAuto = true))

                for (groupIndex in 0 until tracks.groups.size) {
                    val trackGroup = tracks.groups[groupIndex]
                    if (trackGroup.type == C.TRACK_TYPE_VIDEO) {
                        for (trackIndex in 0 until trackGroup.length) {
                            val format = trackGroup.getTrackFormat(trackIndex)
                            val height = format.height
                            val width = format.width

                            if (height > 0) {
                                val label = when {
                                    height >= 2160 -> "4K (2160p)"
                                    height >= 1440 -> "QHD (1440p)"
                                    height >= 1080 -> "1080p"
                                    height >= 720 -> "720p"
                                    else -> "${height}p"
                                }
                                qualityList.add(ExoVideoQualityOption(label, width, height))
                            }
                        }
                    }
                }
                exoQualities = qualityList.distinctBy { it.label }

                val prefQuality = vm.playerSettings.defaultQuality
                if (prefQuality != "Auto") {
                    val targetOpt = exoQualities.firstOrNull { it.label.contains(prefQuality) }
                    if (targetOpt != null && selectedQualityOption == null) {
                        selectedQualityOption = targetOpt
                        val builder = trackSelector.buildUponParameters()
                        builder.setMaxVideoSize(targetOpt.width, targetOpt.height)
                            .setMinVideoSize(targetOpt.width, targetOpt.height)
                        trackSelector.setParameters(builder)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(
                    "AnimenosubPlayer",
                    "PlaybackError source=${vm.playerSettings.videoSourcePreference} code=${error.errorCodeName} message=${error.message} streamUrl=$streamUrl",
                    error
                )

                // Animenosub: M3U8을 찾은 뒤 실제 재생 단계에서 실패하면
                // HTTP 404 여부에 의존하지 않고 인증 WebView를 한 번 표시한다.
                // Media3에서는 HLS 내부 요청 오류가 PlaybackException으로 래핑되거나
                // AnalyticsListener에 전달되지 않는 경우가 있으므로, 이 단계에서는
                // "Animenosub + M3U8 재생 실패" 자체를 인증 필요 신호로 사용한다.
                Toast.makeText(context, "재생 오류: ${error.errorCodeName}", Toast.LENGTH_SHORT).show()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(currentEpisode.id, streamUrl, subtitlesUrl, subtitleSource, syncOffsetMs, isOffline, selectedSubtitleFontPath) {
        val url = streamUrl ?: return@LaunchedEffect
        val isLocalFile = url.startsWith("file://") || url.startsWith("/")

        val mediaSourceFactory = if (isLocalFile) {
            val localDataSourceFactory = DefaultDataSource.Factory(context)
            DefaultMediaSourceFactory(context).setDataSourceFactory(localDataSourceFactory)
        } else {
            val parsedUri = Uri.parse(url)
            val refererHost = if (!parsedUri.host.isNullOrEmpty()) {
                "${parsedUri.scheme ?: "https"}://${parsedUri.host}/"
            } else {
                "https://linkkf.tv/"
            }
            val upstreamFactory = if (isOffline) {
                null
            } else {
                DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(
                        buildMap {
                            if (vm.playerSettings.videoSourcePreference == "animenosub") {
                                // HAR: master/index/segment requests use the signed master
                                // playlist URL as Referer when the M3U8 is opened directly.
                                put("Referer", url)
                                put("Accept", "*/*")
                                put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                                put("Cache-Control", "no-cache")
                                put("Pragma", "no-cache")
                                put("DNT", "1")
                            } else {
                                put("Referer", "https://play.sub3.top/")
                                put("Origin", "https://play.sub3.top")
                            }
                        }
                    )
            }
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(LilacApplication.downloadCache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheReadDataSourceFactory(FileDataSource.Factory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val dataSourceFactory = DefaultDataSource.Factory(context, cacheDataSourceFactory)
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(dataSourceFactory)
                .setSubtitleParserFactory(assSubtitleParserFactory)
        }

        val mediaItemUri = if (isLocalFile && !url.startsWith("file://")) {
            Uri.fromFile(File(url))
        } else {
            Uri.parse(url)
        }

        val mimeType = if (url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(mediaItemUri)
            .apply {
                if (mimeType != null) {
                    setMimeType(mimeType)
                }
            }

        if (!subtitlesUrl.isNullOrEmpty()) {
            val subPath = subtitlesUrl!!
            val lowerSubPath = subPath.lowercase(Locale.ROOT)
            val subtitleMimeType = when {
                lowerSubPath.contains(".ass") || lowerSubPath.contains(".ssa") -> MimeTypes.TEXT_SSA
                lowerSubPath.contains(".srt") -> MimeTypes.APPLICATION_SUBRIP
                else -> MimeTypes.TEXT_VTT
            }

            // Sync is always controlled by the user. Csora does not receive a
            // global offset because only some individual episodes may need tuning.
            val effectiveSyncOffsetMs = syncOffsetMs

            // libass does not automatically use arbitrary files extracted next to
            // an external ASS subtitle. Register Csora's extracted TTF/OTF/TTC
            // files with AssHandler before the subtitle track is created.
            if ((subtitleSource == "csora" || subtitleSource == "kairan") &&
                (lowerSubPath.endsWith(".ass") || lowerSubPath.endsWith(".ssa"))) {
                val loadedFonts = withContext(Dispatchers.IO) {
                    loadSubtitleFontsForSource(context, subPath, subtitleSource, assHandler, selectedSubtitleFontPath)
                }
                Log.d("Subtitle", "ASS_FONT_COUNT source=$subtitleSource loaded=$loadedFonts subtitle=$subPath")
            }

            // Existing sync controls create shifted files for WebVTT/SRT/ASS/SSA.
            val effectiveSubtitlePath = if (effectiveSyncOffsetMs != 0L) {
                withContext(Dispatchers.IO) {
                    prepareSyncedSubtitleFile(
                        context = context,
                        subtitlePath = subPath,
                        animeId = anime.id,
                        episodeNumber = currentEpisode.number,
                        episodeKey = currentEpisode.displayNumber,
                        offsetMs = effectiveSyncOffsetMs
                    )
                } ?: subPath
            } else subPath

            val fontSelectedPath = selectedSubtitleFontPath
                .takeIf { (subtitleSource == "kairan" || subtitleSource == "csora") && !it.isNullOrBlank() && File(it).isFile }
            val fontAppliedSubtitlePath = if (fontSelectedPath != null && (lowerSubPath.endsWith(".ass") || lowerSubPath.endsWith(".ssa"))) {
                SubtitleAssetUtil.prepareAssWithSelectedFont(effectiveSubtitlePath, fontSelectedPath)
            } else {
                effectiveSubtitlePath
            }

            val subUri = when {
                fontAppliedSubtitlePath.startsWith("http://") || fontAppliedSubtitlePath.startsWith("https://") ->
                    Uri.parse(fontAppliedSubtitlePath)
                fontAppliedSubtitlePath.startsWith("file://") ->
                    Uri.parse(fontAppliedSubtitlePath)
                else -> Uri.fromFile(File(fontAppliedSubtitlePath))
            }

            Log.d(
                "Subtitle",
                "LOAD source=$subtitleSource path=$subPath effective=$fontAppliedSubtitlePath " +
                    "mime=$subtitleMimeType syncOffsetMs=$syncOffsetMs effectiveSyncOffsetMs=$effectiveSyncOffsetMs " +
                    "selectedFont=${fontSelectedPath ?: "none"}"
            )

            val subtitleId =
                "${subtitleSource}-subtitle-${anime.id}-${currentEpisode.displayNumber}-${effectiveSyncOffsetMs}"

            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
                .setId(subtitleId)
                .setMimeType(subtitleMimeType)
                .setLanguage("ko")
                .setLabel(
                    when {
                        subtitleSource == "kairan" -> "Kairan ASS"
                        subtitleSource == "csora" -> "Csora ASS"
                        subtitleSource == "linkkf-vtt" -> "Linkkf VTT"
                        else -> "Subtitle"
                    }
                )
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))
        }

        var initialSeekDone = false
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !initialSeekDone) {
                    initialSeekDone = true
                    if (pendingSeekPositionMs >= 0L) {
                        exoPlayer.seekTo(pendingSeekPositionMs)
                        pendingSeekPositionMs = -1L
                    } else {
                        // 수동 전환에서 미리 읽은 대상 회차 기록을 최우선으로 사용한다.
                        // 없으면 이 LaunchedEffect가 준비한 회차의 기록만 다시 조회한다.
                        val pendingForCurrentEpisode = pendingEpisodeProgressEpisodeId == currentEpisode.id
                        val progressForThisEpisode = if (pendingForCurrentEpisode) {
                            pendingEpisodeProgress
                        } else {
                            vm.getProgress(
                                anime.id,
                                currentEpisode.displayNumber,
                                currentEpisode.number,
                                currentEpisode.id
                            )?.progress
                        }
                        Log.d(
                            "PlayerEpisode",
                            "RESTORE_PROGRESS id=${currentEpisode.id} number=${currentEpisode.number} " +
                                "key=${currentEpisode.displayNumber} pendingId=$pendingEpisodeProgressEpisodeId " +
                                "pendingMatched=$pendingForCurrentEpisode progress=$progressForThisEpisode"
                        )
                        pendingEpisodeProgress = null
                        pendingEpisodeProgressEpisodeId = null
                        if (progressForThisEpisode != null) {
                            val duration = exoPlayer.duration
                            if (duration > 0 && duration != C.TIME_UNSET) {
                                val seekPos = (progressForThisEpisode * duration).toLong().coerceAtLeast(0)
                                exoPlayer.seekTo(seekPos)
                            }
                        }
                    }

                }
            }
        }
        // This listener belongs to this exact episode/media preparation. Keep it
        // attached only while this LaunchedEffect is alive. Without explicit
        // removal, the listener from the previous episode remains attached and
        // can seek the newly loaded episode to the previous episode's history.
        exoPlayer.addListener(listener)
        try {
            exoPlayer.setMediaSource(mediaSourceFactory.createMediaSource(mediaItemBuilder.build()))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            awaitCancellation()
        } finally {
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(currentEpisode.id, streamUrl, currentEpisode.number, vm.playerSettings.offlineOpEdAnalysisEnabled) {
        val currentStreamUrl = streamUrl ?: return@LaunchedEffect

        chapterSkipSegments = emptyList()
        activeChapterSkipSegment = null
        buttonChapterSkipSegment = null
        chapterSkipEnteredAtMs = -1L
        skippedChapterSkipKeys = emptySet()
        // OP/ED analysis is intentionally available only for episodes stored offline.
        // Online playback does not perform any OP/ED analysis.
        if (!isDownloaded) {
            chapterAnalysisStatus = null
            chapterAnalysisVisible = false
            Log.d("AniChapters", "ONLINE_ANALYSIS_DISABLED episode=${currentEpisode.number}")
            return@LaunchedEffect
        }

        if (!vm.playerSettings.offlineOpEdAnalysisEnabled) {
            chapterSkipSegments = emptyList()
            chapterAnalysisStatus = null
            chapterAnalysisVisible = false
            Log.d("AniChapters", "OFFLINE_ANALYSIS_DISABLED episode=${currentEpisode.number}")
            return@LaunchedEffect
        }

        // Start the next downloaded episode's analysis in the background.
        // It is deliberately fire-and-forget and never updates the playback UI.
        fun startNextBackgroundAnalysis() {
            val currentIndex = episodeList.indexOfFirst { it.id == currentEpisode.id }
            val nextEpisode = episodeList.getOrNull(currentIndex + 1) ?: return
            if (!LinkkfChapterService.isOfflineEpisodeCompleted(anime.id, nextEpisode)) return

            scope.launch(Dispatchers.Default) {
                runCatching {
                    val cachedNext = OfflineOpEdResultStore.load(context, anime.id, nextEpisode.id)
                    if (cachedNext != null) {
                        Log.d("AniChapters", "NEXT_CACHED episode=${nextEpisode.number} segments=${cachedNext.size}")
                        return@launch
                    }
                    Log.d("AniChapters", "NEXT_BACKGROUND_ANALYSIS_START episode=${nextEpisode.number}")
                    LinkkfChapterService.detectSkipSegmentsOffline(
                        context = context,
                        animeId = anime.id,
                        currentEpisode = nextEpisode,
                        episodes = episodeList,
                        episodeDurationSeconds = 0,
                        onStatus = { message -> Log.d("AniChapters", "NEXT_BACKGROUND $message") }
                    )
                    Log.d("AniChapters", "NEXT_BACKGROUND_ANALYSIS_DONE episode=${nextEpisode.number}")
                }.onFailure {
                    Log.e("AniChapters", "NEXT_BACKGROUND_ANALYSIS_FAILED episode=${nextEpisode.number}", it)
                }
            }
        }

        // A previous background pass may already have resolved this episode.
        // In that case playback uses the saved ranges immediately and does not
        // show an analysis notification.
        val cachedResult = OfflineOpEdResultStore.load(context, anime.id, currentEpisode.id)
        if (cachedResult != null) {
            chapterSkipSegments = cachedResult.map {
                ChapterSkipSegment(it.type, it.startTime, it.endTime, 0.0)
            }
            skipEpisodeKey = "${anime.id}_${currentEpisode.displayNumber}"
            hasSavedOpEdAnalysis = true
            Log.d("AniChapters", "CACHED_RESULT episode=${currentEpisode.number} segments=${cachedResult.size}")
            startNextBackgroundAnalysis()
            return@LaunchedEffect
        }

        chapterAnalysisStatus = "분석 준비 중..."
        chapterAnalysisVisible = true

        while (isActive) {
            val duration = exoPlayer.duration
            if (exoPlayer.playbackState == Player.STATE_READY && duration > 0L && duration != C.TIME_UNSET) {
                val durationSeconds = (duration / 1000L).toInt().coerceAtLeast(1)
                Log.d("AniChapters", "START episode=${currentEpisode.number} duration=$durationSeconds source=linkkf")
                val chapterStatus: (String) -> Unit = { raw ->
                    scope.launch(Dispatchers.Main.immediate) {
                        if (isDownloaded) {
                            // Offline mode: hide the detailed analysis log and show
                            // only the final OP/ED result briefly.
                            if (raw.startsWith("FINGERPRINT_ANALYSIS_COMPLETE") ||
                                raw.startsWith("ANALYSIS_COMPLETE")
                            ) {
                                chapterAnalysisStatus = "✓ OP/ED 찾음"
                                chapterAnalysisVisible = true
                            }
                        } else if (raw.startsWith("ONLINE_FINGERPRINT_ANALYSIS_COMPLETE")) {
                            chapterAnalysisStatus = "✓ OP/ED 찾음"
                            chapterAnalysisVisible = true
                        } else if (raw.startsWith("ONLINE_FINGERPRINT_ANALYSIS_FAILED") ||
                            raw.startsWith("ONLINE_FINGERPRINT_TRAINING_FAILED")) {
                            chapterAnalysisStatus = formatChapterAnalysisStatus(raw)
                            chapterAnalysisVisible = true
                        } else if (raw.startsWith("ONLINE_") || raw.startsWith("M3U8_WEBVIEW_") ||
                            raw.startsWith("FINGERPRINT_")) {
                            // Online mode: show the detailed analysis progress directly
                            // on the playback screen.
                            chapterAnalysisStatus = formatChapterAnalysisStatus(raw)
                            chapterAnalysisVisible = true
                        }
                    }
                }

                // Downloaded episodes are analyzed entirely from the Media3 cache.
                // This avoids WebView/network collection during the offline test.
                chapterSkipSegments = run {
                    Log.d("AniChapters", "OFFLINE_MODE episode=${currentEpisode.number}")
                    LinkkfChapterService.detectSkipSegmentsOffline(
                        context = context,
                        animeId = anime.id,
                        currentEpisode = currentEpisode,
                        episodes = episodeList,
                        episodeDurationSeconds = durationSeconds,
                        onStatus = chapterStatus
                    )
                }
                skipEpisodeKey = "${anime.id}_${currentEpisode.displayNumber}"
                hasSavedOpEdAnalysis = OfflineOpEdResultStore.load(context, anime.id, currentEpisode.id) != null
                Log.d("AniChapters", "LOADED count=${chapterSkipSegments.size}")

                startNextBackgroundAnalysis()
                break
            }
            delay(250L)
        }
    }

    LaunchedEffect(exoPlayer, currentEpisode.number, chapterSkipSegments, isAutoSkipEnabled) {
        val segments = chapterSkipSegments
        if (segments.isEmpty()) {
            activeChapterSkipSegment = null
            return@LaunchedEffect
        }

        while (isActive) {
            val positionSeconds = exoPlayer.currentPosition / 1000.0
            val active = segments.firstOrNull {
                positionSeconds >= it.startTime && positionSeconds < it.endTime
            }

            if (active != activeChapterSkipSegment) {
                activeChapterSkipSegment = active
                buttonChapterSkipSegment = active
                chapterSkipEnteredAtMs = if (active != null) System.currentTimeMillis() else -1L

                if (active != null) {
                    Log.d(
                        "AniChapters",
                        "ENTER type=${active.type} position=$positionSeconds range=${active.startTime}-${active.endTime}"
                    )
                }
            }

            if (active == null) {
                buttonChapterSkipSegment = null
                chapterSkipEnteredAtMs = -1L
            } else if (isAutoSkipEnabled) {
                val key = "${active.type}:${active.startTime}:${active.endTime}"
                val elapsedMs = if (chapterSkipEnteredAtMs >= 0L) {
                    System.currentTimeMillis() - chapterSkipEnteredAtMs
                } else {
                    0L
                }

                // 버튼이 잠깐 보인 뒤 자동 스킵되도록 한다. 자동 스킵을 끄면
                // 구간 전체에서 버튼으로 직접 넘길 수 있다.
                if (key !in skippedChapterSkipKeys && elapsedMs >= 1200L) {
                    val duration = exoPlayer.duration
                    val targetSeconds = if (duration > 0L && duration != C.TIME_UNSET) {
                        minOf(active.endTime, duration / 1000.0 - 0.5)
                    } else {
                        active.endTime
                    }

                    if (targetSeconds > positionSeconds + 0.25) {
                        Log.d(
                            "AniChapters",
                            "AUTO_SKIP type=${active.type} position=$positionSeconds target=$targetSeconds elapsedMs=$elapsedMs"
                        )
                        skippedChapterSkipKeys = skippedChapterSkipKeys + key
                        exoPlayer.seekTo((targetSeconds * 1000.0).toLong().coerceAtLeast(0L))
                        activeChapterSkipSegment = null
                        buttonChapterSkipSegment = null
                        chapterSkipEnteredAtMs = -1L
                    }
                }
            }

            delay(200L)
        }
    }

    fun applySubtitleSettingsToView(playerView: PlayerView) {
        val subView = playerView.subtitleView ?: return

        val isAss = subtitlesUrl?.lowercase(Locale.ROOT)?.let {
            it.endsWith(".ass") || it.endsWith(".ssa") ||
                it.contains(".ass?") || it.contains(".ssa?")
        } == true

        if (isAss) {
            // ASS is rendered by libass through AssSubtitleView. Hide Media3's
            // normal SubtitleView so the same ASS track is not drawn twice.
            subView.visibility = View.INVISIBLE
            Log.d(
                "Subtitle",
                "ASS_VIEW libass=true renderType=OVERLAY_OPEN_GL media3SubtitleView=hidden"
            )
            return
        }

        // VTT/SRT uses the lightweight custom overlay below. Media3 exposes only a
        // fixed outline style, so using it here would make the user's border-width
        // setting ineffective.
        subView.visibility = View.INVISIBLE
        subView.setApplyEmbeddedStyles(isVttStyleEnabled)
        subView.setApplyEmbeddedFontSizes(isVttStyleEnabled)

        // PiP는 실제 표시 영역이 매우 작기 때문에 일반 재생과 동일한 고정 sp를
        // 사용하면 VTT 자막이 화면을 덮을 정도로 커진다. PiP에서만 별도 축소한다.
        val pipScale = if (isInPictureInPicture) 0.48f else 1f
        val calculatedSp = 18f * (subtitleSizePercent / 100f) * pipScale
        subView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, calculatedSp)
        val positionFraction = subtitleBottomPaddingFraction.coerceIn(0.03f, 0.45f)
        subView.setBottomPaddingFraction(positionFraction)

        // Media3 버전에 따라 bottomPaddingFraction이 재측정 시 되돌아가는 경우가 있어
        // 실제 padding도 함께 갱신하고 즉시 invalidate/requestLayout 한다.
        fun applyPositionAfterLayout() {
            subView.setBottomPaddingFraction(positionFraction)
            val bottomPx = (subView.height * positionFraction).toInt().coerceAtLeast(0)
            subView.setPadding(
                subView.paddingLeft,
                subView.paddingTop,
                subView.paddingRight,
                bottomPx
            )
            subView.invalidate()
        }

        // 최초 AndroidView 생성 시에는 SubtitleView 높이가 아직 0일 수 있다.
        // post()로 레이아웃 이후 한 번 더 적용해 처음 표시되는 VTT에도 위치 설정을 반영한다.
        subView.requestLayout()
        subView.post { applyPositionAfterLayout() }
        applyPositionAfterLayout()

        val transparentStyle = CaptionStyleCompat(
            vm.playerSettings.textColor,
            vm.playerSettings.backgroundColor,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            vm.playerSettings.strokeColor,
            if (vttBold) Typeface.DEFAULT_BOLD else (customTypeface ?: Typeface.DEFAULT)
        )
        subView.setStyle(transparentStyle)
    }

    LaunchedEffect(isInPictureInPicture, playerViewRef, subtitlesUrl, subtitleSizePercent, subtitleBottomPaddingFraction, vttBold, vttOutlineWidth) {
        playerViewRef?.let { applySubtitleSettingsToView(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val videoUrl = currentEpisode.videoUrl

        when {
            streamUrl != null -> {
                AndroidView(
                    factory = { ctx ->
                        KeyboardPlayerView(ctx).apply {
                            playerViewRef = this
                            player = forwardingPlayer
                            useController = !isPlayerLocked
                            controllerShowTimeoutMs = 2000
                            isFocusable = true
                            isFocusableInTouchMode = true
                            seekSeconds = vm.playerSettings.doubleTapSeekSeconds
                            requestFocus()

                            // Compose pointerInput으로 PlayerView 위를 덮지 않고 View 레벨에서 더블탭 seek 처리
                            // ExoPlayer Controller(재생바/버튼)의 터치를 유지한다.
                            val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                                override fun onDoubleTap(e: MotionEvent): Boolean {
                                    val seconds = vm.playerSettings.doubleTapSeekSeconds
                                    val delta = seconds * 1000L
                                    if (e.x < width / 2f) {
                                        player?.seekTo((player?.currentPosition ?: 0L) - delta)
                                    } else {
                                        player?.seekTo((player?.currentPosition ?: 0L) + delta)
                                    }
                                    return true
                                }
                            })
                            setOnTouchListener { _, event ->
                                gestureDetector.onTouchEvent(event)
                                false
                            }
                            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                                // PlayerView의 내부 controller 상태만 Compose에 전달한다.
                                // 자동 숨김은 아래 LaunchedEffect가 담당하여 재생 중 상태와
                                // 메뉴 상태를 항상 최신 값으로 참조하도록 한다.
                                isControlsVisible = visibility == View.VISIBLE
                            })
                            applySubtitleSettingsToView(this)

                            // libass renderer overlay. It is transparent unless an
                            // ASS track is active, and it follows the PlayerView
                            // surface size automatically.
                            val libassOverlay = AssSubtitleView(ctx, assHandler).apply {
                                tag = "kairan_libass_overlay"
                                isClickable = false
                                isFocusable = false
                            }
                            addView(
                                libassOverlay,
                                android.widget.FrameLayout.LayoutParams(
                                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                                )
                            )
                            Log.d("Subtitle", "LIBASS_OVERLAY_ATTACHED")
                        }
                    },
                    update = { playerView ->
                    playerViewRef = playerView
                    val settingsButton = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)
                    settingsButton?.visibility = View.GONE
                    playerView.player = forwardingPlayer
                    (playerView as? KeyboardPlayerView)?.seekSeconds = vm.playerSettings.doubleTapSeekSeconds
                    playerView.useController = !isPlayerLocked
                        applySubtitleSettingsToView(playerView)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (subtitlesUrl?.lowercase(Locale.ROOT)?.let { it.endsWith(".vtt") || it.endsWith(".srt") || it.contains(".vtt?") || it.contains(".srt?") } == true) {
                    AndroidView(
                        factory = { ctx ->
                            VttStrokeTextView(ctx).apply {
                                vttOverlayRef = this
                                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                                setTextColor(vm.playerSettings.textColor)
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                setPadding(12, 4, 12, 4)
                                setLineSpacing(0f, 1.0f)
                            }
                        },
                        update = { view ->
                            vttOverlayRef = view
                            view.text = vttCueText
                            view.setTextColor(vm.playerSettings.textColor)
                            view.typeface = if (vttBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                            view.outlineWidthPx = vttOutlineWidth * context.resources.displayMetrics.density
                            view.outlineColor = android.graphics.Color.BLACK
                            val outlinePad = (view.outlineWidthPx + 6f).roundToInt()
                            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * (subtitleSizePercent / 100f) * if (isInPictureInPicture) 0.48f else 1f)
                            val bottom = (subtitleBottomPaddingFraction.coerceIn(0.03f, 0.45f) * 1000).toInt()
                            view.setPadding(12 + outlinePad, 4 + outlinePad, 12 + outlinePad, if (view.height > 0) (view.height * subtitleBottomPaddingFraction.coerceIn(0.03f, 0.45f)).toInt() + outlinePad else 80 + outlinePad)
                            view.invalidate()
                        },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)
                    )
                }
            }
            !isOffline && !resolvedVideoPageUrl.isNullOrBlank() -> {
                val extractorTargetUrl = resolvedVideoPageUrl ?: ""
                StreamUrlExtractor(
                    targetUrl = extractorTargetUrl,
                    onQualitiesFound = { qualities ->
                        parsedStreamingQualities = qualities
                        if (streamUrl == null && qualities.isNotEmpty()) {
                            if (extractorTargetUrl.contains("animenosub", ignoreCase = true)) {
                                Log.d("AnimenosubPlayer", "PLAYBACK_WATCHDOG_ARMED url=${qualities.first().url}")
                            }
                            val selected = qualities.first()
                            selectedStreamingQuality = selected
                            streamUrl = selected.url
                            isLoading = false
                        }
                    },
                    onSubtitleFound = { foundUrl ->
                        // VTT는 ASS/Kairan/Csora의 존재 여부와 관계없이 항상 보관한다.
                        // 영상 소스가 Linkkf가 아니더라도 extractor가 발견한 VTT를
                        // 현재 회차의 VTT 후보로 유지해서 사용자가 언제든 선택할 수 있게 한다.
                        linkkfSubtitleUrl = foundUrl
                        if (subtitleSourcePreference == "linkkf") {
                            subtitlesUrl = foundUrl
                            subtitleSource = "linkkf-vtt"
                            Log.d("Subtitle", "USE_VTT url=$foundUrl source=${vm.playerSettings.videoSourcePreference}")
                        } else {
                            Log.d("Subtitle", "CACHE_VTT url=$foundUrl source=${vm.playerSettings.videoSourcePreference}")
                        }
                    }
                )
                CircularProgressIndicator(color = Lilac)
                // 인증 WebView는 현재 사용하지 않는다.
                // StreamUrlExtractor가 페이지 내부의 HLS 요청을 직접 탐지한다.
            }
            else -> {
                Text(
                    text = if (isOffline) "오프라인 상태이며 다운로드된 영상이 없습니다." else "영상을 불러올 수 없습니다.",
                    color = Color.White
                )
            }
        }

        // 좌/우 더블 탭으로 사용자가 설정한 시간만큼 뒤로/앞으로 이동한다.
        // 연속 더블 탭은 누적 시간을 표시하고, 물결 애니메이션으로 피드백을 준다.
        if (!isPlayerLocked) {
            var playerWidth by remember { mutableIntStateOf(0) }
            var seekFeedbackDirection by remember { mutableIntStateOf(0) } // -1: 뒤로, +1: 앞으로
            var seekFeedbackSeconds by remember { mutableIntStateOf(0) }
            var showSeekFeedback by remember { mutableStateOf(false) }
            val seekRipple = remember { Animatable(0f) }
            val seekScope = rememberCoroutineScope()
            var seekFeedbackJob by remember { mutableStateOf<Job?>(null) }

            Box(
                modifier = Modifier
                    // 영상 영역만 더블탭을 처리하고 하단 PlayerView 컨트롤/재생바는 그대로 터치를 받는다.
                    .fillMaxSize()
                    .padding(bottom = 90.dp)
                    .onSizeChanged { playerWidth = it.width }
            ) {
                // YouTube처럼 아이콘 없이 화면 좌/우에서 반원형 오버레이가 짧게 퍼진다.
                if (showSeekFeedback && seekFeedbackDirection != 0) {
                    val feedbackAlignment = if (seekFeedbackDirection < 0) {
                        Alignment.CenterStart
                    } else {
                        Alignment.CenterEnd
                    }

                    // Animatable 값에 따라 화면 바깥쪽에서 큰 원이 퍼져 들어오는 느낌을 만든다.
                    val rippleScale = 0.55f + seekRipple.value * 0.85f
                    val rippleAlpha = (1f - seekRipple.value * 0.35f).coerceIn(0f, 1f)

                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = feedbackAlignment
                    ) {
                        Box(
                            modifier = Modifier
                                .size(520.dp)
                                .scale(rippleScale)
                                .alpha(0.32f * rippleAlpha)
                                .background(Color.Black, CircleShape)
                        )

                        // YouTube 스타일처럼 숫자만 간단히 표시한다.
                        Text(
                            text = "${seekFeedbackSeconds}초",
                            modifier = Modifier
                                .align(
                                    if (seekFeedbackDirection < 0) Alignment.CenterStart
                                    else Alignment.CenterEnd
                                )
                                .padding(horizontal = 72.dp),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )
                    }
                }
            }
        }

        // PlayerView controller는 표시 상태가 바뀔 때마다 별도의 타이머로 숨긴다.
        // 기존처럼 playerViewRef만 key로 사용하면 controller가 다시 나타난 뒤
        // LaunchedEffect가 재실행되지 않아 상단바가 계속 남는 경우가 있다.
        // 메뉴가 열려 있는 동안에는 컨트롤을 유지한다.
        LaunchedEffect(isControlsVisible, isPlayerLocked, showPlayerSettingsDialog, streamUrl) {
            val pv = playerViewRef ?: return@LaunchedEffect
            if (!isControlsVisible || isPlayerLocked || showPlayerSettingsDialog || streamUrl == null) return@LaunchedEffect

            delay(2500L)

            // 메뉴가 열렸거나 잠금 상태로 바뀐 경우에는 숨기지 않는다.
            if (!isPlayerLocked && !showPlayerSettingsDialog && pv.isControllerFullyVisible) {
                pv.hideController()
            }
            isControlsVisible = false
        }

        // 잠금 상태에서는 PlayerView의 기본 컨트롤을 사용하지 않고,
        // 화면 터치 시 잠금 버튼만 2초 동안 보여준다.
        if (isPlayerLocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                showLockedButton = true
                                lockedButtonRequest++
                            }
                        )
                    }
            )
        }

        LaunchedEffect(lockedButtonRequest, isPlayerLocked) {
            if (isPlayerLocked && showLockedButton) {
                delay(2000L)
                showLockedButton = false
            }
        }

        LaunchedEffect(chapterAnalysisStatus, isDownloaded) {
            val status = chapterAnalysisStatus ?: return@LaunchedEffect
            if (status == "✓ OP/ED 찾음") {
                delay(3000L)
                chapterAnalysisVisible = false
            } else if (!isDownloaded && (status.startsWith("✗ 온라인 OP/ED 분석 실패") ||
                        status.startsWith("✗ OP/ED 학습 실패"))) {
                delay(4000L)
                chapterAnalysisVisible = false
            }
        }

        AnimatedVisibility(
            visible = chapterAnalysisVisible && !isPlayerLocked,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 68.dp, start = 14.dp),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.78f),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (chapterAnalysisStatus?.startsWith("✓") != true && chapterAnalysisStatus?.startsWith("✗") != true) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Lilac
                        )
                        Spacer(Modifier.width(9.dp))
                    }
                    Text(
                        text = chapterAnalysisStatus ?: "OP/ED 분석 중...",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 우측 하단에 6초간 표시되는 Csora ASS 전환 안내.
        AnimatedVisibility(
            visible = showCsoraAssPrompt && !isPlayerLocked,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Csora ASS 자막을 발견했습니다. 바꾸시겠습니까?",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.widthIn(max = 260.dp)
                    )
                    TextButton(onClick = { showCsoraAssPrompt = false }) {
                        Text("아니요")
                    }
                    Button(onClick = {
                        discoveredCsoraAssPath?.let { path ->
                            subtitlesUrl = path
                            subtitleSource = "csora"
                            subtitleSourcePreference = "csora"
                            vm.updatePlayerSettings(
                                context,
                                vm.playerSettings.copy(subtitleSourcePreference = "csora")
                            )
                            Log.d("Csora", "USER_SWITCHED_TO_BACKGROUND_ASS path=$path episode=${currentEpisode.number}")
                        }
                        showCsoraAssPrompt = false
                    }) {
                        Text("예")
                    }
                }
            }
        }

        // 우측 하단에 6초간 표시되는 Kairan ASS 전환 안내.
        AnimatedVisibility(
            visible = showKairanAssPrompt && !isPlayerLocked,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "ASS 자막을 발견했습니다. 바꾸시겠습니까?",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.widthIn(max = 260.dp)
                    )
                    TextButton(onClick = { showKairanAssPrompt = false }) {
                        Text("아니요")
                    }
                    Button(onClick = {
                        discoveredKairanAssPath?.let { path ->
                            subtitlesUrl = path
                            subtitleSource = "kairan"
                            subtitleSourcePreference = "kairan"
                            vm.updatePlayerSettings(
                                context,
                                vm.playerSettings.copy(subtitleSourcePreference = "kairan")
                            )
                            Log.d("Kairan", "USER_SWITCHED_TO_BACKGROUND_ASS path=$path episode=${currentEpisode.number}")
                        }
                        showKairanAssPrompt = false
                    }) {
                        Text("예")
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = (isControlsVisible || showPlayerSettingsDialog) && !isPlayerLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 12.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.42f)
                ) {
                    IconButton(onClick = back) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "뒤로가기",
                            tint = Color.White
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                Box(
                    modifier = Modifier.wrapContentWidth(Alignment.End)
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.42f)
                    ) {
                        IconButton(onClick = { showPlayerSettingsDialog = !showPlayerSettingsDialog }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "플레이어 설정",
                                tint = Color.White
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showPlayerSettingsDialog,
                        onDismissRequest = { showPlayerSettingsDialog = false },
                        modifier = Modifier
                            .width(320.dp)
                            .heightIn(max = 520.dp),
                        shape = RoundedCornerShape(18.dp),
                        containerColor = Color(0xFF18161D),
                        tonalElevation = 6.dp,
                        shadowElevation = 16.dp
                    ) {
                        CompositionLocalProvider(LocalContentColor provides Color.White) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 520.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Text("재생 설정", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("재생 · 스킵 · 자막 설정", fontSize = 11.sp, color = Color.LightGray)
                                Spacer(Modifier.height(14.dp))
                                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                                Spacer(Modifier.height(14.dp))

                                SettingToggleRow(
                                    icon = Icons.Default.PlayArrow,
                                    title = "다음화 자동재생",
                                    checked = isAutoPlayEnabled,
                                    onCheckedChange = { isAutoPlayEnabled = it }
                                )
                                Spacer(Modifier.height(6.dp))
                                SettingToggleRow(
                                    icon = Icons.Default.FastForward,
                                    title = "OP/ED 자동 스킵",
                                    checked = isAutoSkipEnabled,
                                    onCheckedChange = { isAutoSkipEnabled = it }
                                )

                                if (isDownloaded && vm.playerSettings.offlineOpEdAnalysisEnabled && hasSavedOpEdAnalysis) {
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(
                                            onClick = {
                                                OfflineOpEdResultStore.delete(context, anime.id, currentEpisode.id)
                                                chapterSkipSegments = emptyList()
                                                activeChapterSkipSegment = null
                                                buttonChapterSkipSegment = null
                                                skipEpisodeKey = null
                                                hasSavedOpEdAnalysis = false
                                                Toast.makeText(context, "이 회차의 OP/ED 분석을 삭제했습니다.", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("이 회차 OP/ED 분석 삭제")
                                        }
                                    }

                                if (vm.playerSettings.offlineOpEdAnalysisEnabled && OfflineOpEdFingerprintStore.isReady(context, anime.id)) {
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedButton(
                                        onClick = {
                                            OfflineOpEdFingerprintStore.delete(context, anime.id)
                                            hasSavedOpEdAnalysis = false
                                            Toast.makeText(context, "이 애니의 기준 OP/ED fingerprint를 삭제했습니다.", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("기준 OP/ED fingerprint 삭제")
                                    }
                                }

                                Spacer(Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("재생 속도", fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                "${String.format(java.util.Locale.US, "%.2f", playbackSpeed)}x",
                                fontWeight = FontWeight.Bold,
                                color = Lilac
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        val speedIndex = playbackSpeedOptions.indexOf(playbackSpeed)
                            .takeIf { it >= 0 } ?: 4
                        Slider(
                            value = speedIndex.toFloat(),
                            onValueChange = { value ->
                                val index = value.roundToInt().coerceIn(playbackSpeedOptions.indices)
                                vm.updatePlayerSettings(
                                    context,
                                    vm.playerSettings.copy(playbackSpeed = playbackSpeedOptions[index])
                                )
                            },
                            valueRange = 0f..(playbackSpeedOptions.lastIndex).toFloat(),
                            steps = playbackSpeedOptions.size - 2,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            playbackSpeedOptions.forEach { speed ->
                                Text(
                                    "${speed}x",
                                    fontSize = 9.sp,
                                    color = if (speed == playbackSpeed) Lilac else Color.Gray,
                                    fontWeight = if (speed == playbackSpeed) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        Text(
                            "슬라이더를 움직이면 즉시 재생 속도가 변경됩니다.",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 6.dp)
                        )

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))

                        Text("M3U8 화질 직접 선택", fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(6.dp))

                        if (parsedStreamingQualities.isNotEmpty()) {
                            parsedStreamingQualities.forEach { quality ->
                                val isSelected = (selectedStreamingQuality?.url == quality.url) || 
                                                 (selectedStreamingQuality == null && streamUrl == quality.url)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (!isSelected) {
                                                pendingSeekPositionMs = exoPlayer.currentPosition
                                                selectedStreamingQuality = quality
                                                streamUrl = quality.url
                                            }
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = null
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(quality.label, fontSize = 14.sp)
                                }
                            }
                        } else if (exoQualities.isNotEmpty()) {
                            Text("ExoPlayer 내장 트랙 목록", fontSize = 12.sp, color = Color.Gray)
                            exoQualities.forEach { option ->
                                val isSelected = (selectedQualityOption == option) || (selectedQualityOption == null && option.isAuto)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedQualityOption = option
                                            val builder = trackSelector.buildUponParameters()
                                            if (option.isAuto) {
                                                builder.clearVideoSizeConstraints()
                                            } else {
                                                builder
                                                    .setMaxVideoSize(option.width, option.height)
                                                    .setMinVideoSize(option.width, option.height)
                                            }
                                            trackSelector.setParameters(builder)
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = null
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(option.label, fontSize = 14.sp)
                                }
                            }
                        } else {
                            Text("m3u8 화질 정보를 불러오는 중...", fontSize = 12.sp, color = Color.Gray)
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "OP/ED 스킵 버튼 표시",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    "재생화면에 나타나는 OP/ED 스킵 버튼을 표시합니다.",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            Switch(
                                checked = vm.playerSettings.showChapterSkipButton,
                                onCheckedChange = { enabled ->
                                    vm.updatePlayerSettings(
                                        context,
                                        vm.playerSettings.copy(showChapterSkipButton = enabled)
                                    )
                                }
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))

                        Text("자막 소스", fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "다운로드된 Linkkf VTT, Kairan ASS, Csora ASS 중 재생할 소스를 여기서 바로 선택합니다.",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = subtitleSourcePreference == "linkkf",
                                onClick = {
                                    subtitleSourcePreference = "linkkf"
                                    vm.updatePlayerSettings(context, vm.playerSettings.copy(subtitleSourcePreference = "linkkf"))
                                },
                                label = { Text("Linkkf VTT") }
                            )
                            FilterChip(
                                selected = subtitleSourcePreference == "kairan",
                                onClick = {
                                    subtitleSourcePreference = "kairan"
                                    vm.updatePlayerSettings(context, vm.playerSettings.copy(subtitleSourcePreference = "kairan"))
                                },
                                label = { Text("Kairan ASS") }
                            )
                            FilterChip(
                                selected = subtitleSourcePreference == "csora",
                                onClick = {
                                    subtitleSourcePreference = "csora"
                                    // Do not wait for the dialog's 확인 button: preference changes are live.
                                    vm.updatePlayerSettings(context, vm.playerSettings.copy(subtitleSourcePreference = "csora"))
                                },
                                label = { Text("Csora ASS") }
                            )
                        }
                        Text(
                            "Kairan은 현재 연결된 원본이 ASS 형식이므로 ASS 렌더러를 사용합니다.",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))

                        if (discoveredSubtitleFonts.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("발견된 폰트", fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                "Kairan/Csora에서 발견한 폰트입니다. 현재 자막 소스와 같은 폰트만 즉시 적용됩니다.",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                            Spacer(Modifier.height(6.dp))
                            if (discoveredSubtitleFonts.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        pendingSeekPositionMs = exoPlayer.currentPosition
                                        selectedSubtitleFontPath = null
                                            if (subtitleSource == "kairan" || subtitleSource == "csora") {
                                                SubtitleStore.clearSelectedFont(context, anime.id, subtitleSource)
                                            }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selectedSubtitleFontPath == null) Lilac.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f)
                                ) {
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = selectedSubtitleFontPath == null, onClick = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("ASS 원본 폰트", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                                Spacer(Modifier.height(5.dp))
                                discoveredSubtitleFonts.forEach { font ->
                                    val selected = selectedSubtitleFontPath == font.path && subtitleSource == font.source
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            pendingSeekPositionMs = exoPlayer.currentPosition
                                            selectedSubtitleFontPath = font.path
                                            SubtitleStore.saveSelectedFont(context, anime.id, font.source, font.path)
                                            subtitleSourcePreference = font.source
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (selected) Lilac.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f)
                                    ) {
                                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = selected, onClick = null)
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(font.displayName, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(font.source.uppercase(Locale.ROOT), color = Color.Gray, fontSize = 9.sp)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                            Spacer(Modifier.height(10.dp))
                        }

                        Spacer(Modifier.height(12.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSubtitleManager = !showSubtitleManager
                                    if (!showSubtitleManager) {
                                        scope.launch {
                                            savedSubtitles = SubtitleStore.list(context, anime.id, currentEpisode.displayNumber, currentEpisode.number)
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.05f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Lilac)
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("이 에피소드의 저장 자막", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(
                                        if (showSubtitleManager) "잘못된 자막은 삭제하거나 자동 선택에서 제외할 수 있습니다." else "저장된 Kairan/Csora/Linkkf 자막 관리",
                                        color = Color.Gray, fontSize = 10.sp
                                    )
                                }
                                Text(if (showSubtitleManager) "▲" else "▼", color = Color.Gray, fontSize = 12.sp)
                            }
                        }

                        if (showSubtitleManager) {
                            LaunchedEffect(anime.id, currentEpisode.number, showSubtitleManager) {
                                if (showSubtitleManager) {
                                    savedSubtitles = SubtitleStore.list(context, anime.id, currentEpisode.displayNumber, currentEpisode.number)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            if (savedSubtitles.isEmpty()) {
                                Text("현재 저장된 자막이 없습니다.", color = Color.Gray, fontSize = 11.sp)
                            } else {
                                savedSubtitles.forEach { saved ->
                                    val sourceName = when (saved.source) {
                                        "kairan" -> "Kairan ASS"
                                        "csora" -> "Csora ASS"
                                        else -> "Linkkf VTT"
                                    }
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (saved.ignored) Color.White.copy(alpha = 0.025f) else Color.White.copy(alpha = 0.045f)
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(sourceName, color = if (saved.ignored) Color.Gray else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                File(saved.path).name,
                                                color = Color.Gray,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (!saved.episodeMatch) {
                                                Text("⚠ 회차 정보가 현재 에피소드와 일치하지 않음", color = Color(0xFFFFB74D), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                                            } else if (saved.ignored) {
                                                Text("자동 선택에서 제외됨", color = Color(0xFFFFB74D), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                                                horizontalArrangement = Arrangement.End,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                TextButton(onClick = {
                                                    scope.launch {
                                                        SubtitleStore.setIgnored(context, anime.id, currentEpisode.displayNumber, currentEpisode.number, saved.source, !saved.ignored)
                                                        savedSubtitles = SubtitleStore.list(context, anime.id, currentEpisode.displayNumber, currentEpisode.number)
                                                    }
                                                }) {
                                                    Text(if (saved.ignored) "다시 사용" else "사용 안 함", color = Lilac, fontSize = 11.sp)
                                                }
                                                TextButton(onClick = {
                                                    scope.launch {
                                                        val deletingCurrent = subtitlesUrl == saved.path
                                                        SubtitleStore.delete(context, anime.id, currentEpisode.displayNumber, currentEpisode.number, saved.source)
                                                        savedSubtitles = SubtitleStore.list(context, anime.id, currentEpisode.displayNumber, currentEpisode.number)
                                                        if (deletingCurrent) {
                                                            subtitlesUrl = null
                                                            subtitleSource = "none"
                                                            Toast.makeText(context, "자막을 삭제했습니다.", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }) {
                                                    Text("삭제", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(5.dp))
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("자막 설정", fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(10.dp))

                        val currentUserSubtitle = currentEpisode.vttUrl?.takeIf { isLocalUserSubtitlePath(it) }
                        OutlinedButton(
                            onClick = {
                                subtitleFilePickerLauncher.launch(
                                    arrayOf("text/*", "application/x-subrip", "application/x-ass", "application/octet-stream")
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Subtitles, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (currentUserSubtitle != null) "사용자 자막 변경" else "자막 파일 불러오기")
                        }
                        if (currentUserSubtitle != null) {
                            Text(
                                "사용자 자막이 우선 적용됩니다. 오프라인 재생에도 유지됩니다.",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("VTT 원본 색상/스타일 유지", fontSize = 13.sp)
                            Switch(
                                checked = isVttStyleEnabled,
                                onCheckedChange = { isVttStyleEnabled = it }
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("VTT 글자 굵게", fontSize = 13.sp)
                            Switch(
                                checked = vttBold,
                                onCheckedChange = {
                                    vttBold = it
                                    vm.updatePlayerSettings(context, vm.playerSettings.copy(vttBold = it))
                                }
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Text("VTT 테두리 두께 (${String.format(Locale.US, "%.1f", vttOutlineWidth)}dp)", fontSize = 13.sp)
                        Slider(
                            value = vttOutlineWidth,
                            onValueChange = {
                                vttOutlineWidth = it
                                vm.updatePlayerSettings(context, vm.playerSettings.copy(vttOutlineWidth = it))
                            },
                            valueRange = 0.5f..6.0f,
                            steps = 10
                        )
                        Text("기본값 2dp. 2dp에서는 Media3 기본 VTT 배치가 유지됩니다.", fontSize = 10.sp, color = Color.Gray)

                        Spacer(Modifier.height(10.dp))

                        Text("자막 싱크 미세 조정 (${syncOffsetMs}ms)", fontSize = 13.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = { syncOffsetMs -= 250L }, modifier = Modifier.weight(1f)) {
                                Text("-250ms", fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { syncOffsetMs = 0L }, modifier = Modifier.weight(1f)) {
                                Text("초기화", fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { syncOffsetMs += 250L }, modifier = Modifier.weight(1f)) {
                                Text("+250ms", fontSize = 11.sp)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Text("VTT 자막 싱크 입력", fontSize = 13.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = syncOffsetText,
                                onValueChange = { value ->
                                    syncOffsetText = value.filter { it.isDigit() || it == '-' }
                                    syncOffsetText.toLongOrNull()?.let { syncOffsetMs = it }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                suffix = { Text("ms", color = Color.White) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = Color.White,
                                    focusedBorderColor = Lilac,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.45f),
                                    focusedLabelColor = Lilac,
                                    unfocusedLabelColor = Color.LightGray
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "양수 = 자막을 늦춤\n음수 = 자막을 앞당김",
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        Text(
                            "VTT 자막 위치 (${(subtitleBottomPaddingFraction * 100).toInt()}%)",
                            fontSize = 13.sp
                        )
                        Slider(
                            value = subtitleBottomPaddingFraction,
                            onValueChange = { subtitleBottomPaddingFraction = it },
                            valueRange = 0.03f..0.30f,
                            steps = 26
                        )
                        Text(
                            "값이 클수록 자막이 조금 더 위로 올라갑니다.",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )

                        Spacer(Modifier.height(10.dp))

                        Text("자막 크기 조절", fontSize = 13.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Slider(
                                value = subtitleSizePercent,
                                onValueChange = { valValue ->
                                    subtitleSizePercent = valValue
                                    subtitleSizeText = valValue.toInt().toString()
                                },
                                valueRange = 50f..300f,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = subtitleSizeText,
                                onValueChange = { text ->
                                    subtitleSizeText = text
                                    text.toFloatOrNull()?.let { parsed ->
                                        subtitleSizePercent = parsed.coerceIn(50f, 300f)
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(65.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = Color.White,
                                    focusedBorderColor = Lilac,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.45f)
                                )
                            )
                            Text("%", modifier = Modifier.padding(start = 4.dp), fontSize = 12.sp)
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("커스텀 폰트", fontSize = 13.sp)
                                Text(
                                    customFontName ?: "기본 폰트 사용 중", 
                                    fontSize = 11.sp, 
                                    color = Color.Gray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = { fontPickerLauncher.launch("*/*") },
                                colors = ButtonDefaults.buttonColors(containerColor = Lilac)
                            ) {
                                Text("폰트 불러오기", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
        }
        }
        if (vm.playerSettings.showChapterSkipButton) buttonChapterSkipSegment?.let { segment ->
            Box(modifier = Modifier.fillMaxSize()) {
            val label = if (segment.type == "op" || segment.type == "mixed-op") {
                "OP 스킵"
            } else {
                "ED 스킵"
            }

            Button(
                onClick = {
                    val positionSeconds = exoPlayer.currentPosition / 1000.0
                    val duration = exoPlayer.duration
                    val targetSeconds = if (duration > 0L && duration != C.TIME_UNSET) {
                        minOf(segment.endTime, duration / 1000.0 - 0.5)
                    } else {
                        segment.endTime
                    }

                    Log.d(
                        "AniChapters",
                        "BUTTON_SKIP type=${segment.type} position=$positionSeconds target=$targetSeconds"
                    )

                    skippedChapterSkipKeys = skippedChapterSkipKeys + "${segment.type}:${segment.startTime}:${segment.endTime}"
                    activeChapterSkipSegment = null
                    buttonChapterSkipSegment = null
                    chapterSkipEnteredAtMs = -1L

                    if (targetSeconds > positionSeconds) {
                        exoPlayer.seekTo((targetSeconds * 1000.0).toLong().coerceAtLeast(0L))
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 72.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(label)
            }
            }
        }

        if (isPlayerLocked || isControlsVisible) {
            // 플레이어 전체 영역을 기준으로 우측 하단에 잠금 버튼을 고정한다.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomEnd
            ) {
                Surface(
                    modifier = Modifier
                        .padding(end = 20.dp, bottom = 20.dp)
                        .size(48.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.42f)
                ) {
                    IconButton(
                        onClick = {
                            if (isPlayerLocked) {
                                isPlayerLocked = false
                                showLockedButton = true
                                isControlsVisible = true
                            } else {
                                isPlayerLocked = true
                                showLockedButton = true
                                lockedButtonRequest++
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isPlayerLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (isPlayerLocked) "잠금 해제" else "플레이어 잠금",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }

}
