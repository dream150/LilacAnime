package com.lilac.anime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Blogger index for Csora subtitles. */
object CsoraBlogRepository {
    private const val TAG = "CsoraRepository"
    private const val BLOG_URL = "https://csora556.blogspot.com"
    private const val CACHE_FILE = "csora_blog_index.json"
    private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private const val PAGE_SIZE = 500

    suspend fun getPosts(context: Context): List<KairanPost> = withContext(Dispatchers.IO) {
        val cache = File(context.filesDir, CACHE_FILE)
        val cached = loadCache(cache)
        if (cached.isNotEmpty() && cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS) {
            return@withContext cached
        }
        try {
            val fresh = downloadAllPosts()
            if (fresh.isNotEmpty()) saveCache(cache, fresh)
            fresh.ifEmpty { cached }
        } catch (e: Exception) {
            Log.w(TAG, "INDEX_REFRESH_FAILED", e)
            cached
        }
    }

    private fun downloadAllPosts(): List<KairanPost> {
        val out = LinkedHashMap<String, KairanPost>()
        var start = 1
        while (true) {
            val page = downloadPage(start)
            if (page.isEmpty()) break
            page.forEach { out[it.url] = it }
            if (page.size < PAGE_SIZE) break
            start += PAGE_SIZE
        }
        Log.d(TAG, "INDEX_COMPLETE count=${out.size}")
        return out.values.toList()
    }

    private fun downloadPage(start: Int): List<KairanPost> {
        val text = getText("$BLOG_URL/feeds/posts/default?alt=json&max-results=$PAGE_SIZE&start-index=$start")
        val entries = JSONObject(text).optJSONObject("feed")?.optJSONArray("entry") ?: return emptyList()
        return buildList {
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val title = entry.optJSONObject("title")?.optString("\$t")?.trim().orEmpty()
                val links = entry.optJSONArray("link") ?: continue
                var postUrl: String? = null
                for (j in 0 until links.length()) {
                    val link = links.optJSONObject(j) ?: continue
                    if (link.optString("rel") == "alternate") {
                        postUrl = link.optString("href").trim().takeIf { it.isNotBlank() }
                        break
                    }
                }
                if (title.isNotBlank() && postUrl != null) add(KairanPost(title, postUrl))
            }
        }
    }

    private fun getText(urlString: String): String {
        val c = URL(urlString).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"; c.connectTimeout = 5000; c.readTimeout = 15000
            c.instanceFollowRedirects = true; c.useCaches = false
            c.setRequestProperty("User-Agent", "LilacAnime")
            c.setRequestProperty("Accept", "application/json,*/*")
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun loadCache(file: File): List<KairanPost> {
        if (!file.isFile) return emptyList()
        return try {
        val array = JSONArray(file.readText(Charsets.UTF_8))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                val url = item.optString("url").trim()
                if (title.isNotBlank() && url.isNotBlank()) add(KairanPost(title, url))
            }
        }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCache(file: File, posts: List<KairanPost>) {
        val array = JSONArray()
        posts.forEach { array.put(JSONObject().put("title", it.title).put("url", it.url)) }
        file.writeText(array.toString(), Charsets.UTF_8)
    }
}
