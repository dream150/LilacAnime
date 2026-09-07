package com.lilac.anime.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 목록에서 상세 페이지를 다시 요청하지 않도록 작품별 장르/태그를 영구 캐시한다.
 * 빈 목록도 정상적으로 파싱된 결과라면 캐시하여 같은 작품을 반복 요청하지 않는다.
 */
object AnimeGenreCache {
    private const val PREFS = "anime_genre_cache"
    private const val KEY_ENTRIES = "entries"

    @Volatile
    private var loaded = false
    private val memory = HashMap<String, List<String>>()

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
        }
        loaded = true
    }

    @Synchronized
    fun get(context: Context, source: String, detailUrl: String): List<String>? {
        ensureLoaded(context)
        return memory[cacheKey(source, detailUrl)]
    }

    @Synchronized
    fun put(context: Context, source: String, detailUrl: String, genres: List<String>) {
        ensureLoaded(context)
        val key = cacheKey(source, detailUrl)
        memory[key] = genres.filter { it.isNotBlank() }.distinct()

        val root = JSONObject()
        memory.forEach { (entryKey, values) ->
            root.put(entryKey, JSONArray(values))
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, root.toString())
            .apply()
    }

    @Synchronized
    fun clear(context: Context) {
        memory.clear()
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
