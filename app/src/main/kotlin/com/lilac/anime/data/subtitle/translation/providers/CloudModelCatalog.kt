package com.lilac.anime.data.subtitle.translation.providers

import android.content.Context
import com.lilac.anime.data.subtitle.translation.SecureApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Fetches models visible to the user's own API key. Results are never hard-coded as availability claims. */
object CloudModelCatalog {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()

    suspend fun fetch(context: Context, provider: String, qwenRegion: String = "international"): List<String> = withContext(Dispatchers.IO) {
        val key = SecureApiKeyStore.get(context, provider)?.trim()?.takeIf { it.isNotBlank() }
            ?: error("먼저 $provider API Key를 저장하세요.")
        val url = when (provider) {
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta/models?key=${java.net.URLEncoder.encode(key, "UTF-8")}&pageSize=1000"
            "openai" -> "https://api.openai.com/v1/models"
            "qwen" -> (if (qwenRegion == "china") "https://dashscope.aliyuncs.com" else "https://dashscope-intl.aliyuncs.com") + "/compatible-mode/v1/models"
            else -> error("모델 목록을 지원하지 않는 제공자입니다: $provider")
        }
        val builder = Request.Builder().url(url).get()
        if (provider != "gemini") builder.header("Authorization", if (provider == "qwen") "Bearer $key" else "Bearer $key")
        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
                error("모델 목록 조회 실패 (HTTP ${response.code})${if (message.isNotBlank()) ": $message" else ""}")
            }
            val root = JSONObject(raw)
            val arr = root.optJSONArray(if (provider == "gemini") "models" else "data") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = if (provider == "gemini") item.optString("name").removePrefix("models/") else item.optString("id")
                    if (id.isBlank()) continue
                    when (provider) {
                        "gemini" -> if ((item.optJSONArray("supportedGenerationMethods")?.let { methods -> (0 until methods.length()).any { methods.optString(it) == "generateContent" } } == true) && !id.contains("embedding", true)) add(id)
                        "openai" -> if (Regex("^(gpt-|o[0-9])", RegexOption.IGNORE_CASE).containsMatchIn(id) && !Regex("audio|realtime|search|transcribe|tts|embedding", RegexOption.IGNORE_CASE).containsMatchIn(id)) add(id)
                        "qwen" -> if (id.contains("qwen", true) && !Regex("audio|vl|coder", RegexOption.IGNORE_CASE).containsMatchIn(id)) add(id)
                    }
                }
            }.distinct().sortedWith(compareBy<String> { when { it.contains("flash", true) || it.contains("mini", true) || it.contains("plus", true) -> 0; it.contains("pro", true) -> 1; else -> 2 } }.thenBy { it })
        }
    }
}
