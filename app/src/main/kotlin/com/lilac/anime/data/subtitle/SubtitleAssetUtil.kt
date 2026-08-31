package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import com.lilac.anime.KairanSubtitleService
import java.io.File
import java.util.Locale

/** Shared handling for multiple ASS/SSA assets and discovered subtitle fonts. */
object SubtitleAssetUtil {
    data class FontInfo(val path: String, val displayName: String, val source: String)

    data class AssCandidate(val path: String, val source: String, val priority: Int = 0)

    private data class Dialogue(val start: Long, val end: Long, val line: String)

    fun listFonts(context: Context, title: String, source: String): List<FontInfo> {
        val dirs = linkedSetOf<File>()
        when (source.lowercase(Locale.ROOT)) {
            "kairan" -> {
                val safe = KairanSubtitleService.normalizeTitleForFile(title).replace(' ', '_').ifBlank { "subtitle" }.take(60)
                dirs += File(context.filesDir, "kairan_subtitles/fonts/$safe")
                dirs += File(context.filesDir, "kairan_subtitles/fonts/${KairanSubtitleService.normalizeTitleForFile(title)}")
            }
            "csora" -> dirs += File(context.filesDir, "csora_subtitles/${KairanSubtitleService.normalizeTitleForFile(title)}/fonts")
        }
        return dirs.flatMap { dir ->
            if (!dir.isDirectory) emptyList() else dir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("ttf", "otf", "ttc") }
                .map { file -> FontInfo(file.absolutePath, file.nameWithoutExtension, source) }
                .toList()
        }.distinctBy { it.path }
    }

    /**
     * If ASS timelines are disjoint, merge their Dialogue events into one ASS.
     * If any timelines overlap, choose the candidate with the greatest covered
     * duration (dialogue count is the tie breaker). This avoids duplicate text.
     */
    fun resolveAssCandidates(context: Context, title: String, episode: Int, candidates: List<AssCandidate>): String? {
        val valid = candidates.mapNotNull { c ->
            val file = File(c.path)
            if (!file.isFile || !isAss(file)) null else c to parseDialogues(file)
        }
        if (valid.isEmpty()) return null
        if (valid.size == 1) return valid.first().first.path

        val allDialogues = valid.flatMap { it.second }
        val overlap = hasCrossCandidateOverlap(valid)
        if (overlap) {
            val chosen = valid.maxWithOrNull(compareBy<Pair<AssCandidate, List<Dialogue>>> { coveredDuration(it.second) }
                .thenBy { it.second.size }
                .thenBy { it.first.priority })!!.first
            valid.filter { it.first.path != chosen.path }.forEach { File(it.first.path).delete() }
            Log.d("Subtitle", "ASS_OVERLAP choose=${chosen.path} candidates=${valid.size} reason=longest_timeline")
            return chosen.path
        }

        val base = valid.maxByOrNull { it.first.priority }!!.first
        val baseFile = File(base.path)
        val merged = File(baseFile.parentFile, "${baseFile.nameWithoutExtension}_merged_e$episode.ass")
        mergeAssFiles(baseFile, valid.filter { it.first.path != base.path }.map { File(it.first.path) }, merged)
        valid.filter { it.first.path != merged.absolutePath && it.first.path != base.path }.forEach { File(it.first.path).delete() }
        Log.d("Subtitle", "ASS_MERGED count=${valid.size} path=${merged.absolutePath} dialogueCount=${allDialogues.size}")
        return merged.absolutePath
    }


    /** Creates a temporary ASS whose style Fontname fields point at the selected memory font. */
    fun prepareAssWithSelectedFont(subtitlePath: String, selectedFontPath: String?): String {
        if (selectedFontPath.isNullOrBlank()) return subtitlePath
        val subtitle = File(subtitlePath)
        val font = File(selectedFontPath)
        if (!subtitle.isFile || !font.isFile || !isAss(subtitle)) return subtitlePath
        val fontName = font.name
        val text = try { subtitle.readText(Charsets.UTF_8) } catch (_: Exception) { return subtitlePath }
        val out = File(subtitle.parentFile, "${subtitle.nameWithoutExtension}_font_${font.nameWithoutExtension.hashCode().toUInt().toString(16)}.ass")
        try {
            var inStyles = false
            var styleFormat: List<String>? = null
            val lines = text.lines().map { line ->
                val trimmed = line.trim()
                if (trimmed.equals("[V4+ Styles]", true) || trimmed.equals("[V4 Styles]", true)) {
                    inStyles = true
                    styleFormat = null
                    return@map line
                }
                if (trimmed.startsWith("[")) inStyles = false
                if (inStyles && trimmed.startsWith("Format:", true)) {
                    styleFormat = trimmed.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                    return@map line
                }
                if (inStyles && trimmed.startsWith("Style:", true) && styleFormat != null) {
                    val fields = trimmed.substringAfter(':').split(',').toMutableList()
                    val idx = styleFormat!!.indexOf("fontname")
                    if (idx >= 0 && idx < fields.size) {
                        fields[idx] = fontName
                        val prefix = line.takeWhile { it.isWhitespace() }
                        return@map prefix + "Style: " + fields.joinToString(",")
                    }
                }
                line
            }
            out.writeText(lines.joinToString("\n"), Charsets.UTF_8)
            Log.d("Subtitle", "ASS_FONT_OVERRIDE selected=${font.name} path=${out.absolutePath}")
            return out.absolutePath
        } catch (e: Exception) {
            Log.w("Subtitle", "ASS_FONT_OVERRIDE_FAILED font=${font.absolutePath}", e)
            return subtitlePath
        }
    }
    private fun hasCrossCandidateOverlap(items: List<Pair<AssCandidate, List<Dialogue>>>): Boolean {
        for (i in items.indices) for (j in i + 1 until items.size) {
            if (items[i].second.any { a -> items[j].second.any { b -> a.start < b.end && b.start < a.end } }) return true
        }
        return false
    }

    private fun coveredDuration(dialogues: List<Dialogue>): Long {
        if (dialogues.isEmpty()) return 0L
        val ranges = dialogues.map { it.start to it.end }.sortedBy { it.first }
        var total = 0L
        var start = ranges.first().first
        var end = ranges.first().second
        for (r in ranges.drop(1)) {
            if (r.first <= end) end = maxOf(end, r.second)
            else { total += (end - start).coerceAtLeast(0); start = r.first; end = r.second }
        }
        return total + (end - start).coerceAtLeast(0)
    }

    private fun parseDialogues(file: File): List<Dialogue> {
        return try {
            file.readLines(Charsets.UTF_8).mapNotNull { line ->
                if (!line.trimStart().startsWith("Dialogue:", true)) return@mapNotNull null
                val body = line.substringAfter(':')
                val fields = body.split(',', limit = 10)
                if (fields.size < 3) return@mapNotNull null
                val start = parseAssTime(fields[1]) ?: return@mapNotNull null
                val end = parseAssTime(fields[2]) ?: return@mapNotNull null
                Dialogue(start, end, line)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseAssTime(value: String): Long? {
        val p = value.trim().split(':', limit = 3)
        if (p.size != 3) return null
        val h = p[0].toLongOrNull() ?: return null
        val m = p[1].toLongOrNull() ?: return null
        val secParts = p[2].replace(',', '.').split('.', limit = 2)
        val s = secParts[0].toLongOrNull() ?: return null
        val cs = secParts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0
        return h * 3600000 + m * 60000 + s * 1000 + cs * 10
    }

    private fun mergeAssFiles(base: File, extras: List<File>, output: File) {
        val baseText = base.readText(Charsets.UTF_8)
        val baseStyles = Regex("(?im)^Style:\\s*([^,\\r\\n]+),.*$").findAll(baseText).map { it.groupValues[1].trim() }.toMutableSet()
        val extraDialogues = mutableListOf<String>()
        val extraStyleLines = mutableListOf<String>()

        extras.forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            val styles = Regex("(?im)^Style:\\s*([^,\\r\\n]+),.*$").findAll(text)
            styles.forEach { m ->
                val name = m.groupValues[1].trim()
                if (!baseStyles.contains(name)) {
                    extraStyleLines += m.value
                    baseStyles += name
                }
            }
            text.lineSequence().filter { it.trimStart().startsWith("Dialogue:", true) }.forEach { extraDialogues += it }
        }

        var result = baseText
        if (extraStyleLines.isNotEmpty()) {
            val eventIndex = Regex("(?im)^\\[Events\\]\\s*$").find(result)?.range?.first
            if (eventIndex != null) result = result.substring(0, eventIndex) + extraStyleLines.joinToString("\n") + "\n\n" + result.substring(eventIndex)
        }
        if (extraDialogues.isNotEmpty()) result = result.trimEnd() + "\n" + extraDialogues.joinToString("\n") + "\n"
        output.writeText(result, Charsets.UTF_8)
    }

    private fun isAss(file: File): Boolean = try {
        val text = file.readText(Charsets.UTF_8).take(20000).lowercase(Locale.ROOT)
        text.contains("[script info]") && text.contains("[events]") && text.contains("dialogue:")
    } catch (_: Exception) { false }
}
