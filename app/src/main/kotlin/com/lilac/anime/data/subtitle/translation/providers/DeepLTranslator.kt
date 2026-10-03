package com.lilac.anime.data.subtitle.translation.providers

import android.content.Context
import com.lilac.anime.data.subtitle.translation.SecureApiKeyStore
import com.lilac.anime.data.subtitle.translation.TranslationProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DeepLTranslator(private val context: Context) : TranslationProvider {
    override val id = "deepl"
    override val displayName = "DeepL"
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()

    override suspend fun translateBatch(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val key = SecureApiKeyStore.get(context, id)?.trim()?.takeIf { it.isNotEmpty() } ?: error("DeepL API Key가 없습니다.")
        val endpoint = if (key.endsWith(":fx", true)) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
        val body = JSONObject().apply {
            put("text", JSONArray().apply { lines.forEach { put(it) } })
            put("source_lang", "JA")
            put("target_lang", "KO")
            put("preserve_formatting", true)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(endpoint).header("Authorization", "DeepL-Auth-Key $key").post(body).build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(responseText).optString("message") }.getOrDefault("")
                error("DeepL HTTP ${response.code}${if (message.isNotBlank()) ": $message" else ""}")
            }
            val translations = JSONObject(responseText).optJSONArray("translations") ?: error("DeepL 응답에 translations가 없습니다.")
            if (translations.length() != lines.size) error("DeepL 응답 줄 수가 일치하지 않습니다. expected=${lines.size} actual=${translations.length()}")
            return lines.indices.map { index ->
                translations.optJSONObject(index)?.optString("text")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: error("DeepL 번역 결과 ${index + 1}번이 비어 있습니다.")
            }
        }
    }
}
