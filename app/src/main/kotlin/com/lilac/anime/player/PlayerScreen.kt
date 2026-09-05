package com.lilac.anime

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.Job
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import com.lilac.anime.data.*
import com.lilac.anime.data.offline.MpvOfflineStore
import com.lilac.anime.data.offline.LegacyMedia3OfflineBridge
import com.lilac.anime.network.LinkkfChapterService
import com.lilac.anime.network.LinkkfEpisodeM3u8Collector
import com.lilac.anime.player.MpvPlayerEngine
import com.lilac.anime.player.MpvPlayerSurfaceView
import com.lilac.anime.network.OfflineOpEdFingerprintStore
import com.lilac.anime.data.subtitle.KairanSubtitleResult
import com.lilac.anime.data.subtitle.SubtitleAssetUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
private fun formatPlayerTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000L
    val h = totalSeconds / 3600L
    val m = (totalSeconds % 3600L) / 60L
    val sec = totalSeconds % 60L
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

private fun formatChapterAnalysisStatus(raw: String): String {
    val parts = raw.split(' ')
    return when {
        raw == "ANALYSIS_START" || raw.startsWith("ANALYSIS_START") -> "OP/ED 분석 시작"
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

private fun prepareLibassFontFile(context: Context, sourceFile: File): File? {
    if (!sourceFile.isFile || sourceFile.length() <= 0L) return null

    return try {
        // libmpv uses its own font directory for ASS/SSA rendering.
        // `sub-fonts-dir` property.  Keep a real on-disk font directory anyway:
        // it gives libass/ass-media a stable app-private location and avoids
        // depending on a SAF URI or an external subtitle folder.
        val fontsDir = File(context.filesDir, "libass_fonts").apply { mkdirs() }
        val safeName = sourceFile.name
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "custom_font.${sourceFile.extension.ifBlank { "ttf" }}" }
        val target = File(fontsDir, safeName)

        if (!target.isFile || target.length() != sourceFile.length()) {
            sourceFile.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }

        Log.d(
            "Subtitle",
            "ASS_FONT_DIR_READY source=${sourceFile.absolutePath} target=${target.absolutePath} size=${target.length()}"
        )
        target
    } catch (e: Exception) {
        Log.w("Subtitle", "ASS_FONT_DIR_COPY_FAILED source=${sourceFile.absolutePath}", e)
        null
    }
}

private fun loadSubtitleFontsForSource(
    context: Context,
    subtitlePath: String,
    source: String,
    selectedFontPath: String?,
    customFontPath: String?
): Int {
    val dir = File(context.filesDir, "mpv_fonts").apply { mkdirs() }
    var count = 0
    val candidates = buildList {
        selectedFontPath?.let { add(it) }
        customFontPath?.let { add(it) }
        val subtitleFile = File(subtitlePath)
        val parent = subtitleFile.parentFile
        parent?.let { add(File(it, "fonts").absolutePath) }
        when (source) {
            "csora" -> {
                val root = File(context.filesDir, "csora_subtitles")
                parent?.name?.let { add(File(root, "$it/fonts").absolutePath) }
            }
            "kairan" -> {
                val root = File(context.filesDir, "kairan_subtitles/fonts")
                val base = subtitleFile.nameWithoutExtension
                val titleKey = Regex("^(.*)_\\d+(?:_.*)?$").find(base)?.groupValues?.getOrNull(1)
                    ?: base.substringBeforeLast("_")
                add(File(root, titleKey).absolutePath)
            }
        }
    }.distinct()
    fun collectFontFiles(file: File): Sequence<File> {
        if (file.isFile) return sequenceOf(file)
        if (!file.isDirectory) return emptySequence()
        return file.walkTopDown().filter { child ->
            child.isFile && child.extension.lowercase(Locale.ROOT) in setOf("ttf", "otf", "ttc")
        }
    }

    val fontFiles = candidates
        .asSequence()
        .map(::File)
        .flatMap(::collectFontFiles)
        .distinctBy { it.absolutePath }

    for (file in fontFiles) {
        val target = File(dir, file.name)
        if (!target.exists() || target.length() != file.length()) {
            runCatching { file.copyTo(target, overwrite = true) }.onFailure {
                Log.w("MpvSubtitle", "font copy failed: ${file.absolutePath}", it)
            }
        }
        if (target.isFile) count++
    }
    return count
}

private fun isLocalUserSubtitlePath(path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    val file = File(path)
    if (!file.isFile) return false
    val normalized = file.absolutePath.replace('\\', '/')
    return normalized.contains("/${USER_SUBTITLE_DIR}/") &&
        (normalized.endsWith(".ass", true) || normalized.endsWith(".ssa", true) ||
         normalized.endsWith(".srt", true) || normalized.endsWith(".vtt", true) || normalized.endsWith(".smi", true))
}

private fun userSubtitleDirectory(context: Context): File =
    File(context.filesDir, USER_SUBTITLE_DIR).apply { mkdirs() }

private fun userSubtitleFile(
    context: Context, animeId: String, episodeNumber: Int, extension: String,
    episodeKey: String = episodeNumber.toString(), originalName: String = "subtitle"
): File {
    val safeKey = episodeKey.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")
    val safeName = originalName.substringBeforeLast('.', "subtitle")
        .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")
        .trim('_').ifBlank { "subtitle" }.take(50)
    val base = "${animeId}_${safeKey}_${safeName}"
    val ext = extension.lowercase(Locale.ROOT)
    var candidate = File(userSubtitleDirectory(context), "$base.$ext")
    var index = 2
    while (candidate.exists()) {
        candidate = File(userSubtitleDirectory(context), "${base}_$index.$ext")
        index++
    }
    return candidate
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
    
    var isAutoPlayEnabled by rememberSaveable { mutableStateOf(vm.playerSettings.autoPlay) }
    var isAutoSkipEnabled by rememberSaveable { mutableStateOf(vm.playerSettings.autoSkip) }
    var isControlsVisible by remember { mutableStateOf(true) }
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
    var suppressProgressSaveForEpisode by remember { mutableStateOf<Int?>(null) }

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
    var isVttStyleEnabled by rememberSaveable { mutableStateOf(vm.playerSettings.vttStyleEnabled) }
    var vttBold by rememberSaveable { mutableStateOf(vm.playerSettings.vttBold) }
    var vttOutlineWidth by rememberSaveable { mutableFloatStateOf(vm.playerSettings.vttOutlineWidth) }
    var customTypeface by remember { mutableStateOf<Typeface?>(null) }
    var customFontName by remember { mutableStateOf<String?>(null) }
    var selectedSubtitleFontPath by remember(anime.id) { mutableStateOf(vm.playerSettings.subtitleFontPath) }

    LaunchedEffect(vm.playerSettings.customFontPath) {
        val savedPath = vm.playerSettings.customFontPath
        if (!savedPath.isNullOrBlank()) {
            val savedFile = File(savedPath)
            if (savedFile.isFile) {
                runCatching {
                    customTypeface = Typeface.createFromFile(savedFile)
                    customFontName = "커스텀 폰트 적용됨"
                }
            }
        }
    }

    LaunchedEffect(anime.id, vm.playerSettings.subtitleFontPath, vm.playerSettings.subtitleFontSource) {
        selectedSubtitleFontPath = vm.playerSettings.subtitleFontPath?.takeIf { File(it).isFile }
            ?: vm.playerSettings.subtitleFontSource?.let { source -> SubtitleStore.getSelectedFont(context, anime.id, source) }
    }

    var parsedStreamingQualities by remember { mutableStateOf<List<StreamQuality>>(emptyList()) }
    var selectedStreamingQuality by remember { mutableStateOf<StreamQuality?>(null) }
    // 화질/자막 변경처럼 같은 회차 내부에서만 유지해야 하는 임시 seek 위치.
    // 회차가 바뀌면 이전 회차의 위치를 절대로 새 회차에 넘기지 않는다.
    var pendingSeekPositionMs by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(currentEpisode.id, currentEpisode.displayNumber, currentEpisode.number) {
        // 다음/이전 회차 이동 시 이전 회차의 임시 seek 위치를 폐기한다.
        // 새 회차의 시작 위치는 아래의 episode별 저장 progress에서만 결정한다.
        pendingSeekPositionMs = -1L
        suppressProgressSaveForEpisode = null
    }

    var showPlayerSettingsDialog by remember { mutableStateOf(false) }
    var discoveredSubtitleFonts by remember { mutableStateOf<List<SubtitleAssetUtil.FontInfo>>(emptyList()) }
    var savedSubtitles by remember { mutableStateOf<List<SubtitleStore.SavedSubtitle>>(emptyList()) }
    var userSubtitles by remember { mutableStateOf<List<SubtitleStore.SavedSubtitle>>(emptyList()) }
    LaunchedEffect(anime.id, currentEpisode.displayNumber, currentEpisode.number) {
        userSubtitles = SubtitleStore.listUser(context, anime.id, currentEpisode.displayNumber, currentEpisode.number)
        savedSubtitles = SubtitleStore.list(context, anime.id, currentEpisode.displayNumber, currentEpisode.number)
    }
    var showSubtitleManager by remember { mutableStateOf(false) }

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
                    ?: throw IllegalStateException("font stream is null")
                val mime = context.contentResolver.getType(it)?.lowercase(Locale.ROOT)
                val extension = when {
                    mime == "font/otf" || mime == "application/x-font-opentype" -> "otf"
                    it.toString().contains(".otf", ignoreCase = true) -> "otf"
                    else -> "ttf"
                }
                val fontsDir = File(context.filesDir, "custom_fonts").apply { mkdirs() }
                // Preserve the actual container type. Renaming OTF bytes to .ttf
                // breaks the embedded-font fallback and can make libass reject it.
                val persistentFile = File(fontsDir, "player_custom_font.$extension")
                FileOutputStream(persistentFile).use { output -> inputStream.use { input -> input.copyTo(output) } }
                customTypeface = Typeface.createFromFile(persistentFile)
                customFontName = "커스텀 폰트 적용됨"
                vm.updatePlayerSettings(
                    context,
                    vm.playerSettings.copy(customFontPath = persistentFile.absolutePath)
                )
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
                    lowerName.endsWith(".smi") -> "smi"
                    else -> null
                }

                if (extension == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "ASS, SSA, SRT, VTT, SMI 자막만 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val target = userSubtitleFile(context, anime.id, currentEpisode.number, extension, currentEpisode.displayNumber, displayName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("자막 파일을 열 수 없습니다.")

                // Keep user subtitles completely separate from the episode's
                // original/remote subtitle URL. Do not overwrite Episode.vttUrl or
                // OfflineStore's episode metadata.
                SubtitleStore.save(
                    context = context,
                    animeId = anime.id,
                    episodeKey = currentEpisode.displayNumber,
                    episodeNumber = currentEpisode.number,
                    source = "user",
                    path = target.absolutePath
                )
                withContext(Dispatchers.Main) {
                    userSubtitles = SubtitleStore.listUser(context, anime.id, currentEpisode.displayNumber, currentEpisode.number)
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

    // Do not trigger episode loading from PlayerScreen itself. The player may be
    // entered while the stream WebView is resolving the media URL; starting a
    // second detail/episode request here can race that resolver and prevent the
    // stream from reaching mpv. DetailScreen already owns episode loading.
    // Reading the mutable episode cache directly keeps previous/next/autoplay
    // reactive without freezing an initially-empty list in remember().
    val cachedEpisodeList = vm.episodes(anime)
    val episodeList = (cachedEpisodeList.ifEmpty { anime.episodes })
        .sortedWith(compareBy<Episode> { it.number }.thenBy { it.displayNumber })
    // 일부 소스는 재생 화면으로 전달된 Episode와 목록의 id가 달라질 수 있다.
    // id/displayNumber 하나만 믿으면 currentIndex가 -1이 되어 이전/다음 버튼과
    // 자동 다음화가 모두 비활성화된다. 번호까지 포함해 안정적으로 현재 회차를 찾는다.
    val currentIndex = remember(
        episodeList,
        currentEpisode.id,
        currentEpisode.displayNumber,
        currentEpisode.number
    ) {
        episodeList.indexOfFirst { it.id == currentEpisode.id }
            .takeIf { it >= 0 }
            ?: episodeList.indexOfFirst {
                it.displayNumber.equals(currentEpisode.displayNumber, ignoreCase = true)
            }.takeIf { it >= 0 }
            ?: episodeList.indexOfFirst { it.number == currentEpisode.number }
    }
    val prevEpisode = remember(episodeList, currentIndex) {
        if (currentIndex > 0) episodeList.getOrNull(currentIndex - 1) else null
    }
    val nextEpisode = remember(episodeList, currentIndex) {
        if (currentIndex >= 0 && currentIndex < episodeList.size - 1) episodeList.getOrNull(currentIndex + 1) else null
    }

    // The autoplay collector lives for the lifetime of the player. Keep mutable
    // State holders here so that collector always sees the latest episode/list
    // values instead of the values captured when LaunchedEffect first started.
    val currentNextEpisodeState = rememberUpdatedState(nextEpisode)
    val currentAutoPlayState = rememberUpdatedState(isAutoPlayEnabled)
    val currentEpisodeState = rememberUpdatedState(currentEpisode)

    // Re-evaluate on every ViewModel download-state update so a newly completed
    // mpv-native MP4 is picked up without reopening the player.
    val isDownloaded = vm.isEpisodeDownloaded(anime.id, currentEpisode)

    var offlineEp by remember { mutableStateOf<Episode?>(null) }
    LaunchedEffect(anime.id, currentEpisode.id, currentEpisode.displayNumber, isDownloaded) {
        offlineEp = OfflineStore.getEpisode(context, anime.id, currentEpisode)
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
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }

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
            }
        }
    }

    // Animenosub 인증/쿠키 로직은 현재 비활성화한다.
    // 스트림은 StreamUrlExtractor의 WebView에서 직접 탐지하고,
    // 사용자가 PlayerScreen을 나가려는 경우에만 기존 Back 동작을 수행한다.
    BackHandler { back() }

    // User subtitles are stored in SubtitleStore under the separate "user" source.
    // They are not restored into Episode.vttUrl, so changing/adding a user subtitle
    // never hides the Linkkf/Kairan/Csora subtitles.

    LaunchedEffect(currentEpisode, isDownloaded, offlineEp) {
        isLoading = true

        // Older releases stored HLS data in the Media3 cache. Do not depend on
        // OfflineStore's old videoUrl here: it can be missing/stale when the
        // episode id format changed. The Media3 download index is the source of
        // truth for legacy downloads. Migrate any legacy entry before mpv loads it.
        if (isDownloaded) {
            val localAlreadyReady = withContext(Dispatchers.IO) {
                MpvOfflineStore.completedPath(context, anime.id, currentEpisode.id)
            }
            if (localAlreadyReady == null) {
                val migrated = withContext(Dispatchers.IO) {
                    LegacyMedia3OfflineBridge.migrateIfNeeded(context, anime.id, currentEpisode)
                }
                if (migrated != null) {
                    val updated = currentEpisode.copy(videoUrl = migrated.absolutePath)
                    OfflineStore.saveEpisode(context, anime.id, updated)
                    offlineEp = updated
                }
            }
        }
        streamUrl = null
        subtitlesUrl = null
        linkkfSubtitleUrl = currentEpisode.vttUrl
        subtitleSource = "none"
        kairanSubtitleResolved = false
        parsedStreamingQualities = emptyList()
        selectedStreamingQuality = null
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
            // mpv-native offline storage is the source of truth. In particular,
            // never reuse the old OfflineStore.videoUrl when it points at an HLS
            // URL or at an audio-only MP4 produced by an older migration.
            MpvOfflineStore.completedPath(context, anime.id, currentEpisode.id)
                ?: offlineEp?.videoUrl?.takeIf { File(it).isFile && File(it).length() > 0L }
                ?: currentEpisode.videoUrl.takeIf { !isOffline }
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
                val localStoredSubtitle = storedSubtitle?.takeIf { path -> path.startsWith("/") && File(path).isFile && SubtitleStore.pathMatchesEpisode(path, currentEpisode.number) }
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
                    subtitleSourcePreference == "kairan" -> localKairan ?: localCsora ?: localLinkkf
                    subtitleSourcePreference == "csora" -> localCsora ?: localKairan ?: localLinkkf
                    else -> localLinkkf ?: localKairan
                }
                subtitleSource = when {
                    subtitlesUrl == localKairan && localKairan != null -> "kairan"
                    subtitlesUrl == localCsora && localCsora != null -> "csora"
                    subtitlesUrl != null -> "linkkf-vtt"
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

        val storedSubtitle = offlineEp?.vttUrl ?: currentEpisode.vttUrl
        val localStoredSubtitle = storedSubtitle?.takeIf { path -> path.startsWith("/") && File(path).isFile && SubtitleStore.pathMatchesEpisode(path, currentEpisode.number) }

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
                    subtitlesUrl = localLinkkf ?: localKairan ?: localCsora
                    subtitleSource = when { localLinkkf != null -> "linkkf-vtt"; localKairan != null -> "kairan"; localCsora != null -> "csora"; else -> "none" }
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
                    subtitlesUrl = null
                    subtitleSource = "none"
                    Log.d("Kairan", "NO_KAIRAN_SUBTITLE episode=${currentEpisode.number}; waiting for Linkkf VTT fallback")
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
                subtitlesUrl = null
                subtitleSource = "none"
                Log.d("Csora", "NO_CSORA_SUBTITLE episode=${currentEpisode.number}")
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

    val mpvEngine = remember(context) { MpvPlayerEngine(context) }
    DisposableEffect(mpvEngine) {
        onDispose { mpvEngine.release() }
    }

    LaunchedEffect(mpvEngine, playbackSpeed) {
        mpvEngine.setSpeed(playbackSpeed)
    }

    // ASS/SSA는 자막 파일의 모든 스타일(FontSize/Outline/Bold/Margin/Alignment 등)을
    // 그대로 사용하되, 사용자가 고른 폰트만 Fontname으로 교체한다.
    // 즉 커스텀 폰트를 적용해도 ASS의 크기/위치 설정은 건드리지 않는다.

    LaunchedEffect(
        mpvEngine, subtitleSizePercent, vttBold, vttOutlineWidth,
        subtitleBottomPaddingFraction, isInPictureInPicture,
        vm.playerSettings.textColor, vm.playerSettings.strokeColor
    ) {
        mpvEngine.applySubtitleStyle(
            textColor = vm.playerSettings.textColor,
            borderColor = vm.playerSettings.strokeColor,
            sizePercent = subtitleSizePercent,
            bold = vttBold,
            outlineWidth = vttOutlineWidth,
            bottomPaddingFraction = subtitleBottomPaddingFraction,
            pip = isInPictureInPicture
        )
    }

    // Linkkf VTT/SRT도 영상과 분리해서 적용한다. 자막 URL이 늦게 발견되어도
    // 현재 회차의 mpv loadfile을 다시 실행하지 않는다.
    LaunchedEffect(mpvEngine, currentEpisode.id, subtitlesUrl, subtitleSource, syncOffsetMs) {
        val subtitle = subtitlesUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val clean = subtitle.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        val isAss = clean.endsWith(".ass") || clean.endsWith(".ssa")
        if (isAss) return@LaunchedEffect

        val prepared = if (clean.endsWith(".smi") || clean.contains(".smi?")) {
            withContext(Dispatchers.IO) {
                prepareSmiAsVttFile(context, subtitle, anime.id, currentEpisode.number, currentEpisode.displayNumber)
            } ?: subtitle
        } else subtitle

        // Wait for the current episode's media to be opened before adding the
        // subtitle. `sub-add` sent while mpv is replacing a remote HLS item can
        // be consumed by the old item or ignored. The wait is cancellable when
        // the user moves to another episode.
        var waitedMs = 0L
        while (isActive && waitedMs < 10_000L &&
            mpvEngine.playbackState != Player.STATE_READY) {
            delay(50L)
            waitedMs += 50L
        }
        if (!isActive || mpvEngine.playbackState != Player.STATE_READY) return@LaunchedEffect

        withContext(Dispatchers.Main) {
            mpvEngine.replaceSubtitleTrack(prepared)
            mpvEngine.setSubtitleDelay(syncOffsetMs)
        }
    }

    // ASS 폰트 변경은 영상 전체를 다시 로드하지 않고 현재 자막 트랙만 교체한다.
    // 이렇게 해야 폰트 선택 즉시 화면에 반영되고, 재생 위치/버퍼도 유지된다.
    LaunchedEffect(mpvEngine, subtitlesUrl, subtitleSource, vm.playerSettings.customFontPath, selectedSubtitleFontPath) {
        val subtitle = subtitlesUrl ?: return@LaunchedEffect
        val clean = subtitle.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        val isAss = clean.endsWith(".ass") || clean.endsWith(".ssa")
        if (!isAss) return@LaunchedEffect

        val selectedFont = (vm.playerSettings.customFontPath ?: selectedSubtitleFontPath)
            ?.takeIf { File(it).isFile }

        if (selectedFont == null) {
            mpvEngine.resetSubtitleFontFamily()
            // 기본 폰트로 돌아갈 때도 원본 ASS를 다시 로드한다.
            withContext(Dispatchers.Main) {
                mpvEngine.replaceSubtitleTrack(subtitle)
            }
            return@LaunchedEffect
        }

        val effective = withContext(Dispatchers.IO) {
            SubtitleAssetUtil.prepareAssWithSelectedFont(subtitle, selectedFont)
        }
        withContext(Dispatchers.Main) {
            // ASS의 FontSize 등은 prepareAssWithSelectedFont에서 절대 수정하지 않는다.
            SubtitleAssetUtil.fontFamilyName(File(selectedFont))?.let(mpvEngine::setSubtitleFontFamily)
            mpvEngine.replaceSubtitleTrack(effective)
        }
    }

    var uiPositionMs by remember { mutableLongStateOf(0L) }
    var uiDurationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(mpvEngine) {
        while (isActive) {
            MainActivity.isVideoPlaying = mpvEngine.isPlaying
            uiPositionMs = mpvEngine.currentPosition
            uiDurationMs = mpvEngine.duration
            activity?.window?.let { window ->
                if (mpvEngine.isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            delay(100L)
        }
    }

    // UI는 별도의 버튼 상태보다 실제 mpv 위치를 기준으로 계산한다.
    // Compose가 100ms마다 갱신하는 uiPositionMs를 사용하므로, chapter state
    // 갱신 타이밍이 어긋나도 OP/ED 구간에 들어오면 버튼이 즉시 나타난다.
    val visibleChapterSkipSegment = chapterSkipSegments.firstOrNull { segment ->
        val positionSeconds = uiPositionMs / 1000.0
        val key = "${segment.type}:${segment.startTime}:${segment.endTime}"
        positionSeconds >= segment.startTime &&
            positionSeconds < segment.endTime &&
            key !in skippedChapterSkipKeys
    }

    fun switchEpisode(target: Episode) {
        if (target.id == currentEpisode.id &&
            target.displayNumber.equals(currentEpisode.displayNumber, ignoreCase = true)
        ) return

        // Save the old episode first, then pause without calling mpv `stop`.
        // `stop` emits END_FILE and can race the real EOF used by autoplay.
        // The next mpv loadfile(..., replace) performs the actual media replacement.
        val oldEpisode = currentEpisode
        val duration = mpvEngine.duration
        if (duration > 0L && duration != C.TIME_UNSET) {
            val progress = (mpvEngine.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            vm.updateProgress(
                context = context,
                animeId = anime.id,
                episodeNumber = oldEpisode.number,
                episodeKey = oldEpisode.displayNumber,
                progress = progress
            )
        }

        pendingSeekPositionMs = -1L
        isControlsVisible = true
        isLoading = true
        Log.d("MpvEpisode", "SWITCH ${oldEpisode.displayNumber} -> ${target.displayNumber}")

        mpvEngine.stopForEpisodeSwitch()
        currentEpisode = target
    }

    // Autoplay is driven by a single de-duplicated completion signal from mpv.
    // MPV_EVENT_END_FILE is the normal path, while eof-reached is a fallback for
    // HLS streams that do not reliably deliver END_FILE. MpvPlayerEngine emits
    // playbackEndedEvents only once per loaded generation, so both signals cannot
    // advance two episodes. Replacement events are suppressed until START_FILE.
    LaunchedEffect(mpvEngine) {
        mpvEngine.playbackEndedEvents.collect { eventGeneration ->
            // Read the current values from rememberUpdatedState at event time.
            // Do not capture nextEpisode/currentEpisode/autoPlay from the first
            // composition: that made autoplay keep an obsolete next-episode value
            // even though the manual Next button had the correct one.
            val completedEpisode = currentEpisodeState.value
            val target = currentNextEpisodeState.value
            val autoPlay = currentAutoPlayState.value

            Log.d(
                "MpvEpisode",
                "PLAYBACK_ENDED current=${completedEpisode.displayNumber} " +
                    "target=${target?.displayNumber} auto=${autoPlay} generation=$eventGeneration"
            )

            vm.updateProgress(
                context = context,
                animeId = anime.id,
                episodeNumber = completedEpisode.number,
                episodeKey = completedEpisode.displayNumber,
                progress = 0f
            )

            if (autoPlay && target != null) {
                pendingSeekPositionMs = -1L
                switchEpisode(target)
            }
        }
    }

        DisposableEffect(currentEpisode.id, currentEpisode.displayNumber, currentEpisode.number) {
        // Capture BOTH identifiers at effect creation time. Previously only the
        // episode number was captured, while onDispose read
        // currentEpisode.displayNumber. When switching 1 -> 2, Compose disposed
        // the 1화 effect after currentEpisode had already become 2화, so the old
        // 1화 position was written using the new episode key. That made the next
        // episode appear to inherit the previous episode's progress.
        val episodeNumberForSave = currentEpisode.number
        val episodeKeyForSave = currentEpisode.displayNumber
        onDispose {
            if (suppressProgressSaveForEpisode == episodeNumberForSave) return@onDispose
            val duration = mpvEngine.duration
            if (duration > 0 && duration != C.TIME_UNSET) {
                val progress = (mpvEngine.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                vm.updateProgress(
                    context = context,
                    animeId = anime.id,
                    episodeNumber = episodeNumberForSave,
                    episodeKey = episodeKeyForSave,
                    progress = progress
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

    // Video loading is keyed ONLY by the actual episode/video URL.
    // Subtitle discovery happens asynchronously and must never reload the video;
    // otherwise a VTT/ASS callback can replace the newly selected episode while
    // mpv is still opening its HLS stream, leaving the episode selected but paused.
    LaunchedEffect(
        streamUrl,
        currentEpisode.id,
        isOffline
    ) {
        val url = streamUrl ?: return@LaunchedEffect
        val isLocalFile = url.startsWith("file://") || url.startsWith("/")
        val actualUrl = if (isLocalFile && !url.startsWith("file://")) "file://${url}" else url

        // The playback host can change (for example play.sub3.top -> playv2.sub3.top).
        // Keep the request headers aligned with the actual playback host instead of
        // sending a stale hard-coded Referer/Origin from the previous host.
        val playbackOrigin = runCatching {
            java.net.URI(actualUrl).let { uri ->
                if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
                    "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
                } else null
            }
        }.getOrNull()
        val referer = if (vm.playerSettings.videoSourcePreference == "animenosub") {
            actualUrl
        } else {
            playbackOrigin?.let { "$it/" } ?: "https://play.sub3.top/"
        }
        val origin = if (vm.playerSettings.videoSourcePreference == "animenosub") null else playbackOrigin
        val headers = buildList {
            add("Referer: $referer")
            origin?.let { add("Origin: $it") }
        }.joinToString("\n")
        android.util.Log.d("LilacMpv", "STREAM_HEADERS hostOrigin=$playbackOrigin referer=$referer origin=$origin url=$actualUrl")

        withContext(Dispatchers.Main) {
            mpvEngine.configureNetworkHeaders(headers)
            mpvEngine.setSubtitleFontsDir(File(context.filesDir, "mpv_fonts").apply { mkdirs() }.absolutePath)
        }

        // Do not resolve/load subtitles here. Subtitle discovery can finish after
        // the video URL is known, and changing subtitles must not restart the video.
        val fontSelectedPath = (vm.playerSettings.customFontPath ?: selectedSubtitleFontPath)
            .takeIf { !it.isNullOrBlank() && File(it).isFile }

        withContext(Dispatchers.Main) {
            mpvEngine.applySubtitleStyle(
                textColor = vm.playerSettings.textColor,
                borderColor = vm.playerSettings.strokeColor,
                sizePercent = subtitleSizePercent,
                bold = vttBold,
                outlineWidth = vttOutlineWidth,
                bottomPaddingFraction = subtitleBottomPaddingFraction,
                pip = isInPictureInPicture,
                ass = false
            )
        }

        mpvEngine.load(
            url = actualUrl,
            subtitlePath = null,
            syncOffsetMs = syncOffsetMs,
            customFontPath = fontSelectedPath
        )

        val loadGeneration = mpvEngine.activeLoadGeneration
        val pendingSeek = pendingSeekPositionMs
        val savedProgress = vm.getProgress(anime.id, currentEpisode.displayNumber, currentEpisode.number)
        // A completed episode must always start from the beginning when opened
        // again. Keep the normal resume behavior for genuinely unfinished
        // episodes, but treat a progress value at/near the end as completed.
        val resumeProgress = savedProgress?.progress
            ?.takeUnless { it >= 0.95f }

        // Do NOT call play() here. MpvPlayerEngine starts playback from
        // MPV_EVENT_FILE_LOADED. Calling play() immediately after loadfile can
        // race a remote HLS replacement and leave the newly selected episode
        // stuck while the old item's state is being torn down.
        var waitedMs = 0L
        var restored = false
        var playWatchdogUntil = 3_000L
        while (isActive && waitedMs < 10_000L && !restored) {
            if (mpvEngine.loadedLoadGeneration == loadGeneration &&
                mpvEngine.playbackState == Player.STATE_READY) {
                // Re-assert play for the current generation. Remote HLS can reach
                // READY with pause=true after a replacement even though load()
                // requested autoplay. Never use a stale generation here.
                if (waitedMs <= playWatchdogUntil) {
                    mpvEngine.forcePlayIfReady(loadGeneration)
                }
                val duration = mpvEngine.duration
                if (duration > 0L) {
                    when {
                        pendingSeek >= 0L -> mpvEngine.seekTo(pendingSeek.coerceIn(0L, duration))
                        resumeProgress != null -> mpvEngine.seekTo((resumeProgress * duration).toLong().coerceIn(0L, duration))
                        savedProgress != null -> {
                            // 95%+ is a completed watch record. Start at 0 instead
                            // of restoring the playhead to the very end.
                            mpvEngine.seekTo(0L)
                            vm.updateProgress(
                                context = context,
                                animeId = anime.id,
                                episodeNumber = currentEpisode.number,
                                episodeKey = currentEpisode.displayNumber,
                                progress = 0f
                            )
                        }
                    }
                    restored = true
                    pendingSeekPositionMs = -1L
                    break
                }
            }
            delay(50L)
            waitedMs += 50L
        }
        pendingSeekPositionMs = -1L
    }

    // Linkkf watch pages sometimes fail to expose the media request when the
    // hidden extractor is recreated during an autoplay transition. Manual
    // navigation can still work because the WebView gets more time to settle.
    // For an episode that has not produced an m3u8 after a short grace period,
    // resolve that exact episode page with the dedicated collector and feed the
    // resulting index.m3u8 directly to mpv. This bypasses the fragile UI WebView
    // path without changing the normal/manual resolution path.
    LaunchedEffect(currentEpisode.id, currentEpisode.videoUrl, isOffline) {
        if (isOffline || vm.playerSettings.videoSourcePreference != "linkkf") return@LaunchedEffect
        val pageUrl = currentEpisode.videoUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (pageUrl.contains(".m3u8", ignoreCase = true) || pageUrl.contains(".mp4", ignoreCase = true)) return@LaunchedEffect

        delay(8_000L)
        if (!isActive || currentEpisode.videoUrl != pageUrl || streamUrl != null) return@LaunchedEffect

        Log.d("MpvEpisode", "AUTO_STREAM_FALLBACK_START episode=${currentEpisode.displayNumber} page=$pageUrl")
        val result = withContext(Dispatchers.IO) {
            LinkkfEpisodeM3u8Collector.collect(
                context = context,
                episodes = listOf(currentEpisode)
            )
        }
        val resolved = result.urls[currentEpisode.id]
        if (resolved.isNullOrBlank()) {
            Log.e("MpvEpisode", "AUTO_STREAM_FALLBACK_FAILED episode=${currentEpisode.displayNumber} page=$pageUrl")
            return@LaunchedEffect
        }
        if (currentEpisode.videoUrl != pageUrl || streamUrl != null) return@LaunchedEffect
        Log.d("MpvEpisode", "AUTO_STREAM_FALLBACK_FOUND episode=${currentEpisode.displayNumber} m3u8=$resolved")
        selectedStreamingQuality = null
        parsedStreamingQualities = listOf(StreamQuality("Auto", resolved))
        streamUrl = resolved
        isLoading = false
    }

    LaunchedEffect(streamUrl, currentEpisode.number) {
        val currentStreamUrl = streamUrl ?: return@LaunchedEffect

        chapterSkipSegments = emptyList()
        activeChapterSkipSegment = null
        buttonChapterSkipSegment = null
        chapterSkipEnteredAtMs = -1L
        skippedChapterSkipKeys = emptySet()
        chapterAnalysisStatus = null
        chapterAnalysisVisible = false

        // 이미 분석해서 저장된 OP/ED 결과는 스트리밍 재생에서도 바로 사용한다.
        // 별도의 네트워크 분석을 새로 시작하지 않으므로 재생 시작을 방해하지 않는다.
        val cachedSegments = OfflineOpEdFingerprintStore.loadAnalysis(
            context = context,
            animeId = anime.id,
            episodeId = currentEpisode.id
        )
        if (cachedSegments != null) {
            val duration = mpvEngine.duration.takeIf { it > 0L }?.div(1000.0) ?: 0.0
            chapterSkipSegments = cachedSegments.map { segment ->
                if (duration > 0.0) segment.copy(episodeLength = duration) else segment
            }.sortedBy { it.startTime }
            skipEpisodeKey = "${anime.id}_${currentEpisode.displayNumber}"
            Log.d("AniChapters", "CACHE_USED episode=${currentEpisode.number} count=${chapterSkipSegments.size}")
            // Cached results are deliberately silent so an already-analyzed
            // episode does not show the same notification every time it starts.
            return@LaunchedEffect
        }

        // 새 OP/ED 분석은 기존 정책대로 다운로드 완료 회차에서만 수행한다.
        // 스트리밍에서는 분석 API/다운로드를 호출하지 않는다.
        if (!isDownloaded || !vm.playerSettings.offlineOpEdAnalysisEnabled) {
            skipEpisodeKey = null
            return@LaunchedEffect
        }

        while (isActive) {
            val duration = mpvEngine.duration
            if (mpvEngine.playbackState == Player.STATE_READY && duration > 0L && duration != C.TIME_UNSET) {
                val durationSeconds = (duration / 1000L).toInt().coerceAtLeast(1)
                Log.d("AniChapters", "START episode=${currentEpisode.number} duration=$durationSeconds source=linkkf")
                // 분석 과정은 화면에 표시하지 않는다. 상세 진행 로그는 Logcat에만 남긴다.
                val chapterStatus: (String) -> Unit = { raw ->
                    Log.d("AniChapters", "STATUS $raw")
                }

                // Downloaded episodes are analyzed entirely from the Media3 cache.
                // This avoids WebView/network collection during the offline test.
                chapterSkipSegments = if (isDownloaded && vm.playerSettings.offlineOpEdAnalysisEnabled) {
                    Log.d("AniChapters", "OFFLINE_MODE episode=${currentEpisode.number}")
                    LinkkfChapterService.detectSkipSegmentsOffline(
                        context = context,
                        animeId = anime.id,
                        currentEpisode = currentEpisode,
                        episodes = episodeList,
                        episodeDurationSeconds = durationSeconds,
                        onStatus = chapterStatus
                    )
                } else {
                    // 방어 코드: 위에서 스트리밍은 이미 return 되었으므로
                    // 네트워크/스트림 기반 OP/ED 분석은 절대로 호출하지 않는다.
                    emptyList()
                }
                skipEpisodeKey = "${anime.id}_${currentEpisode.displayNumber}"
                Log.d("AniChapters", "LOADED episode=${currentEpisode.number} count=${chapterSkipSegments.size}")

                // 현재 회차 분석이 끝난 뒤, 바로 다음 회차도 다운로드가 완료되어
                // 있다면 같은 백그라운드 흐름에서 한 번 더 분석한다. 스트리밍에는
                // 이 LaunchedEffect 자체가 진입하지 않으므로 네트워크 분석은 없다.
                val next = nextEpisode
                var nextResult: List<ChapterSkipSegment> = emptyList()
                if (next != null && vm.playerSettings.offlineOpEdAnalysisEnabled &&
                    LinkkfChapterService.isOfflineEpisodeCompleted(anime.id, next)) {
                    nextResult = LinkkfChapterService.detectSkipSegmentsOffline(
                        context = context,
                        animeId = anime.id,
                        currentEpisode = next,
                        episodes = episodeList,
                        episodeDurationSeconds = 0,
                        onStatus = chapterStatus
                    )
                    Log.d("AniChapters", "NEXT_LOADED episode=${next.number} count=${nextResult.size}")
                }
                chapterAnalysisStatus = null
                chapterAnalysisVisible = false

                // 모든 분석이 끝난 뒤에만 사용자에게 한 번 알린다.
                // 캐시된 결과를 불러온 경우에도 실제 분석 과정을 표시하지 않는다.
                if (chapterSkipSegments.isNotEmpty() || nextResult.isNotEmpty()) {
                    Toast.makeText(context, "OP/ED를 발견했습니다.", Toast.LENGTH_SHORT).show()
                }
                break
            }
            delay(250L)
        }
    }

    LaunchedEffect(mpvEngine, currentEpisode.number, chapterSkipSegments, isAutoSkipEnabled) {
        val segments = chapterSkipSegments
        if (segments.isEmpty()) {
            activeChapterSkipSegment = null
            return@LaunchedEffect
        }

        while (isActive) {
            val positionSeconds = mpvEngine.currentPosition / 1000.0
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
                if (key !in skippedChapterSkipKeys && elapsedMs >= 2500L) {
                    val duration = mpvEngine.duration
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
                        mpvEngine.seekTo((targetSeconds * 1000.0).toLong().coerceAtLeast(0L))
                        activeChapterSkipSegment = null
                        buttonChapterSkipSegment = null
                        chapterSkipEnteredAtMs = -1L
                    }
                }
            }

            delay(200L)
        }
    }

    var seekFeedbackDirection by remember { mutableIntStateOf(0) }
    var seekFeedbackSeconds by remember { mutableIntStateOf(0) }
    var showSeekFeedback by remember { mutableStateOf(false) }
    val seekRipple = remember { Animatable(0f) }
    var seekFeedbackJob by remember { mutableStateOf<Job?>(null) }

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
                        MpvPlayerSurfaceView(ctx, mpvEngine).apply {
                            seekSeconds = vm.playerSettings.doubleTapSeekSeconds
                            gesturesLocked = isPlayerLocked
                            onSingleTap = {
                                if (isPlayerLocked) {
                                    showLockedButton = true
                                    lockedButtonRequest++
                                } else {
                                    isControlsVisible = !isControlsVisible
                                }
                            }
                            onUnlockTap = {
                                if (isPlayerLocked) {
                                    isPlayerLocked = false
                                    showLockedButton = false
                                    isControlsVisible = true
                                }
                            }
                            onDoubleTap = { direction ->
                                if (!isPlayerLocked) {
                                    seekFeedbackDirection = direction
                                    seekFeedbackSeconds = vm.playerSettings.doubleTapSeekSeconds
                                    showSeekFeedback = true
                                    seekFeedbackJob?.cancel()
                                    seekFeedbackJob = scope.launch {
                                        seekRipple.snapTo(0f)
                                        seekRipple.animateTo(1f, animationSpec = tween(500, easing = FastOutSlowInEasing))
                                        showSeekFeedback = false
                                    }
                                }
                            }
                            onLongPress = { if (!isPlayerLocked) isControlsVisible = false }
                            requestFocus()
                        }
                    },
                    update = { view ->
                        view.seekSeconds = vm.playerSettings.doubleTapSeekSeconds
                        view.gesturesLocked = isPlayerLocked
                        view.onSingleTap = {
                            if (isPlayerLocked) {
                                showLockedButton = true
                                lockedButtonRequest++
                            } else {
                                isControlsVisible = !isControlsVisible
                            }
                        }
                        view.onUnlockTap = {
                            if (isPlayerLocked) {
                                isPlayerLocked = false
                                showLockedButton = false
                                isControlsVisible = true
                            }
                        }
                        view.onDoubleTap = { direction ->
                            if (!isPlayerLocked) {
                                seekFeedbackDirection = direction
                                seekFeedbackSeconds = vm.playerSettings.doubleTapSeekSeconds
                                showSeekFeedback = true
                                seekFeedbackJob?.cancel()
                                seekFeedbackJob = scope.launch {
                                    seekRipple.snapTo(0f)
                                    seekRipple.animateTo(1f, animationSpec = tween(500, easing = FastOutSlowInEasing))
                                    showSeekFeedback = false
                                }
                            }
                        }
                        view.onLongPress = { if (!isPlayerLocked) isControlsVisible = false }
                    },
                    modifier = Modifier.fillMaxSize()
                )

            }
            !isOffline && !resolvedVideoPageUrl.isNullOrBlank() -> {
                val extractorTargetUrl = resolvedVideoPageUrl ?: ""
                key(extractorTargetUrl) {
                    StreamUrlExtractor(
                    targetUrl = extractorTargetUrl,
                    onQualitiesFound = { qualities ->
                        // StreamUrlExtractor can finish asynchronously after the user has
                        // already moved to another episode. Never let a stale WebView callback
                        // install episode N's m3u8 into episode N+1.
                        if (resolvedVideoPageUrl != extractorTargetUrl || currentEpisode.videoUrl != extractorTargetUrl) {
                            Log.d("MpvEpisode", "IGNORE_STALE_STREAM page=$extractorTargetUrl current=${currentEpisode.videoUrl}")
                            return@StreamUrlExtractor
                        }
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
                        // Same guard for subtitles: an old extractor must not mutate the
                        // newly selected episode's state.
                        if (resolvedVideoPageUrl != extractorTargetUrl || currentEpisode.videoUrl != extractorTargetUrl) {
                            Log.d("Subtitle", "IGNORE_STALE_SUBTITLE page=$extractorTargetUrl")
                            return@StreamUrlExtractor
                        }
                        if (vm.playerSettings.videoSourcePreference == "linkkf") {
                            // 발견한 VTT를 항상 보관한다. Kairan을 보고 있는 동안 발견되어도
                            // 나중에 Linkkf VTT를 선택하면 즉시 다시 사용할 수 있어야 한다.
                            linkkfSubtitleUrl = foundUrl
                            if (subtitleSourcePreference == "linkkf") {
                                subtitlesUrl = foundUrl
                                subtitleSource = "linkkf-vtt"
                                Log.d("Subtitle", "USE_LINKKF_VTT url=$foundUrl")
                            } else {
                                Log.d("Subtitle", "CACHE_LINKKF_VTT url=$foundUrl")
                            }
                        }
                    }
                    )
                }
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

            Box(
                modifier = Modifier
                    // 영상 영역만 더블탭을 처리하고 하단 컨트롤은 Compose 오버레이가 처리한다.
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

        // 잠금 상태에서는 플레이어의 기본 컨트롤을 사용하지 않고,
        // 화면 터치 시 잠금 버튼만 2초 동안 보여준다.
        AnimatedVisibility(
            visible = false,
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

        // 기존 Compose 플레이어 상단 UI는 제거하고, 설정 메뉴만 MPV OTT UI에서 공유한다.
        // 메뉴의 모든 기존 기능은 유지한다.
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.TopEnd)) {
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
                                    onCheckedChange = {
                                        isAutoPlayEnabled = it
                                        vm.updatePlayerSettings(context, vm.playerSettings.copy(autoPlay = it))
                                    }
                                )
                                Spacer(Modifier.height(6.dp))
                                SettingToggleRow(
                                    icon = Icons.Default.FastForward,
                                    title = "OP/ED 자동 스킵",
                                    checked = isAutoSkipEnabled,
                                    onCheckedChange = {
                                        isAutoSkipEnabled = it
                                        vm.updatePlayerSettings(context, vm.playerSettings.copy(autoSkip = it))
                                    }
                                )

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
                                                pendingSeekPositionMs = mpvEngine.currentPosition
                                                selectedStreamingQuality = quality
                                                vm.updatePlayerSettings(context, vm.playerSettings.copy(defaultQuality = quality.label))
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
                                        pendingSeekPositionMs = mpvEngine.currentPosition
                                        selectedSubtitleFontPath = null
                                        val fontSource = vm.playerSettings.subtitleFontSource
                                        if (!fontSource.isNullOrBlank()) SubtitleStore.clearSelectedFont(context, anime.id, fontSource)
                                        vm.updatePlayerSettings(
                                            context,
                                            vm.playerSettings.copy(subtitleFontPath = null, subtitleFontSource = null)
                                        )
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
                                    val selected = selectedSubtitleFontPath == font.path
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            pendingSeekPositionMs = mpvEngine.currentPosition
                                            selectedSubtitleFontPath = font.path
                                            SubtitleStore.saveSelectedFont(context, anime.id, font.source, font.path)
                                            vm.updatePlayerSettings(
                                                context,
                                                vm.playerSettings.copy(subtitleFontPath = font.path, subtitleFontSource = font.source)
                                            )
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

                        Text("사용자 자막", fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "Linkkf/Kairan/Csora 자막과 별도로 저장됩니다. 원하는 사용자 자막만 선택해서 사용할 수 있습니다.",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(8.dp))
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
                            Text("사용자 자막 추가")
                        }
                        Spacer(Modifier.height(8.dp))
                        if (userSubtitles.isEmpty()) {
                            Text("추가된 사용자 자막이 없습니다.", color = Color.Gray, fontSize = 11.sp)
                        } else {
                            userSubtitles.forEach { user ->
                                val selected = subtitlesUrl == user.path && subtitleSource == "user"
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) Lilac.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.045f)
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = selected, onClick = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                File(user.path).name,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            TextButton(onClick = {
                                                subtitlesUrl = user.path
                                                subtitleSource = "user"
                                                pendingSeekPositionMs = mpvEngine.currentPosition
                                                kairanSubtitleResolved = true
                                            }) {
                                                Text(if (selected) "사용 중" else "적용", color = Lilac, fontSize = 11.sp)
                                            }
                                            TextButton(onClick = {
                                                scope.launch {
                                                    SubtitleStore.deleteOne(context, anime.id, currentEpisode.displayNumber, currentEpisode.number, "user", user.path)
                                                    userSubtitles = SubtitleStore.listUser(context, anime.id, currentEpisode.displayNumber, currentEpisode.number)
                                                    if (selected) {
                                                        subtitlesUrl = null
                                                        subtitleSource = "none"
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

                        Spacer(Modifier.height(10.dp))
                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("VTT 원본 색상/스타일 유지", fontSize = 13.sp)
                            Switch(
                                checked = isVttStyleEnabled,
                                onCheckedChange = {
                                    isVttStyleEnabled = it
                                    vm.updatePlayerSettings(context, vm.playerSettings.copy(vttStyleEnabled = it))
                                }
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
                            OutlinedButton(onClick = {
                                syncOffsetMs -= 250L
                                syncOffsetText = syncOffsetMs.toString()
                                vm.updatePlayerSettings(context, vm.playerSettings.copy(syncOffsetMs = syncOffsetMs))
                            }, modifier = Modifier.weight(1f)) {
                                Text("-250ms", fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = {
                                syncOffsetMs = 0L
                                syncOffsetText = "0"
                                vm.updatePlayerSettings(context, vm.playerSettings.copy(syncOffsetMs = 0L))
                            }, modifier = Modifier.weight(1f)) {
                                Text("초기화", fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = {
                                syncOffsetMs += 250L
                                syncOffsetText = syncOffsetMs.toString()
                                vm.updatePlayerSettings(context, vm.playerSettings.copy(syncOffsetMs = syncOffsetMs))
                            }, modifier = Modifier.weight(1f)) {
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
                                    syncOffsetText.toLongOrNull()?.let { parsed ->
                                            syncOffsetMs = parsed
                                            vm.updatePlayerSettings(context, vm.playerSettings.copy(syncOffsetMs = parsed))
                                        }
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
                            onValueChange = {
                                subtitleBottomPaddingFraction = it
                                vm.updatePlayerSettings(
                                    context,
                                    vm.playerSettings.copy(subtitleBottomPaddingFraction = it)
                                )
                            },
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
                                    vm.updatePlayerSettings(
                                        context,
                                        vm.playerSettings.copy(subtitleSize = valValue)
                                    )
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
                                        val clamped = parsed.coerceIn(50f, 300f)
                                        subtitleSizePercent = clamped
                                        vm.updatePlayerSettings(
                                            context,
                                            vm.playerSettings.copy(subtitleSize = clamped)
                                        )
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
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (vm.playerSettings.customFontPath != null) {
                                    OutlinedButton(
                                        onClick = {
                                            customTypeface = null
                                            customFontName = null
                                            vm.updatePlayerSettings(
                                                context,
                                                vm.playerSettings.copy(customFontPath = null)
                                            )
                                            Toast.makeText(context, "기본 폰트로 전환했습니다.", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                    ) {
                                        Text("기본", fontSize = 11.sp)
                                    }
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
        // 잠금 상태에서는 화면을 한 번 탭하면 잠금 버튼을 다시 잠시 표시한다.
        // lockedButtonRequest를 사용해 같은 상태값을 반복해서 true로 설정해도 타이머가 다시 시작된다.
        LaunchedEffect(lockedButtonRequest, isPlayerLocked) {
            if (!isPlayerLocked || !showLockedButton) return@LaunchedEffect
            kotlinx.coroutines.delay(3000)
            if (isPlayerLocked) showLockedButton = false
        }

        LaunchedEffect(isControlsVisible, isPlayerLocked, showPlayerSettingsDialog, streamUrl) {
            if (!isControlsVisible || isPlayerLocked || showPlayerSettingsDialog || streamUrl == null) return@LaunchedEffect
            delay(2500L)
            if (!isPlayerLocked && !showPlayerSettingsDialog) isControlsVisible = false
        }

        // Compose-native OTT controller. Intentionally minimal: controls feel integrated
        // with the video instead of looking like a collection of large floating buttons.
        AnimatedVisibility(
            visible = isControlsVisible && !isPlayerLocked,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(220)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                // Soft cinematic vignette, like modern OTT players.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent)))
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = back) {
                        Icon(Icons.Default.ArrowBack, "뒤로", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = currentEpisode.title ?: "${currentEpisode.number}화",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${currentEpisode.number}화",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = { showPlayerSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, "플레이어 설정", tint = Color.White.copy(alpha = 0.92f))
                    }
                }

                // Small, clean transport controls. No white circular button or heavy cards.
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(30.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        modifier = Modifier.size(52.dp),
                        onClick = { mpvEngine.seekBy(-vm.playerSettings.doubleTapSeekSeconds.toDouble()) }
                    ) {
                        Icon(Icons.Default.Replay10, "${vm.playerSettings.doubleTapSeekSeconds}초 뒤로", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    Surface(
                        modifier = Modifier.size(58.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.16f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.28f))
                    ) {
                        IconButton(onClick = { if (mpvEngine.isPlaying) mpvEngine.pause() else mpvEngine.play() }) {
                            Icon(
                                if (mpvEngine.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                "재생/일시정지",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    IconButton(
                        modifier = Modifier.size(52.dp),
                        onClick = { mpvEngine.seekBy(vm.playerSettings.doubleTapSeekSeconds.toDouble()) }
                    ) {
                        Icon(Icons.Default.Forward10, "${vm.playerSettings.doubleTapSeekSeconds}초 앞으로", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(176.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.90f))))
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Slider(
                            value = if (uiDurationMs > 0) (uiPositionMs.toFloat() / uiDurationMs).coerceIn(0f, 1f) else 0f,
                            onValueChange = { if (uiDurationMs > 0) uiPositionMs = (uiDurationMs * it).toLong() },
                            onValueChangeFinished = { mpvEngine.seekTo(uiPositionMs) },
                            modifier = Modifier.fillMaxWidth().height(24.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Lilac,
                                inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${formatPlayerTime(uiPositionMs)} / ${formatPlayerTime(uiDurationMs)}", color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { prevEpisode?.let(::switchEpisode) }, enabled = prevEpisode != null) {
                                Icon(Icons.Default.SkipPrevious, "이전 화", tint = if (prevEpisode != null) Color.White else Color.White.copy(alpha = 0.25f))
                            }
                            IconButton(onClick = { nextEpisode?.let(::switchEpisode) }, enabled = nextEpisode != null) {
                                Icon(Icons.Default.SkipNext, "다음 화", tint = if (nextEpisode != null) Color.White else Color.White.copy(alpha = 0.25f))
                            }
                        }
                    }
                }
            }
        }

        // mpv 컨트롤 위에 독립적으로 떠 있는 스킵 pill. 재생 컨트롤이 숨겨져도
        // OP/ED 구간에 들어오면 바로 표시한다.
        if (vm.playerSettings.showChapterSkipButton && !isPlayerLocked) {
            (visibleChapterSkipSegment ?: buttonChapterSkipSegment)?.let { segment ->
            val isOp = segment.type == "op" || segment.type == "mixed-op"
            val label = if (isOp) "OP 건너뛰기" else "ED 건너뛰기"
            val accent = Lilac

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = if (isControlsVisible) 126.dp else 30.dp)
                    .zIndex(20f),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xE51A1820),
                tonalElevation = 4.dp,
                shadowElevation = 10.dp
            ) {
                Row(
                    modifier = Modifier
                        .clickable {
                            val positionSeconds = mpvEngine.currentPosition / 1000.0
                            val duration = mpvEngine.duration
                            val targetSeconds = if (duration > 0L && duration != C.TIME_UNSET) {
                                minOf(segment.endTime, duration / 1000.0 - 0.5)
                            } else segment.endTime

                            Log.d(
                                "AniChapters",
                                "BUTTON_SKIP type=${segment.type} position=$positionSeconds target=$targetSeconds"
                            )

                            skippedChapterSkipKeys = skippedChapterSkipKeys +
                                "${segment.type}:${segment.startTime}:${segment.endTime}"
                            activeChapterSkipSegment = null
                            buttonChapterSkipSegment = null
                            chapterSkipEnteredAtMs = -1L
                            if (targetSeconds > positionSeconds) {
                                mpvEngine.seekTo((targetSeconds * 1000.0).toLong().coerceAtLeast(0L))
                            }
                        }
                        .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(30.dp),
                        shape = CircleShape,
                        color = accent.copy(alpha = 0.22f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            label,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${formatPlayerTime((segment.endTime * 1000).toLong())}까지",
                            color = Color.LightGray,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
        }

        if ((!isPlayerLocked && isControlsVisible) || (isPlayerLocked && showLockedButton)) {
            // 잠금 상태에서는 화면을 다시 탭했을 때만 잠금 버튼을 잠시 표시한다.
            // 버튼 영역은 위의 전체 화면 터치 레이어에서 제외해 직접 클릭할 수 있다.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart
            ) {
                // 중앙 재생 컨트롤과 하단 타임라인을 가리지 않도록
                // 잠금 버튼은 화면 왼쪽 가장자리 중앙에 배치한다.
                Surface(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(42.dp)
                        .zIndex(100f),
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
                                isControlsVisible = false
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
}
}
