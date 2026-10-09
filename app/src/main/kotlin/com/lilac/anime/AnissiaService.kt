package com.lilac.anime

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Anissia metadata access.
 *
 * This implementation follows the API calls made by the current Anissia web UI:
 *
 *   GET /anime/list/{page}?q={query}
 *   GET /anime/autocorrect?q={query}
 *   GET /anime/animeNo/{animeNo}
 *   GET /anime/caption/animeNo/{animeNo}
 *
 * The /anime/list endpoint is the important part: it is the catalogue/search
 * endpoint and contains completed as well as currently airing titles. The
 * schedule API is deliberately not used as the catalogue.
 */
data class AnissiaSubtitle(
    val episode: String,
    val updateDate: String,
    val website: String?,
    val creator: String
)

data class AnissiaAnime(
    val animeNo: Long,
    val subject: String,
    val originalSubject: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val website: String? = null
)

object AnissiaService {
    private const val TAG = "Anissia"
    private const val API_BASE = "https://api.anissia.net"
    private const val CATALOG_TTL_MS = 6L * 60L * 60L * 1000L
    private const val PAGE_SIZE = 30
    private const val MAX_CATALOG_PAGES = 200

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    private data class AnimePage(
        val content: List<AnissiaAnime>,
        val pageNumber: Int,
        val totalPages: Int,
        val totalElements: Int,
        val last: Boolean
    )

    @Volatile
    private var cachedCatalog: List<AnissiaAnime> = emptyList()

    @Volatile
    private var catalogLoadedAt = 0L

    private suspend fun get(
        urlString: String,
        accept: String = "application/json, text/plain, */*"
    ): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "REQUEST $urlString")
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            }

            Log.d(TAG, "HTTP=$code length=${body.length}")
            if (code !in 200..299) {
                throw IllegalStateException("Anissia HTTP $code")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Current timetable. Kept for compatibility with existing callers.
     * This is NOT used for title resolution.
     */
    suspend fun getSchedule(day: Int): List<AnissiaAnime> {
        require(day in 0..8)
        val body = get("$API_BASE/anime/schedule/$day")
        return parseAnimeArray(body, "SCHEDULE day=$day")
    }

    /**
     * Resolve a title through the same catalogue search endpoint used by
     * anissia.net/anime. This works for END/completed titles as well as ON/new
     * titles.
     */
    suspend fun findAnime(title: String): AnissiaAnime? {
        val raw = title.trim()
        val cleaned = cleanTitle(raw)
        if (cleaned.isBlank()) return null

        Log.d(TAG, "FIND title=$raw cleaned=$cleaned")

        // 1) The Anissia web UI uses autocomplete while typing. Prefer an
        // exact/similar autocomplete hit because it gives us the stable animeNo.
        val auto = try {
            getAutocomplete(cleaned)
        } catch (e: Exception) {
            Log.w(TAG, "AUTOCORRECT_FAILED query=$cleaned", e)
            emptyList()
        }

        val exactAuto = auto.firstOrNull { (_, subject) ->
            cleanTitle(subject).equals(cleaned, ignoreCase = true)
        } ?: auto.firstOrNull { (_, subject) ->
            titleSimilarity(cleaned, cleanTitle(subject)) >= 0.82
        }

        if (exactAuto != null) {
            getAnime(exactAuto.first)?.let {
                Log.d(TAG, "FOUND_AUTOCORRECT animeNo=${it.animeNo} subject=${it.subject}")
                return it
            }
        }

        // 2) /anime/list/{page}?q= is the actual catalogue search endpoint.
        // Unlike schedule/0..8 this includes completed and ongoing works.
        val candidates = linkedSetOf<String>().apply {
            add(raw)
            if (cleaned != raw) add(cleaned)
        }

        for (query in candidates) {
            val first = try {
                getAnimeListPage(0, query)
            } catch (e: Exception) {
                Log.w(TAG, "LIST_SEARCH_FAILED query=$query", e)
                null
            } ?: continue

            findBest(first.content, cleaned)?.let {
                Log.d(TAG, "FOUND_LIST animeNo=${it.animeNo} subject=${it.subject}")
                return it
            }

            // A title query can still return multiple pages. Search every
            // returned page rather than arbitrarily stopping at page 4.
            if (first.totalPages > 1) {
                val pageCount = first.totalPages.coerceAtMost(MAX_CATALOG_PAGES)
                val extra = coroutineScope {
                    (1 until pageCount).map { p ->
                        async {
                            runCatching { getAnimeListPage(p, query).content }
                                .getOrElse {
                                    Log.w(TAG, "LIST_PAGE_FAILED page=$p query=$query", it)
                                    emptyList()
                                }
                        }
                    }.awaitAll().flatten()
                }
                findBest(extra, cleaned)?.let {
                    Log.d(TAG, "FOUND_LIST_PAGE animeNo=${it.animeNo} subject=${it.subject}")
                    return it
                }
            }
        }

        Log.w(TAG, "NOT_FOUND title=$raw")
        return null
    }


    /**
     * Full Anissia catalogue. The web UI paginates /anime/list with 30 items
     * per page. This method follows the real pagination metadata instead of
     * crawling the SPA HTML.
     */
    suspend fun getAnimeCatalog(forceRefresh: Boolean = false): List<AnissiaAnime> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedCatalog.isNotEmpty() &&
            now - catalogLoadedAt < CATALOG_TTL_MS
        ) {
            return cachedCatalog
        }

        val first = try {
            getAnimeListPage(0, "")
        } catch (e: Exception) {
            Log.w(TAG, "CATALOG_FIRST_PAGE_FAILED", e)
            return cachedCatalog
        }

        val pageCount = minOf(first.totalPages.coerceAtLeast(1), MAX_CATALOG_PAGES)
        val all = LinkedHashMap<Long, AnissiaAnime>()
        first.content.forEach { all[it.animeNo] = it }

        if (pageCount > 1) {
            val pages = coroutineScope {
                (1 until pageCount).map { page ->
                    async {
                        runCatching { getAnimeListPage(page, "").content }
                            .getOrElse {
                                Log.w(TAG, "CATALOG_PAGE_FAILED page=$page", it)
                                emptyList()
                            }
                    }
                }.awaitAll()
            }
            pages.flatten().forEach { all[it.animeNo] = it }
        }

        val result = all.values
            .filter { it.animeNo > 0 && it.subject.isNotBlank() }
            .sortedBy { cleanTitle(it.subject).lowercase(Locale.ROOT) }

        cachedCatalog = result
        catalogLoadedAt = System.currentTimeMillis()
        Log.d(TAG, "CATALOG_READY count=${result.size} pages=$pageCount")
        return result
    }

    /**
     * Exact API used by the web detail page. It contains captions inline.
     */
    suspend fun getAnime(animeNo: Long): AnissiaAnime? {
        if (animeNo <= 0) return null
        val body = get("$API_BASE/anime/animeNo/$animeNo")
        return parseAnimeObject(body)
    }

    suspend fun getSubtitles(animeNo: Long): List<AnissiaSubtitle> {
        require(animeNo > 0)

        // /anime/animeNo/{id} is the request made by the web UI when opening
        // an anime. Its response already contains captions. Use it first.
        val detail = runCatching { getAnime(animeNo) }.getOrNull()
        if (detail != null) {
            val captions = getCaptionsFromDetail(detail, animeNo)
            if (captions.isNotEmpty()) return captions
        }

        // Keep the dedicated caption endpoint as a reliable fallback.
        val body = get("$API_BASE/anime/caption/animeNo/$animeNo")
        return parseCaptionArray(body)
    }

    suspend fun getEpisodeSubtitle(animeNo: Long, episode: String): AnissiaSubtitle? {
        val subtitles = getSubtitles(animeNo)
        val normalizedTarget = normalizeEpisode(episode)

        subtitles.firstOrNull {
            normalizeEpisode(it.episode) == normalizedTarget
        }?.let { return it }

        val target = episode.toDoubleOrNull() ?: return null
        return subtitles
            .mapNotNull { subtitle ->
                subtitle.episode.toDoubleOrNull()?.let { n -> subtitle to n }
            }
            .minByOrNull { kotlin.math.abs(it.second - target) }
            ?.first
    }

    private suspend fun getAnimeListPage(page: Int, query: String): AnimePage {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = get("$API_BASE/anime/list/$page?q=$encoded")
        val root = JSONObject(body)
        val data = root.optJSONObject("data")
            ?: throw IllegalStateException("Anissia anime/list response has no data")

        val contentJson = data.optJSONArray("content") ?: JSONArray()
        val content = parseAnimeObjects(contentJson)

        return AnimePage(
            content = content,
            pageNumber = data.optInt("number", page),
            totalPages = data.optInt("totalPages", 1),
            totalElements = data.optInt("totalElements", content.size),
            last = data.optBoolean("last", true)
        )
    }

    private suspend fun getAutocomplete(query: String): List<Pair<Long, String>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = get("$API_BASE/anime/autocorrect?q=$encoded")
        val root = JSONObject(body)
        val data = root.optJSONArray("data") ?: JSONArray()
        val result = ArrayList<Pair<Long, String>>(data.length())

        for (i in 0 until data.length()) {
            val value = data.optString(i).trim()
            if (value.isBlank()) continue
            val firstSpace = value.indexOf(' ')
            if (firstSpace <= 0) continue
            val no = value.substring(0, firstSpace).toLongOrNull() ?: continue
            val subject = value.substring(firstSpace + 1).trim()
            if (subject.isNotBlank()) result += no to subject
        }
        return result
    }

    private fun parseAnimeObject(body: String): AnissiaAnime? {
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: return null
        val anime = parseAnimeJson(data) ?: return null

        // Store the inline caption list temporarily through the process-local
        // map so getSubtitles() can consume the exact web response without
        // introducing a public model change.
        val captions = data.optJSONArray("captions")
        if (captions != null) {
            synchronized(detailCaptionCache) {
                detailCaptionCache[anime.animeNo] = parseCaptionObjects(captions)
            }
        }
        return anime
    }

    private fun getCaptionsFromDetail(detail: AnissiaAnime, animeNo: Long): List<AnissiaSubtitle> {
        synchronized(detailCaptionCache) {
            return detailCaptionCache.remove(animeNo).orEmpty()
                .sortedWith(
                    compareBy<AnissiaSubtitle> { episodeNumber(it.episode) }
                        .thenByDescending { it.updateDate }
                )
        }
    }

    private val detailCaptionCache = HashMap<Long, List<AnissiaSubtitle>>()

    private fun parseCaptionArray(body: String): List<AnissiaSubtitle> {
        val root = JSONObject(body)
        val data = root.opt("data")
        return when (data) {
            is JSONArray -> parseCaptionObjects(data)
            is JSONObject -> parseCaptionObjects(data.optJSONArray("content") ?: JSONArray())
            else -> emptyList()
        }
    }

    private fun parseCaptionObjects(array: JSONArray): List<AnissiaSubtitle> {
        val result = mutableListOf<AnissiaSubtitle>()
        Log.d(TAG, "CAPTION_PARSE count=${array.length()}")
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val episode = item.optString("episode").trim()
            if (episode.isBlank()) continue
            result += AnissiaSubtitle(
                episode = episode,
                updateDate = item.optString("updDt").trim(),
                website = decodeUrl(item.optString("website")).takeIf { it.isNotBlank() },
                creator = item.optString("name").trim()
            )
        }
        Log.d(
            TAG,
            "CAPTION_PARSE_RESULT count=${result.size} " +
                "episodes=${result.take(20).joinToString { it.episode }} " +
                "withWebsite=${result.count { !it.website.isNullOrBlank() }}"
        )
        return result
    }

    private fun parseAnimeObjects(array: JSONArray): List<AnissiaAnime> {
        val result = ArrayList<AnissiaAnime>(array.length())
        for (i in 0 until array.length()) {
            parseAnimeJson(array.optJSONObject(i))?.let { result += it }
        }
        return result
    }

    private fun parseAnimeJson(item: JSONObject?): AnissiaAnime? {
        if (item == null) return null
        val animeNo = item.optLong("animeNo", -1L)
        val subject = item.optString("subject").trim()
        if (animeNo <= 0 || subject.isBlank()) return null

        return AnissiaAnime(
            animeNo = animeNo,
            subject = subject,
            originalSubject = item.optString("originalSubject")
                .trim().takeIf { it.isNotBlank() },
            startDate = item.optString("startDate")
                .trim().takeIf { it.isNotBlank() },
            endDate = item.optString("endDate")
                .trim().takeIf { it.isNotBlank() },
            website = decodeUrl(item.optString("website"))
                .takeIf { it.isNotBlank() }
        )
    }

    private fun parseAnimeArray(body: String, logPrefix: String): List<AnissiaAnime> {
        val root = JSONObject(body)
        val data = root.opt("data")
        val array = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("content") ?: JSONArray()
            else -> JSONArray()
        }
        val result = parseAnimeObjects(array)
        Log.d(TAG, "$logPrefix count=${result.size}")
        return result
    }

    private fun findBest(list: List<AnissiaAnime>, cleaned: String): AnissiaAnime? {
        val exact = list.firstOrNull {
            cleanTitle(it.subject).equals(cleaned, true) ||
                cleanTitle(it.originalSubject.orEmpty()).equals(cleaned, true)
        }
        if (exact != null) return exact

        return list.asSequence()
            .map { it to titleSimilarity(cleaned, cleanTitle(it.subject)) }
            .filter { it.second >= 0.72 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun titleSimilarity(a: String, b: String): Double {
        val at = titleTokens(a)
        val bt = titleTokens(b)
        if (at.isEmpty() || bt.isEmpty()) return 0.0
        val intersection = at.intersect(bt).size.toDouble()
        val union = at.union(bt).size.toDouble().coerceAtLeast(1.0)
        val containment = intersection / minOf(at.size, bt.size).coerceAtLeast(1)
        return (intersection / union * 0.55) + (containment * 0.45)
    }

    private fun titleTokens(value: String): Set<String> {
        val normalized = cleanTitle(value).lowercase(Locale.ROOT)
            .replace(Regex("시즌\\s*(\\d+)"), " season$1 ")
            .replace(Regex("\\bpart\\s*(\\d+)\\b", RegexOption.IGNORE_CASE), " season$1 ")
            .replace(Regex("\\bseason\\s*(\\d+)\\b", RegexOption.IGNORE_CASE), " season$1 ")
            .replace(Regex("\\bs0*(\\d+)\\b", RegexOption.IGNORE_CASE), " season$1 ")
            .replace(Regex("(\\d+)\\s*기\\b"), " season$1 ")
        return normalized
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun normalizeEpisode(value: String): String {
        val match = Regex("(?i)^(?:S\\d+E)?\\s*(\\d+(?:\\.\\d+)?)$").find(value.trim())
        return match?.groupValues?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
            ?: value.trim().lowercase(Locale.ROOT)
    }

    private fun cleanTitle(value: String): String {
        return value
            .replace(Regex("\\[[^\\]]*]"), "")
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("【[^】]*】"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun episodeNumber(value: String): Double =
        value.toDoubleOrNull() ?: Double.MAX_VALUE

    private fun decodeUrl(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
