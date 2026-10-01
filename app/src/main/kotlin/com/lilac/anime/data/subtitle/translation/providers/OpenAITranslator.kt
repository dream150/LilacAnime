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
    private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    override suspend fun translateBatch(lines: List<String>): List<String> {
        val key = SecureApiKeyStore.get(context, id) ?: error("OpenAI API Key가 없습니다.")
        val input = lines.mapIndexed { i, s -> "<LILAC_${i + 1}> $s" }.joinToString("\n")
        val body = JSONObject().apply {
            put("model", "gpt-5.6-luna")
            put("store", false)
            put("instructions", "Translate Japanese anime subtitles into natural Korean. Preserve each <LILAC_N> marker exactly. Return only translated lines with markers, one per line. Do not add commentary.")
            put("input", input)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://api.openai.com/v1/responses").header("Authorization", "Bearer $key").post(body).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("OpenAI HTTP ${r.code}")
            val json = JSONObject(r.body?.string().orEmpty())
            val text = json.optString("output_text").ifBlank { extractOutputText(json) }
            return parseMarked(text, lines)
        }
    }
    private fun extractOutputText(json: JSONObject): String = buildString {
        val output = json.optJSONArray("output") ?: return@buildString
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") append(part.optString("text"))
            }
        }
    }
    private fun parseMarked(text: String, original: List<String>): List<String> = original.indices.map { i ->
        val marker = "<LILAC_${i + 1}>"
        text.substringAfter(marker, "").substringBefore("<LILAC_${i + 2}>").trim().ifBlank { original[i] }
    }
}
