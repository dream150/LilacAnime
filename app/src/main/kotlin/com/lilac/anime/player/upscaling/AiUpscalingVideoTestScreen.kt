@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lilac.anime.player.upscaling

import android.app.Activity
import android.net.Uri
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.effect.LanczosResample
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Full-screen Media3 playback test. mpv remains the production player; this route is isolated
 * for testing Media3's GPU Lanczos resampling and playback performance.
 * AnimeSharpV4 ONNX inference is intentionally reported as not connected: feeding its Bitmap
 * runtime into Media3's per-frame GL pipeline requires a separate GPU output implementation.
 */
@Composable
fun AiUpscalingVideoTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var videoName by remember { mutableStateOf("영상 파일을 선택하세요") }
    var showMetrics by remember { mutableStateOf(true) }
    var playbackState by remember { mutableStateOf("대기 중") }
    var videoSize by remember { mutableStateOf("—") }
    var renderedFps by remember { mutableStateOf(0.0) }
    var droppedFrames by remember { mutableLongStateOf(0L) }
    var totalRenderedFrames by remember { mutableLongStateOf(0L) }
    var bufferingMs by remember { mutableLongStateOf(0L) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var lastFpsWindowMs by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            videoName = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
            lastError = null
        }
    }

    val player = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            // GPU Lanczos resampling produces a 1440p landscape/portrait frame from smaller input.
            // This is a real-time spatial resampling test, not the AnimeSharp neural model yet.
            setVideoEffects(listOf(LanczosResample.scaleToFitWithFlexibleOrientation(2560, 1440)))
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    playbackState = when (state) {
                        Player.STATE_IDLE -> "대기 중"
                        Player.STATE_BUFFERING -> "버퍼링"
                        Player.STATE_READY -> if (playerIsPlaying(this@apply)) "재생 중" else "일시 정지"
                        Player.STATE_ENDED -> "재생 완료"
                        else -> "상태 $state"
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playbackState = if (isPlaying) "재생 중" else if (playbackState != "재생 완료") "일시 정지" else playbackState
                }

                override fun onVideoSizeChanged(size: VideoSize) {
                    videoSize = "${size.width}×${size.height}"
                }

                override fun onPlayerError(error: PlaybackException) {
                    lastError = "재생 오류: ${error.errorCodeName} — ${error.message ?: "원인 미상"}"
                    playbackState = "오류"
                }
            })
            addAnalyticsListener(object : AnalyticsListener {
                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFramesCount: Int,
                    elapsedMs: Long
                ) {
                    droppedFrames += droppedFramesCount.toLong()
                }

                override fun onVideoFrameProcessingOffset(
                    eventTime: AnalyticsListener.EventTime,
                    totalProcessingOffsetUs: Long,
                    frameCount: Int
                ) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val elapsed = (now - lastFpsWindowMs).coerceAtLeast(1L)
                    totalRenderedFrames += frameCount.toLong()
                    if (elapsed >= 500L) {
                        renderedFps = frameCount * 1000.0 / elapsed
                        lastFpsWindowMs = now
                    }
                }

                override fun onRenderedFirstFrame(
                    eventTime: AnalyticsListener.EventTime,
                    output: Any,
                    renderTimeMs: Long
                ) {
                    // First-frame callback only; per-second frame rate is sampled from video-size
                    // and playback-position progress below to avoid pretending this is an inference FPS.
                    if (totalRenderedFrames == 0L) totalRenderedFrames = 1L
                }
            })
        }
    }

    LaunchedEffect(selectedUri, player) {
        val uri = selectedUri ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
        playbackState = "준비 중"
        totalRenderedFrames = 0L
        droppedFrames = 0L
        bufferingMs = 0L
        lastFpsWindowMs = android.os.SystemClock.elapsedRealtime()
    }

    LaunchedEffect(player, selectedUri) {
        while (true) {
            delay(500)
            if (!player.isPlaying) renderedFps = 0.0
        }
    }

    DisposableEffect(player, activity) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        if (window != null) WindowCompat.setDecorFitsSystemWindows(window, false)
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (window != null) WindowCompat.setDecorFitsSystemWindows(window, true)
            player.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { it.player = player }
        )

        // Initial picker remains over the full-screen surface; video itself is never placed in a small preview.
        if (selectedUri == null) {
            Column(
                Modifier.align(Alignment.Center).background(Color(0xDD111318), RoundedCornerShape(18.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Media3 업스케일링 테스트", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("영상을 선택하면 전체 화면으로 재생합니다.", color = Color.LightGray)
                Button(onClick = { picker.launch(arrayOf("video/*")) }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("영상 선택")
                }
            }
        }

        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "뒤로", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("Media3 · $playbackState", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(videoName, color = Color.LightGray, fontSize = 10.sp, maxLines = 1)
            }
            TextButton(onClick = { showMetrics = !showMetrics }) {
                Text(if (showMetrics) "지표 숨김" else "지표 표시", color = Color.White, fontSize = 11.sp)
            }
        }

        if (showMetrics && selectedUri != null) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(12.dp)
                    .widthIn(max = 340.dp)
                    .background(Color(0xD9101115), RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("실시간 성능 오버레이", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                MetricLine("원본 디코더 해상도", videoSize)
                MetricLine("출력 효과", "GPU Lanczos · 최대 2560×1440")
                MetricLine("재생 진행률 추정", String.format(Locale.US, "%.1f fps-equivalent", renderedFps))
                MetricLine("드롭 프레임", droppedFrames.toString())
                MetricLine("재생 위치", "${player.currentPosition / 1000}s / ${if (player.duration > 0) player.duration / 1000 else "—"}s")
                HorizontalDivider(color = Color(0x55FFFFFF))
                Text("AnimeSharpV4 ONNX: 아직 프레임 효과에 연결되지 않음", color = Color(0xFFFFD180), fontSize = 11.sp)
                Text("이 화면은 Media3의 GPU 리샘플링 테스트이며 AI 추론 FPS가 아닙니다.", color = Color.LightGray, fontSize = 10.sp)
                lastError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun playerIsPlaying(player: Player): Boolean = player.isPlaying

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
