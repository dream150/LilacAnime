package com.lilac.anime

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.lilac.anime.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
object AniSkipService {
    private const val BASE_URL = "https://api.aniskip.com/v2"
    private const val JIKAN_URL = "https://api.jikan.moe/v4"
    private const val ANILIST_URL = "https://graphql.anilist.co"
    private const val KITSU_URL = "https://kitsu.io/api/edge"

    private val ANISKIP_TYPES = setOf("op", "ed", "mixed-op", "mixed-ed")

    private data class MalCandidate(
        val malId: Int,
        val score: Int,
        val matchedTitle: String,
        val seasonNumber: Int? = null,
        val year: Int? = null,
        val romajiTitle: String? = null
    )

    /**
     * AniSkip 조회는 MAL ID를 찾은 뒤 한 번만 호출한다.
     *
     * 요청 타입:
     * - op
     * - ed
     * - mixed-op
     * - mixed-ed
     *
     * PlayerScreen에서 실제 영상 길이를 전달하므로 rough(episodeLength=0) 조회와
     * exact 조회를 따로 하지 않는다.
     */
    suspend fun getSkipTimes(
        title: String,
        episodeNumber: Int,
        episodeLengthSeconds: Int
    ): List<AniSkipSegment> = withContext(Dispatchers.IO) {
        try {
            Log.d(
                "AniSkip",
                "START title=\"$title\" episode=$episodeNumber length=$episodeLengthSeconds"
            )

            val malId = findMalId(title)
            Log.d("AniSkip", "MAL_ID=$malId title=\"$title\"")

            if (malId == null) {
                Log.w("AniSkip", "MAL_ID_NOT_FOUND title=\"$title\"")
                return@withContext emptyList()
            }

            val actualLength = episodeLengthSeconds.coerceAtLeast(0)

            Log.d(
                "AniSkip",
                "FETCH_SINGLE malId=$malId episode=$episodeNumber episodeLength=$actualLength"
            )

            val segments = requestSkipTimes(
                malId = malId,
                episodeNumber = episodeNumber,
                episodeLength = actualLength
            )
                .distinctBy {
                    "${it.type}:${it.startTime}:${it.endTime}:${it.episodeLength}"
                }
                .sortedBy { it.startTime }

            if (segments.isEmpty()) {
                Log.w(
                    "AniSkip",
                    "NO_SKIP_DATA malId=$malId episode=$episodeNumber actualLength=$actualLength"
                )
            } else {
                segments.forEach {
                    Log.d(
                        "AniSkip",
                        "SEGMENT type=${it.type} start=${it.startTime} " +
                            "end=${it.endTime} sourceLength=${it.episodeLength}"
                    )
                }
            }

            segments
        } catch (e: Exception) {
            Log.e("AniSkip", "GET_SKIP_TIMES_EXCEPTION", e)
            emptyList()
        }
    }

    /**
     * AniSkip API는 한 번만 호출한다.
     *
     * API Host와 Origin을 동일한 api.aniskip.com으로 맞춰 웹 요청과 최대한
     * 같은 출처 정보를 제공한다. Referer 역시 같은 Origin을 사용한다.
     */
    private fun requestSkipTimes(
        malId: Int,
        episodeNumber: Int,
        episodeLength: Int
    ): List<AniSkipSegment> {
        val query = buildString {
            append("types=op")
            append("&types=ed")
            append("&types=mixed-op")
            append("&types=mixed-ed")

            if (episodeLength > 0) {
                append("&episodeLength=")
                append(episodeLength)
            }
        }

        val url = URL(
            "$BASE_URL/skip-times/$malId/$episodeNumber?$query"
        )

        Log.d("AniSkip", "REQUEST_SINGLE url=$url")
        Log.d(
            "AniSkip",
            "REQUEST_HEADERS Origin=https://api.aniskip.com Referer=https://api.aniskip.com/"
        )

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            useCaches = false
            instanceFollowRedirects = true

            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Origin", "https://api.aniskip.com")
            setRequestProperty("Referer", "https://api.aniskip.com/")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36")
        }

        return try {
            val responseCode = connection.responseCode

            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            Log.d("AniSkip", "HTTP $responseCode url=$url")
            Log.d("AniSkip", "RESPONSE_HEADERS ${connection.headerFields}")
            Log.d("AniSkip", "BODY ${body.take(8_000)}")

            if (responseCode !in 200..299) {
                Log.e(
                    "AniSkip",
                    "HTTP_ERROR code=$responseCode body=${body.take(2_000)}"
                )
                return emptyList()
            }

            val root = JSONObject(body)
            val found = root.optBoolean("found", false)
            Log.d(
                "AniSkip",
                "FOUND=$found status=${root.optInt("statusCode", responseCode)}"
            )

            val results = root.optJSONArray("results") ?: return emptyList()

            buildList {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue

                    val type = item.optString("skipType").ifBlank {
                        item.optString("skip_type")
                    }

                    if (type !in ANISKIP_TYPES) continue

                    val interval = item.optJSONObject("interval") ?: continue

                    val start = interval.optDouble(
                        "startTime",
                        interval.optDouble("start_time", Double.NaN)
                    )
                    val end = interval.optDouble(
                        "endTime",
                        interval.optDouble("end_time", Double.NaN)
                    )
                    val sourceLength = item.optDouble(
                        "episodeLength",
                        item.optDouble("episode_length", 0.0)
                    )

                    if (start.isFinite() && end.isFinite() && end > start) {
                        add(
                            AniSkipSegment(
                                type = type,
                                startTime = start,
                                endTime = end,
                                episodeLength = sourceLength
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AniSkip", "REQUEST_EXCEPTION url=$url", e)
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun translateText(
        text: String,
        source: String,
        target: String,
        provider: String
    ): String? {
        if (text.isBlank()) return null

        return try {
            val url = when (provider) {
                "mymemory" -> URL(
                    "https://api.mymemory.translated.net/get" +
                        "?q=${Uri.encode(text)}" +
                        "&langpair=${Uri.encode(source)}%7C${Uri.encode(target)}"
                )
                else -> URL(
                    "https://translate.googleapis.com/translate_a/single" +
                        "?client=gtx" +
                        "&sl=$source" +
                        "&tl=$target" +
                        "&dt=t" +
                        "&q=${Uri.encode(text)}"
                )
            }

            Log.d(
                "AniSkip",
                "TRANSLATE REQUEST provider=$provider source=$source target=$target text=\"$text\""
            )

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Android) LilacAnime/1.0"
                )
            }

            try {
                val code = connection.responseCode
                val body = (
                    if (code in 200..299) connection.inputStream
                    else connection.errorStream
                )?.bufferedReader()?.use { it.readText() }.orEmpty()

                Log.d(
                    "AniSkip",
                    "TRANSLATE HTTP=$code provider=$provider source=$source target=$target body=${body.take(2500)}"
                )

                if (code !in 200..299 || body.isBlank()) {
                    return null
                }

                val result = if (provider == "mymemory") {
                    JSONObject(body)
                        .optJSONObject("responseData")
                        ?.optString("translatedText")
                        .orEmpty()
                } else {
                    val root = JSONArray(body)
                    val first = root.optJSONArray(0)
                    if (first == null) {
                        ""
                    } else {
                        buildString {
                            for (i in 0 until first.length()) {
                                val row = first.optJSONArray(i) ?: continue
                                append(row.optString(0))
                            }
                        }
                    }
                }
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()

                Log.d(
                    "AniSkip",
                    "TRANSLATE RESULT provider=$provider source=$source target=$target result=\"$result\""
                )

                result.takeIf { it.isNotBlank() }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(
                "AniSkip",
                "TRANSLATE exception provider=$provider source=$source target=$target text=\"$text\"",
                e
            )
            null
        }
    }

    private fun translateKoreanToJapanese(text: String): String? {
        translateText(text, "ko", "ja", "mymemory")?.let { return it }
        translateText(text, "ko", "ja", "google")?.let { return it }
        return null
    }

    private fun translateKoreanToEnglish(text: String): String? {
        translateText(text, "ko", "en", "mymemory")?.let { return it }
        translateText(text, "ko", "en", "google")?.let { return it }
        return null
    }

    private fun findMalId(title: String): Int? {
        val requestedSeason = extractRequestedSeason(title)
        val titleCandidates = buildTitleCandidates(title)
        val original = titleCandidates.firstOrNull().orEmpty()
        val seasonless = titleCandidates.getOrNull(1).orEmpty()
        val baseTitle = seasonless.ifBlank { original }

        Log.d(
            "AniSkip",
            "TITLE_RESOLVE original=\"$title\" cleaned=\"$original\" " +
                "seasonless=\"$seasonless\" season=$requestedSeason"
        )

        val knownAliases = linkedMapOf(
            "도망을잘치는도련님" to listOf(
                "逃げ上手の若君",
                "Nige Jouzu no Wakagimi",
                "The Elusive Samurai"
            ),
            "전생했더니슬라임이었던건에대하여" to listOf(
                "転生したらスライムだった件",
                "Tensei Shitara Slime Datta Ken",
                "That Time I Got Reincarnated as a Slime"
            )
        )

        val normalizedOriginal = normalizeTitle(title)
        val knownAlias = knownAliases.entries
            .firstOrNull { normalizedOriginal.contains(it.key) }
            ?.value
            .orEmpty()

        if (knownAlias.isNotEmpty()) {
            Log.d("AniSkip", "TITLE_RESOLVE knownAliases=$knownAlias")

            for (alias in knownAlias) {
                Log.d("AniSkip", "KNOWN_ALIAS search=\"$alias\"")
                val matches = findMalCandidatesWithAniList(alias, requestedSeason)
                chooseBestMalCandidate(matches, requestedSeason)?.let { selected ->
                    Log.d(
                        "AniSkip",
                        "KNOWN_ALIAS SELECT malId=${selected.malId} " +
                            "romaji=\"${selected.romajiTitle}\" title=\"${selected.matchedTitle}\" " +
                            "score=${selected.score} season=${selected.seasonNumber}"
                    )
                    return selected.malId
                }
            }
        }

        val queryCandidates = linkedSetOf<String>()

        if (baseTitle.isNotBlank() && baseTitle.any { it in 'A'..'Z' || it in 'a'..'z' }) {
            queryCandidates += baseTitle
        }

        val japaneseTitle = translateKoreanToJapanese(baseTitle)
        Log.d("AniSkip", "TITLE_RESOLVE japanese=\"$japaneseTitle\"")

        if (!japaneseTitle.isNullOrBlank()) {
            queryCandidates += japaneseTitle
        }

        val englishTitle = translateKoreanToEnglish(baseTitle)
        Log.d("AniSkip", "TITLE_RESOLVE english=\"$englishTitle\"")

        if (!englishTitle.isNullOrBlank()) {
            queryCandidates += englishTitle
        }

        for (query in queryCandidates) {
            Log.d("AniSkip", "AniList SEARCH query=\"$query\" season=$requestedSeason")

            val matches = findMalCandidatesWithAniList(
                query,
                requestedSeason
            )

            chooseBestMalCandidate(matches, requestedSeason)?.let { selected ->
                Log.d(
                    "AniSkip",
                    "AniList SELECT malId=${selected.malId} " +
                        "romaji=\"${selected.romajiTitle}\" " +
                        "title=\"${selected.matchedTitle}\" " +
                        "score=${selected.score} season=${selected.seasonNumber} year=${selected.year}"
                )
                return selected.malId
            }
        }

        val fallbackQueries = linkedSetOf<String>()
        fallbackQueries.addAll(queryCandidates)

        if (baseTitle.isNotBlank()) {
            fallbackQueries += baseTitle
        }

        for (query in fallbackQueries) {
            Log.d("AniSkip", "Jikan FALLBACK search=\"$query\"")
            val jikanMatches = findMalCandidatesWithJikan(
                query,
                requestedSeason
            )

            chooseBestMalCandidate(jikanMatches, requestedSeason)?.let { selected ->
                Log.d(
                    "AniSkip",
                    "Jikan SELECT malId=${selected.malId} " +
                        "title=\"${selected.matchedTitle}\" score=${selected.score} season=${selected.seasonNumber}"
                )
                return selected.malId
            }
        }

        for (query in fallbackQueries) {
            Log.d("AniSkip", "Kitsu FALLBACK search=\"$query\"")
            val kitsuMatches = findMalCandidatesWithKitsu(
                query,
                requestedSeason
            )

            chooseBestMalCandidate(kitsuMatches, requestedSeason)?.let { selected ->
                Log.d(
                    "AniSkip",
                    "Kitsu SELECT malId=${selected.malId} " +
                        "title=\"${selected.matchedTitle}\" score=${selected.score} season=${selected.seasonNumber}"
                )
                return selected.malId
            }
        }

        for (query in fallbackQueries) {
            Log.d("AniSkip", "MAL FALLBACK search=\"$query\"")
            val malMatches = findMalCandidatesWithMalSearch(
                query,
                requestedSeason
            )

            chooseBestMalCandidate(malMatches, requestedSeason)?.let { selected ->
                Log.d(
                    "AniSkip",
                    "MAL SELECT malId=${selected.malId} " +
                        "title=\"${selected.matchedTitle}\" score=${selected.score} season=${selected.seasonNumber}"
                )
                return selected.malId
            }
        }

        Log.e(
            "AniSkip",
            "MAL ID not found title=\"$title\" japanese=\"$japaneseTitle\" english=\"$englishTitle\""
        )
        return null
    }

    private fun findMalCandidatesWithAniList(
        title: String,
        requestedSeason: Int?
    ): List<MalCandidate> {
        val query = """
            query (${'$'}search: String) {
                Page(page: 1, perPage: 25) {
                    media(
                        search: ${'$'}search
                        type: ANIME
                    ) {
                        id
                        idMal
                        season
                        seasonYear
                        format
                        episodes
                        title {
                            romaji
                            english
                            native
                        }
                        synonyms
                    }
                }
            }
        """.trimIndent()

        return try {
            val body = JSONObject()
                .put("query", query)
                .put(
                    "variables",
                    JSONObject().put("search", title)
                )
                .toString()

            val connection =
                (URL(ANILIST_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 10000
                    useCaches = false
                    setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )
                    setRequestProperty(
                        "Accept",
                        "application/json"
                    )
                    setRequestProperty(
                        "User-Agent",
                        "LilacAnime/1.0"
                    )
                }

            try {
                connection.outputStream.use { output ->
                    output.write(
                        body.toByteArray(Charsets.UTF_8)
                    )
                    output.flush()
                }

                val responseCode = connection.responseCode
                val responseStream =
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val response =
                    responseStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: ""

                Log.d(
                    "AniSkip",
                    "AniList HTTP=$responseCode candidate=\"$title\" response=${response.take(2500)}"
                )

                if (
                    responseCode !in 200..299 ||
                    response.isBlank()
                ) {
                    return emptyList()
                }

                val root = JSONObject(response)

                val media =
                    root.optJSONObject("data")
                        ?.optJSONObject("Page")
                        ?.optJSONArray("media")
                        ?: return emptyList()

                val normalizedQuery =
                    normalizeTitle(title)

                buildList {
                    for (i in 0 until media.length()) {
                        val item =
                            media.optJSONObject(i)
                                ?: continue

                        val malId =
                            item.optInt("idMal", 0)

                        if (malId <= 0) continue

                        val names =
                            mutableListOf<String>()

                        val romajiTitle =
                            item.optJSONObject("title")
                                ?.optString("romaji")
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }

                        item.optJSONObject("title")
                            ?.let { titleObj ->
                                romajiTitle?.let(names::add)

                                titleObj.optString("english")
                                    .takeIf { it.isNotBlank() }
                                    ?.let(names::add)

                                titleObj.optString("native")
                                    .takeIf { it.isNotBlank() }
                                    ?.let(names::add)
                            }

                        item.optJSONArray("synonyms")
                            ?.let { synonyms ->
                                for (j in 0 until synonyms.length()) {
                                    synonyms.optString(j)
                                        .takeIf { it.isNotBlank() }
                                        ?.let(names::add)
                                }
                            }

                        val romajiScore = romajiTitle?.let {
                            compareTitles(
                                normalizedQuery,
                                normalizeTitle(it)
                            )
                        } ?: 0

                        val titleScore = names.maxOfOrNull {
                            compareTitles(
                                normalizedQuery,
                                normalizeTitle(it)
                            )
                        } ?: 0

                        val strongestTitleScore = maxOf(titleScore, romajiScore)

                        val combinedText = names.joinToString(" ")

                        val detectedSeason = extractSeasonNumber(combinedText)
                        val anilistSeason = item.optString("season").trim()
                        val seasonYear = item.optInt("seasonYear", 0).takeIf { it > 0 }
                        val episodes = item.optInt("episodes", 0).takeIf { it > 0 }

                        var score = strongestTitleScore

                        if (requestedSeason != null) {
                            if (detectedSeason == requestedSeason) {
                                score += 5000
                            } else if (
                                detectedSeason != null &&
                                detectedSeason != requestedSeason
                            ) {
                                score -= 5000
                            }
                        }

                        // 제목에 시즌 번호가 없더라도 sequel/season 제목은 romaji/english/native에
                        // 숫자 표현이 포함되는 경우가 많으므로 추가 가산점을 준다.
                        if (requestedSeason != null) {
                            val seasonTokens = listOf(
                                "${requestedSeason}th season",
                                "${requestedSeason}st season",
                                "${requestedSeason}nd season",
                                "${requestedSeason}rd season",
                                "season $requestedSeason",
                                "part $requestedSeason",
                                "${requestedSeason}rd season",
                                "${requestedSeason}th season"
                            )

                            val hasRequestedSeasonToken = names.any { name ->
                                val n = normalizeTitle(name)
                                seasonTokens.any { token -> n.contains(token) } ||
                                    n.contains("${requestedSeason}기") ||
                                    n.contains("제 ${requestedSeason} 기") ||
                                    n.contains("${requestedSeason}th") ||
                                    n.contains("${requestedSeason}nd") ||
                                    n.contains("${requestedSeason}rd") ||
                                    n.contains("${requestedSeason}st")
                            }

                            if (hasRequestedSeasonToken) score += 3000
                        }

                        val format =
                            item.optString("format")

                        if (
                            requestedSeason != null &&
                            format == "TV"
                        ) {
                            score += 100
                        }

                        val year = seasonYear

                        val matchedTitle =
                            names.maxByOrNull {
                                compareTitles(
                                    normalizedQuery,
                                    normalizeTitle(it)
                                )
                            }.orEmpty()

                        Log.d(
                            "AniSkip",
                            "AniList candidate malId=$malId score=$score title=\"$matchedTitle\" " +
                                "romaji=\"$romajiTitle\" season=$detectedSeason year=$year names=$names"
                        )

                        add(
                            MalCandidate(
                                malId = malId,
                                score = score,
                                matchedTitle = matchedTitle,
                                seasonNumber = detectedSeason,
                                year = year,
                                romajiTitle = romajiTitle
                            )
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(
                "AniSkip",
                "AniList exception candidate=\"$title\"",
                e
            )
            emptyList()
        }
    }

    private fun findMalCandidatesWithMalSearch(
        title: String,
        requestedSeason: Int?
    ): List<MalCandidate> {
        return try {
            val query = Uri.encode(title)
            val url = URL(
                "https://myanimelist.net/search/prefix.json" +
                    "?type=anime&keyword=$query"
            )

            Log.d("AniSkip", "MAL REQUEST $url")

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Android) LilacAnime/1.0"
                )
            }

            try {
                val code = connection.responseCode
                val body = (
                    if (code in 200..299) connection.inputStream
                    else connection.errorStream
                )?.bufferedReader()?.use { it.readText() }.orEmpty()

                Log.d(
                    "AniSkip",
                    "MAL HTTP=$code candidate=\"$title\" response=${body.take(2500)}"
                )

                if (code !in 200..299 || body.isBlank()) {
                    return emptyList()
                }

                val root = JSONObject(body)
                val categories = root.optJSONArray("categories") ?: return emptyList()

                val items = buildList {
                    for (i in 0 until categories.length()) {
                        val category = categories.optJSONObject(i) ?: continue
                        val categoryItems = category.optJSONArray("items") ?: continue

                        for (j in 0 until categoryItems.length()) {
                            categoryItems.optJSONObject(j)?.let { add(it) }
                        }
                    }
                }

                val normalizedQuery = normalizeTitle(title)

                buildList {
                    for (item in items) {
                        val malId = item.optInt("id", 0)
                        if (malId <= 0) continue

                        val name = item.optString("name").trim()
                        if (name.isBlank()) continue

                        val normalizedName = normalizeTitle(name)
                        val titleScore = compareTitles(normalizedQuery, normalizedName)
                        var score = titleScore

                        val detectedSeason = extractSeasonNumber(name)

                        if (requestedSeason != null && titleScore > 0) {
                            if (detectedSeason == requestedSeason) {
                                score += 5000
                            } else if (
                                detectedSeason != null &&
                                detectedSeason != requestedSeason
                            ) {
                                score -= 5000
                            }
                        }

                        Log.d(
                            "AniSkip",
                            "MAL candidate malId=$malId score=$score " +
                                "title=\"$name\" season=$detectedSeason"
                        )

                        add(
                            MalCandidate(
                                malId = malId,
                                score = score,
                                matchedTitle = name,
                                seasonNumber = detectedSeason,
                                year = null
                            )
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(
                "AniSkip",
                "MAL exception candidate=\"$title\"",
                e
            )
            emptyList()
        }
    }

    private fun findMalCandidatesWithJikan(
        title: String,
        requestedSeason: Int?
    ): List<MalCandidate> {
        return try {
            val query = Uri.encode(title)
            val url = URL(
                "$JIKAN_URL/anime?q=$query&limit=25"
            )

            Log.d(
                "AniSkip",
                "Jikan REQUEST $url"
            )

            val connection =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    useCaches = false
                    setRequestProperty(
                        "Accept",
                        "application/json"
                    )
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Android) LilacAnime/1.0"
                    )
                }

            try {
                val responseCode =
                    connection.responseCode

                val responseStream =
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val body =
                    responseStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: ""

                Log.d(
                    "AniSkip",
                    "Jikan HTTP=$responseCode candidate=\"$title\" response=${body.take(2000)}"
                )

                if (
                    responseCode !in 200..299 ||
                    body.isBlank()
                ) {
                    return emptyList()
                }

                val data =
                    JSONObject(body)
                        .optJSONArray("data")
                        ?: return emptyList()

                val normalizedQuery =
                    normalizeTitle(title)

                buildList {
                    for (i in 0 until data.length()) {
                        val item =
                            data.optJSONObject(i)
                                ?: continue

                        val malId =
                            item.optInt("mal_id", 0)

                        if (malId <= 0) continue

                        val names =
                            mutableListOf<String>()

                        item.optString("title")
                            .takeIf { it.isNotBlank() }
                            ?.let(names::add)

                        item.optString("title_english")
                            .takeIf { it.isNotBlank() }
                            ?.let(names::add)

                        item.optString("title_japanese")
                            .takeIf { it.isNotBlank() }
                            ?.let(names::add)

                        item.optJSONArray("titles")
                            ?.let { titles ->
                                for (j in 0 until titles.length()) {
                                    titles.optJSONObject(j)
                                        ?.optString("title")
                                        ?.takeIf {
                                            it.isNotBlank()
                                        }
                                        ?.let(names::add)
                                }
                            }

                        val titleScore =
                            names.maxOfOrNull {
                                compareTitles(
                                    normalizedQuery,
                                    normalizeTitle(it)
                                )
                            } ?: 0

                        val combinedText =
                            names.joinToString(" ")

                        val detectedSeason =
                            extractSeasonNumber(
                                combinedText
                            )

                        var score = titleScore

                        if (
                            requestedSeason != null
                        ) {
                            if (
                                detectedSeason ==
                                requestedSeason
                            ) {
                                score += 5000
                            } else if (
                                detectedSeason != null &&
                                detectedSeason != requestedSeason
                            ) {
                                score -= 2500
                            }
                        }

                        val year =
                            item.optString("year")
                                .toIntOrNull()
                                ?: item.optJSONObject("aired")
                                    ?.optString("from")
                                    ?.take(4)
                                    ?.toIntOrNull()

                        val matchedTitle =
                            names.maxByOrNull {
                                compareTitles(
                                    normalizedQuery,
                                    normalizeTitle(it)
                                )
                            }.orEmpty()

                        Log.d(
                            "AniSkip",
                            "Jikan candidate malId=$malId score=$score title=\"$matchedTitle\" season=$detectedSeason year=$year"
                        )

                        add(
                            MalCandidate(
                                malId = malId,
                                score = score,
                                matchedTitle = matchedTitle,
                                seasonNumber = detectedSeason,
                                year = year
                            )
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(
                "AniSkip",
                "Jikan exception candidate=\"$title\"",
                e
            )
            emptyList()
        }
    }

    private fun findMalCandidatesWithKitsu(
        title: String,
        requestedSeason: Int?
    ): List<MalCandidate> {
        return try {
            val query = Uri.encode(title)
            val url = URL(
                "$KITSU_URL/anime?filter[text]=$query&page[limit]=20&include=mappings"
            )

            Log.d(
                "AniSkip",
                "Kitsu REQUEST $url"
            )

            val connection =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    useCaches = false
                    setRequestProperty(
                        "Accept",
                        "application/vnd.api+json"
                    )
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Android) LilacAnime/1.0"
                    )
                }

            try {
                val responseCode =
                    connection.responseCode

                val responseStream =
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val body =
                    responseStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: ""

                Log.d(
                    "AniSkip",
                    "Kitsu HTTP=$responseCode candidate=\"$title\" response=${body.take(2500)}"
                )

                if (
                    responseCode !in 200..299 ||
                    body.isBlank()
                ) {
                    return emptyList()
                }

                val root = JSONObject(body)
                val data =
                    root.optJSONArray("data")
                        ?: return emptyList()

                val included =
                    root.optJSONArray("included")

                val normalizedQuery =
                    normalizeTitle(title)

                buildList {
                    for (i in 0 until data.length()) {
                        val item =
                            data.optJSONObject(i)
                                ?: continue

                        val attributes =
                            item.optJSONObject("attributes")
                                ?: continue

                        val names =
                            mutableListOf<String>()

                        attributes.optString("canonicalTitle")
                            .takeIf { it.isNotBlank() }
                            ?.let(names::add)

                        attributes.optJSONObject("titles")
                            ?.let { titles ->
                                val keys =
                                    titles.keys()

                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    titles.optString(key)
                                        .takeIf {
                                            it.isNotBlank()
                                        }
                                        ?.let(names::add)
                                }
                            }

                        val titleScore =
                            names.maxOfOrNull {
                                compareTitles(
                                    normalizedQuery,
                                    normalizeTitle(it)
                                )
                            } ?: 0

                        val combinedText =
                            names.joinToString(" ")

                        val detectedSeason =
                            extractSeasonNumber(
                                combinedText
                            )

                        var score = titleScore

                        if (
                            requestedSeason != null
                        ) {
                            if (
                                detectedSeason ==
                                requestedSeason
                            ) {
                                score += 5000
                            } else if (
                                detectedSeason != null &&
                                detectedSeason != requestedSeason
                            ) {
                                score -= 2500
                            }
                        }

                        val year =
                            attributes.optString(
                                "startDate"
                            )
                                .take(4)
                                .toIntOrNull()

                        var malId =
                            findKitsuMalId(
                                item,
                                included
                            )

                        if (malId == null) {
                            val slug =
                                item.optString("id")

                            if (
                                slug.isNotBlank()
                            ) {
                                malId =
                                    findMalIdFromKitsuSlug(
                                        slug
                                    )
                            }
                        }

                        if (malId == null) {
                            Log.d(
                                "AniSkip",
                                "Kitsu result has no MAL mapping title=$names"
                            )
                            continue
                        }

                        val matchedTitle =
                            names.maxByOrNull {
                                compareTitles(
                                    normalizedQuery,
                                    normalizeTitle(it)
                                )
                            }.orEmpty()

                        Log.d(
                            "AniSkip",
                            "Kitsu candidate malId=$malId score=$score title=\"$matchedTitle\" season=$detectedSeason year=$year"
                        )

                        add(
                            MalCandidate(
                                malId = malId,
                                score = score,
                                matchedTitle = matchedTitle,
                                seasonNumber = detectedSeason,
                                year = year
                            )
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(
                "AniSkip",
                "Kitsu exception candidate=\"$title\"",
                e
            )
            emptyList()
        }
    }

    private fun findKitsuMalId(
        item: JSONObject,
        included: JSONArray?
    ): Int? {
        val relationships =
            item.optJSONObject("relationships")
                ?: return null

        val mappings =
            relationships.optJSONObject("mappings")
                ?: return null

        val data =
            mappings.optJSONArray("data")
                ?: return null

        for (i in 0 until data.length()) {
            val mapping =
                data.optJSONObject(i)
                    ?: continue

            val mappingId =
                mapping.optString("id")

            if (
                mappingId.isBlank()
            ) {
                continue
            }

            val includedMapping =
                included?.let {
                    findIncludedObject(
                        it,
                        "mappings",
                        mappingId
                    )
                }

            val attributes =
                includedMapping
                    ?.optJSONObject("attributes")
                    ?: continue

            val externalSite =
                attributes.optString(
                    "externalSite"
                )

            val externalId =
                attributes.optString(
                    "externalId"
                )

            if (
                externalId.isNotBlank() &&
                (
                    externalSite.equals(
                        "myanimelist",
                        true
                    ) ||
                    externalSite.equals(
                        "MyAnimeList",
                        true
                    ) ||
                    externalSite.contains(
                        "mal",
                        true
                    )
                )
            ) {
                return externalId.toIntOrNull()
            }
        }

        return null
    }

    private fun findIncludedObject(
        included: JSONArray,
        type: String,
        id: String
    ): JSONObject? {
        for (i in 0 until included.length()) {
            val item =
                included.optJSONObject(i)
                    ?: continue

            if (
                item.optString("type") == type &&
                item.optString("id") == id
            ) {
                return item
            }
        }

        return null
    }

    private fun findMalIdFromKitsuSlug(
        kitsuId: String
    ): Int? {
        return try {
            val url =
                URL(
                    "$KITSU_URL/anime/$kitsuId?include=mappings"
                )

            val connection =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    useCaches = false
                    setRequestProperty(
                        "Accept",
                        "application/vnd.api+json"
                    )
                }

            try {
                if (
                    connection.responseCode !in 200..299
                ) {
                    return null
                }

                val body =
                    connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                val root =
                    JSONObject(body)

                val included =
                    root.optJSONArray("included")

                val data =
                    root.optJSONObject("data")
                        ?: return null

                return findKitsuMalId(
                    data,
                    included
                )
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(
                "AniSkip",
                "Kitsu mapping exception id=$kitsuId",
                e
            )
            null
        }
    }

    private fun chooseBestMalCandidate(
        candidates: List<MalCandidate>,
        requestedSeason: Int?
    ): MalCandidate? {
        if (candidates.isEmpty()) return null

        val grouped = candidates
            .groupBy { it.malId }
            .values
            .mapNotNull { matches ->
                matches.maxByOrNull { it.score }
            }

        val eligible = grouped.filter { candidate ->
            val seasonOk = requestedSeason == null ||
                candidate.seasonNumber == null ||
                candidate.seasonNumber == requestedSeason

            val threshold = if (requestedSeason != null) 4500 else 6500

            seasonOk && candidate.score >= threshold
        }

        if (eligible.isEmpty()) {
            Log.w(
                "AniSkip",
                "MAL_SELECT no strong candidate requestedSeason=$requestedSeason " +
                    "candidates=${grouped.sortedByDescending { it.score }.take(5)}"
            )
            return null
        }

        return eligible.maxWithOrNull(
            compareBy<MalCandidate> {
                if (
                    requestedSeason != null &&
                    it.seasonNumber == requestedSeason
                ) 1 else 0
            }.thenBy { it.score }
        )
    }

    private fun buildTitleCandidates(
        title: String
    ): List<String> {
        val cleaned =
            title
                .replace(
                    Regex("\\[[^]]*]"),
                    " "
                )
                .replace(
                    Regex("\\([^)]*\\)"),
                    " "
                )
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        val candidates =
            linkedSetOf<String>()

        fun addCandidate(value: String) {
            val candidate =
                value
                    .replace(
                        Regex("\\s+"),
                        " "
                    )
                    .trim()

            if (
                candidate.isNotBlank()
            ) {
                candidates.add(candidate)
            }
        }

        addCandidate(cleaned)

        val seasonless =
            cleaned
                .replace(
                    Regex(
                        "(?i)(?:\\b(?:season|part|cour|"
                            + "season\\s*[0-9]+|part\\s*[0-9]+)"
                            + "\\b|\\b\\d+\\s*(?:st|nd|rd|th)"
                            + "\\s+season\\b|\\b\\d+기\\b|"
                            + "\\b시즌\\s*\\d+\\b|\\b제\\s*\\d+\\s*기\\b|"
                            + "\\b第\\s*\\d+\\s*期\\b|\\b第\\s*\\d+\\s*季\\b)"
                    ),
                    " "
                )
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        addCandidate(seasonless)

        addCandidate(
            cleaned.substringBefore(" - ")
        )

        addCandidate(
            cleaned.substringBefore(" | ")
        )

        Regex(
            "[A-Za-z][A-Za-z0-9À-ÿ'’:&.,!? -]{3,}"
        )
            .findAll(cleaned)
            .map {
                it.value.trim()
            }
            .filter {
                it.length >= 4
            }
            .forEach {
                addCandidate(it)
            }

        Regex(
            "(?i)(?:anime|title)?\\s*[:：]\\s*"
                + "([A-Za-z][A-Za-z0-9'’:&.,!? -]{3,})"
        )
            .find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::addCandidate)

        return candidates.toList()
    }

    private fun extractRequestedSeason(
        title: String
    ): Int? {
        val patterns =
            listOf(
                Regex(
                    "(?i)\\b(?:season|part|cour)\\s*(\\d+)\\b"
                ),
                Regex(
                    "(?i)\\b(\\d+)(?:st|nd|rd|th)\\s+season\\b"
                ),
                Regex(
                    "(?i)\\b(\\d+)\\s*기\\b"
                ),
                Regex(
                    "(?i)\\b시즌\\s*(\\d+)\\b"
                ),
                Regex(
                    "(?i)\\b제\\s*(\\d+)\\s*기\\b"
                ),
                Regex(
                    "(?i)\\b第\\s*(\\d+)\\s*期\\b"
                ),
                Regex(
                    "(?i)\\b第\\s*(\\d+)\\s*季\\b"
                )
            )

        for (pattern in patterns) {
            pattern.find(title)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { return it }
        }

        return null
    }

    private fun extractSeasonNumber(
        value: String
    ): Int? {
        val patterns =
            listOf(
                Regex(
                    "(?i)\\bseason\\s*(\\d+)\\b"
                ),
                Regex(
                    "(?i)\\bpart\\s*(\\d+)\\b"
                ),
                Regex(
                    "(?i)\\b(\\d+)(?:st|nd|rd|th)\\s+season\\b"
                ),
                Regex(
                    "(?i)\\b(\\d+)\\s*기\\b"
                ),
                Regex(
                    "(?i)\\b시즌\\s*(\\d+)\\b"
                ),
                Regex(
                    "(?i)\\b제\\s*(\\d+)\\s*기\\b"
                ),
                Regex(
                    "(?i)\\b第\\s*(\\d+)\\s*期\\b"
                ),
                Regex(
                    "(?i)\\b第\\s*(\\d+)\\s*季\\b"
                ),
                Regex(
                    "(?i)\\bS(\\d+)\\b"
                )
            )

        for (pattern in patterns) {
            pattern.find(value)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { return it }
        }

        return null
    }

    private fun normalizeTitle(
        value: String
    ): String =
        value
            .lowercase(Locale.ROOT)
            .replace(
                Regex("\\[[^]]*]"),
                " "
            )
            .replace(
                Regex("\\([^)]*\\)"),
                " "
            )
            .replace(
                Regex("[^\\p{L}\\p{N}]+"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()

    private fun compareTitles(
        a: String,
        b: String
    ): Int {
        if (
            a.isBlank() ||
            b.isBlank()
        ) {
            return 0
        }

        if (a == b) {
            return 10000
        }

        if (
            a.contains(b) ||
            b.contains(a)
        ) {
            return 8000
        }

        val left =
            a.split(' ')
                .filter {
                    it.length >= 2
                }
                .toSet()

        val right =
            b.split(' ')
                .filter {
                    it.length >= 2
                }
                .toSet()

        if (
            left.isEmpty() ||
            right.isEmpty()
        ) {
            return 0
        }

        val overlap =
            left.intersect(right).size.toDouble() /
                maxOf(
                    left.size,
                    right.size
                ).toDouble()

        return (
            overlap * 6000.0
        ).toInt()
    }
}

// ============================================================
// MAIN ACTIVITY
// ============================================================

