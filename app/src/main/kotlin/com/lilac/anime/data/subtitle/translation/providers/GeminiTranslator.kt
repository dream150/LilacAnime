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

class GeminiTranslator(private val context: Context) : TranslationProvider {
    override val id = "gemini"
    override val displayName = "Gemini"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    override suspend fun translateBatch(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val key = SecureApiKeyStore.get(context, id)?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Gemini API Key가 없습니다. AI 설정에서 키를 저장하세요.")
        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
        val configured = prefs.getString("pref_gemini_model", "").orEmpty().trim()
        val candidates = buildList {
            if (configured.isNotBlank()) add(configured)
            add("gemini-2.5-flash")
            add("gemini-2.5-flash-lite")
        }.distinct()
        var last: Throwable? = null
        for (model in candidates) {
            try { return request(key, model, lines) }
            catch (t: Throwable) {
                last = t
                if (!isModelError(t)) throw t
            }
        }
        // The desktop port discovers the models actually enabled for the key. Do the
        // same here instead of assuming a model name forever.
        val discovered = listModels(key)
        for (model in discovered) {
            if (model in candidates) continue
            try { return request(key, model, lines) }
            catch (t: Throwable) { last = t; if (!isModelError(t)) throw t }
        }
        throw last ?: IllegalStateException("사용 가능한 Gemini 생성 모델을 찾지 못했습니다.")
    }

    private fun request(key: String, model: String, lines: List<String>): List<String> {
        val input = CloudTranslationText.markedInput(lines)
        val system = """
            Translate Japanese anime subtitles into natural Korean.
            Keep meaning, speaker tone, names, honorifics and subtitle brevity.
            Preserve every <LILAC_N> marker exactly and in order.
            Return only the translated lines with those markers. Never add explanations.
        """.trimIndent()
        val bodyJson = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", input)))))
            put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 8192))
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val request = Request.Builder().url(url).header("x-goog-api-key", key)
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
                error("Gemini HTTP ${response.code}${if (message.isNotBlank()) ": $message" else ""}")
            }
            val root = JSONObject(raw)
            val candidates = root.optJSONArray("candidates") ?: error("Gemini 응답에 candidates가 없습니다.")
            val first = candidates.optJSONObject(0) ?: error("Gemini 응답이 비어 있습니다.")
            val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: error("Gemini 응답에 번역 텍스트가 없습니다.")
            val text = buildString { for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty()) }.trim()
            if (text.isBlank()) error("Gemini 번역 결과가 비어 있습니다. finishReason=${first.optString("finishReason")}")
            return CloudTranslationText.parseMarked(text, lines)
        }
    }

    private fun listModels(key: String): List<String> = runCatching {
        val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
            .header("x-goog-api-key", key).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val arr = JSONObject(response.body?.string().orEmpty()).optJSONArray("models") ?: return@use emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val methods = item.optJSONArray("supportedGenerationMethods") ?: continue
                    var generates = false
                    for (j in 0 until methods.length()) if (methods.optString(j) == "generateContent") generates = true
                    if (!generates) continue
                    val name = item.optString("name").removePrefix("models/")
                    if (name.startsWith("gemini-") && !name.contains("image", true) && !name.contains("audio", true) && !name.contains("live", true) && !name.contains("embedding", true)) add(name)
                }
            }.sortedWith(compareBy<String> { if (it.contains("flash") && !it.contains("lite")) 0 else 1 }.thenBy { it.length })
        }
    }.getOrDefault(emptyList())

    private fun isModelError(t: Throwable): Boolean =
        t.message?.contains("HTTP 400", true) == true || t.message?.contains("HTTP 404", true) == true
}
