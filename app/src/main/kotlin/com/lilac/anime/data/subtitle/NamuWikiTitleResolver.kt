package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale

object NamuWikiTitleResolver {
    private const val TAG = "NamuWikiTitle"
    private const val PREF = "namuwiki_title_cache"
    private const val CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    private const val BASE_URL = "https://namu.wiki"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"

    suspend fun resolve(context: Context, searchTitle: String): String? = withContext(Dispatchers.IO) {
        val query = searchTitle.trim()
        if (query.isBlank()) return@withContext null
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val key = "v1:${cacheKey(query)}"
        val cachedAt = prefs.getLong("$key:at", 0L)
        prefs.getString("$key:value", null)?.trim()?.takeIf {
            it.isNotBlank() && System.currentTimeMillis() - cachedAt in 0 until CACHE_TTL_MS
        }?.let {
            Log.d(TAG, "CACHE_HIT searchTitle=[$query] korean=[$it]")
            return@withContext it
        }

        try {
            val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val url = "$BASE_URL/Search?q=$encoded"
            val doc = Jsoup.connect(url).userAgent(USER_AGENT)
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8")
                .timeout(15000).followRedirects(true).get()

            val candidates = doc.select("a[href]").mapNotNull { a ->
                val href = a.attr("href").trim()
                val text = a.text().trim()
                if (!href.startsWith("/w/") || text.isBlank()) return@mapNotNull null
                val pageTitle = href.substringAfter("/w/").substringBefore("?")
                    .replace('_', ' ').trim()
                if (!containsKorean(pageTitle)) return@mapNotNull null
                Triple(text, pageTitle, score(query, text, pageTitle))
            }.distinctBy { it.second }

            // NamuWiki itself ranks search results using its alias/index data,
            // so the first Korean /w/ result is the primary candidate. Local
            // scoring is only used when the page contains unrelated navigation
            // links before the actual result.
            val best = candidates.firstOrNull { it.third >= 0.45 }
                ?: candidates.maxByOrNull { it.third }

            if (best == null) {
                Log.w(TAG, "NO_RESULT searchTitle=[$query]")
                return@withContext null
            }

            val koreanTitle = best.second
            prefs.edit().putString("$key:value", koreanTitle)
                .putLong("$key:at", System.currentTimeMillis()).apply()
            Log.d(TAG, "RESOLVED searchTitle=[$query] korean=[$koreanTitle] score=${best.third}")
            koreanTitle
        } catch (e: Exception) {
            Log.w(TAG, "RESOLVE_FAILED searchTitle=[$query]", e)
            null
        }
    }

    private fun cacheKey(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9가-힣]+"), "_").trim('_')

    private fun containsKorean(value: String) = value.any { it in '\uAC00'..'\uD7A3' }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9가-힣]+"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun score(query: String, visible: String, pageTitle: String): Double {
        val q = normalize(query); val v = normalize(visible); val p = normalize(pageTitle)
        var best = maxOf(similarity(q, v), similarity(q, p))
        if (p.contains(q) || q.contains(p)) best = maxOf(best, 0.78)
        return best
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val at = a.split(' ').filter(String::isNotBlank).toSet()
        val bt = b.split(' ').filter(String::isNotBlank).toSet()
        val token = if (at.isEmpty() || bt.isEmpty()) 0.0 else at.intersect(bt).size.toDouble() / maxOf(at.size, bt.size)
        return minOf(1.0, token + if (a.startsWith(b) || b.startsWith(a)) 0.35 else 0.0)
    }
}
