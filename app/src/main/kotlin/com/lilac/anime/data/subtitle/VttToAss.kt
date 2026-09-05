package com.lilac.anime.data.subtitle

import android.content.Context
import java.io.File
import java.util.Locale

/** Converts WebVTT cues to a simple libass track so mpv can render Linkkf VTT reliably. */
fun prepareVttAsAssFile(context: Context, vttPath: String, animeId: String, episodeKey: String): String? {
    val source = File(vttPath.removePrefix("file://"))
    if (!source.isFile) return null
    return runCatching {
        val lines = source.readLines(Charsets.UTF_8).map { it.removePrefix("\uFEFF") }
        val out = StringBuilder()
        out.append("[Script Info]\n")
        out.append("ScriptType: v4.00+\n")
        out.append("PlayResX: 1920\n")
        out.append("PlayResY: 1080\n\n")
        out.append("[V4+ Styles]\n")
        out.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        out.append("Style: Default,Arial,54,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,3,0,2,60,60,45,1\n\n")
        out.append("[Events]\n")
        out.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")

        var i = 0
        var count = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isBlank() || line.equals("WEBVTT", true) || line.startsWith("NOTE")) { i++; continue }
            val timingIndex = if (line.contains(" --> ")) i else if (i + 1 < lines.size && lines[i + 1].contains(" --> ")) i + 1 else -1
            if (timingIndex < 0) { i++; continue }
            val timing = lines[timingIndex]
            val parts = timing.split(" --> ", limit = 2)
            if (parts.size != 2) { i++; continue }
            val start = assTime(parts[0].trim())
            if (start == null) {
                i = timingIndex + 1
                continue
            }
            val end = assTime(parts[1].trim().substringBefore(' '))
            if (end == null) {
                i = timingIndex + 1
                continue
            }
            val text = buildString {
                var j = timingIndex + 1
                while (j < lines.size && lines[j].isNotBlank()) {
                    if (isNotCueMetadata(lines[j])) {
                        if (isNotEmpty()) append("\\N")
                        append(lines[j].trim())
                    }
                    j++
                }
            }.trim()
            if (text.isNotBlank()) {
                out.append("Dialogue: 0,$start,$end,Default,,0,0,0,,${toAssText(text)}\n")
                count++
            }
            i = timingIndex + 1
        }
        if (count == 0) return@runCatching null
        val safe = episodeKey.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val target = File(context.filesDir, "sub_${animeId}_${safe}_linkkf.ass")
        target.writeText(out.toString(), Charsets.UTF_8)
        target.absolutePath
    }.getOrNull()
}

private fun isNotCueMetadata(line: String): Boolean =
    !line.trimStart().startsWith("STYLE", true) && !line.trimStart().startsWith("REGION", true)

private fun toAssText(raw: String): String = raw
    .replace("\\", "\\\\")
    .replace("<i>", "{\\i1}", true).replace("</i>", "{\\i0}", true)
    .replace("<b>", "{\\b1}", true).replace("</b>", "{\\b0}", true)
    .replace("<u>", "{\\u1}", true).replace("</u>", "{\\u0}", true)
    .replace(Regex("<[^>]+>"), "")

private fun assTime(value: String): String? {
    val p = value.replace(',', '.').split(':')
    val h: Long; val m: Long; val sec: Double
    try {
        when (p.size) {
            3 -> { h = p[0].toLong(); m = p[1].toLong(); sec = p[2].toDouble() }
            2 -> { h = 0; m = p[0].toLong(); sec = p[1].toDouble() }
            else -> return null
        }
    } catch (_: Exception) { return null }
    val total = h * 3600.0 + m * 60.0 + sec
    val cs = kotlin.math.round(total * 100.0).toLong().coerceAtLeast(0L)
    val hh = cs / 360000L
    val mm = (cs / 6000L) % 60L
    val ss = (cs / 100L) % 60L
    val cc = cs % 100L
    return String.format(Locale.US, "%d:%02d:%02d.%02d", hh, mm, ss, cc)
}
