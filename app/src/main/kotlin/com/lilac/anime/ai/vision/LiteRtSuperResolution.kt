package com.lilac.anime.ai.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.lilac.anime.ai.AcceleratorHealth
import com.lilac.anime.ai.AcceleratorPolicy
import com.lilac.anime.ai.AiAccelerator
import com.lilac.anime.ai.AiHardware
import com.lilac.anime.ai.NpuStatus
import com.lilac.anime.ai.VendorScopedNpuProvider
import java.io.File
import java.io.IOException
import kotlin.math.min

enum class TensorLayout { NCHW, NHWC }

/**
 * NPU는 입력 shape가 고정된 모델을 요구한다 → 프레임을 고정 크기 타일로 잘라 처리한다.
 * 모델은 .tflite(float32 입출력, 0..1 RGB)를 가정한다. FP16/양자화 모델이라면 LiteRT 변환 시
 * 입출력은 float32로 두고 내부만 양자화하는 것을 권장한다 (NPU AOT/JIT 컴파일은 LiteRT가 처리).
 */
data class SrModelSpec(
    val tile: Int,
    val scale: Int,
    val overlap: Int = 8,
    val layout: TensorLayout = TensorLayout.NHWC
) {
    init {
        require(tile - 2 * overlap > 0) { "tile($tile)은 overlap*2(${overlap * 2})보다 커야 합니다." }
    }
}

class LiteRtSuperResolution private constructor(
    private val model: CompiledModel,
    val accelerator: AiAccelerator,
    val spec: SrModelSpec
) : AutoCloseable {

    private val inputBuffers = model.createInputBuffers()
    private val outputBuffers = model.createOutputBuffers()

    /** 컴파일/로드가 지연 실행되는 경우를 대비해 한 타일을 실제로 돌려 본다 (실패하면 open이 다음 가속기로 폴백). */
    private fun warmup() {
        val t = spec.tile
        inputBuffers[0].writeFloat(FloatArray(3 * t * t))
        model.run(inputBuffers, outputBuffers)
        outputBuffers[0].readFloat()
    }

    fun upscale(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val s = spec.scale
        val t = spec.tile
        val o = spec.overlap
        val step = t - 2 * o
        val ts = t * s
        val nchw = spec.layout == TensorLayout.NCHW

        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)
        val outW = w * s
        val outH = h * s
        val out = IntArray(outW * outH)
        val tileIn = FloatArray(3 * t * t)

        var ty = 0
        while (ty < h) {
            var tx = 0
            while (tx < w) {
                // 타일 입력: 원본 좌표 [tx-o, tx-o+t) × [ty-o, ty-o+t), 경계는 가장자리 픽셀 복제
                for (yy in 0 until t) {
                    val sy = (ty - o + yy).coerceIn(0, h - 1)
                    for (xx in 0 until t) {
                        val sx = (tx - o + xx).coerceIn(0, w - 1)
                        val c = px[sy * w + sx]
                        val r = Color.red(c) / 255f
                        val g = Color.green(c) / 255f
                        val b = Color.blue(c) / 255f
                        if (nchw) {
                            val base = yy * t + xx
                            tileIn[base] = r; tileIn[t * t + base] = g; tileIn[2 * t * t + base] = b
                        } else {
                            val base = (yy * t + xx) * 3
                            tileIn[base] = r; tileIn[base + 1] = g; tileIn[base + 2] = b
                        }
                    }
                }
                inputBuffers[0].writeFloat(tileIn)
                model.run(inputBuffers, outputBuffers)
                val res = outputBuffers[0].readFloat()

                // 타일 중앙(overlap 제외)만 결과에 복사
                val xEnd = min(tx + step, w)
                val yEnd = min(ty + step, h)
                for (sy in ty until yEnd) {
                    val ly = sy - (ty - o)
                    for (sx in tx until xEnd) {
                        val lx = sx - (tx - o)
                        for (dy in 0 until s) {
                            val oy = ly * s + dy
                            for (dx in 0 until s) {
                                val ox = lx * s + dx
                                val r: Float; val g: Float; val b: Float
                                if (nchw) {
                                    val i = oy * ts + ox
                                    r = res[i]; g = res[ts * ts + i]; b = res[2 * ts * ts + i]
                                } else {
                                    val i = (oy * ts + ox) * 3
                                    r = res[i]; g = res[i + 1]; b = res[i + 2]
                                }
                                out[(sy * s + dy) * outW + (sx * s + dx)] = Color.rgb(
                                    (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt(),
                                    (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt(),
                                    (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                                )
                            }
                        }
                    }
                }
                tx += step
            }
            ty += step
        }
        return Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888)
    }

    override fun close() {
        runCatching { model.close() }
    }

    companion object {
        private const val TAG = "LiteRtSuperResolution"
        private const val ENGINE_ID = "litert-sr"

        /** 블로킹(모델 컴파일 포함). NPU → GPU → CPU 순으로 시도하고, 첫 성공을 반환한다. */
        fun open(
            context: Context,
            modelFile: File,
            spec: SrModelSpec,
            order: List<AiAccelerator> = AcceleratorPolicy.order(context)
        ): LiteRtSuperResolution {
            if (!modelFile.isFile) throw IOException("초해상도 모델 파일이 없습니다: ${modelFile.name}")
            val failures = mutableListOf<String>()
            for (acc in order) {
                if (acc == AiAccelerator.NPU && AiHardware.npuStatus(context) != NpuStatus.READY) {
                    failures += "NPU: ${AiHardware.npuStatus(context).message}"
                    continue
                }
                val key = AcceleratorHealth.key(context, ENGINE_ID, modelFile.name, acc)
                AcceleratorHealth.skipReason(context, key)?.let { failures += "${acc.label}: 건너뜀($it)"; continue }
                AcceleratorHealth.begin(context, key)
                val t0 = SystemClock.elapsedRealtime()
                try {
                    val sr = create(context, modelFile, spec, acc)
                    try {
                        sr.warmup()
                    } catch (t: Throwable) {
                        sr.close(); throw t
                    }
                    AcceleratorHealth.success(context, key, SystemClock.elapsedRealtime() - t0)
                    Log.i(TAG, "OPEN accelerator=$acc model=${modelFile.name} initMs=${SystemClock.elapsedRealtime() - t0}")
                    return sr
                } catch (t: Throwable) {
                    AcceleratorHealth.failure(context, key, t.message ?: t.javaClass.simpleName)
                    failures += "${acc.label}: ${t.message ?: t.javaClass.simpleName}"
                    Log.w(TAG, "OPEN_FAILED accelerator=$acc", t)
                }
            }
            throw IOException("초해상도 가속기를 초기화하지 못했습니다.\n" + failures.joinToString("\n"))
        }

        private fun create(context: Context, file: File, spec: SrModelSpec, acc: AiAccelerator): LiteRtSuperResolution {
            val compiled = when (acc) {
                // NPU가 처리하지 못하는 서브그래프는 GPU로 (LiteRT 부분 위임)
                AiAccelerator.NPU -> CompiledModel.create(
                    file.absolutePath,
                    CompiledModel.Options(Accelerator.NPU, Accelerator.GPU),
                    Environment.create(VendorScopedNpuProvider(context))
                )
                AiAccelerator.GPU -> CompiledModel.create(file.absolutePath, CompiledModel.Options(Accelerator.GPU))
                AiAccelerator.CPU -> CompiledModel.create(file.absolutePath, CompiledModel.Options(Accelerator.CPU))
            }
            return try {
                LiteRtSuperResolution(compiled, acc, spec)
            } catch (t: Throwable) {
                runCatching { compiled.close() }
                throw t
            }
        }
    }
}
