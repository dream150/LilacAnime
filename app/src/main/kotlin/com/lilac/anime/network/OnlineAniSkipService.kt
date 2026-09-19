package com.lilac.anime.network

import android.util.Log
import com.lilac.anime.core.model.ChapterSkipSegment
import com.lilac.anime.data.matcher.HangulSimilarityMatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
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
        val normalized = HangulSimilarityMatcher.filterNoise(title)
        Log.d(TAG, "MAL_SEARCH_START provider=AniList title=\"$title\" normalized=\"$normalized\"")
        if (normalized.isBlank()) {
            Log.w(TAG, "MAL_SEARCH_ABORT normalized title is blank")
            return null
        }

        malIdCache[normalized]?.let { cachedId ->
            Log.d(TAG, "MAL_CACHE_HIT normalized=\"$normalized\" malId=$cachedId")
            return cachedId
        }
        Log.d(TAG, "MAL_CACHE_MISS normalized=\"$normalized\"")

        // AniList is used only as a resolver: it searches anime by a complete
        // title in ONE script/language at a time and returns idMal, which AniSkip
        // needs. Do not send a mixed Korean+Romanized query such as
        // "이거 그리고 죽어 Kore Kaite Shine" because AniList search can fail to
        // find the intended title when unrelated language variants are combined.
        // Instead this becomes two independent searches:
        //   1) "이거 그리고 죽어"
        //   2) "Kore Kaite Shine"
        // The same rule also keeps other scripts (Japanese/CJK) separate.
        val queries = buildAniListLanguageQueries(title)
        Log.d(TAG, "ANILIST_LANGUAGE_QUERIES title=\"$title\" queries=$queries")

        for ((index, query) in queries.withIndex()) {
            val body = searchAniListCandidates(query, attempt = index + 1)
            if (body.isBlank()) continue

            val bestId = parseBestAniListMalId(body, query)
            if (bestId != null) {
                malIdCache[normalized] = bestId
                Log.d(TAG, "MAL_SEARCH_DONE provider=AniList title=\"$title\" malId=$bestId query=\"$query\"")
                return bestId
            }
        }

        Log.w(TAG, "MAL_SEARCH_NO_MATCH provider=AniList title=\"$title\"")
        return null
    }

    /**
     * Splits a title into script/language-specific queries.
     *
     * Example:
     *   "이거 그리고 죽어 Kore Kaite Shine"
     * becomes:
     *   "이거 그리고 죽어" and "Kore Kaite Shine"
     *
     * Tokens containing multiple scripts are split into their script groups so
     * a Korean query never carries Romanized text (and vice versa).
     */
    private fun buildAniListLanguageQueries(title: String): List<String> {
        val hangul = mutableListOf<String>()
        val latin = mutableListOf<String>()
        val eastAsian = mutableListOf<String>()
        val neutral = mutableListOf<String>()

        fun isHangul(c: Char) = c in '\uAC00'..'\uD7A3'
        fun isLatin(c: Char) = (c in 'A'..'Z') || (c in 'a'..'z')
        fun isEastAsian(c: Char) =
            (c in '\u3040'..'\u30FF') ||
                (c in '\u31F0'..'\u31FF') ||
                (c in '\u3400'..'\u4DBF') ||
                (c in '\u4E00'..'\u9FFF')

        title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { token ->
            val hasHangul = token.any(::isHangul)
            val hasLatin = token.any(::isLatin)
            val hasEastAsian = token.any(::isEastAsian)

            when {
                hasHangul && !hasLatin && !hasEastAsian -> hangul += token
                hasLatin && !hasHangul && !hasEastAsian -> latin += token
                hasEastAsian && !hasHangul && !hasLatin -> eastAsian += token
                hasHangul || hasLatin || hasEastAsian -> {
                    // A genuinely mixed token is split by script so the request
                    // still never mixes Korean and Romanized text.
                    token.filter(::isHangul).takeIf { it.isNotBlank() }?.let { hangul += it }
                    token.filter(::isLatin).takeIf { it.isNotBlank() }?.let { latin += it }
                    token.filter(::isEastAsian).takeIf { it.isNotBlank() }?.let { eastAsian += it }
                }
                else -> neutral += token
            }
        }

        // Pure numbers/punctuation are neutral. Add them to each language
        // query instead of making a meaningless number-only AniList request.
        val groups = listOf(hangul, eastAsian, latin)
        if (neutral.isNotEmpty() && groups.any { it.isNotEmpty() }) {
            groups.filter { it.isNotEmpty() }.forEach { it += neutral }
        }

        val queries = buildList {
            if (hangul.isNotEmpty()) add(hangul.joinToString(" "))
            if (eastAsian.isNotEmpty()) add(eastAsian.joinToString(" "))
            if (latin.isNotEmpty()) add(latin.joinToString(" "))
        }.map { HangulSimilarityMatcher.filterNoise(it).trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // For titles made of a single unclassified script/symbol set, retain a
        // complete-title query rather than producing an empty request list.
        return if (queries.isNotEmpty()) {
            queries
        } else {
            listOf(HangulSimilarityMatcher.filterNoise(title).trim())
                .filter { it.isNotBlank() }
        }
    }

    /**
     * AniList is the only MAL-ID resolver. It is an optional dependency:
     * any HTTP, GraphQL, parsing, timeout, or network failure becomes an empty
     * result so online OP/ED lookup simply becomes unavailable for this title.
     *
     * AniList's public GraphQL endpoint accepts POST requests with a query and
     * variables. The query asks for anime entries plus idMal and all useful
     * title variants/synonyms for local verification.
     */
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
     * AniList explicitly notes that titles are not unique, so we do not blindly
     * take the first search result.
     */
    private fun parseBestAniListMalId(body: String, query: String): Int? {
        if (body.isBlank()) return null

        return runCatching {
            val root = JSONObject(body)
            val errors = root.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                Log.w(TAG, "ANILIST_GRAPHQL_ERROR query=\"$query\" errors=$errors")
                return@runCatching null
            }

            val data = root.optJSONObject("data")
                ?.optJSONObject("Page")
                ?.optJSONArray("media")
                ?: return@runCatching null

            var bestId: Int? = null
            var bestScore = 0
            var secondScore = 0
            var candidateCount = 0

            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val malId = item.optInt("idMal", 0)
                if (malId <= 0) continue
                candidateCount++

                val titles = mutableListOf<String>()
                val titleObject = item.optJSONObject("title")
                titleObject?.optString("romaji").takeIf { !it.isNullOrBlank() }?.let(titles::add)
                titleObject?.optString("english").takeIf { !it.isNullOrBlank() }?.let(titles::add)
                titleObject?.optString("native").takeIf { !it.isNullOrBlank() }?.let(titles::add)
                titleObject?.optString("userPreferred").takeIf { !it.isNullOrBlank() }?.let(titles::add)

                item.optJSONArray("synonyms")?.let { aliases ->
                    for (j in 0 until aliases.length()) {
                        aliases.optString(j).takeIf { it.isNotBlank() }?.let(titles::add)
                    }
                }

                val score = MalAnimeMatcher.bestScore(query, titles)
                if (score > bestScore) {
                    secondScore = bestScore
                    bestScore = score
                    bestId = malId
                } else if (score > secondScore) {
                    secondScore = score
                }
            }

            val margin = bestScore - secondScore
            Log.d(TAG, "MAL_MATCH provider=AniList query=\"$query\" candidates=$candidateCount bestId=$bestId bestScore=$bestScore secondScore=$secondScore margin=$margin")

            if (bestId != null && bestScore >= 8200 && (secondScore == 0 || margin >= 500)) {
                bestId
            } else {
                Log.w(TAG, "MAL_MATCH_REJECTED provider=AniList query=\"$query\" bestId=$bestId bestScore=$bestScore secondScore=$secondScore margin=$margin")
                null
            }
        }.onFailure { error ->
            Log.e(TAG, "ANILIST_PARSE_ERROR ${error.javaClass.simpleName}: ${error.message}", error)
        }.getOrNull()
    }

}
