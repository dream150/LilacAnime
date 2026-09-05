package com.lilac.anime.data.offline

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import com.lilac.anime.Episode
import com.lilac.anime.LilacApplication
import com.lilac.anime.offlineDownloadId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI

/**
 * Compatibility bridge for Media3 downloads created before the mpv-native
 * downloader existed.
 *
 * Important: the old download is NOT converted to MP4. We reconstruct a fully
 * local HLS playlist and its cached objects on disk, preserving TS/fMP4 data.
 * This avoids the MediaExtractor/MediaMuxer codec/container problems that made
 * older offline episodes audio-only or completely unplayable.
 */
@OptIn(UnstableApi::class)
object LegacyMedia3OfflineBridge {
    private const val COPY_BUFFER_SIZE = 4 * 1024 * 1024

    private data class Variant(val url: String, val bandwidth: Long)
    private data class Segment(val url: String, val duration: Double, val raw: String)

    suspend fun migrateIfNeeded(
        context: Context,
        animeId: String,
        episode: Episode
    ): File? = withContext(Dispatchers.IO) {
        MpvOfflineStore.completedPath(context, animeId, episode.id)?.let { return@withContext File(it) }

        val download = findCompletedDownload(animeId, episode) ?: run {
            android.util.Log.w(TAG, "NO_LEGACY_DOWNLOAD anime=$animeId episode=${episode.id}")
            return@withContext null
        }

        val cache = LilacApplication.downloadCache
        val requestUrl = download.request.uri.toString()
        android.util.Log.d(TAG, "LEGACY_START id=${download.request.id} url=$requestUrl cacheKeys=${cache.keys.size}")

        val masterKey = findCachedKey(cache, requestUrl, playlist = true)
        if (masterKey == null) {
            android.util.Log.w(TAG, "MASTER_NOT_CACHED url=$requestUrl keys=${cache.keys.take(20)}")
            return@withContext null
        }

        val master = readCachedText(cache, masterKey)
            ?: return@withContext null

        val media: Pair<String, String> = if (master.contains("#EXT-X-STREAM-INF")) {
            val variants = parseVariants(master, requestUrl)
            val candidates = variants.mapNotNull { v ->
                findCachedKey(cache, v.url, playlist = true)?.let { key -> Triple(v, key, readCachedText(cache, key)) }
            }.filter { it.third != null }
            val selected = candidates.maxByOrNull { it.first.bandwidth } ?: run {
                android.util.Log.w(TAG, "NO_CACHED_VARIANT variants=${variants.size}")
                return@withContext null
            }
            selected.first.url to selected.third!!
        } else {
            requestUrl to master
        }

        val mediaUrl = media.first
        val playlist = media.second
        if (playlist.contains("#EXT-X-KEY") && !playlist.contains("METHOD=NONE")) {
            android.util.Log.w(TAG, "ENCRYPTED_LEGACY_HLS url=$mediaUrl")
            return@withContext null
        }

        val segments = parseSegments(playlist, mediaUrl)
        if (segments.isEmpty()) {
            android.util.Log.w(TAG, "NO_SEGMENTS url=$mediaUrl")
            return@withContext null
        }

        val initUrl = parseInit(playlist, mediaUrl)
        val dir = MpvOfflineStore.episodeDir(context, animeId, episode.id).apply { mkdirs() }
        val partsDir = File(dir, "legacy_parts").apply { mkdirs() }

        val localNames = ArrayList<String>(segments.size)
        for ((index, segment) in segments.withIndex()) {
            val key = findCachedKey(cache, segment.url, playlist = false)
            if (key == null) {
                android.util.Log.w(TAG, "SEGMENT_NOT_CACHED index=$index url=${segment.url}")
                partsDir.deleteRecursively()
                return@withContext null
            }
            val ext = when {
                segment.url.substringBefore('?').substringBefore('#').endsWith(".m4s", true) -> "m4s"
                key.substringBefore('?').substringBefore('#').endsWith(".m4s", true) -> "m4s"
                else -> "ts"
            }
            val name = "seg_%06d.%s".format(index, ext)
            val target = File(partsDir, name)
            if (!target.isFile || target.length() == 0L) {
                FileOutputStream(target).use { copyCachedEntry(cache, key, it) }
            }
            if (!target.isFile || target.length() == 0L) {
                partsDir.deleteRecursively()
                return@withContext null
            }
            localNames += name
        }

        val initName = initUrl?.let { "init.mp4" }
        if (initUrl != null) {
            val key = findCachedKey(cache, initUrl, playlist = false)
            if (key == null) {
                android.util.Log.w(TAG, "INIT_NOT_CACHED url=$initUrl")
                partsDir.deleteRecursively()
                return@withContext null
            }
            val initFile = File(partsDir, initName!!)
            if (!initFile.isFile || initFile.length() == 0L) {
                FileOutputStream(initFile).use { copyCachedEntry(cache, key, it) }
            }
        }

        val finalInitName = initName
        finalInitName?.let { name ->
            File(partsDir, name).copyTo(File(dir, name), overwrite = true)
        }
        localNames.forEach { name ->
            File(partsDir, name).copyTo(File(dir, name), overwrite = true)
        }

        val fallback = MpvOfflineStore.fallbackPlaylist(context, animeId, episode.id)
        buildLocalPlaylist(fallback, playlist, segments, localNames, finalInitName)
        partsDir.deleteRecursively()

        val result = fallback.takeIf { it.isFile && it.length() > 0L }
        android.util.Log.d(TAG, "LEGACY_DONE id=${download.request.id} segments=${segments.size} init=${initUrl != null} path=${result?.absolutePath}")
        result
    }

    private fun findCompletedDownload(animeId: String, episode: Episode): androidx.media3.exoplayer.offline.Download? {
        val cursor = runCatching { LilacApplication.downloadManager.downloadIndex.getDownloads() }.getOrNull() ?: return null
        return try {
            val exact = offlineDownloadId(animeId, episode)
            val ids = listOf(exact, episode.id, "${animeId}_${episode.number}", "${animeId}::${episode.number}")
            var fallback: androidx.media3.exoplayer.offline.Download? = null
            while (cursor.moveToNext()) {
                val d = cursor.download
                if (d.state != androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) continue
                if (d.request.id == exact) {
                    fallback = d
                    break
                }
                if (d.request.id in ids) fallback = d
            }
            fallback
        } finally {
            cursor.close()
        }
    }

    /**
     * CacheKeyFactory in the old DownloadManager normally used the request URI,
     * but we still need to tolerate normalized/query-stripped keys from older
     * builds. Selection is deliberately scored rather than taking the first
     * path match, because HLS playlists often reuse the same filename.
     */
    private fun findCachedKey(cache: Cache, requested: String, playlist: Boolean): String? {
        val keys = cache.keys
        if (keys.contains(requested) && (!playlist || isPlaylistKey(requested))) return requested

        val requestedUri = runCatching { URI(requested) }.getOrNull()
        val requestedPath = requestedUri?.path ?: requested.substringBefore('?').substringBefore('#')
        val requestedName = requestedPath.substringAfterLast('/')

        data class Candidate(val key: String, val score: Int, val bytes: Long)
        val candidates = keys.mapNotNull { key ->
            if (playlist && !isPlaylistKey(key)) return@mapNotNull null
            val uri = runCatching { URI(key) }.getOrNull()
            val path = uri?.path ?: key.substringBefore('?').substringBefore('#')
            val name = path.substringAfterLast('/')
            if (path.isBlank()) return@mapNotNull null

            var score = 0
            if (path == requestedPath) score += 1000
            if (uri?.host != null && requestedUri?.host != null && uri.host == requestedUri.host) score += 100
            if (name == requestedName) score += 50
            if (path.endsWith(requestedPath)) score += 25
            if (requestedPath.endsWith(path)) score += 20
            if (score == 0) return@mapNotNull null
            Candidate(key, score, cachedBytes(cache, key))
        }
        return candidates.maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { it.bytes })?.key
    }

    private fun isPlaylistKey(key: String): Boolean =
        key.substringBefore('?').substringBefore('#').endsWith(".m3u8", true)

    private fun cachedBytes(cache: Cache, key: String): Long =
        cache.getCachedSpans(key).sumOf { it.length.coerceAtLeast(0L) }

    private fun readCachedText(cache: Cache, key: String): String? = runCatching {
        val spans = cache.getCachedSpans(key).sortedBy { it.position }
        if (spans.isEmpty()) return@runCatching null
        val out = StringBuilder()
        var expected = 0L
        for (span in spans) {
            val file = span.file ?: return@runCatching null
            if (span.position != expected) return@runCatching null
            file.inputStream().bufferedReader(Charsets.UTF_8).use { out.append(it.readText()) }
            expected += span.length
        }
        out.toString()
    }.getOrNull()

    private fun copyCachedEntry(cache: Cache, key: String, output: java.io.OutputStream) {
        val spans = cache.getCachedSpans(key).sortedBy { it.position }
        check(spans.isNotEmpty()) { "empty cache entry: $key" }
        var expected = 0L
        for (span in spans) {
            val file = span.file ?: error("missing cache file: $key")
            check(span.position == expected) { "incomplete cache entry: $key" }
            file.inputStream().buffered(COPY_BUFFER_SIZE).use { input ->
                input.copyTo(output, COPY_BUFFER_SIZE)
            }
            expected += span.length
        }
    }

    private fun parseVariants(text: String, base: String): List<Variant> {
        val lines = text.lines().map(String::trim)
        return lines.mapIndexedNotNull { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val bw = Regex("(?:AVERAGE-)?BANDWIDTH=(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: return@mapIndexedNotNull null
            Variant(resolveUrl(base, next), bw)
        }
    }

    private fun parseInit(text: String, base: String): String? {
        val line = text.lineSequence().firstOrNull { it.trim().startsWith("#EXT-X-MAP:") } ?: return null
        val uri = Regex("URI=\\\"([^\\\"]+)\\\"").find(line)?.groupValues?.getOrNull(1) ?: return null
        return resolveUrl(base, uri)
    }

    private fun parseSegments(text: String, base: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var duration = 0.0
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                line.isNotEmpty() && !line.startsWith("#") -> {
                    out += Segment(resolveUrl(base, line), duration, line)
                    duration = 0.0
                }
            }
        }
        return out
    }

    private fun buildLocalPlaylist(
        output: File,
        original: String,
        segments: List<Segment>,
        names: List<String>,
        initName: String?
    ) {
        var index = 0
        output.bufferedWriter().use { writer ->
            original.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-MAP:") && initName != null ->
                        writer.appendLine(line.replace(Regex("URI=\"[^\"]+\""), "URI=\"$initName\""))
                    line.isNotEmpty() && !line.startsWith("#") -> {
                        if (index < names.size) writer.appendLine(names[index++])
                    }
                    else -> writer.appendLine(raw)
                }
            }
        }
        check(index == segments.size) { "playlist/segment count mismatch" }
    }

    private fun resolveUrl(base: String, value: String): String =
        runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)

    private const val TAG = "LegacyOffline"
}
