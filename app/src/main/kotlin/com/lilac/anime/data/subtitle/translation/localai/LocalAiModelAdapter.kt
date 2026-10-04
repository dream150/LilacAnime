package com.lilac.anime.data.subtitle.translation.localai

/**
 * Model-family adapter. The llama.cpp/Snapdragon runtime stays model-agnostic;
 * adapters only describe model-specific prompt/template behavior.
 */
interface LocalAiModelAdapter {
    val id: String
    val displayName: String
    val profile: LocalAiModelProfile

    fun matches(model: LocalAiModel): Boolean = LocalAiModelProfiles.resolve(model).id == profile.id

    fun prepare(request: LocalAiPromptRequest): LocalAiPreparedPrompt

    /** Optional model-specific output cleanup. Null means no special handling. */
    fun cleanOutput(output: String): String? = null
}

data class LocalAiPromptRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val chatTemplate: String?,
    val useChatTemplate: Boolean,
    val addGenerationPrompt: Boolean,
    val thinkingMode: String
)

data class LocalAiPreparedPrompt(
    val messages: List<Pair<String, String>>,
    val chatTemplate: String?,
    val useChatTemplate: Boolean,
    val addGenerationPrompt: Boolean,
    /** 0=template/default, 1=adapter explicitly enables, 2=adapter disables. */
    val nativeTemplateControl: Int = 0
)

object LocalAiAdapterRegistry {
    private val adapters: List<LocalAiModelAdapter> = listOf(
        HyMtModelAdapter,
        QwenModelAdapter,
        GemmaModelAdapter,
        GenericChatModelAdapter
    )

    fun all(): List<LocalAiModelAdapter> = adapters

    fun resolve(model: LocalAiModel): LocalAiModelAdapter =
        adapters.firstOrNull { it.matches(model) } ?: GenericChatModelAdapter
}

/** HY-MT family: keep the adapter conservative because this is translation-first. */
object HyMtModelAdapter : LocalAiModelAdapter {
    override val id = "hy-mt"
    override val displayName = "HY-MT"
    override val profile = LocalAiModelProfiles.HY_MT

    override fun matches(model: LocalAiModel): Boolean = profile.id == LocalAiModelProfiles.resolve(model).id

    override fun prepare(request: LocalAiPromptRequest): LocalAiPreparedPrompt {
        // HY-MT is treated as a normal chat/completion translation model. Do not
        // inject Qwen-specific /no_think or template variables into it.
        return LocalAiPreparedPrompt(
            messages = if (request.useChatTemplate) {
                listOf("system" to request.systemPrompt, "user" to request.userPrompt)
            } else {
                listOf("user" to (request.systemPrompt + "\n\n" + request.userPrompt))
            },
            chatTemplate = request.chatTemplate,
            useChatTemplate = request.useChatTemplate,
            addGenerationPrompt = request.addGenerationPrompt,
            nativeTemplateControl = 0
        )
    }
}

/** Qwen-family adapter. Keep all Qwen-specific prompt behavior here. */
object QwenModelAdapter : LocalAiModelAdapter {
    override val id = "qwen"
    override val displayName = "Qwen"
    override val profile = LocalAiModelProfiles.QWEN

    override fun matches(model: LocalAiModel): Boolean = profile.id == LocalAiModelProfiles.resolve(model).id

    override fun prepare(request: LocalAiPromptRequest): LocalAiPreparedPrompt {
        val off = request.thinkingMode == "off"
        val on = request.thinkingMode == "on"
        val system = when {
            off -> request.systemPrompt + "\n\nDo not reason or output any thinking. Return only the final translated subtitle."
            on -> request.systemPrompt + "\n\nReasoning may be used internally, but the final response must contain only the translated subtitle."
            else -> request.systemPrompt
        }
        val user = if (off) request.userPrompt + "\n\n/no_think" else request.userPrompt
        val template = if (off) forceThinkingOffTemplate(request.chatTemplate) else request.chatTemplate

        return LocalAiPreparedPrompt(
            messages = if (request.useChatTemplate) {
                listOf("system" to system, "user" to user)
            } else {
                listOf("user" to (system + "\n\n" + user))
            },
            chatTemplate = template,
            useChatTemplate = request.useChatTemplate,
            addGenerationPrompt = request.addGenerationPrompt,
            nativeTemplateControl = 0
        )
    }

    override fun cleanOutput(output: String): String? {
        val trimmed = output.trim()
        if (trimmed.isBlank()) return null
        return trimmed
            .replace(Regex("(?is)<think>.*?</think>"), "")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun forceThinkingOffTemplate(template: String?): String? {
        if (template.isNullOrBlank() || !template.contains("enable_thinking")) return template
        var result = "{%- set enable_thinking = false %}\n$template"
        result = result.replace("enable_thinking = true", "enable_thinking = false")
        return result
    }
}

/** Gemma adapter intentionally starts conservative; model-specific behavior can grow here. */
object GemmaModelAdapter : LocalAiModelAdapter {
    override val id = "gemma"
    override val displayName = "Gemma"
    override val profile = LocalAiModelProfiles.GEMMA

    override fun matches(model: LocalAiModel): Boolean = profile.id == LocalAiModelProfiles.resolve(model).id

    override fun prepare(request: LocalAiPromptRequest): LocalAiPreparedPrompt {
        // Gemma 4 uses the new <|turn> / <turn|> format. Older llama.cpp C-API
        // template detection can reject this template even though the model
        // itself loads correctly. Keep the original GGUF template attached so
        // the native layer can recognize the Gemma 4 marker and use its safe
        // manual formatter when necessary.
        val thinkingControl = when (request.thinkingMode) {
            "on" -> 1
            "off" -> 2
            else -> 0
        }
        return LocalAiPreparedPrompt(
            messages = if (request.useChatTemplate) {
                listOf("system" to request.systemPrompt, "user" to request.userPrompt)
            } else {
                listOf("user" to (request.systemPrompt + "\n\n" + request.userPrompt))
            },
            chatTemplate = request.chatTemplate,
            useChatTemplate = request.useChatTemplate,
            addGenerationPrompt = request.addGenerationPrompt,
            nativeTemplateControl = thinkingControl
        )
    }

    override fun cleanOutput(output: String): String? {
        val cleaned = output
            .replace(Regex("(?is)<think>.*?</think>"), "")
            .replace(Regex("(?is)<\\|think\\|>.*?<\\|channel\\|>"), "")
            .trim()
        return cleaned.takeIf { it.isNotBlank() }
    }
}

/** Fallback for any GGUF chat model supported by the installed runtime. */
object GenericChatModelAdapter : LocalAiModelAdapter {
    override val id = "generic"
    override val displayName = "범용 GGUF"
    override val profile = LocalAiModelProfiles.GENERIC

    override fun matches(model: LocalAiModel): Boolean = true

    override fun prepare(request: LocalAiPromptRequest): LocalAiPreparedPrompt {
        val messages = if (request.useChatTemplate) {
            listOf("system" to request.systemPrompt, "user" to request.userPrompt)
        } else {
            listOf("user" to (request.systemPrompt + "\n\n" + request.userPrompt))
        }
        return LocalAiPreparedPrompt(
            messages = messages,
            chatTemplate = request.chatTemplate,
            useChatTemplate = request.useChatTemplate,
            addGenerationPrompt = request.addGenerationPrompt,
            nativeTemplateControl = 0
        )
    }
}
