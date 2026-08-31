package com.lilac.anime.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lilac.anime.stream.BrowserEpisodeStreamExtractor
import com.lilac.anime.stream.EpisodeStreamInfo
import com.lilac.anime.stream.StreamQuality
import com.lilac.anime.stream.SubtitleDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.math.roundToInt

@Composable
fun DesktopApp() {
    val scope = rememberCoroutineScope()
    val extractor = remember { BrowserEpisodeStreamExtractor() }

    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var episode by remember { mutableStateOf("1") }
    var info by remember { mutableStateOf<EpisodeStreamInfo?>(null) }
    var selected by remember { mutableStateOf<StreamQuality?>(null) }
    var subtitle by remember { mutableStateOf<Path?>(null) }
    var status by remember { mutableStateOf("페이지 URL 또는 m3u8 URL을 입력하세요.") }
    var busy by remember { mutableStateOf(false) }
    var playerState by remember { mutableStateOf(MpvPlayer.State()) }
    var speed by remember { mutableStateOf(1.0) }
    var volume by remember { mutableStateOf(100.0) }
    var subtitleDelay by remember { mutableStateOf(0L) }
    var autoSkip by remember { mutableStateOf(true) }
    var skipSegments by remember { mutableStateOf<List<DesktopAniSkipSegment>>(emptyList()) }
    var activeSkip by remember { mutableStateOf<DesktopAniSkipSegment?>(null) }
    var lastAutoSkippedKey by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val listener: (MpvPlayer.State) -> Unit = { playerState = it }
        MpvPlayer.addListener(listener)
        onDispose {
            MpvPlayer.removeListener(listener)
            MpvPlayer.stop()
        }
    }

    LaunchedEffect(playerState.position, skipSegments, autoSkip) {
        if (!playerState.running) {
            activeSkip = null
            return@LaunchedEffect
        }

        val segment = skipSegments.firstOrNull {
            playerState.position >= it.startTime && playerState.position < it.endTime
        }
        activeSkip = segment

        if (autoSkip && segment != null) {
            val key = "${segment.type}:${segment.startTime}:${segment.endTime}"
            if (lastAutoSkippedKey != key) {
                lastAutoSkippedKey = key
                MpvPlayer.seek(segment.endTime - playerState.position)
                activeSkip = null
            }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LilacAnime Desktop", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.weight(1f))
                    FilterChip(
                        selected = autoSkip,
                        onClick = { autoSkip = !autoSkip },
                        label = { Text("자동 스킵") }
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        MpvPlayer.stop()
                        status = "재생 중지"
                    }) { Text("중지") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("작품명 (AniSkip)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = episode,
                        onValueChange = { episode = it.filter(Char::isDigit) },
                        modifier = Modifier.width(90.dp),
                        label = { Text("화") },
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("페이지 URL / m3u8 URL") },
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = url.isNotBlank() && !busy,
                        onClick = {
                            busy = true
                            status = "m3u8 / VTT 검색 중..."
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { extractor.extract(url.trim()) }
                                }.onSuccess { result ->
                                    info = result
                                    selected = result.qualities.firstOrNull()
                                    subtitle = null
                                    status = if (result.qualities.isEmpty()) {
                                        "m3u8 요청을 찾지 못했습니다."
                                    } else {
                                        "${result.qualities.size}개 화질 발견"
                                    }
                                }.onFailure {
                                    status = "추출 실패: ${it.message ?: it::class.simpleName}"
                                }
                                busy = false
                            }
                        }
                    ) { Text(if (busy) "검색 중..." else "주소 분석") }

                    OutlinedButton(
                        enabled = title.isNotBlank() && !busy,
                        onClick = {
                            scope.launch {
                                status = "AniSkip 조회 중..."
                                skipSegments = AniSkipDesktopService.getSkipTimes(
                                    title.trim(),
                                    episode.toIntOrNull() ?: 1,
                                    playerState.duration.roundToInt()
                                )
                                lastAutoSkippedKey = null
                                status = "AniSkip ${skipSegments.size}개 구간 발견"
                            }
                        }
                    ) { Text("AniSkip 불러오기") }
                }

                Text(status)

                info?.qualities?.takeIf { it.isNotEmpty() }?.let { qualities ->
                    Card {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("화질", style = MaterialTheme.typography.titleMedium)
                            Row {
                                qualities.forEach { quality ->
                                    FilterChip(
                                        selected = selected?.url == quality.url,
                                        onClick = { selected = quality },
                                        label = { Text(quality.label) }
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    val quality = selected ?: return@Button
                                    scope.launch {
                                        val localSubtitle = withContext(Dispatchers.IO) {
                                            info?.subtitleUrl?.let { subtitleUrl ->
                                                SubtitleDownloader.download(
                                                    subtitleUrl,
                                                    Path(
                                                        System.getProperty("user.home"),
                                                        ".lilacanime",
                                                        "subtitles"
                                                    ),
                                                    headers = info?.subtitleHeaders.orEmpty()
                                                )
                                            }
                                        }
                                        subtitle = localSubtitle
                                        runCatching {
                                            MpvPlayer.play(
                                                quality.url,
                                                localSubtitle?.toString(),
                                                quality.headers
                                            )
                                        }.onSuccess {
                                            status = "${quality.label} 재생 시작"
                                        }.onFailure {
                                            status = "mpv 실행 실패: ${it.message}"
                                        }
                                    }
                                }) { Text("mpv로 재생") }

                                OutlinedButton(onClick = {
                                    chooseSubtitle()?.let { path ->
                                        subtitle = path
                                        if (playerState.running) MpvPlayer.setSubtitle(path)
                                    }
                                }) { Text("자막 파일") }

                                if (info?.subtitleUrl != null) {
                                    Text("VTT 자동 발견", modifier = Modifier.padding(top = 10.dp))
                                }
                            }
                        }
                    }
                }

                if (playerState.running) {
                    PlayerControls(
                        state = playerState,
                        speed = speed,
                        volume = volume,
                        subtitleDelay = subtitleDelay,
                        subtitle = subtitle,
                        skip = activeSkip,
                        onPause = { MpvPlayer.togglePause() },
                        onSeek = { MpvPlayer.seek(it) },
                        onSpeed = {
                            speed = it
                            MpvPlayer.setSpeed(it)
                        },
                        onVolume = {
                            volume = it
                            MpvPlayer.setVolume(it)
                        },
                        onSubtitles = { MpvPlayer.toggleSubtitles() },
                        onDelay = {
                            subtitleDelay += it
                            MpvPlayer.subtitleDelay(it)
                        },
                        onFullscreen = { MpvPlayer.toggleFullscreen() },
                        onSkip = {
                            activeSkip?.let {
                                MpvPlayer.seek(it.endTime - playerState.position)
                                activeSkip = null
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(
    state: MpvPlayer.State,
    speed: Double,
    volume: Double,
    subtitleDelay: Long,
    subtitle: Path?,
    skip: DesktopAniSkipSegment?,
    onPause: () -> Unit,
    onSeek: (Double) -> Unit,
    onSpeed: (Double) -> Unit,
    onVolume: (Double) -> Unit,
    onSubtitles: () -> Unit,
    onDelay: (Long) -> Unit,
    onFullscreen: () -> Unit,
    onSkip: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (skip != null) {
                Button(onClick = onSkip) {
                    Text("${skip.type.uppercase()} 건너뛰기")
                }
            }

            val duration = state.duration.coerceAtLeast(1.0)
            Slider(
                value = state.position.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { target -> onSeek(target.toDouble() - state.position) },
                valueRange = 0f..duration.toFloat()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${formatTime(state.position)} / ${formatTime(state.duration)}")
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = { onSeek(-10.0) }) { Text("↶ 10") }
                Spacer(Modifier.width(5.dp))
                Button(onClick = onPause) { Text(if (state.paused) "▶" else "Ⅱ") }
                Spacer(Modifier.width(5.dp))
                FilledTonalButton(onClick = { onSeek(10.0) }) { Text("10 ↷") }
                Spacer(Modifier.width(5.dp))
                OutlinedButton(onClick = onSubtitles) { Text("자막") }
                Spacer(Modifier.width(5.dp))
                OutlinedButton(onClick = onFullscreen) { Text("전체화면") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("배속")
                Spacer(Modifier.width(6.dp))
                listOf(0.75, 1.0, 1.25, 1.5, 2.0).forEach { value ->
                    FilterChip(
                        selected = speed == value,
                        onClick = { onSpeed(value) },
                        label = { Text("${value}x") }
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("볼륨 ${volume.roundToInt()}%")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("싱크 ${subtitleDelay}ms")
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { onDelay(-250) }) { Text("-250") }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = { onDelay(250) }) { Text("+250") }
                Spacer(Modifier.width(10.dp))
                Text(subtitle?.fileName?.toString() ?: "자막 없음")
            }
        }
    }
}

private fun formatTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).roundToInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}

private fun chooseSubtitle(): Path? {
    val dialog = FileDialog(null as Frame?, "자막 파일 선택", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name ->
        val lower = name.lowercase()
        lower.endsWith(".ass") ||
            lower.endsWith(".ssa") ||
            lower.endsWith(".srt") ||
            lower.endsWith(".vtt")
    }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return Path(dialog.directory, file)
}
