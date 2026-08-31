package com.lilac.anime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Creates a temporary subtitle file with all cue/event times shifted by [offsetMs].
 * Supports WebVTT, SRT and ASS/SSA so the PlayerScreen's existing sync controls
 * work identically for Linkkf VTT and Kairan/Csora ASS subtitles.
 */
suspend fun prepareSyncedSubtitleFile(
    context: Context,
    subtitlePath: String,
    animeId: String,
    episodeNumber: Int,
    offsetMs: Long,
    episodeKey: String = episodeNumber.toString()
): String? = withContext(Dispatchers.IO) {
    if (offsetMs == 0L) return@withContext subtitlePath

    try {
        val lower = subtitlePath.lowercase(Locale.ROOT)
        val extension = when {
            lower.endsWith(".ass") -> "ass"
            lower.endsWith(".ssa") -> "ssa"
            lower.endsWith(".srt") -> "srt"
            lower.endsWith(".vtt") -> "vtt"
            else -> return@withContext null
        }

        val sourceText = readSubtitleText(subtitlePath) ?: return@withContext null
        val adjusted = when (extension) {
            "ass", "ssa" -> shiftAss(sourceText, offsetMs)
            else -> shiftVttOrSrt(sourceText, offsetMs)
        }

        val dir = File(context.filesDir, "synced_subtitles").apply { mkdirs() }
        val safeAnimeId = animeId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeOffset = if (offsetMs >= 0) "p$offsetMs" else "m${-offsetMs}"
        val target = File(
            dir,
            "${safeAnimeId}_${episodeNumber}_${extension}_${safeOffset}.$extension"
        )
        target.writeText(adjusted, Charsets.UTF_8)
        Log.d("Subtitle", "SYNCED_FILE path=${target.absolutePath} format=$extension offsetMs=$offsetMs")
        target.absolutePath
    } catch (e: Exception) {
        Log.e("Subtitle", "SYNC_PREPARE_FAILED path=$subtitlePath offsetMs=$offsetMs", e)
        null
    }
}

private fun readSubtitleText(path: String): String? {
    return if (path.startsWith("http://") || path.startsWith("https://")) {
        val connection = (URL(path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LilacAnime/1.0")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    } else {
        File(path.removePrefix("file://"))
            .takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)
    }
}

private fun shiftVttOrSrt(sourceText: String, offsetMs: Long): String {
    val timestampLine = Regex(
        """(\d{1,2}:)?\d{2}:\d{2}[.,]\d{3}\s*-->\s*(\d{1,2}:)?\d{2}:\d{2}[.,]\d{3}"""
    )
    val timePart = Regex("""\d{1,2}:\d{2}:\d{2}[.,]\d{3}""")

    return sourceText.lineSequence().joinToString("\n") { line ->
        if (!timestampLine.containsMatchIn(line)) line
        else timePart.replace(line) { match ->
            formatClock(parseClock(match.value) + offsetMs, '.', 3)
        }
    }
}

private fun shiftAss(sourceText: String, offsetMs: Long): String {
    return sourceText.lineSequence().mapNotNull { line ->
        val trimmed = line.trimStart()
        val isEvent = trimmed.startsWith("Dialogue:", true) || trimmed.startsWith("Comment:", true)
        if (!isEvent) return@mapNotNull line

        val colon = line.indexOf(':')
        if (colon < 0) return@mapNotNull line
        val prefix = line.substring(0, colon + 1)
        val fields = line.substring(colon + 1).split(',', limit = 10).toMutableList()
        if (fields.size < 3) return@mapNotNull line

        val start = parseAssClock(fields[1]) ?: return@mapNotNull line
        val end = parseAssClock(fields[2]) ?: return@mapNotNull line
        val shiftedEnd = end + offsetMs
        if (shiftedEnd <= 0L) return@mapNotNull null

        fields[1] = formatAssClock((start + offsetMs).coerceAtLeast(0L))
        fields[2] = formatAssClock(shiftedEnd.coerceAtLeast(0L))
        prefix + fields.joinToString(",")
    }.joinToString("\n")
}

private fun parseClock(value: String): Long {
    val normalized = value.trim().replace(',', '.')
    val parts = normalized.split(':')
    if (parts.size != 3) return 0L
    val seconds = parts[2].split('.', limit = 2)
    val fraction = seconds.getOrNull(1).orEmpty().padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    return (parts[0].toLongOrNull() ?: 0L) * 3_600_000L +
        (parts[1].toLongOrNull() ?: 0L) * 60_000L +
        (seconds[0].toLongOrNull() ?: 0L) * 1_000L + fraction
}

private fun formatClock(value: Long, separator: Char, fractionDigits: Int): String {
    val safe = value.coerceAtLeast(0L)
    val h = safe / 3_600_000L
    val m = (safe % 3_600_000L) / 60_000L
    val s = (safe % 60_000L) / 1_000L
    val ms = safe % 1_000L
    val fraction = if (fractionDigits == 2) ms / 10L else ms
    return if (fractionDigits == 2) {
        String.format(Locale.ROOT, "%d:%02d:%02d%c%02d", h, m, s, separator, fraction)
    } else {
        String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", h, m, s, separator, fraction)
    }
}

private fun parseAssClock(value: String): Long? {
    val match = Regex("""^\s*(\d+):(\d{2}):(\d{2})[.](\d{1,3})\s*$""").matchEntire(value)
        ?: return null
    val h = match.groupValues[1].toLong()
    val m = match.groupValues[2].toLong()
    val s = match.groupValues[3].toLong()
    val cs = match.groupValues[4].padEnd(2, '0').take(2).toLong()
    return h * 3_600_000L + m * 60_000L + s * 1_000L + cs * 10L
}

private fun formatAssClock(value: Long): String = formatClock(value, '.', 2)
