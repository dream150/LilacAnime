package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import com.lilac.anime.Anime
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Jimaku Japanese subtitle resolver.
 *
 * Jimaku's web UI performs AniList-ID matching client-side on the homepage.
 * There is no search API in the captured flow, so we reproduce that same
 * matching locally:
 *
 *   GET / -> data-extra.anilist_id -> /entry/{id}
 *   GET /entry/{id} -> file links
 *   GET /entry/{id}/download/{filename} -> subtitle bytes
 *
 * The Re:ANIME AniList ID is therefore the primary key; title translation or
 * NamuWiki title resolution is not involved.
 */
object JimakuSubtitleService {
    private const val TAG = "Jimaku"
    private const val BASE_URL = "https://jimaku.cc"
    private const val CACHE_DIR = "jimaku_subtitles"
    private const val PREF = "jimaku_entry_cache"
    private const val MAX_SUBTITLE_BYTES = 12 * 1024 * 1024L

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    data class SubtitleOption(
        val name: String,
        val url: String,
        val format: String,
        val size: Long,
        val cachedPath: String? = null,
        val isBundle: Boolean = false,
        val bundleStartEpisode: Int? = null,
        val bundleEndEpisode: Int? = null
    )

    private data class FileCandidate(
        val name: String,
        val url: String,
        val size: Long = 0L
    )

    suspend fun listEpisodeSubtitles(
        context: Context,
        anime: Anime,
        episodeNumber: Int,
        episodeKey: String = episodeNumber.toString()
    ): List<SubtitleOption> = withContext(Dispatchers.IO) {
        val anilistId = anime.anilistId?.takeIf { it > 0 } ?: return@withContext emptyList()
        if (episodeNumber <= 0) return@withContext emptyList()
        runCatching {
            val entryId = resolveEntryId(context, anilistId) ?: return@runCatching emptyList()
            scoreEpisodeFiles(loadEntryFiles(entryId), episodeNumber, anime.title)
                .map { candidate ->
                    val ext = candidate.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val range = episodeRange(candidate.name)
                    SubtitleOption(
                        name = candidate.name,
                        url = candidate.url,
                        format = ext,
                        size = candidate.size,
                        cachedPath = cachedPathFor(context, anilistId, episodeKey, candidate),
                        isBundle = range != null,
                        bundleStartEpisode = range?.first,
                        bundleEndEpisode = range?.second
                    )
                }
        }.getOrElse {
            Log.w(TAG, "LIST_FAILED anilistId=$anilistId episode=$episodeNumber", it)
            emptyList()
        }
    }

    suspend fun downloadSelectedSubtitle(
        context: Context,
        anime: Anime,
        episodeNumber: Int,
        episodeKey: String,
        option: SubtitleOption
    ): String? = withContext(Dispatchers.IO) {
        val anilistId = anime.anilistId?.takeIf { it > 0 } ?: return@withContext null
        if (option.cachedPath?.let { File(it).isFile } == true) {
            SubtitleStore.save(context, anime.id, episodeKey, episodeNumber, "jimaku", option.cachedPath)
            return@withContext option.cachedPath
        }
        val entryId = Regex("""/entry/(\d+)""").find(option.url)?.groupValues?.getOrNull(1)
            ?: resolveEntryId(context, anilistId)
            ?: return@withContext null
        val candidate = FileCandidate(option.name, option.url, option.size)
        downloadSubtitle(
            context = context,
            anilistId = anilistId,
            episodeKey = episodeKey,
            episodeNumber = episodeNumber,
            candidate = candidate,
            entryId = entryId,
            bundleRange = if (option.isBundle) {
                option.bundleStartEpisode!!..option.bundleEndEpisode!!
            } else null
        )?.also { saved ->
            SubtitleStore.save(context, anime.id, episodeKey, episodeNumber, "jimaku", saved)
        }
    }

    suspend fun findSubtitle(
        context: Context,
        anime: Anime,
        episodeNumber: Int,
        episodeKey: String = episodeNumber.toString()
    ): KairanSubtitleResult? {
        val anilistId = anime.anilistId?.takeIf { it > 0 } ?: run {
            Log.d(TAG, "ANI_LIST_ID_MISSING title=${anime.title}")
            return null
        }
        if (episodeNumber <= 0) return null

        return withContext(Dispatchers.IO) {
            val cached = findCached(context, anilistId, episodeKey, episodeNumber)
            if (cached != null) {
                Log.d(TAG, "CACHE_HIT anilistId=$anilistId episode=$episodeKey")
                return@withContext KairanSubtitleResult.DirectFile(cached)
            }

            try {
                val entryId = resolveEntryId(context, anilistId)
                if (entryId == null) {
                    Log.d(TAG, "ENTRY_NOT_FOUND anilistId=$anilistId title=${anime.title}")
                    return@withContext null
                }

                val candidates = loadEntryFiles(entryId)
                val selected = selectEpisodeFile(
                    candidates = candidates,
                    episodeNumber = episodeNumber,
                    animeTitle = anime.title
                ) ?: run {
                    Log.d(
                        TAG,
                        "EPISODE_FILE_NOT_FOUND anilistId=$anilistId entry=$entryId episode=$episodeNumber files=${candidates.size}"
                    )
                    return@withContext null
                }

                val saved = downloadSubtitle(
                    context = context,
                    anilistId = anilistId,
                    episodeKey = episodeKey,
                    episodeNumber = episodeNumber,
                    candidate = selected,
                    entryId = entryId
                )

                if (saved != null) {
                    SubtitleStore.save(
                        context,
                        anime.id,
                        episodeKey,
                        episodeNumber,
                        "jimaku",
                        saved
                    )
                    Log.d(
                        TAG,
                        "SUBTITLE_SAVED anilistId=$anilistId entry=$entryId episode=$episodeKey " +
                            "format=${File(saved).extension} size=${File(saved).length()}"
                    )
                    KairanSubtitleResult.DirectFile(saved)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "LOOKUP_FAILED anilistId=$anilistId episode=$episodeNumber", e)
                null
            }
        }
    }

    private fun resolveEntryId(context: Context, anilistId: Int): String? {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.getString(anilistId.toString(), null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val html = getText("$BASE_URL/")
        val document = Jsoup.parse(html, BASE_URL)

        for (entry in document.select("div.entry[data-extra]")) {
            val extra = entry.attr("data-extra")
            val json = runCatching { JSONObject(extra) }.getOrNull() ?: continue
            if (json.optInt("anilist_id", -1) != anilistId) continue

            val href = entry.selectFirst("a[href*=/entry/]")?.attr("href").orEmpty()
            val entryId = Regex("""/entry/(\d+)""").find(href)?.groupValues?.getOrNull(1)
                ?: continue

            prefs.edit().putString(anilistId.toString(), entryId).apply()
            Log.d(TAG, "ENTRY_RESOLVED anilistId=$anilistId entry=$entryId")
            return entryId
        }

        return null
    }

    private fun loadEntryFiles(entryId: String): List<FileCandidate> {
        val url = "$BASE_URL/entry/$entryId"
        val document = Jsoup.parse(getText(url), url)

        return document.select("div.entry[data-extra]").mapNotNull { entry ->
            val json = runCatching { JSONObject(entry.attr("data-extra")) }.getOrNull()
            val name = json?.optString("name").orEmpty()
                .ifBlank { entry.selectFirst("a.file-name")?.text().orEmpty() }
                .trim()
            val href = json?.optString("url").orEmpty()
                .ifBlank { entry.selectFirst("a.file-name")?.attr("href").orEmpty() }
                .trim()
            if (name.isBlank() || href.isBlank()) return@mapNotNull null

            val lower = name.lowercase(Locale.ROOT)
            if (lower.substringAfterLast('.', "") !in SUPPORTED_FORMATS) {
                return@mapNotNull null
            }

            val absolute = if (href.startsWith("http", true)) href else "$BASE_URL${if (href.startsWith("/")) href else "/$href"}"
            FileCandidate(
                name = name,
                url = absolute,
                size = json?.optLong("size", 0L) ?: 0L
            )
        }.distinctBy { it.url }
    }

    private fun selectEpisodeFile(
        candidates: List<FileCandidate>,
        episodeNumber: Int,
        animeTitle: String
    ): FileCandidate? = scoreEpisodeFiles(candidates, episodeNumber, animeTitle).firstOrNull()

    private fun scoreEpisodeFiles(
        candidates: List<FileCandidate>,
        episodeNumber: Int,
        animeTitle: String
    ): List<FileCandidate> {
        data class Scored(val candidate: FileCandidate, val score: Int)

        return candidates.mapNotNull { candidate ->
            val name = candidate.name.lowercase(Locale.ROOT)
            val ext = name.substringAfterLast('.', "")
            if (ext !in SUPPORTED_FORMATS) return@mapNotNull null

            val range = episodeRange(name)
            val episodeScore = if (range != null) {
                if (episodeNumber !in range.first..range.second) return@mapNotNull null
                45
            } else {
                exactEpisodeScore(name, episodeNumber)
            }
            if (episodeScore <= 0) return@mapNotNull null

            val formatScore = when (ext) {
                "ass" -> 100
                "ssa" -> 96
                "srt" -> 90
                "vtt" -> 86
                "smi", "sami" -> 84
                "sub" -> 80
                "mpl2", "mpsub", "jacosub", "aqt", "pjs", "rt", "sbv" -> 76
                else -> 70
            }
            val assQuality = when {
                ext in setOf("ass", "ssa") && name.contains("furigana") -> 8
                ext in setOf("ass", "ssa") && name.contains(".ja") -> 6
                ext in setOf("ass", "ssa") -> 3
                name.contains(".ja") || name.contains("[cc]") -> 2
                else -> 0
            }
            val seasonBonus = detectSeasonNumber(animeTitle)?.let { season ->
                if (Regex("""(?:^|[^a-z0-9])s0*$season(?:e|[-_ ])""").containsMatchIn(name)) 12 else 0
            } ?: 0
            Scored(candidate, episodeScore + formatScore + assQuality + seasonBonus)
        }.sortedWith(
            compareByDescending<Scored> { it.score }
                .thenByDescending { it.candidate.size }
                .thenBy { it.candidate.name.lowercase(Locale.ROOT) }
        ).map { it.candidate }
    }

    /** Detect an explicit season number from an anime title.
     * Only explicit season markers are considered so unrelated numbers in
     * titles (years, episode counts, etc.) do not affect Jimaku ranking.
     */
    private fun detectSeasonNumber(animeTitle: String): Int? {
        val title = animeTitle.trim()
        if (title.isEmpty()) return null

        val patterns = listOf(
            Regex("""\bseason\s*(\d+)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bs(?:eason)?\s*0*(\d+)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d+)(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE),
            Regex("""\bpart\s+(\d+)\b""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(title) ?: continue
            val season = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            if (season in 1..99) return season
        }
        return null
    }

    private fun exactEpisodeScore(name: String, episodeNumber: Int): Int {
        val ep = episodeNumber.toString().padStart(2, '0')
        val patterns = listOf(
            Regex("""(?:^|[^a-z0-9])s\d{1,2}e0*$ep(?:[^0-9]|$)"""),
            Regex("""(?:^|[^a-z0-9])(?:ep|episode|e)0*$ep(?:[^0-9]|$)"""),
            Regex("""(?:^|[^a-z0-9])0*$ep(?:[^a-z0-9]|$)""")
        )
        return when {
            patterns[0].containsMatchIn(name) -> 65
            patterns[1].containsMatchIn(name) -> 58
            patterns[2].containsMatchIn(name) -> 50
            Regex("""(?:^|[^a-z0-9])0*$ep\s*(?:화|회|편|話)(?:$|[^a-z0-9])""").containsMatchIn(name) -> 55
            else -> 0
        }
    }

    private fun episodeRange(name: String): Pair<Int, Int>? {
        val patterns = listOf(
            Regex("""(?:s\d{1,2}e)0*(\d{1,3})\s*[-~〜–—]\s*(?:s\d{1,2}e)?0*(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|[^a-z0-9])(?:e|ep|episode)?0*(\d{1,3})\s*[-~〜–—]\s*(?:e|ep|episode)?0*(\d{1,3})(?:$|[^0-9])""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val m = pattern.find(name) ?: continue
            val a = m.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            val b = m.groupValues.getOrNull(2)?.toIntOrNull() ?: continue
            if (a < b) return a to b
        }
        return null
    }

    private fun containsEpisodeRange(name: String): Boolean = episodeRange(name) != null


    private fun cachedPathFor(
        context: Context,
        anilistId: Int,
        episodeKey: String,
        candidate: FileCandidate
    ): String? {
        val ext = candidate.name.substringAfterLast('.', "srt").lowercase(Locale.ROOT)
            .let { if (it in SUPPORTED_FORMATS) it else "srt" }
        val output = File(
            context.filesDir,
            "$CACHE_DIR/$anilistId/ep_${safeEpisodeKey(episodeKey)}_${sha256(candidate.url).take(12)}.$ext"
        )
        return output.takeIf { it.isFile && it.length() > 0L }?.absolutePath
    }

    private fun findCached(
        context: Context,
        anilistId: Int,
        episodeKey: String,
        episodeNumber: Int
    ): String? {
        val dir = File(context.filesDir, "$CACHE_DIR/$anilistId")
        if (!dir.isDirectory) return null

        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in SUPPORTED_FORMATS }
            ?.filter { SubtitleStore.subtitleMatchesEpisode(it.absolutePath, episodeKey, episodeNumber) }
            ?.sortedByDescending { it.extension.equals("ass", true) }
            ?.firstOrNull()
            ?.absolutePath
    }

    private fun downloadSubtitle(
        context: Context,
        anilistId: Int,
        episodeKey: String,
        episodeNumber: Int,
        candidate: FileCandidate,
        entryId: String,
        bundleRange: IntRange? = null
    ): String? {
        val request = Request.Builder()
            .url(candidate.url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/plain,text/*,application/octet-stream;q=0.9,*/*;q=0.5")
            .header("Referer", "$BASE_URL/entry/$entryId")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Jimaku download HTTP ${response.code}")
            }

            val body = response.body ?: return null
            if (body.contentLength() > MAX_SUBTITLE_BYTES) {
                Log.w(TAG, "SUBTITLE_TOO_LARGE size=${body.contentLength()}")
                return null
            }

            val bytes = body.bytes()
            if (bytes.isEmpty() || bytes.size.toLong() > MAX_SUBTITLE_BYTES) return null

            val extension = candidate.name.substringAfterLast('.', "srt")
                .lowercase(Locale.ROOT)
                .let { if (it in SUPPORTED_FORMATS) it else "srt" }

            val key = safeEpisodeKey(episodeKey)
            val hash = sha256(candidate.url + "#" + episodeNumber).take(12)
            val dir = File(context.filesDir, "$CACHE_DIR/$anilistId").apply { mkdirs() }
            val output = File(dir, "ep_${key}_$hash.$extension")

            val finalBytes = if (bundleRange != null) {
                extractBundleEpisode(bytes, extension, bundleRange, episodeNumber)
                    ?: run {
                        Log.w(TAG, "BUNDLE_EXTRACT_FAILED file=${candidate.name} episode=$episodeNumber range=$bundleRange")
                        return null
                    }
            } else bytes
            output.writeBytes(finalBytes)

            if (!SubtitleStore.subtitleMatchesEpisode(output.absolutePath, episodeKey, episodeNumber)) {
                output.delete()
                Log.w(TAG, "EPISODE_VALIDATION_FAILED file=${candidate.name} episode=$episodeNumber")
                return null
            }

            return output.absolutePath
        }
    }

    /**
     * Splits a multi-episode subtitle into the requested episode.
     * Jimaku bundle files are commonly concatenated subtitle streams. The
     * individual episode stream normally starts its timestamps from zero, so
     * a timestamp reset is a reliable boundary while preserving the original
     * format/style headers.
     */
    private fun extractBundleEpisode(
        bytes: ByteArray,
        extension: String,
        range: IntRange,
        requestedEpisode: Int
    ): ByteArray? {
        if (requestedEpisode !in range) return null
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        return when (extension.lowercase(Locale.ROOT)) {
            "ass", "ssa" -> extractAssBundle(text, requestedEpisode - range.first)
            "srt" -> extractSrtBundle(text, requestedEpisode - range.first)
            "vtt" -> extractVttBundle(text, requestedEpisode - range.first)
            "smi", "sami" -> extractSmiBundle(text, requestedEpisode - range.first)
            else -> extractGenericTimedBundle(text, requestedEpisode - range.first)
        }?.toByteArray(Charsets.UTF_8)
    }

    private fun <T> splitByReset(items: List<T>, timeOf: (T) -> Long): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        val result = mutableListOf<MutableList<T>>()
        var current = mutableListOf<T>()
        var previous = Long.MIN_VALUE
        for (item in items) {
            val time = timeOf(item)
            if (current.isNotEmpty() && time >= 0 && previous >= 0 && time + 500 < previous) {
                result += current
                current = mutableListOf()
            }
            current += item
            if (time >= 0) previous = time
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private data class TimedBlock(val raw: String, val start: Long)

    private fun extractSrtBundle(text: String, index: Int): String? {
        val blocks = Regex("(?ms)(?:^|\\n)\\s*\\d+\\s*\\n\\s*(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})\\s*-->.*?(?=\\n\\s*\\d+\\s*\\n\\s*\\d{2}:|\\z)")
            .findAll(text).map { m ->
                val raw = m.value.trimStart('\n')
                TimedBlock(raw, parseSrtTime(m.groupValues[1]))
            }.toList()
        if (blocks.isEmpty()) return null
        val parts = splitByReset(blocks) { it.start }
        val chosen = parts.getOrNull(index) ?: return null
        return chosen.mapIndexed { i, b ->
            val normalized = b.raw.trim()
            val lines = normalized.lines().toMutableList()
            if (lines.firstOrNull()?.trim()?.matches(Regex("\\d+")) == true) lines[0] = (i + 1).toString()
            lines.joinToString("\\n")
        }.joinToString("\\n\\n", postfix = "\\n")
    }

    private fun extractVttBundle(text: String, index: Int): String? {
        val blocks = Regex("(?ms)(?:^|\\n)\\s*((?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3})\\s*-->.*?(?=\\n\\s*(?:\\d{2}:)?\\d{2}:|\\z)")
            .findAll(text).map { m -> TimedBlock(m.value.trimStart('\n'), parseVttTime(m.groupValues[1])) }.toList()
        if (blocks.isEmpty()) return null
        val parts = splitByReset(blocks) { it.start }
        val chosen = parts.getOrNull(index) ?: return null
        return "WEBVTT\\n\\n" + chosen.joinToString("\\n\\n") { it.raw.trim() } + "\\n"
    }

    private fun extractAssBundle(text: String, index: Int): String? {
        val lines = text.lines()
        val eventsStart = lines.indexOfFirst { it.trim().equals("[Events]", true) }
        if (eventsStart < 0) return null
        val dialogue = lines.mapIndexedNotNull { lineIndex, line ->
            if (!line.startsWith("Dialogue:", true)) return@mapIndexedNotNull null
            val payload = line.substringAfter(':', "").trim()
            val start = payload.split(',', limit = 3).getOrNull(1)?.trim() ?: return@mapIndexedNotNull null
            TimedBlock("__LINE__$lineIndex", parseAssTime(start))
        }
        if (dialogue.isEmpty()) return null
        val parts = splitByReset(dialogue) { it.start }
        val chosen = parts.getOrNull(index) ?: return null
        val chosenLines = chosen.mapNotNull { block ->
            val lineIndex = block.raw.removePrefix("__LINE__").toIntOrNull() ?: return@mapNotNull null
            lines.getOrNull(lineIndex)
        }.toSet()
        if (chosenLines.isEmpty()) return null
        val out = StringBuilder()
        for (line in lines) {
            if (!line.startsWith("Dialogue:", true) || line in chosenLines) out.append(line).append('\n')
        }
        return out.toString()
    }

    private fun extractSmiBundle(text: String, index: Int): String? {
        val matches = Regex("(?is)<sync\\b[^>]*\\bstart\\s*=\\s*['\"]?(\\d+)['\"]?[^>]*>.*?(?=<sync\\b|</body>|</sami>|\\z)")
            .findAll(text).map { m -> TimedBlock(m.value, m.groupValues[1].toLongOrNull() ?: -1L) }.toList()
        if (matches.isEmpty()) return null
        val parts = splitByReset(matches) { it.start }
        val chosen = parts.getOrNull(index) ?: return null
        val header = text.substringBefore(matches.first().raw)
        return header + chosen.joinToString("\\n") { it.raw } + "\\n</body>\\n</sami>"
    }

    private fun extractGenericTimedBundle(text: String, index: Int): String? {
        val lines = text.lines()
        val timed = lines.mapIndexedNotNull { i, line ->
            val time = Regex("(?:^|\\[)(\\d{1,2}:\\d{2}:\\d{2}(?:[.,]\\d{1,3})?)").find(line)?.groupValues?.get(1)
                ?: Regex("^(\\d+)\\s+.*$").find(line)?.groupValues?.get(1)
            time?.let { TimedBlock("$i", parseLooseTime(it)) }
        }
        if (timed.isEmpty()) return null
        val parts = splitByReset(timed) { it.start }
        val chosen = parts.getOrNull(index) ?: return null
        val indices = chosen.mapNotNull { it.raw.toIntOrNull() }.toSet()
        return lines.filterIndexed { i, _ -> i in indices }.joinToString("\\n", postfix = "\\n")
    }

    private fun parseSrtTime(value: String): Long = parseClock(value.replace(',', '.'))
    private fun parseVttTime(value: String): Long = parseClock(value)
    private fun parseAssTime(value: String): Long {
        val p = value.trim().split(':')
        if (p.size != 3) return -1
        val sec = p[2].replace(',', '.').toDoubleOrNull() ?: return -1
        return ((p[0].toLongOrNull() ?: return -1) * 3600000L) +
            ((p[1].toLongOrNull() ?: return -1) * 60000L) + (sec * 1000.0).toLong()
    }
    private fun parseClock(value: String): Long {
        val p = value.trim().split(':')
        if (p.size !in 2..3) return -1
        val secPart = p.last().toDoubleOrNull() ?: return -1
        val min = p[p.size - 2].toLongOrNull() ?: return -1
        val hour = if (p.size == 3) p[0].toLongOrNull() ?: return -1 else 0L
        return hour * 3600000L + min * 60000L + (secPart * 1000.0).toLong()
    }
    private fun parseLooseTime(value: String): Long = value.toLongOrNull() ?: parseClock(value.replace(',', '.'))

    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.8")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Jimaku HTTP ${response.code}")
            }
            return response.body?.string().orEmpty().also {
                if (it.isBlank()) throw IOException("Jimaku empty response")
            }
        }
    }

    private fun safeEpisodeKey(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val SUPPORTED_FORMATS = setOf(
        "ass", "ssa", "srt", "vtt", "smi", "sami",
        "sub", "mpl2", "mpsub", "jacosub", "aqt", "pjs", "rt", "sbv"
    )

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
}
