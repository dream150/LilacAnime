package com.lilac.anime.data.subtitle.translation

import android.content.Context
import com.lilac.anime.data.subtitle.translation.providers.DeepLTranslator
import com.lilac.anime.data.subtitle.translation.providers.LocalTranslator
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
    private fun provider(context: Context, providerId: String): TranslationProvider = when (providerId) {
        "openai" -> OpenAITranslator(context)
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
        val p = provider(context, providerId)
        val cacheKey = TranslationCache.keyFor(source, p.id, "ko") + "_${animeId}_${episodeKey}"
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
        runCatching { provider(context, providerId).translateBatch(texts) }.getOrElse { texts }
    }

    suspend fun translateText(context: Context, providerId: String, text: String): String? {
        val normalized = text.trim()
        if (normalized.isBlank()) return normalized
        return runCatching {
            provider(context, providerId).translateBatch(listOf(normalized)).firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun test(context: Context, providerId: String, text: String = "こんにちは。今日はいい天気ですね。") : Result<String> = runCatching {
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
            val translated = provider(context, providerId).translateBatch(sourceTexts)
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
            val result = provider(context, providerId).translateBatch(listOf(text))
            result.firstOrNull()?.takeIf { it.isNotBlank() } ?: error("번역 결과가 비어 있습니다.")
        }
    }
}
