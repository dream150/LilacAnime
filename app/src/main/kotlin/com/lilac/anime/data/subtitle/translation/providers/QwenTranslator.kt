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

class QwenTranslator(private val context: Context) : TranslationProvider {
    override val id = "qwen"
    override val displayName = "Qwen"
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()

    override suspend fun translateBatch(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val key = SecureApiKeyStore.get(context, id)?.takeIf { it.isNotBlank() } ?: error("Qwen API Key가 없습니다.")
        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
        val model = prefs.getString("pref_qwen_model", "qwen-plus")?.trim()?.takeIf { it.isNotEmpty() } ?: "qwen-plus"
        val endpoint = if (prefs.getString("pref_qwen_region", "international") == "china") {
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        } else {
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Translate Japanese anime subtitles into natural Korean. Preserve every <LILAC_N> marker exactly and in order. Return only translated lines with markers, no commentary."))
            .put(JSONObject().put("role", "user").put("content", CloudTranslationText.markedInput(lines)))
        val body = JSONObject().put("model", model).put("messages", messages).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key").post(body).build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(responseText).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
                error("Qwen HTTP ${response.code}${if (message.isNotBlank()) ": $message" else ""}")
            }
            val json = JSONObject(responseText)
            val text = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            if (text.isBlank()) error("Qwen 응답에 번역 텍스트가 없습니다.")
            return CloudTranslationText.parseMarked(text, lines)
        }
    }
}
