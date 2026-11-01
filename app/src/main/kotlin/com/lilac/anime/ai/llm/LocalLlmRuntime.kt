package com.lilac.anime.ai.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.lilac.anime.ai.AcceleratorHealth
import com.lilac.anime.ai.AcceleratorPolicy
import com.lilac.anime.ai.AiAccelerator
import com.lilac.anime.ai.AiHardware
import com.lilac.anime.ai.NpuStatus
import com.lilac.anime.data.subtitle.translation.localai.LocalAiModelManager
import com.lilac.anime.data.subtitle.translation.localai.RuntimeRegistry
import java.io.IOException

/**
 * 로컬 LLM 세션 관리자. 호출자(LocalAiTranslationRuntime)가 Mutex로 직렬화한다고 가정한다.
 *
 * 흐름: 후보 (모델 × 가속기) 목록 → 건너뛸 조합 제외 → "시도 중" 표식 → 로드 → 성공/실패 기록.
 *  - pref_translation_model_id 가 "auto"(또는 비어 있음)면 NPU 빌드 > GGUF NPU 팩 > GPU 빌드 > 나머지 순으로 시도
 *  - 특정 모델을 직접 골랐으면 그 모델만 (가속기 폴백만 적용). 번역 품질이 조용히 바뀌지 않게 하기 위함.
 */
internal object LocalLlmRuntime {
    private const val TAG = "LocalLlmRuntime"
    private const val PREFS = "lilac_offline_store"
    const val AUTO = "auto"

    private var active: LlmEngine? = null
    private var lastSummary: String = "세션 없음"

    data class Candidate(val model: LocalLlmModel, val accelerator: AiAccelerator)

    fun isOpen(): Boolean = active != null
    fun describe(): String = lastSummary

    /** 이 기기에서 실제로 쓸 수 있는 모든 모델 (LiteRT-LM + llama.cpp 팩이 있는 GGUF). */
    fun availableModels(context: Context): List<LocalLlmModel> {
        val litert = LiteRtModelStore.installed(context).filter { it.deviceCompatible }
        val gguf = LocalAiModelManager.installed(context)
            .filter { RuntimeRegistry.select(context, it) != null }
            .map {
                LocalLlmModel(
                    id = it.id, displayName = it.displayName, format = ModelFormat.GGUF,
                    path = it.localPath, sizeBytes = it.sizeBytes, gguf = it
                )
            }
        return litert + gguf
    }

    private fun ggufAccelerator(context: Context, model: LocalLlmModel): AiAccelerator {
        val first = model.gguf?.let { RuntimeRegistry.select(context, it) }?.pack?.backendOrder?.firstOrNull().orEmpty()
        return when {
            first in setOf("npu", "hexagon", "htp", "qnn") -> AiAccelerator.NPU
            first in setOf("gpu", "opencl", "vulkan") -> AiAccelerator.GPU
            else -> AiAccelerator.CPU
        }
    }

    private fun rank(context: Context, model: LocalLlmModel): Int = when {
        model.format == ModelFormat.LITERTLM && model.npuTarget -> 0
        model.format == ModelFormat.GGUF && ggufAccelerator(context, model) == AiAccelerator.NPU -> 1
        model.format == ModelFormat.LITERTLM -> 2 // 범용 빌드: GPU/CPU
        else -> 3
    }

    fun candidates(context: Context): List<Candidate> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val selected = prefs.getString("pref_translation_model_id", AUTO) ?: AUTO
        val all = availableModels(context)
        val models = if (selected != AUTO && all.any { it.id == selected }) {
            all.filter { it.id == selected }
        } else {
            all.sortedWith(compareBy({ rank(context, it) }, { it.sizeBytes }))
        }
        val order = AcceleratorPolicy.order(context)
        val npuReady = AiHardware.npuStatus(context) == NpuStatus.READY
        return models.flatMap { model ->
            when (model.format) {
                // NPU AOT 빌드는 NPU 전용, 범용 빌드는 GPU/CPU 전용
                ModelFormat.LITERTLM -> order
                    .filter { if (model.npuTarget) it == AiAccelerator.NPU && npuReady else it != AiAccelerator.NPU }
                    .map { Candidate(model, it) }
                ModelFormat.GGUF -> listOf(Candidate(model, ggufAccelerator(context, model)))
            }
        }
    }

    private fun instantiate(context: Context, c: Candidate): LlmEngine = when (c.model.format) {
        ModelFormat.LITERTLM -> LiteRtLmEngine.open(context, c.model, c.accelerator)
        ModelFormat.GGUF -> LlamaCppEngine.open(context, c.model.gguf ?: throw IOException("GGUF 정보가 없습니다."))
    }

    private fun engineIdOf(c: Candidate) =
        if (c.model.format == ModelFormat.LITERTLM) LiteRtLmEngine.ENGINE_ID else "llama.cpp"

    /** 블로킹. 첫 번째로 성공한 후보를 세션으로 연다. 모두 실패하면 사유를 모아 예외를 던진다. */
    fun open(context: Context): LlmEngine {
        active?.let { return it }
        val list = candidates(context)
        if (list.isEmpty()) {
            throw IOException(
                "실행할 수 있는 로컬 AI 모델이 없습니다. 설정에서 이 기기용 .litertlm 모델(NPU/GPU)이나 " +
                    "런타임 팩이 있는 GGUF 모델을 추가하세요. (${AiHardware.soc().displayName})"
            )
        }
        val failures = mutableListOf<String>()
        for (c in list) {
            val key = AcceleratorHealth.key(context, engineIdOf(c), c.model.id, c.accelerator)
            AcceleratorHealth.skipReason(context, key)?.let {
                failures += "${c.model.displayName}/${c.accelerator.label}: 건너뜀($it)"
                continue
            }
            AcceleratorHealth.begin(context, key)
            val t0 = SystemClock.elapsedRealtime()
            try {
                val engine = instantiate(context, c)
                val ms = SystemClock.elapsedRealtime() - t0
                AcceleratorHealth.success(context, key, ms)
                active = engine
                lastSummary = "${engine.engineId} · ${engine.accelerator.label} · ${c.model.displayName} (로드 ${ms}ms)"
                Log.i(TAG, "SESSION_OPEN $lastSummary")
                return engine
            } catch (t: Throwable) {
                AcceleratorHealth.failure(context, key, t.message ?: t.javaClass.simpleName)
                failures += "${c.model.displayName}/${c.accelerator.label}: ${t.message ?: t.javaClass.simpleName}"
                Log.w(TAG, "CANDIDATE_FAILED ${c.model.displayName}/${c.accelerator}", t)
            }
        }
        throw IOException("모든 로컬 AI 실행 후보가 실패했습니다.\n" + failures.joinToString("\n"))
    }

    fun close() {
        val engine = active ?: return
        runCatching { engine.close() }
        active = null
        lastSummary = "세션 없음"
    }

    /**
     * 생성 중 예외가 나면 해당 조합을 실패로 기록하고 다음 후보로 한 번 재시도한다.
     * (예: NPU로 로드는 됐는데 추론 중 디스패치 오류)
     */
    fun generate(context: Context, request: LlmRequest): String {
        val engine = active ?: open(context)
        return try {
            engine.generate(request)
        } catch (t: Throwable) {
            Log.w(TAG, "GENERATE_FAILED ${engine.engineId}/${engine.accelerator}", t)
            // open()의 engineIdOf()와 같은 규칙: "llama.cpp:<pack>" → "llama.cpp", "litert-lm" → 그대로
            val key = AcceleratorHealth.key(
                context, engine.engineId.substringBefore(':'), engine.modelId, engine.accelerator
            )
            AcceleratorHealth.failure(context, key, "추론 중 오류: ${t.message ?: t.javaClass.simpleName}")
            close()
            open(context).generate(request)
        }
    }
}
