package com.lilac.anime.network

import android.util.Log
import com.lilac.anime.AniSkipSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * OP/ED skip data backed by AnimeThemes + Open Anime Timestamps.
 *
 * AnimeThemes is the authority for whether an anime has OP/ED themes and for
 * resolving the AniList/AniDB relationship. Open Anime Timestamps supplies
 * episode-specific positions because AnimeThemes does not publish timestamps
 * inside normal episode videos.
 */
object AnimeThemesSkipService {
    private const val TAG = "AnimeThemesSkip"
    private const val ANILIST_URL = "https://graphql.anilist.co"
    private const val ANIMETHEMES_URL = "https://api.animethemes.moe/anime/"
    private const val TIMESTAMPS_URL =
        "https://raw.githubusercontent.com/Ellivers/open-anime-timestamps/refs/heads/master/timestamps.json"

    private const val OPENING_LENGTH_SECONDS = 90.0

    private var timestampDatabase: JSONObject? = null
    private val cache = mutableMapOf<String, AnimeThemeInfo?>()

    private data class AnimeThemeInfo(
        val aniListId: Int?,
        val aniDbId: Int?,
        val malId: Int?,
        val hasOp: Boolean,
        val hasEd: Boolean
    )

    private data class TimestampEntry(
        val source: String,
        val openingStart: Double,
        val openingEnd: Double,
        val endingStart: Double,
        val endingEnd: Double,
        val previewStart: Double
    )

    suspend fun getSkipTimes(
        title: String,
        episodeNumber: Int,
        episodeLengthSeconds: Int
    ): List<AniSkipSegment> = withContext(Dispatchers.IO) {
        try {
            val themes = resolveAnime(title) ?: run {
                Log.d(TAG, "ANIME_NOT_FOUND title=$title")
                return@withContext emptyList()
            }

            if (!themes.hasOp && !themes.hasEd) {
                Log.d(TAG, "NO_OP_ED aniList=${themes.aniListId} title=$title")
                return@withContext emptyList()
            }

            val aniDbId = themes.aniDbId ?: run {
                Log.d(
                    TAG,
                    "ANIDB_RESOURCE_NOT_FOUND aniList=${themes.aniListId} mal=${themes.malId} title=$title"
                )
                return@withContext emptyList()
            }

            val entry = getTimestampEntry(aniDbId, episodeNumber) ?: run {
                Log.d(TAG, "TIMESTAMP_NOT_FOUND aniDb=$aniDbId episode=$episodeNumber")
                return@withContext emptyList()
            }

            val duration = episodeLengthSeconds.toDouble().coerceAtLeast(1.0)
            val result = mutableListOf<AniSkipSegment>()

            if (themes.hasOp && entry.openingStart >= 0.0) {
                val start = entry.openingStart.coerceIn(0.0, duration)
                val naturalEnd = when {
                    entry.openingEnd > start + 1.0 -> entry.openingEnd
                    entry.endingStart > start + 1.0 -> minOf(start + OPENING_LENGTH_SECONDS, entry.endingStart)
                    else -> start + OPENING_LENGTH_SECONDS
                }
                val end = naturalEnd.coerceIn(start, duration)
                if (end > start + 1.0) {
                    result += AniSkipSegment("op", start, end, duration)
                }
            }

            if (themes.hasEd && entry.endingStart >= 0.0) {
                val start = entry.endingStart.coerceIn(0.0, duration)
                val naturalEnd = when {
                    entry.endingEnd > start + 1.0 -> entry.endingEnd
                    entry.previewStart > start + 1.0 -> entry.previewStart
                    else -> duration
                }
                val end = naturalEnd.coerceIn(start, duration)
                if (end > start + 1.0) {
                    result += AniSkipSegment("ed", start, end, duration)
                }
            }

            Log.d(
                TAG,
                "LOADED title=$title aniList=${themes.aniListId} mal=${themes.malId} " +
                    "aniDb=$aniDbId episode=$episodeNumber source=${entry.source} segments=${result.size}"
            )
            result
        } catch (e: Exception) {
            Log.e(TAG, "GET_SKIP_TIMES_EXCEPTION title=$title episode=$episodeNumber", e)
            emptyList()
        }
    }

    private fun resolveAnime(title: String): AnimeThemeInfo? {
        val key = normalize(title)
        if (cache.containsKey(key)) return cache[key]

        // Linkkf can give us only a Korean display title. AniList search is
        // useful for romaji/English titles, but Korean-localized titles are not
        // guaranteed to be indexed. Try AniList first, then Kitsu as a localized
        // title/cross-reference resolver. AnimeThemes remains the source of truth
        // for OP/ED metadata and the AniDB relationship.
        val candidates = linkedSetOf<String>().apply {
            add(title.trim())
            val firstLatin = title.indexOfFirst { it.isLetter() && it.code < 128 }
            if (firstLatin > 0) add(title.substring(firstLatin).trim())
            title.split("|", "/", "·", " - ").map { it.trim() }
                .filter { it.length >= 3 }
                .forEach(::add)
        }

        for (candidate in candidates) {
            val result = searchAniList(candidate)
            if (result != null) {
                val info = getAnimeThemes(result.first, result.second)
                if (info != null) {
                    cache[key] = info
                    Log.d(TAG, "ANIME_RESOLVE source=anilist query=$candidate aniList=${info.aniListId} mal=${info.malId}")
                    return info
                }
            }
        }

        // Korean/localized titles are not guaranteed to be searchable by AniList.
        // Use Kitsu as a title resolver because its anime records expose localized
        // titles and cross-service mappings (MAL/AniList/AniDB). AnimeThemes remains
        // the source of truth for OP/ED metadata and the final AniDB relationship.
        val kitsu = searchKitsu(title)
        if (kitsu != null) {
            val (malId, aniListId, titles) = kitsu
            if (aniListId != null) {
                val info = getAnimeThemes(aniListId, titles)
                if (info != null) {
                    cache[key] = info
                    Log.d(TAG, "ANIME_RESOLVE source=kitsu query=$title aniList=${info.aniListId} mal=${info.malId}")
                    return info
                }
            }
            if (malId != null) {
                val info = getAnimeThemesByMal(malId, titles)
                if (info != null) {
                    cache[key] = info
                    Log.d(TAG, "ANIME_RESOLVE source=kitsu query=$title aniList=${info.aniListId} mal=${info.malId}")
                    return info
                }
            }
        }

        cache[key] = null
        Log.d(TAG, "ANIME_RESOLVE title=$title aniList=null mal=null aniDb=null hasOp=null hasEd=null score=-1")
        return null
    }

    private data class KitsuResolve(
        val malId: Int?,
        val aniListId: Int?,
        val titles: List<String>
    )

    private fun searchKitsu(search: String): KitsuResolve? {
        val encoded = java.net.URLEncoder.encode(search.trim(), "UTF-8")
        val url = "https://kitsu.io/api/edge/anime" +
            "?filter[text]=$encoded&page[limit]=10&include=mappings"
        val json = httpJson(url) ?: run {
            Log.d(TAG, "KITSU_SEARCH_FAILED query=$search")
            return null
        }

        val data = json.optJSONArray("data") ?: return null
        val included = json.optJSONArray("included")
        var best: KitsuResolve? = null
        var bestScore = -1

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val attrs = item.optJSONObject("attributes") ?: continue
            val titles = mutableListOf<String>()
            attrs.optString("canonicalTitle").takeIf { it.isNotBlank() }?.let(titles::add)
            val titleMap = attrs.optJSONObject("titles")
            if (titleMap != null) {
                val keys = titleMap.keys()
                while (keys.hasNext()) {
                    val value = titleMap.optString(keys.next())
                    if (value.isNotBlank()) titles += value
                }
            }
            val abbreviated = attrs.optJSONArray("abbreviatedTitles")
            if (abbreviated != null) {
                for (j in 0 until abbreviated.length()) {
                    abbreviated.optString(j).takeIf { it.isNotBlank() }?.let(titles::add)
                }
            }
            val other = attrs.optJSONArray("otherTitles")
            if (other != null) {
                for (j in 0 until other.length()) {
                    other.optString(j).takeIf { it.isNotBlank() }?.let(titles::add)
                }
            }

            var malId: Int? = null
            var aniListId: Int? = null

            // JSON:API puts mapping identifiers in relationships.mappings and
            // the actual mapping objects in the top-level included array.
            val mappingIds = mutableSetOf<String>()
            val mappingData = item.optJSONObject("relationships")
                ?.optJSONObject("mappings")
                ?.optJSONArray("data")
            if (mappingData != null) {
                for (j in 0 until mappingData.length()) {
                    mappingData.optJSONObject(j)?.optString("id")?.takeIf { it.isNotBlank() }
                        ?.let(mappingIds::add)
                }
            }

            if (included != null) {
                for (j in 0 until included.length()) {
                    val mapping = included.optJSONObject(j) ?: continue
                    if (mapping.optString("type") != "mappings") continue
                    if (mappingIds.isNotEmpty() && mapping.optString("id") !in mappingIds) continue
                    val ma = mapping.optJSONObject("attributes") ?: continue
                    val site = ma.optString("externalSite")
                    val externalId = ma.optString("externalId").toIntOrNull() ?: continue
                    when {
                        site.equals("myanimelist/anime", true) -> malId = externalId
                        site.equals("anilist/anime", true) -> aniListId = externalId
                    }
                }
            }

            // Some Kitsu responses omit included mappings. Fetch the mapping
            // collection directly for that result as a fallback.
            if (malId == null && aniListId == null) {
                val kitsuId = item.optString("id")
                if (kitsuId.isNotBlank()) {
                    val mappingRoot = httpJson("https://kitsu.io/api/edge/anime/$kitsuId/mappings")
                    val mappingArray = mappingRoot?.optJSONArray("data")
                    if (mappingArray != null) {
                        for (j in 0 until mappingArray.length()) {
                            val mapping = mappingArray.optJSONObject(j) ?: continue
                            val ma = mapping.optJSONObject("attributes") ?: continue
                            val site = ma.optString("externalSite")
                            val externalId = ma.optString("externalId").toIntOrNull() ?: continue
                            when {
                                site.equals("myanimelist/anime", true) -> malId = externalId
                                site.equals("anilist/anime", true) -> aniListId = externalId
                            }
                        }
                    }
                }
            }

            val score = titleScore(search, titles) + if (malId != null || aniListId != null) 10 else 0
            if (score > bestScore && (malId != null || aniListId != null)) {
                bestScore = score
                best = KitsuResolve(malId, aniListId, titles)
            }
        }

        if (best != null) {
            Log.d(TAG, "KITSU_RESOLVE query=$search mal=${best.malId} aniList=${best.aniListId} score=$bestScore")
        }
        return best
    }

    private fun searchAniList(search: String): Pair<Int, List<String>>? {
        val query = """
            query(\$search: String) {
              Page(page: 1, perPage: 10) {
                media(search: \$search, type: ANIME) {
                  id
                  idMal
                  title { romaji english native }
                }
              }
            }
        """.trimIndent()

        val body = JSONObject()
            .put("query", query)
            .put("variables", JSONObject().put("search", search))
            .toString()

        val json = httpJson(ANILIST_URL, "POST", body) ?: return null
        val media = json.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
            ?: return null

        var bestId = 0
        var bestMal = 0
        var bestTitles = emptyList<String>()
        var bestScore = -1

        for (i in 0 until media.length()) {
            val item = media.optJSONObject(i) ?: continue
            val titles = item.optJSONObject("title") ?: continue
            val titleList = listOf(
                titles.optString("romaji"),
                titles.optString("english"),
                titles.optString("native")
            ).filter { it.isNotBlank() }
            val score = titleScore(search, titleList)
            if (score > bestScore) {
                bestScore = score
                bestId = item.optInt("id", 0)
                bestMal = item.optInt("idMal", 0)
                bestTitles = titleList
            }
        }

        if (bestId <= 0) return null
        return bestId to bestTitles + "#mal=$bestMal"
    }

    private fun getAnimeThemes(aniListId: Int, searchTitles: List<String>): AnimeThemeInfo? {
        val url = ANIMETHEMES_URL +
            "?filter[external_id]=$aniListId" +
            "&filter[has]=resources" +
            "&filter[site]=AniList" +
            "&include=animethemes.animethemeentries.videos,resources"

        val root = httpJson(url) ?: return null
        val anime = root.optJSONArray("anime")?.optJSONObject(0) ?: return null

        var hasOp = false
        var hasEd = false
        val themes = anime.optJSONArray("animethemes")
        if (themes != null) {
            for (i in 0 until themes.length()) {
                val theme = themes.optJSONObject(i) ?: continue
                when (theme.optString("type").uppercase(Locale.ROOT)) {
                    "OP" -> hasOp = true
                    "ED" -> hasEd = true
                }
            }
        }

        var aniDbId: Int? = null
        var malId: Int? = null
        val resources = anime.optJSONArray("resources")
        if (resources != null) {
            for (i in 0 until resources.length()) {
                val resource = resources.optJSONObject(i) ?: continue
                val site = resource.optString("site")
                val externalId = resource.optString("external_id").toIntOrNull() ?: continue
                when {
                    site.equals("AniDB", true) -> aniDbId = externalId
                    site.equals("MyAnimeList", true) -> malId = externalId
                }
            }
        }

        // Depending on the API serializer, resources can be returned in the
        // included relationship instead of nested on the anime object.
        val included = root.optJSONArray("resources")
        if (included != null) {
            for (i in 0 until included.length()) {
                val resource = included.optJSONObject(i) ?: continue
                val site = resource.optString("site")
                val externalId = resource.optString("external_id").toIntOrNull() ?: continue
                when {
                    aniDbId == null && site.equals("AniDB", true) -> aniDbId = externalId
                    malId == null && site.equals("MyAnimeList", true) -> malId = externalId
                }
            }
        }

        // Fallback MAL ID from the AniList search result marker.
        if (malId == null) {
            searchTitles.lastOrNull { it.startsWith("#mal=") }
                ?.removePrefix("#mal=")?.toIntOrNull()?.takeIf { it > 0 }
                ?.let { malId = it }
        }

        return AnimeThemeInfo(aniListId, aniDbId, malId, hasOp, hasEd)
    }

    private fun getAnimeThemesByMal(malId: Int, searchTitles: List<String>): AnimeThemeInfo? {
        val url = ANIMETHEMES_URL +
            "?filter[external_id]=$malId" +
            "&filter[has]=resources" +
            "&filter[site]=MyAnimeList" +
            "&include=animethemes.animethemeentries.videos,resources"
        val root = httpJson(url) ?: return null
        val anime = root.optJSONArray("anime")?.optJSONObject(0) ?: return null

        var hasOp = false
        var hasEd = false
        val themes = anime.optJSONArray("animethemes")
        if (themes != null) {
            for (i in 0 until themes.length()) {
                when (themes.optJSONObject(i)?.optString("type")?.uppercase(Locale.ROOT)) {
                    "OP" -> hasOp = true
                    "ED" -> hasEd = true
                }
            }
        }

        var aniListId: Int? = null
        var aniDbId: Int? = null
        var resolvedMalId: Int? = malId
        val resources = anime.optJSONArray("resources")
        if (resources != null) {
            for (i in 0 until resources.length()) {
                val resource = resources.optJSONObject(i) ?: continue
                val site = resource.optString("site")
                val externalId = resource.optString("external_id").toIntOrNull() ?: continue
                when {
                    site.equals("AniList", true) -> aniListId = externalId
                    site.equals("AniDB", true) -> aniDbId = externalId
                    site.equals("MyAnimeList", true) -> resolvedMalId = externalId
                }
            }
        }
        return AnimeThemeInfo(aniListId, aniDbId, resolvedMalId, hasOp, hasEd)
    }

    private fun getTimestampEntry(aniDbId: Int, episodeNumber: Int): TimestampEntry? {
        val database = timestampDatabase ?: run {
            val loaded = httpJson(TIMESTAMPS_URL) ?: return null
            timestampDatabase = loaded
            loaded
        }

        val entries = database.optJSONArray(aniDbId.toString()) ?: return null
        val candidates = mutableListOf<TimestampEntry>()

        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optInt("episode_number", -1) != episodeNumber) continue

            val source = item.optString("source", "unknown")
            // Do not use rows that were only copied from AniSkip.
            if (source.equals("anime_skip", ignoreCase = true)) continue

            val opening = item.optJSONObject("opening")
            val ending = item.optJSONObject("ending")
            val openingStart = opening?.optDouble("start", -1.0) ?: item.optDouble("opening_start", -1.0)
            val openingEnd = opening?.optDouble("end", -1.0) ?: item.optDouble("opening_end", -1.0)
            val endingStart = ending?.optDouble("start", -1.0) ?: item.optDouble("ending_start", -1.0)
            val endingEnd = ending?.optDouble("end", -1.0) ?: item.optDouble("ending_end", -1.0)

            candidates += TimestampEntry(
                source,
                openingStart,
                openingEnd,
                endingStart,
                endingEnd,
                item.optDouble("preview_start", -1.0)
            )
        }

        return candidates.firstOrNull()
    }

    private fun titleScore(query: String, titles: List<String>): Int {
        val q = normalize(query)
        if (q.isBlank()) return 0
        return titles.maxOfOrNull { title ->
            val t = normalize(title)
            when {
                t == q -> 100
                t.contains(q) || q.contains(t) -> 85
                else -> tokenScore(q, t)
            }
        } ?: 0
    }

    private fun tokenScore(a: String, b: String): Int {
        val aa = a.chunked(2).toSet()
        val bb = b.chunked(2).toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0
        return ((aa.intersect(bb).size.toDouble() / maxOf(aa.size, bb.size)) * 80.0).toInt()
    }

    private fun httpJson(urlString: String, method: String = "GET", requestBody: String? = null): JSONObject? {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 25_000
            useCaches = true
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LilacAnime/1.0")
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        return try {
            if (requestBody != null) {
                connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "HTTP_ERROR code=$code url=$urlString body=${body.take(500)}")
                return null
            }
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "HTTP_EXCEPTION url=$urlString", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
}
