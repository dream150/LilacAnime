package com.lilac.anime.data.subtitle.translation

import android.content.Context
import android.util.Log
import com.lilac.anime.data.subtitle.translation.providers.LocalTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class RealtimeSubtitleTranslator(private val context: Context) {
    private data class Cue(val startMs: Long, val endMs: Long, val text: String, val kind: String, val index: Int)

    private val lock = Mutex()
    private val translationLock = Mutex()
    private var activeProvider: TranslationProvider = LocalTranslator(context)
    private var activeProviderId: String = "local"
    private var sessionProvider: TranslationSessionProvider? = null
    private var sessionStarted = false
    private val cache = LinkedHashMap<String, String>(256, 0.75f, true)
    private var cues: List<Cue> = emptyList()
    private var sourcePath: String? = null
    private var sourceContent: String? = null
    private var sourceExt: String = ""
    private var translatedPath: String? = null
    private var worker: Job? = null
    private var prefetchPositionMs: Long = 0L
    private var generation = 0
    private var subtitleVersion = 0L
    private var appliedSubtitleVersion = -1L
    private var renderedSubtitleVersion = -1L
    private var offlineParseWarningLogged = false

    suspend fun prepare(path: String?, positionMs: Long, scope: CoroutineScope, providerId: String = "local") {
        val file = path?.let(::File)?.takeIf { it.isFile } ?: return
        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return
        val parsed = runCatching { parse(content, file.extension.lowercase()) }
            .onFailure { Log.e("RealtimeSubtitleTranslator", "PARSE_FAILED path=${file.absolutePath}", it) }
            .getOrDefault(emptyList())
        if (parsed.isEmpty()) {
            Log.w("RealtimeSubtitleTranslator", "PARSE_EMPTY path=${file.absolutePath} ext=${file.extension}")
        }
        lock.withLock {
            generation++
            worker?.cancel()
            worker = null
            cache.clear()
            sourcePath = file.absolutePath
            sourceContent = content
            sourceExt = file.extension.lowercase()
            translatedPath = null
            subtitleVersion++
            appliedSubtitleVersion = -1L
            renderedSubtitleVersion = -1L
            cues = parsed
        }
        if (sessionStarted) {
            runCatching { sessionProvider?.endSession() }
            sessionStarted = false
        }
        activeProviderId = providerId
        activeProvider = TranslationManager.createProvider(context, providerId)
        sessionProvider = activeProvider as? TranslationSessionProvider
        val localGeneration = generation
        prefetchPositionMs = positionMs
        worker = scope.launch(Dispatchers.IO) {
            prefetchLoop(localGeneration, positionMs)
        }
    }

    /**
     * Playback-time lookup only.
     *
     * Translation must never run synchronously from the subtitle event callback.
     * The background prefetch worker is the only path allowed to call the model.
     * This prevents a subtitle change from blocking playback and removes the old
     * one-line/realtime translation behavior that competed with batch prefetch.
     */
    suspend fun updatePlaybackPosition(positionMs: Long) {
        // Playback events only advance the prefetch window. They never call the
        // translator and never perform a one-line translation.
        prefetchPositionMs = positionMs
    }

    suspend fun clear() {
        lock.withLock {
            generation++
            worker?.cancel()
            worker = null
            cache.clear()
            cues = emptyList()
            sourcePath = null
            sourceContent = null
            sourceExt = ""
            translatedPath?.let { runCatching { File(it).delete() } }
            translatedPath = null
            subtitleVersion++
            appliedSubtitleVersion = -1L
            renderedSubtitleVersion = -1L
            if (sessionStarted) {
                sessionProvider?.endSession()
                sessionStarted = false
            }
            activeProviderId = "local"
            activeProvider = LocalTranslator(context)
            sessionProvider = null
        }
    }

    private suspend fun ensureSession() {
        if (sessionStarted) return
        val provider = sessionProvider ?: return
        provider.beginSession()
        sessionStarted = true
    }

    private suspend fun prefetchLoop(localGeneration: Int, initialPositionMs: Long) {
        var nextIndex = -1
        val failures = mutableMapOf<String, Int>()
        while (true) {
            if (localGeneration != lock.withLock { generation }) return

            val snapshot = lock.withLock { cues }
            if (snapshot.isEmpty()) return

            val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("pref_ai_prefetch_enabled", true)) return

            // Prefetch count is only a playback-ahead window. It is NOT an AI
            // request batch size. Every model invocation below receives exactly
            // one subtitle cue.
            val prefetchAhead = prefs.getInt("pref_ai_prefetch_ahead", 10).coerceIn(0, 40)
            val contextCueCount = prefs.getInt("pref_ai_context_cues", 4).coerceIn(0, 6)
            val position = lock.withLock { prefetchPositionMs }
            val ordered = snapshot.sortedWith(compareBy<Cue> { it.startMs }.thenBy { it.endMs }.thenBy { it.index })
            val currentIndex = ordered.indexOfFirst { it.startMs >= position - 500L }
                .let { if (it < 0) ordered.lastIndex.coerceAtLeast(0) else it }

            if (nextIndex < currentIndex) nextIndex = currentIndex

            // Keep enough work queued to cover the current cue plus the configured
            // number of cues ahead. The cursor only advances; it never creates a
            // multi-cue translation request.
            val windowEnd = (currentIndex + prefetchAhead + 1).coerceAtMost(ordered.size)
            if (nextIndex >= windowEnd) {
                delay(200L)
                continue
            }

            while (nextIndex < windowEnd && lock.withLock { cache.containsKey(cueCacheKey(ordered[nextIndex])) }) {
                nextIndex++
            }
            if (nextIndex >= windowEnd) continue

            val cue = ordered[nextIndex]
            nextIndex++

            if (localGeneration != lock.withLock { generation }) return
            Log.i(
                "RealtimeSubtitleTranslator",
                "PREFETCH_NEXT position=$position configuredAhead=$prefetchAhead cue=${cue.index}"
            )

            try {
                translationLock.withLock {
                    ensureSession()
                    if (localGeneration != lock.withLock { generation }) throw CancellationException("subtitle session changed")
                    val already = lock.withLock { cache.containsKey(cueCacheKey(cue)) }
                    if (!already) {
                        val orderedIndex = ordered.indexOf(cue)
                        val contextStart = (orderedIndex - contextCueCount).coerceAtLeast(0)
                        val contextCues = ordered.subList(contextStart, orderedIndex)
                        val contextMemory = contextCues.map { previous ->
                            LocalTranslator.LocalAiContext(
                                source = modelText(previous.text, previous.kind),
                                translation = lock.withLock { cache[cueCacheKey(previous)] }
                            )
                        }
                        val futureLines = ordered
                            .subList(orderedIndex + 1, (orderedIndex + 3).coerceAtMost(ordered.size))
                            .map { modelText(it.text, it.kind) }
                        val source = modelText(cue.text, cue.kind)
                        Log.i(
                            "RealtimeSubtitleTranslator",
                            "PREFETCH_TRANSLATE provider=$activeProviderId cue=${cue.index} contextCount=${if (activeProvider is LocalTranslator) contextMemory.size else 0}"
                        )
                        val selectedProvider = activeProvider
                        val result = if (selectedProvider is LocalTranslator) {
                            selectedProvider.translateWithContext(source, contextMemory, futureLines).trim()
                        } else {
                            selectedProvider.translateBatch(listOf(source)).firstOrNull().orEmpty().trim()
                        }
                        if (result.isNotBlank()) {
                            lock.withLock { cache[cueCacheKey(cue)] = result }
                            lock.withLock { subtitleVersion++ }
                        } else {
                            error("번역 결과가 비어 있습니다.")
                        }
                    }
                }
                runCatching { rebuildTranslatedSubtitle() }
                    .onFailure { Log.e("RealtimeSubtitleTranslator", "RENDER_TRANSLATED_FAILED", it) }
            } catch (e: CancellationException) {
                Log.d("RealtimeSubtitleTranslator", "PREFETCH_NEXT_CANCELLED cue=${cue.index}")
                throw e
            } catch (t: Throwable) {
                Log.e("RealtimeSubtitleTranslator", "PREFETCH_NEXT_FAILED provider=$activeProviderId cue=${cue.index}", t)
                val key = cueCacheKey(cue)
                val attempt = (failures[key] ?: 0) + 1
                failures[key] = attempt
                if (attempt < 3) nextIndex = minOf(nextIndex, ordered.indexOf(cue))
                else Log.e("RealtimeSubtitleTranslator", "PREFETCH_GIVE_UP provider=$activeProviderId cue=${cue.index} attempts=$attempt")
                delay(if (activeProviderId == "local") 500L else 3000L)
            }
        }
    }

    private fun cueCacheKey(cue: Cue): String =
        if (cue.index >= 0) "cue:${cue.kind}:${cue.index}" else "runtime:${normalize(modelText(cue.text, cue.kind))}"

    private fun findCurrentCue(snapshot: List<Cue>, text: String, positionMs: Long): Cue? {
        if (snapshot.isEmpty()) return null
        val kind = snapshot.firstOrNull()?.kind ?: return null
        val key = normalize(modelText(text, kind))
        if (key.isBlank()) return null
        val matching = snapshot.filter { normalize(modelText(it.text, it.kind)) == key }
        return matching
            .filter { positionMs >= it.startMs && positionMs <= it.endMs }
            .minByOrNull { kotlin.math.abs(positionMs - it.startMs) }
            ?: matching.minByOrNull { kotlin.math.abs(positionMs - it.startMs) }
    }

    /** Returns the newly generated subtitle path only once per generated version. */
    suspend fun consumeTranslatedSubtitleUpdate(): String? = lock.withLock {
        if (translatedPath.isNullOrBlank() || renderedSubtitleVersion < 0L || appliedSubtitleVersion == renderedSubtitleVersion) return@withLock null
        appliedSubtitleVersion = renderedSubtitleVersion
        translatedPath
    }

    private fun normalize(text: String): String = text
        .replace("\r", "")
        .trim()

    private fun modelText(text: String, kind: String): String {
        val normalized = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val lineBreaksNormalized = if (kind == "srt" || kind == "vtt" || kind == "sbv") {
            normalized.replace("\n", "\\N")
        } else {
            normalized
        }
        return lineBreaksNormalized
            .replace(Regex("(?s)\\{[^}]*\\}"), "")
            .replace(Regex("(?is)<[^>]+>"), "")
            .trim()
    }

    fun translatedSubtitlePath(): String? = translatedPath

    private suspend fun rebuildTranslatedSubtitle() {
        val content = lock.withLock { sourceContent } ?: return
        val ext = lock.withLock { sourceExt }
        val snapshot = lock.withLock { cues }
        val translated = lock.withLock { cache.toMap() }
        if (snapshot.isEmpty() || translated.isEmpty()) return
        val output = renderTranslated(content, ext, snapshot, translated)
        val dir = File(context.cacheDir, "realtime_translated_subtitles").apply { mkdirs() }
        val sourceName = sourcePath?.let { File(it).nameWithoutExtension } ?: "subtitle"
        val out = File(dir, "${sourceName}_${generation}.${if (ext == "sami") "smi" else ext.ifBlank { "ass" }}")
        runCatching {
            val tmp = File(dir, out.name + ".tmp")
            tmp.writeText(output, Charsets.UTF_8)
            if (!tmp.isFile || tmp.length() == 0L) error("translated subtitle output is empty")
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
        }.onSuccess {
            lock.withLock {
                translatedPath = out.absolutePath
                renderedSubtitleVersion = subtitleVersion
            }
        }.onFailure {
            Log.e("RealtimeSubtitleTranslator", "WRITE_TRANSLATED_FAILED path=${out.absolutePath}", it)
        }
    }

    private fun restoreTags(original: String, translated: String, kind: String): String {
        val normalized = translated.replace("\r\n", "\n").replace('\r', '\n')
        val tagRegex = if (kind == "ass") Regex("\\{[^}]*\\}") else Regex("(?is)<[^>]+>")
        val matches = tagRegex.findAll(original).toList()
        if (matches.isEmpty()) return if (kind == "ass") normalized.replace("\n", "\\N") else normalized
        // ASS override tags belong at the same logical positions as the original
        // cue. Do not let a model-generated label or echoed context become part of
        // the subtitle payload.
        val visibleLength = original.replace(tagRegex, "").length.coerceAtLeast(1)
        val translatedText = if (kind == "ass") normalized.replace("\n", "\\N") else normalized
        val insertions = matches.map { match ->
            val before = original.substring(0, match.range.first).replace(tagRegex, "")
            val ratio = before.length.toDouble() / visibleLength.toDouble()
            val position = (translatedText.length * ratio).toInt().coerceIn(0, translatedText.length)
            position to match.value
        }.sortedWith(compareBy<Pair<Int, String>> { it.first })
        val out = StringBuilder()
        var cursor = 0
        insertions.groupBy { it.first }.toSortedMap().forEach { (position, tags) ->
            val p = position.coerceIn(cursor, translatedText.length)
            if (p > cursor) out.append(translatedText.substring(cursor, p))
            tags.forEach { out.append(it.second) }
            cursor = p
        }
        if (cursor < translatedText.length) out.append(translatedText.substring(cursor))
        return out.toString()
    }

    private fun renderTranslated(original: String, ext: String, cues: List<Cue>, translated: Map<String, String>): String {
        fun value(cue: Cue): String = restoreTags(cue.text, translated[cueCacheKey(cue)] ?: cue.text, cue.kind)
        return when (ext) {
            "ass", "ssa" -> renderAssPreservingCues(original, cues, ::value)
            "srt" -> renderSrtPreservingTiming(original, cues, ::value)
            "vtt", "sbv" -> renderTimedTextPreservingStructure(original, ext, cues, ::value)
            "smi", "sami" -> {
                var cueIndex = 0
                Regex("(?is)<SYNC\\s+Start\\s*=\\s*(\\d+)\\s*>(.*?)(?=<SYNC\\s+Start|</BODY>|</SAMI>)").replace(original) { match ->
                    val cue = cues.getOrNull(cueIndex++) ?: return@replace match.value
                    val tag = Regex("(?is)<SYNC\\s+Start\\s*=\\s*\\d+\\s*>").find(match.value)?.value ?: ""
                    "$tag${value(cue)}"
                }
            }
            "sub" -> {
                var cueIndex = 0
                original.lineSequence().map { line ->
                    if (!Regex("^\\s*\\{\\d+\\}\\{\\d+\\}").containsMatchIn(line)) return@map line
                    val cue = cues.getOrNull(cueIndex++) ?: return@map line
                    Regex("^(\\s*\\{\\d+\\}\\{\\d+\\}\\s*).*$").replace(line) { it.groupValues[1] + value(cue) }
                }.joinToString("\n")
            }
            "mpl", "mpl2" -> {
                var cueIndex = 0
                original.lineSequence().map { line ->
                    if (!Regex("^\\s*\\[\\d+\\]\\s*\\[\\d+\\]").containsMatchIn(line)) return@map line
                    val cue = cues.getOrNull(cueIndex++) ?: return@map line
                    Regex("^(\\s*\\[\\d+\\]\\s*\\[\\d+\\]\\s*).*$").replace(line) { it.groupValues[1] + value(cue) }
                }.joinToString("\n")
            }
            "ttml", "xml" -> {
                var cueIndex = 0
                Regex("(?is)<p\\b([^>]*)>(.*?)</p>").replace(original) { match ->
                    val cue = cues.getOrNull(cueIndex++) ?: return@replace match.value
                    "<p${match.groupValues[1]}>${value(cue)}</p>"
                }
            }
            else -> original
        }
    }

    private fun renderAssPreservingCues(
        original: String,
        cues: List<Cue>,
        value: (Cue) -> String
    ): String {
        return original.replace("\r\n", "\n").replace('\r', '\n').lines().joinToString("\n") { line ->
            if (!line.trimStart().startsWith("Dialogue:", ignoreCase = true)) return@joinToString line
            val body = line.substringAfter(':', "").trimStart().split(',', limit = 10)
            if (body.size < 10) return@joinToString line
            val start = parseAssTimeForRender(body[1])
            val end = parseAssTimeForRender(body[2])
            val rawText = body[9].trim()
            val cue = cues.firstOrNull { it.kind == "ass" && it.startMs == start && it.endMs == end && it.text.trim() == rawText }
                ?: cues.firstOrNull { it.kind == "ass" && it.startMs == start && it.endMs == end }
                ?: return@joinToString line
            val colon = line.indexOf(':')
            val prefix = line.substring(0, colon + 1)
            val payload = line.substring(colon + 1)
            val leading = payload.takeWhile { it.isWhitespace() }
            val content = payload.drop(leading.length)
            val commaPositions = content.mapIndexedNotNull { index, ch -> if (ch == ',') index else null }
            if (commaPositions.size < 9) return@joinToString line
            val textStart = commaPositions[8] + 1
            prefix + leading + content.substring(0, textStart) + value(cue).replace("\n", "\\N")
        }
    }

    private fun parseAssTimeForRender(value: String): Long {
        val p = value.trim().split(':')
        return runCatching {
            if (p.size != 3) return@runCatching -1L
            val sec = p[2].replace(',', '.').toDouble()
            ((p[0].toLong() * 3600 + p[1].toLong() * 60) * 1000 + (sec * 1000).toLong())
        }.getOrDefault(-1L)
    }

    private fun renderTimedTextPreservingStructure(
        original: String,
        ext: String,
        cues: List<Cue>,
        value: (Cue) -> String
    ): String {
        val lines = original.replace("\r\n", "\n").replace('\r', '\n').lines().toMutableList()
        val timing = Regex("^\\s*([^\\s]+)\\s*-->\\s*([^\\s]+)(?:\\s+.*)?$")
        var cueIndex = 0
        var i = 0
        while (i < lines.size) {
            if (ext == "sbv") {
                val first = lines[i].trim()
                if (!first.contains(',')) { i++; continue }
                val p = first.split(',', limit = 2)
                if (p.size != 2 || parseTime(p[0]) < 0 || parseTime(p[1]) < 0) { i++; continue }
                val cue = cues.getOrNull(cueIndex++) ?: break
                val startBody = i + 1
                var j = startBody
                while (j < lines.size && lines[j].isNotBlank()) j++
                lines.subList(startBody, j).clear()
                lines.add(startBody, value(cue).replace("\\N", "\n"))
                i = startBody + 1
                continue
            }
            if (!timing.matches(lines[i])) { i++; continue }
            val cue = cues.getOrNull(cueIndex++) ?: break
            val startBody = i + 1
            var j = startBody
            while (j < lines.size && !timing.matches(lines[j])) {
                if (lines[j].isBlank()) break
                j++
            }
            lines.subList(startBody, j).clear()
            lines.add(startBody, value(cue).replace("\\N", "\n"))
            i = startBody + 1
        }
        return lines.joinToString("\n")
    }

    private fun renderSrtPreservingTiming(
        original: String,
        cues: List<Cue>,
        value: (Cue) -> String
    ): String {
        val normalized = original.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\n{2,}"))
        var cueIndex = 0
        return blocks.joinToString("\n\n") { block ->
            val lines = block.split('\n').toMutableList()
            val timingIndex = lines.indexOfFirst { it.contains(" --> ") }
            if (timingIndex < 0) return@joinToString block
            val cue = cues.getOrNull(cueIndex) ?: return@joinToString block
            val timing = lines[timingIndex]
            val valueText = value(cue)
            lines.subList(timingIndex + 1, lines.size).clear()
            lines += valueText.replace("\\N", "\n")
            lines[timingIndex] = timing
            cueIndex++
            lines.joinToString("\n")
        }
    }

    private fun parse(text: String, ext: String): List<Cue> {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val detected = ext.lowercase().ifBlank {
            when {
                normalized.startsWith("WEBVTT", true) -> "vtt"
                Regex("(?im)^\\s*Dialogue:").containsMatchIn(normalized) -> "ass"
                Regex("(?is)<SYNC\\s+Start\\s*=").containsMatchIn(normalized) -> "smi"
                Regex("(?is)<tt[ >]|<ttml").containsMatchIn(normalized) -> "ttml"
                Regex("(?m)^\\s*\\[\\d+\\]\\s*\\[\\d+\\]").containsMatchIn(normalized) -> "sub"
                Regex("(?m)^\\s*\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*[-=]+>").containsMatchIn(normalized) -> "srt"
                else -> ""
            }
        }
        return when (detected) {
            "ass", "ssa" -> parseAss(normalized)
            "srt" -> parseTimedText(normalized, "srt")
            "vtt" -> parseTimedText(normalized, "vtt")
            "smi", "sami" -> parseSmi(normalized)
            "sbv" -> parseSbv(normalized)
            "sub", "mpl2" -> parseSubLike(normalized, detected)
            "ttml", "xml" -> parseTtml(normalized)
            else -> emptyList()
        }
    }

    private fun parseTimedText(text: String, kind: String): List<Cue> {
        val lines = text.lines()
        val result = mutableListOf<Cue>()
        var i = 0
        var index = 0
        val timing = Regex("^\\s*([^\\s]+)\\s*-->\\s*([^\\s]+)(?:\\s+.*)?$")
        while (i < lines.size) {
            val m = timing.find(lines[i])
            if (m == null) { i++; continue }
            val start = parseTime(m.groupValues[1])
            val end = parseTime(m.groupValues[2])
            if (start < 0 || end < 0 || end <= start) { i++; continue }
            val body = buildList {
                var j = i + 1
                while (j < lines.size && !timing.matches(lines[j])) {
                    val line = lines[j]
                    if (line.isBlank()) break
                    if (!(kind == "vtt" && line.trim().startsWith("NOTE", true))) add(line)
                    j++
                }
            }.joinToString("\\N").trim()
            if (body.isNotBlank()) result += Cue(start, end, body, kind, index++)
            i++
            while (i < lines.size && !timing.matches(lines[i]) && lines[i].isNotBlank()) i++
        }
        return result
    }

    private fun parseAss(text: String): List<Cue> {
        val all = mutableListOf<Cue>()
        val japanese = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff々ー]")
        var ordinal = 0
        text.lines().forEach { line ->
            if (!line.startsWith("Dialogue:", true)) return@forEach
            val parts = line.substringAfter(':').trimStart().split(',', limit = 10)
            if (parts.size < 10) { ordinal++; return@forEach }
            val start = parseAssTime(parts[1]); val end = parseAssTime(parts[2])
            val body = parts[9].trim()
            if (start >= 0 && end > start && body.isNotBlank() && japanese.containsMatchIn(stripSubtitleMarkup(body))) {
                all += Cue(start, end, body, "ass", ordinal)
            }
            ordinal++
        }
        return all
    }

    private fun parseSmi(text: String): List<Cue> {
        val matches = Regex("(?is)<SYNC\\s+Start\\s*=\\s*(\\d+)\\s*>(.*?)(?=<SYNC\\s+Start|</BODY>|</SAMI>)").findAll(text).toList()
        return matches.mapIndexedNotNull { i, m ->
            val start = m.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
            val next = matches.getOrNull(i + 1)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: start + 4000L
            val body = m.groupValues[2].replace(Regex("(?is)<br\\s*/?>"), "\\N").replace(Regex("(?is)<[^>]+>"), "").trim()
            if (body.isBlank() || next <= start) null else Cue(start, next, body, "smi", i)
        }
    }

    private fun parseSbv(text: String): List<Cue> {
        val blocks = text.split(Regex("\\n\\s*\\n+"))
        return blocks.mapIndexedNotNull { i, block ->
            val lines = block.lines(); val first = lines.firstOrNull()?.trim() ?: return@mapIndexedNotNull null
            val p = first.split(',', limit = 2); if (p.size != 2) return@mapIndexedNotNull null
            val start = parseTime(p[0]); val end = parseTime(p[1]); val body = lines.drop(1).joinToString("\\N").trim()
            if (start >= 0 && end > start && body.isNotBlank()) Cue(start, end, body, "sbv", i) else null
        }
    }

    private fun parseSubLike(text: String, kind: String): List<Cue> = text.lineSequence().mapIndexedNotNull { i, line ->
        val m = Regex("^\\s*\\[(\\d+)\\]\\s*\\[(\\d+)\\]\\s*(.*)$").find(line) ?: return@mapIndexedNotNull null
        val start = m.groupValues[1].toLongOrNull()?.times(100L) ?: return@mapIndexedNotNull null
        val end = m.groupValues[2].toLongOrNull()?.times(100L) ?: return@mapIndexedNotNull null
        val body = m.groupValues[3].replace("|", "\\N").trim()
        if (end <= start || body.isBlank()) null else Cue(start, end, body, kind, i)
    }.toList()

    private fun parseTtml(text: String): List<Cue> {
        val regex = Regex("(?is)<p\\b([^>]*)>(.*?)</p>")
        return regex.findAll(text).mapIndexedNotNull { i, m ->
            val attrs = m.groupValues[1]; val body = stripSubtitleMarkup(m.groupValues[2]).trim()
            val begin = Regex("(?i)\\bbegin\\s*=\\s*[\\\"']([^\\\"']+)").find(attrs)?.groupValues?.get(1)
            val end = Regex("(?i)\\bend\\s*=\\s*[\\\"']([^\\\"']+)").find(attrs)?.groupValues?.get(1)
            val dur = Regex("(?i)\\bdur\\s*=\\s*[\\\"']([^\\\"']+)").find(attrs)?.groupValues?.get(1)
            val start = parseTtmlTime(begin) ?: return@mapIndexedNotNull null
            val finish = parseTtmlTime(end) ?: dur?.let { start + (parseTtmlTime(it) ?: return@mapIndexedNotNull null) } ?: return@mapIndexedNotNull null
            if (finish <= start || body.isBlank()) null else Cue(start, finish, body, "ttml", i)
        }.toList()
    }

    private fun parseTtmlTime(value: String?): Long? {
        val v = value?.trim() ?: return null
        return when {
            v.matches(Regex("\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?")) -> parseTime(v)
            v.endsWith("ms", true) -> v.dropLast(2).toDoubleOrNull()?.toLong()
            v.endsWith("s", true) -> v.dropLast(1).toDoubleOrNull()?.times(1000L)?.toLong()
            else -> null
        }
    }

    private fun stripSubtitleMarkup(value: String): String = value
        .replace(Regex("(?s)\\{[^{}]*\\}"), "")
        .replace(Regex("(?is)<[^>]+>"), "")
        .replace("\\N", "\n")
        .trim()

    private fun parseTime(value: String): Long {
        val v = value.trim().substringBefore(' ')
        val p = v.split(':')
        return runCatching {
            when (p.size) {
                3 -> ((p[0].toLong() * 3600 + p[1].toLong() * 60) * 1000) + (p[2].replace(',', '.').toDouble() * 1000).toLong()
                2 -> (p[0].toLong() * 60_000) + (p[1].replace(',', '.').toDouble() * 1000).toLong()
                else -> -1L
            }
        }.getOrDefault(-1L)
    }

    private fun parseAssTime(value: String): Long {
        val p = value.trim().split(':')
        return runCatching {
            if (p.size != 3) return@runCatching -1L
            val seconds = p[2].replace(',', '.').toDouble()
            ((p[0].toLong() * 3600 + p[1].toLong() * 60) * 1000 + (seconds * 1000).toLong())
        }.getOrDefault(-1L)
    }
}
