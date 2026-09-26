package com.lilac.anime.data

import android.net.Uri
import com.lilac.anime.Anime
import com.lilac.anime.Episode
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

object ReAnimeParser {
    private const val BASE_URL = "https://reanime.to"
    private const val ID_PREFIX = "reanime:"

    /** Parse the JSON returned by Re:ANIME's current API. */
    fun parseAnimeApi(json: String): List<Anime> {
        val root = runCatching { JSONObject(json) }.getOrNull()
        val array = runCatching { JSONArray(json) }.getOrNull()
        val candidates = when {
            array != null -> array
            root != null -> findAnimeArray(root)
            else -> JSONArray()
        }

        val result = linkedMapOf<String, Anime>()
        for (i in 0 until candidates.length()) {
            val item = candidates.optJSONObject(i) ?: continue
            val anime = parseAnimeObject(item) ?: continue
            result[anime.id] = anime
        }
        return result.values.toList()
    }

    private fun findAnimeArray(root: JSONObject): JSONArray {
        val preferred = listOf("data", "results", "anime", "animes", "items", "latest_aired", "top_weekly")
        preferred.forEach { key ->
            root.optJSONArray(key)?.let { if (it.length() > 0) return it }
        }
        // Some responses wrap the payload one level deeper.
        preferred.forEach { key ->
            val obj = root.optJSONObject(key) ?: return@forEach
            preferred.forEach { nested ->
                obj.optJSONArray(nested)?.let { if (it.length() > 0) return it }
            }
        }
        return JSONArray()
    }

    private fun parseAnimeObject(item: JSONObject): Anime? {
        val anime = item.optJSONObject("anime") ?: item

        // The v1 search API can expose the Re:ANIME slug either directly,
        // as a URL, or inside a nested anime object. Prefer the real URL/slug
        // so clicking a catalog item always opens /anime/{slug}.
        val detailUrlFromApi = firstNonBlank(
            stringValue(anime.opt("detailUrl")),
            stringValue(anime.opt("detail_url")),
            stringValue(anime.opt("url")),
            stringValue(anime.opt("link")),
            stringValue(anime.opt("href")),
            stringValue(item.opt("detailUrl")),
            stringValue(item.opt("detail_url")),
            stringValue(item.opt("url")),
            stringValue(item.opt("link")),
            stringValue(item.opt("href"))
        )

        // Re:ANIME v1 search currently exposes the real site slug in anime_id,
        // e.g. "attack-on-titan-p9y2p9". This is the authoritative value for
        // building /anime/{slug}; do not derive the slug from the display title.
        val rawSlug = firstNonBlank(
            stringValue(anime.opt("anime_id")),
            stringValue(item.opt("anime_id")),
            stringValue(anime.opt("slug")),
            stringValue(anime.opt("anime_slug")),
            stringValue(anime.opt("url_slug")),
            stringValue(item.opt("slug")),
            stringValue(item.opt("anime_slug")),
            stringValue(item.opt("url_slug"))
        )

        val slug = extractAnimeSlug(detailUrlFromApi)
            ?: extractAnimeSlug(rawSlug)
            ?: rawSlug?.takeIf { looksLikeSlug(it) }

        val title = stringValue(anime.opt("title"))
            .ifBlank { stringValue(anime.opt("name")) }
            .ifBlank { stringValue(item.opt("title")) }
            .ifBlank { stringValue(item.opt("name")) }
        if (title.isBlank()) return null

        // Re:ANIME's display title is already the English search title we want
        // to feed into NamuWiki for Korean title resolution. Do not depend on
        // a separate romaji/romanized field from the API.
        val romaji = title

        // Keep provider IDs when Re:Anime exposes them. Different API builds
        // have used several spellings, so accept the common variants.
        val malId = firstInt(
            anime, item,
            "mal_id", "malId", "myanimelist_id", "myanimelistId", "myanimelist", "mal"
        )
        val anilistId = firstInt(
            anime, item,
            "anilist_id", "anilistId", "anilist", "anilist_media_id"
        )

        val finalSlug = slug ?: title
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        val id = ID_PREFIX + finalSlug

        val poster = firstNonBlank(
            extractImage(anime.opt("cover_image")),
            extractImage(anime.opt("cover")),
            extractImage(anime.opt("poster")),
            extractImage(anime.opt("image")),
            extractImage(item.opt("cover_image")),
            extractImage(item.opt("poster")),
            extractImage(item.opt("image"))
        ).orEmpty()

        val description = stringValue(anime.opt("description"))
            .ifBlank { stringValue(anime.opt("synopsis")) }
        val genres = extractStringList(anime.opt("genres"))
            .ifEmpty { extractStringList(item.opt("genres")) }

        val detailUrl = "$BASE_URL/anime/$finalSlug"

        android.util.Log.d(
            "ReAnime",
            "PARSED_ANIME title=$title slug=$finalSlug detailUrl=$detailUrl " +
                "anilistId=$anilistId malId=$malId"
        )

        return Anime(
            id = id,
            anilistId = anilistId,
            malId = malId,
            title = title,
            poster = poster,
            backdrop = poster,
            genres = genres,
            description = description,
            romaji = romaji,
            detailUrl = detailUrl
        )
    }

    private fun firstNonBlank(vararg values: String): String? {
        return values.firstOrNull { it.isNotBlank() }
    }

    private fun looksLikeSlug(value: String): Boolean {
        val text = value.trim().removePrefix("/").removeSuffix("/")
        return text.contains("-") &&
            !text.contains(" ") &&
            !text.equals("anime", true) &&
            !text.equals("watch", true)
    }

    private fun extractAnimeSlug(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val text = value.trim()
        if (looksLikeSlug(text)) {
            return text
                .substringAfterLast("/anime/")
                .substringAfterLast("/watch/")
                .substringBefore("?")
                .substringBefore("#")
                .trim('/')
                .takeIf { looksLikeSlug(it) }
        }

        return runCatching {
            val uri = Uri.parse(text)
            val path = uri.path.orEmpty()
            val marker = when {
                "/anime/" in path -> "/anime/"
                "/watch/" in path -> "/watch/"
                else -> return@runCatching null
            }
            path.substringAfter(marker)
                .substringBefore('/')
                .trim()
                .takeIf { looksLikeSlug(it) }
        }.getOrNull()
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? =
        keys.asSequence().map { stringValue(obj.opt(it)) }.firstOrNull { it.isNotBlank() }

    private fun firstStringFromObject(value: Any?, vararg keys: String): String? =
        (value as? JSONObject)?.let { firstString(it, *keys) }

    private fun firstInt(first: JSONObject, second: JSONObject, vararg keys: String): Int? =
        keys.asSequence()
            .mapNotNull { key ->
                val a = first.opt(key)
                val b = second.opt(key)
                sequenceOf(a, b).mapNotNull { value ->
                    when (value) {
                        is Number -> value.toInt()
                        is String -> value.trim().toIntOrNull()
                        else -> null
                    }
                }.firstOrNull { it > 0 }
            }
            .firstOrNull()

    private fun stringValue(value: Any?): String {
        return when (value) {
            is String -> value.trim()
            is JSONObject -> firstString(value, "english", "romaji", "native", "name", "title") ?: ""
            else -> value?.toString()?.trim().orEmpty()
        }
    }

    private fun extractImage(value: Any?): String {
        if (value is String) return value.trim()
        if (value is JSONObject) {
            listOf("extra_large", "large", "medium", "url", "src", "original", "poster").forEach { key ->
                val s = stringValue(value.opt(key))
                if (s.isNotBlank()) return s
            }
        }
        return ""
    }

    private fun extractStringList(value: Any?): List<String> {
        if (value is JSONArray) return (0 until value.length()).mapNotNull {
            val s = stringValue(value.opt(it))
            s.takeIf { it.isNotBlank() }
        }
        if (value is JSONObject) {
            return value.keys().asSequence().map { key -> stringValue(value.opt(key)) }
                .filter { it.isNotBlank() }.toList()
        }
        return emptyList()
    }

    fun parseAnimeList(document: Document): List<Anime> = emptyList()

    fun parseAnimeDetail(document: Document, original: Anime): Anime {
        val title = sequenceOf(
            document.selectFirst("h1")?.text()?.trim().orEmpty(),
            original.title
        ).firstOrNull { it.isNotBlank() } ?: original.title
        val description = sequenceOf(
            document.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty(),
            document.selectFirst(".description, [class*=description], [class*=synopsis]")?.text()?.trim().orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val poster = sequenceOf(
            document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty(),
            document.selectFirst("img")?.absUrl("src").orEmpty(),
            original.poster
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        return original.copy(title = title, description = description, poster = poster, backdrop = poster)
    }

    /** Parse the JSON returned by Re:ANIME /api/episodes/{slug}. */
    fun parseEpisodeApi(json: String, anime: Anime): List<Episode> {
        val rootObject = runCatching { JSONObject(json) }.getOrNull()
        val rootArray = runCatching { JSONArray(json) }.getOrNull()
        val candidates = when {
            rootArray != null -> rootArray
            rootObject != null -> findEpisodeArray(rootObject)
            else -> JSONArray()
        }

        val slug = extractSlug(anime.detailUrl)
            ?: anime.id.removePrefix(ID_PREFIX).trim('/')
        if (slug.isBlank()) return emptyList()

        val result = linkedMapOf<String, Episode>()
        for (i in 0 until candidates.length()) {
            val raw = candidates.optJSONObject(i) ?: continue
            val item = raw.optJSONObject("episode") ?: raw

            // Some API responses wrap the actual episode object one more level.
            val payload = item.optJSONObject("data") ?: item.optJSONObject("episode") ?: item

            val number = firstPositiveInt(
                payload, item, raw,
                "number", "episode", "episodeNumber", "episode_number",
                "ep", "epNumber", "episode_no", "episodeNo"
            ) ?: continue

            val display = number.toString()
            val title = firstString(
                payload, item, raw,
                "title", "name", "episode_title", "episodeTitle"
            ).ifBlank { "Episode $display" }

            // The player route is stable even when the API response does not
            // include an explicit watch URL.
            val videoUrl = BASE_URL + "/watch/" + slug + "?ep=" + number
            val id = ID_PREFIX + slug + ":" + display
            result[id] = Episode(
                id = id,
                number = number,
                title = title,
                videoUrl = videoUrl,
                displayNumber = display
            )
        }

        val episodes = result.values.sortedBy { it.number }
        android.util.Log.d(
            "ReAnime",
            "PARSED_EPISODE_API slug=$slug count=${episodes.size}"
        )
        return episodes
    }

    private fun findEpisodeArray(root: JSONObject): JSONArray {
        val keys = listOf("data", "episodes", "results", "items")
        for (key in keys) {
            root.optJSONArray(key)?.let { return it }
            val nested = root.optJSONObject(key)
            if (nested != null) {
                for (nestedKey in keys) {
                    nested.optJSONArray(nestedKey)?.let { return it }
                }
            }
        }
        return JSONArray()
    }

    private fun firstPositiveInt(vararg args: Any): Int? {
        val objects = args.filterIsInstance<JSONObject>()
        val keys = args.dropWhile { it is JSONObject }.filterIsInstance<String>()
        for (key in keys) {
            for (obj in objects) {
                val value = obj.opt(key)
                val number = when (value) {
                    is Number -> value.toInt()
                    is String -> value.trim().toIntOrNull()
                    else -> null
                }
                if (number != null && number > 0) return number
            }
        }
        return null
    }

    private fun firstString(vararg args: Any): String {
        val objects = args.filterIsInstance<JSONObject>()
        val keys = args.dropWhile { it is JSONObject }.filterIsInstance<String>()
        for (key in keys) {
            for (obj in objects) {
                val value = stringValue(obj.opt(key))
                if (value.isNotBlank()) return value
            }
        }
        return ""
    }

    fun parseEpisodes(document: Document, anime: Anime): List<Episode> {
        return parseEpisodeLinks(document, anime).sortedBy { it.number }
    }

    private fun parseEpisodeLinks(document: Document, anime: Anime): List<Episode> {
        val result = linkedMapOf<String, Episode>()
        document.select("a[href*='/watch/']").forEach { link ->
            val href = link.absUrl("href").trim()
            val slug = extractSlug(href) ?: return@forEach
            if (!slug.equals(extractSlug(anime.detailUrl), true)) return@forEach
            val number = parseEpisodeNumber(href) ?: parseEpisodeNumber(link.text()) ?: return@forEach
            val display = number.toString()
            val id = "$ID_PREFIX$slug:$display"
            val title = link.text().trim().ifBlank { "Episode $display" }
            result[id] = Episode(id = id, number = number, title = title, videoUrl = normalizeEpisodeUrl(href, slug), displayNumber = display)
        }
        return result.values.toList()
    }

    fun parseDubEpisodes(document: Document, anime: Anime): List<Episode> = emptyList()

    private fun normalizeEpisodeUrl(url: String, slug: String): String {
        val episode = parseEpisodeNumber(url) ?: return "$BASE_URL/watch/$slug?ep=latest"
        return "$BASE_URL/watch/$slug?ep=$episode"
    }

    private fun extractSlug(url: String): String? {
        val path = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
        val markers = listOf("/watch/", "/anime/")
        for (marker in markers) {
            val index = path.indexOf(marker)
            if (index >= 0) {
                return path.substring(index + marker.length)
                    .trim('/')
                    .substringBefore('/')
                    .takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun parseEpisodeNumber(value: String): Int? {
        val query = runCatching { Uri.parse(value).getQueryParameter("ep") }.getOrNull()
        query?.toIntOrNull()?.let { return it }
        val text = value.trim()
        Regex("(?i)(?:episode|ep|#)\\s*[-.]?\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }
}
