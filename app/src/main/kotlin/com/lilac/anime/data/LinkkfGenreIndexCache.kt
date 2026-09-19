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
 * Persistent index built from Linkkf's genre/class listing pages.
 *
 * detailUrl -> [genre, genre, ...]
 * genre -> last scanned page
 *
 * This replaces the old per-anime detail-page genre enrichment. A genre page
 * contains many anime at once, so the network cost is amortized across the catalog.
 */
object LinkkfGenreIndexCache {
    private const val PREFS = "linkkf_genre_index"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_PAGES = "pages"
    private const val KEY_LAST_SYNC = "last_sync"

    @Volatile private var loaded = false
    private val entries = LinkedHashMap<String, MutableSet<String>>()
    private val pages = LinkedHashMap<String, Int>()
    private var lastSync = 0L

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching {
            prefs.getString(KEY_ENTRIES, null)?.let { raw ->
                val root = JSONObject(raw)
                root.keys().forEach { url ->
                    val array = root.optJSONArray(url) ?: JSONArray()
                    entries[url] = buildSet {
                        for (i in 0 until array.length()) add(array.optString(i))
                    }.filter { it.isNotBlank() }.toMutableSet()
                }
            }
            prefs.getString(KEY_PAGES, null)?.let { raw ->
                val root = JSONObject(raw)
                root.keys().forEach { genre -> pages[genre] = root.optInt(genre, 0) }
            }
            lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
        }
        loaded = true
    }

    @Synchronized
    fun genresFor(context: Context, detailUrl: String): List<String> {
        ensureLoaded(context)
        return entries[detailUrl]?.toList().orEmpty()
    }

    @Synchronized
    fun hasEntry(context: Context, detailUrl: String): Boolean {
        ensureLoaded(context)
        return entries.containsKey(detailUrl)
    }

    @Synchronized
    fun nextPage(context: Context, genre: String): Int {
        ensureLoaded(context)
        return (pages[genre] ?: 0) + 1
    }

    @Synchronized
    fun markPage(context: Context, genre: String, page: Int) {
        ensureLoaded(context)
        pages[genre] = maxOf(pages[genre] ?: 0, page)
        persist(context)
    }

    @Synchronized
    fun merge(context: Context, detailUrl: String, genre: String) {
        ensureLoaded(context)
        entries.getOrPut(detailUrl) { linkedSetOf() }.add(genre)
    }

    @Synchronized
    fun markSync(context: Context) {
        ensureLoaded(context)
        lastSync = System.currentTimeMillis()
        persist(context)
    }

    @Synchronized
    fun canSync(context: Context, intervalMs: Long): Boolean {
        ensureLoaded(context)
        return System.currentTimeMillis() - lastSync >= intervalMs
    }

    @Synchronized
    fun persist(context: Context) {
        ensureLoaded(context)
        val entriesJson = JSONObject().apply {
            entries.forEach { (url, genres) -> put(url, JSONArray(genres.toList())) }
        }
        val pagesJson = JSONObject().apply {
            pages.forEach { (genre, page) -> put(genre, page) }
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, entriesJson.toString())
            .putString(KEY_PAGES, pagesJson.toString())
            .putLong(KEY_LAST_SYNC, lastSync)
            .apply()
    }

    @Synchronized
    fun clear(context: Context) {
        entries.clear()
        pages.clear()
        lastSync = 0L
        loaded = true
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
