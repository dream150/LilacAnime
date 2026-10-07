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
        val instruction = """
            Translate Japanese anime subtitles into natural Korean.
            Keep the meaning, speaker tone, names and honorifics natural.
            Keep each subtitle concise.
            Return exactly one translated item for every input item.
            Do not add explanations.
        """.trimIndent()

        val itemSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("i", JSONObject().put("type", "integer"))
                    .put("t", JSONObject().put("type", "string"))
            )
            .put("required", org.json.JSONArray().put("i").put("t"))
            .put("additionalProperties", false)

        val linesSchema = JSONObject()
            .put("type", "array")
            .put("items", itemSchema)

        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("lines", linesSchema))
            .put("required", org.json.JSONArray().put("lines"))
            .put("additionalProperties", false)

        val bodyJson = JSONObject()
            .put("model", model)
            .put("store", false)
            .put("instructions", instruction)
            .put("input", CloudTranslationText.markedInput(lines))

        when (shape) {
            0 -> {
                bodyJson.put(
                    "text",
                    JSONObject().put(
                        "format",
                        JSONObject()
                            .put("type", "json_schema")
                            .put("name", "subtitles")
                            .put("strict", true)
                            .put("schema", schema)
                    )
                )
                if (model.matches(Regex("^(o\\d|gpt-5).*", RegexOption.IGNORE_CASE))) {
                    bodyJson.put("reasoning", JSONObject().put("effort", "low"))
                }
            }
            1 -> bodyJson.put("text", JSONObject().put("format", JSONObject().put("type", "json_object")))
            // shape 2 deliberately has no response-format restriction, matching the
            // desktop fallback for models that reject structured output.
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $key")
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                }.getOrDefault("")
                if (response.code == 400 && shape < 2) {
                    return request(key, model, lines, shape + 1)
                }
                error("OpenAI HTTP ${response.code}${if (msg.isNotBlank()) ": $msg" else ""}")
            }

            val root = JSONObject(raw)
            val text = root.optString("output_text").ifBlank { extractOutputText(root) }.trim()
            if (text.isBlank()) {
                error("OpenAI 응답에 번역 텍스트가 없습니다.")
            }
            return@use CloudTranslationText.parseMarked(text, lines)
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
