package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import com.lilac.anime.AnissiaService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Single Korean fan-subtitle resolver backed by Anissia metadata.
 *
 * Anissia provides the canonical Korean title/animeNo and the subtitle maker's
 * website. The actual file is fetched from that website because Anissia does
 * not host subtitle binaries itself.
 */
object AnissiaSubtitleService {
    data class SubtitleOption(
        val episode: String,
        val creator: String,
        val updateDate: String,
        val website: String,
        val cachedPath: String? = null
    ) {
        val name: String
            get() = buildString {
                append(if (creator.isBlank()) "Anissia 자막" else creator)
                if (updateDate.isNotBlank()) append(" · ").append(updateDate)
            }
    }
    private const val TAG = "AnissiaSubtitle"
    private const val CACHE_DIR = "anissia_subtitles"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    private suspend fun resolveAnime(koreanTitle: String, seasonNumber: Int?): com.lilac.anime.AnissiaAnime? {
        val titleCandidates = buildList {
            seasonNumber?.takeIf { it > 1 }?.let { number ->
                add("${koreanTitle.trim()} 시즌 $number")
                add("${koreanTitle.trim()} ${number}기")
            }
            koreanTitle.trim().takeIf { it.isNotBlank() }?.let(::add)
        }.distinct()
        for (candidate in titleCandidates) {
            val found = try {
                AnissiaService.findAnime(candidate)
            } catch (e: Exception) {
                Log.w(TAG, "ANIME_RESOLVE_FAILED candidate=$candidate", e)
                null
            }
            if (found != null) return found
        }
        return null
    }

    suspend fun listEpisodeSubtitles(
        context: Context,
        koreanTitle: String,
        episodeNumber: Int,
        episodeKey: String = episodeNumber.toString(),
        seasonNumber: Int? = null,
        animeId: String = koreanTitle
    ): List<SubtitleOption> = withContext(Dispatchers.IO) {
        val anime = resolveAnime(koreanTitle, seasonNumber) ?: return@withContext emptyList()
        val captions = try {
            AnissiaService.getSubtitles(anime.animeNo)
        } catch (e: Exception) {
            Log.w(TAG, "CAPTION_LIST_FAILED animeNo=${anime.animeNo}", e)
            emptyList()
        }
        Log.d(
            TAG,
            "PICKER_CAPTIONS animeNo=${anime.animeNo} title=[${anime.subject}] " +
                "requestedEpisode=$episodeNumber episodeKey=$episodeKey total=${captions.size} " +
                "episodes=${captions.take(20).joinToString { it.episode }}"
        )

        captions
            .filter { caption ->
                episodeMatches(caption.episode, episodeNumber)
            }
            .filter { !it.website.isNullOrBlank() }
            .map { caption ->
                val cached = SubtitleStore.get(context, animeId, episodeKey, episodeNumber, "anissia")
                    ?.takeIf { File(it).isFile && File(it).length() > 0L }
                SubtitleOption(
                    episode = caption.episode,
                    creator = caption.creator,
                    updateDate = caption.updateDate,
                    website = caption.website!!.trim(),
                    cachedPath = cached
                )
            }
            .distinctBy { "${it.creator}|${it.website}|${it.updateDate}" }
    }

    suspend fun downloadSelectedSubtitle(
        context: Context,
        koreanTitle: String,
        episodeNumber: Int,
        episodeKey: String,
        animeId: String,
        option: SubtitleOption
    ): String? = withContext(Dispatchers.IO) {
        option.cachedPath?.takeIf { File(it).isFile }?.let { return@withContext it }
        val result = downloadFromSubtitleWebsite(
            context = context,
            websiteUrl = option.website,
            title = koreanTitle,
            episodeNumber = episodeNumber,
            episodeKey = episodeKey,
            creator = option.creator
        ) ?: return@withContext null
        SubtitleStore.save(context, animeId, episodeKey, episodeNumber, "anissia", result)
        result
    }

    suspend fun findSubtitle(
        context: Context,
        koreanTitle: String,
        episodeNumber: Int,
        episodeKey: String = episodeNumber.toString(),
        seasonNumber: Int? = null,
        animeId: String = koreanTitle
    ): String? = withContext(Dispatchers.IO) {
        val resolvedAnime = resolveAnime(koreanTitle, seasonNumber) ?: return@withContext null

        val caption = try {
            AnissiaService.getEpisodeSubtitle(resolvedAnime.animeNo, episodeKey)
                ?: AnissiaService.getEpisodeSubtitle(resolvedAnime.animeNo, episodeNumber.toString())
        } catch (e: Exception) {
            Log.w(TAG, "EPISODE_CAPTION_FAILED animeNo=${resolvedAnime.animeNo} episode=$episodeKey", e)
            null
        } ?: return@withContext null

        val website = caption.website?.trim()?.takeIf { it.isNotBlank() }
            ?: return@withContext null

        val cached = SubtitleStore.get(
            context, animeId, episodeKey, episodeNumber, "anissia"
        )?.takeIf { File(it).isFile && File(it).length() > 0L }
        if (cached != null) return@withContext cached

        val result = downloadFromSubtitleWebsite(
            context = context,
            websiteUrl = website,
            title = resolvedAnime.subject.ifBlank { koreanTitle },
            episodeNumber = episodeNumber,
            episodeKey = episodeKey,
            creator = caption.creator
        ) ?: return@withContext null

        SubtitleStore.save(
            context,
            animeId,
            episodeKey,
            episodeNumber,
            "anissia",
            result
        )
        Log.d(TAG, "SUBTITLE_READY animeNo=${resolvedAnime.animeNo} episode=$episodeKey creator=${caption.creator} path=$result")
        result
    }

    private suspend fun downloadFromSubtitleWebsite(
        context: Context,
        websiteUrl: String,
        title: String,
        episodeNumber: Int,
        episodeKey: String,
        creator: String
    ): String? {
        val initialUrl = runCatching { URLDecoder.decode(websiteUrl.trim(), "UTF-8") }
            .getOrDefault(websiteUrl.trim())
        if (initialUrl.isBlank()) return null

        // Anissia is the metadata/index layer. For providers that already have
        // mature downloaders in LilacAnime, pass the exact post URL through those
        // implementations instead of duplicating their site-specific rules here.
        val host = runCatching { URI(initialUrl).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        if (host == "kairan03.blogspot.com" || host.endsWith(".kairan03.blogspot.com")) {
            KairanSubtitleService.findSubtitleFromAnissiaPost(
                context, initialUrl, title, episodeNumber, episodeKey
            )?.let { return it }
        }
        if (host == "csora556.blogspot.com" || host.endsWith(".csora556.blogspot.com")) {
            CsoraSubtitleService.findSubtitleFromAnissiaPost(
                context, initialUrl, title, episodeNumber, episodeKey
            )?.let { return it }
        }

        val pageQueue = ArrayDeque<String>()
        val visitedPages = linkedSetOf<String>()
        val candidates = linkedSetOf<String>()
        pageQueue.add(initialUrl)

        while (pageQueue.isNotEmpty() && visitedPages.size < 12) {
            val pageUrl = pageQueue.removeFirst()
            if (!visitedPages.add(pageUrl)) continue

            val html = runCatching {
                Jsoup.connect(pageUrl)
                    .userAgent(USER_AGENT)
                    .referrer(initialUrl)
                    .timeout(20000)
                    .followRedirects(true)
                    .get()
                    .html()
            }.getOrElse {
                Log.w(TAG, "PAGE_FETCH_FAILED url=$pageUrl", it)
                continue
            }

            val document = Jsoup.parse(html, pageUrl)
            val sameHostPages = mutableListOf<Pair<String, String>>()

            document.select("a[href]").forEach { element ->
                val href = element.absUrl("href").ifBlank { element.attr("href") }.trim()
                if (href.isBlank()) return@forEach
                val text = element.text().trim()

                if (looksLikeSubtitleLink(href, text, episodeNumber)) {
                    candidates += href
                }

                if (isGoogleDrive(href) || href.contains("attachment", true)) {
                    candidates += href
                }

                val next = runCatching { URI(href) }.getOrNull()
                if (next != null && next.host != null &&
                    URI(pageUrl).host.equals(next.host, true) &&
                    !href.startsWith("javascript:", true) &&
                    !href.startsWith("mailto:", true)
                ) {
                    sameHostPages += href to text
                }
            }

            // Naver Blog exposes attachments in aPostFiles rather than normal <a> links.
            Regex(
                "(?:encodedAttachFileUrl|attachFileUrl)\\\"?\\s*[:=]\\s*\\\"([^\\\"]+)\\\"",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { match ->
                candidates += Jsoup.parse("<a href=\"${match.groupValues[1]}\"></a>").select("a").attr("abs:href")
                    .ifBlank { match.groupValues[1] }
                    .replace("\\u0026", "&")
            }

            // Tistory attachment links are commonly emitted as /attachment/... URLs.
            Regex(
                "https?://[^\\\"'<>\\s]+/attachment/[^\\\"'<>\\s]+",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { candidates += it.value.replace("&amp;", "&") }

            // Some sites only put the direct file URL in inline JS.
            Regex(
                "https?://[^\\\"'<>\\s]+(?:\\.zip|\\.7z|\\.rar|\\.ass|\\.ssa|\\.srt|\\.smi|\\.vtt)(?:\\?[^\\\"'<>\\s]*)?",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { candidates += it.value.replace("\\u0026", "&") }

            Regex(
                "https?://[^\\\"'<>\\s]*(?:drive\\.google\\.com|drive\\.usercontent\\.google\\.com)[^\\\"'<>\\s]*",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { candidates += it.value.replace("\\u0026", "&") }

            // Follow likely archive/pagination/post links so completed episodes can be found
            // when the Anissia website points to a blog home/archive rather than one post.
            val scoredPages = sameHostPages
                .distinctBy { it.first }
                .map { it.first to scorePageLink(it.first, it.second, title, episodeNumber) }
                .filter { it.second >= 10 }
                .sortedByDescending { it.second }
            scoredPages.take(5).forEach { pageQueue.addLast(it.first) }
        }

        val ordered = candidates
            .distinct()
            .sortedWith(
                compareByDescending<String> { scoreLink(it, episodeNumber, title) }
                    .thenBy { it.length }
            )

        for ((index, url) in ordered.withIndex()) {
            val targetDir = File(context.filesDir, "$CACHE_DIR/${safe(title)}/${safe(episodeKey)}").apply { mkdirs() }
            val urlExt = url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
            val targetExt = urlExt.takeIf { it in setOf("zip", "7z", "rar", "ass", "ssa", "srt", "smi", "vtt") } ?: "bin"
            val target = File(targetDir, "anissia_${safe(episodeKey)}_$index.$targetExt")
            try {
                val ok = if (isGoogleDrive(url)) {
                    GoogleDriveDownloader.download(url, target, USER_AGENT)
                } else {
                    downloadDirect(url, target, initialUrl)
                }
                if (!ok || !target.isFile || target.length() == 0L) {
                    target.delete()
                    continue
                }
                val actual = materializeSubtitleFile(context, target, title, episodeKey, creator)
                if (actual != null) return actual
            } catch (e: Exception) {
                Log.w(TAG, "DOWNLOAD_FAILED url=$url", e)
                target.delete()
            }
        }

        Log.w(TAG, "NO_SUBTITLE_FILE website=$initialUrl episode=$episodeNumber pages=${visitedPages.size} candidates=${candidates.size}")
        return null
    }

    /**
     * Anissia episode values are not guaranteed to be plain "1".
     * Depending on the provider/catalog entry they can be "01", "1.0",
     * "01화", "1회", "EP 01", or "S01E01". Normalize all of those to
     * the numeric episode before filtering the picker.
     */
    private fun episodeMatches(value: String, target: Int): Boolean {
        val raw = value.trim()
        if (raw.isBlank()) return false

        raw.toDoubleOrNull()?.let {
            if (kotlin.math.abs(it - target.toDouble()) < 0.01) return true
        }

        val normalized = raw.lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), "")

        Regex("""^s\\d+e(\\d+(?:\\.\\d+)?)$""")
            .matchEntire(normalized)
            ?.groupValues?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.let {
                if (kotlin.math.abs(it - target.toDouble()) < 0.01) return true
            }

        Regex("""(?:^|\\D)0*(\\d+(?:\\.\\d+)?)(?:화|회|화수|편|ep|episode|화\\b|\\D|$)""")
            .find(normalized)
            ?.groupValues?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.let {
                if (kotlin.math.abs(it - target.toDouble()) < 0.01) return true
            }

        return false
    }

    private fun scorePageLink(url: String, text: String, title: String, episode: Int): Int {
        val value = "$url $text".lowercase(Locale.ROOT)
        var score = 0
        if (Regex("(?:^|\\D)0*${episode}(?:화|회|ep|\\D|$)").containsMatchIn(value)) score += 100
        val titleTokens = title.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
        titleTokens.forEach { if (value.contains(it)) score += 8 }
        if (value.contains("next") || value.contains("older") || value.contains("page") || value.contains("list") || value.contains("archive")) score += 12
        return score
    }

    private fun materializeSubtitleFile(
        context: Context,
        downloaded: File,
        title: String,
        episodeKey: String,
        creator: String
    ): String? {
        val bytes = runCatching { downloaded.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val head = bytes.copyOf(minOf(bytes.size, 4096)).toString(Charsets.UTF_8)
            .trimStart('\uFEFF')
            .trimStart()
            .lowercase(Locale.ROOT)
        if (head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head")) return null

        val lowerName = downloaded.name.lowercase(Locale.ROOT)
        val isZipPayload = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val isArchive = lowerName.endsWith(".zip") || lowerName.endsWith(".7z") || lowerName.endsWith(".rar") || isZipPayload
        val targetBase = File(context.filesDir, "anissia_subtitles/${safe(title)}/${safe(episodeKey)}").apply { mkdirs() }

        if (isArchive && lowerName.endsWith(".zip")) {
            return extractZipSubtitle(downloaded, targetBase, episodeKey)
        }

        val text = bytes.toString(Charsets.UTF_8)
        val ext = when {
            lowerName.endsWith(".ass") || text.contains("[Script Info]", true) -> "ass"
            lowerName.endsWith(".ssa") -> "ssa"
            lowerName.endsWith(".smi") || text.contains("<SAMI", true) -> "smi"
            lowerName.endsWith(".vtt") || text.trimStart().startsWith("WEBVTT", true) -> "vtt"
            else -> "srt"
        }
        if (!isSubtitlePayload(text, ext)) return null

        val out = File(targetBase, "${safe(episodeKey)}.$ext")
        out.writeBytes(bytes)
        SubtitleStore.setDisplayName(context, out.absolutePath, "${title} ${episodeKey}화${if (creator.isNotBlank()) " - $creator" else ""}.$ext")
        return out.absolutePath
    }

    private fun extractZipSubtitle(zip: File, targetDir: File, episodeKey: String): String? {
        val candidates = mutableListOf<File>()
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = File(entry.name).name
                val lower = name.lowercase(Locale.ROOT)
                if (lower !in setOf("ass", "ssa", "srt", "vtt", "smi").map { "." + it }) continue
                val out = File(targetDir, "${safe(episodeKey)}_${safe(name)}")
                out.outputStream().use { input.copyTo(it) }
                if (out.length() > 0L) candidates += out
            }
        }
        return candidates.firstOrNull { SubtitleStore.subtitleMatchesEpisode(it.absolutePath, episodeKey.toDoubleOrNull()?.toInt() ?: 0) }
            ?.absolutePath
            ?: candidates.firstOrNull()?.absolutePath
    }

    private fun isSubtitlePayload(text: String, ext: String): Boolean = when (ext) {
        "ass", "ssa" -> text.contains("[Events]", true) || text.contains("Dialogue:", true)
        "smi" -> text.contains("<SYNC", true)
        "vtt" -> text.trimStart().startsWith("WEBVTT", true)
        else -> text.contains(" --> ")
    }

    private fun downloadDirect(url: String, target: File, referer: String): Boolean {
        val connection = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Referer", referer)
        }
        return try {
            if (connection.responseCode !in 200..299) return false
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            target.length() > 0L
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchText(url: String): String? = runCatching {
        val connection = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun looksLikeSubtitleLink(url: String, text: String, episode: Int): Boolean {
        val value = (url + " " + text).lowercase(Locale.ROOT)
        if (value.contains("youtube.com") || value.contains("youtu.be")) return false
        val ext = value.substringBefore('?').substringAfterLast('.')
        if (ext in setOf("ass", "ssa", "srt", "smi", "vtt", "zip", "7z", "rar")) return true
        return Regex("(?:^|\\D)0*${episode}(?:화|회|ep|화\\b|\\b)").containsMatchIn(value)
    }

    private fun scoreLink(url: String, episode: Int, title: String = ""): Int {
        var score = 0
        val value = url.lowercase(Locale.ROOT)
        if (Regex("(?:^|\\D)0*${episode}(?:\\D|$)").containsMatchIn(value)) score += 100
        if (value.endsWith(".ass") || value.endsWith(".ssa")) score += 40
        if (value.endsWith(".srt") || value.endsWith(".vtt")) score += 30
        if (value.endsWith(".zip")) score += 20
        if (value.endsWith(".7z") || value.endsWith(".rar")) score += 8
        if (isGoogleDrive(value)) score += 10
        title.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.forEach {
            if (value.contains(it)) score += 5
        }
        return score
    }

    private fun isGoogleDrive(url: String): Boolean =
        url.contains("drive.google.com", true) || url.contains("drive.usercontent.google.com", true)

    private fun safe(value: String): String =
        value.trim().replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").take(100).ifBlank { "subtitle" }
}
