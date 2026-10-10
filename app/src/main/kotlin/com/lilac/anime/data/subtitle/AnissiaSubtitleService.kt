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
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

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

        // Anissia's caption API represents the creator's latest registered episode,
        // not a complete per-episode index. Never filter this list by the requested
        // episode; use each creator website as the entry point and search that site.
        captions
            .filter { !it.website.isNullOrBlank() }
            .map { caption ->
                val optionSource = optionSourceKey(caption.creator, caption.website.orEmpty())
                val cached = SubtitleStore.get(context, animeId, episodeKey, episodeNumber, optionSource)
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
        val optionSource = optionSourceKey(option.creator, option.website)
        SubtitleStore.save(
            context, animeId, episodeKey, episodeNumber,
            optionSource, result,
            displayName = "${koreanTitle} ${episodeNumber}화 - ${option.creator.ifBlank { "Anissia" }}"
        )
        Log.d(TAG, "OPTION_SUBTITLE_SAVED creator=${option.creator} sourceKey=$optionSource path=$result")
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
        val resolvedAnime = resolveAnime(koreanTitle, seasonNumber)
        val title = resolvedAnime?.subject?.takeIf { it.isNotBlank() } ?: koreanTitle
        val cached = SubtitleStore.get(context, animeId, episodeKey, episodeNumber, "anissia")
            ?.takeIf { File(it).isFile && File(it).length() > 0L }
        if (cached != null) return@withContext cached

        // First try all maker websites associated with this anime, newest update first.
        val titleCreators = if (resolvedAnime != null) runCatching {
            AnissiaService.getSubtitles(resolvedAnime.animeNo)
                .filter { !it.website.isNullOrBlank() && it.website!!.startsWith("http", true) }
                .sortedByDescending { it.updateDate }
        }.onFailure { Log.w(TAG, "CREATOR_LIST_FAILED animeNo=${resolvedAnime.animeNo}", it) }
            .getOrDefault(emptyList()) else emptyList()

        val visitedSites = linkedSetOf<String>()
        val candidates = mutableListOf<Triple<String, String, String>>()
        titleCreators.forEach { c ->
            val site = c.website.orEmpty().trim()
            if (site.isNotBlank() && visitedSites.add(site)) candidates += Triple(site, c.creator, "anime")
        }
        Log.i(TAG, "CREATOR_SITES anime=${resolvedAnime?.animeNo ?: -1} episode=$episodeKey count=${candidates.size}")

        for ((site, creator, source) in candidates) {
            Log.d(TAG, "CREATOR_SITE_TRY source=$source creator=$creator website=$site episode=$episodeNumber")
            val result = runCatching {
                downloadFromSubtitleWebsite(context, site, title, episodeNumber, episodeKey, creator)
            }.onFailure { Log.w(TAG, "CREATOR_SITE_FAILED creator=$creator website=$site", it) }.getOrNull()
            if (!result.isNullOrBlank() && File(result).isFile && File(result).length() > 0L) {
                SubtitleStore.save(context, animeId, episodeKey, episodeNumber, "anissia", result)
                Log.i(TAG, "SUBTITLE_READY source=$source episode=$episodeKey creator=$creator path=$result")
                return@withContext result
            }
        }

        // If the anime's registered links are stale, consult Anissia's approved
        // translator directory and try recent, distinct blogs as a bounded fallback.
        // The anime caption endpoint often keeps an old *post* URL (especially
        // Naver Blog URLs that now return 403). Refresh the translator directory
        // and prefer the same creator's current website before trying unrelated
        // recently approved creators. A stale post URL must not prevent recovery.
        val directory = runCatching {
            AnissiaService.getRecentTranslatorWebsites(maxPages = 8, forceRefresh = true)
        }.onFailure { Log.w(TAG, "TRANSLATOR_DIRECTORY_FAILED", it) }
            .getOrDefault(emptyList())

        fun normalizeCreator(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]"), "")

        val creatorKey = normalizeCreator(titleCreators.firstOrNull {
            it.website?.trim() == candidates.firstOrNull()?.first
        }?.creator.orEmpty())
        // Prefer an exact creator-name match, then a partial match. Keep newest
        // registrations first within each group; only then use general directory
        // candidates as a last-resort discovery path.
        val prioritizedDirectory = directory.sortedWith(
            compareBy<com.lilac.anime.AnissiaTranslator> {
                val name = normalizeCreator(it.name)
                when {
                    creatorKey.isBlank() -> 2
                    name == creatorKey -> 0
                    name.contains(creatorKey) || creatorKey.contains(name) -> 1
                    else -> 2
                }
            }.thenByDescending { it.regTime }
        )
        Log.i(TAG, "DIRECTORY_PRIORITIZED creatorKey=$creatorKey total=${prioritizedDirectory.size}")
        var attemptedFallbacks = 0
        for (maker in prioritizedDirectory) {
            if (attemptedFallbacks >= 18) break
            val site = maker.website.trim()
            if (site.isBlank() || !visitedSites.add(site)) continue
            attemptedFallbacks++
            Log.d(TAG, "DIRECTORY_SITE_TRY index=$attemptedFallbacks creator=${maker.name} website=$site episode=$episodeNumber")
            val result = runCatching {
                downloadFromSubtitleWebsite(context, site, title, episodeNumber, episodeKey, maker.name)
            }.onFailure { Log.w(TAG, "DIRECTORY_SITE_FAILED creator=${maker.name} website=$site", it) }.getOrNull()
            if (!result.isNullOrBlank() && File(result).isFile && File(result).length() > 0L) {
                SubtitleStore.save(context, animeId, episodeKey, episodeNumber, "anissia", result)
                Log.i(TAG, "SUBTITLE_READY source=directory episode=$episodeKey creator=${maker.name} path=$result")
                return@withContext result
            }
        }

        // Preserve the known specialized fallbacks after generic Anissia discovery.
        val kairan = runCatching { KairanSubtitleService.findSubtitle(context, title, episodeNumber, episodeKey) }
            .onFailure { Log.w(TAG, "BLOGSPOT_FALLBACK_KAIRAN_FAILED title=$title", it) }.getOrNull()
        (kairan as? KairanSubtitleResult.DirectFile)?.path?.let { path ->
            if (File(path).isFile && File(path).length() > 0L) return@withContext path
        }
        val csora = runCatching { CsoraSubtitleService.findSubtitle(context, title, episodeNumber, episodeKey) }
            .onFailure { Log.w(TAG, "BLOGSPOT_FALLBACK_CSORA_FAILED title=$title", it) }.getOrNull()
        (csora as? KairanSubtitleResult.DirectFile)?.path?.let { path ->
            if (File(path).isFile && File(path).length() > 0L) return@withContext path
        }
        Log.w(TAG, "NO_SUBTITLE_FOUND title=$title episode=$episodeKey animeCreators=${titleCreators.size} directoryTried=$attemptedFallbacks")
        null
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
        val fontCandidates = linkedSetOf<String>()
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

                // Tistory exposes attached subtitle archives as signed Kakaocdn URLs
                // (for example blog.kakaocdn.net/.../title.zip?credential=...&attach=1).
                // Parse the resolved href from the DOM so HTML-escaped query strings
                // are decoded without losing the signature, then prioritize the visible
                // filename/label for episode matching.
                val normalizedHref = href
                    .replace("\\u0026", "&")
                    .replace("\\u003d", "=")
                    .replace("&amp;", "&")
                val linkExt = normalizedHref.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (linkExt in setOf("ttf", "otf", "ttc")) fontCandidates += normalizedHref
                else if (looksLikeSubtitleLink(normalizedHref, text, episodeNumber)) candidates += normalizedHref

                if (isGoogleDrive(normalizedHref) || normalizedHref.contains("attachment", true) ||
                    normalizedHref.contains("blog.kakaocdn.net", true) &&
                    Regex("\\.(?:zip|7z|rar|ass|ssa|srt|smi|sami|vtt|sub|idx|sup|ttml|dfxp|xml|sbv|mpl|mpl2|txt|scc|lrc)(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(normalizedHref)
                ) {
                    candidates += normalizedHref
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
                "https?://[^\\\"'<>\\s]+(?:\\.zip|\\.7z|\\.rar|\\.ass|\\.ssa|\\.srt|\\.smi|\\.sami|\\.vtt|\\.sub|\\.idx|\\.sup|\\.ttml|\\.dfxp|\\.xml|\\.sbv|\\.mpl|\\.mpl2|\\.txt|\\.scc|\\.lrc|\\.ttf|\\.otf|\\.ttc)(?:\\?[^\\\"'<>\\s]*)?",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { match ->
                val url = match.value.replace("\\u0026", "&")
                val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext in setOf("ttf", "otf", "ttc")) fontCandidates += url else candidates += url
            }

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

        if (fontCandidates.isNotEmpty()) {
            val fontDir = File(context.filesDir, "$CACHE_DIR/${title.trim()}/fonts").apply { mkdirs() }
            fontCandidates.take(12).forEachIndexed { index, fontUrl ->
                val ext = fontUrl.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext !in setOf("ttf", "otf", "ttc")) return@forEachIndexed
                val out = File(fontDir, "${safe(creator.ifBlank { "creator" })}_${index}.$ext")
                if (out.isFile && out.length() > 0L) return@forEachIndexed
                runCatching {
                    val ok = if (isGoogleDrive(fontUrl)) GoogleDriveDownloader.download(fontUrl, out, USER_AGENT)
                        else downloadDirect(fontUrl, out, initialUrl)
                    if (ok && out.length() in 1..(20L * 1024 * 1024)) {
                        Log.i(TAG, "FONT_READY name=${out.name} bytes=${out.length()}")
                    } else {
                        out.delete()
                        Log.d(TAG, "FONT_DOWNLOAD_SKIPPED url=${fontUrl.take(180)}")
                    }
                }.onFailure { out.delete(); Log.w(TAG, "FONT_DOWNLOAD_FAILED url=${fontUrl.take(180)}", it) }
            }
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
            val targetExt = urlExt.takeIf { it in setOf("zip", "7z", "rar", "ass", "ssa", "srt", "smi", "sami", "vtt", "sub", "idx", "sup", "ttml", "dfxp", "xml", "sbv", "mpl", "mpl2", "txt", "scc", "lrc", "ttf", "otf", "ttc") } ?: "bin"
            val target = File(targetDir, "anissia_${safe(episodeKey)}_$index.$targetExt")
            try {
                val ok = if (isGoogleDrive(url)) {
                    GoogleDriveDownloader.download(url, target, USER_AGENT)
                } else {
                    downloadDirect(url, target, initialUrl)
                }
                if (!ok || !target.isFile || target.length() == 0L) {
                    Log.w(TAG, "CANDIDATE_DOWNLOAD_EMPTY index=$index url=${url.take(220)} ok=$ok exists=${target.exists()} bytes=${target.length()}")
                    target.delete()
                    continue
                }
                Log.d(TAG, "CANDIDATE_DOWNLOADED index=$index url=${url.substringBefore('?').takeLast(120)} bytes=${target.length()} ext=${target.extension}")
                val actual = materializeSubtitleFile(context, target, title, episodeKey, episodeNumber, creator)
                if (actual != null) {
                    Log.i(TAG, "SUBTITLE_EXTRACTED episode=$episodeNumber path=$actual source=${url.substringBefore('?').takeLast(120)}")
                    return actual
                }
                Log.w(TAG, "CANDIDATE_NO_SUBTITLE index=$index url=${url.substringBefore('?').takeLast(120)} bytes=${target.length()} ext=${target.extension}")
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
        episodeNumber: Int,
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
        val targetBase = File(
            context.filesDir,
            "$CACHE_DIR/${safe(title)}/${safe(episodeKey)}/${safe(creator.ifBlank { "unknown_creator" })}"
        ).apply { mkdirs() }

        if (isArchive && (lowerName.endsWith(".zip") || isZipPayload)) {
            val fontDir = File(context.filesDir, "$CACHE_DIR/${title.trim()}/fonts").apply { mkdirs() }
            return extractZipSubtitle(downloaded, targetBase, episodeKey, episodeNumber, title, fontDir)
        }

        val originalExt = lowerName.substringAfterLast('.', "").substringBefore('?')
        // Bitmap subtitle payloads are binary; keep them intact for mpv rather than
        // attempting charset conversion. IDX/SUB generally needs both companion files.
        if (originalExt in setOf("idx", "sup")) {
            val out = File(targetBase, "${safe(episodeKey)}.$originalExt")
            out.writeBytes(bytes)
            SubtitleStore.setDisplayName(context, out.absolutePath, "${title} ${episodeKey}화 - ${creator}.$originalExt")
            Log.i(TAG, "BINARY_SUBTITLE_READY ext=$originalExt bytes=${bytes.size} path=${out.absolutePath}")
            return out.absolutePath
        }
        val text = decodeSubtitleText(bytes)
        val detectedExt = when {
            text.contains("[Script Info]", true) -> "ass"
            text.contains("<SAMI", true) -> "smi"
            text.trimStart().startsWith("WEBVTT", true) -> "vtt"
            text.contains("<tt", true) && (text.contains("<p", true) || text.contains("<div", true)) -> "ttml"
            else -> lowerName.substringAfterLast('.', "srt").substringBefore('?')
        }
        val ext = detectedExt.takeIf { it in setOf("ass", "ssa", "srt", "smi", "sami", "vtt", "sub", "ttml", "dfxp", "xml", "sbv", "mpl", "mpl2", "txt", "scc", "lrc") } ?: "srt"
        if (!isSubtitlePayload(text, ext)) return null

        val out = File(targetBase, "${safe(episodeKey)}.$ext")
        out.writeText(text.removePrefix("\uFEFF"), Charsets.UTF_8)
        SubtitleStore.setDisplayName(context, out.absolutePath, "${title} ${episodeKey}화${if (creator.isNotBlank()) " - $creator" else ""}.$ext")
        return out.absolutePath
    }

    private fun extractZipSubtitle(
        zip: File,
        targetDir: File,
        episodeKey: String,
        episodeNumber: Int,
        title: String,
        fontDir: File
    ): String? {
        val candidates = mutableListOf<File>()
        var totalExpandedBytes = 0L
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory) continue
                val rawEntryName = entry.name.replace('\\', '/')
                // Reject absolute paths and traversal entries; preserve safe nested folders so
                // users can distinguish same-named subtitle/font files from different folders.
                if (rawEntryName.startsWith("/") || rawEntryName.split('/').any { it == ".." }) {
                    Log.w(TAG, "ZIP_UNSAFE_ENTRY_SKIPPED entry=${rawEntryName.take(160)}")
                    input.closeEntry()
                    continue
                }
                val relativeName = rawEntryName.split('/').filter { it.isNotBlank() && it != "." }
                    .joinToString("/") { safe(it) }
                val name = relativeName.substringAfterLast('/')
                val lower = name.lowercase(Locale.ROOT)
                // Compare the entry extension, not the whole filename. The previous
                // check compared "chainsaw_man_12.ass" to ".ass", which rejected every
                // subtitle inside Tistory ZIP attachments and made those blogs appear
                // unsupported even when the archive downloaded successfully.
                val extension = lower.substringAfterLast('.', "")
                if (extension in setOf("ttf", "otf", "ttc")) {
                    // Keep archive fonts in the directory already consumed by SubtitleAssetUtil/mpv.
                    // Ignore suspiciously large font entries and never use the archive path.
                    val fontBytes = input.readBytes()
                    totalExpandedBytes += fontBytes.size
                    if (fontBytes.isNotEmpty() && fontBytes.size <= 20 * 1024 * 1024 && totalExpandedBytes <= 80L * 1024 * 1024) {
                        val fontFile = File(fontDir, relativeName).apply { parentFile?.mkdirs() }
                        runCatching { fontFile.writeBytes(fontBytes) }
                        Log.d(TAG, "ZIP_FONT_EXTRACTED name=${fontFile.name} bytes=${fontBytes.size}")
                    }
                    continue
                }
                if (extension !in setOf("ass", "ssa", "srt", "vtt", "smi", "sami", "sub", "idx", "sup", "ttml", "dfxp", "xml", "sbv", "mpl", "mpl2", "txt", "scc", "lrc")) continue
                if (totalExpandedBytes > 100L * 1024 * 1024) {
                    Log.w(TAG, "ZIP_EXPANSION_LIMIT archive=${zip.name}")
                    break
                }
                // Keep the archive's original basename so episode matching examines
                // the actual filename (e.g. Chainsaw_man_12.ass), not a generated
                // prefix such as "12_" that would make every entry look like episode 12.
                val out = File(targetDir, relativeName).apply { parentFile?.mkdirs() }
                val payload = input.readBytes()
                totalExpandedBytes += payload.size
                if (payload.isEmpty()) continue
                if (totalExpandedBytes > 100L * 1024 * 1024) {
                    out.delete()
                    Log.w(TAG, "ZIP_EXPANSION_LIMIT archive=${zip.name}")
                    break
                }
                // Bitmap subtitles and IDX/SUB pairs are binary; retain them unchanged.
                if (extension in setOf("idx", "sup")) {
                    out.writeBytes(payload)
                    candidates += out
                    continue
                }
                val decoded = decodeSubtitleText(payload).removePrefix("\uFEFF")
                if (isSubtitlePayload(decoded, extension)) {
                    out.writeText(decoded, Charsets.UTF_8)
                    candidates += out
                } else {
                    out.delete()
                }
            }
        }
        // episodeKey is the subtitle cache/catalog key (it can be the Anissia
        // post ID, e.g. 977), not the requested episode. Always match archive
        // entries against the actual playback episode number (e.g. episode 1).
        val requestedEpisode = episodeNumber
        if (candidates.isEmpty()) {
            Log.w(TAG, "ZIP_NO_SUBTITLE_ENTRIES archive=${zip.name} bytes=${zip.length()}")
            return null
        }

        // Prefer explicit episode matches from the archive entry names. Some fansub
        // archives use names such as title_01.ass, title - 01 [1080p].ass, or
        // S01E01.ass; the shared matcher understands ranges and avoids season-number
        // false positives. Keep a narrow fallback for single-file archives only.
        val matching = candidates.filter { file ->
            requestedEpisode > 0 && (
                SubtitleEpisodeMatcher.matches(file.relativeTo(targetDir).invariantSeparatorsPath.substringBeforeLast('.', file.name), requestedEpisode) ||
                    SubtitleEpisodeMatcher.matches(file.nameWithoutExtension, requestedEpisode) ||
                    SubtitleStore.subtitleMatchesEpisode(file.absolutePath, requestedEpisode)
                )
        }
        val chosen = matching.maxByOrNull { SubtitleEpisodeMatcher.score(it.relativeTo(targetDir).invariantSeparatorsPath.substringBeforeLast('.', it.name), requestedEpisode) }
            ?: candidates.singleOrNull()
        Log.d(TAG, "ZIP_ENTRIES archive=${zip.name} requested=$requestedEpisode subtitles=${candidates.size} matching=${matching.size} selected=${chosen?.name ?: "none"} names=${candidates.take(20).joinToString { it.name }}")
        return chosen?.absolutePath
    }

    private fun isSubtitlePayload(text: String, ext: String): Boolean = when (ext) {
        "ass", "ssa" -> text.contains("[Events]", true) || text.contains("Dialogue:", true)
        "smi", "sami" -> text.contains("<SYNC", true) || text.contains("<SAMI", true)
        "vtt" -> text.trimStart().startsWith("WEBVTT", true)
        "ttml", "dfxp", "xml" -> text.contains("<tt", true) && (text.contains("<p", true) || text.contains("<div", true))
        "sbv" -> Regex("\\d{1,2}:\\d{2}:\\d{2}[.,]\\d{1,3}\\s*,\\s*\\d{1,2}:\\d{2}:\\d{2}[.,]\\d{1,3}").containsMatchIn(text)
        "sub" -> text.contains(" --> ") || Regex("(?m)^\\{\\d+\\}\\{\\d+\\}").containsMatchIn(text)
        "mpl", "mpl2" -> Regex("(?m)^\\[\\d+\\]\\[\\d+\\]").containsMatchIn(text)
        "scc" -> text.contains("\\t") && Regex("(?m)^\\d{2}:\\d{2}:\\d{2}:\\d{2}").containsMatchIn(text)
        "lrc" -> Regex("(?m)^\\[\\d{2}:\\d{2}[.]?\\d*\\]").containsMatchIn(text)
        "txt" -> text.contains(" --> ") || text.contains("[Events]", true) || Regex("(?m)^\\{\\d+\\}\\{\\d+\\}").containsMatchIn(text)
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
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(TAG, "HTTP_DOWNLOAD_FAILED status=$status type=${connection.contentType} url=${url.take(220)}")
                return false
            }
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            if (target.length() == 0L) Log.w(TAG, "HTTP_DOWNLOAD_ZERO type=${connection.contentType} url=${url.take(220)}")
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
        if (ext in setOf("ass", "ssa", "srt", "smi", "sami", "vtt", "sub", "idx", "sup", "ttml", "dfxp", "xml", "sbv", "mpl", "mpl2", "txt", "scc", "lrc", "zip", "7z", "rar", "ttf", "otf", "ttc")) return true
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

    private fun optionSourceKey(creator: String, website: String): String {
        // Keep every Anissia creator/blog in a separate cache slot. The old single
        // "anissia" key caused each picker option to reuse whichever creator was
        // downloaded first.
        val identity = "${creator.trim().lowercase(Locale.ROOT)}|${website.trim().lowercase(Locale.ROOT)}"
        val hash = identity.hashCode().toUInt().toString(16)
        return "anissia_${safe(creator).lowercase(Locale.ROOT)}_$hash"
    }

    private fun decodeSubtitleText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        }
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (utf8 != null) return utf8.removePrefix("\uFEFF")
        // Most Korean SMI/ASS attachments without a BOM are EUC-KR/CP949.
        for (name in listOf("EUC-KR", "x-windows-949", "UTF-16LE", "UTF-16BE", "Shift_JIS")) {
            val decoded = runCatching { String(bytes, Charset.forName(name)) }.getOrNull() ?: continue
            if (decoded.count { it == '\uFFFD' } < 2) return decoded.removePrefix("\uFEFF")
        }
        return String(bytes, Charset.forName("x-windows-949")).removePrefix("\uFEFF")
    }

    private fun isGoogleDrive(url: String): Boolean =
        url.contains("drive.google.com", true) || url.contains("drive.usercontent.google.com", true)

    private fun safe(value: String): String =
        value.trim().replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").take(100).ifBlank { "subtitle" }
}
