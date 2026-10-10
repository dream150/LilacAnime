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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeepLTranslator(private val context: Context) : TranslationProvider {
    override val id = "deepl"
    override val displayName = "DeepL"
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(110, TimeUnit.SECONDS).build()

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val key = SecureApiKeyStore.get(context, id)?.trim()?.takeIf { it.isNotBlank() }
            ?: error("DeepL API Key가 없습니다. AI 설정에서 키를 저장하세요.")
        val endpoint = if (key.endsWith(":fx", true)) "https://api-free.deepl.com/v2/usage" else "https://api.deepl.com/v2/usage"
        val req = Request.Builder().url(endpoint).header("Authorization", "DeepL-Auth-Key $key").get().build()
        client.newCall(req).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(response.code, raw)
            val root = JSONObject(raw)
            val used = root.optLong("character_count", -1L)
            val limit = root.optLong("character_limit", -1L)
            if (used >= 0L && limit >= 0L) "연결 성공: DeepL API 인증 확인 (사용량 $used / $limit 문자)"
            else "연결 성공: DeepL API 인증 확인"
        }
    }

    override suspend fun translateBatch(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val key = SecureApiKeyStore.get(context, id)?.trim()?.takeIf { it.isNotBlank() } ?: error("DeepL API Key가 없습니다.")
        val endpoint = if (key.endsWith(":fx", true)) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
        val body = JSONObject().put("text", JSONArray().apply { lines.forEach { put(it) } })
            .put("target_lang", "KO").put("preserve_formatting", true)
            .put("context", CloudTranslationPrompt.config(context).userInstruction).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(endpoint).header("Authorization", "DeepL-Auth-Key $key").post(body).build()
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                return client.newCall(req).execute().use { response ->
                    val raw = response.body?.string().orEmpty(); if (!response.isSuccessful) throw apiError(response.code, raw)
                    val arr = JSONObject(raw).optJSONArray("translations") ?: error("DeepL 응답에 translations가 없습니다.")
                    if (arr.length() != lines.size) error("DeepL 응답 줄 수가 일치하지 않습니다. expected=${lines.size} actual=${arr.length()}")
                    lines.indices.map { i -> arr.optJSONObject(i)?.optString("text")?.trim()?.takeIf { it.isNotBlank() } ?: error("DeepL 번역 결과 ${i + 1}번이 비어 있습니다.") }
                }
            } catch (t: Throwable) { last = t; if (attempt < 2 && (t.message?.contains("HTTP 429") == true || t.message?.contains("HTTP 5") == true)) Thread.sleep((1000L shl attempt).coerceAtMost(4000L)) else if (attempt == 2) throw t }
        }
        throw last ?: error("DeepL 번역에 실패했습니다.")
    }
    private fun apiError(code: Int, raw: String): Throwable { val msg = runCatching { val root = JSONObject(raw); root.optString("message").ifBlank { root.optJSONObject("error")?.optString("message").orEmpty() } }.getOrNull().orEmpty(); return IllegalStateException("DeepL HTTP $code${if (msg.isNotBlank()) ": $msg" else ""}") }
}
