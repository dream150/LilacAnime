package com.lilac.anime.data.subtitle.whisper

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Composition
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.RandomAccessFile
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Japanese speech-to-SRT pipeline using whisper.cpp and a multilingual ggml model. */
@OptIn(UnstableApi::class)
object WhisperTranscriptionService {
    private const val TAG = "LilacWhisper"
    private const val TARGET_RATE = 16_000
    private const val PREFS = "whisper_model_preferences"
    private const val PREF_MODEL = "selected_model"

    data class ModelOption(val id: String, val title: String, val fileName: String, val url: String, val minBytes: Long, val note: String)
    val modelOptions = listOf(
        ModelOption("tiny", "Tiny · 약 75 MB", "ggml-tiny.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin", 30_000_000L, "가장 가벼움 · 저사양 기기 권장"),
        ModelOption("base", "Base · 약 142 MB", "ggml-base.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin", 60_000_000L, "인식 정확도와 메모리 사용량의 균형"),
        ModelOption("small", "Small · 약 466 MB", "ggml-small.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin", 200_000_000L, "정확도 우선 · 메모리 부족 가능성 높음")
    )
    fun selectedModelId(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(PREF_MODEL, "tiny")?.takeIf { id -> modelOptions.any { it.id == id } } ?: "tiny"
    fun setSelectedModel(context: Context, id: String) {
        require(modelOptions.any { it.id == id }) { "지원하지 않는 Whisper 모델입니다." }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_MODEL, id).apply()
    }
    fun option(context: Context) = modelOptions.first { it.id == selectedModelId(context) }
    fun modelFile(context: Context, id: String = selectedModelId(context)): File {
        val option = modelOptions.first { it.id == id }
        return File(File(context.filesDir, "whisper/models").apply { mkdirs() }, option.fileName)
    }
    fun isModelInstalled(context: Context, id: String = selectedModelId(context)) =
        modelFile(context, id).let { file -> file.isFile && file.length() > modelOptions.first { it.id == id }.minBytes }

    suspend fun downloadModel(context: Context, onProgress: (Int) -> Unit = {}): File = downloadModel(context, selectedModelId(context), onProgress)

    suspend fun downloadModel(context: Context, modelId: String, onProgress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val option = modelOptions.firstOrNull { it.id == modelId } ?: error("지원하지 않는 Whisper 모델입니다.")
        val target = modelFile(context, modelId)
        if (isModelInstalled(context, modelId)) return@withContext target
        val partial = File(target.parentFile, "${option.fileName}.part")
        val connection = (URL(option.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LilacAnime/Whisper")
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "Whisper 모델 다운로드 실패: HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            connection.inputStream.use { input -> FileOutputStream(partial).use { output ->
                val buffer = ByteArray(1024 * 256); var count: Long = 0
                while (true) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n); count += n
                    if (total > 0) onProgress(((count * 100 / total).toInt()).coerceIn(0, 100))
                }
                output.fd.sync()
            } }
            check(partial.length() > option.minBytes) { "다운로드된 ${option.title} 모델 크기가 올바르지 않습니다." }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Whisper 모델 파일을 저장하지 못했습니다." }
            target
        } catch (t: Throwable) { partial.delete(); throw t }
        finally { connection.disconnect() }
    }

    /** Supports local files and direct media URLs that Android MediaExtractor can demux. */
    suspend fun transcribe(context: Context, mediaSource: String, headers: Map<String, String> = emptyMap(), onProgress: (String) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val selectedModel = selectedModelId(context)
        check(isModelInstalled(context, selectedModel)) { "Whisper ${selectedModel} 모델이 설치되지 않았습니다. 설정에서 선택한 모델을 다운로드하세요." }
        require(mediaSource.isNotBlank()) { "영상 주소가 비어 있습니다." }
        val outputDir = File(context.filesDir, "whisper/subtitles").apply { mkdirs() }
        val key = mediaSource.hashCode().toUInt().toString(16) + "-" + selectedModel
        val srt = File(outputDir, "$key.srt")
        if (srt.isFile && srt.length() > 0L) return@withContext srt
        val wav = File(outputDir, "$key.wav")
        onProgress("영상에서 일본어 음성 추출 중")
        val sourceForDecode = if (mediaSource.contains(".m3u8", ignoreCase = true)) {
            onProgress("Media3로 HLS 오디오 추출 중")
            val extracted = File(outputDir, "$key-audio.mp4")
            exportAudioOnly(context, mediaSource, extracted)
            extracted.absolutePath
        } else mediaSource
        try {
            decodeToWav(sourceForDecode, headers, wav)
            onProgress("Whisper 일본어 음성 인식 중 (30초 단위 처리)")
            val result = try {
                WhisperNative.transcribeWav(modelFile(context, selectedModel).absolutePath, wav.absolutePath)
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "TRANSCRIPTION_OOM", oom)
                throw IllegalStateException("메모리가 부족합니다. 다른 앱을 종료한 뒤 다시 시도하세요.", oom)
            } catch (t: Throwable) {
                Log.e(TAG, "TRANSCRIPTION_FAILED", t)
                throw t
            }
            check(result.isNotBlank()) { "Whisper가 음성을 인식하지 못했습니다. 음성 트랙이나 미디어 형식을 확인하세요." }
            srt.writeText(result, Charsets.UTF_8)
            Log.i(TAG, "TRANSCRIPTION_COMPLETE sourceKey=$key chars=${result.length} output=${srt.name}")
            srt
        } finally {
            wav.delete()
            if (sourceForDecode != mediaSource) File(sourceForDecode).delete()
            // Native allocations are released by whisper_free() in JNI. GC is only a fallback
            // for short-lived Java wrappers after a failed native call, not the primary OOM fix.
            System.runFinalization()
            System.gc()
        }
    }

    private suspend fun exportAudioOnly(context: Context, source: String, target: File): String = suspendCancellableCoroutine { cont ->
        if (target.isFile && target.length() > 0L) { cont.resume(target.absolutePath); return@suspendCancellableCoroutine }
        val transformer = runCatching {
            Transformer.Builder(context.applicationContext)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(target.absolutePath)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        target.delete()
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
        }.getOrElse { cont.resumeWithException(it); return@suspendCancellableCoroutine }
        cont.invokeOnCancellation { runCatching { transformer.cancel() }; target.delete() }
        runCatching {
            val item = EditedMediaItem.Builder(MediaItem.fromUri(source)).setRemoveVideo(true).build()
            transformer.start(item, target.absolutePath)
        }.onFailure { if (cont.isActive) cont.resumeWithException(it) }
    }

    private fun decodeToWav(source: String, headers: Map<String, String>, wav: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val raw = File(wav.parentFile, wav.nameWithoutExtension + ".pcm.tmp")
        try {
            if (source.startsWith("http://") || source.startsWith("https://")) extractor.setDataSource(source, headers)
            else extractor.setDataSource(source.removePrefix("file://"))
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            }
            check(track >= 0) { "영상에 오디오 트랙이 없습니다." }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("알 수 없는 오디오 코덱")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            FileOutputStream(raw).buffered(256 * 1024).use { rawOut ->
                val timeout = 10_000L
                while (!outputDone) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(timeout)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, timeout)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = codec.outputFormat
                            if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (index >= 0) {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val chunk = ByteArray(minOf(info.size, 64 * 1024))
                                while (buffer.hasRemaining()) { val n = minOf(buffer.remaining(), chunk.size); buffer.get(chunk, 0, n); rawOut.write(chunk, 0, n) }
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            check(raw.length() > 0L && sampleRate > 0 && channels > 0) { "오디오 디코딩 결과가 비어 있습니다." }
            // Convert interleaved PCM to mono 16 kHz directly from disk. Never hold the whole episode in RAM.
            RandomAccessFile(wav, "rw").use { out ->
                out.setLength(0)
                val header = ByteArray(44)
                fun ascii(offset: Int, value: String) { value.toByteArray(Charsets.US_ASCII).copyInto(header, offset) }
                fun u16(offset: Int, value: Int) { header[offset] = value.toByte(); header[offset + 1] = (value shr 8).toByte() }
                fun u32(offset: Int, value: Long) { for (i in 0..3) header[offset + i] = (value shr (8 * i)).toByte() }
                ascii(0,"RIFF"); ascii(8,"WAVE"); ascii(12,"fmt "); u32(16,16); u16(20,1); u16(22,1); u32(24,TARGET_RATE.toLong()); u32(28,(TARGET_RATE * 2).toLong()); u16(32,2); u16(34,16); ascii(36,"data")
                out.write(header)
                DataInputStream(BufferedInputStream(raw.inputStream(), 256 * 1024)).use { input ->
                    val frameBytes = channels * 2
                    var previous = IntArray(channels)
                    var current = IntArray(channels)
                    fun readFrame(dest: IntArray): Boolean {
                        for (c in 0 until channels) {
                            val lo = input.read(); if (lo < 0) return false
                            val hi = input.read(); if (hi < 0) return false
                            dest[c] = ((lo and 0xff) or (hi shl 8)).toShort().toInt()
                        }
                        return true
                    }
                    if (readFrame(previous)) {
                        var sourceIndex = 1L
                        var outputIndex = 1L
                        var dataBytes = 0L
                        val bytes = ByteArray(32 * 1024); var byteCount = 0
                        fun flush() { if (byteCount > 0) { out.write(bytes, 0, byteCount); dataBytes += byteCount; byteCount = 0 } }
                        fun emit(sample: Int) { bytes[byteCount++] = sample.toByte(); bytes[byteCount++] = (sample shr 8).toByte(); if (byteCount >= bytes.size - 2) flush() }
                        // Emit sample zero, then interpolate as source frames arrive.
                        emit((previous.sum().toDouble() / channels).toInt().coerceIn(-32768, 32767))
                        while (readFrame(current)) {
                            val maxOut = (sourceIndex.toDouble() * TARGET_RATE / sampleRate).toLong()
                            while (outputIndex < maxOut) {
                                val pos = outputIndex.toDouble() * sampleRate / TARGET_RATE
                                val frac = (pos - (sourceIndex - 1)).coerceIn(0.0, 1.0)
                                var sum = 0.0
                                for (c in 0 until channels) sum += previous[c] * (1.0 - frac) + current[c] * frac
                                emit((sum / channels).toInt().coerceIn(-32768, 32767)); outputIndex++
                            }
                            val tmp = previous; previous = current; current = tmp; sourceIndex++
                        }
                        flush()
                        out.seek(0); ascii(0,"RIFF"); u32(4,36L + dataBytes); ascii(8,"WAVE"); ascii(12,"fmt "); u32(16,16); u16(20,1); u16(22,1); u32(24,TARGET_RATE.toLong()); u32(28,(TARGET_RATE * 2).toLong()); u16(32,2); u16(34,16); ascii(36,"data"); u32(40,dataBytes); out.write(header)
                    }
                }
            }
            check(wav.length() > 44L) { "변환된 WAV 음성이 비어 있습니다." }
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release(); raw.delete()
        }
    }

}
