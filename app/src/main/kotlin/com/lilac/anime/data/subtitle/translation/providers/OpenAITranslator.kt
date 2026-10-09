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

class OpenAITranslator(private val context: Context) : TranslationProvider {
    override val id = "openai"
    override val displayName = "OpenAI"
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).callTimeout(210, TimeUnit.SECONDS).build()

    override suspend fun translateBatch(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val key = SecureApiKeyStore.get(context, id)?.trim()?.takeIf { it.isNotBlank() } ?: error("OpenAI API Key가 없습니다.")
        val available = listModels(key)
        if (available.isEmpty()) error("이 OpenAI API 키로 사용할 수 있는 모델이 없습니다.")
        val configured = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE).getString("pref_openai_model", "").orEmpty().trim()
        val chain = buildList { if (configured in available && configured.isNotBlank()) add(configured); addAll(available.filter { it.contains("mini") }); addAll(available.filter { it.contains("nano") }); addAll(available) }.distinct()
        var last: Throwable? = null
        for (model in chain) {
            try { return request(key, model, lines) } catch (t: Throwable) { last = t; if (!isSwitchable(t)) throw t }
        }
        throw last ?: error("OpenAI 번역에 실패했습니다.")
    }

    private fun request(key: String, model: String, lines: List<String>): List<String> {
        val schema = CloudTranslationPrompt.objectSchema()
        val shapes = listOf(0, 1, 2)
        var last: Throwable? = null
        for (shape in shapes) {
            try {
                val body = JSONObject().put("model", model).put("store", false)
                    .put("instructions", CloudTranslationPrompt.config(context).system).put("input", CloudTranslationPrompt.userMessage(context, lines))
                when (shape) {
                    0 -> {
                        body.put("text", JSONObject().put("format", JSONObject().put("type", "json_schema").put("name", "subtitles").put("strict", true).put("schema", schema)))
                        if (Regex("^(o\\d|gpt-5)", RegexOption.IGNORE_CASE).containsMatchIn(model)) body.put("reasoning", JSONObject().put("effort", "low"))
                    }
                    1 -> body.put("text", JSONObject().put("format", JSONObject().put("type", "json_object")))
                }
                val req = Request.Builder().url("https://api.openai.com/v1/responses").header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                return client.newCall(req).execute().use { response ->
                    val raw = response.body?.string().orEmpty(); if (!response.isSuccessful) throw apiError(response.code, raw)
                    val root = JSONObject(raw); val text = root.optString("output_text").ifBlank { extract(root) }.trim()
                    if (text.isBlank()) error("OpenAI 응답에 번역 텍스트가 없습니다.")
                    CloudTranslationText.parseMarked(text, lines)
                }
            } catch (t: Throwable) {
                last = t
                if (!isBadRequest(t)) throw t
            }
        }
        throw last ?: error("OpenAI 요청에 실패했습니다.")
    }

    private fun extract(root: JSONObject): String = buildString {
        val output = root.optJSONArray("output") ?: return@buildString
        for (i in 0 until output.length()) { val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) { val part = content.optJSONObject(j) ?: continue; if (part.optString("type") == "output_text") append(part.optString("text")) }
        }
    }

    private fun listModels(key: String): List<String> = runCatching {
        val req = Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $key").get().build()
        client.newCall(req).execute().use { response ->
            val raw = response.body?.string().orEmpty(); if (!response.isSuccessful) throw apiError(response.code, raw)
            val arr = JSONObject(raw).optJSONArray("data") ?: JSONArray()
            buildList { for (i in 0 until arr.length()) { val id = arr.optJSONObject(i)?.optString("id").orEmpty(); if (Regex("^(gpt-4|gpt-5|o\\d)").containsMatchIn(id) && !Regex("audio|realtime|search|transcribe|tts", RegexOption.IGNORE_CASE).containsMatchIn(id)) add(id) } }
                .distinct().sortedWith(compareBy<String> { if (it.contains("mini", true)) 0 else if (it.contains("nano", true)) 1 else 2 }.thenBy { it.length })
        }
    }.getOrThrow()

    private fun apiError(code: Int, raw: String): Throwable { val msg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty(); return IllegalStateException("OpenAI HTTP $code${if (msg.isNotBlank()) ": $msg" else ""}") }
    private fun isBadRequest(t: Throwable) = t.message?.contains("HTTP 400") == true
    private fun isSwitchable(t: Throwable) = t.message?.let { it.contains("HTTP 400") || it.contains("HTTP 404") || it.contains("HTTP 429") || it.contains("HTTP 500") || it.contains("HTTP 503") } == true
}
