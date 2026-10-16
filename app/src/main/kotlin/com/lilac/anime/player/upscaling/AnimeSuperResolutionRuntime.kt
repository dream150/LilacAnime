package com.lilac.anime.player.upscaling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.ShortBuffer
import java.util.concurrent.TimeUnit
import java.util.EnumSet
import kotlin.math.min

/**
 * AnimeSharpV4 Fast RCAN PixelUnshuffle 2x FP16 ONNX runtime.
 * This performs local inference on Bitmap frames. The current libmpv AAR does not
 * expose decoded frames to Kotlin, so this runtime is currently used by the test
 * pipeline and is not yet injected into every live mpv frame.
 */
object AnimeSuperResolutionRuntime {
    private const val TAG = "LilacAiUpscaler"
    // Pin to an immutable Hugging Face revision so a model update cannot silently change the artifact.
    private const val MODEL_URL = "https://huggingface.co/Kim2091/2x-AnimeSharpV4/resolve/1a9339b/2x-AnimeSharpV4_Fast_RCAN_PU_fp16_opset17.onnx?download=true"
    private const val MODEL_FILE = "2x-animesharpv4-fast-rcan-pu-fp16-opset17.onnx"
    private const val MIN_MODEL_BYTES = 25L * 1024L * 1024L
    private const val MAX_MODEL_BYTES = 45L * 1024L * 1024L
    private const val MODEL_SCALE = 2
    private const val TILE_SIZE = 128 // Kept for profile/API compatibility; whole-frame path does not tile.

    data class RuntimeStatus(
        val modelReady: Boolean,
        val provider: String,
        val inferenceMillis: Long = 0L,
        val previewPath: String? = null,
        val message: String
    )

    data class FrameProfile(
        val totalMillis: Long,
        val inputMillis: Long,
        val inferenceMillis: Long,
        val outputMillis: Long,
        val tileCount: Int,
        val tileSize: Int
    )

    @Volatile private var latestFrameProfile = FrameProfile(0, 0, 0, 0, 0, TILE_SIZE)
    fun lastFrameProfile(): FrameProfile = latestFrameProfile

    private val lock = Any()
    @Volatile private var session: OrtSession? = null
    @Volatile private var activeProvider: String = "uninitialized"
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    fun modelFile(context: Context): File = File(File(context.filesDir, "ai-upscaling"), MODEL_FILE)

    suspend fun prepareAndTest(context: Context): RuntimeStatus = withContext(Dispatchers.IO) {
        synchronized(lock) {
            ensureModel(context.applicationContext)
            ensureSession(context.applicationContext)
        }
        val input = makeDiagnosticBitmap(64, 64)
        val started = System.nanoTime()
        val output = try {
            upscaleBitmap(input, 2)
        } catch (firstFailure: Throwable) {
            if (activeProvider != "NNAPI") throw firstFailure
            Log.w(TAG, "NNAPI_INFERENCE_FAILED fallback=CPU", firstFailure)
            synchronized(lock) {
                runCatching { session?.close() }
                session = null
                createCpuSession(context.applicationContext)
            }
            upscaleBitmap(input, 2)
        }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        val outputFile = File(context.cacheDir, "ai-upscaling-preview.png")
        FileOutputStream(outputFile).use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
        input.recycle()
        output.recycle()
        val status = RuntimeStatus(true, activeProvider, elapsed, outputFile.absolutePath,
            "AnimeSharpV4 Fast 2x FP16 ONNX 추론 성공 (${activeProvider}, ${elapsed}ms). 라이브 mpv 프레임 연결은 아직 별도 단계입니다.")
        Log.i(TAG, "MODEL_INFERENCE_TEST_OK model=AnimeSharpV4-Fast-RCAN-PU scale=2x provider=$activeProvider elapsedMs=$elapsed preview=${outputFile.name}")
        status
    }

    fun isReady(): Boolean = session != null
    fun providerName(): String = activeProvider

    /** Run one ONNX inference for the entire frame (no spatial tiling). */
    fun upscaleBitmap(source: Bitmap, requestedScale: Int): Bitmap {
        val modelSession = session ?: throw IllegalStateException("AI 모델이 준비되지 않았습니다. 설정에서 모델 테스트를 먼저 실행하세요.")
        require(requestedScale in 2..4) { "지원 배율은 2x, 3x, 4x입니다." }
        require(source.width > 0 && source.height > 0)

        val totalStart = System.nanoTime()
        val inputStart = System.nanoTime()
        // PixelUnshuffle requires even spatial dimensions. Pad only the right/bottom edge
        // for odd frame dimensions, then crop the padded region from the output.
        val tensorW = if (source.width % 2 == 0) source.width else source.width + 1
        val tensorH = if (source.height % 2 == 0) source.height else source.height + 1
        val sourcePixels = IntArray(source.width * source.height)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        val input = FloatArray(3 * tensorW * tensorH)
        var p = 0
        for (c in 0..2) {
            for (y in 0 until tensorH) {
                val sy = min(y, source.height - 1)
                for (x in 0 until tensorW) {
                    val sx = min(x, source.width - 1)
                    val argb = sourcePixels[sy * source.width + sx]
                    val channel = when (c) {
                        0 -> Color.red(argb)
                        1 -> Color.green(argb)
                        else -> Color.blue(argb)
                    }
                    input[p++] = channel / 255.0f
                }
            }
        }
        val inputHalf = ShortArray(input.size)
        for (i in input.indices) inputHalf[i] = floatToHalf(input[i])
        val tensor = OnnxTensor.createTensor(
            env,
            ShortBuffer.wrap(inputHalf),
            longArrayOf(1, 3, tensorH.toLong(), tensorW.toLong()),
            OnnxJavaType.FLOAT16
        )
        val inputNanos = System.nanoTime() - inputStart

        val inferStart = System.nanoTime()
        val result = try {
            val inputName = modelSession.inputNames.firstOrNull()
                ?: throw IllegalStateException("AI 모델 입력 이름을 찾을 수 없습니다.")
            modelSession.run(mapOf(inputName to tensor))
        } catch (t: Throwable) {
            tensor.close()
            throw IllegalStateException("ONNX 전체 프레임 추론 실패 (frame=${source.width}x${source.height}, padded=${tensorW}x${tensorH}, provider=$activeProvider)", t)
        }
        val inferenceNanos = System.nanoTime() - inferStart
        val outputStart = System.nanoTime()
        val outputWidth = source.width * requestedScale
        val outputHeight = source.height * requestedScale
        val outputPixels = IntArray(outputWidth * outputHeight)
        try {
            val outputName = modelSession.outputNames.firstOrNull()
                ?: throw IllegalStateException("AI 모델 출력 이름을 찾을 수 없습니다.")
            val value = (result[outputName].orElse(null) as? OnnxTensor)
                ?: throw IllegalStateException("AI 모델 출력 텐서($outputName)를 찾을 수 없습니다.")
            val shape = value.info.shape
            val outH = shape[2].toInt()
            val outW = shape[3].toInt()
            val expectedW = tensorW * MODEL_SCALE
            val expectedH = tensorH * MODEL_SCALE
            if (outW != expectedW || outH != expectedH) {
                throw IllegalStateException("AnimeSharpV4 Fast 출력 크기가 예상과 다릅니다: ${outW}x$outH, 입력=${tensorW}x${tensorH}, 예상=${expectedW}x${expectedH}")
            }
            val fb = value.floatBuffer ?: throw IllegalStateException("AI 모델 출력이 Float 텐서가 아닙니다.")
            val count = outW * outH
            val r = FloatArray(count)
            val g = FloatArray(count)
            val b = FloatArray(count)
            fb.rewind(); fb.get(r); fb.get(g); fb.get(b)
            // Resample the model's native 2x output only if a 3x/4x preview was requested.
            // Coordinates are based on the unpadded source dimensions to crop padding cleanly.
            for (dy in 0 until outputHeight) {
                val sy = ((dy + 0.5f) * MODEL_SCALE.toFloat() / requestedScale - 0.5f)
                    .coerceIn(0f, (source.height * MODEL_SCALE - 1).toFloat())
                val y0 = sy.toInt(); val y1 = min(source.height * MODEL_SCALE - 1, y0 + 1); val fy = sy - y0
                for (dx in 0 until outputWidth) {
                    val sx = ((dx + 0.5f) * MODEL_SCALE.toFloat() / requestedScale - 0.5f)
                        .coerceIn(0f, (source.width * MODEL_SCALE - 1).toFloat())
                    val x0 = sx.toInt(); val x1 = min(source.width * MODEL_SCALE - 1, x0 + 1); val fx = sx - x0
                    val i00 = y0 * outW + x0; val i01 = y0 * outW + x1
                    val i10 = y1 * outW + x0; val i11 = y1 * outW + x1
                    fun sample(channel: FloatArray): Int {
                        val top = channel[i00] * (1f - fx) + channel[i01] * fx
                        val bottom = channel[i10] * (1f - fx) + channel[i11] * fx
                        return (((top * (1f - fy) + bottom * fy).coerceIn(0f, 1f) * 255f) + 0.5f).toInt()
                    }
                    outputPixels[dy * outputWidth + dx] = Color.rgb(sample(r), sample(g), sample(b))
                }
            }
        } finally {
            result.close()
            tensor.close()
        }
        val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(outputPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
        val elapsedNanos = System.nanoTime() - totalStart
        val outputNanos = (elapsedNanos - inputNanos - inferenceNanos).coerceAtLeast(0L)
        val totalMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
        val frameEdge = maxOf(source.width, source.height)
        latestFrameProfile = FrameProfile(
            totalMillis,
            TimeUnit.NANOSECONDS.toMillis(inputNanos),
            TimeUnit.NANOSECONDS.toMillis(inferenceNanos),
            TimeUnit.NANOSECONDS.toMillis(outputNanos),
            1,
            frameEdge
        )
        Log.i(TAG, "AI_UPSCALING_PROFILE frame=${source.width}x${source.height} out=${outputWidth}x${outputHeight} scale=${requestedScale}x provider=$activeProvider mode=whole-frame batch=1 inputMs=${latestFrameProfile.inputMillis} inferenceMs=${latestFrameProfile.inferenceMillis} outputMs=${latestFrameProfile.outputMillis} totalMs=$totalMillis")
        return bitmap
    }


    /** Convert IEEE-754 float32 to IEEE-754 binary16 bits for the FP16 ONNX model. */
    private fun floatToHalf(value: Float): Short {
        val bits = java.lang.Float.floatToIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        var exponent = ((bits ushr 23) and 0xff) - 127 + 15
        var mantissa = bits and 0x7fffff
        val halfBits = when {
            ((bits ushr 23) and 0xff) == 0xff -> {
                if (mantissa == 0) sign or 0x7c00 else sign or 0x7e00
            }
            exponent <= 0 -> {
                if (exponent < -10) sign
                else {
                    mantissa = mantissa or 0x800000
                    val shift = 14 - exponent
                    var halfMantissa = mantissa ushr shift
                    val remainder = mantissa and ((1 shl shift) - 1)
                    val halfway = 1 shl (shift - 1)
                    if (remainder > halfway || (remainder == halfway && (halfMantissa and 1) != 0)) halfMantissa++
                    sign or halfMantissa
                }
            }
            exponent >= 31 -> sign or 0x7c00
            else -> {
                var halfMantissa = mantissa ushr 13
                val remainder = mantissa and 0x1fff
                if (remainder > 0x1000 || (remainder == 0x1000 && (halfMantissa and 1) != 0)) {
                    halfMantissa++
                    if (halfMantissa == 0x400) {
                        halfMantissa = 0
                        exponent++
                    }
                }
                if (exponent >= 31) sign or 0x7c00 else sign or (exponent shl 10) or halfMantissa
            }
        }
        return halfBits.toShort()
    }

    private fun ensureModel(context: Context): File {
        val target = modelFile(context)
        if (target.exists()) {
            if (target.length() in MIN_MODEL_BYTES..MAX_MODEL_BYTES) return target
            target.delete()
        }
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        Log.i(TAG, "MODEL_DOWNLOAD_START url=$MODEL_URL")
        val request = Request.Builder().url(MODEL_URL).header("User-Agent", "LilacAnime/0.3.4").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("AnimeSharpV4 Fast 모델 다운로드 HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("AnimeSharpV4 Fast 모델 응답 본문이 없습니다.")
            if (body.contentLength() > MAX_MODEL_BYTES) throw IllegalStateException("AnimeSharpV4 Fast 모델 크기가 제한을 초과했습니다.")
            var total = 0L
            body.byteStream().use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_MODEL_BYTES) throw IllegalStateException("AnimeSharpV4 Fast 모델 크기가 제한을 초과했습니다.")
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        }
        if (part.length() !in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            val actual = part.length()
            part.delete()
            throw IllegalStateException("AnimeSharpV4 Fast 모델 파일 크기가 올바르지 않습니다: ${actual} bytes")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        Log.i(TAG, "MODEL_DOWNLOAD_DONE model=AnimeSharpV4-Fast-RCAN-PU scale=${MODEL_SCALE}x bytes=${target.length()} revision=1a9339b")
        return target
    }

    private fun ensureSession(context: Context) {
        if (session != null) return
        val file = modelFile(context)
        var nnapiFailure: Throwable? = null
        try {
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            // Reflection keeps compatibility with ONNX Runtime Android builds where
            // NNAPI methods may differ; unsupported devices fall through to CPU.
            try {
                options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                session = env.createSession(file.absolutePath, options)
                activeProvider = "NNAPI"
                Log.i(TAG, "MODEL_SESSION_READY model=AnimeSharpV4-Fast-RCAN-PU scale=${MODEL_SCALE}x provider=NNAPI flags=USE_FP16 ${sessionShapeSummary(session)}")
                return
            } catch (t: Throwable) {
                nnapiFailure = t.cause ?: t
            } finally {
                // SessionOptions is closed after each provider attempt below.
                runCatching { options.close() }
            }
        } catch (t: Throwable) { nnapiFailure = t }

        createCpuSession(context, nnapiFailure)
    }

    private fun createCpuSession(context: Context, nnapiFailure: Throwable? = null) {
        val cpuOptions = OrtSession.SessionOptions()
        cpuOptions.setIntraOpNumThreads(2)
        cpuOptions.setInterOpNumThreads(1)
        try {
            session = env.createSession(modelFile(context).absolutePath, cpuOptions)
            activeProvider = "CPU"
            Log.w(TAG, "MODEL_SESSION_READY model=AnimeSharpV4-Fast-RCAN-PU scale=${MODEL_SCALE}x provider=CPU nnapiFallback=${nnapiFailure?.javaClass?.simpleName ?: "runtime-inference-fallback"} ${sessionShapeSummary(session)}")
        } finally {
            cpuOptions.close()
        }
    }


    private fun sessionShapeSummary(current: OrtSession?): String {
        if (current == null) return "session=null"
        val inputs = current.inputInfo.entries.joinToString(prefix = "inputs=[", postfix = "]") { entry ->
            val shape = (entry.value.info as? TensorInfo)?.shape?.contentToString() ?: "unknown"
            "${entry.key}:$shape"
        }
        val outputs = current.outputInfo.entries.joinToString(prefix = "outputs=[", postfix = "]") { entry ->
            val shape = (entry.value.info as? TensorInfo)?.shape?.contentToString() ?: "unknown"
            "${entry.key}:$shape"
        }
        return "$inputs $outputs"
    }

    private fun makeDiagnosticBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(30, 35, 60))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(235, 90, 135)
        canvas.drawRect(8f, 8f, 30f, 30f, paint)
        paint.color = Color.rgb(80, 220, 180)
        canvas.drawCircle(44f, 28f, 12f, paint)
        paint.color = Color.WHITE
        paint.strokeWidth = 2f
        canvas.drawLine(4f, 56f, 60f, 36f, paint)
        return bitmap
    }
}
