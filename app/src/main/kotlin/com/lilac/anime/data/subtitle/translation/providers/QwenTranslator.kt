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
    private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    override suspend fun translateBatch(lines: List<String>): List<String> {
        val key = SecureApiKeyStore.get(context, id) ?: error("Qwen API Key가 없습니다.")
        val input = lines.mapIndexed { i, s -> "<LILAC_${i + 1}> $s" }.joinToString("\n")
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "Translate Japanese anime subtitles into natural Korean. Preserve each <LILAC_N> marker exactly. Return only translated lines.")).put(JSONObject().put("role", "user").put("content", input))
        val body = JSONObject().apply { put("model", "qwen-plus"); put("messages", messages) }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions").header("Authorization", "Bearer $key").post(body).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("Qwen HTTP ${r.code}")
            val json = JSONObject(r.body?.string().orEmpty())
            val text = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            return lines.indices.map { i -> text.substringAfter("<LILAC_${i + 1}>", "").substringBefore("<LILAC_${i + 2}>").trim().ifBlank { lines[i] } }
        }
    }
}
