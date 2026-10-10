package com.lilac.anime.data.subtitle.translation

import android.content.Context
import com.lilac.anime.data.subtitle.translation.providers.DeepLTranslator
import com.lilac.anime.data.subtitle.translation.providers.LocalTranslator
import com.lilac.anime.data.subtitle.translation.providers.GeminiTranslator
import com.lilac.anime.data.subtitle.translation.providers.OpenAITranslator
import com.lilac.anime.data.subtitle.translation.providers.QwenTranslator
import java.io.File
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

object TranslationManager {
    fun createProvider(context: Context, providerId: String): TranslationProvider = when (providerId) {
        "openai" -> OpenAITranslator(context)
        "gemini" -> GeminiTranslator(context)
        "deepl" -> DeepLTranslator(context)
        "qwen" -> QwenTranslator(context)
        else -> LocalTranslator(context)
    }

    suspend fun translateFile(context: Context, sourcePath: String, animeId: String, episodeKey: String, providerId: String): String? = withContext(NonCancellable) {
        val startedAt = System.nanoTime()
        val source = File(sourcePath)
        if (!source.isFile) {
            android.util.Log.e("SubtitleProfile", "TRANSLATE_SOURCE_NOT_FILE path=${source.absolutePath}")
            return@withContext null
        }
        val p = createProvider(context, providerId)
        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
        val modelSignature = when (p.id) {
            "gemini" -> prefs.getString("pref_gemini_model", "gemini-2.5-flash")
            "openai" -> prefs.getString("pref_openai_model", "gpt-4.1-mini")
            "qwen" -> prefs.getString("pref_qwen_model", "qwen-plus")
            else -> null
        }.orEmpty()
        val safeModelSignature = modelSignature.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val cacheKey = TranslationCache.keyFor(source, if (safeModelSignature.isBlank()) p.id else "${p.id}_$safeModelSignature", "ko") + "_${animeId}_${episodeKey}"
        TranslationCache.get(context, cacheKey)?.let {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            android.util.Log.d("SubtitleProfile", "CACHE_HIT provider=${p.id} file=${source.name} elapsedMs=$elapsedMs")
            return@withContext it
        }
        val translated = try {
            SubtitleFormatTranslator.translate(context, sourcePath, cacheKey, p)
        } catch (error: Throwable) {
            android.util.Log.e(
                "SubtitleProfile",
                "TRANSLATE_EXCEPTION provider=${p.id} source=${source.absolutePath} type=${error::class.java.name} message=${error.message}",
                error
            )
            throw error
        } ?: run {
            android.util.Log.e("SubtitleProfile", "TRANSLATE_RESULT_NULL source=${source.absolutePath} provider=${p.id}")
            return@withContext null
        }
        val result = TranslationCache.put(context, cacheKey, translated)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        android.util.Log.d("SubtitleProfile", "TRANSLATE_FILE_DONE provider=${p.id} file=${source.name} elapsedMs=$elapsedMs")
        result
    }

    suspend fun exportTranslatedFile(context: Context, translatedPath: String, displayName: String): Uri? = withContext(Dispatchers.IO) {
        val source = File(translatedPath)
        if (!source.isFile) {
            android.util.Log.e("SubtitleProfile", "EXPORT_SOURCE_NOT_FILE path=${source.absolutePath}")
            return@withContext null
        }
        android.util.Log.d("SubtitleProfile", "EXPORT_START source=${source.absolutePath} bytes=${source.length()} displayName=$displayName")
        val safeName = displayName.replace(Regex("[\\/:*?\"<>|]"), "_")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(safeName))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LilacAnime")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run {
                android.util.Log.e("SubtitleProfile", "EXPORT_INSERT_FAILED displayName=$safeName")
                return@withContext null
            }
            android.util.Log.d("SubtitleProfile", "EXPORT_INSERT_SUCCESS uri=$uri")
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(source).use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("번역본 파일을 저장할 수 없습니다.")
                android.util.Log.d("SubtitleProfile", "EXPORT_COPY_SUCCESS uri=$uri bytes=${source.length()}")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                android.util.Log.d("SubtitleProfile", "EXPORT_COMPLETE uri=$uri")
                uri
            } catch (e: Exception) {
                android.util.Log.e("SubtitleProfile", "EXPORT_FAILED uri=$uri", e)
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).resolve("LilacAnime")
            val created = dir.mkdirs()
            android.util.Log.d("SubtitleProfile", "EXPORT_LEGACY_DIR path=${dir.absolutePath} exists=${dir.exists()} created=$created writable=${dir.canWrite()}")
            val target = dir.resolve(safeName)
            try {
                FileInputStream(source).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                android.util.Log.d("SubtitleProfile", "EXPORT_COPY_SUCCESS path=${target.absolutePath} bytes=${target.length()}")
            } catch (e: Exception) {
                android.util.Log.e("SubtitleProfile", "EXPORT_FAILED path=${target.absolutePath}", e)
                throw e
            }
            android.util.Log.d("SubtitleProfile", "EXPORT_COMPLETE path=${target.absolutePath}")
            Uri.fromFile(target)
        }
    }

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "ass", "ssa" -> "text/x-ssa"
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "smi", "sami" -> "text/plain"
        "sbv", "sub", "mpl", "mpl2" -> "text/plain"
        "ttml", "xml" -> "application/ttml+xml"
        else -> "text/plain"
    }

    suspend fun translateBatch(context: Context, providerId: String, texts: List<String>): List<String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val provider = createProvider(context, providerId)
        var last: Throwable? = null
        for (attempt in 0..3) {
            try {
                return@withContext provider.translateBatch(texts)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                last = t
                val message = t.message.orEmpty()
                val retryable = message.contains("HTTP 429") ||
                    Regex("HTTP 5\\d{2}").containsMatchIn(message) ||
                    t is java.io.IOException
                if (!retryable || attempt == 3) break
                val delayMs = (1500L shl attempt).coerceAtMost(8000L)
                android.util.Log.w("TranslationManager", "RETRY provider=$providerId attempt=${attempt + 1} delayMs=$delayMs message=$message")
                kotlinx.coroutines.delay(delayMs)
            }
        }
        throw last ?: IllegalStateException("${provider.displayName} 번역에 실패했습니다.")
    }

    suspend fun translateText(context: Context, providerId: String, text: String): String? {
        val normalized = text.trim()
        if (normalized.isBlank()) return normalized
        return runCatching {
            translateBatch(context, providerId, listOf(normalized)).firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun testConnection(context: Context, providerId: String): Result<String> = runCatching {
        when (providerId) {
            "gemini" -> (createProvider(context, providerId) as GeminiTranslator).testConnection()
            "openai" -> (createProvider(context, providerId) as OpenAITranslator).testConnection()
            "deepl" -> (createProvider(context, providerId) as DeepLTranslator).testConnection()
            "qwen" -> (createProvider(context, providerId) as QwenTranslator).testConnection()
            else -> error("${createProvider(context, providerId).displayName}는 API 키 연결 테스트를 지원하지 않습니다.")
        }
    }

    suspend fun test(context: Context, providerId: String, text: String = "こんにちは。今日はいい天気ですね。"): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            if (Regex("(?m)^\\s*Dialogue:").containsMatchIn(normalized)) {
                val lines = normalized.split('\n').toMutableList()
                val dialogueIndexes = mutableListOf<Int>()
                val sourceTexts = mutableListOf<String>()
                lines.forEachIndexed { index, line ->
                    if (!line.startsWith("Dialogue:", true)) return@forEachIndexed
                    val body = line.substringAfter(':').trimStart()
                    val parts = body.split(',', limit = 10)
                    if (parts.size < 10) return@forEachIndexed
                    dialogueIndexes += index
                    sourceTexts += parts[9].trim()
                }
                if (sourceTexts.isEmpty()) error("번역할 Dialogue가 없습니다.")
                val translated = createProvider(context, providerId).translateBatch(sourceTexts)
                dialogueIndexes.forEachIndexed { i, lineIndex ->
                    val translatedText = translated.getOrNull(i)?.takeIf { it.isNotBlank() } ?: sourceTexts[i]
                    val parts = lines[lineIndex].substringAfter(':').trimStart().split(',', limit = 10).toMutableList()
                    if (parts.size >= 10) {
                        parts[9] = translatedText
                        lines[lineIndex] = "Dialogue: " + parts.joinToString(",")
                    }
                }
                lines.joinToString("\n")
            } else {
                val result = createProvider(context, providerId).translateBatch(listOf(text))
                val translated = result.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: error("번역 결과가 비어 있습니다.")
                if (providerId != "local" && translated == text.trim()) error("API 요청은 성공했지만 번역 결과가 원문과 같습니다. 모델/프롬프트 응답을 확인하세요.")
                translated
            }
        }
    }
}
