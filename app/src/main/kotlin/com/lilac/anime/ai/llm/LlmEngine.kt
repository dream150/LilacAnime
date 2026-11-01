package com.lilac.anime.ai.llm

import com.lilac.anime.ai.AiAccelerator
import com.lilac.anime.data.subtitle.translation.localai.LocalAiModel
import java.io.File

enum class ModelFormat { GGUF, LITERTLM }

enum class ThinkingMode {
    OFF, AUTO, ON;

    companion object {
        fun from(value: String?): ThinkingMode = when (value) {
            "on" -> ON
            "auto" -> AUTO
            else -> OFF
        }
    }
}

/** 런타임마다 지원하는 샘플링 항목이 다르다. LiteRT-LM은 temperature/topK/topP만 사용하고 나머지는 무시한다. */
data class Sampling(
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val maxTokens: Int,
    val repetitionPenalty: Float = 1.0f,
    val repeatLastN: Int = 64,
    val minP: Float = 0f,
    val typicalP: Float = 1f,
    val frequencyPenalty: Float = 0f,
    val presencePenalty: Float = 0f,
    val seed: Int = -1
)

data class LlmRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val sampling: Sampling,
    val thinking: ThinkingMode,
    /** false면 completion 모드(GGUF 전용). */
    val useChatTemplate: Boolean = true
)

/** 하나의 (런타임, 모델, 가속기) 조합. 호출자는 단일 추론 스레드에서만 generate를 호출한다. */
interface LlmEngine : AutoCloseable {
    val engineId: String
    val accelerator: AiAccelerator
    val modelId: String
    fun generate(request: LlmRequest): String
}

data class LocalLlmModel(
    val id: String,
    val displayName: String,
    val format: ModelFormat,
    val path: String,
    val sizeBytes: Long,
    /** 파일명의 SoC 토큰이 이 기기와 일치하는 NPU AOT 빌드. */
    val npuTarget: Boolean = false,
    /** 다른 SoC 전용 빌드면 false → 목록에서 제외. */
    val deviceCompatible: Boolean = true,
    val gguf: LocalAiModel? = null
) {
    /** 기존 adapter/profile 로직(thinking, cleanOutput 등)을 재사용하기 위한 어댑터용 모델. */
    fun asProfileModel(): LocalAiModel = gguf ?: LocalAiModel(
        id = id,
        repoId = "",
        fileName = File(path).name,
        displayName = displayName,
        sizeBytes = sizeBytes,
        architecture = null,
        quantization = null,
        chatTemplate = null,
        localPath = path,
        runtimeId = null
    )
}
