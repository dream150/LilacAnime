package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Optional TMDB fallback. The key is read from pref_tmdb_api_key. */
object TmdbTitleResolver {
    private const val TAG = "TmdbTitle"
    private const val PREFS = "tmdb_title_cache"
    private const val KEY_PREF = "pref_tmdb_api_key"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(context: Context, title: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val apiKey = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            .getString(KEY_PREF, null)?.trim().orEmpty()
        if (apiKey.isBlank() || title.isBlank()) return@withContext null

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(title, null)?.takeIf { it.isNotBlank() }?.let { return@withContext it }

        runCatching {
            val encoded = URLEncoder.encode(title.trim(), "UTF-8")
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&language=ko-KR&query=$encoded&page=1&include_adult=false"
            val request = Request.Builder().url(url).header("User-Agent", "LilacAnime/TmdbTitle").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val results = JSONObject(response.body?.string().orEmpty()).optJSONArray("results") ?: return@use null
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val ko = item.optString("name").ifBlank { item.optString("title") }.trim()
                    if (ko.isNotBlank()) {
                        prefs.edit().putString(title, ko).apply()
                        Log.d(TAG, "FOUND query=[$title] korean=[$ko]")
                        return@use ko
                    }
                }
                null
            }
        }.getOrElse {
            Log.w(TAG, "FAILED title=[$title]", it)
            null
        }
    }
}
