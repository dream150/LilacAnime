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
 * makes the linkkf.app migration independent from the old the previous site markup markup.
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

        android.util.Log.d("LinkkfAPI", "REQUEST url=$url")

        var lastCode = -1
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    lastCode = response.code
                    android.util.Log.d(
                        "LinkkfAPI",
                        "RESPONSE code=${response.code} length=${body.length} attempt=${attempt + 1}"
                    )
                    if (response.isSuccessful) return body

                    // Cloudflare-style 522 is commonly transient. Retry before
                    // surfacing the failure to the repository/UI. Other 5xx
                    // responses also get one or two cheap retries.
                    if (response.code !in 500..599 || attempt == 2) {
                        throw IllegalStateException("Linkkf API HTTP ${response.code}: $url")
                    }
                }
            } catch (error: java.io.IOException) {
                lastError = error
                if (attempt == 2) throw error
            }
            try {
                Thread.sleep(350L * (attempt + 1))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }

        throw IllegalStateException(
            "Linkkf API request failed code=$lastCode: $url",
            lastError
        )
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

    data class ViewStats(
        val day: Int = 0,
        val week: Int = 0,
        val month: Int = 0,
        val total: Int = 0,
        val lastUpdated: String = ""
    )

    fun getViewStats(postId: String): ViewStats? {
        val root = JSONObject(get("$API_BASE/view.php?action=get&id=${encode(postId)}"))
        if (root.optString("status") != "success") return null
        val data = root.optJSONObject("data") ?: return null
        return ViewStats(
            day = data.optInt("day_views", 0),
            week = data.optInt("week_views", 0),
            month = data.optInt("month_views", 0),
            total = data.optInt("total_views", 0),
            lastUpdated = data.optString("last_updated", "")
        )
    }

    fun recordView(postId: String): Boolean {
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("action", "record")
            .addFormDataPart("id", postId)
            .build()
        val request = Request.Builder()
            .url("$API_BASE/view.php")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$WEB_BASE/up/$postId/")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val text = response.body?.string().orEmpty()
            return runCatching { JSONObject(text).optString("status") == "success" }.getOrDefault(false)
        }
    }

    data class RelatedSeries(
        val id: Int,
        val name: String,
        val count: Int,
        val items: List<Anime>
    )

    fun getRelatedSeries(seriesTagIds: List<Int>, currentPostId: String): List<RelatedSeries> {
        if (seriesTagIds.isEmpty()) return emptyList()
        return seriesTagIds.mapNotNull { tagId ->
            try {
                val tax = JSONObject(get("$API_BASE/link/tax.php?taxonomy=anime-aniss&tag_ID=$tagId"))
                val term = tax.optJSONArray("terms")?.optJSONObject(0)
                val name = term?.optString("name")?.trim().orEmpty().ifBlank { "Series $tagId" }
                val count = term?.optInt("count", 0) ?: 0
                val root = JSONObject(get("$API_BASE/singlefilter.php?postanisstagid=$tagId&limit=25"))
                val items = parseArray(root.optJSONArray("data"))
                    .filterNot { it.id == currentPostId }
                if (items.isEmpty()) null else RelatedSeries(tagId, name, count, items)
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.count }
    }

    data class EpisodeServer(val id: Int, val name: String, val episodes: List<Episode>)

    fun getEpisodeServers(postId: String): List<EpisodeServer> {
        val root = JSONArray(get("$EPISODE_API_BASE/api2.php?epid=$postId"))
        return (0 until root.length()).mapNotNull { index ->
            val server = root.optJSONObject(index) ?: return@mapNotNull null
            val id = server.optInt("id", -1)
            if (id <= 0) return@mapNotNull null
            val name = server.optString("server_name").trim().ifBlank { "Server $id" }
            val data = server.optJSONArray("server_data") ?: return@mapNotNull null
            val episodes = (0 until data.length()).mapNotNull { epIndex ->
                val item = data.optJSONObject(epIndex) ?: return@mapNotNull null
                val display = item.optString("name").trim()
                val slug = item.optString("slug").trim()
                val link = item.optString("link").trim()
                val numberMatch = Regex("""^(\d+)""").find(display.ifBlank { slug })
                val number = numberMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val suffix = display.substring(numberMatch.value.length).trim().lowercase()
                val displayNumber = if (suffix.isBlank()) number.toString() else "$number$suffix"
                val episodeKey = link.ifBlank { "${postId}v${id}_$slug" }
                Episode(
                    id = episodeKey,
                    number = number,
                    title = "${displayNumber}화",
                    description = displayNumber,
                    videoUrl = "$WEB_BASE/up/$postId/watch/?server=$id&slug=${encode(slug)}",
                    displayNumber = displayNumber
                )
            }.distinctBy { it.id }
                .sortedWith(compareBy<Episode> { it.number }.thenBy { it.displayNumber })
            EpisodeServer(id, name, episodes)
        }
    }

    fun getEpisodes(postId: String, serverId: Int = 12): List<Episode> {
        val servers = getEpisodeServers(postId)
        return servers.firstOrNull { it.id == serverId }?.episodes
            ?: servers.firstOrNull()?.episodes
            ?: emptyList()
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
        val description = first(item, "postcontent", "description", "synopsis")
        val seasonTypeTagIds = splitIntTags(first(item, "postseasontypetagid"))
        val studioTagIds = splitIntTags(first(item, "studiostagid"))
        val sourceTagIds = splitIntTags(first(item, "anisourceid", "postsourceid"))
        val yearTagId = first(item, "postyeartagid").toIntOrNull()
        val seriesTagIds = splitIntTags(first(item, "postanisstagid"))
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
            description = description,
            airedDate = first(item, "postdate", "datepub"),
            year = first(item, "postyear"),
            format = first(item, "postseasontype"),
            studios = splitTags(first(item, "poststudios")),
            source = first(item, "anisource"),
            romaji = first(item, "romaji"),
            english = first(item, "english"),
            native = first(item, "native"),
            synonyms = first(item, "anisynonyms"),
            note = first(item, "postnote", "postnoti"),
            seasonTypeTagIds = seasonTypeTagIds,
            studioTagIds = studioTagIds,
            sourceTagIds = sourceTagIds,
            yearTagId = yearTagId,
            seriesTagIds = seriesTagIds,
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

    private fun splitIntTags(value: String): List<Int> =
        value.split(",", "|", "/")
            .mapNotNull { it.trim().toIntOrNull() }
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
