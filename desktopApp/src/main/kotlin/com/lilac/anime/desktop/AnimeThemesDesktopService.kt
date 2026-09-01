package com.lilac.anime.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/** Desktop counterpart of the Android AnimeThemes + Open Anime Timestamps skip provider. */
data class DesktopSkipSegment(val type: String, val startTime: Double, val endTime: Double)

object AnimeThemesDesktopService {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private var timestampsJson: String? = null

    suspend fun getSkipTimes(title: String, episode: Int, length: Int): List<DesktopSkipSegment> = withContext(Dispatchers.IO) {
        val malId = findMalId(title) ?: return@withContext emptyList()
        val animeThemes = getAnimeThemes(malId) ?: return@withContext emptyList()
        val aniDbId = animeThemes.aniDbId ?: return@withContext emptyList()
        val db = timestampsJson ?: get("https://raw.githubusercontent.com/jonbarrow/open-anime-timestamps/master/timestamps.json")?.also { timestampsJson = it }
            ?: return@withContext emptyList()
        val block = Regex("\\\"$aniDbId\\\"\\s*:\\s*\\[(.*?)]\\s*(?:,\\s*\\\"|})", RegexOption.DOT_MATCHES_ALL).find(db)?.groupValues?.get(1)
            ?: return@withContext emptyList()
        val result = mutableListOf<DesktopSkipSegment>()
        val entryRegex = Regex("\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL)
        for (m in entryRegex.findAll(block)) {
            val item = m.groupValues[1]
            val ep = number(item, "episode_number") ?: continue
            if (ep != episode) continue
            val source = string(item, "source") ?: ""
            if (source.equals("anime_skip", true)) continue
            val opening = number(item, "opening_start") ?: -1.0
            val ending = number(item, "ending_start") ?: -1.0
            val preview = number(item, "preview_start") ?: -1.0
            if (animeThemes.hasOp && opening >= 0) {
                val end = minOf(opening + 90.0, if (ending > opening + 1) ending else length.toDouble())
                if (end > opening + 1) result += DesktopSkipSegment("op", opening, end)
            }
            if (animeThemes.hasEd && ending >= 0) {
                val end = if (preview > ending + 1) preview else length.toDouble()
                if (end > ending + 1) result += DesktopSkipSegment("ed", ending, end.coerceAtMost(length.toDouble()))
            }
            if (result.isNotEmpty()) break
        }
        result.sortedBy { it.startTime }
    }

    private data class ThemeInfo(val aniDbId: Int?, val hasOp: Boolean, val hasEd: Boolean)

    private fun findMalId(title: String): Int? {
        val q = URLEncoder.encode(title, StandardCharsets.UTF_8)
        val body = get("https://api.jikan.moe/v4/anime?q=$q&limit=10") ?: return null
        return Regex("\\\"mal_id\\\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun getAnimeThemes(malId: Int): ThemeInfo? {
        val resource = get("https://api.animethemes.moe/resource?filter[external_id]=$malId&filter[site]=MyAnimeList&include=anime") ?: return null
        val animeId = Regex("\\\"id\\\"\\s*:\\s*(\\d+)").find(resource)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val body = get("https://api.animethemes.moe/anime?filter[anime][id]=$animeId&include=animethemes.animethemeentries,resources") ?: return null
        val hasOp = Regex("\\\"type\\\"\\s*:\\s*\\\"OP\\\"").containsMatchIn(body)
        val hasEd = Regex("\\\"type\\\"\\s*:\\s*\\\"ED\\\"").containsMatchIn(body)
        val resources = body.substringAfter("\\\"resources\\\"", "")
        val aniDbId = Regex("\\\"site\\\"\\s*:\\s*\\\"AniDB\\\".*?\\\"external_id\\\"\\s*:\\s*\\\"?(\\d+)", RegexOption.DOT_MATCHES_ALL)
            .find(resources)?.groupValues?.get(1)?.toIntOrNull()
        return ThemeInfo(aniDbId, hasOp, hasEd)
    }

    private fun get(url: String): String? = runCatching {
        val req = HttpRequest.newBuilder().uri(URI(url)).header("Accept", "application/json").header("User-Agent", "LilacAnime Desktop").GET().build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() in 200..299) res.body() else null
    }.getOrNull()

    private fun number(text: String, key: String): Double? = Regex("\\\"$key\\\"\\s*:\\s*(-?[0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    private fun string(text: String, key: String): String? = Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(text)?.groupValues?.get(1)
}
