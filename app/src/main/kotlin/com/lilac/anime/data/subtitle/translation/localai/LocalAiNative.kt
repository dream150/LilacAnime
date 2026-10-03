package com.lilac.anime.data.subtitle.translation.localai

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Model-agnostic JNI bridge for the installed llama.cpp acceleration runtime.
 * The bridge never selects a model family or quantization-specific path.
 */
internal object LocalAiNative {
    private const val TAG = "LocalAiNative"

    private var initialized = false
    private var loaded = false
    private var loadedKey: String? = null
    private var loadError: Throwable? = null

    private external fun nativeInit(runtimeDirectory: String, backend: String): Boolean
    private external fun nativeLoad(modelPath: String, contextSize: Int, threads: Int): Boolean
    private external fun nativeTranslateAdvanced(
        roles: Array<String>,
        contents: Array<String>,
        chatTemplate: String,
        useChatTemplate: Boolean,
        addGenerationPrompt: Boolean,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        repeatLastN: Int,
        minP: Float,
        typicalP: Float,
        frequencyPenalty: Float,
        presencePenalty: Float,
        seed: Int,
        thinkingEnabled: Boolean
    ): String?
    private external fun nativeRelease()
    private external fun nativeShutdown()

    private var currentContextSize = 4096
    private var currentThreads = 6

    fun load(context: Context, runtime: RuntimeRegistry.Candidate, modelPath: String): Boolean {
        val pack = runtime.pack ?: run {
            loadError = IOException("GPU/NPU runtime pack이 없습니다.")
            return false
        }
        val directory = File(pack.directory)
        val bridge = File(directory, pack.libraryFile)
        if (!bridge.isFile) {
            loadError = IOException("runtime JNI bridge가 없습니다: ${bridge.absolutePath}")
            return false
        }

        val key = "${runtime.id}|${runtime.version}|${directory.absolutePath}|${pack.libraryFile}"
        if (loaded && loadedKey == key) {
            return runCatching { nativeLoad(modelPath, currentContextSize, currentThreads) }
                .onFailure { loadError = it }
                .getOrDefault(false)
        }
        if (initialized && loadedKey != key) {
            loadError = IOException("다른 native runtime은 앱 재시작 후 사용할 수 있습니다.")
            return false
        }

        return runCatching {
            loadRuntimeDependencies(directory)
            System.load(bridge.absolutePath)
            Log.i(TAG, "JNI_BRIDGE_LOADED runtime=${runtime.id} path=${bridge.absolutePath}")

            val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            currentContextSize = prefs.getInt("pref_ai_context_size", 4096).coerceIn(1024, 32768)
            val configuredThreads = prefs.getInt("pref_ai_threads", 0).coerceIn(0, 12)
            currentThreads = if (configuredThreads > 0) configuredThreads
            else Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

            check(nativeInit(directory.absolutePath, "auto")) { "GPU/NPU native runtime 초기화에 실패했습니다." }
            initialized = true
            loadedKey = key
            check(nativeLoad(modelPath, currentContextSize, currentThreads)) {
                "GPU/NPU runtime에서 모델을 로드하지 못했습니다."
            }
            loaded = true
            true
        }.onFailure {
            loadError = it
            Log.e(TAG, "RUNTIME_LOAD_FAILED", it)
            loaded = false
            if (initialized) runCatching { nativeShutdown() }
            initialized = false
            loadedKey = null
        }.getOrDefault(false)
    }

    private fun loadRuntimeDependencies(directory: File) {
        listOf(
            "libggml-base.so",
            "libggml-cpu.so",
            "libggml-opencl.so",
            "libggml-hexagon.so",
            "libggml.so",
            "libllama-common.so",
            "libllama.so"
        ).forEach { name ->
            val file = File(directory, name)
            if (file.isFile) {
                runCatching { System.load(file.absolutePath) }
                    .onFailure { Log.w(TAG, "DEPENDENCY_LOAD_FAILED name=$name", it) }
            }
        }
    }

    fun translate(
        messages: List<Pair<String, String>>,
        chatTemplate: String?,
        useChatTemplate: Boolean,
        addGenerationPrompt: Boolean,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        repeatLastN: Int,
        minP: Float,
        typicalP: Float,
        frequencyPenalty: Float,
        presencePenalty: Float,
        seed: Int,
        thinkingEnabled: Boolean
    ): String? {
        if (!loaded || messages.isEmpty()) return null
        val roles = messages.map { it.first }.toTypedArray()
        val contents = messages.map { it.second }.toTypedArray()
        return nativeTranslateAdvanced(
            roles,
            contents,
            chatTemplate.orEmpty(),
            useChatTemplate,
            addGenerationPrompt,
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
            thinkingEnabled
        )
    }

    fun release() {
        if (initialized) runCatching { nativeRelease() }
        loaded = false
    }

    fun shutdown() {
        if (initialized) runCatching { nativeShutdown() }
        loaded = false
        initialized = false
        loadedKey = null
    }

    fun error(): Throwable? = loadError
}
