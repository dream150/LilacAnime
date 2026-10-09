package com.lilac.anime.data.subtitle.translation.providers

import org.json.JSONArray
import org.json.JSONObject
import android.content.Context

/** Translation contract ported from the desktop subtitle-translator. */
internal object CloudTranslationPrompt {
    val SYSTEM = """
You are an experienced Korean anime subtitle translator working to the standard of a good Korean fansub team.
Translate Japanese or English anime subtitles into natural spoken Korean.
Read the lines as a scene and keep speaker relationships and character voice consistent.
Preserve meaning faithfully. Do not add explanations, censor, invent, merge, split, skip, or reorder lines.
Keep each subtitle concise and natural. Avoid translationese such as unnecessary 그녀/그/당신 or literal idioms.
Friends, family and classmates normally use 반말; strangers and superiors normally use 존댓말. Keep each character's speech style consistent.
Use established Korean anime spellings for names. Translate common honorifics naturally: 先輩→선배, 先生→선생님, お兄ちゃん/兄さん according to the speaker.
Keep stammers, interruptions, sound effects, music marks and bracketed caption labels where appropriate.
Return exactly one translated item for every input item, with the same numeric index.
Output only the requested JSON structure and nothing else.
""".trimIndent()


    data class Config(val system: String, val userInstruction: String)

    fun config(context: Context): Config {
        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
        val system = prefs.getString("pref_ai_system_prompt", null)?.trim()
            ?.takeIf { it.isNotBlank() } ?: SYSTEM
        val user = prefs.getString("pref_translation_prompt", null)?.trim()
            ?.takeIf { it.isNotBlank() } ?: "주어진 자막을 자연스러운 한국어로 번역해. 입력한 순서와 줄 수를 그대로 유지해."
        return Config(system = system, userInstruction = user)
    }

    fun userMessage(context: Context, lines: List<String>): String {
        val user = config(context).userInstruction
        val marked = markedInput(lines)
        return user
            .replace("{context}", "")
            .replace("{future_context}", "")
            .replace("{source_text}", marked)
            .trim()
            .let { if (it.contains("<LILAC_")) it else "$it\n\n$marked" }
    }

    fun markedInput(lines: List<String>): String = lines.mapIndexed { i, line ->
        "<LILAC_${i + 1}> $line"
    }.joinToString("\n")

    fun objectSchema(): JSONObject {
        val item = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("i", JSONObject().put("type", "integer"))
                .put("t", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("i").put("t"))
            .put("additionalProperties", false)
        return JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("lines", JSONObject()
                .put("type", "array").put("items", item)))
            .put("required", JSONArray().put("lines"))
            .put("additionalProperties", false)
    }
}
