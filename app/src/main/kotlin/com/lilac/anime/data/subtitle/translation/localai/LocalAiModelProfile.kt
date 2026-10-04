package com.lilac.anime.data.subtitle.translation.localai

/**
 * Model-level capabilities and defaults. This is deliberately separate from
 * the native runtime: the same runtime can execute many model families.
 */
enum class LocalAiModelFamily {
    HY_MT,
    QWEN,
    GEMMA,
    GENERIC
}

enum class LocalAiFeature {
    SYSTEM_PROMPT,
    TEMPERATURE,
    TOP_P,
    TOP_K,
    MAX_TOKENS,
    THINKING_CONTROL,
    VISION,
    TOOL_CALLING
}

data class LocalAiGenerationDefaults(
    val temperature: Float = 0.25f,
    val topP: Float = 0.85f,
    val topK: Int = 40,
    val maxTokens: Int = 256,
    val contextSize: Int = 4096,
    val threads: Int = 0
)

data class LocalAiModelProfile(
    val id: String,
    val displayName: String,
    val family: LocalAiModelFamily,
    val features: Set<LocalAiFeature>,
    val defaults: LocalAiGenerationDefaults = LocalAiGenerationDefaults(),
    val notes: String = ""
) {
    fun supports(feature: LocalAiFeature): Boolean = feature in features
}

object LocalAiModelProfiles {
    val HY_MT = LocalAiModelProfile(
        id = "hy-mt",
        displayName = "HY-MT",
        family = LocalAiModelFamily.HY_MT,
        features = setOf(
            LocalAiFeature.SYSTEM_PROMPT,
            LocalAiFeature.TEMPERATURE,
            LocalAiFeature.TOP_P,
            LocalAiFeature.TOP_K,
            LocalAiFeature.MAX_TOKENS
        ),
        defaults = LocalAiGenerationDefaults(
            temperature = 0.15f,
            topP = 0.85f,
            topK = 40,
            maxTokens = 256
        ),
        notes = "번역 특화 모델. 기본적으로 별도의 thinking 제어를 사용하지 않습니다."
    )

    val QWEN = LocalAiModelProfile(
        id = "qwen",
        displayName = "Qwen",
        family = LocalAiModelFamily.QWEN,
        features = setOf(
            LocalAiFeature.SYSTEM_PROMPT,
            LocalAiFeature.TEMPERATURE,
            LocalAiFeature.TOP_P,
            LocalAiFeature.TOP_K,
            LocalAiFeature.MAX_TOKENS,
            LocalAiFeature.THINKING_CONTROL
        ),
        notes = "Qwen 계열의 chat template에 맞춰 thinking 설정을 adapter에서 처리합니다."
    )

    val GEMMA = LocalAiModelProfile(
        id = "gemma",
        displayName = "Gemma",
        family = LocalAiModelFamily.GEMMA,
        features = setOf(
            LocalAiFeature.SYSTEM_PROMPT,
            LocalAiFeature.TEMPERATURE,
            LocalAiFeature.TOP_P,
            LocalAiFeature.TOP_K,
            LocalAiFeature.MAX_TOKENS
        ),
        notes = "Gemma 계열. 특수 템플릿 동작은 모델별 adapter에서 확장합니다."
    )

    val GENERIC = LocalAiModelProfile(
        id = "generic",
        displayName = "범용 GGUF",
        family = LocalAiModelFamily.GENERIC,
        features = setOf(
            LocalAiFeature.SYSTEM_PROMPT,
            LocalAiFeature.TEMPERATURE,
            LocalAiFeature.TOP_P,
            LocalAiFeature.TOP_K,
            LocalAiFeature.MAX_TOKENS
        ),
        notes = "등록되지 않은 llama.cpp 호환 GGUF를 위한 안전한 기본 프로필입니다."
    )

    val all: List<LocalAiModelProfile> = listOf(HY_MT, QWEN, GEMMA, GENERIC)

    fun resolve(model: LocalAiModel): LocalAiModelProfile {
        val text = listOf(model.repoId, model.fileName, model.displayName, model.architecture, model.chatTemplate)
            .filterNotNull()
            .joinToString(" ")
            .lowercase()

        return when {
            text.contains("hy-mt") || text.contains("hymt") ||
                text.contains("hunyuan-mt") || text.contains("hunyuan_mt") -> HY_MT
            // Gemma 4 also uses an enable_thinking variable in its Jinja
            // template, so architecture/name must win over that generic hint.
            text.contains("gemma-4") || text.contains("gemma4") -> GEMMA
            text.contains("qwen") -> QWEN
            text.contains("enable_thinking") -> QWEN
            else -> GENERIC
        }
    }
}
