package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import com.lilac.anime.AnissiaService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
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
        val cachedPath: String? = null,
        /** Anissia's canonical title; used to search the creator's blog. */
        val searchTitle: String = "",
        /** Further blogs of the same creator, tried after [website] if nothing is found there. */
        val extraWebsites: List<String> = emptyList()
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
    // Time budgets keep the picker from hanging when a blog is slow or huge.
    private const val SITE_BUDGET_MS = 45_000L
    private const val SELECTED_BUDGET_MS = 90_000L
    private const val OVERALL_BUDGET_MS = 150_000L
    private const val DIRECTORY_PAGES = 20
    private const val MAX_DIRECTORY_SITES = 80
    private const val DIRECTORY_CONCURRENCY = 4
    private const val DIRECTORY_SITE_BUDGET_MS = 25_000L
    private const val MAX_DOWNLOAD_BYTES = 60L * 1024 * 1024
    private val EXPLICIT_EPISODE = Regex("""(?i)(?<![\d.])0*(\d{1,4})\s*(?:화|회|话|話)|(?<![a-z])(?:ep|episode|e)\s*\.?\s*0*(\d{1,4})(?!\d)""")
    private val EPISODE_RANGE = Regex("""(?<![\d.])0*(\d{1,4})\s*[~\-–]\s*0*(\d{1,4})(?!\d)""")
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
    private const val FILE_EXTENSIONS =
        "zip|7z|rar|ass|ssa|srt|smi|sami|vtt|sub|idx|sup|ttml|dfxp|xml|sbv|mpl|mpl2|txt|scc|lrc|ttf|otf|ttc"

    // Patterns used to dig attachment links out of raw page source. They run on text that went
    // through normalizePageText(), i.e. HTML entities and JSON slash escapes are already undone.
    //
    // Naver Blog: old editors emit aPostFiles[n] = [{'encodedAttachFileUrl':'...'}], SmartEditor ONE
    // keeps the link inside a JSON data attribute. Both quote styles are accepted.
    private val NAVER_ATTACH_KEY = Regex(
        """(?:encodedAttachFileUrl|attachFileUrl)["']?\s*[:=]\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val NAVER_ATTACH_HOST = Regex("""https?://blogattach\.naver\.com/[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
    private val TISTORY_ATTACH = Regex("""https?://[^\s"'<>\\]+/attachment/[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
    private val DIRECT_FILE = Regex(
        """https?://[^\s"'<>\\]+?\.(?:$FILE_EXTENSIONS)(?![\w.])(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val DRIVE_URL = Regex(
        """https?://[^\s"'<>\\]*(?:drive\.google\.com|drive\.usercontent\.google\.com)[^\s"'<>\\]*""",
        RegexOption.IGNORE_CASE
    )
    private val ATTACHMENT_EXT_AT_END = Regex(
        """\.(?:zip|7z|rar|ass|ssa|srt|smi|sami|vtt|sub|idx|sup|ttml|dfxp|xml|sbv|mpl|mpl2|txt|scc|lrc)(?:[?#]|\z)""",
        RegexOption.IGNORE_CASE
    )
    // Tistory posts are /123 or, with slug permalinks, /entry/some-title.
    private val TISTORY_POST_PATH = Regex("""^(?:https?://[^/]+)?/(?:entry/[^/?#]+|\d+)(?:[?#].*)?\z""")

    // true: when Anissia lists no creator we can map to a directory blog, fall back to sweeping the
    // newest blogs in the translator directory. false: only ever search the anime's own creators.
    private const val FALLBACK_TO_FULL_DIRECTORY = true
    private const val DIRECTORY_CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * A subtitle creator of one anime and every blog we know for them, best source first:
     * 1) translator directory, 2) recent-captions map, 3) this anime's own caption website.
     */
    private data class CreatorBlog(
        val creator: String,
        val websites: List<String>,
        val updateDate: String,
        val episode: String
    ) {
        val website: String get() = websites.first()
    }

    /** One approved entry of Anissia's translator directory. */
    private data class DirectoryMaker(val applyNo: Int, val name: String, val website: String, val regTime: Long)
    private data class DirectoryCache(val savedAt: Long, val makers: List<DirectoryMaker>)
    @Volatile private var directoryCache: DirectoryCache? = null

    // Directory entries that are not subtitle blogs (Anissia itself, code hosts).
    private val NON_BLOG_HOSTS = setOf(
        "anissia.net", "github.com", "youtube.com", "youtu.be", "twitter.com", "x.com", "facebook.com",
        "instagram.com", "discord.com", "discord.gg", "t.me", "cafe.naver.com", "open.kakao.com",
        "docs.google.com", "drive.google.com"
    )
    private fun isNonBlogHost(host: String): Boolean =
        NON_BLOG_HOSTS.any { host == it || host.endsWith(".$it") }

    // Recent captions: GET https://api.anissia.net/anime/caption/recent/{page}, 20 per page, newest first.
    // {"code":"ok","data":{"content":[{animeNo,episode,name,subject,updDt,website}],"last":bool}}
    private const val RECENT_CAPTION_API = "https://api.anissia.net/anime/caption/recent/"
    private const val RECENT_CAPTION_PAGES = 30
    private const val RECENT_CAPTION_BATCH = 6
    private data class RecentCaptionPage(val entries: List<Pair<String, String>>, val last: Boolean)
    private data class RecentCaptionCache(val savedAt: Long, val sitesByCreator: Map<String, List<String>>)
    @Volatile private var recentCaptionCache: RecentCaptionCache? = null
    private const val DIRECTORY_API = "https://api.anissia.net/translator/apply/list/"

    private fun normalizeCreator(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    /**
     * Anissia's approved translator directory (https://anissia.net/translator/apply), cached for a few hours.
     *
     * The page is backed by GET https://api.anissia.net/translator/apply/list/{page} (0-based, 30 per
     * page): {"code":"ok","data":{"content":[{applyNo,status,result,name,website,regTime}],"last":bool}}.
     * Most entries (about three quarters) have website "-", some are result "FAIL" (rejected applications)
     * and a few point at github/anissia itself or contain junk; only real, approved blogs are kept.
     */
    private suspend fun loadTranslatorDirectory(): List<DirectoryMaker> {
        directoryCache
            ?.takeIf { it.makers.isNotEmpty() && System.currentTimeMillis() - it.savedAt < DIRECTORY_CACHE_TTL_MS }
            ?.let { return it.makers }

        val makers = linkedMapOf<Int, DirectoryMaker>()
        for (page in 0 until DIRECTORY_PAGES) {
            currentCoroutineContext().ensureActive()
            var last = true
            try {
                val raw = Jsoup.connect("$DIRECTORY_API$page")
                    .userAgent(DESKTOP_USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .referrer("https://anissia.net/")
                    .timeout(15000).ignoreContentType(true).maxBodySize(0)
                    .execute().body()
                val root = JSONObject(raw)
                if (root.optString("code") != "ok") break
                val data = root.optJSONObject("data") ?: break
                val content = data.optJSONArray("content")
                if (content != null) {
                    for (i in 0 until content.length()) {
                        val item = content.optJSONObject(i) ?: continue
                        directoryMakerOf(
                            applyNo = item.optInt("applyNo"),
                            name = item.optString("name"),
                            website = item.optString("website"),
                            regTime = item.optLong("regTime"),
                            result = item.optString("result", "PASS")
                        )?.let { makers.putIfAbsent(it.applyNo, it) }
                    }
                }
                last = data.optBoolean("last", true) || (content?.length() ?: 0) == 0
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "TRANSLATOR_DIRECTORY_PAGE_FAILED page=$page", e)
            }
            if (last) break
        }

        // Safety net: if the API shape ever changes, fall back to the shared service.
        if (makers.isEmpty()) {
            runCatching { AnissiaService.getRecentTranslatorWebsites(maxPages = DIRECTORY_PAGES, forceRefresh = true) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Log.w(TAG, "TRANSLATOR_DIRECTORY_FAILED", it)
                }
                .getOrDefault(emptyList())
                .forEachIndexed { index, t ->
                    directoryMakerOf(
                        applyNo = -(index + 1),
                        name = t.name.toString(),
                        website = t.website.toString(),
                        regTime = t.regTime.toString().toLongOrNull() ?: 0L,
                        result = "PASS"
                    )?.let { makers.putIfAbsent(it.applyNo, it) }
                }
        }

        val list = makers.values.toList()
        Log.i(TAG, "TRANSLATOR_DIRECTORY_READY blogs=${list.size}")
        if (list.isNotEmpty()) directoryCache = DirectoryCache(System.currentTimeMillis(), list)
        return list
    }

    private fun directoryMakerOf(applyNo: Int, name: String, website: String, regTime: Long, result: String): DirectoryMaker? {
        if (!result.equals("PASS", true)) return null          // rejected application
        if (name.isBlank()) return null
        val site = normalizeWebsite(website) ?: return null    // "-", junk text, anything with spaces
        val host = hostOf(site)
        if (host.isBlank() || isNonBlogHost(host)) return null
        return DirectoryMaker(applyNo, name.trim(), site, regTime)
    }

    /** A caption's website, if it is usable as a blog to search (no social/video/drive links). */
    private fun usableCreatorSite(raw: String?): String? {
        val site = normalizeWebsite(raw) ?: return null
        val host = hostOf(site)
        return if (host.isBlank() || isNonBlogHost(host)) null else site
    }

    private fun siteKey(site: String): String = site.trimEnd('/').lowercase(Locale.ROOT)

    private fun fetchRecentCaptionPage(page: Int): RecentCaptionPage {
        return try {
            val raw = Jsoup.connect("$RECENT_CAPTION_API$page")
                .userAgent(DESKTOP_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .referrer("https://anissia.net/")
                .timeout(15000).ignoreContentType(true).maxBodySize(0)
                .execute().body()
            val root = JSONObject(raw)
            if (root.optString("code") != "ok") return RecentCaptionPage(emptyList(), false)
            val data = root.optJSONObject("data") ?: return RecentCaptionPage(emptyList(), false)
            val content = data.optJSONArray("content")
            val entries = buildList {
                if (content != null) for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    val name = item.optString("name")
                    val site = usableCreatorSite(item.optString("website")) ?: continue
                    if (name.isNotBlank()) add(name to site)
                }
            }
            RecentCaptionPage(entries, data.optBoolean("last", true) || (content?.length() ?: 0) == 0)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "RECENT_CAPTION_PAGE_FAILED page=$page", e)
            RecentCaptionPage(emptyList(), false)
        }
    }

    /**
     * creator name (normalized) -> blogs that creator registered on recent captions, newest first.
     * Lets us find a creator's blog even when this anime's caption has no website yet ("준비중") and
     * the creator has no website in the translator directory.
     */
    private suspend fun loadRecentCaptionSites(): Map<String, List<String>> {
        recentCaptionCache
            ?.takeIf { it.sitesByCreator.isNotEmpty() && System.currentTimeMillis() - it.savedAt < DIRECTORY_CACHE_TTL_MS }
            ?.let { return it.sitesByCreator }

        val byCreator = linkedMapOf<String, LinkedHashSet<String>>()
        var page = 0
        var reachedEnd = false
        while (page < RECENT_CAPTION_PAGES && !reachedEnd) {
            currentCoroutineContext().ensureActive()
            val pages = (page until minOf(page + RECENT_CAPTION_BATCH, RECENT_CAPTION_PAGES)).toList()
            val results = coroutineScope { pages.map { p -> async(Dispatchers.IO) { fetchRecentCaptionPage(p) } }.awaitAll() }
            for (result in results) { // page order = newest first
                result.entries.forEach { (name, site) ->
                    val key = normalizeCreator(name)
                    if (key.isNotBlank()) byCreator.getOrPut(key) { linkedSetOf() }.add(site)
                }
                if (result.last) reachedEnd = true
            }
            page += pages.size
        }
        val map = byCreator.mapValues { it.value.toList() }
        Log.i(TAG, "RECENT_CAPTION_SITES_READY creators=${map.size} pages=$page")
        if (map.isNotEmpty()) recentCaptionCache = RecentCaptionCache(System.currentTimeMillis(), map)
        return map
    }

    /**
     * 1) Ask Anissia for the creators of [anime] (and the website each registered on this anime).
     * 2) Collect each creator's blogs, in this order:
     *      a) translator directory (approved blogs),
     *      b) recent-captions map (what the creator registered on other recent captions),
     *      c) this anime's own caption website.
     * Every blog is only an entry point: it is searched by title, never used as the file link itself.
     */
    private suspend fun resolveCreatorBlogs(anime: com.lilac.anime.AnissiaAnime): List<CreatorBlog> {
        val captions = try {
            AnissiaService.getSubtitles(anime.animeNo)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "CAPTION_LIST_FAILED animeNo=${anime.animeNo}", e)
            emptyList()
        }
        if (captions.isEmpty()) return emptyList()
        val directory = loadTranslatorDirectory()
        val recent = loadRecentCaptionSites()

        val blogs = linkedMapOf<String, CreatorBlog>()
        for (caption in captions.sortedByDescending { it.updateDate }) {
            val key = normalizeCreator(caption.creator)
            if (key.isBlank()) continue

            // a) Exact name first, then partial ("이름" vs "이름 자막팀"); a creator may have several entries.
            val fromDirectory = directory.filter { normalizeCreator(it.name) == key }.ifEmpty {
                directory.filter {
                    val name = normalizeCreator(it.name)
                    name.length >= 2 && key.length >= 2 && (name.contains(key) || key.contains(name))
                }
            }.sortedByDescending { it.regTime }.map { it.website }
            // b) exact name only: a partial match here could attach another team's blog.
            val fromRecent = recent[key].orEmpty()
            // c)
            val fromCaption = listOfNotNull(usableCreatorSite(caption.website))

            val websites = LinkedHashMap<String, String>() // siteKey -> site, insertion order = priority
            (fromDirectory + fromRecent + fromCaption).forEach { websites.putIfAbsent(siteKey(it), it) }
            Log.d(
                TAG,
                "CREATOR_SITES creator=${caption.creator} directory=${fromDirectory.size} recent=${fromRecent.size} " +
                    "caption=${fromCaption.size} total=${websites.size}"
            )
            if (websites.isEmpty()) {
                Log.d(TAG, "CREATOR_NO_BLOG creator=${caption.creator}")
                continue
            }
            val existing = blogs[key]
            blogs[key] = if (existing == null) {
                CreatorBlog(
                    creator = caption.creator,
                    websites = websites.values.toList(),
                    updateDate = caption.updateDate,
                    episode = caption.episode
                )
            } else {
                val merged = LinkedHashMap<String, String>()
                (existing.websites + websites.values).forEach { merged.putIfAbsent(siteKey(it), it) }
                existing.copy(websites = merged.values.toList())
            }
        }
        return blogs.values.toList()
    }

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
        val searchTitle = anime.subject.takeIf { it.isNotBlank() } ?: koreanTitle

        // Anissia only tells us WHO subtitles this anime. The blog of each creator comes from the
        // translator directory (https://anissia.net/translator/apply), and the blog is searched by
        // title when an option is downloaded. Anissia's own per-anime links are never used.
        val blogs = resolveCreatorBlogs(anime)
        Log.d(
            TAG,
            "PICKER_CREATORS animeNo=${anime.animeNo} title=[${anime.subject}] " +
                "requestedEpisode=$episodeNumber episodeKey=$episodeKey blogs=${blogs.size} " +
                "creators=${blogs.take(20).joinToString { it.creator }}"
        )

        blogs.map { blog ->
            val optionSource = optionSourceKey(blog.creator, blog.website)
            val cached = SubtitleStore.get(context, animeId, episodeKey, episodeNumber, optionSource)
                ?.takeIf { isUsableCachedSubtitle(it) }
            SubtitleOption(
                episode = blog.episode,
                creator = blog.creator,
                updateDate = blog.updateDate,
                website = blog.website,
                cachedPath = cached,
                searchTitle = searchTitle,
                extraWebsites = blog.websites.drop(1)
            )
        }.also { Log.i(TAG, "PICKER_OPTIONS animeNo=${anime.animeNo} options=${it.size} creators=${it.joinToString { option -> option.creator }.take(500)}") }
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
        val overallDeadline = System.currentTimeMillis() + SELECTED_BUDGET_MS
        var result: String? = null
        for (site in listOf(option.website) + option.extraWebsites) {
            if (System.currentTimeMillis() > overallDeadline) break
            result = downloadFromSubtitleWebsite(
                context = context,
                websiteUrl = site,
                title = option.searchTitle.ifBlank { koreanTitle },
                episodeNumber = episodeNumber,
                episodeKey = episodeKey,
                creator = option.creator,
                deadlineMs = minOf(System.currentTimeMillis() + SITE_BUDGET_MS, overallDeadline),
                // The site is a blog home/archive, so only accept posts/files of this anime.
                requireTitleMatch = true
            )
            if (!result.isNullOrBlank()) break
        }
        if (result.isNullOrBlank()) return@withContext null
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
        val overallDeadline = System.currentTimeMillis() + OVERALL_BUDGET_MS
        val resolvedAnime = resolveAnime(koreanTitle, seasonNumber)
        val title = resolvedAnime?.subject?.takeIf { it.isNotBlank() } ?: koreanTitle
        val cached = SubtitleStore.get(context, animeId, episodeKey, episodeNumber, "anissia")
            ?.takeIf { isUsableCachedSubtitle(it) }
        if (cached != null) return@withContext cached

        // Anissia is only asked *who* subtitles this anime. Each creator name is then looked up in
        // Anissia's approved translator directory (https://anissia.net/translator/apply) to get that
        // creator's own blog, and that blog is searched for the anime title.
        val creatorBlogs = if (resolvedAnime != null) {
            runCatching { resolveCreatorBlogs(resolvedAnime) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Log.w(TAG, "CREATOR_BLOGS_FAILED animeNo=${resolvedAnime.animeNo}", it)
                }
                .getOrDefault(emptyList())
        } else emptyList()
        Log.i(TAG, "CREATOR_BLOGS title=[$title] count=${creatorBlogs.size} creators=${creatorBlogs.joinToString { it.creator }.take(300)}")

        val seenSites = linkedSetOf<String>()
        var sites: List<Pair<String, String>> = creatorBlogs
            .flatMap { blog -> blog.websites.map { blog.creator to it } }
            .filter { seenSites.add(siteKey(it.second)) }
        var siteBudgetMs = SITE_BUDGET_MS
        if (sites.isEmpty() && FALLBACK_TO_FULL_DIRECTORY) {
            // Nobody is registered for this anime (or no creator is in the directory): sweep the newest blogs.
            val seen = linkedSetOf<String>()
            sites = loadTranslatorDirectory()
                .sortedByDescending { it.regTime }
                .mapNotNull { maker -> if (seen.add(siteKey(maker.website))) maker.name to maker.website else null }
                .take(MAX_DIRECTORY_SITES)
            siteBudgetMs = DIRECTORY_SITE_BUDGET_MS
            Log.i(TAG, "DIRECTORY_FALLBACK sites=${sites.size}")
        }

        var attemptedFallbacks = 0
        if (sites.isNotEmpty()) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val winner = CompletableDeferred<Triple<String, String, String>?>()
                val permits = Semaphore(DIRECTORY_CONCURRENCY)
                val jobs = sites.map { (creatorName, site) ->
                    scope.launch {
                        permits.withPermit {
                            if (winner.isCompleted || System.currentTimeMillis() > overallDeadline) return@withPermit
                            attemptedFallbacks++
                            Log.d(TAG, "CREATOR_SITE_TRY creator=$creatorName website=$site episode=$episodeNumber")
                            val result = runCatching {
                                downloadFromSubtitleWebsite(
                                    context, site, title, episodeNumber, episodeKey, creatorName,
                                    deadlineMs = minOf(System.currentTimeMillis() + siteBudgetMs, overallDeadline),
                                    requireTitleMatch = true
                                )
                            }.onFailure {
                                if (it is kotlinx.coroutines.CancellationException) throw it
                                Log.w(TAG, "CREATOR_SITE_FAILED creator=$creatorName website=$site", it)
                            }.getOrNull()
                            if (!result.isNullOrBlank() && File(result).isFile && File(result).length() > 0L) {
                                winner.complete(Triple(creatorName, site, result))
                            }
                        }
                    }
                }
                scope.launch { jobs.joinAll(); winner.complete(null) }
                val found = winner.await()
                if (found != null) {
                    val (creatorName, _, path) = found
                    SubtitleStore.save(context, animeId, episodeKey, episodeNumber, "anissia", path)
                    Log.i(TAG, "SUBTITLE_READY source=creator_blog episode=$episodeKey creator=$creatorName path=$path")
                    return@withContext path
                }
            } finally {
                // Stop sites that have not started yet; requests already in flight just finish on their own.
                scope.cancel()
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
        Log.w(TAG, "NO_SUBTITLE_FOUND title=$title episode=$episodeKey creatorBlogs=${creatorBlogs.size} sitesTried=$attemptedFallbacks")
        null
    }

    private suspend fun downloadFromSubtitleWebsite(
        context: Context,
        websiteUrl: String,
        title: String,
        episodeNumber: Int,
        episodeKey: String,
        creator: String,
        deadlineMs: Long = System.currentTimeMillis() + SITE_BUDGET_MS,
        requireTitleMatch: Boolean = false
    ): String? {
        // Never URL-decode an entire URL: '+' and percent-encoded query values may be
        // meaningful signed-link data. Jsoup/URI already resolve escaped DOM links.
        val initialUrl = normalizeWebsite(websiteUrl) ?: return null
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
        val candidateText = HashMap<String, String>()
        val candidatePageTitle = HashMap<String, String>()
        val attempted = linkedSetOf<String>()
        var attemptCounter = 0
        fun addCandidate(raw: String?) {
            val value = normalizeUrl(raw.orEmpty())
            if (!value.startsWith("http", ignoreCase = true)) return
            if (isJunkCandidate(value)) return
            if (isGoogleDrive(value) && isDriveFolder(value)) {
                Log.d(TAG, "CANDIDATE_SKIPPED_DRIVE_FOLDER url=${value.take(160)}")
                return
            }
            candidates += value
        }
        val candidatePageUrl = HashMap<String, String>()

        // 1 = this file is for the requested episode, 0 = unknown, -1 = other episode
        // (or, for unrelated directory blogs, a different anime).
        fun candidateVerdict(url: String): Int {
            val linkCtx = "${candidateText[url].orEmpty()} ${fileNameOf(url)}"
            val pageCtx = candidatePageTitle[url].orEmpty()
            if (requireTitleMatch && !titleMatches("$linkCtx $pageCtx", title)) return -1
            val byLink = episodeVerdict(linkCtx, episodeNumber)
            if (byLink != 0) return byLink
            return episodeVerdict(pageCtx, episodeNumber)
        }

        fun downloadFonts() {
            if (fontCandidates.isEmpty()) return
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

        suspend fun tryCandidates(strongOnly: Boolean): String? {
            val pending = candidates.filter { it !in attempted }.mapNotNull { url ->
                val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext == "7z" || ext == "rar") {
                    // No extractor for these in this service; do not waste time downloading them.
                    attempted += url
                    Log.d(TAG, "CANDIDATE_SKIPPED_UNSUPPORTED_ARCHIVE url=${url.take(160)}")
                    return@mapNotNull null
                }
                val verdict = candidateVerdict(url)
                if (verdict < 0) {
                    attempted += url
                    Log.d(TAG, "CANDIDATE_SKIPPED_OTHER_EPISODE url=${url.take(160)}")
                    return@mapNotNull null
                }
                if (strongOnly && verdict == 0) return@mapNotNull null
                url to verdict
            }.sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenByDescending { scoreLink(it.first, episodeNumber, title) }
                    .thenBy { it.first.length }
            ).take(if (strongOnly) 12 else 10)

            Log.d(TAG, "PENDING strong=$strongOnly ${pending.map { it.first.take(110) }}")
            for ((url, _) in pending) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() > deadlineMs) break
                attempted += url
                val index = attemptCounter++
                val targetDir = File(context.filesDir, "$CACHE_DIR/${safe(title)}/${safe(episodeKey)}").apply { mkdirs() }
                val urlExt = url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
                val targetExt = urlExt.takeIf { it in setOf("zip", "ass", "ssa", "srt", "smi", "sami", "vtt", "sub", "idx", "sup", "ttml", "dfxp", "xml", "sbv", "mpl", "mpl2", "txt", "scc", "lrc") } ?: "bin"
                // Sites are searched concurrently: the name must be unique per site or downloads overwrite each other.
                val siteTag = initialUrl.hashCode().toUInt().toString(16)
                val target = File(targetDir, "anissia_${safe(episodeKey)}_${safe(creator)}_${siteTag}_$index.$targetExt")
                try {
                    val ok = if (isGoogleDrive(url)) {
                        GoogleDriveDownloader.download(url, target, USER_AGENT)
                    } else {
                        downloadDirect(url, target, candidatePageUrl[url] ?: initialUrl)
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
                        downloadFonts()
                        return actual
                    }
                    Log.w(TAG, "CANDIDATE_NO_SUBTITLE index=$index url=${url.substringBefore('?').takeLast(120)} bytes=${target.length()} ext=${target.extension}")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "DOWNLOAD_FAILED url=$url", e)
                    target.delete()
                }
            }
            return null
        }
        pageQueue.add(initialUrl)
        // Desktop parity: a registered Anissia URL may point to a blog home or an old post.
        // Seed a small, episode-ranked list from each site's own index/search endpoint before
        // crawling ordinary links. This is bounded and does not replace the normal downloader.
        discoverSiteSpecificPages(initialUrl, title, episodeNumber).forEach { if (it != initialUrl) pageQueue.addLast(it) }

        // Search more of a creator's archive. The old 12-page ceiling meant older
        // episodes were often invisible when the registered URL pointed at a blog home.
        // Keep a generous safety ceiling and a visited set to prevent loops.
        while (pageQueue.isNotEmpty() && visitedPages.size < MAX_SUBTITLE_DISCOVERY_PAGES && System.currentTimeMillis() < deadlineMs) {
            currentCoroutineContext().ensureActive()
            val queuedUrl = pageQueue.removeFirst()
            // Naver post pages are iframe shells; always read the real PostView document.
            val pageUrl = canonicalNaverPostUrl(queuedUrl) ?: queuedUrl
            if (!visitedPages.add(pageUrl)) continue

            val fetched = fetchHtml(pageUrl, initialUrl)
                ?: naverMobilePostUrl(pageUrl)?.let { fetchHtml(it, initialUrl) }
                ?: continue
            val html = fetched.first
            val finalPageUrl = fetched.second
            Log.i(TAG, "PAGE_FETCH_OK status=${fetched.third} original=$pageUrl final=$finalPageUrl bytes=${html.length}")
            val document = Jsoup.parse(html, finalPageUrl)
            val pageTitle = document.title()
            val sameHostPages = mutableListOf<Pair<String, String>>()

            document.select("a[href]").forEach { element ->
                val rawHref = element.absUrl("href").ifBlank { element.attr("href") }.trim()
                if (rawHref.isBlank()) return@forEach
                val text = element.text().trim()

                // Tistory exposes attached subtitle archives as signed Kakaocdn URLs
                // (for example blog.kakaocdn.net/.../title.zip?credential=...&attach=1).
                // Parse the resolved href from the DOM so HTML-escaped query strings
                // are decoded without losing the signature, then prioritize the visible
                // filename/label for episode matching. http links to known hosts are upgraded to https.
                val normalizedHref = normalizeUrl(rawHref)
                if (text.isNotBlank()) candidateText.putIfAbsent(normalizedHref, text)
                val linkPath = runCatching { URI(normalizedHref).path.orEmpty() }.getOrDefault(normalizedHref.substringBefore('?').substringBefore('#'))
                val linkExt = linkPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (linkExt in setOf("ttf", "otf", "ttc")) fontCandidates += normalizedHref
                else if (looksLikeSubtitleLink(normalizedHref, text, episodeNumber, isSameSite(finalPageUrl, normalizedHref))) addCandidate(normalizedHref)

                val isKakaoAttachment = normalizedHref.contains("kakaocdn.net", true) &&
                    (normalizedHref.contains("attach=1", true) || ATTACHMENT_EXT_AT_END.containsMatchIn(normalizedHref))
                if (isGoogleDrive(normalizedHref) || normalizedHref.contains("attachment", true) || isKakaoAttachment) {
                    addCandidate(normalizedHref)
                }

                if (!normalizedHref.startsWith("javascript:", true) &&
                    !normalizedHref.startsWith("mailto:", true) &&
                    isSameSite(finalPageUrl, normalizedHref)
                ) {
                    sameHostPages += normalizedHref to text
                }
            }

            // Frames hold the real content on some blogs (Naver); read same-blog frames next.
            document.select("iframe[src], frame[src]").forEach { frame ->
                val src = normalizeUrl(frame.absUrl("src").ifBlank { frame.attr("src") })
                if (src.startsWith("http", true) && isSameSite(finalPageUrl, src)) pageQueue.addFirst(src)
            }

            // Everything below scans raw page source. Undo entity/JSON escaping first, otherwise
            // URLs come out with "&amp;", "\/" or trailing "&quot;..." glued on and fail to download.
            val scan = normalizePageText(html)
            // Naver Blog keeps attachments in aPostFiles / JSON data attributes, not in <a> links.
            NAVER_ATTACH_KEY.findAll(scan).forEach { addCandidate(it.groupValues[1]) }
            NAVER_ATTACH_HOST.findAll(scan).forEach { addCandidate(it.value) }
            // Tistory attachment links are commonly emitted as /attachment/... URLs.
            TISTORY_ATTACH.findAll(scan).forEach { addCandidate(it.value) }
            // Some sites only put the direct file URL in inline JS.
            DIRECT_FILE.findAll(scan).forEach { match ->
                val url = normalizeUrl(match.value)
                val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext in setOf("ttf", "otf", "ttc")) fontCandidates += url else addCandidate(url)
            }
            DRIVE_URL.findAll(scan).forEach { addCandidate(it.value.trimEnd(',', ';', ')', ']', '}')) }

            // Follow likely archive/pagination/post links so completed episodes can be found
            // when the Anissia website points to a blog home/archive rather than one post.
            val scoredPages = sameHostPages
                .distinctBy { it.first }
                .map { it.first to scorePageLink(it.first, it.second, title, episodeNumber) }
                .filter { it.second >= 10 }
                .sortedByDescending { it.second }
            scoredPages.take(20).forEach { pageQueue.addLast(it.first) }
            candidates.forEach {
                candidatePageTitle.putIfAbsent(it, pageTitle)
                candidatePageUrl.putIfAbsent(it, finalPageUrl)
            }
            // Do not crawl the whole archive when this page already offers a file for the episode.
            tryCandidates(strongOnly = true)?.let { return it }
        }

        // Anything left: candidates whose episode could not be determined from their
        // link text / file name / page title (conflicting ones were already dropped).
        tryCandidates(strongOnly = false)?.let { return it }

        Log.w(TAG, "NO_SUBTITLE_FILE website=$initialUrl episode=$episodeNumber pages=${visitedPages.size} candidates=${candidates.size} attempted=${attempted.size}")
        return null
    }

    private fun discoverSiteSpecificPages(initialUrl: String, title: String, episode: Int): List<String> {
        val origin = runCatching { URI(initialUrl).let { "${it.scheme}://${it.authority}" } }.getOrNull() ?: return emptyList()
        val host = runCatching { URI(origin).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val posts = linkedMapOf<String, String>()
        fun add(url: String?, label: String?) {
            if (url.isNullOrBlank()) return
            val absolute = runCatching { normalizeUrl(URI(origin).resolve(url.trim()).toString()) }.getOrNull() ?: return
            // Naver post links come in several shapes; keep one canonical form so the same post
            // from the RSS feed and the list API is not counted twice.
            val resolved = canonicalNaverPostUrl(absolute) ?: absolute
            val uri = runCatching { URI(resolved) }.getOrNull() ?: return
            if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return
            val candidateHost = uri.host.orEmpty().lowercase(Locale.ROOT)
            val isNaverHostPair = candidateHost in setOf("blog.naver.com", "m.blog.naver.com") && host in setOf("blog.naver.com", "m.blog.naver.com")
            // blogspot.com redirects to a country domain (blogspot.kr ...); same blog name = same site.
            val isSameBlogger = isBloggerHost(host) && isBloggerHost(candidateHost) &&
                host.substringBefore(".blogspot.") == candidateHost.substringBefore(".blogspot.")
            if (!uri.host.equals(host, true) && !isNaverHostPair && !isSameBlogger) return
            // The first sighting is often an image link with no text; keep the first non-blank label.
            val existing = posts[resolved]
            if (existing == null || (existing.isBlank() && !label.isNullOrBlank())) posts[resolved] = label.orEmpty()
        }
        val queries = listOf(title.trim(), title.trim().replace(Regex("\\s*(?:시즌|season)\\s*\\d+|\\s*\\d+\\s*기$", RegexOption.IGNORE_CASE), "").trim())
            .filter { it.length >= 2 }.distinct()
        val cachedIndex = siteIndexCache[origin]?.takeIf { System.currentTimeMillis() - it.savedAt < SITE_INDEX_TTL_MS }
        if (cachedIndex != null) posts.putAll(cachedIndex.posts)
        try {
            if (cachedIndex == null) when {
                isBloggerHost(host) -> {
                    // Returns the number of entries read, or -1 when the feed could not be fetched/parsed.
                    fun readFeed(feedUrl: String): Int {
                        val raw = try {
                            Jsoup.connect(feedUrl).userAgent(USER_AGENT).referrer("$origin/")
                                .timeout(15000).maxBodySize(0).ignoreContentType(true).execute().body()
                        } catch (e: Exception) {
                            Log.d(TAG, "BLOGGER_FEED_FAILED url=$feedUrl reason=${e.message}")
                            return -1
                        }
                        val feed = runCatching { JSONObject(raw) }.getOrNull()?.optJSONObject("feed") ?: return -1
                        val entries = feed.optJSONArray("entry") ?: return 0
                        for (i in 0 until entries.length()) {
                            val entry = entries.optJSONObject(i) ?: continue
                            val label = entry.optJSONObject("title")?.optString("\$t").orEmpty()
                            val links = entry.optJSONArray("link") ?: continue
                            for (j in 0 until links.length()) {
                                val link = links.optJSONObject(j) ?: continue
                                if (link.optString("rel") == "alternate") add(link.optString("href"), label)
                            }
                        }
                        return entries.length()
                    }
                    // The summary feed is far lighter than the full-content one; fall back if it is refused.
                    fun readFeedAny(params: String): Int {
                        val summary = readFeed("$origin/feeds/posts/summary?alt=json&$params")
                        return if (summary >= 0) summary else readFeed("$origin/feeds/posts/default?alt=json&$params")
                    }
                    // Title search reaches old episodes without paging through the whole archive.
                    for (query in queries) {
                        readFeedAny("max-results=100&q=${URLEncoder.encode(query, "UTF-8")}")
                    }
                    for (start in (1..3000 step 150)) {
                        val before = posts.size
                        val count = readFeedAny("max-results=150&start-index=$start")
                        if (count < 150 || posts.size == before) break
                    }
                }
                host.endsWith(".tistory.com") -> {
                    // RSS only has the newest posts. <link> must be read with the XML parser: the HTML
                    // parser treats <link> as an empty element and the URL text is lost.
                    runCatching {
                        val rss = Jsoup.connect("$origin/rss").userAgent(USER_AGENT).referrer("$origin/")
                            .header("Accept", "application/rss+xml,application/xml;q=0.9,*/*;q=0.8")
                            .ignoreContentType(true).parser(Parser.xmlParser()).timeout(12000).get()
                        rss.select("item").forEach { item -> add(item.selectFirst("link")?.text(), item.selectFirst("title")?.text()) }
                    }.onFailure { Log.d(TAG, "TISTORY_RSS_UNAVAILABLE host=$host reason=${it.message}") }
                    // /search/{query} reaches older episodes. Posts are /123 or, with slug permalinks, /entry/slug.
                    for (query in queries) {
                        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
                        for (page in 1..3) {
                            var matched = 0
                            runCatching {
                                val searchUrl = "$origin/search/$encoded" + if (page > 1) "?page=$page" else ""
                                val doc = Jsoup.connect(searchUrl).userAgent(USER_AGENT).referrer("$origin/").timeout(12000).get()
                                doc.select("a[href]").forEach { a ->
                                    val href = a.attr("href")
                                    if (TISTORY_POST_PATH.containsMatchIn(href)) {
                                        matched++
                                        add(href, a.text())
                                    }
                                }
                            }.onFailure { Log.d(TAG, "TISTORY_SEARCH_FAILED query=$query page=$page reason=${it.message}") }
                            if (matched == 0) break
                        }
                    }
                }
                host == "blog.naver.com" || host == "m.blog.naver.com" -> {
                    val uri = URI(initialUrl)
                    val segments = uri.path.split('/').filter { it.isNotBlank() }
                    val blogId = uri.getQueryParameterCompat("blogId") ?: segments.firstOrNull().orEmpty()
                    if (blogId.isNotBlank()) {
                        // RSS: the simplest, most stable list of a blog's recent posts.
                        runCatching {
                            val rss = Jsoup.connect("https://rss.blog.naver.com/${URLEncoder.encode(blogId, "UTF-8")}.xml")
                                .userAgent(DESKTOP_USER_AGENT).referrer("https://blog.naver.com/$blogId")
                                .header("Accept-Language", ACCEPT_LANGUAGE)
                                .ignoreContentType(true).parser(Parser.xmlParser()).timeout(12000).get()
                            rss.select("item").forEach { item -> add(item.selectFirst("link")?.text(), item.selectFirst("title")?.text()) }
                        }.onFailure { Log.d(TAG, "NAVER_RSS_UNAVAILABLE blogId=$blogId reason=${it.message}") }
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
                else -> {
                    // Custom sites whose directory entry is itself a search URL, e.g.
                    // https://erulabo.com/search?keyword=cate:자막 -> rerun that search with the anime title.
                    val uri = runCatching { URI(initialUrl) }.getOrNull()
                    val param = uri?.rawQuery?.split('&')?.map { it.substringBefore('=') }
                        ?.firstOrNull { it in setOf("keyword", "q", "query", "search", "s") }
                    if (uri != null && param != null && uri.path.orEmpty().contains("search", true)) {
                        val original = uri.getQueryParameterCompat(param).orEmpty()
                        for (query in queries) {
                            // Keep a filter such as "cate:자막" as a second variant.
                            val variants = linkedSetOf(query)
                            if (original.contains(':')) variants += "$query $original"
                            for (variant in variants) {
                                runCatching {
                                    val searchUrl = "$origin${uri.rawPath}?$param=${URLEncoder.encode(variant, "UTF-8")}"
                                    val doc = Jsoup.connect(searchUrl).userAgent(USER_AGENT).referrer("$origin/").timeout(12000).get()
                                    doc.select("a[href]").forEach { a -> add(a.absUrl("href").ifBlank { a.attr("href") }, a.text()) }
                                }.onFailure { Log.d(TAG, "SITE_SEARCH_FAILED host=$host variant=$variant reason=${it.message}") }
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
        // Match the episode against the label and the last path segment only. Whole URLs contain
        // dates (/2024/05/), numeric post ids and blog ids, which gave every post of a given month
        // (or any id that happened to equal the episode number) a false +100.
        val episodeSource = "${fileNameOf(url)} $text".lowercase(Locale.ROOT)
        var score = 0
        if (Regex("(?:^|\\D)0*${episode}(?:화|회|ep|\\D|$)").containsMatchIn(episodeSource)) score += 100
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
        "scc" -> text.contains("\t") && Regex("(?m)^\\d{2}:\\d{2}:\\d{2}:\\d{2}").containsMatchIn(text)
        "lrc" -> Regex("(?m)^\\[\\d{2}:\\d{2}[.]?\\d*\\]").containsMatchIn(text)
        "txt" -> text.contains(" --> ") || text.contains("[Events]", true) || Regex("(?m)^\\{\\d+\\}\\{\\d+\\}").containsMatchIn(text)
        else -> text.contains(" --> ")
    }

    private fun downloadDirect(url: String, target: File, referer: String): Boolean {
        // HttpURLConnection does not follow http->https redirects, which many old blogs
        // and attachment hosts use, so redirects are followed manually.
        var currentUrl = url
        var redirects = 0
        while (true) {
            val connection = (URL(currentUrl).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 60000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgentFor(currentUrl))
                setRequestProperty("Accept-Language", ACCEPT_LANGUAGE)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Referer", referer)
            }
            try {
                val status = connection.responseCode
                if (status in intArrayOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank() || ++redirects > 8) {
                        Log.w(TAG, "HTTP_REDIRECT_FAILED status=$status redirects=$redirects url=${currentUrl.take(220)}")
                        return false
                    }
                    currentUrl = runCatching { URL(URL(currentUrl), location).toString() }.getOrNull() ?: return false
                    continue
                }
                val contentType = connection.contentType.orEmpty()
                val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
                if (status !in 200..299) {
                    Log.w(TAG, "HTTP_DOWNLOAD_FAILED status=$status type=$contentType url=${url.take(220)} final=$currentUrl")
                    return false
                }
                var total = 0L
                var tooLarge = false
                connection.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_DOWNLOAD_BYTES) { tooLarge = true; break }
                            out.write(buffer, 0, n)
                        }
                    }
                }
                if (tooLarge) {
                    Log.w(TAG, "HTTP_DOWNLOAD_TOO_LARGE type=$contentType url=${url.take(220)}")
                    target.delete()
                    return false
                }
                Log.i(TAG, "HTTP_DOWNLOAD_RESPONSE status=$status type=$contentType disposition=${disposition.take(120)} bytes=${target.length()} url=${url.take(160)} final=$currentUrl")
                if (target.length() == 0L) Log.w(TAG, "HTTP_DOWNLOAD_ZERO type=$contentType url=${url.take(220)}")
                return target.length() > 0L
            } finally {
                connection.disconnect()
            }
        }
    }

    // ------------------------------------------------------------------
    // URL / page helpers for blog hosts (Naver Blog, Tistory, Blogger)
    // ------------------------------------------------------------------

    private fun hostOf(url: String): String =
        runCatching { URL(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")

    private fun isNaverHost(host: String): Boolean =
        host == "naver.com" || host.endsWith(".naver.com") || host.endsWith(".naver.net") || host.endsWith(".pstatic.net")

    private fun isBloggerHost(host: String): Boolean = host.contains(".blogspot.")

    /** Naver serves its PC pages to desktop browsers; m.blog.naver.com keeps the mobile UA. */
    private fun userAgentFor(url: String): String {
        val host = hostOf(url)
        return if (isNaverHost(host) && host != "m.blog.naver.com") DESKTOP_USER_AGENT else USER_AGENT
    }

    /** Plain-http links to these hosts are blocked by Android's cleartext policy, and they all speak https. */
    private fun shouldUpgradeToHttps(host: String): Boolean =
        isNaverHost(host) || isBloggerHost(host) ||
            host == "tistory.com" || host.endsWith(".tistory.com") ||
            host.endsWith(".kakaocdn.net") || host.endsWith(".daumcdn.net") ||
            host.endsWith(".google.com") || host.endsWith(".googleusercontent.com") ||
            host == "dropbox.com" || host.endsWith(".dropbox.com")

    private fun normalizeUrl(raw: String): String {
        var url = raw.trim()
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u002F", "/")
            .replace("\\/", "/")
        // Blogger/Docs content often wraps links as https://www.google.com/url?q=<real url>&sa=...
        if (hostOf(url).let { it == "www.google.com" || it == "google.com" } && url.contains("/url?")) {
            Regex("[?&](?:q|url)=([^&]+)").find(url)?.groupValues?.get(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?.takeIf { it.startsWith("http", true) }
                ?.let { url = it }
        }
        if (url.startsWith("//")) url = "https:$url"
        if (url.startsWith("http://", ignoreCase = true) && shouldUpgradeToHttps(hostOf(url))) {
            url = "https://" + url.substring(7)
        }
        val host = hostOf(url)
        if (host == "dropbox.com" || host.endsWith(".dropbox.com")) {
            url = when {
                url.contains("dl=0") -> url.replace("dl=0", "dl=1")
                url.contains("dl=1") -> url
                else -> url + (if (url.contains('?')) "&" else "?") + "dl=1"
            }
        }
        return url
    }

    /** Anissia's website field is free text: "blog.naver.com/x" or "x.tistory.com" without a scheme is common. */
    private fun normalizeWebsite(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || value.contains(' ')) return null
        val withScheme = when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            Regex("""[\w-]+(?:\.[\w-]+)+(?:/.*)?""").matches(value) -> "https://$value"
            else -> return null
        }
        return normalizeUrl(withScheme)
    }

    /**
     * Undo the escaping that hides URLs in raw page source: HTML entities (&quot; &amp;),
     * JSON slash escapes (\/) and \uXXXX escapes. Run before scanning source with regexes.
     */
    private fun normalizePageText(html: String): String =
        Parser.unescapeEntities(html, false)
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("\\u002F", "/")
            .replace("\\/", "/")

    /**
     * blog.naver.com/{id}/{logNo} is only an <iframe> shell; the post body and its attachment
     * list live at PostView.naver. Convert every known post-URL shape to that address.
     */
    private fun canonicalNaverPostUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "blog.naver.com" && host != "m.blog.naver.com") return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val first = segments.firstOrNull().orEmpty()
        val (blogId, logNo) = when {
            first.equals("PostView.naver", true) || first.equals("PostView.nhn", true) ->
                uri.getQueryParameterCompat("blogId").orEmpty() to uri.getQueryParameterCompat("logNo").orEmpty()
            segments.size >= 2 && segments[1].all { it.isDigit() } -> first to segments[1]
            segments.size == 1 && !first.contains('.') -> first to uri.getQueryParameterCompat("logNo").orEmpty()
            else -> return null
        }
        if (blogId.isBlank() || logNo.isBlank() || !logNo.all { it.isDigit() }) return null
        return "https://blog.naver.com/PostView.naver?blogId=${URLEncoder.encode(blogId, "UTF-8")}" +
            "&logNo=$logNo&redirect=Dlog&widgetTypeCall=true&directAccess=false"
    }

    /** Fallback when PostView.naver is refused: the mobile post address. */
    private fun naverMobilePostUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host?.lowercase(Locale.ROOT) != "blog.naver.com") return null
        val blogId = uri.getQueryParameterCompat("blogId") ?: return null
        val logNo = uri.getQueryParameterCompat("logNo") ?: return null
        return "https://m.blog.naver.com/${URLEncoder.encode(blogId, "UTF-8")}/$logNo"
    }

    private fun naverBlogIdOf(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "blog.naver.com" && host != "m.blog.naver.com") return null
        val first = uri.path.orEmpty().split('/').firstOrNull { it.isNotBlank() }.orEmpty()
        return if (first.contains('.')) uri.getQueryParameterCompat("blogId")?.takeIf { it.isNotBlank() }
        else first.takeIf { it.isNotBlank() }
    }

    /** Same blog? Naver blogs are told apart by blogId, blogspot.* country domains by blog name. */
    private fun isSameSite(a: String, b: String): Boolean {
        val hostA = hostOf(a)
        val hostB = hostOf(b)
        if (hostA.isBlank() || hostB.isBlank()) return false
        val naverBlogHosts = setOf("blog.naver.com", "m.blog.naver.com")
        if (hostA in naverBlogHosts && hostB in naverBlogHosts) {
            val idA = naverBlogIdOf(a)
            return idA != null && idA.equals(naverBlogIdOf(b), ignoreCase = true)
        }
        if (isBloggerHost(hostA) && isBloggerHost(hostB)) {
            return hostA.substringBefore(".blogspot.") == hostB.substringBefore(".blogspot.")
        }
        return hostA.removePrefix("www.") == hostB.removePrefix("www.")
    }

    private fun fetchHtml(pageUrl: String, referer: String): Triple<String, String, Int>? {
        return try {
            val response = Jsoup.connect(pageUrl)
                .userAgent(userAgentFor(pageUrl))
                .referrer(referer)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .timeout(20000)
                .followRedirects(true)
                .maxBodySize(0)
                .ignoreHttpErrors(true)
                .execute()
            val status = response.statusCode()
            if (status !in 200..299) {
                Log.w(TAG, "PAGE_FETCH_HTTP_FAILED status=$status url=$pageUrl final=${response.url()}")
                null
            } else {
                Triple(response.body(), response.url().toString(), status)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PAGE_FETCH_FAILED url=$pageUrl", e)
            null
        }
    }

    private fun fileNameOf(url: String): String {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url.substringBefore('?').substringBefore('#'))
        val last = path.substringAfterLast('/')
        return runCatching { java.net.URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
    }

    /** 1 = text refers to [episode], -1 = text clearly refers to other episode(s), 0 = no information. */
    private fun episodeVerdict(text: String, episode: Int): Int {
        if (episode <= 0 || text.isBlank()) return 0
        if (SubtitleEpisodeMatcher.matches(text, episode)) return 1
        for (m in EPISODE_RANGE.findAll(text)) {
            val lo = m.groupValues[1].toIntOrNull() ?: continue
            val hi = m.groupValues[2].toIntOrNull() ?: continue
            if (lo <= hi && episode in lo..hi) return 1
        }
        val mentioned = EXPLICIT_EPISODE.findAll(text).mapNotNull {
            it.groupValues[1].ifBlank { it.groupValues[2] }.toIntOrNull()
        }.toList()
        return if (mentioned.isNotEmpty() && episode !in mentioned) -1 else 0
    }

    private fun titleMatches(text: String, title: String): Boolean {
        fun norm(v: String) = v.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
        val haystack = norm(text)
        val whole = norm(title)
        if (whole.length >= 2 && haystack.contains(whole)) return true
        return title.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .any { haystack.contains(it) }
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

    private fun looksLikeSubtitleLink(url: String, text: String, episode: Int, sameSite: Boolean = false): Boolean {
        val value = (url + " " + text).lowercase(Locale.ROOT)
        if (value.contains("youtube.com") || value.contains("youtu.be")) return false
        if (isJunkCandidate(url)) return false
        if (isGoogleDrive(url)) return !isDriveFolder(url)
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url.substringBefore('?').substringBefore('#'))
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in setOf("ass", "ssa", "srt", "smi", "sami", "vtt", "sub", "idx", "sup", "ttml", "dfxp", "xml", "sbv", "mpl", "mpl2", "txt", "scc", "lrc", "zip", "7z", "rar", "ttf", "otf", "ttc")) return true
        // Same-site links are pages (they are crawled separately); extension-less external links
        // only count when the visible label itself names the episode, never from the URL alone.
        if (sameSite) return false
        return Regex("(?:^|\\D)0*${episode}(?:화|회|ep|\\b)").containsMatchIn(text.lowercase(Locale.ROOT))
    }

    private fun scoreLink(url: String, episode: Int, title: String = ""): Int {
        var score = 0
        val value = url.lowercase(Locale.ROOT)
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url.substringBefore('?').substringBefore('#'))
        val pathAndQuery = "$path ${runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")}".lowercase(Locale.ROOT)
        if (Regex("(?:^|\\D)0*${episode}(?:\\D|$)").containsMatchIn(fileNameOf(url).lowercase(Locale.ROOT))) score += 100
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in setOf("ass", "ssa")) score += 40
        if (ext in setOf("srt", "vtt", "smi", "sami")) score += 30
        if (ext == "zip") score += 20
        if (ext in setOf("7z", "rar")) score += 8
        if (isGoogleDrive(value)) score += 60
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

    private val NON_SUBTITLE_EXT = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "avif",
        "mp4", "mkv", "avi", "mov", "webm", "mp3", "m4a", "flac", "aac",
        "js", "css", "json", "html", "htm", "php", "woff", "woff2"
    )
    private val JUNK_HOSTS = listOf(
        "googleusercontent.com", "bp.blogspot.com", "blogger.com", "gstatic.com",
        "googleapis.com", "googletagmanager.com", "google-analytics.com", "w3.org", "schema.org"
    )

    /** Images, scripts, feeds and Blogger infrastructure links are never subtitle files. */
    private fun isJunkCandidate(url: String): Boolean {
        val host = hostOf(url)
        if (JUNK_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        val path = runCatching { URI(url).path.orEmpty() }
            .getOrDefault(url.substringBefore('?').substringBefore('#')).lowercase(Locale.ROOT)
        if (path.substringAfterLast('.', "") in NON_SUBTITLE_EXT) return true
        return path.contains("/feeds/") || path.startsWith("/search")
    }

    private fun isDriveFolder(url: String): Boolean =
        url.contains("/drive/folders/", true) || url.contains("/folderview", true)

    private fun isGoogleDrive(url: String): Boolean =
        url.contains("drive.google.com", true) || url.contains("drive.usercontent.google.com", true)

    private fun safe(value: String): String =
        value.trim().replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").take(100).ifBlank { "subtitle" }
}
