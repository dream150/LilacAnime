package com.lilac.anime.data.subtitle.translation.providers

import android.content.Context
import android.util.Log
import com.lilac.anime.ai.llm.LlmRequest
import com.lilac.anime.ai.llm.LocalLlmRuntime
import com.lilac.anime.ai.llm.Sampling
import com.lilac.anime.ai.llm.ThinkingMode
import com.lilac.anime.data.subtitle.translation.TranslationSessionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

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

/**
 * 공개 API(beginSession/endSession/warmForAnime/clearPersistentSession/translateWithContext, DEFAULT_*, THINKING_*)는
 * 기존과 동일하다. 엔진 선택·가속기 폴백은 [LocalLlmRuntime]이 담당한다.
 */
internal object LocalAiTranslationRuntime {
    private const val TAG = "LocalAiTranslation"
    private const val PROMPT_VERSION = 2
    private val lock = Mutex()
    private val inferenceDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LilacLocalAiInference").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    data class SubtitleContext(val source: String, val translation: String?)

    val DEFAULT_SYSTEM_PROMPT =
        "너는 애니메이션 자막 번역가야. 주어진 문맥들을 참고해서 번역해야 할 일본어 문장만 추가설명 없이 한국어 문장 그대로 번역해. 이름은 발음 그대로 써줘."

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

    private var persistentAnimeId: String? = null

    /** 현재 세션 요약 (엔진 · 가속기 · 모델). 설정 화면/진단 로그에서 사용. */
    fun sessionSummary(): String = LocalLlmRuntime.describe()

    suspend fun warmForAnime(context: Context, animeId: String?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val requested = animeId?.takeIf { it.isNotBlank() }
            if (LocalLlmRuntime.isOpen() && persistentAnimeId == requested) return@withLock
            if (LocalLlmRuntime.isOpen()) LocalLlmRuntime.close()
            LocalLlmRuntime.open(context)
            persistentAnimeId = requested
            Log.i(TAG, "PERSISTENT_SESSION_START anime=${requested.orEmpty()} ${LocalLlmRuntime.describe()}")
        }
    }

    suspend fun clearPersistentSession(animeId: String? = null) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (animeId != null && persistentAnimeId != animeId) return@withLock
            if (!LocalLlmRuntime.isOpen()) {
                persistentAnimeId = null
                return@withLock
            }
            LocalLlmRuntime.close()
            persistentAnimeId = null
            Log.i(TAG, "PERSISTENT_SESSION_END")
        }
    }

    suspend fun beginSession(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (LocalLlmRuntime.isOpen()) return@withLock
            LocalLlmRuntime.open(context)
            Log.i(TAG, "SESSION_START ${LocalLlmRuntime.describe()}")
        }
    }

    suspend fun endSession() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!LocalLlmRuntime.isOpen()) return@withLock
            if (persistentAnimeId != null) {
                Log.i(TAG, "SESSION_END_SKIPPED persistentAnime=${persistentAnimeId.orEmpty()}")
                return@withLock
            }
            LocalLlmRuntime.close()
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
        if (!LocalLlmRuntime.isOpen()) beginSession(context)
        lock.withLock {
            val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            prefs.edit().putInt("pref_ai_prompt_version", PROMPT_VERSION).apply()

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
            val sampling = Sampling(
                maxTokens = prefs.getInt("pref_ai_max_tokens", 256).coerceIn(32, 2048),
                temperature = prefs.getFloat("pref_ai_temperature", 0.25f).coerceIn(0.0f, 2.0f),
                topP = prefs.getFloat("pref_ai_top_p", 0.85f).coerceIn(0.01f, 1.0f),
                topK = prefs.getInt("pref_ai_top_k", 40).coerceIn(1, 200),
                repetitionPenalty = prefs.getFloat("pref_ai_repetition_penalty", 1.05f).coerceIn(0.8f, 1.5f),
                repeatLastN = prefs.getInt("pref_ai_repeat_last_n", 64).coerceIn(0, 512),
                minP = prefs.getFloat("pref_ai_min_p", 0.0f).coerceIn(0.0f, 1.0f),
                typicalP = prefs.getFloat("pref_ai_typical_p", 1.0f).coerceIn(0.01f, 1.0f),
                frequencyPenalty = prefs.getFloat("pref_ai_frequency_penalty", 0.0f).coerceIn(-2.0f, 2.0f),
                presencePenalty = prefs.getFloat("pref_ai_presence_penalty", 0.0f).coerceIn(-2.0f, 2.0f),
                seed = prefs.getInt("pref_ai_seed", -1)
            )
            val useChatTemplate = (prefs.getString("pref_ai_prompt_mode", "chat") ?: "chat") != "completion"
            val thinking = ThinkingMode.from(prefs.getString("pref_ai_thinking_mode", THINKING_OFF))

            Log.i(
                TAG,
                "TRANSLATE_START ${LocalLlmRuntime.describe()} mode=${if (useChatTemplate) "chat" else "completion"} " +
                    "thinking=$thinking promptChars=${prompt.length} maxTokens=${sampling.maxTokens}"
            )

            val rawOutput = withContext(inferenceDispatcher) {
                LocalLlmRuntime.generate(
                    context,
                    LlmRequest(systemPrompt, prompt, sampling, thinking, useChatTemplate)
                )
            }

            parseSingleOutput(rawOutput, cleanSource) ?: run {
                Log.w(TAG, "MODEL_OUTPUT_INVALID retrying")
                val retry = withContext(inferenceDispatcher) {
                    LocalLlmRuntime.generate(
                        context,
                        LlmRequest(
                            systemPrompt = systemPrompt,
                            userPrompt = "Translate only this subtitle.\n<CURRENT>$cleanSource</CURRENT>",
                            sampling = sampling,
                            thinking = thinking,
                            useChatTemplate = true
                        )
                    )
                }
                parseSingleOutput(retry, cleanSource) ?: cleanSource
            }
        }
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
