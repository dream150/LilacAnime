package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import com.lilac.anime.AnissiaService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

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
    private data class SiteIndexCache(val savedAt: Long, val posts: Map<String, String>)
    private val siteIndexCache = ConcurrentHashMap<String, SiteIndexCache>()
    private const val SITE_INDEX_TTL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_SUBTITLE_DISCOVERY_PAGES = 80
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
            .sortedByDescending { it.updateDate }
            .map { caption ->
                val optionSource = optionSourceKey(caption.creator, caption.website.orEmpty())
                val cached = SubtitleStore.get(context, animeId, episodeKey, episodeNumber, optionSource)
                    ?.takeIf { isUsableCachedSubtitle(it) }
                SubtitleOption(
                    episode = caption.episode,
                    creator = caption.creator,
                    updateDate = caption.updateDate,
                    website = caption.website!!.trim(),
                    cachedPath = cached
                )
            }
            .distinctBy { "${it.creator.trim().lowercase(Locale.ROOT)}|${it.website.trim().trimEnd('/').lowercase(Locale.ROOT)}" }
            .also { Log.i(TAG, "PICKER_OPTIONS animeNo=${anime.animeNo} options=${it.size} creators=${it.joinToString { option -> option.creator }.take(500)}") }
    }

    suspend fun downloadSelectedSubtitle(
        context: Context,
        koreanTitle: String,
        episodeNumber: Int,
        episodeKey: String,
        animeId: String,
        option: SubtitleOption
    ): String? = withContext(Dispatchers.IO) {
        option.cachedPath?.takeIf { isUsableCachedSubtitle(it) }?.let { return@withContext it }
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
            ?.takeIf { isUsableCachedSubtitle(it) }
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
        // Never URL-decode an entire URL: '+' and percent-encoded query values may be
        // meaningful signed-link data. Jsoup/URI already resolve escaped DOM links.
        val initialUrl = websiteUrl.trim().replace("&amp;", "&")
        if (initialUrl.isBlank() || !initialUrl.startsWith("http", true)) return null

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
        // Desktop parity: a registered Anissia URL may point to a blog home or an old post.
        // Seed a small, episode-ranked list from each site's own index/search endpoint before
        // crawling ordinary links. This is bounded and does not replace the normal downloader.
        discoverSiteSpecificPages(initialUrl, title, episodeNumber).forEach { if (it != initialUrl) pageQueue.addLast(it) }

        // Search more of a creator's archive. The old 12-page ceiling meant older
        // episodes were often invisible when the registered URL pointed at a blog home.
        // Keep a generous safety ceiling and a visited set to prevent loops.
        while (pageQueue.isNotEmpty() && visitedPages.size < MAX_SUBTITLE_DISCOVERY_PAGES) {
            val pageUrl = pageQueue.removeFirst()
            if (!visitedPages.add(pageUrl)) continue

            val fetched = runCatching {
                val response = Jsoup.connect(pageUrl)
                    .userAgent(USER_AGENT)
                    .referrer(initialUrl)
                    .timeout(20000)
                    .followRedirects(true)
                    .execute()
                Triple(response.body(), response.url().toString(), response.statusCode())
            }.getOrElse {
                Log.w(TAG, "PAGE_FETCH_FAILED url=$pageUrl", it)
                continue
            }
            val html = fetched.first
            val finalPageUrl = fetched.second
            if (fetched.third !in 200..299) {
                Log.w(TAG, "PAGE_FETCH_HTTP_FAILED status=${fetched.third} url=$pageUrl final=$finalPageUrl")
                continue
            }
            Log.i(TAG, "PAGE_FETCH_OK status=${fetched.third} original=$pageUrl final=$finalPageUrl bytes=${html.length}")
            val document = Jsoup.parse(html, finalPageUrl)
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
                val linkPath = runCatching { URI(normalizedHref).path.orEmpty() }.getOrDefault(normalizedHref.substringBefore('?').substringBefore('#'))
                val linkExt = linkPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
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
                    URI(finalPageUrl).host.equals(next.host, true) &&
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
            scoredPages.take(20).forEach { pageQueue.addLast(it.first) }
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

    private fun discoverSiteSpecificPages(initialUrl: String, title: String, episode: Int): List<String> {
        val origin = runCatching { URI(initialUrl).let { "${it.scheme}://${it.authority}" } }.getOrNull() ?: return emptyList()
        val host = runCatching { URI(origin).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val posts = linkedMapOf<String, String>()
        fun add(url: String?, label: String?) {
            if (url.isNullOrBlank()) return
            val resolved = runCatching { URI(origin).resolve(url).toString() }.getOrNull() ?: return
            val uri = runCatching { URI(resolved) }.getOrNull() ?: return
            if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return
            val candidateHost = uri.host.orEmpty().lowercase(Locale.ROOT)
            val isNaverHostPair = candidateHost in setOf("blog.naver.com", "m.blog.naver.com") && host in setOf("blog.naver.com", "m.blog.naver.com")
            if (!uri.host.equals(host, true) && !isNaverHostPair) return
            posts.putIfAbsent(resolved, label.orEmpty())
        }
        val queries = listOf(title.trim(), title.trim().replace(Regex("\\s*(?:시즌|season)\\s*\\d+|\\s*\\d+\\s*기$", RegexOption.IGNORE_CASE), "").trim())
            .filter { it.length >= 2 }.distinct()
        val cachedIndex = siteIndexCache[origin]?.takeIf { System.currentTimeMillis() - it.savedAt < SITE_INDEX_TTL_MS }
        if (cachedIndex != null) posts.putAll(cachedIndex.posts)
        try {
            if (cachedIndex == null) when {
                host.endsWith(".blogspot.com") -> {
                    // Blogger feed pagination: inspect older archive pages as well as recent posts.
                    // Stop when the feed ends or no new post URLs are returned.
                    for (start in (1..3000 step 150)) {
                        val before = posts.size
                        val feedUrl = "$origin/feeds/posts/default?alt=json&max-results=150&start-index=$start"
                        val raw = try {
                            Jsoup.connect(feedUrl).userAgent(USER_AGENT).referrer("$origin/")
                                .timeout(12000).ignoreContentType(true).execute().body()
                        } catch (e: Exception) {
                            Log.d(TAG, "BLOGGER_FEED_PAGE_FAILED start=$start reason=${e.message}")
                            break
                        }
                        val feed = JSONObject(raw).optJSONObject("feed") ?: break
                        val entries = feed.optJSONArray("entry") ?: break
                        for (i in 0 until entries.length()) {
                            val entry = entries.optJSONObject(i) ?: continue
                            val label = entry.optJSONObject("title")?.optString("\$t").orEmpty()
                            val links = entry.optJSONArray("link") ?: continue
                            for (j in 0 until links.length()) {
                                val link = links.optJSONObject(j) ?: continue
                                if (link.optString("rel") == "alternate") add(link.optString("href"), label)
                            }
                        }
                        if (entries.length() < 150 || posts.size == before) break
                    }
                }
                host.endsWith(".tistory.com") -> {
                    // RSS includes recent posts; /search/{query} fills in older episode posts.
                    runCatching {
                        val rss = Jsoup.connect("$origin/rss").userAgent(USER_AGENT).referrer("$origin/")
                            .header("Accept", "application/rss+xml,application/xml;q=0.9,*/*;q=0.8")
                            .timeout(12000).get()
                        rss.select("item").forEach { item -> add(item.selectFirst("link")?.text(), item.selectFirst("title")?.text()) }
                    }.onFailure { Log.d(TAG, "TISTORY_RSS_UNAVAILABLE host=$host reason=${it.message}") }
                    for (query in queries) {
                        runCatching {
                            val searchUrl = "$origin/search/${URLEncoder.encode(query, "UTF-8")}"
                            val doc = Jsoup.connect(searchUrl).userAgent(USER_AGENT).referrer("$origin/").timeout(12000).get()
                            doc.select("a[href]").forEach { a ->
                                val href = a.attr("href")
                                if (Regex("^(?:https?://[^/]+)?/(?:entry/)?\\d+(?:[?#].*)?$").containsMatchIn(href)) add(href, a.text())
                            }
                        }.onFailure { Log.d(TAG, "TISTORY_SEARCH_FAILED query=$query reason=${it.message}") }
                    }
                }
                host == "blog.naver.com" || host == "m.blog.naver.com" -> {
                    val uri = URI(initialUrl)
                    val segments = uri.path.split('/').filter { it.isNotBlank() }
                    val blogId = uri.getQueryParameterCompat("blogId") ?: segments.firstOrNull().orEmpty()
                    if (blogId.isNotBlank()) {
                        val apiBase = "https://m.blog.naver.com/api/blogs/${URLEncoder.encode(blogId, "UTF-8")}"
                        fun fetchNaverPage(apiUrl: String): Int {
                            val before = posts.size
                            runCatching {
                                val raw = Jsoup.connect(apiUrl).userAgent(USER_AGENT)
                                    .referrer("https://m.blog.naver.com/$blogId")
                                    .timeout(12000).ignoreContentType(true).execute().body()
                                val root = JSONObject(raw).optJSONObject("result") ?: return@runCatching
                                val items = root.optJSONArray("items") ?: root.optJSONArray("list") ?: return@runCatching
                                for (i in 0 until items.length()) {
                                    val item = items.optJSONObject(i) ?: continue
                                    val logNo = item.optString("logNo").ifBlank { item.optString("logNoStr") }
                                    val label = item.optString("title").ifBlank { item.optString("titleWithInspectMessage") }
                                    if (logNo.isNotBlank()) add(
                                        "https://blog.naver.com/PostView.naver?blogId=${URLEncoder.encode(blogId, "UTF-8")}&logNo=${URLEncoder.encode(logNo, "UTF-8")}",
                                        label
                                    )
                                }
                                items.length()
                            }.onFailure { Log.d(TAG, "NAVER_INDEX_REQUEST_FAILED url=$apiUrl reason=${it.message}") }
                            return posts.size - before
                        }
                        // Stop each pagination stream once it stops yielding new posts.
                        for (page in 1..20) {
                            val added = fetchNaverPage("$apiBase/post-list?categoryNo=0&itemCount=30&page=$page")
                            if (added == 0) break
                        }
                        for (query in queries) {
                            for (page in 1..10) {
                                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                                val added = fetchNaverPage("$apiBase/search/post?query=$encodedQuery&page=$page")
                                if (added == 0) break
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SITE_INDEX_DISCOVERY_FAILED host=$host title=$title episode=$episode", e)
        }
        if (cachedIndex == null && posts.isNotEmpty()) {
            siteIndexCache[origin] = SiteIndexCache(System.currentTimeMillis(), posts.toMap())
        }
        Log.i(TAG, "SITE_INDEX_READY host=$host title=$title episode=$episode posts=${posts.size}")
        return posts.entries
            .map { it.key to scorePageLink(it.key, it.value, title, episode) }
            .filter { it.second >= 10 }
            .sortedByDescending { it.second }
            .take(30)
            .map { it.first }
    }

    private fun URI.getQueryParameterCompat(name: String): String? = rawQuery?.split('&')
        ?.mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.firstOrNull() == name) pieces.getOrNull(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } else null
        }?.firstOrNull()

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
        if (head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head") ||
            head.startsWith("<body") || head.contains("just a moment...") ||
            head.contains("enable javascript and cookies") || head.contains("access denied")) {
            Log.w(TAG, "DOWNLOAD_NOT_SUBTITLE_HTML file=${downloaded.name} head=${head.take(120)}")
            return null
        }

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
            val finalUrl = connection.url?.toString().orEmpty()
            val contentType = connection.contentType.orEmpty()
            val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
            if (status !in 200..299) {
                Log.w(TAG, "HTTP_DOWNLOAD_FAILED status=$status type=$contentType url=${url.take(220)} final=$finalUrl")
                return false
            }
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            Log.i(TAG, "HTTP_DOWNLOAD_RESPONSE status=$status type=$contentType disposition=${disposition.take(120)} bytes=${target.length()} url=${url.take(160)} final=$finalUrl")
            if (target.length() == 0L) Log.w(TAG, "HTTP_DOWNLOAD_ZERO type=$contentType url=${url.take(220)}")
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
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url.substringBefore('?').substringBefore('#'))
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in setOf("ass", "ssa", "srt", "smi", "sami", "vtt", "sub", "idx", "sup", "ttml", "dfxp", "xml", "sbv", "mpl", "mpl2", "txt", "scc", "lrc", "zip", "7z", "rar", "ttf", "otf", "ttc")) return true
        return Regex("(?:^|\\D)0*${episode}(?:화|회|ep|화\\b|\\b)").containsMatchIn(value)
    }

    private fun scoreLink(url: String, episode: Int, title: String = ""): Int {
        var score = 0
        val value = url.lowercase(Locale.ROOT)
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url.substringBefore('?').substringBefore('#'))
        val pathAndQuery = "$path ${runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")}".lowercase(Locale.ROOT)
        if (Regex("(?:^|\\D)0*${episode}(?:\\D|$)").containsMatchIn("$pathAndQuery $value")) score += 100
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in setOf("ass", "ssa")) score += 40
        if (ext in setOf("srt", "vtt", "smi", "sami")) score += 30
        if (ext == "zip") score += 20
        if (ext in setOf("7z", "rar")) score += 8
        if (isGoogleDrive(value)) score += 10
        title.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.forEach {
            if (value.contains(it)) score += 5
        }
        return score
    }

    private fun isUsableCachedSubtitle(path: String): Boolean {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L || file.length() > 100L * 1024 * 1024) return false
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext in setOf("idx", "sup")) return true
        val bytes = runCatching {
            java.io.FileInputStream(file).use { input ->
                val buffer = ByteArray(128 * 1024)
                val count = input.read(buffer)
                if (count <= 0) byteArrayOf() else buffer.copyOf(count)
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        val text = decodeSubtitleText(bytes).removePrefix("\uFEFF").trimStart()
        val valid = when (ext) {
            "ass", "ssa" -> text.contains("[Events]", true) && text.contains("Dialogue:", true)
            "smi", "sami" -> text.contains("<SYNC", true) || text.contains("<SAMI", true)
            "vtt" -> text.startsWith("WEBVTT", true)
            "ttml", "dfxp", "xml" -> text.contains("<tt", true) && (text.contains("<p", true) || text.contains("<div", true))
            "srt" -> Regex("(?m)^\\s*\\d{1,3}\\s*$").containsMatchIn(text) && Regex("(?m)\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}\\s*-->\\s*").containsMatchIn(text)
            "sub", "mpl", "mpl2", "sbv", "txt", "lrc", "scc" -> isSubtitlePayload(text, ext)
            else -> false
        }
        if (!valid) Log.w(TAG, "CACHE_INVALID_SUBTITLE path=${file.name} ext=$ext bytes=${file.length()}")
        return valid
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
