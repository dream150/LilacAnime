package com.lilac.anime.player.upscaling

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** Experimental file-based decoder that sends decoded video frames through the real ONNX model. */
@Composable
fun AiUpscalingLabDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("영상 파일을 선택하세요") }
    var scale by remember { mutableStateOf(2) }
    var aiInputLimit by remember { mutableStateOf(128) }
    var busy by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("영상 파일을 선택한 뒤 재생을 시작하세요.") }
    var metrics by remember { mutableStateOf(LabMetrics()) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var sourcePreview by remember { mutableStateOf<Bitmap?>(null) }
    var session by remember { mutableStateOf<VideoAiUpscalingSession?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "선택한 영상"
            status = "선택됨. 모델 준비 후 AI 프레임 처리를 시작합니다."
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    DisposableEffect(session) {
        onDispose { session?.stop() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 업스케일링 실험실") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Text(fileName, style = MaterialTheme.typography.bodySmall) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = !busy && !playing, onClick = { picker.launch(arrayOf("video/*")) }) { Text("영상 파일 선택") }
                        listOf(2, 3, 4).forEach { factor ->
                            FilterChip(selected = scale == factor, onClick = { scale = factor }, label = { Text("${factor}x") }, enabled = !playing)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("AI 입력 폭", style = MaterialTheme.typography.labelMedium)
                        listOf(128, 256, 512).forEach { limit ->
                            FilterChip(selected = aiInputLimit == limit, onClick = { aiInputLimit = limit }, label = { Text("${limit}px") }, enabled = !playing)
                        }
                    }
                }
                item {
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                        Text("AI 모델 준비 중…")
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("원본", style = MaterialTheme.typography.labelMedium)
                            sourcePreview?.let { bitmap ->
                                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "원본 영상 프레임", modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Fit)
                            } ?: Text("대기 중", modifier = Modifier.padding(vertical = 24.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("AI 결과", style = MaterialTheme.typography.labelMedium)
                            frame?.let { bitmap ->
                                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "실시간 AI 업스케일링 출력", modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Fit)
                            } ?: Text("대기 중", modifier = Modifier.padding(vertical = 24.dp))
                        }
                    }
                }
                item { Text(status, style = MaterialTheme.typography.bodySmall) }
                item { Text("성능 지표", fontWeight = FontWeight.Bold) }
                item { MetricLine("원본 해상도", metrics.sourceResolution) }
                item { MetricLine("AI 입력 해상도", metrics.aiInputResolution + " (선택한 폭 제한: ${aiInputLimit}px)") }
                item { MetricLine("AI 출력 프레임", metrics.outputResolution) }
                item { MetricLine("모델 / 실행 장치", metrics.provider) }
                item { MetricLine("최근 추론 시간", "${metrics.lastInferenceMs} ms") }
                item { MetricLine("평균 추론 시간", "${metrics.averageInferenceMs} ms") }
                item { MetricLine("재생 기준 처리 FPS", fmt(metrics.aiFps)) }
                item { MetricLine("추론 처리량", fmt(metrics.inferenceFps) + " FPS") }
                item { MetricLine("영상 타임라인 FPS", fmt(metrics.sourceFps)) }
                item { MetricLine("누적 처리 프레임", metrics.processed.toString()) }
                item { MetricLine("건너뛴 프레임", metrics.dropped.toString()) }
                item { MetricLine("처리 지연", "${metrics.latencyMs} ms") }
                item { Text("측정 유의사항: AI 입력은 선택한 폭(128/256/512px)에 맞춰 축소합니다. 작은 입력 폭은 속도에 유리하지만 원본 디테일이 줄어듭니다. 이 결과를 전체 해상도 AI 추론 성능과 혼동하지 마세요. 오디오는 원본 재생기로 재생되며, 모델 처리량이 낮으면 화면 출력은 끊길 수 있습니다.", style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = selectedUri != null && !busy && !playing, onClick = {
                    val uri = selectedUri ?: return@Button
                    busy = true
                    status = "모델 준비 및 동영상 디코더 초기화 중…"
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { AnimeSuperResolutionRuntime.prepareAndTest(context) }
                            val next = VideoAiUpscalingSession(
                                context = context,
                                uri = uri,
                                scale = scale,
                                maxAiWidth = aiInputLimit,
                                onFrame = { original, bitmap, update -> sourcePreview = original; frame = bitmap; metrics = update },
                                onStatus = { status = it },
                                onFinished = { playing = false }
                            )
                            session?.stop()
                            session = next
                            next.start()
                            playing = true
                            status = "AI 추론을 적용하며 영상 재생 중…"
                        } catch (t: Throwable) {
                            status = "실험 실행 실패: ${t.message ?: t.javaClass.simpleName}"
                        } finally { busy = false }
                    }
                }) { Text("AI 재생 시작") }
                OutlinedButton(enabled = playing || busy, onClick = {
                    session?.stop(); session = null; playing = false; busy = false
                    status = "정지됨"
                }) { Text("정지") }
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("닫기") } }
    )
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)

private data class LabMetrics(
    val sourceResolution: String = "—",
    val aiInputResolution: String = "—",
    val outputResolution: String = "—",
    val provider: String = "—",
    val lastInferenceMs: Long = 0,
    val averageInferenceMs: Long = 0,
    val aiFps: Double = 0.0,
    val inferenceFps: Double = 0.0,
    val sourceFps: Double = 0.0,
    val processed: Long = 0,
    val dropped: Long = 0,
    val latencyMs: Long = 0
)

private class VideoAiUpscalingSession(
    private val context: Context,
    private val uri: Uri,
    private val scale: Int,
    private val maxAiWidth: Int,
    private val onFrame: (Bitmap, Bitmap, LabMetrics) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val stopped = AtomicBoolean(false)
    private val processed = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val decodedFrames = AtomicLong(0)
    private val inferenceTotalMs = AtomicLong(0)
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var extractor: MediaExtractor? = null
    @Volatile private var sourcePfd: ParcelFileDescriptor? = null
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var decodeThread: Thread? = null
    @Volatile private var sourceW = 0
    @Volatile private var sourceH = 0
    @Volatile private var sourceFps = 0.0
    @Volatile private var lastInferenceMs = 0L
    @Volatile private var latestOutputBitmap: Bitmap? = null
    @Volatile private var latestSourceBitmap: Bitmap? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        Thread({
            try {
                decode()
            } catch (t: Throwable) {
                Log.e("LilacAiUpscaler", "LAB_DECODE_FAILED", t)
                if (!stopped.get()) mainHandler.post {
                    onStatus("디코더 오류: ${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                cleanup()
                mainHandler.post { onFinished() }
            }
        }, "LilacAiUpscaleDecode").also { decodeThread = it; it.start() }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { player?.pause() }
        decodeThread?.interrupt()
    }

    private fun decode() {
        val ex = MediaExtractor()
        extractor = ex
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("영상 파일을 열 수 없습니다.")
        sourcePfd = pfd
        ex.setDataSource(pfd.fileDescriptor)

        var videoTrack = -1
        var videoFormat: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val trackFormat = ex.getTrackFormat(i)
            val trackMime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
            if (trackMime.startsWith("video/") && videoTrack < 0) {
                videoTrack = i
                videoFormat = trackFormat
            }
        }
        val format = videoFormat ?: throw IllegalStateException("영상 트랙을 찾을 수 없습니다.")
        sourceW = format.getInteger(MediaFormat.KEY_WIDTH)
        sourceH = format.getInteger(MediaFormat.KEY_HEIGHT)
        sourceFps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
        } else 0.0
        ex.selectTrack(videoTrack)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalStateException("코덱 MIME이 없습니다.")

        // Important: do NOT attach an ImageReader Surface. Several devices reject this
        // Surface configuration for decoder output (setOutputSurface/BAD_INDEX). In
        // byte-buffer mode Android exposes flexible YUV frames via getOutputImage().
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        val mediaCodec = MediaCodec.createDecoderByType(mime)
        codec = mediaCodec
        mediaCodec.configure(format, null, null, 0)
        mediaCodec.start()
        Log.i("LilacAiUpscaler", "LAB_DECODER_READY mime=$mime mode=BYTEBUFFER_YUV420 width=$sourceW height=$sourceH")

        val mp = MediaPlayer()
        player = mp
        mp.setDataSource(context, uri)
        mp.setOnCompletionListener { mainHandler.post { onStatus("원본 오디오 재생 완료 · AI 프레임 처리를 마무리 중") } }
        mp.prepare()
        mp.start()
        val playbackStartNs = System.nanoTime()
        var firstPtsUs = -1L
        var inputEos = false
        var outputEos = false
        val info = MediaCodec.BufferInfo()
        mainHandler.post {
            onStatus("AI 프레임 처리 중 · ${sourceW}×${sourceH} · ${scale}x · ${AnimeSuperResolutionRuntime.providerName()} · YUV 버퍼 디코딩")
        }

        while (!stopped.get() && !outputEos) {
            if (!inputEos) {
                val inputIndex = mediaCodec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = mediaCodec.getInputBuffer(inputIndex)
                        ?: throw IllegalStateException("디코더 입력 버퍼를 가져오지 못했습니다.")
                    inputBuffer.clear()
                    val sampleSize = ex.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        mediaCodec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    } else {
                        mediaCodec.queueInputBuffer(inputIndex, 0, sampleSize, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }

            when (val outputIndex = mediaCodec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFormat = mediaCodec.outputFormat
                    Log.i("LilacAiUpscaler", "LAB_OUTPUT_FORMAT $outFormat")
                }
                else -> if (outputIndex >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0 && !stopped.get()) {
                        decodedFrames.incrementAndGet()
                        val ptsUs = info.presentationTimeUs.coerceAtLeast(0L)
                        if (firstPtsUs < 0L) firstPtsUs = ptsUs
                        // Pace decoded frames against the audio/video timeline. If AI inference
                        // is slower than playback, no sleep occurs and latency grows naturally.
                        val targetNs = playbackStartNs + (ptsUs - firstPtsUs).coerceAtLeast(0L) * 1_000L
                        while (!stopped.get()) {
                            val remainingNs = targetNs - System.nanoTime()
                            if (remainingNs <= 0L) break
                            try {
                                Thread.sleep((remainingNs / 1_000_000L).coerceAtMost(8L).coerceAtLeast(1L))
                            } catch (_: InterruptedException) {
                                if (stopped.get()) break
                            }
                        }
                        if (!stopped.get()) {
                            val image = try {
                                mediaCodec.getOutputImage(outputIndex)
                            } catch (t: Throwable) {
                                Log.e("LilacAiUpscaler", "GET_OUTPUT_IMAGE_FAILED index=$outputIndex", t)
                                null
                            }
                            if (image == null) {
                                dropped.incrementAndGet()
                                mainHandler.post { onStatus("디코더가 YUV 프레임을 제공하지 않았습니다. 다른 코덱으로 다시 시도해 주세요.") }
                            } else {
                                try {
                                    processFrame(image, ptsUs, playbackStartNs, targetNs)
                                } catch (t: Throwable) {
                                    dropped.incrementAndGet()
                                    Log.e("LilacAiUpscaler", "VIDEO_FRAME_INFERENCE_FAILED ptsUs=$ptsUs", t)
                                    mainHandler.post { onStatus("프레임 AI 처리 실패: ${t.message ?: t.javaClass.simpleName}") }
                                } finally {
                                    runCatching { image.close() }
                                }
                            }
                        }
                    }
                    mediaCodec.releaseOutputBuffer(outputIndex, false)
                    if (eos) outputEos = true
                }
            }
        }
    }

    private fun processFrame(image: Image, ptsUs: Long, playbackStartNs: Long, targetNs: Long) {
        val inputBitmap = imageToBitmap(image)
        val sourcePreviewBitmap = if (inputBitmap.width > 320) {
            val h = max(1, (inputBitmap.height * 320f / inputBitmap.width).toInt())
            Bitmap.createScaledBitmap(inputBitmap, 320, h, true)
        } else Bitmap.createBitmap(inputBitmap)
        val aiInput = if (inputBitmap.width > maxAiWidth) {
            val h = max(1, (inputBitmap.height * maxAiWidth.toFloat() / inputBitmap.width).toInt())
            Bitmap.createScaledBitmap(inputBitmap, maxAiWidth, h, true)
        } else inputBitmap

        val started = System.nanoTime()
        val aiOutput = try {
            AnimeSuperResolutionRuntime.upscaleBitmap(aiInput, scale)
        } finally {
            if (aiInput !== inputBitmap && !aiInput.isRecycled) aiInput.recycle()
            if (!inputBitmap.isRecycled) inputBitmap.recycle()
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        lastInferenceMs = elapsed
        inferenceTotalMs.addAndGet(elapsed)
        val count = processed.incrementAndGet()
        val output = if (aiOutput.width > 1280) {
            val h = max(1, (aiOutput.height * 1280f / aiOutput.width).toInt())
            Bitmap.createScaledBitmap(aiOutput, 1280, h, true).also { aiOutput.recycle() }
        } else aiOutput

        val wallElapsedMs = max(1L, (System.nanoTime() - playbackStartNs) / 1_000_000L)
        val inferenceMs = max(1L, inferenceTotalMs.get())
        val targetElapsedMs = max(0L, (targetNs - playbackStartNs) / 1_000_000L)
        val latencyMs = max(0L, (System.nanoTime() - targetNs) / 1_000_000L)
        val metrics = LabMetrics(
            sourceResolution = "${sourceW}×${sourceH}",
            aiInputResolution = "${aiInput.width}×${aiInput.height}",
            outputResolution = "${output.width}×${output.height} (미리보기; 목표 ${sourceW * scale}×${sourceH * scale})",
            provider = AnimeSuperResolutionRuntime.providerName(),
            lastInferenceMs = elapsed,
            averageInferenceMs = inferenceTotalMs.get() / count,
            aiFps = count * 1000.0 / wallElapsedMs,
            inferenceFps = count * 1000.0 / inferenceMs,
            sourceFps = sourceFps,
            processed = count,
            dropped = dropped.get(),
            latencyMs = latencyMs
        )
        Log.d("LilacAiUpscaler", "FRAME_DONE ptsUs=$ptsUs inferenceMs=$elapsed processed=$count playbackFps=${metrics.aiFps} inferenceFps=${metrics.inferenceFps} latencyMs=$latencyMs")
        val previousSource = latestSourceBitmap
        val previousOutput = latestOutputBitmap
        latestSourceBitmap = sourcePreviewBitmap
        latestOutputBitmap = output
        mainHandler.post {
            onFrame(sourcePreviewBitmap, output, metrics)
            if (previousSource != null && previousSource !== sourcePreviewBitmap) {
                mainHandler.postDelayed({ runCatching { if (!previousSource.isRecycled) previousSource.recycle() } }, 1200L)
            }
            if (previousOutput != null && previousOutput !== output) {
                mainHandler.postDelayed({ runCatching { if (!previousOutput.isRecycled) previousOutput.recycle() } }, 1200L)
            }
        }
    }

    private fun cleanup() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { extractor?.release() }
        extractor = null
        runCatching { sourcePfd?.close() }
        sourcePfd = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        mainHandler.postDelayed({
            runCatching { latestSourceBitmap?.let { if (!it.isRecycled) it.recycle() } }
            runCatching { latestOutputBitmap?.let { if (!it.isRecycled) it.recycle() } }
            latestSourceBitmap = null
            latestOutputBitmap = null
        }, 1500L)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val yBase = y * yRow
            val uvY = y shr 1
            for (x in 0 until w) {
                val yy = (yBuf.get(yBase + x * yPix).toInt() and 0xff) - 16
                val uvX = x shr 1
                val uu = (uBuf.get(uvY * uRow + uvX * uPix).toInt() and 0xff) - 128
                val vv = (vBuf.get(uvY * vRow + uvX * vPix).toInt() and 0xff) - 128
                val c = max(0, yy)
                val r = ((298 * c + 409 * vv + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * uu - 208 * vv + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * uu + 128) shr 8).coerceIn(0, 255)
                pixels[y * w + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
