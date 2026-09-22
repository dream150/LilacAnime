package com.lilac.anime.data

import com.lilac.anime.Anime
import com.lilac.anime.Episode
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Linkkf.app API client.
 *
 * Home/schedule/detail/episode data is served by the 1.5imgdarr API rather
 * than by the HTML page. Keeping this separate from the old Jsoup parser
 * makes the linkkf.app migration independent from the old linkkf.tv markup.
 */
class LinkkfApiClient {
    companion object {
        const val WEB_BASE = "https://linkkf.app"
        const val API_BASE = "https://linkkf1.5imgdarr.top/api"
        const val EPISODE_API_BASE = "https://linkkfep1.5imgdarr.top"

        private const val IMAGE_PROXY = "https://rez1.ims1.top/350x/"
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun get(url: String, referer: String = "$WEB_BASE/"): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Referer", referer)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Linkkf API HTTP ${response.code}: $url")
            }
            return response.body?.string().orEmpty()
        }
    }

    fun getHome(page: Int = 1, limit: Int = 12): List<Anime> {
        val root = JSONObject(get("$API_BASE/filter.php?page=$page&limit=$limit"))
        return parseArray(root.optJSONArray("data"))
    }

    data class FilterTag(val id: Int, val name: String, val slug: String = "", val count: Int = 0)

    fun getFilterTags(taxonomy: String): List<FilterTag> {
        val root = JSONObject(
            get("$API_BASE/link/api.php?taxonomy=${encode(taxonomy)}&limit=200&orderby=name&order=ASC")
        )
        val terms = root.optJSONArray("terms") ?: return emptyList()
        return (0 until terms.length()).mapNotNull { i ->
            val item = terms.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optInt("tag_ID", 0)
            if (id <= 0) return@mapNotNull null
            FilterTag(
                id = id,
                name = item.optString("name").trim(),
                slug = item.optString("slug").trim(),
                count = item.optInt("count", 0)
            )
        }
    }

    fun getFilteredAnime(
        page: Int = 1,
        limit: Int = 20,
        seasonTypeIds: List<Int> = emptyList(),
        genreIds: List<Int> = emptyList(),
        yearIds: List<Int> = emptyList()
    ): FilterResult {
        val params = mutableListOf("page=$page", "limit=$limit")
        if (seasonTypeIds.isNotEmpty()) params += "postseasontypetagid=${seasonTypeIds.joinToString(",")}"
        if (genreIds.isNotEmpty()) params += "postanigenrestagid=${genreIds.joinToString(",")}"
        if (yearIds.isNotEmpty()) params += "postyeartagid=${yearIds.joinToString(",")}"
        val root = JSONObject(get("$API_BASE/singlefilter.php?${params.joinToString("&")}"))
        return FilterResult(
            items = parseArray(root.optJSONArray("data")),
            page = root.optJSONObject("pagination")?.optInt("current_page", page) ?: page,
            totalPages = root.optJSONObject("pagination")?.optInt("total_pages", 1) ?: 1,
            totalResults = root.optJSONObject("pagination")?.optInt("total_results", 0) ?: 0
        )
    }

    data class FilterResult(val items: List<Anime>, val page: Int, val totalPages: Int, val totalResults: Int)

    fun getSchedule(categoryTagId: Int, limit: Int = 50): List<Anime> {
        val root = JSONObject(
            get("$API_BASE/singlefilter.php?categorytagid=$categoryTagId&limit=$limit")
        )
        return parseArray(root.optJSONArray("data"))
    }

    fun getAnime(postId: String): Anime? {
        val root = JSONObject(get("$API_BASE/single.php?postid=$postId"))
        return parseItem(root.optJSONObject("data"))
    }

    fun getEpisodes(postId: String, serverId: Int = 12): List<Episode> {
        val root = JSONArray(
            get("$EPISODE_API_BASE/api2.php?epid=$postId")
        )

        val server = (0 until root.length())
            .mapNotNull { root.optJSONObject(it) }
            .firstOrNull { it.optInt("id", -1) == serverId }
            ?: root.optJSONObject(0)
            ?: return emptyList()

        val data = server.optJSONArray("server_data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val display = item.optString("name").trim()
            val slug = item.optString("slug").trim()
            val link = item.optString("link").trim()

            val numberMatch = Regex("""^(\d+)""").find(display.ifBlank { slug })
            val number = numberMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null

            val suffix = display.substring(numberMatch.value.length)
                .trim()
                .lowercase()
            val displayNumber = if (suffix.isBlank()) number.toString() else "$number$suffix"
            val episodeKey = link.ifBlank { "${postId}v$slug" }

            Episode(
                id = episodeKey,
                number = number,
                title = "${displayNumber}화",
                description = displayNumber,
                videoUrl = "$WEB_BASE/up/$postId/watch/?server=$serverId&slug=${encode(slug)}",
                displayNumber = displayNumber
            )
        }.distinctBy { it.id }
            .sortedWith(compareBy<Episode> { it.number }.thenBy { it.displayNumber })
    }

    /**
     * Resolves the provider page for an episode. api2.php gives the stable
     * episode token; apilink2.php turns that token into playhd3.php links.
     */
    fun getPlayerLinks(episodeToken: String): List<PlayerLink> {
        val root = JSONObject(
            get("https://emdlinkkf.5imgdarr.top/apilink2.php?data=${encode(episodeToken)}")
        )
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val server = item.optString("server").trim()
            val link = item.optString("link").trim()
            if (link.isBlank()) null else PlayerLink(server, link)
        }
    }

    data class PlayerLink(val server: String, val url: String)

    private fun parseArray(array: JSONArray?): List<Anime> =
        if (array == null) emptyList()
        else (0 until array.length()).mapNotNull { parseItem(array.optJSONObject(it)) }
            .distinctBy { it.id }

    private fun parseItem(item: JSONObject?): Anime? {
        if (item == null) return null
        val id = item.optString("postid").trim()
        if (id.isBlank()) return null

        val title = first(item, "postname", "name")
        val thumb = first(item, "postthum", "thumb")
        val genres = splitTags(first(item, "postanigenres", "genres"))
        // Linkkf.app's API may expose this as anilistid/postanilistid (and
        // older API variants have used anilist_id/postanilist). Accept the
        // known variants so the ID is preserved instead of forcing a title
        // search later when AniSkip timestamps are requested.
        val anilistId = first(
            item,
            "anilistid",
            "anilist_id",
            "postanilistid",
            "postanilist",
            "anilistId",
            "anilist"
        ).toIntOrNull()

        return Anime(
            id = id,
            anilistId = anilistId,
            title = title,
            description = "",
            poster = normalizeImage(thumb),
            backdrop = thumb,
            genres = genres,
            episodes = emptyList(),
            detailUrl = "$WEB_BASE/up/$id/"
        )
    }

    private fun first(item: JSONObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            item.optString(key).trim().takeIf { it.isNotBlank() }
        }.orEmpty()

    private fun splitTags(value: String): List<String> =
        value.split(",", "|", "/")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun normalizeImage(url: String): String {
        val value = url.trim()
        if (value.isBlank()) return ""
        if (value.startsWith("https://rez1.ims1.top/")) return value
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return IMAGE_PROXY + value
        }
        if (value.startsWith("//")) return IMAGE_PROXY + "https:$value"
        return value
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
}
