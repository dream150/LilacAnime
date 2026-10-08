package com.lilac.anime.data

import com.lilac.anime.Anime
import com.lilac.anime.Episode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

/**
 * Parser for the current Linkkf site (linkani.tv).
 *
 * The current site is server-rendered HTML.  The catalog, detail page and
 * episode/watch page all contain the information the Android app needs; there
 * is no dependency on the old linkkf.app JSON endpoints.
 */
object LinkkfParser {
    const val BASE_URL = "https://linkani.tv"

    fun parseAnimeList(document: Document, limit: Int = Int.MAX_VALUE): List<Anime> =
        document.select(".vod-list .vod-item")
            .mapNotNull { item ->
                val link = item.selectFirst("h3.vod-item-title a[href]")
                    ?: item.selectFirst("a.vod-item-img[href]")
                    ?: return@mapNotNull null
                val detailUrl = link.absUrl("href").ifBlank {
                    absolute(link.attr("href"))
                }
                val title = item.selectFirst("h3.vod-item-title")?.text()?.trim().orEmpty()
                if (detailUrl.isBlank() || title.isBlank()) return@mapNotNull null

                val image = item.selectFirst(".img-wrapper")?.attr("data-original").orEmpty()
                val episodeText = item.selectFirst(".vod-item-desc")?.text()?.trim().orEmpty()
                val id = extractAnimeId(detailUrl)
                Anime(
                    id = id,
                    title = title,
                    poster = absolute(image),
                    backdrop = absolute(image),
                    description = "",
                    episodes = emptyList(),
                    detailUrl = detailUrl,
                    note = episodeText
                )
            }
            .distinctBy { it.id }
            .take(limit)

    fun parseAnimeDetail(document: Document, original: Anime): Anime {
        val title = document.selectFirst(".detail-info-title")?.text()?.trim()
            .takeUnless { it.isNullOrBlank() } ?: original.title

        val poster = document.selectFirst(".detail-img [data-original]")
            ?.attr("data-original")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".detail-img img[src]")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
            ?: original.poster

        val description = document.selectFirst("meta[name=description]")
            ?.attr("content")
            ?.trim()
            .orEmpty()
            .ifBlank { original.description }

        val info = document.select(".detail-info-desc li")
            .map { it.text().replace(Regex("\\s+"), " ").trim() }

        fun infoValue(prefixes: List<String>): String =
            info.firstOrNull { line ->
                prefixes.any { line.startsWith(it, ignoreCase = true) }
            }?.substringAfter("：", "")
                ?.substringAfter(":", "")
                ?.trim()
                .orEmpty()

        val genreLine = info.firstOrNull {
            it.startsWith("장르：") || it.startsWith("장르:")
        }.orEmpty()
        val genres = genreLine
            .substringAfter("：", genreLine.substringAfter(":", ""))
            .split("/")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val studioLine = info.firstOrNull {
            it.startsWith("제작사：") || it.startsWith("제작사:")
        }.orEmpty()
        val studios = studioLine
            .substringAfter("：", studioLine.substringAfter(":", ""))
            .split("/")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = infoValue(listOf("년", "년도")).replace("년", "").trim()
        val format = infoValue(listOf("분류")).trim()
        val native = infoValue(listOf("원제")).trim()
        val anilistId = Regex("""anilist-(\d+)""")
            .find(absolute(poster))
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()

        val episodes = parseEpisodes(document, original)
        val related = document.select(".detail-actor-box .vod-item").mapNotNull { item ->
            val a = item.selectFirst("h3.vod-item-title a[href]") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { absolute(a.attr("href")) }
            val rid = extractAnimeId(href)
            val rtitle = a.text().trim()
            if (rid.isBlank() || rtitle.isBlank()) null
            else Triple(rid, rtitle, absolute(item.selectFirst(".img-wrapper")?.attr("data-original").orEmpty()))
        }.distinctBy { it.first }

        return original.copy(
            title = title,
            poster = absolute(poster),
            backdrop = absolute(poster),
            description = description,
            genres = genres,
            airedDate = year,
            year = year,
            format = format,
            studios = studios,
            native = native,
            anilistId = anilistId ?: original.anilistId,
            detailUrl = original.detailUrl.ifBlank { document.location() },
            episodes = episodes,
            // Keep related works source-local without changing the shared model:
            // existing Linkkf UI can continue using its separate related API state.
        )
    }

    fun parseEpisodes(document: Document, anime: Anime): List<Episode> =
        document.select(".episode-box a.ep[href], .episode-box a[href*='/watch/']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { absolute(link.attr("href")) }
                val raw = link.text().trim()
                val match = Regex("""(\d+)([A-Za-z]+)?""").find(raw)
                    ?: Regex("""/k(\d+)([A-Za-z]+)?/?$""").find(href)
                    ?: return@mapNotNull null
                val number = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                val suffix = match.groupValues.getOrNull(2).orEmpty().lowercase()
                val display = "$number$suffix"
                if (href.isBlank()) return@mapNotNull null
                Episode(
                    id = "${anime.id}_ep_${display.lowercase()}",
                    number = number,
                    title = "${display}화",
                    description = "${anime.title} ${display}화",
                    videoUrl = href,
                    displayNumber = display
                )
            }
            .distinctBy { it.videoUrl ?: it.id }
            .sortedWith(compareBy<Episode> { it.number }.thenBy { it.displayNumber })

    fun parseDubEpisodes(document: Document, anime: Anime): List<Episode> = emptyList()

    fun parseWatch(document: Document): WatchData? {
        val script = document.select("script").firstOrNull {
            it.data().contains("player_aaaa")
        }?.data().orEmpty()
        if (script.isBlank()) return null

        val start = script.indexOf("var player_aaaa")
        if (start < 0) return null
        val brace = script.indexOf('{', start)
        if (brace < 0) return null

        var depth = 0
        var quoted = false
        var escaped = false
        var end = -1
        for (i in brace until script.length) {
            val c = script[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
                continue
            }
            when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i + 1
                        break
                    }
                }
            }
        }
        if (end <= brace) return null

        val jsonText = script.substring(brace, end)
            .replace("\\u0026", "&")
            .replace("\\/", "/")
        val obj = runCatching { org.json.JSONObject(jsonText) }.getOrNull() ?: return null

        return WatchData(
            streamUrl = obj.optString("actual_url").ifBlank { obj.optString("url") }.trim().ifBlank { null },
            nextStreamUrl = obj.optString("url_next").trim().ifBlank { null },
            previousPage = obj.optString("link_pre").trim().ifBlank { null },
            nextPage = obj.optString("link_next").trim().ifBlank { null },
            subtitleUrl = obj.optString("subtitle_url").trim().ifBlank { null },
            title = obj.optJSONObject("vod_data")?.optString("vod_name").orEmpty()
        )
    }

    data class WatchData(
        val streamUrl: String?,
        val nextStreamUrl: String?,
        val previousPage: String?,
        val nextPage: String?,
        val subtitleUrl: String?,
        val title: String
    )

    private fun absolute(value: String): String {
        val v = value.trim()
        if (v.isBlank()) return ""
        return when {
            v.startsWith("http://") || v.startsWith("https://") -> v
            v.startsWith("//") -> "https:$v"
            v.startsWith("/") -> BASE_URL + v
            else -> "$BASE_URL/$v"
        }
    }

    private fun extractAnimeId(url: String): String =
        url.trimEnd('/').substringAfterLast('/').trim()

    private fun Element.absOrAbsolute(attr: String): String =
        absUrl(attr).ifBlank { absolute(attr(attr)) }
}
