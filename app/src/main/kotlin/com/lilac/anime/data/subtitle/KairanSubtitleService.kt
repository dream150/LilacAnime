package com.lilac.anime

import android.content.Context
import android.util.Log
import com.lilac.anime.data.subtitle.KairanSubtitleResult
import com.lilac.anime.data.subtitle.SubtitleAssetUtil
import com.lilac.anime.data.subtitle.KairanTitleNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

object KairanSubtitleService {
    private const val TAG = "Kairan"
    private const val CACHE_DIR = "kairan_subtitles"
    private const val POST_CACHE_PREFS = "kairan_post_cache"
    private const val ASSET_SCHEMA_VERSION = 7
    private const val BUILD_MARKER = "KairanSubtitleService-0.2.5-CLEAN"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    suspend fun findSubtitle(context: Context, title: String, episodeNumber: Int): KairanSubtitleResult? =
        findSubtitle(context, title, episodeNumber, episodeNumber.toString())

    suspend fun findSubtitle(context: Context, title: String, episodeNumber: Int, episodeKey: String): KairanSubtitleResult? =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "BUILD_MARKER=$BUILD_MARKER")
                Log.d(TAG, "START_SEARCH title=[$title] episode=$episodeNumber")
                val normalizedTitle = KairanTitleNormalizer.normalize(title)
                Log.d(TAG, "NORMALIZED_TITLE original=[$title] normalized=[$normalizedTitle]")

                SubtitleStore.get(context, normalizeTitleForFile(title), episodeKey, episodeNumber, "kairan")
                    ?.takeIf { File(it).isFile }
                    ?.let {
                        val hasFonts = hasKairanFonts(context, it)
                        val syncedRecently = isAssetScanFresh(context, title, episodeNumber, episodeKey)
                        Log.d(TAG, "LOCAL_SUBTITLE_HIT path=$it fonts=$hasFonts assetScanFresh=$syncedRecently")
                        if (syncedRecently) {
                            return@withContext KairanSubtitleResult.DirectFile(it)
                        }
                    }

                val postUrl = findBlogPost(context, title, episodeNumber, episodeKey)
                    ?: run {
                        Log.w(TAG, "POST_NOT_FOUND title=[$title] episode=$episodeNumber")
                        return@withContext null
                    }

                Log.d(TAG, "POST_FOUND url=$postUrl")
                val html = getText(postUrl)
                val links = extractGoogleDriveLinks(html)
                Log.d(TAG, "DRIVE_LINK_COUNT count=${links.size}")

                val subtitleCandidates = mutableListOf<SubtitleAssetUtil.AssCandidate>()
                var fontCount = 0

                // Process every Drive link. Kairan posts can contain a main ZIP plus
                // a later, separately uploaded subtitle/font file. Returning after the
                // first successful download would silently ignore those later assets.
                links.forEachIndexed { index, link ->
                    val id = extractGoogleDriveId(link) ?: return@forEachIndexed
                    val asset = downloadGoogleDriveAsset(context, id, title, episodeNumber, episodeKey, index)
                    fontCount += asset.fontCount
                    asset.subtitlePaths.forEach { path ->
                        val priority = asset.subtitlePriority
                        subtitleCandidates += SubtitleAssetUtil.AssCandidate(path, "kairan", priority)
                        Log.d(TAG, "SUBTITLE_CANDIDATE priority=$priority path=$path")
                    }
                }

                val selected = SubtitleAssetUtil.resolveAssCandidates(context, title, episodeNumber, subtitleCandidates)
                markAssetScan(context, title, episodeNumber, episodeKey)
                if (selected != null) {
                    SubtitleStore.save(context, normalizeTitleForFile(title), episodeKey, episodeNumber, "kairan", selected)
                    Log.d(TAG, "SUBTITLE_READY path=$selected fonts=$fontCount candidates=${subtitleCandidates.size}")
                    return@withContext KairanSubtitleResult.DirectFile(selected)
                }

                Log.w(TAG, "DRIVE_SUBTITLE_NOT_FOUND url=$postUrl fonts=$fontCount")
                null
            } catch (e: Exception) {
                Log.e(TAG, "FIND_SUBTITLE_FAILED title=[$title] ep=$episodeNumber", e)
                null
            }
        }

    private fun postCacheKey(title: String, episode: Int, episodeKey: String = episode.toString()): String =
        "v4:${normalizeTitleForFile(title)}#$episodeKey"

    private fun cachedPostUrl(context: Context, title: String, episode: Int, episodeKey: String = episode.toString()): String? =
        context.getSharedPreferences(POST_CACHE_PREFS, Context.MODE_PRIVATE)
            .getString(postCacheKey(title, episode, episodeKey), null)
            ?.takeIf { it.isNotBlank() }

    private fun cachePostUrl(context: Context, title: String, episode: Int, url: String, episodeKey: String = episode.toString()) {
        context.getSharedPreferences(POST_CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(postCacheKey(title, episode, episodeKey), url)
            .apply()
    }

    private suspend fun findBlogPost(context: Context, title: String, episode: Int, episodeKey: String = episode.toString()): String? {
        cachedPostUrl(context, title, episode, episodeKey)?.let { cached ->
            Log.d(TAG, "POST_CACHE_HIT episode=$episode url=$cached")
            return cached
        }

        val posts = KairanBlogRepository.getPosts(context)
        if (posts.isEmpty()) {
            Log.w(TAG, "BLOG_INDEX_EMPTY")
            return null
        }

        var match = KairanPostMatcher.findBestMatch(title, episode, posts, episodeKey)
        if (match == null) {
            // A legacy index may contain only the first 150 Blogger posts.
            // Refresh once before giving up so older/less-recent Kairan posts are searchable.
            Log.d(TAG, "BLOG_MATCH_MISS_REFRESH title=[$title] episode=$episode cachedCount=${posts.size}")
            val refreshed = KairanBlogRepository.refresh(context)
            if (refreshed.isNotEmpty()) {
                Log.d(TAG, "BLOG_REFRESHED count=${refreshed.size}")
                match = KairanPostMatcher.findBestMatch(title, episode, refreshed, episodeKey)
            }
        }
        if (match == null) {
            Log.w(TAG, "BLOG_MATCH_MISS title=[$title] episode=$episode")
            return null
        }

        Log.d(TAG, "BLOG_MATCH similarity=${match.similarity} title=[${match.post.title}] url=${match.post.url}")
        cachePostUrl(context, title, episode, match.post.url, episodeKey)
        return match.post.url
    }

    private fun extractGoogleDriveLinks(html: String): List<String> {
        val out = linkedSetOf<String>()
        val absolute = Regex("""https?://(?:drive|docs)\.google\.com/[^\s\"'<>\\]+""", RegexOption.IGNORE_CASE)
        absolute.findAll(html).forEach { m ->
            val u = m.value.replace("&amp;", "&").replace("\\/", "/").trimEnd(')',']','}','\"','\'')
            if (extractGoogleDriveId(u) != null) out += u
        }
        val href = Regex("""href=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        href.findAll(html).forEach { m ->
            val u = m.groupValues[1].replace("&amp;", "&").replace("\\/", "/")
            if (extractGoogleDriveId(u) != null) out += u
        }
        return out.toList()
    }

    private fun extractGoogleDriveId(url: String): String? {
        Regex("""/file/d/([^/?]+)""").find(url)?.let { return it.groupValues[1] }
        Regex("""[?&]id=([^&]+)""").find(url)?.let { return it.groupValues[1] }
        return null
    }

    private data class DownloadAssetResult(
        val subtitlePaths: List<String>,
        val subtitlePriority: Int,
        val fontCount: Int
    )

    private fun safeEpisodeKey(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")

    private fun downloadGoogleDriveAsset(
        context: Context,
        fileId: String,
        title: String,
        episode: Int,
        episodeKey: String,
        linkIndex: Int
    ): DownloadAssetResult {
        val dir = File(context.filesDir, "$CACHE_DIR/${normalizeTitleForFile(title)}/${safeEpisodeKey(episodeKey)}").apply { mkdirs() }
        val safe = normalizeTitle(title).replace(' ', '_').ifBlank { "subtitle" }.take(60)
        val urls = listOf(
            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
            "https://drive.google.com/uc?export=download&id=$fileId"
        )

        for (downloadUrl in urls) {
            val temp = File(dir, "${safe}_${episode}_${System.currentTimeMillis()}_$linkIndex.tmp")
            try {
                Log.d(TAG, "DOWNLOAD_REQUEST url=$downloadUrl")
                val c = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "*/*")
                }
                try {
                    val code = c.responseCode
                    Log.d(TAG, "DOWNLOAD_HTTP code=$code contentType=${c.contentType}")
                    if (code !in 200..299) continue
                    c.inputStream.use { input -> FileOutputStream(temp).use { input.copyTo(it) } }
                    Log.d(TAG, "DOWNLOAD_SIZE bytes=${temp.length()}")
                    if (temp.length() < 32) { temp.delete(); continue }
                    if (looksLikeHtml(temp)) { Log.w(TAG, "DOWNLOAD_RETURNED_HTML"); temp.delete(); continue }

                    if (isZipFile(temp)) {
                        Log.d(TAG, "DOWNLOAD_ARCHIVE_ZIP")
                        val result = extractAssetsFromZip(temp, dir, safe, episode)
                        temp.delete()
                        if (result != null) return result
                        continue
                    }

                    if (isAssFile(temp)) {
                        val ext = detectSubtitleExtension(temp)
                        val out = File(dir, "${safe}_${episode}_direct_${System.currentTimeMillis()}$ext")
                        temp.copyTo(out, overwrite = true)
                        temp.delete()
                        if (!SubtitleStore.subtitleMatchesEpisode(out.absolutePath, episode)) {
                            Log.w(TAG, "DIRECT_ASS_EPISODE_MISMATCH requested=$episode path=${out.name}")
                            out.delete()
                            continue
                        }
                        return DownloadAssetResult(listOf(out.absolutePath), 3, 0)
                    }

                    if (isFontFile(temp)) {
                        val fontDir = File(dir, "fonts/$safe").apply { mkdirs() }
                        val ext = detectFontExtension(temp)
                        val out = File(fontDir, "font_direct_${System.currentTimeMillis()}.$ext")
                        temp.copyTo(out, overwrite = true)
                        temp.delete()
                        Log.d(TAG, "FONT_DIRECT_FOUND path=${out.absolutePath}")
                        return DownloadAssetResult(emptyList(), 0, 1)
                    }

                    Log.w(TAG, "DOWNLOAD_UNRECOGNIZED file=${temp.name}")
                    temp.delete()
                } finally { c.disconnect() }
            } catch (e: Exception) {
                Log.w(TAG, "DOWNLOAD_FAILED url=$downloadUrl", e)
                temp.delete()
            }
        }
        return DownloadAssetResult(emptyList(), 0, 0)
    }

    private data class AssInfo(
        val dialogueCount: Int,
        val positionedCount: Int,
        val movingCount: Int,
        val playResX: Int?,
        val playResY: Int?,
        val styleCount: Int
    )

    private fun inspectAssFile(file: File): AssInfo = try {
        val text = file.inputStream().bufferedReader().use { it.readText() }
        val lower = text.lowercase(Locale.ROOT)

        val dialogueLines = text.lineSequence()
            .filter { it.trimStart().startsWith("dialogue:", ignoreCase = true) }
            .toList()

        val positioned = dialogueLines.count {
            Regex("""\\pos\s*\(""", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }

        val moving = dialogueLines.count {
            Regex("""\\move\s*\(""", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }

        val playResX = Regex(
            """(?im)^\s*playresx\s*[:=]\s*(\d+)"""
        ).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val playResY = Regex(
            """(?im)^\s*playresy\s*[:=]\s*(\d+)"""
        ).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val styleCount = text.lineSequence().count {
            val t = it.trimStart()
            t.startsWith("style:", ignoreCase = true) ||
                t.startsWith("format:", ignoreCase = true) && lower.contains("[v4+ styles]")
        }

        AssInfo(
            dialogueCount = dialogueLines.size,
            positionedCount = positioned,
            movingCount = moving,
            playResX = playResX,
            playResY = playResY,
            styleCount = styleCount
        )
    } catch (_: Exception) {
        AssInfo(0, 0, 0, null, null, 0)
    }

    private fun isZipFile(file: File): Boolean {
        if (file.length() < 4) return false
        return try {
            file.inputStream().use { input ->
                input.read() == 0x50 && input.read() == 0x4B &&
                    input.read() == 0x03 && input.read() == 0x04
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun extractAssetsFromZip(
        zipFile: File,
        dir: File,
        safeTitle: String,
        episode: Int
    ): DownloadAssetResult? {
        val subtitles = mutableListOf<Pair<String, File>>()
        var fontCount = 0
        var extractedBytes = 0L
        val maxEntryBytes = 100L * 1024L * 1024L
        val maxTotalBytes = 300L * 1024L * 1024L
        val fontDir = File(dir, "fonts/$safeTitle").apply { mkdirs() }

        return try {
            ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue

                    val originalName = entry.name.replace('\\', '/')
                    val baseName = File(originalName).name
                    if (baseName.isBlank()) { zis.closeEntry(); continue }
                    val lower = baseName.lowercase(Locale.ROOT)
                    val ext = when {
                        lower.endsWith(".ass") -> "ass"
                        lower.endsWith(".ssa") -> "ssa"
                        lower.endsWith(".ttf") -> "ttf"
                        lower.endsWith(".otf") -> "otf"
                        lower.endsWith(".ttc") -> "ttc"
                        else -> null
                    }
                    if (ext == null) { zis.closeEntry(); continue }

                    val targetDir = if (ext in setOf("ttf", "otf", "ttc")) fontDir else dir
                    targetDir.mkdirs()
                    val safeEntryName = baseName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val out = File(targetDir, "${safeTitle}_${episode}_archive_${safeEntryName}")
                    var written = 0L
                    FileOutputStream(out).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            written += read
                            extractedBytes += read
                            if (written > maxEntryBytes || extractedBytes > maxTotalBytes) {
                                throw IllegalStateException("ZIP_ASSET_SIZE_LIMIT")
                            }
                            output.write(buffer, 0, read)
                        }
                    }

                    when (ext) {
                        "ass", "ssa" -> {
                            if (isAssFile(out)) subtitles += originalName to out else out.delete()
                        }
                        "ttf", "otf", "ttc" -> {
                            if (isFontFile(out)) {
                                fontCount++
                                Log.d(TAG, "FONT_ZIP_FOUND entry=$originalName path=${out.absolutePath}")
                            } else out.delete()
                        }
                    }
                    zis.closeEntry()
                }
            }

            if (subtitles.isEmpty()) {
                if (fontCount > 0) Log.d(TAG, "ZIP_FONTS_ONLY count=$fontCount")
                return if (fontCount > 0) DownloadAssetResult(emptyList(), 0, fontCount) else null
            }

            // Do not fall back to another episode when the archive contains
            // episode-numbered subtitle files.
            val exact = subtitles.filter { subtitleEpisodeNumber(it.first) == episode }
            val numbered = subtitles.filter { subtitleEpisodeNumber(it.first) != null }
            val selected = when {
                exact.isNotEmpty() -> exact
                numbered.isNotEmpty() -> {
                    Log.w(TAG, "ZIP_EPISODE_MISMATCH requested=$episode numbered=${numbered.map { it.first }}")
                    emptyList()
                }
                else -> subtitles
            }
            Log.d(TAG, "ZIP_SUBTITLES_FOUND count=${selected.size} entries=${selected.map { it.first }} fonts=$fontCount")
            val contentValid = selected.filter {
                SubtitleStore.subtitleMatchesEpisode(it.second.absolutePath, episode)
            }
            if (contentValid.isEmpty()) {
                Log.w(TAG, "ZIP_ASS_CONTENT_MISMATCH requested=$episode entries=${selected.map { it.first }}")
                subtitles.forEach { it.second.delete() }
                return if (fontCount > 0) DownloadAssetResult(emptyList(), 0, fontCount) else null
            }
            DownloadAssetResult(contentValid.map { it.second.absolutePath }, 2, fontCount)
        } catch (e: Exception) {
            subtitles.forEach { it.second.delete() }
            Log.w(TAG, "ZIP_EXTRACT_FAILED", e)
            null
        }
    }

    private fun subtitleEpisodeNumber(name: String): Int? {
        val base = File(name.replace('\\', '/')).nameWithoutExtension.lowercase(Locale.ROOT)
        val patterns = listOf(
            Regex("""(?:^|[^0-9])(?:episode|ep|e|#)\s*0*(\d{1,3})(?:$|[^0-9])""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|[^0-9])0*(\d{1,3})\s*(?:화|회|편|話)(?:$|[^0-9])"""),
            Regex("""(?:^|[_ .-])0*(\d{1,3})(?:$|[_ .-])""")
        )
        return patterns.take(2).asSequence()
            .mapNotNull { it.find(base)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .firstOrNull()
            ?: patterns[2].findAll(base)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .lastOrNull()
    }

    private fun detectSubtitleExtension(file: File): String =
        when {
            isSsaFile(file) -> ".ssa"
            else -> ".ass"
        }

    private fun isSsaFile(file: File): Boolean = try {
        val sample = file.inputStream().bufferedReader().use { it.readText().take(20000).lowercase(Locale.ROOT) }
        sample.contains("[script info]") && sample.contains("[events]")
    } catch (_: Exception) { false }

    private fun isFontFile(file: File): Boolean = try {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            val n = input.read(header)
            if (n < 4) return false
            header.contentEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00)) ||
                String(header, Charsets.US_ASCII) == "OTTO" ||
                String(header, Charsets.US_ASCII) == "ttcf"
        }
    } catch (_: Exception) { false }

    private fun detectFontExtension(file: File): String = try {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            val n = input.read(header)
            if (n < 4) "bin"
            else when {
                header.contentEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00)) -> "ttf"
                String(header, Charsets.US_ASCII) == "OTTO" -> "otf"
                String(header, Charsets.US_ASCII) == "ttcf" -> "ttc"
                else -> "bin"
            }
        }
    } catch (_: Exception) { "bin" }

    private fun hasKairanFonts(context: Context, subtitlePath: String): Boolean {
        val episodeDir = File(subtitlePath).parentFile ?: return false
        val fontsRoot = File(episodeDir, "fonts")
        return fontsRoot.walkTopDown().any {
            it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("ttf", "otf", "ttc")
        }
    }

    private fun assetScanKey(title: String, episode: Int, episodeKey: String = episode.toString()): String = "${normalizeTitleForFile(title)}#$episodeKey"

    private fun isAssetScanFresh(context: Context, title: String, episode: Int, episodeKey: String = episode.toString()): Boolean {
        val prefs = context.getSharedPreferences(POST_CACHE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt("asset_scan_version:${assetScanKey(title, episode, episodeKey)}", 0) != ASSET_SCHEMA_VERSION) return false
        val t = prefs.getLong("asset_scan:${assetScanKey(title, episode, episodeKey)}", 0L)
        return System.currentTimeMillis() - t < 6L * 60L * 60L * 1000L
    }

    private fun markAssetScan(context: Context, title: String, episode: Int, episodeKey: String = episode.toString()) {
        context.getSharedPreferences(POST_CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("asset_scan:${assetScanKey(title, episode, episodeKey)}", System.currentTimeMillis())
            .putInt("asset_scan_version:${assetScanKey(title, episode, episodeKey)}", ASSET_SCHEMA_VERSION)
            .apply()
    }

    private fun isAssFile(file: File): Boolean = try {
        val sample = file.inputStream().bufferedReader().use { it.readText().take(20000).lowercase(Locale.ROOT) }
        sample.contains("[script info]") && sample.contains("[events]")
    } catch (_: Exception) { false }

    private fun looksLikeHtml(file: File): Boolean = try {
        val sample = file.inputStream().bufferedReader().use { it.readText().take(3000).trimStart().lowercase(Locale.ROOT) }
        sample.startsWith("<!doctype html") || sample.startsWith("<html") || sample.contains("<head")
    } catch (_: Exception) { false }

    private fun getText(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT); setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,*/*")
        }
        return try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            text
        } finally { c.disconnect() }
    }

    internal fun normalizeTitleForFile(value: String): String = normalizeTitle(value)

    private fun normalizeTitle(value: String): String {
        // Do not use Regex for title normalization. Android's ICU regex backend
        // can reject patterns that work on desktop/JVM, and this path only needs
        // simple bracket removal + alphanumeric normalization.
        val lower = value.lowercase(Locale.ROOT)
        val out = StringBuilder(lower.length)
        var squareDepth = 0
        var parenDepth = 0
        var pendingSpace = false

        fun appendSpaceIfNeeded() {
            if (out.isNotEmpty() && !pendingSpace) pendingSpace = true
        }

        for (ch in lower) {
            when {
                ch == '[' -> squareDepth++
                ch == ']' && squareDepth > 0 -> squareDepth--
                ch == '(' -> parenDepth++
                ch == ')' && parenDepth > 0 -> parenDepth--
                squareDepth > 0 || parenDepth > 0 -> Unit
                ch.isLetterOrDigit() -> {
                    if (pendingSpace) {
                        out.append(' ')
                        pendingSpace = false
                    }
                    out.append(ch)
                }
                else -> appendSpaceIfNeeded()
            }
        }
        return out.toString().trim()
    }
}

