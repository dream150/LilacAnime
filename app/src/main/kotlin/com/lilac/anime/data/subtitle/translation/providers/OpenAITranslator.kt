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
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()

    override suspend fun translateBatch(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val key = SecureApiKeyStore.get(context, id)?.trim()?.takeIf { it.isNotBlank() } ?: error("OpenAI API Key가 없습니다.")
        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
        val configured = prefs.getString("pref_openai_model", "").orEmpty().trim()
        val models = buildList { if (configured.isNotBlank()) add(configured); add("gpt-4.1-mini"); add("gpt-4o-mini") }.distinct()
        var last: Throwable? = null
        for (model in models) {
            try { return request(key, model, lines, 0) }
            catch (t: Throwable) { last = t; if (!isModelOrShapeError(t)) throw t }
        }
        listModels(key).forEach { model ->
            if (model in models) return@forEach
            try { return request(key, model, lines, 0) } catch (t: Throwable) { last = t; if (!isModelOrShapeError(t)) throw t }
        }
        throw last ?: IllegalStateException("사용 가능한 OpenAI 모델을 찾지 못했습니다.")
    }

    private fun request(key: String, model: String, lines: List<String>, shape: Int): List<String> {
        val instruction = "Translate Japanese anime subtitles into natural Korean. Preserve every <LILAC_N> marker exactly and in order. Keep each line concise. Return only translated lines with markers, no commentary."
        val body = JSONObject().apply {
            put("model", model)
            put("store", false)
            put("instructions", instruction)
            put("input", CloudTranslationText.markedInput(lines))
            if (shape == 1) put("text", JSONObject().put("format", JSONObject().put("type", "json_object")))
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("https://api.openai.com/v1/responses").header("Authorization", "Bearer $key").post(body).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
                if (response.code == 400 && shape == 0) return request(key, model, lines, 1)
                error("OpenAI HTTP ${response.code}${if (msg.isNotBlank()) ": $msg" else ""}")
            }
            val root = JSONObject(raw)
            val text = root.optString("output_text").ifBlank { extractOutputText(root) }.trim()
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

    private fun listModels(key: String): List<String> = runCatching {
        val request = Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $key").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val arr = JSONObject(response.body?.string().orEmpty()).optJSONArray("data") ?: return@use emptyList()
            buildList { for (i in 0 until arr.length()) { val id = arr.optJSONObject(i)?.optString("id").orEmpty(); if (id.matches(Regex("(gpt-4|gpt-5|o[1-9]).*")) && !id.contains("audio") && !id.contains("realtime")) add(id) } }.sortedWith(compareBy<String> { if (it.contains("mini")) 0 else 1 }.thenBy { it.length })
        }
    }.getOrDefault(emptyList())

    private fun isModelOrShapeError(t: Throwable): Boolean = t.message?.let { it.contains("HTTP 400") || it.contains("HTTP 404") } == true
}
