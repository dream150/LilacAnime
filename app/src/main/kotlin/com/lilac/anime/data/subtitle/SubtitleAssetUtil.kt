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
            // Keep every downloaded candidate on disk. The chosen file remains the
            // default, while the other valid files stay available in offline subtitle management.
            Log.d("Subtitle", "ASS_OVERLAP choose=${chosen.path} candidates=${valid.size} reason=longest_timeline keepAll=true")
            return chosen.path
        }

        val base = valid.maxByOrNull { it.first.priority }!!.first
        val baseFile = File(base.path)
        val merged = File(baseFile.parentFile, "${baseFile.nameWithoutExtension}_merged_e$episode.ass")
        mergeAssFiles(baseFile, valid.filter { it.first.path != base.path }.map { File(it.first.path) }, merged)
        // Keep the original ASS files as well as the merged default. This is required
        // for offline use: every discovered subtitle asset must remain selectable.
        Log.d("Subtitle", "ASS_MERGED count=${valid.size} path=${merged.absolutePath} dialogueCount=${allDialogues.size} keepAll=true")
        return merged.absolutePath
    }


    /** UU-encodes a TrueType font for the legacy ASS/SSA [Fonts] section. */
    private fun uuEncodeFont(data: ByteArray): String {
        val out = StringBuilder()
        var offset = 0
        while (offset < data.size) {
            val count = minOf(45, data.size - offset)
            out.append((count + 0x20).toChar())
            var i = 0
            while (i < count) {
                val a = data[offset + i].toInt() and 0xff
                val b = if (i + 1 < count) data[offset + i + 1].toInt() and 0xff else 0
                val c = if (i + 2 < count) data[offset + i + 2].toInt() and 0xff else 0
                val c1 = (a shr 2) and 0x3f
                val c2 = ((a shl 4) or (b shr 4)) and 0x3f
                val c3 = ((b shl 2) or (c shr 6)) and 0x3f
                val c4 = c and 0x3f
                fun enc(v: Int): Char = ((v and 0x3f) + 0x20).toChar()
                out.append(enc(c1)).append(enc(c2)).append(enc(c3)).append(enc(c4))
                i += 3
            }
            out.append('\n')
            offset += count
        }
        out.append('`').append('\n')
        return out.toString()
    }

    private fun appendEmbeddedFontSection(assText: String, font: File): String {
        // The classic [Fonts] section is parsed by libass and embeds the TTF in
        // the subtitle itself. This avoids the ass-media 0.5.x ordering problem
        // where addFont() can reach libass after its font lookup was configured.
        if (!font.isFile || !font.extension.equals("ttf", true)) return assText
        return try {
            val name = "LilacCustom_${font.nameWithoutExtension}.ttf"
            val encoded = uuEncodeFont(font.readBytes())
            assText.trimEnd() + "\n\n[Fonts]\nfontname: $name\n" + encoded
        } catch (e: Exception) {
            Log.w("Subtitle", "ASS_EMBED_FONT_FAILED font=${font.absolutePath}", e)
            assText
        }
    }

    /** Creates a temporary ASS with the selected font forced onto styles and dialogue overrides. */
    fun prepareAssWithSelectedFont(subtitlePath: String, selectedFontPath: String?): String {
        if (selectedFontPath.isNullOrBlank()) return subtitlePath
        val subtitle = File(subtitlePath)
        val font = File(selectedFontPath)
        if (!subtitle.isFile || !font.isFile || !isAss(subtitle)) return subtitlePath

        val text = try { subtitle.readText(Charsets.UTF_8) } catch (_: Exception) { return subtitlePath }
        val fontFamily = detectFontFamily(font)
            ?: font.nameWithoutExtension
                .replace(Regex("[_-](Regular|Medium|Bold|Semibold|Light|Black|Italic|Oblique)$", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { font.nameWithoutExtension }

        val out = File(
            subtitle.parentFile,
            "${subtitle.nameWithoutExtension}_font_${font.nameWithoutExtension.hashCode().toUInt().toString(16)}.ass"
        )
        try {
            var inStyles = false
            var styleFormat: List<String>? = null
            val result = text.lineSequence().map { line ->
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
                    if (idx >= 0 && idx < fields.size) fields[idx] = fontFamily
                    val prefix = line.takeWhile { it.isWhitespace() }
                    return@map prefix + "Style: " + fields.joinToString(",")
                }

                // Dialogue-level \fn overrides take precedence over Style.Fontname.
                // Replace them as well so the selected font is actually visible.
                if (trimmed.startsWith("Dialogue:", true) || trimmed.startsWith("Comment:", true)) {
                    return@map line.replace(Regex("\\\\fn[^\\\\}]+", RegexOption.IGNORE_CASE), "\\\\fn$fontFamily")
                }
                line
            }.toList()

            val rewrittenAss = result.joinToString("\n")
            out.writeText(appendEmbeddedFontSection(rewrittenAss, font), Charsets.UTF_8)
            Log.d("Subtitle", "ASS_FONT_OVERRIDE selected=${font.name} family=$fontFamily embeddedTtf=${font.extension.equals("ttf", true)} path=${out.absolutePath}")
            return out.absolutePath
        } catch (e: Exception) {
            Log.w("Subtitle", "ASS_FONT_OVERRIDE_FAILED font=${font.absolutePath}", e)
            return subtitlePath
        }
    }

    /** Returns the OpenType/TrueType family name used by libass/ASS Fontname. */
    fun fontFamilyName(file: File): String? = detectFontFamily(file)

    /** Reads the OpenType/TrueType family name used by libass for ASS Fontname matching. */
    private fun detectFontFamily(file: File): String? {
        return try {
            val bytes = file.readBytes()
            fun u16(pos: Int): Int =
                ((bytes[pos].toInt() and 0xff) shl 8) or (bytes[pos + 1].toInt() and 0xff)
            fun u32(pos: Int): Long =
                ((u16(pos).toLong() shl 16) or u16(pos + 2).toLong()) and 0xffffffffL

            fun readSfnt(base: Int): String? {
                if (base < 0 || base + 12 > bytes.size) return null
                val count = u16(base + 4)
                var nameOffset = -1
                for (i in 0 until count) {
                    val pos = base + 12 + i * 16
                    if (pos + 16 > bytes.size) break
                    val tag = String(byteArrayOf(bytes[pos], bytes[pos + 1], bytes[pos + 2], bytes[pos + 3]), Charsets.US_ASCII)
                    if (tag == "name") {
                        nameOffset = base + u32(pos + 8).toInt()
                        break
                    }
                }
                if (nameOffset < 0 || nameOffset + 6 > bytes.size) return null

                val records = u16(nameOffset + 2)
                val storage = u16(nameOffset + 4)
                var family: String? = null
                var typographicFamily: String? = null
                var fallback: String? = null

                for (i in 0 until records) {
                    val pos = nameOffset + 6 + i * 12
                    if (pos + 12 > bytes.size) break
                    val platform = u16(pos)
                    val language = u16(pos + 4)
                    val nameId = u16(pos + 6)
                    if (nameId != 1 && nameId != 16) continue
                    val len = u16(pos + 8)
                    val off = u16(pos + 10)
                    val dataStart = nameOffset + storage + off
                    if (dataStart < 0 || dataStart + len > bytes.size) continue

                    val value = if (platform == 3 || platform == 0) {
                        runCatching { String(bytes, dataStart, len, Charsets.UTF_16BE) }.getOrNull()
                    } else {
                        runCatching { String(bytes, dataStart, len, Charsets.ISO_8859_1) }.getOrNull()
                    }?.trim()?.takeIf { it.isNotBlank() } ?: continue

                    if (fallback == null) fallback = value
                    if (nameId == 1 && family == null) family = value
                    if (nameId == 16 && typographicFamily == null) typographicFamily = value
                    if (nameId == 1 && (language == 0x0412 || language == 0x0409 || platform == 3)) family = value
                }
                return family ?: typographicFamily ?: fallback
            }

            // TrueType Collection: each face has an offset in the TTC header.
            if (bytes.size >= 12 && String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) == "ttcf") {
                val numFonts = u32(8).toInt()
                if (numFonts > 0 && 12 + 4 <= bytes.size) {
                    return readSfnt(u32(12).toInt())
                }
            }
            readSfnt(0)
        } catch (_: Exception) {
            null
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
