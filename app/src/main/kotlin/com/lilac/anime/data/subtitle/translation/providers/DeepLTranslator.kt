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

class DeepLTranslator(private val context: Context) : TranslationProvider {
    override val id = "deepl"
    override val displayName = "DeepL"
    private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    override suspend fun translateBatch(lines: List<String>): List<String> {
        val key = SecureApiKeyStore.get(context, id) ?: error("DeepL API Key가 없습니다.")
        val input = lines.mapIndexed { i, s -> "<LILAC_${i + 1}> $s" }.joinToString("\n")
        val body = JSONObject().apply { put("text", JSONArray().put(input)); put("source_lang", "JA"); put("target_lang", "KO") }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://api.deepl.com/v2/translate").header("Authorization", "DeepL-Auth-Key $key").post(body).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("DeepL HTTP ${r.code}")
            val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("translations") ?: error("DeepL 응답 오류")
            val text = arr.optJSONObject(0)?.optString("text").orEmpty()
            return lines.indices.map { i -> text.substringAfter("<LILAC_${i + 1}>", "").substringBefore("<LILAC_${i + 2}>").trim().ifBlank { lines[i] } }
        }
    }
}
