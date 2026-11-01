package com.lilac.anime.ai.llm

import android.content.Context
import com.lilac.anime.ai.AiAccelerator
import com.lilac.anime.data.subtitle.translation.localai.LocalAiAdapterRegistry
import com.lilac.anime.data.subtitle.translation.localai.LocalAiModel
import com.lilac.anime.data.subtitle.translation.localai.LocalAiNative
import com.lilac.anime.data.subtitle.translation.localai.LocalAiPromptRequest
import com.lilac.anime.data.subtitle.translation.localai.RuntimeRegistry
import java.io.IOException

/**
 * 기존 llama.cpp 런타임 팩(JNI contract lilac-local-ai-v3)을 그대로 감싼 폴백 엔진.
 * LiteRT-LM이 지원하지 않는 모델(HY-MT 등 GGUF 전용 모델)을 계속 쓰기 위해 유지한다.
 * LocalAiNative는 프로세스 전역 싱글턴이라 동시에 하나의 모델만 로드된다.
 */
class LlamaCppEngine private constructor(
    private val model: LocalAiModel,
    private val runtimeId: String,
    override val accelerator: AiAccelerator
) : LlmEngine {

    override val engineId: String = "llama.cpp:$runtimeId"
    override val modelId: String = model.id

    override fun generate(request: LlmRequest): String {
        val adapter = LocalAiAdapterRegistry.resolve(model)
        val prepared = adapter.prepare(
            LocalAiPromptRequest(
                systemPrompt = request.systemPrompt,
                userPrompt = request.userPrompt,
                chatTemplate = model.chatTemplate,
                useChatTemplate = request.useChatTemplate,
                addGenerationPrompt = true,
                thinkingMode = request.thinking.name.lowercase()
            )
        )
        val s = request.sampling
        val raw = LocalAiNative.translate(
            messages = prepared.messages,
            chatTemplate = prepared.chatTemplate,
            useChatTemplate = prepared.useChatTemplate,
            addGenerationPrompt = prepared.addGenerationPrompt,
            maxTokens = s.maxTokens,
            temperature = s.temperature,
            topP = s.topP,
            topK = s.topK,
            repetitionPenalty = s.repetitionPenalty,
            repeatLastN = s.repeatLastN,
            minP = s.minP,
            typicalP = s.typicalP,
            frequencyPenalty = s.frequencyPenalty,
            presencePenalty = s.presencePenalty,
            seed = s.seed,
            thinkingMode = prepared.nativeTemplateControl
        ).orEmpty()
        return adapter.cleanOutput(raw) ?: raw
    }

    override fun close() {
        LocalAiNative.release()
    }

    companion object {
        fun open(context: Context, model: LocalAiModel): LlamaCppEngine {
            val runtime = RuntimeRegistry.select(context, model)
                ?: throw IOException("이 GGUF와 호환되는 llama.cpp 런타임 팩이 없습니다.")
            if (!LocalAiNative.load(context, runtime, model.localPath)) {
                throw IOException("llama.cpp 런타임 초기화 실패: ${LocalAiNative.error()?.message.orEmpty()}")
            }
            // 팩의 backendOrder 첫 항목을 대표 가속기로 표기 (실제 선택은 네이티브 계층이 한다)
            val first = runtime.pack?.backendOrder?.firstOrNull().orEmpty()
            val accelerator = when {
                first in setOf("npu", "hexagon", "htp", "qnn") -> AiAccelerator.NPU
                first in setOf("gpu", "opencl", "vulkan") -> AiAccelerator.GPU
                else -> AiAccelerator.CPU
            }
            return LlamaCppEngine(model, runtime.id, accelerator)
        }
    }
}
