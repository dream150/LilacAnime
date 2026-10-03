package com.lilac.anime.data.subtitle.translation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.max

internal object SubtitleFormatTranslator {
    private data class Cue(val startMs: Long, val endMs: Long, val text: String, val raw: String, val kind: String, val index: Int)

    suspend fun translate(context: Context, sourcePath: String, cacheKey: String, provider: TranslationProvider): String? = withContext(Dispatchers.IO) {
        val totalStartedAt = System.nanoTime()
        val file = File(sourcePath)
        Log.i("SubtitleProfile", "TRANSLATION_START path=${file.absolutePath} provider=${provider.id} exists=${file.exists()} size=${file.length()}")
        if (!file.isFile) {
            Log.e("SubtitleProfile", "TRANSLATION_ABORT source_not_file path=${file.absolutePath}")
            return@withContext null
        }

        val readStartedAt = System.nanoTime()
        val content = readSubtitleText(file) ?: run {
            Log.e("SubtitleProfile", "TRANSLATION_ABORT subtitle_read_failed path=${file.absolutePath}")
            return@withContext null
        }
        val readMs = (System.nanoTime() - readStartedAt) / 1_000_000L

        val ext = file.extension.lowercase(Locale.ROOT)
        val parseStartedAt = System.nanoTime()
        val parsed = parse(content, ext)
        val parseMs = (System.nanoTime() - parseStartedAt) / 1_000_000L
        if (parsed.isEmpty()) {
            Log.e("SubtitleProfile", "TRANSLATION_ABORT parse_empty ext=$ext chars=${content.length}")
            return@withContext null
        }

        val translated = Array<String?>(parsed.size) { null }
        var totalModelMs = 0L
        val localProvider = provider as? com.lilac.anime.data.subtitle.translation.providers.LocalTranslator
        val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
        val contextCueCount = prefs.getInt("pref_ai_context_cues", 4).coerceIn(0, 6)
        val sessionProvider = provider as? TranslationSessionProvider
        if (sessionProvider != null) sessionProvider.beginSession()
        try {
            if (localProvider != null) {
                parsed.forEachIndexed { index, cue ->
                    val source = modelText(cue.text, cue.kind)
                    val startIndex = (index - contextCueCount).coerceAtLeast(0)
                    val contextItems = parsed
                        .subList(startIndex, index)
                        .map { previous ->
                            com.lilac.anime.data.subtitle.translation.providers.LocalTranslator.LocalAiContext(
                                source = modelText(previous.text, previous.kind),
                                translation = translated[previous.index]?.takeIf { normalizeModelText(it) != normalizeModelText(previous.text) }
                            )
                        }
                    val futureLines = parsed
                        .subList(index + 1, (index + 3).coerceAtMost(parsed.size))
                        .map { modelText(it.text, it.kind) }

                    Log.d(
                        "SubtitleProfile",
                        "CUE_START index=${index + 1}/${parsed.size} contextCount=${contextItems.size} futureCount=${futureLines.size}"
                    )
                    val modelStartedAt = System.nanoTime()
                    val result = try {
                        localProvider.translateWithContext(source, contextItems, futureLines)
                    } catch (error: CancellationException) {
                        Log.e("SubtitleProfile", "CUE_CANCELLED index=${index + 1}/${parsed.size} type=${error::class.java.name} message=${error.message}", error)
                        throw error
                    } catch (error: Throwable) {
                        // A single bad cue must never abort the entire subtitle file.
                        // Keep the original cue and let the next cue continue.
                        Log.e("SubtitleProfile", "CUE_FAILED_KEEP_ORIGINAL index=${index + 1}/${parsed.size} type=${error::class.java.name} message=${error.message}", error)
                        cue.text
                    }
                    val modelMs = (System.nanoTime() - modelStartedAt) / 1_000_000L
                    totalModelMs += modelMs
                    translated[cue.index] = result.takeIf { it.isNotBlank() } ?: cue.text
                    Log.d("SubtitleProfile", "CUE_DONE index=${index + 1}/${parsed.size} contextCount=${contextItems.size} futureCount=${futureLines.size} modelMs=$modelMs")
                }
            } else {
                parsed.chunked(10).forEachIndexed { chunkIndex, chunk ->
                    val sources = chunk.map { modelText(it.text, it.kind) }
                    val modelStartedAt = System.nanoTime()
                    val results = try {
                        provider.translateBatch(sources)
                    } catch (error: CancellationException) {
                        Log.e("SubtitleProfile", "API_BATCH_CANCELLED batch=${chunkIndex + 1} type=${error::class.java.name} message=${error.message}", error)
                        throw error
                    } catch (error: Throwable) {
                        Log.e("SubtitleProfile", "API_BATCH_FAILED provider=${provider.id} batch=${chunkIndex + 1} type=${error::class.java.name} message=${error.message}", error)
                        throw error
                    }
                    if (results.size != chunk.size) error("${provider.displayName} 응답 cue 수가 일치하지 않습니다. expected=${chunk.size} actual=${results.size}")
                    val modelMs = (System.nanoTime() - modelStartedAt) / 1_000_000L
                    totalModelMs += modelMs
                    chunk.forEachIndexed { index, cue ->
                        translated[cue.index] = results[index].takeIf { it.isNotBlank() } ?: error("${provider.displayName} ${chunkIndex * 10 + index + 1}번째 번역 결과가 비어 있습니다.")
                    }
                    Log.d("SubtitleProfile", "API_BATCH_DONE provider=${provider.id} batch=${chunkIndex + 1} cues=${chunk.size} modelMs=$modelMs")
                }
            }
        } finally {
            Log.d("SubtitleProfile", "SESSION_CLEANUP_BEGIN")
            if (sessionProvider != null) {
                try {
                    sessionProvider.endSession()
                    Log.d("SubtitleProfile", "SESSION_CLEANUP_DONE")
                } catch (error: Throwable) {
                    Log.e("SubtitleProfile", "SESSION_CLEANUP_FAILED type=${error::class.java.name} message=${error.message}", error)
                    throw error
                }
            }
        }

        Log.i("SubtitleProfile", "ALL_CUES_DONE cues=${parsed.size} contextCues=$contextCueCount")
        val renderStartedAt = System.nanoTime()
        val output = try {
            render(content, ext, parsed, translated)
        } catch (error: Throwable) {
            Log.e("SubtitleProfile", "RENDER_FAILED type=${error::class.java.name} message=${error.message}", error)
            throw error
        }
        val renderMs = (System.nanoTime() - renderStartedAt) / 1_000_000L
        val outDir = File(context.filesDir, "translated_subtitles")
        val absolutePath = outDir.absolutePath
        val created = outDir.mkdirs()
        val exists = outDir.exists()
        val canWrite = outDir.canWrite()
        Log.d("SubtitleProfile", "OUTPUT_DIR path=$absolutePath exists=$exists created=$created writable=$canWrite")
        val out = File(outDir, cacheKey + "." + if (ext == "sami") "smi" else ext)
        val writeStartedAt = System.nanoTime()
        runCatching {
            out.writeText(output, Charsets.UTF_8)
        }.onFailure {
            Log.e("SubtitleProfile", "OUTPUT_WRITE_FAILED path=${out.absolutePath}", it)
            throw it
        }
        val writeMs = (System.nanoTime() - writeStartedAt) / 1_000_000L
        Log.d("SubtitleProfile", "OUTPUT_WRITE_SUCCESS path=${out.absolutePath} bytes=${out.length()}")
        val totalMs = (System.nanoTime() - totalStartedAt) / 1_000_000L
        Log.d(
            "SubtitleProfile",
            "TOTAL cues=${parsed.size} contextCues=$contextCueCount readMs=$readMs parseMs=$parseMs modelMs=$totalModelMs renderMs=$renderMs writeMs=$writeMs totalMs=$totalMs"
        )
        Log.i("SubtitleProfile", "TRANSLATION_SUCCESS path=${out.absolutePath}")
        out.absolutePath
    }

    private fun readSubtitleText(file: File): String? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val candidates = mutableListOf<Charset>()
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> candidates += Charsets.UTF_8
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> candidates += Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> candidates += Charsets.UTF_16BE
            else -> candidates += listOf(Charsets.UTF_8, Charset.forName("EUC-KR"), Charset.forName("Shift_JIS"), Charsets.UTF_16LE, Charsets.UTF_16BE)
        }
        val decoded = candidates.distinct().mapNotNull { charset ->
            runCatching { String(bytes, charset).removePrefix("\uFEFF") }.getOrNull()
        }
        return decoded.maxByOrNull { text ->
            val replacement = text.count { it == '\uFFFD' }
            val controls = text.count { it.code in 0..8 || it.code in 14..31 }
            text.length - replacement * 20 - controls * 20
        }
    }

    private fun parseByContent(c: String): List<Cue> {
        val normalized = c.trimStart()
        return when {
            normalized.startsWith("WEBVTT", true) -> parseVtt(c)
            Regex("(?im)^Dialogue:").containsMatchIn(c) -> parseAss(c)
            Regex("(?is)<SAMI|<SYNC\\s+Start\\s*=").containsMatchIn(c) -> parseSmi(c)
            Regex("(?is)<tt[ >]|<ttml|xmlns=.*ttml").containsMatchIn(c) -> parseTtml(c)
            Regex("(?m)^\\s*\\{\\d+\\}\\{\\d+\\}").containsMatchIn(c) -> parseSub(c)
            Regex("(?m)^\\s*\\[?\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}\\]?\\s*[-=]+>").containsMatchIn(c) -> parseSrt(c)
            else -> emptyList()
        }
    }

    private fun parse(content: String, ext: String): List<Cue> = when (ext) {
        "ass", "ssa" -> parseAss(content)
        "srt" -> parseSrt(content)
        "vtt" -> parseVtt(content)
        "smi", "sami" -> parseSmi(content)
        "sbv" -> parseSbv(content)
        "sub" -> parseSub(content)
        "mpl", "mpl2" -> parseMpl2(content)
        "ttml", "xml" -> parseTtml(content)
        else -> parseByContent(content)
    }

    private fun normalizeModelText(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\\N", "\n")
        .trim()

    private fun modelText(text: String, kind: String): String {
        var value = text.replace("\r\n", "\n").replace('\r', '\n')
        if (kind == "srt") value = value.replace("\n", "\\N")
        value = value.replace(Regex("(?s)\\{[^}]*\\}"), "")
        value = value.replace(Regex("(?is)<[^>]+>"), "")
        return value.trim()
    }

    private fun restoreTags(original: String, translated: String, kind: String): String {
        val normalized = translated.replace("\r\n", "\n").replace('\r', '\n')
        return when (kind) {
            "ass" -> {
                val tags = Regex("\\{[^}]*\\}").findAll(original).map { it.value }.toList()
                val text = normalized.replace("\n", "\\N")
                if (tags.isEmpty()) text else tags.joinToString("") + text
            }
            else -> {
                val tags = Regex("(?is)<[^>]+>").findAll(original).map { it.value }.toList()
                if (tags.isEmpty()) normalized else tags.joinToString("") + normalized
            }
        }
    }

    private fun parseAss(c: String): List<Cue> {
        val lines = c.lines()
        val japaneseRegex = Regex("[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff々ー]")
        val firstJapaneseDialogueLine = lines.indexOfFirst { line ->
            if (!line.startsWith("Dialogue:", true)) return@indexOfFirst false
            val body = line.substringAfter(':').trimStart()
            val parts = body.split(',', limit = 10)
            parts.size >= 10 && japaneseRegex.containsMatchIn(parts[9])
        }
        if (firstJapaneseDialogueLine < 0) {
            Log.w("SubtitleProfile", "ASS_FIRST_JAPANESE_NOT_FOUND")
            return emptyList()
        }
        Log.d("SubtitleProfile", "ASS_FIRST_JAPANESE_LINE line=${firstJapaneseDialogueLine + 1}")
        var cueIndex = 0
        return lines.mapIndexedNotNull { idx, line ->
            if (idx < firstJapaneseDialogueLine || !line.startsWith("Dialogue:", true)) return@mapIndexedNotNull null
            val body = line.substringAfter(':').trimStart()
            val parts = body.split(',', limit = 10)
            if (parts.size < 10) return@mapIndexedNotNull null
            val start = assTime(parts[1]) ?: return@mapIndexedNotNull null
            val end = assTime(parts[2]) ?: return@mapIndexedNotNull null
            Cue(start, end, parts[9].trim(), line, "ass", cueIndex++)
        }
    }

    private fun parseSrt(c: String): List<Cue> {
        val blocks = c.replace("\r\n", "\n").split(Regex("\n{2,}"))
        var idx = 0
        return blocks.mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val timeLine = lines.firstOrNull { it.contains(" --> ") } ?: return@mapNotNull null
            val m = Regex("(\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3})\\s*-->\\s*(\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3})").find(timeLine) ?: return@mapNotNull null
            val start = srtTime(m.groupValues[1]) ?: return@mapNotNull null
            val end = srtTime(m.groupValues[2]) ?: return@mapNotNull null
            val text = lines.drop(lines.indexOf(timeLine) + 1).joinToString("\n").trim()
            Cue(start, end, text, block, "srt", idx++)
        }
    }

    private fun parseVtt(c: String): List<Cue> {
        val blocks = c.replace("\r\n", "\n").split(Regex("\n{2,}"))
        var idx = 0
        return blocks.mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val timeLine = lines.firstOrNull { it.contains(" --> ") } ?: return@mapNotNull null
            val m = Regex("(\\d{2}:\\d{2}(?::\\d{2})?[.,]\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}(?::\\d{2})?[.,]\\d{3})").find(timeLine) ?: return@mapNotNull null
            val start = webVttTime(m.groupValues[1]) ?: return@mapNotNull null
            val end = webVttTime(m.groupValues[2]) ?: return@mapNotNull null
            val text = lines.drop(lines.indexOf(timeLine) + 1).joinToString("\n").trim()
            Cue(start, end, text, block, "vtt", idx++)
        }
    }

    private fun parseSmi(c: String): List<Cue> {
        val regex = Regex("(?is)<SYNC\\s+Start\\s*=\\s*(\\d+)\\s*>(.*?)(?=<SYNC\\s+Start|</BODY>|</SAMI>)")
        val matches = regex.findAll(c).toList()
        return matches.mapIndexed { i, m ->
            val start = m.groupValues[1].toLongOrNull() ?: 0L
            val next = matches.getOrNull(i + 1)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: start + 4000L
            val text = m.groupValues[2].trim()
            Cue(start, next, text, m.value, "smi", i)
        }
    }

    private fun parseSbv(c: String): List<Cue> {
        val blocks = c.replace("\r\n", "\n").split(Regex("\n{2,}"))
        var idx = 0
        return blocks.mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val times = lines.firstOrNull()?.split(',') ?: return@mapNotNull null
            if (times.size != 2) return@mapNotNull null
            val start = webVttTime(times[0].trim()) ?: return@mapNotNull null
            val end = webVttTime(times[1].trim()) ?: return@mapNotNull null
            Cue(start, end, lines.drop(1).joinToString("\n"), block, "sbv", idx++)
        }
    }

    private fun parseSub(c: String): List<Cue> = c.lineSequence().mapIndexedNotNull { idx, line ->
        val m = Regex("^\\s*\\[(\\d+)\\]\\s*\\[(\\d+)\\]\\s*(.*)$").find(line) ?: return@mapIndexedNotNull null
        val start = m.groupValues[1].toLongOrNull()?.times(100L) ?: return@mapIndexedNotNull null
        val end = m.groupValues[2].toLongOrNull()?.times(100L) ?: return@mapIndexedNotNull null
        Cue(start, end, m.groupValues[3].replace("|", "\\n").trim(), line, "sub", idx)
    }.toList()

    private fun parseMpl2(c: String): List<Cue> = c.lineSequence().mapIndexedNotNull { idx, line ->
        val m = Regex("^\\s*\\[(\\d+)\\]\\s*\\[(\\d+)\\]\\s*(.*)$").find(line) ?: return@mapIndexedNotNull null
        val start = m.groupValues[1].toLongOrNull()?.times(100L) ?: return@mapIndexedNotNull null
        val end = m.groupValues[2].toLongOrNull()?.times(100L) ?: return@mapIndexedNotNull null
        Cue(start, end, m.groupValues[3].replace("|", "\\n").trim(), line, "mpl2", idx)
    }.toList()

    private fun parseTtml(c: String): List<Cue> {
        val regex = Regex("(?is)<p\\b([^>]*)>(.*?)</p>")
        return regex.findAll(c).mapIndexedNotNull { idx, m ->
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            val begin = Regex("(?i)\\bbegin\\s*=\\s*[\\\"']([^\\\"']+)").find(attrs)?.groupValues?.get(1)
            val end = Regex("(?i)\\bend\\s*=\\s*[\\\"']([^\\\"']+)").find(attrs)?.groupValues?.get(1)
            val dur = Regex("(?i)\\bdur\\s*=\\s*[\\\"']([^\\\"']+)").find(attrs)?.groupValues?.get(1)
            val start = ttmlTime(begin) ?: return@mapIndexedNotNull null
            val finish = ttmlTime(end) ?: dur?.let { start + (ttmlDuration(it) ?: return@mapIndexedNotNull null) } ?: return@mapIndexedNotNull null
            val text = body.trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            Cue(start, finish, text, m.value, "ttml", idx)
        }.toList()
    }

    private fun ttmlTime(s: String?): Long? {
        val v = s?.trim() ?: return null
        if (v.matches(Regex("\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?"))) return webVttTime(v)
        if (v.matches(Regex("\\d+(?:\\.\\d+)?ms"))) return v.removeSuffix("ms").toDoubleOrNull()?.toLong()
        if (v.matches(Regex("\\d+(?:\\.\\d+)?s"))) return v.removeSuffix("s").toDoubleOrNull()?.times(1000)?.toLong()
        return null
    }

    private fun ttmlDuration(s: String): Long? = ttmlTime(s)

    private fun render(original: String, ext: String, cues: List<Cue>, translated: Array<String?>): String = when (ext) {
        "ass", "ssa" -> {
            original.replace("\r\n", "\n").replace('\r', '\n').lines().map { line ->
                if (!line.startsWith("Dialogue:", true)) return@map line
                val parts = line.substringAfter(':').trimStart().split(',', limit = 10)
                if (parts.size < 10) return@map line
                val start = assTime(parts[1]) ?: return@map line
                val end = assTime(parts[2]) ?: return@map line
                val rawText = parts[9].trim()
                val cue = cues.firstOrNull { it.kind == "ass" && it.startMs == start && it.endMs == end && it.text.trim() == rawText }
                    ?: cues.firstOrNull { it.kind == "ass" && it.startMs == start && it.endMs == end }
                    ?: return@map line
                val t = restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind)
                val mutable = parts.toMutableList()
                mutable[9] = t
                "Dialogue: " + mutable.joinToString(",")
            }.joinToString("\n")
        }
        "srt" -> renderSrtPreservingTiming(original, cues, translated)
        "vtt" -> {
            val blocks = original.replace("\r\n", "\n").split(Regex("\n{2,}"))
            var i = 0
            blocks.joinToString("\n\n") { block ->
                val cue = cues.getOrNull(i) ?: return@joinToString block
                if (!block.contains(" --> ")) return@joinToString block
                val lines = block.lines().toMutableList(); val ti = lines.indexOfFirst { it.contains(" --> ") }
                if (ti >= 0) { lines.subList(ti + 1, lines.size).clear(); lines += restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind); i++ }
                lines.joinToString("\n")
            }
        }
        "sbv" -> {
            val blocks = original.replace("\r\n", "\n").split(Regex("\n{2,}"))
            var i = 0
            blocks.joinToString("\n\n") { block ->
                val cue = cues.getOrNull(i) ?: return@joinToString block
                if (!block.contains(",")) return@joinToString block
                val lines = block.lines().toMutableList()
                if (lines.size >= 2) { lines.subList(1, lines.size).clear(); lines += restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind); i++ }
                lines.joinToString("\n")
            }
        }
        "smi", "sami" -> {
            var i = 0
            Regex("(?is)<SYNC\\s+Start\\s*=\\s*(\\d+)\\s*>(.*?)(?=<SYNC\\s+Start|</BODY>|</SAMI>)").replace(original) { m ->
                val cue = cues.getOrNull(i++) ?: return@replace m.value
                val text = restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind)
                val tag = Regex("(?is)<SYNC\\s+Start\\s*=\\s*\\d+\\s*>").find(m.value)?.value ?: ""
                "$tag$text"
            }
        }
        "sub" -> original.lineSequence().mapIndexed { i, line ->
            val cue = cues.getOrNull(i) ?: return@mapIndexed line
            val text = restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind)
            Regex("^(\\s*\\{\\d+\\}\\{\\d+\\}\\s*).*$").replace(line) { it.groupValues[1] + text }
        }.joinToString("\n")
        "mpl", "mpl2" -> original.lineSequence().mapIndexed { i, line ->
            val cue = cues.getOrNull(i) ?: return@mapIndexed line
            val text = restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind)
            Regex("^(\\s*\\[\\d+\\]\\s*\\[\\d+\\]\\s*).*$").replace(line) { it.groupValues[1] + text }
        }.joinToString("\n")
        "ttml", "xml" -> {
            var i = 0
            Regex("(?is)<p\\b([^>]*)>(.*?)</p>").replace(original) { m ->
                val cue = cues.getOrNull(i++) ?: return@replace m.value
                val text = restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind)
                "<p${m.groupValues[1]}>$text</p>"
            }
        }
        else -> original
    }


    private fun renderSrtPreservingTiming(original: String, cues: List<Cue>, translated: Array<String?>): String {
        val normalized = original.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\n{2,}"))
        var cueIndex = 0
        return blocks.joinToString("\n\n") { block ->
            val lines = block.split('\n').toMutableList()
            val timingIndex = lines.indexOfFirst { it.contains(" --> ") }
            if (timingIndex < 0) return@joinToString block
            val cue = cues.getOrNull(cueIndex) ?: return@joinToString block
            val originalTiming = lines[timingIndex]
            val expectedTiming = formatSrtTiming(cue.startMs, cue.endMs, originalTiming)
            if (originalTiming != expectedTiming) {
                Log.w("SubtitleProfile", "SRT_TIMING_SOURCE_MISMATCH cue=${cue.index} source=[$originalTiming] parsed=[$expectedTiming]")
            }
            val value = restoreTags(cue.text, translated[cue.index] ?: cue.text, cue.kind)
            lines.subList(timingIndex + 1, lines.size).clear()
            lines += value.replace("\\N", "\n")
            cueIndex++
            lines[timingIndex] = originalTiming
            lines.joinToString("\n")
        }
    }

    private fun formatSrtTiming(startMs: Long, endMs: Long, originalTiming: String): String {
        val separator = if (originalTiming.contains(" --> ")) " --> " else "-->"
        fun format(ms: Long): String {
            val total = ms.coerceAtLeast(0L)
            val h = total / 3_600_000L
            val m = (total % 3_600_000L) / 60_000L
            val s = (total % 60_000L) / 1_000L
            val msPart = total % 1_000L
            return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, msPart)
        }
        return format(startMs) + separator + format(endMs)
    }

    private fun assTime(s: String): Long? = s.trim().split(':').let { p -> if (p.size != 3) null else ((p[0].toLongOrNull() ?: return null) * 3600000L + (p[1].toLongOrNull() ?: return null) * 60000L + ((p[2].substringBefore('.').toLongOrNull() ?: return null) * 1000L) + (p[2].substringAfter('.', "0").padEnd(2, '0').take(2).toLongOrNull() ?: 0L) * 10L) }
    private fun srtTime(s: String): Long? = webVttTime(s.replace(',', '.'))
    private fun webVttTime(s: String): Long? { val p = s.replace(',', '.').split(':'); if (p.size !in 2..3) return null; val sec = p.last().toDoubleOrNull() ?: return null; val min = p[p.size-2].toLongOrNull() ?: return null; val h = if (p.size == 3) p[0].toLongOrNull() ?: return null else 0L; return h*3600000L + min*60000L + (sec*1000).toLong() }
}
