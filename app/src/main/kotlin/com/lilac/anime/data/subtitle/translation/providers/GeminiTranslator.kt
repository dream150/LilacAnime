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

        // Match the desktop app: validate the key and discover the models actually
        // enabled for it before attempting translation. This prevents an obsolete
        // saved model (for example gemini-3.5-flash-lite) from masking a valid key.
        val available = listModels(key)
        if (available.isEmpty()) {
            error("이 Gemini API 키로 사용할 수 있는 generateContent 모델이 없습니다.")
        }

        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
        val configured = prefs.getString("pref_gemini_model", "").orEmpty().trim()
        val preferred = buildList {
            if (configured.isNotBlank() && configured in available) add(configured)
            add(defaultModel(available))
            addAll(available.filter { it.contains("flash", true) })
            addAll(available)
        }.filter { it.isNotBlank() }.distinct()

        var last: Throwable? = null
        for (model in preferred) {
            try {
                return request(key, model, lines)
            } catch (t: Throwable) {
                last = t
                // A bad request can be model/config-specific; try another available
                // generation model. Authentication/quota errors are fatal and are
                // returned with the server's actual message below.
                if (!isModelError(t)) throw t
            }
        }
        throw last ?: IllegalStateException("사용 가능한 Gemini 생성 모델로 번역하지 못했습니다.")
    }

    private fun defaultModel(models: List<String>): String =
        models.firstOrNull { it.contains("flash", true) && !it.contains("lite", true) && !it.contains("preview", true) }
            ?: models.firstOrNull { it.contains("flash", true) }
            ?: models.firstOrNull().orEmpty()

    private fun request(key: String, model: String, lines: List<String>): List<String> {
        val input = CloudTranslationText.markedInput(lines)
        val system = """
            Translate Japanese anime subtitles into natural Korean.
            Keep meaning, speaker tone, names, honorifics and subtitle brevity.
            Return one object for every input line, using the original 1-based index.
            Output only JSON. Never add explanations.
        """.trimIndent()

        val itemSchema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("i", JSONObject().put("type", "INTEGER"))
                .put("t", JSONObject().put("type", "STRING")))
            .put("required", JSONArray().put("i").put("t"))
        val responseSchema = JSONObject()
            .put("type", "ARRAY")
            .put("items", itemSchema)

        val thinkingConfig = if (model.startsWith("gemini-2.5")) {
            JSONObject().put("thinkingBudget", if (model.contains("pro", true)) 128 else 0)
        } else {
            JSONObject().put("thinkingLevel", "low")
        }

        // Match the desktop implementation: strict JSON first, then the same
        // schema without thinking controls, then plain JSON mode.
        val configs = listOf(
            JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", responseSchema)
                .put("temperature", 0.3)
                .put("thinkingConfig", thinkingConfig),
            JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", responseSchema)
                .put("temperature", 0.3),
            JSONObject()
                .put("responseMimeType", "application/json")
        )

        var last: Throwable? = null
        for (config in configs) {
            try {
                return requestWithConfig(key, model, lines, system, input, config)
            } catch (t: Throwable) {
                last = t
                if (t.message?.contains("HTTP 400", true) != true) throw t
            }
        }
        throw last ?: IllegalStateException("Gemini 요청에 실패했습니다.")
    }

    private fun requestWithConfig(
        key: String,
        model: String,
        lines: List<String>,
        system: String,
        input: String,
        generationConfig: JSONObject
    ): List<String> {
        val bodyJson = JSONObject().apply {
            put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", system))
            ))
            put("contents", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", input)))
            ))
            put("generationConfig", generationConfig)
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", key)
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                }.getOrDefault("")
                error("Gemini HTTP ${response.code}${if (message.isNotBlank()) ": $message" else ""}")
            }

            val root = JSONObject(raw)
            val candidates = root.optJSONArray("candidates")
                ?: error("Gemini 응답에 candidates가 없습니다.")
            val first = candidates.optJSONObject(0)
                ?: error("Gemini 응답이 비어 있습니다.")
            val parts = first.optJSONObject("content")?.optJSONArray("parts")
                ?: error("Gemini 응답에 번역 텍스트가 없습니다.")
            val text = buildString {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (!part.optBoolean("thought", false)) {
                        append(part.optString("text").orEmpty())
                    }
                }
            }.trim()
            if (text.isBlank()) {
                error(
                    "Gemini 번역 결과가 비어 있습니다. " +
                        "finishReason=${first.optString("finishReason")} " +
                        "blockReason=${root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()}"
                )
            }
            return@use CloudTranslationText.parseMarked(text, lines)
        }
    }

    private fun listModels(key: String): List<String> {
        val result = mutableListOf<String>()
        var pageToken = ""
        do {
            val tokenQuery = if (pageToken.isBlank()) "" else "&pageToken=${java.net.URLEncoder.encode(pageToken, "UTF-8")}"
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000$tokenQuery")
                .header("x-goog-api-key", key)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                    }.getOrDefault("")
                    error("Gemini HTTP ${response.code}${if (message.isNotBlank()) ": $message" else ""}")
                }
                val root = JSONObject(raw)
                val arr = root.optJSONArray("models")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val methods = item.optJSONArray("supportedGenerationMethods") ?: continue
                        var generates = false
                        for (j in 0 until methods.length()) {
                            if (methods.optString(j) == "generateContent") {
                                generates = true
                                break
                            }
                        }
                        if (!generates) continue
                        val name = item.optString("name").removePrefix("models/")
                        if (name.startsWith("gemini-", true) &&
                            !name.contains("image", true) &&
                            !name.contains("audio", true) &&
                            !name.contains("tts", true) &&
                            !name.contains("live", true) &&
                            !name.contains("embedding", true) &&
                            !name.contains("robotics", true) &&
                            !name.contains("computer-use", true) &&
                            !name.contains("native", true)
                        ) result += name
                    }
                }
                pageToken = root.optString("nextPageToken").orEmpty()
            }
        } while (pageToken.isNotBlank())

        val version = { name: String -> Regex("^gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0 }
        return result.distinct().sortedWith(
            compareByDescending<String> { version(it) }
                .thenBy { if (it.contains("flash", true) && !it.contains("lite", true) && !it.contains("preview", true)) 0 else 1 }
                .thenBy { it.length }
                .thenBy { it }
        )
    }

    private fun isModelError(t: Throwable): Boolean =
        t.message?.contains("HTTP 400", true) == true || t.message?.contains("HTTP 404", true) == true
}
