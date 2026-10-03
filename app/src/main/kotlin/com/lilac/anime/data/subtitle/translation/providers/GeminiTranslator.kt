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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GeminiTranslator(private val context: Context) : TranslationProvider {
    override val id = "gemini"
    override val displayName = "Gemini"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    override suspend fun translateBatch(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val key = SecureApiKeyStore.get(context, id)?.takeIf { it.isNotBlank() }
            ?: error("Gemini API Key가 없습니다. AI 설정에서 키를 저장하세요.")
        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
        val model = prefs.getString("pref_gemini_model", "gemini-3.5-flash-lite")
            ?.trim()?.takeIf { it.isNotEmpty() } ?: "gemini-3.5-flash-lite"
        require(model.matches(Regex("[A-Za-z0-9._-]+"))) { "Gemini 모델 이름이 올바르지 않습니다." }
        val input = CloudTranslationText.markedInput(lines)
        val system = "Translate Japanese anime subtitles into natural Korean. Keep the meaning, tone, names, and subtitle brevity. Preserve every <LILAC_N> marker exactly and in order. Return only the translated lines with their markers. Do not add explanations."
        val bodyJson = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", input)))))
            put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 4096))
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:generateContent"
        val request = Request.Builder().url(url).header("x-goog-api-key", key)
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    val error = JSONObject(responseText).optJSONObject("error")
                    error?.optString("message").orEmpty()
                }.getOrDefault("")
                error("Gemini HTTP ${response.code}${if (message.isNotBlank()) ": $message" else ""}")
            }
            val json = JSONObject(responseText)
            val candidates = json.optJSONArray("candidates") ?: error("Gemini 응답에 candidates가 없습니다.")
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: error("Gemini 응답에 번역 텍스트가 없습니다.")
            val text = buildString {
                for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }.trim()
            if (text.isBlank()) error("Gemini 번역 결과가 비어 있습니다. finishReason=${candidates.optJSONObject(0)?.optString("finishReason")}")
            return CloudTranslationText.parseMarked(text, lines)
        }
    }
}
