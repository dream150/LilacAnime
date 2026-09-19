package com.lilac.anime.network

import android.util.Log
import com.lilac.anime.core.model.ChapterSkipSegment
import com.lilac.anime.data.matcher.HangulSimilarityMatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLEncoder
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves OP/ED timestamps for ONLINE playback only.
 *
 * This service deliberately does not know anything about the offline OP/ED
 * fingerprint/analyzer path. It only returns ChapterSkipSegment values that
 * PlayerScreen can feed into the existing skip UI/auto-skip logic.
 */
object OnlineAniSkipService {
    private const val TAG = "OnlineAniSkip"
    private const val ANI_SKIP_BASE = "https://api.aniskip.com/v2/skip-times"
    private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"

    private val allowedTypes = setOf("op", "ed", "mixed-op", "mixed-ed")

    // Some Android networks have broken/unavailable IPv6 routing. Jikan can
    // resolve to an IPv6 address first, which previously caused ENETUNREACH
    // before the request ever reached the API. Prefer IPv4 addresses while
    // keeping IPv6 as a fallback if IPv4 is unavailable.
    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return Dns.SYSTEM.lookup(hostname)
                .sortedBy { address -> if (address is Inet4Address) 0 else 1 }
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(ipv4FirstDns)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val malIdCache = ConcurrentHashMap<String, Int>()
    private val segmentCache = ConcurrentHashMap<String, List<ChapterSkipSegment>>()

    /**
     * Returns emptyList() on any network/matching/API failure.
     * A failure must never affect video playback.
     *
     * The online lookup deliberately sends episodeLength=0. AniSkip then
     * returns the canonical episodeLength for each segment, which PlayerScreen
     * uses to map AniSkip timestamps onto the actual mpv timeline.
     */
    suspend fun getSkipSegments(
        title: String,
        episodeNumber: Int,
        episodeLengthSeconds: Int = 0
    ): List<ChapterSkipSegment> {
        Log.d(TAG, "GET_SKIP_START title=\"$title\" episode=$episodeNumber length=$episodeLengthSeconds")
        if (title.isBlank() || episodeNumber <= 0) {
            Log.d(TAG, "GET_SKIP_ABORT invalid title/episode")
            return emptyList()
        }

        val malId = resolveMalId(title)
        if (malId == null) {
            Log.w(TAG, "MAL_ID_NOT_FOUND title=\"$title\"")
            return emptyList()
        }
        Log.d(TAG, "MAL_ID_RESOLVED title=\"$title\" malId=$malId")

        // The local player duration must never be used as AniSkip's
        // episodeLength filter. Different video cuts commonly have very
        // different lengths, and sending the local duration can make AniSkip
        // return 404 even when skip data exists.
        val cacheKey = "$malId:$episodeNumber:0"

        segmentCache[cacheKey]?.let { cached ->
            Log.d(TAG, "CACHE_HIT key=$cacheKey segments=${cached.size}")
            return cached
        }
        Log.d(TAG, "CACHE_MISS key=$cacheKey")

        val query = buildString {
            append("types=op&types=ed&types=mixed-op&types=mixed-ed")
            append("&episodeLength=0")
        }

        val url = "$ANI_SKIP_BASE/$malId/$episodeNumber?$query"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "LilacAnime Android")
            .get()
            .build()

        Log.d(TAG, "ANISKIP_REQUEST url=$url")
        val result = runCatching {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "ANISKIP_RESPONSE code=${response.code} success=${response.isSuccessful}")
                if (!response.isSuccessful) {
                    Log.w(TAG, "ANISKIP_HTTP_ERROR code=${response.code} episode=$episodeNumber malId=$malId")
                    return@use emptyList<ChapterSkipSegment>()
                }
                val body = response.body?.string().orEmpty()
                Log.d(TAG, "ANISKIP_BODY length=${body.length}")
                parseSkipResponse(body)
            }
        }.onFailure { error ->
            Log.e(TAG, "ANISKIP_NETWORK_ERROR ${error.javaClass.simpleName}: ${error.message}", error)
        }.getOrElse { emptyList() }

        Log.d(TAG, "ANISKIP_PARSED rawSegments=${result.size}")

        val cleaned = result
            .filter { it.startTime >= 0.0 && it.endTime > it.startTime }
            .distinctBy { "${it.type}:${it.startTime}:${it.endTime}" }
            .sortedBy { it.startTime }

        segmentCache[cacheKey] = cleaned
        Log.d(TAG, "GET_SKIP_DONE malId=$malId episode=$episodeNumber segments=${cleaned.size} " +
            cleaned.joinToString(prefix = "[", postfix = "]") { "${it.type}:${it.startTime}-${it.endTime}" })
        return cleaned
    }

    private fun parseSkipResponse(body: String): List<ChapterSkipSegment> {
        if (body.isBlank()) {
            Log.w(TAG, "PARSE_EMPTY_BODY")
            return emptyList()
        }

        return runCatching {
            val root = JSONObject(body)
            val results = root.optJSONArray("results") ?: return@runCatching emptyList<ChapterSkipSegment>()
            Log.d(TAG, "PARSE_RESULTS count=${results.length()}")

            buildList<ChapterSkipSegment> {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val skipType = item.optString("skipType")
                    if (skipType !in allowedTypes) continue

                    val interval = item.optJSONObject("interval") ?: item
                    val start = interval.optDouble("startTime", Double.NaN)
                    val end = interval.optDouble("endTime", Double.NaN)
                    val episodeLength = item.optDouble("episodeLength", 0.0)

                    if (start.isFinite() && end.isFinite() && end > start) {
                        add(
                            ChapterSkipSegment(
                                type = skipType,
                                startTime = start,
                                endTime = end,
                                episodeLength = episodeLength
                            )
                        )
                    }
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "PARSE_ERROR ${error.javaClass.simpleName}: ${error.message}", error)
        }.getOrElse { emptyList<ChapterSkipSegment>() }
    }

    private suspend fun resolveMalId(title: String): Int? {
        // Only BD tags are removed. Season information (e.g. "2기") is kept
        // exactly as supplied and is included in the NamuWiki title search.
        val searchTitle = separateKoreanTitle(removeBdTag(title))
        val normalized = HangulSimilarityMatcher.filterNoise(searchTitle)
        Log.d(TAG, "MAL_SEARCH_START title=\"$title\" searchTitle=\"$searchTitle\" normalized=\"$normalized\"")
        if (normalized.isBlank()) return null

        malIdCache[normalized]?.let { cachedId ->
            Log.d(TAG, "MAL_CACHE_HIT normalized=\"$normalized\" malId=$cachedId")
            return cachedId
        }

        val namu = searchNamuWikiTitle(searchTitle)
        if (namu == null) {
            Log.w(TAG, "NAMU_NO_MATCH title=\"$searchTitle\"")
            return null
        }

        Log.d(
            TAG,
            "NAMU_MATCH title=\"$searchTitle\" page=\"${namu.pageTitle}\" score=${namu.score} url=${namu.url} japanese=\"${namu.japaneseTitle}\""
        )

        val japaneseTitle = namu.japaneseTitle.trim()
        if (japaneseTitle.isBlank()) {
            Log.w(TAG, "NAMU_JAPANESE_TITLE_EMPTY title=\"$searchTitle\" url=${namu.url}")
            return null
        }

        val body = searchAniListCandidates(japaneseTitle, 1)
        val candidates = parseAniListCandidates(
            body = body,
            query = japaneseTitle,
            rankingTitles = listOf(japaneseTitle)
        ).filter { it.malId != null }
            .associateBy { it.malId!! }
            .values
            .sortedByDescending { it.score }

        if (candidates.isEmpty()) {
            Log.w(TAG, "ANILIST_NO_CANDIDATES title=\"$searchTitle\" japanese=\"$japaneseTitle\"")
            return null
        }

        val best = candidates.first()
        val secondScore = candidates.getOrNull(1)?.score ?: 0
        val margin = best.score - secondScore

        Log.d(
            TAG,
            "ANILIST_GLOBAL_MATCH title=\"$searchTitle\" japanese=\"$japaneseTitle\" " +
                "candidates=${candidates.size} bestId=${best.malId} bestScore=${best.score} " +
                "secondScore=$secondScore margin=$margin query=\"${best.query}\" native=\"${best.nativeTitle}\""
        )

        if (best.malId != null && best.score >= 7000 &&
            (secondScore == 0 || margin >= 300)
        ) {
            malIdCache[normalized] = best.malId
            Log.d(TAG, "MAL_SEARCH_DONE title=\"$searchTitle\" malId=${best.malId} source=namu")
            return best.malId
        }

        Log.w(TAG, "MAL_MATCH_REJECTED title=\"$searchTitle\" bestId=${best.malId} bestScore=${best.score} margin=$margin")
        return null
    }

    private data class NamuSearchCandidate(
        val pageTitle: String,
        val url: String,
        val score: Int,
        val japaneseTitle: String
    )

    /**
     * Searches NamuWiki with the original Korean title, picks the most similar
     * title-content result, opens that page, and extracts the Japanese original
     * title from the infobox/content.
     */
    private suspend fun searchNamuWikiTitle(title: String): NamuSearchCandidate? {
        if (title.isBlank()) return null

        val encoded = URLEncoder.encode(title, Charsets.UTF_8.name())
        val searchUrl = "https://namu.wiki/Search?target=title_content&q=$encoded"
        val request = Request.Builder()
            .url(searchUrl)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "ko-KR,ko;q=0.9")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            .get()
            .build()

        Log.d(TAG, "NAMU_REQUEST title=\"$title\" url=$searchUrl")

        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Log.d(TAG, "NAMU_RESPONSE code=${response.code} success=${response.isSuccessful} bodyLength=${body.length}")
                if (!response.isSuccessful || body.isBlank()) return@use null

                // Parse only title/link pairs from the NamuWiki search result page.
                // The article itself is fetched only after the best title is selected.
                val links = Jsoup.parse(body)
                    .select("a[href^=/w/]")
                    .mapNotNull { element ->
                        val path = element.attr("href").trim()
                        val label = element.text().replace(Regex("\\s+"), " ").trim()
                        if (path.isBlank() || label.isBlank()) return@mapNotNull null

                        // Compare the actual work title without season information.
                        // NamuWiki commonly puts the season in a suffix such as
                        // "(애니메이션 2기)", while the search query is usually
                        // "작품명 2기". Removing the season from both sides makes
                        // the base title similarity comparable and lets the
                        // separate season bonus handle the season match.
                        val baseTitleScore = HangulSimilarityMatcher.score(
                            removeNamuSeasonInfo(title),
                            removeNamuSeasonInfo(label)
                        )
                        val seasonBonus = namuTitleSeasonBonus(title, label)
                        val score = baseTitleScore + seasonBonus
                        Triple(label, "https://namu.wiki$path", score)
                    }
                    .distinctBy { it.second }
                    .sortedByDescending { it.third }

                Log.d(TAG, "NAMU_TITLE_LINK_RESULTS title=\"$title\" count=${links.size}")
                links.take(10).forEachIndexed { index, result ->
                    Log.d(TAG, "NAMU_RESULT index=$index title=\"${result.first}\" score=${result.third} url=${result.second}")
                }

                val bestLink = links.firstOrNull() ?: return@use null
                Log.d(TAG, "NAMU_BEST_LINK title=\"$title\" page=\"${bestLink.first}\" score=${bestLink.third} url=${bestLink.second}")

                val articleRequest = Request.Builder()
                    .url(bestLink.second)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "ko-KR,ko;q=0.9")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                    .get()
                    .build()

                client.newCall(articleRequest).execute().use { articleResponse ->
                    val articleBody = articleResponse.body?.string().orEmpty()
                    Log.d(TAG, "NAMU_ARTICLE_RESPONSE code=${articleResponse.code} success=${articleResponse.isSuccessful} bodyLength=${articleBody.length}")
                    if (!articleResponse.isSuccessful || articleBody.isBlank()) return@use null

                    val japanese = extractNamuJapaneseTitle(articleBody, title)
                    if (japanese.isBlank()) {
                        Log.w(TAG, "NAMU_JAPANESE_TITLE_NOT_FOUND url=${bestLink.second}")
                        return@use null
                    }

                    Log.d(TAG, "NAMU_JAPANESE_TITLE title=\"${bestLink.first}\" japanese=\"$japanese\"")
                    NamuSearchCandidate(bestLink.first, bestLink.second, bestLink.third, japanese)
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "NAMU_ERROR ${error.javaClass.simpleName}: ${error.message}", error)
        }.getOrNull()
    }

    private fun removeNamuSeasonInfo(value: String): String =
        value
            // Handles both "2기" and "(애니메이션 2기)" forms.
            .replace(Regex("(?:\\(\\s*애니메이션\\s*)?\\d+\\s*기\\s*\\)?"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun namuTitleSeasonBonus(query: String, resultTitle: String): Int {
        val normalizedQuery = normalizeNamuTitle(query)
        val normalizedResult = normalizeNamuTitle(resultTitle)

        var bonus = 0

        // A NamuWiki title such as "작품명(애니메이션 4기)" is a strong
        // signal that the result is the exact anime season requested.
        val querySeason = extractKoreanSeasonNumber(normalizedQuery)
        val resultAnimeSeason = Regex("(?:애니메이션\\s*)?(\\d+)기")
            .find(normalizedResult)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        if (querySeason != null && resultAnimeSeason == querySeason) {
            bonus += 3000
        }

        // If the complete normalized titles are identical, strongly prefer it
        // over a merely similar search result.
        if (normalizedQuery == normalizedResult) {
            bonus += 2500
        }

        // Also reward the common NamuWiki convention where the base title is
        // followed by an explicit "(애니메이션 N기)" suffix.
        if (querySeason != null && resultAnimeSeason == querySeason &&
            normalizedResult.contains("애니메이션${querySeason}기")) {
            bonus += 1500
        }

        return bonus
    }

    private fun extractKoreanSeasonNumber(title: String): Int? =
        Regex("(?:제\\s*)?(\\d+)\\s*기\\b")
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun normalizeNamuTitle(value: String): String =
        value
            .replace(Regex("\\s+"), "")
            .replace("-", "")
            .replace("–", "")
            .replace("—", "")
            .replace(":", "")
            .trim()

    private fun extractNamuJapaneseTitle(html: String, searchTitle: String): String {
        val document = Jsoup.parse(html)

        // NamuWiki's infobox normally stores the field name and its value in
        // adjacent <th>/<td> (or <td>/<td>) cells.  Only inspect the value
        // cell belonging to an explicit Japanese-title field.  Do not use the
        // longest Japanese-looking string from the whole page: that can pick
        // an author's name, a character name, or another work's title.
        val labelPatterns = listOf(
            "원제",
            "일본어",
            "일본어판 제목",
            "일본어 제목",
            "원작명",
            "원어"
        )

        fun isJapaneseTitleLabel(value: String): Boolean {
            val normalized = value
                .replace(Regex("\\s+"), "")
                .trim()
            return labelPatterns.any { normalized.equals(it.replace(" ", ""), ignoreCase = true) }
        }

        fun japaneseCandidate(value: String): String? {
            // Keep Japanese-script characters and digits together.  The previous
            // extractor discarded digits, so titles such as "作品名 2" / "作品名 第2期"
            // lost their season number before the AniList search.
            val parts = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー・「」『』]+(?:\\s*(?:第\\s*)?\\d+(?:\\s*[期部章話])?)?")
                .findAll(value)
                .map { it.value.trim(' ', '·', '•') }
                .filter { it.isNotBlank() && it.any(Char::isLetter) }
                .toList()

            if (parts.isEmpty()) return null

            // Take every Japanese text run from the selected <td>. Numeric season
            // markers that belong to the Japanese run are preserved as well.
            return parts.joinToString(" ").trim()
        }

        // NamuWiki anime/manga infoboxes can expose the original title in a
        // dedicated first-row <td colspan="2">. For example, the DOM can look
        // like:
        //   <tr><td colspan="2"><div><span>逃げ上手の若君<br>
        //   The Elusive Samurai</span></div></td></tr>
        // In that structure there is no Korean label such as "원제", so check
        // the colspan=2 title cell before the label-based paths below.
        for (cell in document.select("td[colspan='2']")) {
            val text = cell.text().trim()
            val candidate = japaneseCandidate(text)
            if (!candidate.isNullOrBlank()) {
                Log.d(TAG, "NAMU_JAPANESE_TITLE colspan2 text=\"$text\" value=\"$candidate\"")
                return candidate
            }
        }

        // First: explicit table rows. This is the most reliable path.
        for (row in document.select("tr")) {
            val cells = row.select(":scope > th, :scope > td")
            if (cells.size < 2) continue

            for (index in 0 until (cells.size - 1)) {
                val label = cells[index].text().trim()
                if (!isJapaneseTitleLabel(label)) continue

                val valueCell = cells[index + 1]
                val candidate = japaneseCandidate(valueCell.text())
                if (!candidate.isNullOrBlank()) {
                    Log.d(TAG, "NAMU_JAPANESE_TITLE label=\"$label\" value=\"$candidate\"")
                    return candidate
                }
            }
        }

        // Second: handle nested markup where the label itself is inside a
        // span/div within the table cell.
        for (element in document.getAllElements()) {
            val own = element.ownText().trim()
            if (!isJapaneseTitleLabel(own)) continue

            val cell = element.parents().firstOrNull {
                it.tagName() == "th" || it.tagName() == "td"
            }
            val valueCell = cell?.nextElementSibling()
            val candidate = valueCell?.let { japaneseCandidate(it.text()) }
            if (!candidate.isNullOrBlank()) {
                Log.d(TAG, "NAMU_JAPANESE_TITLE label=\"$own\" value=\"$candidate\"")
                return candidate
            }
        }

        // No broad page-wide Japanese fallback. Returning an unrelated
        // Japanese string is worse than failing and letting the caller log the
        // missing original title.
        return ""
    }

    /**
     * Many catalog titles are stored as "한국어 제목 Romanized Title".
     * NamuWiki title search should use the Korean work title, not the trailing
     * romanization. Keep an explicit Korean season suffix such as "2기".
     */
    private fun separateKoreanTitle(title: String): String {
        val value = title.replace(Regex("\\s+"), " ").trim()
        if (value.isBlank() || !value.any { it in '\uAC00'..'\uD7A3' }) return value

        val lastHangulIndex = value.indexOfLast { it in '\uAC00'..'\uD7A3' }
        if (lastHangulIndex < 0 || lastHangulIndex >= value.lastIndex) return value

        val suffix = value.substring(lastHangulIndex + 1)
        // Preserve season notation immediately following the Korean title.
        val season = Regex("^\\s*(?:제\\s*)?\\d+\\s*기\\b").find(suffix)?.value.orEmpty()
        val koreanPart = value.substring(0, lastHangulIndex + 1)
        return (koreanPart + season).trim()
    }

    private fun removeBdTag(title: String): String {
        return title
            .replace(Regex("(?i)(?:\\[\\s*bd\\s*\\]|\\(\\s*bd\\s*\\)|(?<![A-Za-z0-9])bd(?![A-Za-z0-9]))"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private suspend fun searchAniListCandidates(query: String, attempt: Int): String {
        val graphQl = """
            query SearchAnime(${'$'}search: String!) {
              Page(page: 1, perPage: 10) {
                media(search: ${'$'}search, type: ANIME) {
                  id
                  idMal
                  title {
                    romaji
                    english
                    native
                    userPreferred
                  }
                  synonyms
                }
              }
            }
        """.trimIndent()

        val payload = JSONObject()
            .put("query", graphQl)
            .put("variables", JSONObject().put("search", query))
            .toString()

        for (retry in 1..2) {
            val request = Request.Builder()
                .url(ANILIST_GRAPHQL_URL)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "LilacAnime Android")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), payload))
                .build()

            Log.d(TAG, "ANILIST_REQUEST query=\"$query\" attempt=$attempt retry=$retry")

            val result = runCatching {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    Log.d(TAG, "ANILIST_RESPONSE query=\"$query\" code=${response.code} success=${response.isSuccessful} bodyLength=${body.length}")
                    if (!response.isSuccessful) {
                        Log.w(TAG, "ANILIST_HTTP_ERROR code=${response.code} query=\"$query\"")
                        return@use AniListAttemptResult(response.code, "")
                    }
                    AniListAttemptResult(response.code, body)
                }
            }.onFailure { error ->
                Log.w(TAG, "ANILIST_NETWORK_ERROR query=\"$query\" ${error.javaClass.simpleName}: ${error.message}")
            }.getOrElse { AniListAttemptResult(-1, "") }

            if (result.body.isNotBlank()) return result.body

            if (retry == 1 && result.code in setOf(429, 500, 502, 503, 504)) {
                delay(400L)
                continue
            }
            break
        }

        Log.w(TAG, "ANILIST_UNAVAILABLE query=\"$query\"")
        return ""
    }

    private data class AniListAttemptResult(
        val code: Int,
        val body: String
    )

    /**
     * Select a MAL id only when the AniList candidate is a strong title match.
     * Also returns AniList title.romaji so a Japanese-title miss can retry using
     * the canonical romanized title supplied by AniList itself.
     */
    private fun parseAniListCandidates(
        body: String,
        query: String,
        rankingTitles: List<String>
    ): List<AniListCandidate> {
        if (body.isBlank()) return emptyList()

        return runCatching {
            val root = JSONObject(body)
            val errors = root.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                Log.w(TAG, "ANILIST_GRAPHQL_ERROR query=\"$query\" errors=$errors")
                return@runCatching emptyList()
            }

            val data = root.optJSONObject("data")
                ?.optJSONObject("Page")
                ?.optJSONArray("media")
                ?: return@runCatching emptyList()

            val candidates = mutableListOf<AniListCandidate>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val malId = item.optInt("idMal", 0).takeIf { it > 0 }
                val titleObject = item.optJSONObject("title")
                val romaji = titleObject?.optString("romaji").orEmpty().trim()
                val nativeTitle = titleObject?.optString("native").orEmpty().trim()
                val englishTitle = titleObject?.optString("english").orEmpty().trim()

                val titles = mutableListOf<String>()
                titleObject?.optString("romaji")?.takeIf { it.isNotBlank() }?.let(titles::add)
                titleObject?.optString("english")?.takeIf { it.isNotBlank() }?.let(titles::add)
                titleObject?.optString("native")?.takeIf { it.isNotBlank() }?.let(titles::add)
                titleObject?.optString("userPreferred")?.takeIf { it.isNotBlank() }?.let(titles::add)

                item.optJSONArray("synonyms")?.let { aliases ->
                    for (j in 0 until aliases.length()) {
                        aliases.optString(j).takeIf { it.isNotBlank() }?.let(titles::add)
                    }
                }

                val score = rankingTitles
                    .filter { it.isNotBlank() }
                    .maxOfOrNull { reference -> MalAnimeMatcher.bestScore(reference, titles) }
                    ?: MalAnimeMatcher.bestScore(query, titles)

                if (malId != null) {
                    candidates += AniListCandidate(
                        malId = malId,
                        romaji = romaji,
                        nativeTitle = nativeTitle,
                        englishTitle = englishTitle,
                        synonyms = item.optJSONArray("synonyms")?.let { aliases ->
                            buildList {
                                for (j in 0 until aliases.length()) {
                                    aliases.optString(j).takeIf { it.isNotBlank() }?.let(::add)
                                }
                            }
                        }.orEmpty(),
                        score = score,
                        query = query
                    )
                } else if (romaji.isNotBlank()) {
                    candidates += AniListCandidate(
                        malId = null,
                        romaji = romaji,
                        nativeTitle = nativeTitle,
                        englishTitle = englishTitle,
                        synonyms = emptyList(),
                        score = score,
                        query = query
                    )
                }
            }

            val sorted = candidates.sortedByDescending { it.score }
            Log.d(
                TAG,
                "ANILIST_PARSE query=\"$query\" candidates=${sorted.size} top=${sorted.take(5).joinToString { candidate -> "${candidate.malId}:${candidate.score}" }}"
            )
            sorted
        }.onFailure { error ->
            Log.e(TAG, "ANILIST_PARSE_ERROR ${error.javaClass.simpleName}: ${error.message}", error)
        }.getOrElse { emptyList() }
    }

    private data class AniListCandidate(
        val malId: Int?,
        val romaji: String,
        val nativeTitle: String,
        val englishTitle: String,
        val synonyms: List<String>,
        val score: Int,
        val query: String
    )

}
