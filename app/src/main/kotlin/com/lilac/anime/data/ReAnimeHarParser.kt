package com.lilac.anime.data

import com.lilac.anime.Anime
import com.lilac.anime.Episode
import com.lilac.anime.ReAnimeRelated
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser for SvelteKit's devalue-style __data.json captured in the Re:ANIME HAR.
 *
 * The payload is a reference table. Numeric values in object fields are not all
 * references, so this parser resolves only fields whose schema is reference-based.
 */
object ReAnimeHarParser {
    private const val ID_PREFIX = "reanime:"


    data class Facets(
        val genres: List<String> = emptyList(),
        val formats: List<String> = emptyList(),
        val statuses: List<String> = emptyList(),
        val seasons: List<String> = emptyList(),
        val years: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val studios: List<String> = emptyList()
    )

    fun parseTop(json: String): List<Anime> = parseAnimeCollection(json)

    fun parseSchedule(json: String): List<Anime> = parseAnimeCollection(json)

    fun parseFacets(json: String): Facets {
        return runCatching {
            val root = JSONObject(json)
            fun values(vararg keys: String): List<String> {
                for (key in keys) {
                    val value = root.opt(key) ?: root.optJSONObject("facets")?.opt(key)
                    val result = mutableListOf<String>()
                    when (value) {
                        is JSONArray -> {
                            for (i in 0 until value.length()) {
                                val item = value.opt(i)
                                when (item) {
                                    is JSONObject -> {
                                        val v = item.optString("name").ifBlank { item.optString("value") }.ifBlank { item.optString("label") }
                                        if (v.isNotBlank()) result += v
                                    }
                                    else -> item?.toString()?.takeIf { it.isNotBlank() }?.let { result += it }
                                }
                            }
                        }
                        is JSONObject -> {
                            val keys = value.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                if (k.isNotBlank()) result += k
                            }
                        }
                    }
                    if (result.isNotEmpty()) return result.distinct()
                }
                return emptyList()
            }
            Facets(
                genres = values("genres", "genre"),
                formats = values("formats", "format"),
                statuses = values("statuses", "status"),
                seasons = values("seasons", "season"),
                years = values("years", "season_year", "year"),
                tags = values("tags", "tag"),
                studios = values("studios", "studio")
            )
        }.getOrDefault(Facets())
    }

    private fun parseAnimeCollection(json: String): List<Anime> {
        return runCatching {
            val root = JSONObject(json)
            val found = LinkedHashMap<String, JSONObject>()
            fun walk(value: Any?) {
                when (value) {
                    is JSONObject -> {
                        if (value.has("anime_id") || value.has("anilist_id")) {
                            val id = value.optString("anime_id").ifBlank { value.optInt("anilist_id", 0).toString() }
                            if (id.isNotBlank() && id != "0") found.putIfAbsent(id, value)
                        }
                        val keys = value.keys()
                        while (keys.hasNext()) walk(value.opt(keys.next()))
                    }
                    is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
                }
            }
            walk(root)
            found.values.mapNotNull { item ->
                val id = item.optString("anime_id").ifBlank { item.optString("slug") }.ifBlank { item.optInt("anilist_id", 0).toString() }
                if (id.isBlank() || id == "0") return@mapNotNull null
                val titleObj = item.optJSONObject("title")
                val title = titleObj?.optString("english").orEmpty()
                    .ifBlank { titleObj?.optString("romaji").orEmpty() }
                    .ifBlank { item.optString("title") }
                    .ifBlank { id }
                val posterObj = item.optJSONObject("cover_image")
                val poster = posterObj?.optString("extra_large").orEmpty()
                    .ifBlank { posterObj?.optString("large").orEmpty() }
                    .ifBlank { item.optString("poster") }
                val genres = item.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } } ?: emptyList()
                Anime(
                    id = "$ID_PREFIX$id",
                    anilistId = item.optInt("anilist_id", 0).takeIf { it > 0 },
                    malId = item.optInt("mal_id", 0).takeIf { it > 0 },
                    title = title,
                    native = titleObj?.optString("native").orEmpty(),
                    romaji = titleObj?.optString("romaji").orEmpty(),
                    english = titleObj?.optString("english").orEmpty(),
                    poster = poster,
                    backdrop = item.optString("banner_image").ifBlank { poster },
                    format = item.optString("format"),
                    year = item.optInt("season_year", 0).takeIf { it > 0 }?.toString().orEmpty(),
                    note = item.optString("status"),
                    genres = genres,
                    source = "reanime",
                    detailUrl = "${ReAnimeHarClient.BASE_URL}/anime/$id"
                )
            }
        }.getOrElse { emptyList() }
    }

    fun parseSearch(json: String): List<Anime> {
        // The /api/v1/search response has changed shape between Re:ANIME
        // deployments (results/data/items and nested anime objects). Reuse the
        // tolerant API parser that already handles those variants instead of
        // assuming results[] + title{} only.
        val parsed = runCatching { ReAnimeParser.parseAnimeApi(json) }
            .getOrDefault(emptyList())
        if (parsed.isNotEmpty()) return parsed.map { anime ->
            anime.copy(
                source = "reanime",
                id = if (anime.id.startsWith(ID_PREFIX)) anime.id else ID_PREFIX + anime.id.removePrefix("reanime:"),
                detailUrl = if (anime.detailUrl.isNotBlank()) anime.detailUrl
                else "${ReAnimeHarClient.BASE_URL}/anime/${anime.id.removePrefix(ID_PREFIX)}"
            )
        }

        // Keep a small direct fallback for older payloads whose entries are
        // exposed as a plain results array.
        return runCatching<List<Anime>> {
            val root = JSONObject(json)
            val results = root.optJSONArray("results") ?: root.optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val id = item.optString("anime_id").ifBlank { item.optString("slug") }
                    if (id.isBlank()) continue
                    val titleObj = item.optJSONObject("title")
                    val title = titleObj?.optString("english").orEmpty()
                        .ifBlank { titleObj?.optString("romaji").orEmpty() }
                        .ifBlank { item.optString("title") }
                        .ifBlank { id }
                    val cover = item.optJSONObject("cover_image")
                    add(Anime(
                        id = ID_PREFIX + id,
                        anilistId = item.optInt("anilist_id", 0).takeIf { it > 0 },
                        malId = item.optInt("mal_id", 0).takeIf { it > 0 },
                        title = title,
                        native = titleObj?.optString("native").orEmpty(),
                        romaji = titleObj?.optString("romaji").orEmpty(),
                        english = titleObj?.optString("english").orEmpty(),
                        poster = cover?.optString("extra_large").orEmpty().ifBlank { cover?.optString("large").orEmpty() },
                        format = item.optString("format"),
                        year = item.optInt("season_year", 0).takeIf { it > 0 }?.toString().orEmpty(),
                        note = item.optString("status"),
                        genres = item.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } } ?: emptyList(),
                        source = "reanime",
                        detailUrl = "${ReAnimeHarClient.BASE_URL}/anime/$id"
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun parseDetail(json: String, fallback: Anime): Anime {
        val table = table(json) ?: return fallback
        val root = table.obj(0) ?: return fallback
        val animeRef = root.optInt("anime", -1)
        val anime = table.obj(animeRef) ?: return fallback

        val slug = table.stringRef(anime, "anime_id")
            ?: fallback.detailUrl.substringAfter("/anime/").substringBefore("/")
                .takeIf { it.isNotBlank() }
        val titleObj = table.objRef(anime, "title")
        val title = table.stringRef(titleObj, "english")
            ?: table.stringRef(anime, "english")
            ?: fallback.title
        val native = table.stringRef(titleObj, "native") ?: fallback.native
        val romaji = table.stringRef(titleObj, "romaji") ?: fallback.romaji

        val cover = table.objRef(anime, "cover_image")
        val poster = table.stringRef(cover, "extra_large")
            ?: table.stringRef(cover, "large")
            ?: fallback.poster

        val start = table.dateRef(anime, "start_date")
        val end = table.dateRef(anime, "end_date")
        val aired = when {
            start != null && end != null -> "$start ~ $end"
            start != null -> start
            else -> fallback.airedDate
        }

        val genres = table.stringArrayRef(anime, "genres").ifEmpty { fallback.genres }
        val studios = table.objectArrayRef(anime, "studios").mapNotNull {
            table.stringRef(it, "name")
        }.ifEmpty { fallback.studios }

        val related = table.objectArrayRef(anime, "relations").mapNotNull { relation ->
            val relationSlug = table.stringRef(relation, "anime_id") ?: return@mapNotNull null
            val relationTitle = table.objRef(relation, "title")
            val relationCover = table.objRef(relation, "cover_image")
            ReAnimeRelated(
                id = "$ID_PREFIX$relationSlug",
                title = table.stringRef(relationTitle, "english")
                    ?: table.stringRef(relationTitle, "romaji")
                    ?: relationSlug,
                nativeTitle = table.stringRef(relationTitle, "native").orEmpty(),
                romaji = table.stringRef(relationTitle, "romaji").orEmpty(),
                poster = table.stringRef(relationCover, "extra_large")
                    ?: table.stringRef(relationCover, "large").orEmpty(),
                format = table.stringRef(relation, "format").orEmpty(),
                relationType = table.stringRef(relation, "relation_type").orEmpty(),
                season = table.stringRef(relation, "season").orEmpty(),
                seasonYear = table.intRef(relation, "season_year")
            )
        }

        return fallback.copy(
            id = "$ID_PREFIX${slug ?: fallback.id.removePrefix(ID_PREFIX)}",
            anilistId = table.intRef(anime, "anilist_id") ?: fallback.anilistId,
            malId = table.intRef(anime, "mal_id") ?: fallback.malId,
            title = title,
            native = native,
            romaji = romaji,
            english = table.stringRef(anime, "english") ?: title,
            poster = poster,
            backdrop = table.stringRef(table.objRef(anime, "banner_image"), "url") ?: poster,
            description = table.stringRef(anime, "description") ?: fallback.description,
            genres = genres,
            studios = studios,
            format = table.stringRef(anime, "format") ?: fallback.format,
            source = table.stringRef(anime, "source") ?: fallback.source,
            year = table.intRef(anime, "season_year")?.toString() ?: fallback.year,
            season = table.stringRef(anime, "season") ?: fallback.season,
            seasonYear = table.intRef(anime, "season_year") ?: fallback.seasonYear,
            seasonNumber = table.intRef(anime, "season_number") ?: table.intRef(anime, "seasonNumber") ?: fallback.seasonNumber,
            airedDate = aired,
            note = listOfNotNull(
                table.stringRef(anime, "status"),
                table.stringRef(anime, "season")
            ).joinToString(" · "),
            synonyms = table.stringArrayRef(anime, "synonyms").joinToString(", "),
            reAnimeRelated = related,
            detailUrl = "${ReAnimeHarClient.BASE_URL}/anime/${slug ?: fallback.id.removePrefix(ID_PREFIX)}"
        )
    }

    /**
     * The anime detail __data.json does NOT contain the episode array.
     * The captured Re:ANIME watch page does, under root.episodes.
     */
    fun parseWatchEpisodes(json: String, anime: Anime): List<Episode> {
        val table = table(json) ?: return emptyList()
        val root = table.obj(0) ?: return emptyList()
        val refs = table.arrayRef(root, "episodes")
        val slug = anime.detailUrl.substringAfter("/anime/").substringBefore("/")
            .ifBlank { anime.id.removePrefix(ID_PREFIX).substringBefore("/") }
        return refs.mapNotNull { ref ->
            val item = table.obj(ref) ?: return@mapNotNull null
            episodeFromTable(table, item, slug)
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    /**
     * Builds the complete episode list when the watch payload only exposes the
     * current/last episode. episodes_total comes from the same HAR-backed detail
     * object, so missing entries remain real Re:ANIME watch URLs rather than
     * fabricated provider URLs. Known episode metadata is overlaid when present.
     */
    fun parseWatchEpisodes(json: String, anime: Anime, totalEpisodes: Int): List<Episode> {
        val known = parseWatchEpisodes(json, anime).associateBy { it.number }
        val total = totalEpisodes.coerceAtLeast(known.keys.maxOrNull() ?: 0)
        if (total <= 0) return known.values.sortedBy { it.number }
        val slug = anime.detailUrl.substringAfter("/anime/").substringBefore("/")
            .ifBlank { anime.id.removePrefix(ID_PREFIX).substringBefore("/") }
        return (1..total).map { number ->
            known[number] ?: Episode(
                id = "reanime:$slug:ep-$number",
                number = number,
                displayNumber = number.toString(),
                title = "Episode $number",
                playable = anime.source == "reanime",
                videoUrl = "${ReAnimeHarClient.BASE_URL}/watch/$slug?ep=$number"
            )
        }
    }

    private fun episodeFromTable(table: RefTable, item: JSONObject, slug: String): Episode? {
        val number = table.intRef(item, "number") ?: table.intRef(item, "episode_number") ?: 0
        if (number <= 0) return null
        val title = table.stringRef(item, "title") ?: "Episode $number"
        val aired = table.stringRef(item, "aired") ?: ""
        val id = table.stringRef(item, "episodeId") ?: "ep-$number"
        return Episode(
            id = "reanime:$slug:$id",
            number = number,
            displayNumber = number.toString(),
            title = title,
            airedDate = aired,
            isFiller = table.boolRef(item, "is_filler") ?: table.boolRef(item, "filler") ?: false,
            isRecap = table.boolRef(item, "is_recap") ?: table.boolRef(item, "recap") ?: false,
            playable = table.boolRef(item, "playable") ?: true,
            subbed = table.boolRef(item, "subbed") ?: false,
            dubbed = table.boolRef(item, "dubbed") ?: false,
            videoUrl = "${ReAnimeHarClient.BASE_URL}/watch/$slug?ep=$number"
        )
    }

    private fun table(json: String): RefTable? = runCatching<RefTable?> {
        val root = JSONObject(json)
        val nodes = root.optJSONArray("nodes") ?: return null
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val data = node.optJSONArray("data") ?: continue
            if (data.length() > 200) return RefTable(data)
        }
        null
    }.getOrNull()

    private class RefTable(private val data: JSONArray) {
        fun raw(index: Int): Any? = if (index in 0 until data.length()) data.opt(index) else null
        fun obj(index: Int): JSONObject? = raw(index) as? JSONObject
        fun resolveRef(value: Any?): Any? =
            if (value is Number && value.toInt() in 0 until data.length()) raw(value.toInt()) else value

        fun objRef(obj: JSONObject?, key: String): JSONObject? =
            obj?.opt(key)?.let { resolveRef(it) as? JSONObject }

        fun arrayRef(obj: JSONObject?, key: String): List<Int> =
            (obj?.opt(key)?.let(::resolveRef) as? JSONArray)?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optInt(it, -1).takeIf { n -> n >= 0 } }
            } ?: emptyList()

        fun stringRef(obj: JSONObject?, key: String): String? =
            obj?.opt(key)?.let(::resolveRef) as? String

        fun intRef(obj: JSONObject?, key: String): Int? =
            obj?.opt(key)?.let(::resolveRef).let { value ->
                when (value) {
                    is Number -> value.toInt()
                    is String -> value.toIntOrNull()
                    else -> null
                }
            }

        fun boolRef(obj: JSONObject?, key: String): Boolean? =
            obj?.opt(key)?.let(::resolveRef) as? Boolean

        fun stringArrayRef(obj: JSONObject?, key: String): List<String> {
            val arr = obj?.opt(key)?.let(::resolveRef) as? JSONArray ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optInt(i, -1).takeIf { it >= 0 }?.let { raw(it) as? String }
                    ?: arr.optString(i).takeIf { it.isNotBlank() }
            }
        }

        fun objectArrayRef(obj: JSONObject?, key: String): List<JSONObject> {
            val arr = obj?.opt(key)?.let(::resolveRef) as? JSONArray ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optInt(i, -1).takeIf { it >= 0 }?.let { raw(it) as? JSONObject }
                    ?: arr.opt(i) as? JSONObject
            }
        }

        fun dateRef(obj: JSONObject?, key: String): String? {
            val date = objRef(obj, key) ?: return null
            val day = intRef(date, "day") ?: return null
            val month = intRef(date, "month") ?: return null
            val year = intRef(date, "year") ?: return null
            if (year <= 0) return null
            return "%04d-%02d-%02d".format(year, month, day)
        }
    }
}
