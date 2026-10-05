package com.lilac.anime.data.subtitle.translation.providers

import android.content.Context
import android.util.Log
import com.lilac.anime.data.subtitle.translation.TranslationProvider
import com.lilac.anime.data.subtitle.translation.TranslationSessionProvider
import com.lilac.anime.data.subtitle.translation.localai.LocalAiModel
import com.lilac.anime.data.subtitle.translation.localai.LocalAiModelManager
import com.lilac.anime.data.subtitle.translation.localai.LocalAiAdapterRegistry
import com.lilac.anime.data.subtitle.translation.localai.LocalAiPromptRequest
import com.lilac.anime.data.subtitle.translation.localai.LocalAiNative
import com.lilac.anime.data.subtitle.translation.localai.RuntimeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.io.IOException

class LocalTranslator(private val context: Context) : TranslationSessionProvider {
    data class LocalAiContext(val source: String, val translation: String?)

    override val id = "local"
    override val displayName = "로컬 AI"
    private var sessionActive = false

    override suspend fun beginSession() {
        if (sessionActive) return
        LocalAiTranslationRuntime.beginSession(context)
        sessionActive = true
    }

    override suspend fun endSession() {
        if (!sessionActive) return
        LocalAiTranslationRuntime.endSession()
        sessionActive = false
    }

    override suspend fun translateBatch(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val ownSession = !sessionActive
        if (ownSession) beginSession()
        return try {
            val contextCount = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
                .getInt("pref_ai_context_cues", 4).coerceIn(0, 10)
            buildList(lines.size) {
                lines.indices.forEach { index ->
                    val previous = lines.subList((index - contextCount).coerceAtLeast(0), index)
                        .map { LocalAiContext(it, null) }
                    val future = lines.subList(index + 1, (index + 3).coerceAtMost(lines.size))
                    add(
                        LocalAiTranslationRuntime.translateWithContext(
                            context = context,
                            source = lines[index],
                            previousContext = previous.map {
                                LocalAiTranslationRuntime.SubtitleContext(it.source, it.translation)
                            },
                            futureLines = future
                        )
                    )
                }
            }
        } finally {
            if (ownSession) endSession()
        }
    }

    suspend fun translateWithContext(
        source: String,
        context: List<LocalAiContext>,
        futureLines: List<String> = emptyList()
    ): String {
        if (source.isBlank()) return source
        val ownSession = !sessionActive
        if (ownSession) beginSession()
        return try {
            LocalAiTranslationRuntime.translateWithContext(
                this.context,
                source,
                context.map { LocalAiTranslationRuntime.SubtitleContext(it.source, it.translation) },
                futureLines
            )
        } finally {
            if (ownSession) endSession()
        }
    }
}

internal object LocalAiTranslationRuntime {
    private const val TAG = "LocalAiTranslation"
    private const val PROMPT_VERSION = 2
    private val lock = Mutex()
    private val inferenceDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LilacLocalAiInference").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    data class SubtitleContext(val source: String, val translation: String?)

    val DEFAULT_SYSTEM_PROMPT =
        "너는 애니메이션 자막 번역가야. 이름은 번역없이 발음만 한국어로 쓰고, 주어진 문맥들을 참고해서, 번역해야 할 일본어 문장만 추가설명 없이 한국어 문장으로 번역해서 그것만 출력해."
    
    /** Reasoning preference exposed to model adapters. Each adapter decides how it is implemented. */
    const val THINKING_AUTO = "auto"
    const val THINKING_ON = "on"
    const val THINKING_OFF = "off"


    val DEFAULT_PROMPT = """
이전 맥락
{context}
이후 맥락
{future_context}

번역해야 할 문장
{source_text}
""".trimIndent()

    private var sessionRuntime: RuntimeRegistry.Candidate? = null
    private var sessionModel: LocalAiModel? = null
    private var sessionModelId: String? = null
    private var sessionChatTemplate: String? = null
    private var persistentAnimeId: String? = null

    suspend fun warmForAnime(context: Context, animeId: String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val requested = animeId?.takeIf { it.isNotBlank() }
            if (sessionRuntime != null && persistentAnimeId == requested) return@withLock
            if (sessionRuntime != null) {
                LocalAiNative.release()
                sessionRuntime = null
                sessionModel = null
                sessionModelId = null
                sessionChatTemplate = null
            }
            val model = selectModel(context)
            val runtime = RuntimeRegistry.select(context, model)
                ?: throw IOException("GPU/NPU runtime이 설치되어 있지 않거나 이 GGUF와 호환되지 않습니다.")
            if (!LocalAiNative.load(context, runtime, model.localPath)) {
                throw IOException("GPU/NPU runtime 초기화 실패: ${LocalAiNative.error()?.message.orEmpty()}")
            }
            sessionRuntime = runtime
            sessionModel = model
            sessionModelId = model.id
            sessionChatTemplate = model.chatTemplate
            persistentAnimeId = requested
            Log.i(TAG, "PERSISTENT_SESSION_START anime=${requested.orEmpty()} runtime=${runtime.id} model=${model.displayName}")
        }
    }

    suspend fun clearPersistentSession(animeId: String? = null) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (animeId != null && persistentAnimeId != animeId) return@withLock
            if (sessionRuntime == null) {
                persistentAnimeId = null
                return@withLock
            }
            LocalAiNative.release()
            sessionRuntime = null
            sessionModel = null
            sessionModelId = null
            sessionChatTemplate = null
            persistentAnimeId = null
            Log.i(TAG, "PERSISTENT_SESSION_END")
        }
    }

    suspend fun beginSession(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (sessionRuntime != null) return@withLock
            val model = selectModel(context)
            val runtime = RuntimeRegistry.select(context, model)
                ?: throw IOException("GPU/NPU runtime이 설치되어 있지 않거나 이 GGUF와 호환되지 않습니다.")
            if (!LocalAiNative.load(context, runtime, model.localPath)) {
                throw IOException("GPU/NPU runtime 초기화 실패: ${LocalAiNative.error()?.message.orEmpty()}")
            }
            sessionRuntime = runtime
            sessionModel = model
            sessionModelId = model.id
            sessionChatTemplate = model.chatTemplate
            Log.i(TAG, "SESSION_START runtime=${runtime.id} version=${runtime.version} model=${model.displayName}")
        }
    }

    suspend fun endSession() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (sessionRuntime == null) return@withLock
            if (persistentAnimeId != null) {
                Log.i(TAG, "SESSION_END_SKIPPED persistentAnime=${persistentAnimeId.orEmpty()}")
                return@withLock
            }
            LocalAiNative.release()
            sessionRuntime = null
            sessionModel = null
            sessionModelId = null
            sessionChatTemplate = null
            persistentAnimeId = null
            Log.i(TAG, "SESSION_END")
        }
    }

    suspend fun translateWithContext(
        context: Context,
        source: String,
        previousContext: List<SubtitleContext>,
        futureLines: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        if (source.isBlank()) return@withContext source
        if (sessionRuntime == null) beginSession(context)
        lock.withLock {
            val runtime = sessionRuntime ?: throw IOException("local AI runtime이 초기화되지 않았습니다.")
            val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            prefs.edit().putInt("pref_ai_prompt_version", PROMPT_VERSION).apply()

            val model = sessionModel ?: selectModel(context)
            val adapter = LocalAiAdapterRegistry.resolve(model)
            val cleanSource = cleanText(source)
            val savedPrompt = prefs.getString("pref_translation_prompt", null)?.trim()
            val promptTemplate = if (savedPrompt.isNullOrBlank()) DEFAULT_PROMPT else savedPrompt
            val contextCount = prefs.getInt("pref_ai_context_cues", 4).coerceIn(0, 10)
            val cleanContext = previousContext
                .map { SubtitleContext(cleanText(it.source), null) }
                .filter { it.source.isNotBlank() }
                .takeLast(contextCount)
            val contextText = cleanContext.map { cleanText(it.source) }.filter { it.isNotBlank() }.joinToString("\n")
            val futureText = futureLines.map(::cleanText).filter { it.isNotBlank() }.joinToString("\n")
            val prompt = promptTemplate
                .replace("{context}", contextText)
                .replace("{future_context}", futureText)
                .replace("{source_text}", cleanSource)

            val systemPrompt = prefs.getString("pref_ai_system_prompt", null)?.trim()
                ?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT
            val maxTokens = prefs.getInt("pref_ai_max_tokens", 256).coerceIn(32, 2048)
            val temperature = prefs.getFloat("pref_ai_temperature", 0.25f).coerceIn(0.0f, 2.0f)
            val topP = prefs.getFloat("pref_ai_top_p", 0.85f).coerceIn(0.01f, 1.0f)
            val topK = prefs.getInt("pref_ai_top_k", 40).coerceIn(1, 200)
            val repetitionPenalty = prefs.getFloat("pref_ai_repetition_penalty", 1.05f).coerceIn(0.8f, 1.5f)
            val repeatLastN = prefs.getInt("pref_ai_repeat_last_n", 64).coerceIn(0, 512)
            val minP = prefs.getFloat("pref_ai_min_p", 0.0f).coerceIn(0.0f, 1.0f)
            val typicalP = prefs.getFloat("pref_ai_typical_p", 1.0f).coerceIn(0.01f, 1.0f)
            val frequencyPenalty = prefs.getFloat("pref_ai_frequency_penalty", 0.0f).coerceIn(-2.0f, 2.0f)
            val presencePenalty = prefs.getFloat("pref_ai_presence_penalty", 0.0f).coerceIn(-2.0f, 2.0f)
            val seed = prefs.getInt("pref_ai_seed", -1)
            val promptMode = prefs.getString("pref_ai_prompt_mode", "chat") ?: "chat"
            val useChatTemplate = promptMode != "completion"
            // Thinking is a model capability, not a runtime capability. The selected
            // model adapter translates this preference into the model's own template rules.
            val thinkingMode = prefs.getString("pref_ai_thinking_mode", THINKING_OFF) ?: THINKING_OFF
            val prepared = adapter.prepare(
                LocalAiPromptRequest(
                    systemPrompt = systemPrompt,
                    userPrompt = prompt,
                    chatTemplate = sessionChatTemplate,
                    useChatTemplate = useChatTemplate,
                    addGenerationPrompt = true,
                    thinkingMode = thinkingMode
                )
            )

            Log.i(
                TAG,
                "TRANSLATE_START runtime=${runtime.id} model=${sessionModelId.orEmpty()} " +
                    "adapter=${adapter.id} template=${!prepared.chatTemplate.isNullOrBlank()} " +
                    "mode=${if (prepared.useChatTemplate) "chat" else "completion"} " +
                    "thinking=$thinkingMode promptChars=${prepared.messages.lastOrNull()?.second?.length ?: 0} maxTokens=$maxTokens"
            )

            val rawOutput = withContext(inferenceDispatcher) { LocalAiNative.translate(
                messages = prepared.messages,
                chatTemplate = prepared.chatTemplate,
                useChatTemplate = prepared.useChatTemplate,
                addGenerationPrompt = prepared.addGenerationPrompt,
                maxTokens = maxTokens,
                temperature = temperature,
                topP = topP,
                topK = topK,
                repetitionPenalty = repetitionPenalty,
                repeatLastN = repeatLastN,
                minP = minP,
                typicalP = typicalP,
                frequencyPenalty = frequencyPenalty,
                presencePenalty = presencePenalty,
                seed = seed,
                thinkingMode = prepared.nativeTemplateControl
            ).orEmpty() }
            val output = adapter.cleanOutput(rawOutput) ?: rawOutput

            val parsed = parseSingleOutput(output, cleanSource)
            if (parsed != null) parsed else {
                Log.w(TAG, "MODEL_OUTPUT_INVALID retrying")
                val retryPrepared = adapter.prepare(
                    LocalAiPromptRequest(
                        systemPrompt = systemPrompt,
                        userPrompt = "Translate only this subtitle.\n<CURRENT>$cleanSource</CURRENT>",
                        chatTemplate = sessionChatTemplate,
                        useChatTemplate = true,
                        addGenerationPrompt = true,
                        thinkingMode = thinkingMode
                    )
                )
                val retry = withContext(inferenceDispatcher) { LocalAiNative.translate(
                    retryPrepared.messages,
                    retryPrepared.chatTemplate,
                    retryPrepared.useChatTemplate,
                    retryPrepared.addGenerationPrompt,
                    maxTokens,
                    temperature,
                    topP,
                    topK,
                    repetitionPenalty,
                    repeatLastN,
                    minP,
                    typicalP,
                    frequencyPenalty,
                    presencePenalty,
                    seed,
                    retryPrepared.nativeTemplateControl
                ).orEmpty() }
                parseSingleOutput(retry, cleanSource) ?: cleanSource
            }
        }
    }

    private fun selectModel(context: Context): LocalAiModel {
        val models = LocalAiModelManager.installed(context)
        if (models.isEmpty()) throw IOException("설치된 GGUF 모델이 없습니다. 설정에서 모델을 추가하세요.")
        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
        val selectedId = prefs.getString("pref_translation_model_id", null)
        return models.firstOrNull { it.id == selectedId }
            ?: models.firstOrNull { RuntimeRegistry.select(context, it) != null }
            ?: throw IOException("현재 GPU/NPU runtime으로 실행할 수 있는 GGUF 모델이 없습니다.")
    }

    private fun cleanText(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()

    private fun parseSingleOutput(output: String, original: String): String? {
        var value = cleanText(output)
        if (value.isBlank()) return null

        // Thinking-capable models can emit a reasoning block before the final
        // answer. Never expose that block as subtitle text.
        val think = Regex("(?is)^\\s*<think>.*?</think>\\s*").find(value)
        if (think != null) {
            value = value.removeRange(think.range).trim()
        } else if (Regex("(?is)^\\s*<think>\\b").containsMatchIn(value)) {
            // The model hit the output limit while still reasoning. Treat it
            // as invalid so the caller can retry instead of showing CoT.
            return null
        }

        Regex("(?is)<target>\\s*(.*?)\\s*</target>").find(value)?.groupValues?.getOrNull(1)?.let { value = it.trim() }
        value = value.replace(Regex("(?is)^```(?:text|plaintext|korean|ko)?\\s*"), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
        value = value.removePrefix("<assistant>").removeSuffix("</assistant>").trim()
        value = value.replace(Regex("(?im)^(?:현재\\s*번역결과|번역\\s*결과|번역|한국어|translation|korean|ko)\\s*[:：]\\s*"), "").trim()
        if (value.equals(original.trim(), true)) return null
        if (value.contains("<CURRENT>", true) || value.contains("REFERENCE CONTEXT", true) || value.contains("TRANSLATION TASK", true)) return null
        return value
    }
}
