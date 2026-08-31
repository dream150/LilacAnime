package com.lilac.anime

import android.content.Context
import android.util.Log
import com.lilac.anime.data.subtitle.KairanSubtitleResult
import com.lilac.anime.data.subtitle.SubtitleAssetUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Csora subtitle provider.
 *
 * Csora posts usually contain a series title plus links labelled "폰트", "1 ~ 5화",
 * "5화" and so on. The Google Drive URL itself normally has no episode number, so
 * the visible anchor label is the authoritative episode selector.
 */
object CsoraSubtitleService {
    private const val TAG = "Csora"
    private const val CACHE_DIR = "csora_subtitles"
    private const val PREF = "csora_post_cache"
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    private const val MIN_TITLE_SIMILARITY = 0.52
    private const val ASSET_SCHEMA_VERSION = 2

    private data class DownloadLink(val label: String, val url: String)

    suspend fun findSubtitle(
        context: Context,
        title: String,
        episodeNumber: Int
    ): KairanSubtitleResult? = findSubtitle(context, title, episodeNumber, episodeNumber.toString())

    suspend fun findSubtitle(
        context: Context,
        title: String,
        episodeNumber: Int,
        episodeKey: String
    ): KairanSubtitleResult? = withContext(Dispatchers.IO) {
        val cachedSubtitle = SubtitleStore.get(context, titleKey(title), episodeKey, episodeNumber, "csora")
        val fontDir = File(context.filesDir, "$CACHE_DIR/${titleKey(title)}/${safeEpisodeKey(episodeKey)}/fonts")
        val hasCachedFonts = fontDir.listFiles()?.any { file ->
            file.isFile && file.extension.lowercase(Locale.ROOT) in setOf("ttf", "otf", "ttc")
        } == true
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val cacheVersion = prefs.getInt("asset_version:${titleKey(title)}#$episodeKey", 0)

        // Keep cached subtitles fast only after the multi-ASS/font asset pass has run.
        if (cachedSubtitle != null && hasCachedFonts && cacheVersion == ASSET_SCHEMA_VERSION) {
            return@withContext KairanSubtitleResult.DirectFile(cachedSubtitle)
        }

        val post = findPost(context, title) ?: cachedSubtitle?.let {
            return@withContext KairanSubtitleResult.DirectFile(it)
        } ?: return@withContext null
        Log.d(TAG, "POST_FOUND url=$post")

        val allLinks = extractAllDownloadLinks(getText(post))
        val fontLinks = allLinks.filter { isFontLink(it.label) }
        val links = selectEpisodeLinks(allLinks, episodeNumber)
        Log.d(TAG, "EPISODE_LINKS episode=$episodeNumber count=${links.size} labels=${links.joinToString { it.label }} fontLinks=${fontLinks.size}")

        if (cachedSubtitle != null) {
            downloadFontLinks(context, fontLinks, title, episodeKey)
            return@withContext KairanSubtitleResult.DirectFile(cachedSubtitle)
        }

        val candidates = mutableListOf<SubtitleAssetUtil.AssCandidate>()
        for (link in links) {
            val results = downloadAndExtract(context, link.url, title, episodeNumber, episodeKey)
            results.forEach { path ->
                candidates += SubtitleAssetUtil.AssCandidate(path, "csora", 2)
                Log.d(TAG, "SUBTITLE_CANDIDATE path=$path label=${link.label}")
            }
        }
        if (candidates.isNotEmpty()) {
            // Some Csora posts provide fonts separately from the subtitle ZIP.
            // Font failures must never discard an otherwise valid subtitle.
            downloadFontLinks(context, fontLinks, title, episodeKey)
            val episodeValidCandidates = candidates.filter {
                SubtitleStore.subtitleMatchesEpisode(it.path, episodeNumber)
            }
            Log.d(TAG, "EPISODE_VALIDATION requested=$episodeNumber total=${candidates.size} valid=${episodeValidCandidates.size}")
            val selected = if (episodeValidCandidates.all { it.path.endsWith(".ass", true) || it.path.endsWith(".ssa", true) }) {
                SubtitleAssetUtil.resolveAssCandidates(context, title, episodeNumber, episodeValidCandidates)
            } else {
                episodeValidCandidates.firstOrNull()?.path
            }
            candidates.filter { it !in episodeValidCandidates }.forEach { File(it.path).delete() }
            if (selected != null) {
                SubtitleStore.save(context, titleKey(title), episodeKey, episodeNumber, "csora", selected)
                prefs.edit().putInt("asset_version:${titleKey(title)}#$episodeKey", ASSET_SCHEMA_VERSION).apply()
                return@withContext KairanSubtitleResult.DirectFile(selected)
            }
        }
        null
    }

    private suspend fun findPost(context: Context, title: String): String? {
        val key = titleKey(title)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(key, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val posts = CsoraBlogRepository.getPosts(context)
        val match = posts
            .asSequence()
            .map { post -> post to KairanPostMatcher.weightedSimilarity(title, post.title) }
            .maxByOrNull { it.second }
            ?: return null

        if (match.second < MIN_TITLE_SIMILARITY) {
            Log.d(TAG, "POST_MATCH_REJECTED similarity=${match.second} target=$title candidate=${match.first.title}")
            return null
        }

        Log.d(TAG, "BLOG_MATCH similarity=${match.second} target=$title title=${match.first.title}")
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(key, match.first.url).apply()
        return match.first.url
    }

    /**
     * Extract Drive/download anchors from the post. IMPORTANT: these are Kotlin raw
     * strings, so regex escapes use \b and \s (not double escaped \\b/\\s).
     */
    private fun extractAllDownloadLinks(html: String): List<DownloadLink> {
        val all = LinkedHashMap<String, DownloadLink>()
        val anchor = Regex(
            """<a\b[^>]*?href\s*=\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        anchor.findAll(html).forEach { match ->
            val url = decodeUrl(match.groupValues[1])
            val label = stripHtml(match.groupValues[2]).trim()
            if (looksLikeDownloadLink(url)) {
                all.putIfAbsent(url, DownloadLink(label, url))
                Log.d(TAG, "DRIVE_LINK label=[$label] url=$url")
            }
        }
        return all.values.toList()
    }

    private fun safeEpisodeKey(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")

    private fun selectEpisodeLinks(links: List<DownloadLink>, episode: Int): List<DownloadLink> {
        val exact = links.filter { !isFontLink(it.label) && isEpisodeLink(it.label, episode) }
        if (exact.isNotEmpty()) return exact

        val ranged = links.filter { !isFontLink(it.label) && isEpisodeRange(it.label, episode) }
        if (ranged.isNotEmpty()) return ranged

        return emptyList()
    }

    private fun decodeUrl(value: String): String = value
        .replace("&amp;", "&")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .trim()

    private fun stripHtml(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")

    private fun isFontLink(label: String): Boolean {
        val lower = label.lowercase(Locale.ROOT)
        return lower.contains("폰트") || lower.contains("font")
    }

    private fun isEpisodeLink(label: String, episode: Int): Boolean {
        val normalized = label.replace(Regex("\\s+"), " ").trim()
        val ep = episode.toString()
        return Regex("(?<!\\d)0*$ep\\s*(?:화|회|편|話)(?!\\d)", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized) ||
            Regex("\\b(?:ep|episode|e)\\s*0*$ep\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(normalized)
    }

    private fun isEpisodeRange(label: String, episode: Int): Boolean {
        val normalized = label.replace(Regex("\\s+"), " ")
        val range = Regex("(?<!\\d)(\\d+)\\s*(?:~|〜|–|—|-)\\s*(\\d+)\\s*(?:화|회|편|話)?")
            .find(normalized) ?: return false
        val start = range.groupValues[1].toIntOrNull() ?: return false
        val end = range.groupValues[2].toIntOrNull() ?: return false
        return episode in minOf(start, end)..maxOf(start, end)
    }

    private fun looksLikeDownloadLink(url: String): Boolean {
        val l = url.lowercase(Locale.ROOT)
        return l.contains("drive.google.com") ||
            l.contains("docs.google.com") ||
            l.contains("drive.usercontent.google.com") ||
            l.contains("googleusercontent.com") ||
            l.endsWith(".ass") || l.endsWith(".ssa") || l.endsWith(".zip")
    }

    private suspend fun downloadAndExtract(context: Context, originalUrl: String, title: String, episode: Int, episodeKey: String = episode.toString()): List<String> {
        val dir = File(context.filesDir, "$CACHE_DIR/${titleKey(title)}/${safeEpisodeKey(episodeKey)}").apply { mkdirs() }
        val tmp = File(dir, "download_${System.currentTimeMillis()}.bin")
        try {
            val downloaded = GoogleDriveDownloader.download(originalUrl, tmp, UA)
            if (!downloaded || tmp.length() < 32 || looksLikeHtml(tmp)) {
                Log.w(TAG, "DOWNLOAD_NOT_FILE url=$originalUrl size=${tmp.length()}")
                tmp.delete()
                return emptyList()
            }

            val selected = when {
                isZip(tmp) -> extractZip(dir, tmp, episode)
                isAss(tmp) -> listOf(File(dir, "episode_${episode}_${System.currentTimeMillis()}.ass").also { tmp.copyTo(it, overwrite = true) }.absolutePath)
                isVttOrSrt(tmp) -> {
                    val extension = detectTextSubtitleExtension(tmp)
                    listOf(File(dir, "episode_${episode}_${System.currentTimeMillis()}.$extension").also { tmp.copyTo(it, overwrite = true) }.absolutePath)
                }
                else -> {
                    Log.w(TAG, "UNSUPPORTED_MAGIC magic=${fileMagic(tmp)} size=${tmp.length()} url=$originalUrl")
                    emptyList()
                }
            }
            tmp.delete()
            if (selected.isNotEmpty()) Log.d(TAG, "SUBTITLE_ASSETS_FOUND count=${selected.size} url=$originalUrl")
            return selected
        } catch (e: Exception) {
            Log.w(TAG, "DOWNLOAD_FAILED url=$originalUrl", e)
            tmp.delete()
            return emptyList()
        }
    }

    private fun downloadFontLinks(context: Context, links: List<DownloadLink>, title: String, episodeKey: String = "1") {
        if (links.isEmpty()) return
        val dir = File(context.filesDir, "$CACHE_DIR/${titleKey(title)}/${safeEpisodeKey(episodeKey)}/fonts").apply { mkdirs() }
        for ((index, link) in links.withIndex()) {
            val tmp = File(dir, "font_download_${System.currentTimeMillis()}_$index.bin")
            try {
                if (!GoogleDriveDownloader.download(link.url, tmp, UA) || tmp.length() < 32 || looksLikeHtml(tmp)) {
                    Log.w(TAG, "FONT_DOWNLOAD_NOT_FILE label=${link.label} url=${link.url} size=${tmp.length()}")
                    tmp.delete()
                    continue
                }
                if (isZip(tmp)) {
                    extractFontsFromZip(dir, tmp)
                    tmp.delete()
                    continue
                }
                val ext = detectFontExtension(tmp)
                if (ext == null) {
                    Log.w(TAG, "FONT_UNSUPPORTED magic=${fileMagic(tmp)} label=${link.label}")
                    tmp.delete()
                    continue
                }
                val out = File(dir, "font_${index}_${System.currentTimeMillis()}.$ext")
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
                Log.d(TAG, "FONT_SAVED path=${out.absolutePath} size=${out.length()}")
            } catch (e: Exception) {
                Log.w(TAG, "FONT_DOWNLOAD_FAILED label=${link.label} url=${link.url}", e)
                tmp.delete()
            }
        }
    }

    private fun extractFontsFromZip(fontDir: File, zip: File) {
        var count = 0
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    val rawName = entry.name.substringAfterLast('/')
                    val ext = rawName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext in setOf("ttf", "otf", "ttc")) {
                        val safeName = rawName.replace(Regex("[^A-Za-z0-9가-힣._ -]"), "_")
                        val out = File(fontDir, safeName)
                        FileOutputStream(out).use { zis.copyTo(it) }
                        count++
                    }
                }
                zis.closeEntry()
            }
        }
        Log.d(TAG, "FONT_ZIP_EXTRACTED count=$count zip=${zip.name}")
    }

    private fun detectFontExtension(file: File): String? = try {
        file.inputStream().use { input ->
            val bytes = ByteArray(4)
            if (input.read(bytes) < 4) return null
            when {
                bytes.contentEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00)) -> "ttf"
                bytes.toString(Charsets.US_ASCII) == "OTTO" -> "otf"
                bytes.toString(Charsets.US_ASCII) == "ttcf" -> "ttc"
                else -> null
            }
        }
    } catch (_: Exception) { null }

    private fun extractZip(dir: File, zip: File, episode: Int): List<String> {
        Log.d(TAG, "ZIP_EXTRACT_START path=${zip.name} size=${zip.length()} episode=$episode")
        val extracted = mutableListOf<File>()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9가-힣._ -]"), "_")
                    if (name.isNotBlank()) {
                        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        val targetDir = if (ext in setOf("ttf", "otf", "ttc")) File(dir, "fonts") else dir
                        targetDir.mkdirs()
                        val out = File(targetDir, name)
                        FileOutputStream(out).use { zis.copyTo(it) }
                        when (ext) {
                            "ass", "ssa" -> if (isAss(out)) extracted += out
                            "vtt", "srt" -> if (isVttOrSrt(out)) extracted += out
                        }
                        if (ext in setOf("ass", "ssa", "vtt", "srt")) Log.d(TAG, "ZIP_ENTRY name=${entry.name} valid=${out in extracted}")
                    }
                }
                zis.closeEntry()
            }
        }
        if (extracted.isEmpty()) {
            Log.w(TAG, "ZIP_NO_SUBTITLES entries=0")
            return emptyList()
        }
        val selected = selectEpisodeFiles(extracted, episode)
        Log.d(TAG, "ZIP_EXTRACT_DONE candidates=${selected.map { it.name }} count=${selected.size}")
        return selected.map { it.absolutePath }
    }

    private fun selectEpisodeFiles(files: List<File>, episode: Int): List<File> {
        val exactNumber = Regex("(?<!\\d)0*${episode}(?!\\d)")
        val exact = files.filter { file ->
            isEpisodeLink(file.name, episode) ||
                isEpisodeRange(file.name, episode) ||
                exactNumber.containsMatchIn(file.nameWithoutExtension)
        }
        return if (exact.isNotEmpty()) exact else files
    }

    private fun getText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun isZip(file: File): Boolean = file.inputStream().use { input ->
        input.read() == 'P'.code && input.read() == 'K'.code
    }

    private fun isAss(file: File): Boolean = try {
        file.readText().take(20_000).lowercase().let { it.contains("[script info]") && it.contains("[events]") }
    } catch (_: Exception) { false }

    private fun isVttOrSrt(file: File): Boolean = try {
        val text = file.readText().take(20_000).trimStart()
        text.startsWith("WEBVTT", true) ||
            Regex("""\d{1,2}:\d{2}:\d{2}[,.]\d{3}\s*-->""").containsMatchIn(text)
    } catch (_: Exception) { false }

    private fun detectTextSubtitleExtension(file: File): String = try {
        if (file.readText().trimStart().startsWith("WEBVTT", true)) "vtt" else "srt"
    } catch (_: Exception) { "vtt" }

    private fun fileMagic(file: File): String = try {
        file.inputStream().use { input ->
            val bytes = ByteArray(12)
            val count = input.read(bytes)
            if (count > 0) bytes.copyOf(count).joinToString(" ") { "%02X".format(it) } else ""
        }
    } catch (_: Exception) { "" }

    private fun looksLikeHtml(file: File): Boolean = try {
        file.readText().take(2_000).trimStart().lowercase().let {
            it.startsWith("<html") || it.startsWith("<!doctype") || it.startsWith("<head")
        }
    } catch (_: Exception) { false }

    private fun titleKey(title: String) = KairanSubtitleService.normalizeTitleForFile(title)
}
