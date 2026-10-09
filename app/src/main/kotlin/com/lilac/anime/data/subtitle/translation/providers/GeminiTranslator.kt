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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class GeminiTranslator(private val context: Context) : TranslationProvider {
    override val id = "gemini"
    override val displayName = "Gemini"
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).callTimeout(210, TimeUnit.SECONDS).build()

    /** Desktop-compatible key test: only list models; never sends a translation prompt. */
    suspend fun testConnection(): String {
        val key = SecureApiKeyStore.get(context, id)?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Gemini API Key가 없습니다. AI 설정에서 키를 저장하세요.")
        return try {
            val models = listModels(key)
            if (models.isEmpty()) error("이 Gemini API 키로 사용할 수 있는 generateContent 모델이 없습니다.")
            val selected = defaultModel(models)
            android.util.Log.i("GeminiTranslator", "KEY_TEST_OK models=${models.size} default=$selected")
            "연결 성공: $selected (사용 가능 모델 ${models.size}개)"
        } catch (t: Throwable) {
            android.util.Log.e("GeminiTranslator", "KEY_TEST_FAILED type=${t::class.java.name} message=${t.message}", t)
            throw t
        }
    }

    override suspend fun translateBatch(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val key = SecureApiKeyStore.get(context, id)?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Gemini API Key가 없습니다. AI 설정에서 키를 저장하세요.")
        val models = listModels(key)
        if (models.isEmpty()) error("이 Gemini API 키로 사용할 수 있는 generateContent 모델이 없습니다.")
        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
        val configured = prefs.getString("pref_gemini_model", "").orEmpty().trim()
        val chain = buildList {
            if (configured in models && configured.isNotBlank()) add(configured)
            add(defaultModel(models)); addAll(models.filter { it.contains("flash", true) }); addAll(models)
        }.filter { it.isNotBlank() }.distinct()
        var last: Throwable? = null
        for (model in chain) {
            try { return generate(key, model, lines) }
            catch (t: Throwable) { last = t; if (!isSwitchable(t)) throw t }
        }
        throw last ?: error("Gemini 번역에 실패했습니다.")
    }

    private suspend fun generate(key: String, model: String, lines: List<String>): List<String> = withContext(Dispatchers.IO) {
        val schema = JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("i", JSONObject().put("type", "INTEGER")).put("t", JSONObject().put("type", "STRING"))).put("required", JSONArray().put("i").put("t")))
        val thinking = if (model.startsWith("gemini-2.5")) JSONObject().put("thinkingBudget", if (model.contains("pro", true)) 128 else 0) else JSONObject().put("thinkingLevel", "low")
        val configs = listOf(
            JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema).put("temperature", .3).put("thinkingConfig", thinking),
            JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema).put("temperature", .3),
            JSONObject().put("responseMimeType", "application/json")
        )
        var last: Throwable? = null
        for (config in configs) {
            try {
                val body = JSONObject()
                    .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", CloudTranslationPrompt.config(context).system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", CloudTranslationPrompt.userMessage(context, lines))))))
                    .put("generationConfig", config)
                val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:generateContent")
                    .header("x-goog-api-key", key).post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                android.util.Log.d("GeminiTranslator", "GENERATE_REQUEST model=$model configIndex=${configs.indexOf(config)} lines=${lines.size}")
                return@withContext client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    android.util.Log.d("GeminiTranslator", "GENERATE_RESPONSE model=$model code=${response.code} bytes=${raw.length}")
                    if (!response.isSuccessful) throw apiError("Gemini", response.code, raw)
                    val root = JSONObject(raw); val first = root.optJSONArray("candidates")?.optJSONObject(0) ?: error("Gemini 응답이 비어 있습니다.")
                    val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: error("Gemini 응답에 번역 텍스트가 없습니다.")
                    val text = buildString { for (i in 0 until parts.length()) { val p = parts.optJSONObject(i) ?: continue; if (!p.optBoolean("thought", false)) append(p.optString("text")) } }.trim()
                    if (text.isBlank()) error("Gemini 번역 결과가 비어 있습니다. finishReason=${first.optString("finishReason")}")
                    CloudTranslationText.parseMarked(text, lines)
                }
            } catch (t: Throwable) {
                last = t
                if (!isBadRequest(t)) throw t
            }
        }
        throw last ?: error("Gemini 요청에 실패했습니다.")
    }

    private suspend fun listModels(key: String): List<String> = withContext(Dispatchers.IO) {
        val result = mutableListOf<String>(); var token = ""
        do {
            val suffix = if (token.isBlank()) "" else "&pageToken=${URLEncoder.encode(token, "UTF-8")}"
            val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000$suffix").header("x-goog-api-key", key).get().build()
            android.util.Log.d("GeminiTranslator", "MODELS_REQUEST url=${request.url}")
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                android.util.Log.d("GeminiTranslator", "MODELS_RESPONSE code=${response.code} bytes=${raw.length}")
                if (!response.isSuccessful) throw apiError("Gemini", response.code, raw)
                val root = JSONObject(raw); val arr = root.optJSONArray("models") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val name = (m.optString("baseModelId").ifBlank { m.optString("name") }).removePrefix("models/").trim()
                    if (!name.startsWith("gemini-", ignoreCase = true)) continue

                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var supportsGenerate = methods == null
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.optString(j).equals("generateContent", ignoreCase = true)) {
                                supportsGenerate = true
                                break
                            }
                        }
                    }

                    android.util.Log.d(
                        "GeminiTranslator",
                        "MODEL_CANDIDATE name=$name generateContent=$supportsGenerate methods=${methods?.toString() ?: "<missing>"}"
                    )

                    if (supportsGenerate &&
                        !Regex("image|tts|audio|live|embedding|robotics|computer-use|native-audio|exp", RegexOption.IGNORE_CASE).containsMatchIn(name)
                    ) {
                        result += name
                    }
                }
                token = root.optString("nextPageToken", "")
            }
        } while (token.isNotBlank())
        return@withContext result.distinct().sortedWith(compareByDescending<String> { Regex("""^gemini-(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0 }.thenBy { it.length }.thenBy { it })
    }

    private fun defaultModel(models: List<String>) = models.firstOrNull { it.contains("flash", true) && !it.contains("lite|preview".toRegex(RegexOption.IGNORE_CASE)) }
        ?: models.firstOrNull { it.contains("flash", true) } ?: models.first()
    private fun isBadRequest(t: Throwable) = t.message?.contains("HTTP 400") == true
    private fun isSwitchable(t: Throwable) = t.message?.let { it.contains("HTTP 400") || it.contains("HTTP 404") || it.contains("HTTP 429") || it.contains("HTTP 503") } == true
    private fun apiError(name: String, code: Int, raw: String): Throwable { val msg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty(); return IllegalStateException("$name HTTP $code${if (msg.isNotBlank()) ": $msg" else ""}") }
}
