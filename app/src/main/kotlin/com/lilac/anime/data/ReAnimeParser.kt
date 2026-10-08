package com.lilac.anime.data

import android.net.Uri
import com.lilac.anime.Anime
import com.lilac.anime.Episode
import com.lilac.anime.ReAnimeRelated
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

object ReAnimeParser {
    data class EpisodePage(
        val episodes: List<Episode>,
        val limit: Int,
        val offset: Int,
        val total: Int,
        val totalPages: Int
    )
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

        // Re:ANIME search responses commonly expose title metadata as an
        // AniList-like object: { english, romaji, native }. Preserve the
        // native/Japanese title separately instead of losing it when the
        // display title is converted to a String.
        val titleValue = anime.opt("title")
        val itemTitleValue = item.opt("title")

        val title = stringValue(titleValue)
            .ifBlank { stringValue(anime.opt("name")) }
            .ifBlank { stringValue(itemTitleValue) }
            .ifBlank { stringValue(item.opt("name")) }
        if (title.isBlank()) return null

        val nativeTitle = firstNonBlank(
            extractJapaneseTitle(titleValue),
            extractJapaneseTitle(itemTitleValue),
            firstString(
                anime,
                "title_native", "titleNative", "native_title",
                "nativeTitle", "japanese_title", "japaneseTitle",
                "japanese", "native"
            ),
            firstString(
                item,
                "title_native", "titleNative", "native_title",
                "nativeTitle", "japanese_title", "japaneseTitle",
                "japanese", "native"
            ),
            findJapaneseTitle(anime),
            findJapaneseTitle(item)
        ).orEmpty()

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
        val season = firstNonBlank(
            stringValue(anime.opt("season")),
            stringValue(item.opt("season"))
        ).orEmpty()
        val seasonYear = firstInt(
            anime, item, "season_year", "seasonYear", "season_year_value"
        ) ?: firstInt(anime, item, "year", "release_year", "releaseYear")
        val seasonNumber = firstInt(
            anime, item, "season_number", "seasonNumber", "season_num", "seasonIndex"
        ) ?: inferSeasonNumber(title)

        val detailUrl = "$BASE_URL/anime/$finalSlug"

        android.util.Log.d(
            "ReAnime",
            "PARSED_ANIME title=$title native=$nativeTitle slug=$finalSlug detailUrl=$detailUrl " +
                "anilistId=$anilistId malId=$malId season=$season seasonYear=$seasonYear seasonNumber=$seasonNumber"
        )

        return Anime(
            id = id,
            anilistId = anilistId,
            malId = malId,
            title = title,
            native = nativeTitle,
            poster = poster,
            backdrop = poster,
            genres = genres,
            description = description,
            season = season,
            seasonYear = seasonYear,
            seasonNumber = seasonNumber,
            detailUrl = detailUrl
        )
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
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

    private fun extractJapaneseTitle(value: Any?): String? {
        if (value !is JSONObject) return null
        return firstString(
            value,
            "native", "japanese", "title_native", "titleNative",
            "native_title", "nativeTitle", "japanese_title", "japaneseTitle"
        )
    }

    /**
     * Re:ANIME has changed the shape of title metadata between API builds.
     * If the title object is nested differently, walk the response and pick
     * an explicitly named native/Japanese title instead of falling back to
     * the English display title.
     */
    private fun findJapaneseTitle(value: Any?): String? {
        when (value) {
            is JSONObject -> {
                val direct = firstString(
                    value,
                    "native", "japanese", "title_native", "titleNative",
                    "native_title", "nativeTitle", "japanese_title", "japaneseTitle"
                )
                if (!direct.isNullOrBlank()) return direct

                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val nested = findJapaneseTitle(value.opt(key))
                    if (!nested.isNullOrBlank()) return nested
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val nested = findJapaneseTitle(value.opt(i))
                    if (!nested.isNullOrBlank()) return nested
                }
            }
        }
        return null
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? =
        keys.asSequence().map { stringValue(obj.opt(it)) }.firstOrNull { it.isNotBlank() }

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
        val html = document.html()
        val animeStart = html.indexOf("anime:{")
        val episodesStart = html.indexOf("},episodes:{", animeStart).takeIf { it > animeStart } ?: html.length
        val mediaHtml = if (animeStart >= 0 && episodesStart > animeStart) html.substring(animeStart, episodesStart) else html
        val title = firstNonBlank(
            document.selectFirst("h1")?.text()?.trim(),
            original.title
        ).orEmpty()

        val poster = firstNonBlank(
            document.selectFirst("meta[property=og:image]")?.attr("content")?.trim(),
            original.poster
        ).orEmpty()

        // The detail page embeds the complete AniList-like media object in the
        // SSR payload. Prefer it over DOM text so the app gets the same metadata
        // that Re:Anime displays: synopsis, format, year, studios, native title,
        // season/status, genres, IDs and relations.
        val description = regexString(mediaHtml, "description:\"((?:\\\\.|[^\"])*)\"")
            ?.let(::decodeJsString)
            ?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\\n")
            ?.replace(Regex("<[^>]+>"), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: original.description

        val native = regexObjectString(mediaHtml, "title:\\{[^}]*native:\"((?:\\\\.|[^\"])*)\"")
            ?.let(::decodeJsString)
            ?: original.native
        val english = regexObjectString(mediaHtml, "title:\\{[^}]*english:\"((?:\\\\.|[^\"])*)\"")
            ?.let(::decodeJsString).orEmpty()
        val romaji = regexObjectString(mediaHtml, "title:\\{[^}]*romaji:\"((?:\\\\.|[^\"])*)\"")
            ?.let(::decodeJsString).orEmpty()

        val format = regexString(mediaHtml, "format:\"([^\"]+)\"").orEmpty()
        val status = regexString(mediaHtml, "status:\"([^\"]+)\"").orEmpty()
        val source = regexString(mediaHtml, "source:\"([^\"]+)\"").orEmpty()
        val season = regexString(mediaHtml, "season:\"([^\"]+)\"").orEmpty()
        val year = regexString(mediaHtml, "season_year:(\\d+)")
            ?: Regex("start_date:\\{[^}]*year:(\\d+)").find(mediaHtml)?.groupValues?.getOrNull(1)
            ?: ""
        val startDate = Regex("start_date:\\{day:(\\d+),month:(\\d+),year:(\\d+)").find(mediaHtml)?.let {
            "%04d-%02d-%02d".format(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt())
        }.orEmpty()
        val endDate = Regex("end_date:\\{day:(\\d+),month:(\\d+),year:(\\d+)").find(mediaHtml)?.let {
            "%04d-%02d-%02d".format(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt())
        }.orEmpty()
        val airedDate = when {
            startDate.isNotBlank() && endDate.isNotBlank() -> "$startDate ~ $endDate"
            startDate.isNotBlank() -> startDate
            else -> original.airedDate
        }

        val genres = Regex("genres:\\[([^]]*)\\]").find(mediaHtml)?.groupValues?.getOrNull(1)
            ?.let { Regex("\"((?:\\\\.|[^\"])*)\"").findAll(it).map { m -> decodeJsString(m.groupValues[1]) }.toList() }
            .orEmpty()
            .ifEmpty { original.genres }

        val studios = Regex("studios:\\[([^]]*)\\]").find(mediaHtml)?.groupValues?.getOrNull(1)
            ?.let { Regex("name:\"((?:\\\\.|[^\"])*)\"").findAll(it).map { m -> decodeJsString(m.groupValues[1]) }.toList() }
            .orEmpty()
            .ifEmpty { original.studios }

        val synonyms = Regex("synonyms:\\[([^]]*)\\]").find(mediaHtml)?.groupValues?.getOrNull(1)
            ?.let { Regex("\"((?:\\\\.|[^\"])*)\"").findAll(it).map { m -> decodeJsString(m.groupValues[1]) }.toList() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ")
            .orEmpty()

        val related = parseRelations(mediaHtml, finalIdPrefix = ID_PREFIX)

        val anilistId = regexInt(mediaHtml, "anilist_id:(\\d+)") ?: original.anilistId
        val malId = regexInt(mediaHtml, "mal_id:(\\d+)") ?: original.malId

        android.util.Log.d(
            "ReAnime",
            "DETAIL_METADATA title=$title anilistId=$anilistId malId=$malId format=$format " +
                "genres=${genres.size} studios=${studios.size} related=${related.size} descriptionLength=${description.length}"
        )

        return original.copy(
            title = title,
            description = description,
            poster = poster,
            backdrop = poster,
            anilistId = anilistId,
            malId = malId,
            native = native,
            english = english,
            romaji = romaji,
            genres = genres,
            studios = studios,
            synonyms = synonyms,
            format = format,
            year = year,
            season = season,
            seasonYear = season.toIntOrNull() ?: year.toIntOrNull(),
            seasonNumber = regexInt(mediaHtml, "season_number:(\\d+)") ?: inferSeasonNumber(title),
            airedDate = airedDate,
            source = source,
            note = listOfNotNull(
                status.takeIf { it.isNotBlank() },
                season.takeIf { it.isNotBlank() }
            ).joinToString(" · "),
            reAnimeRelated = related
        )
    }

    private fun inferSeasonNumber(title: String): Int? {
        val patterns = listOf(
            Regex("(?i)\\bseason\\s*(\\d+)\\b"),
            Regex("(?i)\\bpart\\s*(\\d+)\\b"),
            Regex("(?i)\\bcour\\s*(\\d+)\\b"),
            Regex("(?i)\\bs(\\d+)\\b"),
            Regex("(\\d+)\\s*기\\b")
        )
        return patterns.firstNotNullOfOrNull { it.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            ?.takeIf { it > 0 }
    }

    private fun regexString(text: String, pattern: String): String? =
        Regex(pattern).find(text)?.groupValues?.getOrNull(1)

    private fun regexObjectString(text: String, pattern: String): String? = regexString(text, pattern)

    private fun regexLastString(text: String, pattern: String): String? =
        Regex(pattern).findAll(text).lastOrNull()?.groupValues?.getOrNull(1)

    private fun regexInt(text: String, pattern: String): Int? =
        Regex(pattern).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun parseRelations(html: String, finalIdPrefix: String): List<ReAnimeRelated> {
        val start = html.indexOf("relations:[")
        if (start < 0) return emptyList()
        val end = html.indexOf("],requested:", start).takeIf { it > start } ?: return emptyList()
        val section = html.substring(start + "relations:[".length, end)
        val pattern = Regex(
            """\{anime_id:"([^"]+)",cover_image:\{[^}]*extra_large:"([^"]+)"[^}]*\},format:"([^"]*)",relation_type:"([^"]*)",season:"([^"]*)",season_year:(\d+),title:\{english:"([^"]*)",native:"([^"]*)",romaji:"([^"]*)"""
        )
        return pattern.findAll(section).mapNotNull { m ->
            val slug = decodeJsString(m.groupValues[1]).trim()
            if (slug.isBlank()) return@mapNotNull null
            val english = decodeJsString(m.groupValues[7])
            val native = decodeJsString(m.groupValues[8])
            val romaji = decodeJsString(m.groupValues[9])
            ReAnimeRelated(
                id = finalIdPrefix + slug,
                title = english.ifBlank { native.ifBlank { romaji } },
                nativeTitle = native,
                romaji = romaji,
                poster = decodeJsString(m.groupValues[2]),
                format = m.groupValues[3],
                relationType = m.groupValues[4],
                season = m.groupValues[5],
                seasonYear = m.groupValues[6].toIntOrNull()
            )
        }.distinctBy { it.id }.toList()
    }

    /**
     * Re:ANIME 상세 페이지는 회차를 /watch 링크로 렌더링하지 않는다.
     * SSR payload 안의:
     *
     *   episodes:{data:[{aired:...,episodeId:"ep-1",episode_number:1,...}],limit:100,offset:0,total:...}
     *
     * 구조에 회차 메타데이터를 넣어준다. 기존 href 기반 파서는 이 페이지에서
     * 회차를 0개로 판단했기 때문에, SSR payload를 우선 파싱한다.
     */
    fun parseEpisodePage(document: Document, anime: Anime): EpisodePage {
        val slug = extractSlug(anime.detailUrl) ?: return EpisodePage(emptyList(), 100, 0, 0, 1)
        val html = document.html()
        val payloadStart = run {
            val compact = html.indexOf("episodes:{data:[")
            if (compact >= 0) compact else html.indexOf("episodes: {data:[")
        }

        if (payloadStart >= 0) {
            val dataStart = html.indexOf("[", payloadStart)
            val dataEnd = html.indexOf("],limit:", dataStart).takeIf { it >= 0 }
                ?: html.indexOf("], limit:", dataStart)
            if (dataStart >= 0 && dataEnd > dataStart) {
                val episodePayload = html.substring(dataStart + 1, dataEnd)
                val parsed = parseEpisodePayload(episodePayload, slug).sortedBy { it.number }
                val metaTail = html.substring(dataEnd, (dataEnd + 180).coerceAtMost(html.length))
                val limit = Regex("""limit\s*:\s*(\d+)""").find(metaTail)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 100
                val offset = Regex("""offset\s*:\s*(\d+)""").find(metaTail)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val total = Regex("""total\s*:\s*(\d+)""").find(metaTail)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: parsed.size
                val totalPages = Regex("""totalPages\s*:\s*(\d+)""").find(metaTail)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: ((total + limit - 1) / limit).coerceAtLeast(1)
                android.util.Log.d("ReAnime", "EPISODE_PAGE_PARSED slug=$slug offset=$offset limit=$limit total=$total pages=$totalPages count=${parsed.size}")
                return EpisodePage(parsed, limit, offset, total, totalPages)
            }
        }

        return EpisodePage(parseEpisodeLinks(document, anime).sortedBy { it.number }, 100, 0, 0, 1)
    }

    /** Legacy/fallback parser for pages that still render /watch links directly. */
    private fun parseEpisodeLinks(document: Document, anime: Anime): List<Episode> {
        val slug = extractSlug(anime.detailUrl) ?: return emptyList()
        val result = linkedMapOf<Int, Episode>()
        for (link in document.select("a[href]")) {
            val href = link.attr("href")
            if (!href.contains("/watch/")) continue
            val number = parseEpisodeNumber(href) ?: continue
            val title = link.text().trim().ifBlank { "Episode $number" }
            val absolute = if (href.startsWith("http")) href else "$BASE_URL${if (href.startsWith("/")) href else "/$href"}"
            result[number] = Episode(
                id = "$ID_PREFIX$slug:$number",
                number = number,
                title = title,
                videoUrl = normalizeEpisodeUrl(absolute, slug),
                displayNumber = number.toString(),
                playable = true
            )
        }
        return result.values.toList()
    }

    fun parseEpisodes(document: Document, anime: Anime): List<Episode> =
        parseEpisodePage(document, anime).episodes

    private fun parseEpisodePayload(payload: String, slug: String): List<Episode> {
        val objectPattern = Regex(
            """\{aired:"((?:\\.|[^"])*)",description:"((?:\\.|[^"])*)",dubbed:(true|false),duration:(\d+),episodeId:"((?:\\.|[^"])*)",episode_number:(\d+),is_filler:(true|false),is_recap:(true|false),playable:(true|false),site:"((?:\\.|[^"])*)",subbed:(true|false),thumbnail:"((?:\\.|[^"])*)",title:"((?:\\.|[^"])*)",title_japanese:"((?:\\.|[^"])*)",title_romanji:"((?:\\.|[^"])*)",updated_at:"((?:\\.|[^"])*)",url:"((?:\\.|[^"])*)"\}"""
        )

        return objectPattern.findAll(payload).mapNotNull { match ->
            val g = match.groupValues
            val aired = decodeJsString(g[1])
            val description = decodeJsString(g[2])
            val dubbed = g[3].toBoolean()
            val episodeId = decodeJsString(g[5])
            val number = g[6].toIntOrNull() ?: return@mapNotNull null
            val filler = g[7].toBoolean()
            val recap = g[8].toBoolean()
            val playable = g[9].toBoolean()
            val subbed = g[11].toBoolean()
            val thumbnail = decodeJsString(g[12])
            val title = decodeJsString(g[13]).ifBlank { "Episode $number" }
            val japaneseTitle = decodeJsString(g[14])
            val romanji = decodeJsString(g[15])

            val id = "$ID_PREFIX$slug:$number"
            Episode(
                id = id,
                number = number,
                title = title,
                description = description,
                videoUrl = normalizeEpisodeUrl("$BASE_URL/watch/$slug?ep=$number", slug),
                displayNumber = number.toString(),
                nativeTitle = japaneseTitle,
                airedDate = aired,
                isFiller = filler,
                isRecap = recap,
                playable = playable,
                subbed = subbed,
                dubbed = dubbed,
                thumbnailUrl = thumbnail
            ).also {
                android.util.Log.v(
                    "ReAnime",
                    "DETAIL_EPISODE id=$episodeId ep=$number title=$title romanji=$romanji " +
                        "filler=$filler recap=$recap playable=$playable"
                )
            }
        }.distinctBy { it.number }.toList()
    }

    private fun decodeJsString(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching {
            // JSONObject가 JS의 \\uXXXX / escaped quote를 안전하게 복원한다.
            org.json.JSONArray("""["$value"]""").getString(0)
        }.getOrElse {
            value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
        }.trim()
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
