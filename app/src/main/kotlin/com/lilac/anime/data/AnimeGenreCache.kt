package com.lilac.anime.data

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
import com.lilac.anime.data.subtitle.*
import com.lilac.anime.network.*
import com.lilac.anime.player.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.detail.*
import com.lilac.anime.ui.home.*
import com.lilac.anime.ui.navigation.*
import com.lilac.anime.ui.search.*
import com.lilac.anime.ui.settings.*
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 목록에서 상세 페이지를 반복 요청하지 않도록 작품별 장르/태그를
 * TTL 동안 캐시한다. TTL이 지나면 다음 상세 요청에서 다시 가져온다.
 */
object AnimeGenreCache {
    private const val PREFS = "anime_genre_cache"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_TIMES = "times"
    private const val TTL_MS = 12L * 60L * 60L * 1000L

    @Volatile
    private var loaded = false
    private val memory = HashMap<String, List<String>>()
    private val times = HashMap<String, Long>()

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
            ?: run { loaded = true; return }
        runCatching {
            val root = JSONObject(raw)
            root.keys().forEach { key ->
                val array = root.optJSONArray(key) ?: JSONArray()
                memory[key] = buildList {
                    for (i in 0 until array.length()) add(array.optString(i))
                }.filter { it.isNotBlank() }.distinct()
            }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TIMES, null)?.let { timeRaw ->
                    val timeRoot = JSONObject(timeRaw)
                    timeRoot.keys().forEach { key -> times[key] = timeRoot.optLong(key, 0L) }
                }
        }
        loaded = true
    }

    @Synchronized
    fun get(context: Context, source: String, detailUrl: String): List<String>? {
        ensureLoaded(context)
        val key = cacheKey(source, detailUrl)
        val savedAt = times[key] ?: 0L
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > TTL_MS) {
            memory.remove(key)
            times.remove(key)
            return null
        }
        return memory[key]
    }

    @Synchronized
    fun put(context: Context, source: String, detailUrl: String, genres: List<String>) {
        ensureLoaded(context)
        val key = cacheKey(source, detailUrl)
        memory[key] = genres.filter { it.isNotBlank() }.distinct()
        times[key] = System.currentTimeMillis()

        val root = JSONObject()
        memory.forEach { (entryKey, values) ->
            root.put(entryKey, JSONArray(values))
        }
        val timeRoot = JSONObject().apply { times.forEach { (entryKey, value) -> put(entryKey, value) } }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, root.toString())
            .putString(KEY_TIMES, timeRoot.toString())
            .apply()
    }

    @Synchronized
    fun clear(context: Context) {
        memory.clear()
        times.clear()
        loaded = true
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun cacheKey(source: String, detailUrl: String): String =
        "$source|$detailUrl"
}
