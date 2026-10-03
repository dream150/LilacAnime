package com.lilac.anime.data.subtitle.translation.providers

import android.content.Context
import com.lilac.anime.data.subtitle.translation.SecureApiKeyStore
import com.lilac.anime.data.subtitle.translation.TranslationProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenAITranslator(private val context: Context) : TranslationProvider {
    override val id = "openai"
    override val displayName = "OpenAI"
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()

    override suspend fun translateBatch(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val key = SecureApiKeyStore.get(context, id)?.takeIf { it.isNotBlank() } ?: error("OpenAI API Key가 없습니다.")
        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
        val model = prefs.getString("pref_openai_model", "gpt-4.1-mini")?.trim()?.takeIf { it.isNotEmpty() } ?: "gpt-4.1-mini"
        val body = JSONObject().apply {
            put("model", model)
            put("store", false)
            put("instructions", "Translate Japanese anime subtitles into natural Korean. Keep each line concise. Preserve every <LILAC_N> marker exactly and in order. Return only translated lines with markers, no commentary.")
            put("input", CloudTranslationText.markedInput(lines))
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("https://api.openai.com/v1/responses").header("Authorization", "Bearer $key").post(body).build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(responseText).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
                error("OpenAI HTTP ${response.code}${if (message.isNotBlank()) ": $message" else ""}")
            }
            val json = JSONObject(responseText)
            val text = json.optString("output_text").ifBlank { extractOutputText(json) }
            if (text.isBlank()) error("OpenAI 응답에 번역 텍스트가 없습니다.")
            return CloudTranslationText.parseMarked(text, lines)
        }
    }

    private fun extractOutputText(json: JSONObject): String = buildString {
        val output = json.optJSONArray("output") ?: return@buildString
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") append(part.optString("text"))
            }
        }
    }
}
