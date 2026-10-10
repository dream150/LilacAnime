package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import com.lilac.anime.data.subtitle.translation.SecureApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Resolves an anime's best Korean title for subtitle providers.
 *
 * TMDB is deliberately used only as a title resolver; it is never involved in
 * playback or subtitle download. Results are cached by normalized source title.
 */
object TmdbTitleResolver {
    private const val TAG = "TmdbTitle"
    private const val PREFS = "tmdb_title_cache"
    private const val KEY_PREF = "pref_tmdb_api_key"
    private const val SECURE_KEY = "tmdb"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(context: Context, title: String): String? = withContext(Dispatchers.IO) {
        val query = normalize(title)
        if (query.isBlank()) return@withContext null

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(query, null)?.takeIf { it.isNotBlank() }?.let { return@withContext it }

        val apiKey = apiKey(context)
        if (apiKey.isBlank()) {
            Log.d(TAG, "SKIP_NO_API_KEY")
            return@withContext null
        }

        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey" +
                "&language=ko-KR&query=$encoded&page=1&include_adult=false"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "LilacAnime/TmdbTitleResolver")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code}")
                    return@use null
                }
                val results = JSONObject(body).optJSONArray("results") ?: return@use null
                val best = chooseBest(query, results)
                best?.let {
                    prefs.edit().putString(query, it).apply()
                    Log.d(TAG, "FOUND query=[$query] korean=[$it]")
                }
                best
            }
        }.getOrElse {
            Log.w(TAG, "FAILED query=[$query]", it)
            null
        }
    }


    /**
     * Resolve the best Korean title using all titles the source knows about.
     * TMDB is tried before community title lookup by callers so one bad source
     * spelling does not prevent subtitle discovery.
     */
    suspend fun resolveBest(context: Context, titles: Collection<String>): String? = withContext(Dispatchers.IO) {
        titles.map { normalize(it) }.filter { it.isNotBlank() }.distinct().forEach { query ->
            resolve(context, query)?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        }
        null
    }


    suspend fun searchTitleVariants(context: Context, query: String, limit: Int = 8): List<String> = withContext(Dispatchers.IO) {
        val q = normalize(query)
        val key = apiKey(context)
        if (q.isBlank() || key.isBlank()) return@withContext emptyList()
        runCatching {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$key&language=ko-KR&query=$encoded&page=1&include_adult=false"
            val request = Request.Builder().url(url).header("Accept","application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val arr = JSONObject(response.body?.string().orEmpty()).optJSONArray("results") ?: return@use emptyList()
                buildList {
                    for (i in 0 until minOf(arr.length(), limit)) {
                        val item = arr.optJSONObject(i) ?: continue
                        if (item.optString("media_type") != "tv") continue
                        val ko = item.optString("name").trim()
                        val original = item.optString("original_name").trim()
                        if (ko.isNotBlank()) add(ko)
                        if (original.isNotBlank()) add(original)
                    }
                }.distinct()
            }
        }.getOrElse {
            Log.w(TAG, "SEARCH_VARIANTS_FAILED query=$q", it)
            emptyList()
        }
    }


    /** Translate a Korean Re:Anime search query into TMDB's original/English title. */
    suspend fun resolveOriginalSearchTitle(context: Context, title: String): String? = withContext(Dispatchers.IO) {
        val query = normalize(title)
        val key = apiKey(context)
        if (query.isBlank() || key.isBlank() || !query.any { it in '\uac00'..'\ud7a3' }) return@withContext null
        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$key&language=ko-KR&query=$encoded&page=1&include_adult=false"
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val arr = JSONObject(response.body?.string().orEmpty()).optJSONArray("results") ?: return@use null
                var best: String? = null
                var bestScore = -1
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    if (item.optString("media_type") != "tv") continue
                    val korean = item.optString("name").trim()
                    val original = item.optString("original_name").trim()
                    if (korean.isBlank() || original.isBlank()) continue
                    val score = when { korean.equals(query, true) -> 3; korean.contains(query, true) || query.contains(korean, true) -> 2; else -> 1 }
                    if (score > bestScore) { bestScore = score; best = original }
                }
                best
            }
        }.getOrElse { Log.w(TAG, "ORIGINAL_TITLE_FAILED query=[$query]", it); null }
    }

    /**
     * TMDB fallback for the anime search screen. The source catalog remains the
     * primary result; TMDB only fills an empty result or supplies Korean display
     * titles when the API key is configured.
     */
    suspend fun searchAnime(context: Context, query: String, limit: Int = 12): List<com.lilac.anime.Anime> = withContext(Dispatchers.IO) {
        val q = normalize(query)
        val key = apiKey(context)
        if (q.isBlank() || key.isBlank()) return@withContext emptyList()
        runCatching {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$key&language=ko-KR&query=$encoded&page=1&include_adult=false"
            val request = Request.Builder().url(url).header("Accept","application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val arr = JSONObject(response.body?.string().orEmpty()).optJSONArray("results") ?: return@use emptyList()
                buildList {
                    for (i in 0 until minOf(arr.length(), limit)) {
                        val item = arr.optJSONObject(i) ?: continue
                        if (item.optString("media_type") != "tv") continue
                        val id = item.optInt("id", 0)
                        if (id <= 0) continue
                        val ko = item.optString("name").trim()
                        val original = item.optString("original_name").trim()
                        if (ko.isBlank() && original.isBlank()) continue
                        val poster = item.optString("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }.orEmpty()
                        val backdrop = item.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w1280$it" }.orEmpty()
                        val year = item.optString("first_air_date").take(4)
                        add(com.lilac.anime.Anime(
                            id = "tmdb:$id",
                            title = ko.ifBlank { original },
                            poster = poster,
                            backdrop = backdrop,
                            description = item.optString("overview"),
                            year = year,
                            format = "TV",
                            source = "tmdb",
                            native = original,
                            english = original
                        ))
                    }
                }
            }
        }.getOrElse {
            Log.w(TAG, "SEARCH_FAILED query=$q", it)
            emptyList()
        }
    }

    suspend fun test(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = apiKey(context)
            require(key.isNotBlank()) { "TMDB API Key가 없습니다." }
            val request = Request.Builder()
                .url("https://api.themoviedb.org/3/configuration?api_key=$key")
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "TMDB HTTP ${response.code}" }
                "TMDB 연결 성공"
            }
        }
    }

    fun setApiKey(context: Context, value: String) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            SecureApiKeyStore.remove(context, SECURE_KEY)
            context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
                .edit().remove(KEY_PREF).apply()
        } else {
            SecureApiKeyStore.put(context, SECURE_KEY, trimmed)
        }
    }

    fun hasApiKey(context: Context): Boolean = apiKey(context).isNotBlank()

    private fun apiKey(context: Context): String =
        SecureApiKeyStore.get(context, SECURE_KEY)?.trim().orEmpty().ifBlank {
            context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
                .getString(KEY_PREF, null)?.trim().orEmpty()
        }

    private fun chooseBest(query: String, results: org.json.JSONArray): String? {
        data class Candidate(val title: String, val score: Double)
        val q = normalize(query)
        val candidates = mutableListOf<Candidate>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val mediaType = item.optString("media_type")
            if (mediaType != "tv" && mediaType != "movie") continue

            val korean = item.optString("name").ifBlank { item.optString("title") }.trim()
            if (korean.isBlank()) continue
            val original = item.optString("original_name").ifBlank { item.optString("original_title") }
            val overview = item.optString("overview")
            val popularity = item.optDouble("popularity", 0.0)

            val titleScore = similarity(q, normalize(original))
            val koreanScore = similarity(q, normalize(korean))
            val animeBonus = if (mediaType == "tv") 0.12 else 0.0
            val popularityBonus = (popularity.coerceAtMost(100.0) / 1000.0)
            val overviewBonus = if (overview.contains("anime", true) || overview.contains("animation", true)) 0.08 else 0.0
            val score = maxOf(titleScore, koreanScore * 0.85) + animeBonus + popularityBonus + overviewBonus
            candidates += Candidate(korean, score)
        }
        return candidates.maxByOrNull { it.score }
            ?.takeIf { it.score >= 0.18 }
            ?.title
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[‘’'`´]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.82
        val at = a.split(' ').filter { it.isNotBlank() }.toSet()
        val bt = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (at.isEmpty() || bt.isEmpty()) return 0.0
        return at.intersect(bt).size.toDouble() / at.union(bt).size.toDouble()
    }
}
