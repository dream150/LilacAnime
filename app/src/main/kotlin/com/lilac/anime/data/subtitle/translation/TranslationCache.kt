package com.lilac.anime.data.subtitle.translation

import android.content.Context
import java.io.File
import java.security.MessageDigest

object TranslationCache {
    private const val DIR = "translation_cache"
    fun keyFor(file: File, provider: String, target: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = file.inputStream().use { it.readBytes() }
        val hash = digest.digest(bytes).joinToString("") { "%02x".format(it) }
        return "${hash}_${provider}_${target}"
    }
    fun get(context: Context, key: String): String? = File(context.filesDir, DIR).listFiles()?.firstOrNull { it.name.startsWith("$key.") && it.isFile && it.length() > 0 }?.absolutePath
    fun put(context: Context, key: String, path: String): String {
        val source = File(path)
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val out = File(dir, key + "." + source.extension.lowercase())
        val copied = source.copyTo(out, overwrite = true)
        if (!out.isFile || out.length() != source.length()) {
            android.util.Log.e(
                "SubtitleProfile",
                "CACHE_WRITE_VERIFY_FAILED source=${source.absolutePath} sourceBytes=${source.length()} output=${out.absolutePath} outputBytes=${out.length()} copied=${copied.absolutePath}"
            )
            throw IllegalStateException("번역 캐시 파일 저장 검증에 실패했습니다.")
        }
        android.util.Log.d("SubtitleProfile", "CACHE_WRITE_SUCCESS path=${out.absolutePath} bytes=${out.length()}")
        return out.absolutePath
    }
    fun clear(context: Context) { File(context.filesDir, DIR).deleteRecursively(); File(context.filesDir, "translated_subtitles").deleteRecursively() }
}
