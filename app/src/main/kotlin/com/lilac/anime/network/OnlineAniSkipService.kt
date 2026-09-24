package com.lilac.anime.network

import android.util.Log
import com.lilac.anime.core.model.ChapterSkipSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Online AniSkip resolver.
 *
 * ID priority:
 *  1. direct MAL ID supplied by the source (Re:Anime when available)
 *  2. AniList ID supplied by the source -> AniList GraphQL idMal
 *
 * Title searching/NamuWiki fallback is intentionally not used here. Provider
 * IDs are much safer than guessing a title, and all HTTP/DNS work is forced
 * onto Dispatchers.IO so Android cannot throw NetworkOnMainThreadException.
 */
object OnlineAniSkipService {
    private const val TAG = "OnlineAniSkip"
    private const val ANI_SKIP_BASE = "https://api.aniskip.com/v2/skip-times"
    private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"

    private val allowedTypes = setOf("op", "ed", "mixed-op", "mixed-ed")

    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname)
                .sortedBy { if (it is Inet4Address) 0 else 1 }
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

    private data class TimedInt(val value: Int, val savedAt: Long)
    private data class TimedSegments(val value: List<ChapterSkipSegment>, val savedAt: Long)

    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    private val malIdCache = ConcurrentHashMap<String, TimedInt>()
    private val segmentCache = ConcurrentHashMap<String, TimedSegments>()

    suspend fun getSkipSegments(
        title: String,
        episodeNumber: Int,
        episodeLengthSeconds: Int = 0,
        anilistId: Int? = null,
        malId: Int? = null
    ): List<ChapterSkipSegment> = withContext(Dispatchers.IO) {
        Log.d(
            TAG,
            "GET_SKIP_START title=\"$title\" episode=$episodeNumber length=$episodeLengthSeconds " +
                "anilistId=$anilistId malId=$malId"
        )

        if (episodeNumber <= 0) {
            Log.d(TAG, "GET_SKIP_ABORT invalid episode=$episodeNumber")
            return@withContext emptyList()
        }

        val resolvedMalId = when {
            malId != null && malId > 0 -> {
                Log.d(TAG, "MAL_ID_DIRECT malId=$malId source=provider")
                malId
            }
            anilistId != null && anilistId > 0 -> {
                Log.d(TAG, "MAL_ID_FROM_ANILIST_START anilistId=$anilistId")
                resolveMalIdFromAniListId(anilistId)
            }
            else -> null
        }

        if (resolvedMalId == null) {
            Log.w(
                TAG,
                "MAL_ID_NOT_FOUND title=\"$title\" episode=$episodeNumber " +
                    "anilistId=$anilistId malId=$malId"
            )
            return@withContext emptyList()
        }

        Log.d(TAG, "MAL_ID_RESOLVED title=\"$title\" malId=$resolvedMalId")

        val cacheKey = "$resolvedMalId:$episodeNumber:0"
        segmentCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.savedAt <= CACHE_TTL_MS) {
                Log.d(TAG, "CACHE_HIT key=$cacheKey segments=${cached.value.size}")
                return@withContext cached.value
            }
            segmentCache.remove(cacheKey)
            Log.d(TAG, "CACHE_EXPIRED key=$cacheKey")
        }

        val url = "$ANI_SKIP_BASE/$resolvedMalId/$episodeNumber" +
            "?types=op&types=ed&types=mixed-op&types=mixed-ed&episodeLength=0"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "LilacAnime Android")
            .get()
            .build()

        Log.d(TAG, "ANISKIP_REQUEST url=$url")
        val result = runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Log.d(
                    TAG,
                    "ANISKIP_RESPONSE code=${response.code} success=${response.isSuccessful} bodyLength=${body.length}"
                )
                if (!response.isSuccessful || body.isBlank()) {
                    Log.w(TAG, "ANISKIP_HTTP_ERROR code=${response.code} malId=$resolvedMalId episode=$episodeNumber")
                    emptyList()
                } else {
                    parseSkipResponse(body)
                }
            }
        }.onFailure { error ->
            Log.e(
                TAG,
                "ANISKIP_NETWORK_ERROR ${error.javaClass.simpleName}: ${error.message}",
                error
            )
        }.getOrElse { emptyList() }

        val cleaned = result
            .filter { it.startTime >= 0.0 && it.endTime > it.startTime }
            .distinctBy { "${it.type}:${it.startTime}:${it.endTime}" }
            .sortedBy { it.startTime }

        segmentCache[cacheKey] = TimedSegments(cleaned, System.currentTimeMillis())
        Log.d(
            TAG,
            "GET_SKIP_DONE malId=$resolvedMalId episode=$episodeNumber segments=${cleaned.size} " +
                cleaned.joinToString(prefix = "[", postfix = "]") {
                    "${it.type}:${it.startTime}-${it.endTime}"
                }
        )
        cleaned
    }

    private fun parseSkipResponse(body: String): List<ChapterSkipSegment> {
        return runCatching {
            val root = JSONObject(body)
            val results = root.optJSONArray("results") ?: return@runCatching emptyList()
            Log.d(TAG, "PARSE_RESULTS count=${results.length()}")

            buildList {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val type = item.optString("skipType")
                    if (type !in allowedTypes) continue

                    val interval = item.optJSONObject("interval") ?: item
                    val start = interval.optDouble("startTime", Double.NaN)
                    val end = interval.optDouble("endTime", Double.NaN)
                    val episodeLength = item.optDouble("episodeLength", 0.0)
                    if (start.isFinite() && end.isFinite() && end > start) {
                        add(
                            ChapterSkipSegment(
                                type = type,
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
        }.getOrElse { emptyList() }
    }

    private suspend fun resolveMalIdFromAniListId(anilistId: Int): Int? = withContext(Dispatchers.IO) {
        val cacheKey = "anilist:$anilistId"
        malIdCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.savedAt <= CACHE_TTL_MS) {
                Log.d(TAG, "MAL_CACHE_HIT anilistId=$anilistId malId=${cached.value}")
                return@withContext cached.value
            }
            malIdCache.remove(cacheKey)
        }

        val query = """
            query (${ '$' }id: Int) {
                Media(id: ${ '$' }id, type: ANIME) {
                    id
                    idMal
                }
            }
        """.trimIndent()

        val requestBody = JSONObject()
            .put("query", query)
            .put("variables", JSONObject().put("id", anilistId))
            .toString()
            .toRequestBody("application/json".toMediaTypeOrNull())

        return@withContext runCatching {
            val request = Request.Builder()
                .url(ANILIST_GRAPHQL_URL)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "LilacAnime Android")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Log.d(TAG, "ANILIST_ID_LOOKUP id=$anilistId code=${response.code} bodyLength=${body.length}")
                if (!response.isSuccessful || body.isBlank()) return@use null

                val media = JSONObject(body)
                    .optJSONObject("data")
                    ?.optJSONObject("Media")
                    ?: return@use null
                val resolved = media.optInt("idMal", 0).takeIf { it > 0 }
                if (resolved != null) {
                    malIdCache[cacheKey] = TimedInt(resolved, System.currentTimeMillis())
                    Log.d(TAG, "ANILIST_ID_RESOLVED anilistId=$anilistId malId=$resolved")
                } else {
                    Log.w(TAG, "ANILIST_ID_HAS_NO_MAL anilistId=$anilistId")
                }
                resolved
            }
        }.onFailure { error ->
            Log.e(
                TAG,
                "ANILIST_ID_LOOKUP_ERROR anilistId=$anilistId ${error.javaClass.simpleName}: ${error.message}",
                error
            )
        }.getOrNull()
    }
}
