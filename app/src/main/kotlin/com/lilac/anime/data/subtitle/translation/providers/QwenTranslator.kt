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
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).callTimeout(210, TimeUnit.SECONDS).build()

    override suspend fun translateBatch(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val key = SecureApiKeyStore.get(context, id)?.trim()?.takeIf { it.isNotBlank() } ?: error("Qwen API Key가 없습니다.")
        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
        val region = if (prefs.getString("pref_qwen_region", "international") == "china") "https://dashscope.aliyuncs.com" else "https://dashscope-intl.aliyuncs.com"
        val available = listModels(key, region)
        val configured = prefs.getString("pref_qwen_model", "").orEmpty().trim()
        val models = if (available.isEmpty()) listOf(configured.ifBlank { "qwen-plus" }) else buildList { if (configured in available && configured.isNotBlank()) add(configured); addAll(available.filter { it.contains("plus", true) }); addAll(available.filter { it.contains("flash", true) }); addAll(available); }.distinct()
        var last: Throwable? = null
        for (model in models) { try { return request(key, region, model, lines) } catch (t: Throwable) { last = t; if (!isSwitchable(t)) throw t } }
        throw last ?: error("Qwen 번역에 실패했습니다.")
    }

    private fun request(key: String, region: String, model: String, lines: List<String>): List<String> {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", CloudTranslationPrompt.config(context).system))
            .put(JSONObject().put("role", "user").put("content", CloudTranslationPrompt.userMessage(context, lines)))
        val body = JSONObject().put("model", model).put("messages", messages).put("temperature", .3)
            .put("response_format", JSONObject().put("type", "json_object")).put("enable_thinking", false)
        val req = Request.Builder().url("$region/compatible-mode/v1/chat/completions").header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return client.newCall(req).execute().use { response ->
            val raw = response.body?.string().orEmpty(); if (!response.isSuccessful) throw apiError(response.code, raw)
            val text = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
            if (text.isBlank()) error("Qwen 응답에 번역 텍스트가 없습니다.")
            CloudTranslationText.parseMarked(text, lines)
        }
    }

    private fun listModels(key: String, region: String): List<String> = runCatching {
        val req = Request.Builder().url("$region/compatible-mode/v1/models").header("Authorization", "Bearer $key").get().build()
        client.newCall(req).execute().use { response ->
            val raw = response.body?.string().orEmpty(); if (!response.isSuccessful) return@use emptyList()
            val arr = JSONObject(raw).optJSONArray("data") ?: JSONArray()
            buildList { for (i in 0 until arr.length()) { val id = arr.optJSONObject(i)?.optString("id").orEmpty(); if (id.contains("qwen", true) && !Regex("audio|vl|coder", RegexOption.IGNORE_CASE).containsMatchIn(id)) add(id) } }.distinct()
        }
    }.getOrDefault(emptyList())

    private fun apiError(code: Int, raw: String): Throwable { val msg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty(); return IllegalStateException("Qwen HTTP $code${if (msg.isNotBlank()) ": $msg" else ""}") }
    private fun isSwitchable(t: Throwable) = t.message?.let { it.contains("HTTP 400") || it.contains("HTTP 404") || it.contains("HTTP 429") || it.contains("HTTP 500") || it.contains("HTTP 503") } == true
}
