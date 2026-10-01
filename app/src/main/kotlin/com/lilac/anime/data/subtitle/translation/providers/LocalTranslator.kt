package com.lilac.anime.data.subtitle.translation.providers

import android.content.Context
import android.util.Log
import com.lilac.anime.data.subtitle.translation.TranslationProvider
import com.lilac.anime.data.subtitle.translation.TranslationSessionProvider
import com.lilac.anime.data.subtitle.translation.localai.LocalAiModelManager
import com.lilac.anime.data.subtitle.translation.localai.LocalAiRuntimeManager
import com.lilac.anime.data.subtitle.translation.localai.RuntimeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class LocalTranslator(private val context: Context) : TranslationSessionProvider {
    override val id = "local"
    override val displayName = "로컬 AI"
    private var sessionActive = false

    override suspend fun beginSession() {
        if (sessionActive) {
            Log.d("LocalAiTranslation", "PROVIDER_SESSION_REUSE")
            return
        }
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
            // Compatibility implementation for the generic TranslationProvider API.
            // There is no model-level batch translation anymore: each subtitle is
            // translated as a single cue, with only the preceding cues supplied as context.
            val results = ArrayList<String>(lines.size)
            for (index in lines.indices) {
                val contextCount = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
                .getInt("pref_ai_context_cues", 3).coerceIn(0, 10)
            val contextLines = lines.subList((index - contextCount).coerceAtLeast(0), index)
                results += translateWithContext(lines[index], contextLines)
            }
            results
        } finally {
            if (ownSession) endSession()
        }
    }

    suspend fun translateWithContext(source: String, contextLines: List<String>): String {
        if (source.isBlank()) return source
        val ownSession = !sessionActive
        if (ownSession) beginSession()
        return try {
            LocalAiTranslationRuntime.translateWithContext(context, source, contextLines)
        } finally {
            if (ownSession) endSession()
        }
    }
}

internal object HunyuanQ2Native {
    private const val TAG = "LocalAiNative"
    private var loaded = false
    private var initialized = false
    private var loadedLibrary: String? = null
    private var loadError: Throwable? = null

    private external fun nativeInit(runtimeDirectory: String, backend: String): Boolean
    private external fun nativeLoad(path: String, contextSize: Int, threads: Int): Boolean
    private external fun nativeTranslate(roles: Array<String>, contents: Array<String>, chatTemplate: String, addGenerationPrompt: Boolean, maxTokens: Int, temperature: Float, topP: Float, topK: Int, repetitionPenalty: Float): String?
    private external fun nativeRelease()
    private external fun nativeShutdown()

    private fun loadLibraryIfPresent(directory: File, name: String) {
        val file = File(directory, name)
        if (file.isFile) {
            System.load(file.absolutePath)
            Log.i(TAG, "dependency loaded: ${file.name}")
        }
    }

    fun load(runtimeDirectory: String, libraryFile: String, modelPath: String): Boolean {
        val directory = File(runtimeDirectory)
        val runtimeLibrary = File(directory, libraryFile)
        val loadKey = "apk:lilac_hunyuan_jni|${runtimeDirectory}|${libraryFile}"
        if (loaded && loadedLibrary == loadKey) return nativeLoad(modelPath, currentContextSize, currentThreads)
        if (initialized && loadedLibrary != loadKey) {
            throw IOException("다른 native runtime은 앱 재시작 후 사용할 수 있습니다.")
        }
        return runCatching {
            listOf(
                "libggml-base.so",
                "libggml-cpu.so",
                "libggml-opencl.so",
                "libggml-hexagon.so",
                "libggml.so",
                "libllama-common.so",
                "libllama.so"
            ).forEach { name ->
                runCatching { loadLibraryIfPresent(directory, name) }
                    .onFailure { Log.w(TAG, "optional dependency load failed: $name", it) }
            }

            val bridgeSource = runCatching {
                System.loadLibrary("lilac_hunyuan_jni")
                "apk"
            }.getOrElse {
                if (!runtimeLibrary.isFile) throw IOException("APK JNI bridge와 runtime JNI bridge가 모두 없습니다: ${runtimeLibrary.absolutePath}")
                System.load(runtimeLibrary.absolutePath)
                "runtime-pack"
            }
            Log.i(TAG, "JNI bridge loaded source=$bridgeSource runtime=${directory.absolutePath}")

            val prefs = appContext?.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            val contextSize = prefs?.getInt("pref_ai_context_size", 4096)?.coerceIn(1024, 16384) ?: 4096
            val configuredThreads = prefs?.getInt("pref_ai_threads", 0)?.coerceIn(0, 12) ?: 0
            val threads = if (configuredThreads > 0) configuredThreads else Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            currentContextSize = contextSize
            currentThreads = threads
            if (!nativeInit(directory.absolutePath, "auto")) throw IOException("native runtime 초기화에 실패했습니다.")
            initialized = true
            loadedLibrary = loadKey
            loaded = nativeLoad(modelPath, contextSize, threads)
            if (!loaded) throw IOException("모델을 native runtime에 불러오지 못했습니다.")
            true
        }.onFailure {
            loadError = it
            Log.e(TAG, "runtime load failed", it)
            runCatching { nativeShutdown() }
            loaded = false
            initialized = false
            loadedLibrary = null
        }.getOrDefault(false)
    }

    private var appContext: Context? = null
    private var currentContextSize = 4096
    private var currentThreads = 6

    fun setContext(context: Context) { appContext = context.applicationContext }

    fun translate(messages: List<Pair<String, String>>, chatTemplate: String?, addGenerationPrompt: Boolean, maxTokens: Int, temperature: Float, topP: Float, topK: Int, repetitionPenalty: Float): String? {
        if (!loaded || messages.isEmpty()) return null
        val roles = messages.map { it.first }.toTypedArray()
        val contents = messages.map { it.second }.toTypedArray()
        return nativeTranslate(roles, contents, chatTemplate.orEmpty(), addGenerationPrompt, maxTokens, temperature, topP, topK, repetitionPenalty)
    }

    fun release() {
        if (initialized) nativeRelease()
        loaded = false
    }

    fun shutdown() {
        if (initialized) nativeShutdown()
        loaded = false
        initialized = false
        loadedLibrary = null
    }

    fun error(): Throwable? = loadError
}


internal object LocalAiTranslationRuntime {
    private const val TAG = "LocalAiTranslation"
    const val DEFAULT_PROMPT = "{context}\n\nUse the context above only as reference. Do not translate or repeat it. Translate only the following current subtitle into Korean, without additional explanation.\n\n{source_text}"
    private val lock = Mutex()
    private var sessionModel: dev.ffmpegkit.llama.LlamaModel? = null
    private var sessionRuntime: RuntimeRegistry.Candidate? = null
    private var sessionModelId: String? = null
    private var sessionChatTemplate: String? = null
    private var sessionContext: Context? = null

    suspend fun beginSession(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (sessionRuntime != null) return@withLock
            beginSessionInternal(context)
            Log.i(TAG, "SESSION_START RUNTIME=${sessionRuntime?.id} VERSION=${sessionRuntime?.version} MODEL=${sessionModelId.orEmpty()}")
        }
    }

    suspend fun endSession() = withContext(Dispatchers.IO) {
        lock.withLock {
            val runtime = sessionRuntime
            if (runtime?.kind == RuntimeRegistry.Kind.BUILTIN_LLAMA) {
                sessionModel?.let { dev.ffmpegkit.llama.Llama.releaseModel(it) }
            } else if (runtime?.kind == RuntimeRegistry.Kind.NATIVE_PACK) {
                HunyuanQ2Native.release()
            }
            sessionModel = null
            sessionRuntime = null
            sessionModelId = null
            sessionChatTemplate = null
            sessionContext = null
            Log.i(TAG, "SESSION_END")
        }
    }

    suspend fun translateWithContext(context: Context, source: String, contextLines: List<String>): String = withContext(Dispatchers.IO) {
        if (source.isBlank()) return@withContext source
        if (sessionRuntime == null) beginSession(context)
        lock.withLock {
            val runtime = sessionRuntime ?: throw IOException("local AI runtime이 초기화되지 않았습니다.")
            val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            val savedPrompt = prefs.getString("pref_translation_prompt", null)?.trim()
            val legacyDefaultPrompt = "Translate the following subtitle segment into Korean, without additional explanation.\n\nPreserve all subtitle formatting, timing, positioning, styling, effect, control, and metadata tags exactly as they are. Do not translate, remove, rename, reorder, or modify any tags, tag parameters, timestamps, escape sequences, or special characters. Preserve line breaks and the original structure. Translate only natural-language subtitle text. Output only the translated subtitle.\n\n{source_text}"
            val isLegacyDefault = savedPrompt == legacyDefaultPrompt
            val template = if (savedPrompt.isNullOrBlank() || isLegacyDefault) DEFAULT_PROMPT else savedPrompt
            val promptSource = if (savedPrompt.isNullOrBlank() || isLegacyDefault) "default" else "user"
            val contextCount = prefs.getInt("pref_ai_context_cues", 3).coerceIn(0, 10)
            val cleanContext = contextLines
                .map { it.replace("\r\n", "\n").replace('\r', '\n').trim() }
                .filter { it.isNotBlank() }
                .takeLast(contextCount)
            val contextText = cleanContext.joinToString("\n")
            val cleanSource = source.replace("\r\n", "\n").replace('\r', '\n').trim()
            val prompt = if (template.contains("{context}")) {
                val contextualTemplate = template.replace("{context}", contextText)
                if (template.contains("{source_text}")) contextualTemplate.replace("{source_text}", cleanSource)
                else "$contextualTemplate\n\nCurrent subtitle:\n$cleanSource"
            } else {
                val sourcePart = if (template.contains("{source_text}")) template.replace("{source_text}", cleanSource) else "$template\n\n$cleanSource"
                if (contextText.isBlank()) sourcePart else """Context for reference only (do not translate or repeat):
$contextText

Translate only the current subtitle below into Korean. Use the context only to resolve meaning, names, and pronouns. Do not include any context text in the output. Output only the translation of the current subtitle.

Current subtitle:
$sourcePart"""
            }
            Log.i(TAG, "CONTEXT_TRANSLATION promptSource=$promptSource contextCount=${cleanContext.size} sourceChars=${cleanSource.length} contextChars=${contextText.length} promptChars=${prompt.length}")
            Log.d(TAG, "PROMPT_INPUT context=${contextText.replace("\n", "\\n").take(800)} current=${cleanSource.replace("\n", "\\n").take(500)} prompt=${prompt.replace("\n", "\\n").take(1400)}")
            val maxTokens = prefs.getInt("pref_ai_max_tokens", 1536).coerceIn(256, 4096)
            val temperature = prefs.getFloat("pref_ai_temperature", 0.7f).coerceIn(0.0f, 2.0f)
            val topP = prefs.getFloat("pref_ai_top_p", 0.6f).coerceIn(0.05f, 1.0f)
            val topK = prefs.getInt("pref_ai_top_k", 20).coerceIn(1, 100)
            val repetitionPenalty = prefs.getFloat("pref_ai_repetition_penalty", 1.05f).coerceIn(1.0f, 1.5f)
            Log.i(TAG, "SAMPLING temperature=$temperature topP=$topP topK=$topK repetitionPenalty=$repetitionPenalty maxTokens=$maxTokens")
            val output = when (runtime.kind) {
                RuntimeRegistry.Kind.BUILTIN_LLAMA -> {
                    val llamaModel = sessionModel ?: throw IOException("llama 모델이 로드되지 않았습니다.")
                    dev.ffmpegkit.llama.Llama.complete(llamaModel, prompt = prompt, maxTokens = maxTokens).text
                }
                RuntimeRegistry.Kind.NATIVE_PACK -> {
                    val messages = listOf("user" to prompt)
                    Log.i(TAG, "CHAT_TEMPLATE_RUNTIME runtime=native mode=messages addGenerationPrompt=false templatePresent=${!sessionChatTemplate.isNullOrBlank()} promptChars=${prompt.length}")
                    HunyuanQ2Native.translate(messages, sessionChatTemplate, false, maxTokens, temperature, topP, topK, repetitionPenalty) ?: ""
                }
            }
            Log.d(TAG, "MODEL_RAW_OUTPUT ${output.replace("\n", "\\n").take(1200)}")
            val parsedOutput = parseSingleOutput(output, cleanSource)
            Log.d(TAG, "PARSED_OUTPUT ${parsedOutput.replace("\n", "\\n").take(800)}")
            parsedOutput
        }
    }


    private suspend fun beginSessionInternal(context: Context) {
        HunyuanQ2Native.setContext(context)
        val model = selectModel(context)
        var runtime = RuntimeRegistry.select(context, model)
            ?: throw IOException("이 모델과 호환되는 runtime이 없습니다: ${model.displayName}")
        when (runtime.kind) {
            RuntimeRegistry.Kind.BUILTIN_LLAMA -> {
                val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
                val contextSize = prefs.getInt("pref_ai_context_size", 4096).coerceIn(1024, 16384)
                val configuredThreads = prefs.getInt("pref_ai_threads", 0).coerceIn(0, 12)
                val threads = if (configuredThreads > 0) configuredThreads else Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                sessionModel = dev.ffmpegkit.llama.Llama.loadModel(
                    modelPath = model.localPath,
                    config = dev.ffmpegkit.llama.LlamaConfig(
                        contextSize = contextSize,
                        threads = threads
                    )
                )
            }
            RuntimeRegistry.Kind.NATIVE_PACK -> {
                val pack = runtime.pack ?: throw IOException("선택된 native runtime 정보가 없습니다.")
                if (!HunyuanQ2Native.load(pack.directory, pack.libraryFile, model.localPath)) {
                    val preferred = RuntimeRegistry.selectedId(context, model)
                    val fallback = RuntimeRegistry.compatible(context, model)
                        .firstOrNull { it.kind == RuntimeRegistry.Kind.BUILTIN_LLAMA }
                    if (preferred == RuntimeRegistry.AUTO && fallback != null) {
                        Log.w(TAG, "SNAPDRAGON_RUNTIME_FAILED_FALLBACK_CPU error=${HunyuanQ2Native.error()?.message.orEmpty()}")
                        HunyuanQ2Native.shutdown()
                        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
                        val contextSize = prefs.getInt("pref_ai_context_size", 4096).coerceIn(1024, 16384)
                        val configuredThreads = prefs.getInt("pref_ai_threads", 0).coerceIn(0, 12)
                        val threads = if (configuredThreads > 0) configuredThreads else Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                        sessionModel = dev.ffmpegkit.llama.Llama.loadModel(
                            modelPath = model.localPath,
                            config = dev.ffmpegkit.llama.LlamaConfig(contextSize = contextSize, threads = threads)
                        )
                        runtime = fallback
                    } else {
                        throw IOException("native runtime을 불러오지 못했습니다: ${HunyuanQ2Native.error()?.message.orEmpty()}")
                    }
                }
            }
        }
        sessionRuntime = runtime
        sessionModelId = model.id
        sessionChatTemplate = model.chatTemplate
        sessionContext = context.applicationContext
    }

    private fun selectModel(context: Context): com.lilac.anime.data.subtitle.translation.localai.LocalAiModel {
        val models = LocalAiModelManager.installed(context)
        if (models.isEmpty()) throw IOException("설치된 GGUF 모델이 없습니다. 설정에서 모델을 추가하세요.")
        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
        val selectedId = prefs.getString("pref_translation_model_id", null)
        return models.firstOrNull { it.id == selectedId }
            ?: models.firstOrNull { RuntimeRegistry.select(context, it) != null }
            ?: throw IOException("현재 실행할 수 있는 GGUF 모델이 없습니다.")
    }

    private fun parseSingleOutput(output: String, original: String): String {
        var value = output.replace("\r\n", "\n").replace('\r', '\n').trim()
        value = value.removePrefix("<target>").removeSuffix("</target>").trim()
        value = value.replace(Regex("(?is)^```(?:text|plaintext|korean|ko)?\\s*"), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
        val labelled = Regex("(?is)<target>(.*?)</target>").find(value)?.groupValues?.getOrNull(1)?.trim()
        if (!labelled.isNullOrBlank()) value = labelled
        val lines = value.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return original
        if (lines.size == 1) return lines[0]
        // A multiline subtitle is still one cue. Preserve it rather than allowing
        // the next cue to be accidentally consumed by the output parser.
        return lines.joinToString("\n")
    }

    private fun parseLines(output: String, original: List<String>): List<String> {
        val normalized = output
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val rawLines = normalized.split('\n').toMutableList()
        while (rawLines.firstOrNull()?.trim().orEmpty().startsWith("```") ) rawLines.removeAt(0)
        while (rawLines.lastOrNull()?.trim().orEmpty().startsWith("```") ) rawLines.removeAt(rawLines.lastIndex)
        while (rawLines.firstOrNull()?.isBlank() == true) rawLines.removeAt(0)
        while (rawLines.lastOrNull()?.isBlank() == true) rawLines.removeAt(rawLines.lastIndex)

        val rebuilt = ArrayList<String>(original.size)
        var outputIndex = 0

        for (originalIndex in original.indices) {
            if (outputIndex >= rawLines.size) {
                rebuilt += original[originalIndex]
                continue
            }

            val expectedLineCount = original[originalIndex]
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split('\n')
                .size

            val first = rawLines[outputIndex]
            if (expectedLineCount <= 1 || first.contains("\\N")) {
                rebuilt += first.trimEnd()
                outputIndex++
                continue
            }

            val endExclusive = (outputIndex + expectedLineCount).coerceAtMost(rawLines.size)
            val combined = rawLines.subList(outputIndex, endExclusive).joinToString("\\N")
            rebuilt += combined.trimEnd()
            outputIndex = endExclusive
        }

        if (rebuilt.size != original.size || outputIndex != rawLines.size) {
            Log.w(
                TAG,
                "BATCH_OUTPUT_LINE_MISMATCH expected=${original.size} actualRaw=${rawLines.size} reconstructed=${rebuilt.size} consumed=$outputIndex outputChars=${normalized.length}"
            )
        }

        return List(original.size) { index ->
            val value = rebuilt.getOrNull(index)?.trimEnd().orEmpty()
            if (value.isBlank()) original[index] else value
        }
    }

}
