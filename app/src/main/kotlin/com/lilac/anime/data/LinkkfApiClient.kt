package com.lilac.anime.data

import com.lilac.anime.Anime
import com.lilac.anime.Episode

/**
 * Compatibility facade for the Linkkf-specific callers already present in v29.
 *
 * The public method names are retained so the rest of the application does not
 * need to know that Linkkf has moved from its old JSON endpoints to linkani.tv.
 */
class LinkkfApiClient {
    companion object {
        const val WEB_BASE = "https://linkani.tv"
        const val API_BASE = WEB_BASE
        const val EPISODE_API_BASE = WEB_BASE
    }

    private val client = LinkkfClient()

    data class FilterTag(
        val id: Int,
        val name: String,
        val slug: String = "",
        val count: Int = 0
    )

    data class FilterResult(
        val items: List<Anime>,
        val page: Int,
        val totalPages: Int,
        val totalResults: Int
    )

    data class EpisodeServer(
        val id: Int,
        val name: String,
        val episodes: List<Episode>
    )

    data class PlayerLink(val server: String, val url: String)

    data class ViewStats(
        val day: Int = 0,
        val week: Int = 0,
        val month: Int = 0,
        val total: Int = 0,
        val lastUpdated: String = ""
    )

    data class RelatedSeries(
        val id: Int,
        val name: String,
        val count: Int,
        val items: List<Anime>
    )

    fun getHome(page: Int = 1, limit: Int = 12): List<Anime> =
        loadList(listUrl(page), limit)

    fun getFilteredAnime(
        page: Int = 1,
        limit: Int = 20,
        seasonTypeIds: List<Int> = emptyList(),
        genreIds: List<Int> = emptyList(),
        yearIds: List<Int> = emptyList()
    ): FilterResult {
        // The new site exposes filter links rather than the old numeric API.
        // Preserve the old integer contract by resolving IDs back to routes.
        val route = filterRoute(seasonTypeIds, genreIds, yearIds)
        val url = pagedRoute(route, page)
        val items = loadList(url, limit)
        val totalPages = detectTotalPages(url)
        return FilterResult(items, page.coerceAtMost(totalPages), totalPages, items.size)
    }

    fun getFilterTags(taxonomy: String): List<FilterTag> {
        val document = client.getDocument("$WEB_BASE/list/2/")
        val selector = when (taxonomy) {
            "anime-seasontype" -> "a[href*='/list/2/lang/']"
            "anigenres" -> "a[href*='/list/2/class/']"
            "anime-seasonys" -> "a[href*='/list/2/year/']"
            else -> ""
        }

        if (selector.isBlank()) return emptyList()

        return document.select(selector)
            .mapNotNull { a ->
                val name = a.text().trim()
                val href = a.attr("href").trim()
                if (name.isBlank() || href.isBlank()) return@mapNotNull null
                val weekday = name in setOf("월", "화", "수", "목", "금", "토", "일")
                if (name.equals("Genres", true) || name.equals("Year", true) ||
                    name.equals("Type", true) || name.equals("Anime", true) ||
                    weekday
                ) return@mapNotNull null
                FilterTag(
                    id = stableId(href),
                    name = name,
                    slug = href
                )
            }
            .distinctBy { it.id }
    }

    fun getSchedule(categoryTagId: Int, limit: Int = 50): List<Anime> {
        val day = when (categoryTagId) {
            21189 -> "월"
            21190 -> "화"
            21191 -> "수"
            21192 -> "목"
            21193 -> "금"
            21194 -> "토"
            21195 -> "일"
            else -> null
        } ?: return getHome(1, limit)

        val encoded = java.net.URLEncoder.encode(day, "UTF-8").replace("+", "%20")
        return loadList("$WEB_BASE/list/2/class/$encoded/", limit)
    }

    fun getAnime(postId: String): Anime? {
        if (postId.isBlank()) return null
        val url = "$WEB_BASE/ani/${postId.trim('/')}/"
        val document = client.getDocument(url)
        val seed = Anime(
            id = postId.trim('/'),
            title = document.selectFirst(".detail-info-title")?.text()?.trim().orEmpty(),
            detailUrl = url
        )
        return LinkkfParser.parseAnimeDetail(document, seed)
    }

    fun getEpisodes(postId: String, serverId: Int = 1): List<Episode> =
        getAnime(postId)?.episodes.orEmpty()

    fun getEpisodeServers(postId: String): List<EpisodeServer> {
        val episodes = getEpisodes(postId)
        if (episodes.isEmpty()) return emptyList()
        // The supplied HAR shows a single active "DB" source on the current site.
        return listOf(EpisodeServer(1, "DB", episodes))
    }

    fun getScheduleForDay(day: String, limit: Int = 50): List<Anime> {
        val normalized = day.trim()
        if (normalized.isBlank()) return getHome(1, limit)
        val encoded = java.net.URLEncoder.encode(normalized, "UTF-8").replace("+", "%20")
        return loadList("$WEB_BASE/list/2/class/$encoded/", limit)
    }

    fun getSeasonType(tagId: Int, limit: Int = 20): List<Anime> {
        val route = when (tagId) {
            // Current site's useful source-specific home sections.
            5086 -> "$WEB_BASE/label/topday/"
            5061 -> "$WEB_BASE/list/2/lang/Movie/"
            5085 -> "$WEB_BASE/list/9/"
            else -> "$WEB_BASE/list/2/"
        }
        return loadList(route, limit)
    }

    fun getViewStats(postId: String): ViewStats? = null
    fun recordView(postId: String): Boolean = false

    fun getRelatedSeries(seriesTagIds: List<Int>, currentPostId: String): List<RelatedSeries> {
        val anime = getAnime(currentPostId) ?: return emptyList()
        // Current Linkkf pages expose "관련 애니" as ordinary HTML cards.
        val document = client.getDocument(anime.detailUrl)
        val items = document.select(".detail-actor-box .vod-item").mapNotNull { card ->
            val a = card.selectFirst("h3.vod-item-title a[href]") ?: return@mapNotNull null
            val href = a.absUrl("href")
            val id = href.trimEnd('/').substringAfterLast('/')
            val title = a.text().trim()
            if (id.isBlank() || title.isBlank() || id == currentPostId) null
            else Anime(
                id = id,
                title = title,
                poster = card.selectFirst(".img-wrapper")?.attr("data-original").orEmpty().let { abs(it) },
                detailUrl = href
            )
        }.distinctBy { it.id }

        return if (items.isEmpty()) emptyList()
        else listOf(RelatedSeries(1, "관련 애니", items.size, items))
    }

    fun getPlayerLinks(episodeToken: String): List<PlayerLink> = emptyList()

    private fun loadList(url: String, limit: Int): List<Anime> {
        val document = client.getDocument(url)
        return LinkkfParser.parseAnimeList(document, limit)
    }

    private fun listUrl(page: Int): String =
        if (page <= 1) "$WEB_BASE/list/2/"
        else "$WEB_BASE/list/2/page/$page/"

    private fun pagedRoute(route: String, page: Int): String {
        if (page <= 1) return route
        return if (route.endsWith("/")) "${route}page/$page/" else "$route/page/$page/"
    }

    private fun detectTotalPages(url: String): Int {
        val document = runCatching { client.getDocument(url) }.getOrNull() ?: return 1
        val pages = document.select("a[href]").mapNotNull { a ->
            Regex("""/page/(\\d+)/?$""").find(a.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        return pages.maxOrNull() ?: 1
    }

    private fun filterRoute(
        seasonTypeIds: List<Int>,
        genreIds: List<Int>,
        yearIds: List<Int>
    ): String {
        val document = runCatching { client.getDocument("$WEB_BASE/list/2/") }.getOrNull()
        fun findRoute(id: Int): String? {
            if (document == null) return null
            return document.select("a[href]").firstOrNull { stableId(it.attr("href")) == id }?.attr("href")
        }
        val routeId = genreIds.firstOrNull() ?: yearIds.firstOrNull() ?: seasonTypeIds.firstOrNull()
        val route = routeId?.let(::findRoute)
        return if (route.isNullOrBlank()) "$WEB_BASE/list/2/" else abs(route)
    }

    private fun stableId(route: String): Int =
        route.hashCode().let { if (it == Int.MIN_VALUE) 1 else kotlin.math.abs(it) }

    private fun abs(value: String): String {
        val v = value.trim()
        return when {
            v.startsWith("http://") || v.startsWith("https://") -> v
            v.startsWith("//") -> "https:$v"
            v.startsWith("/") -> "$WEB_BASE$v"
            else -> "$WEB_BASE/$v"
        }
    }
}
