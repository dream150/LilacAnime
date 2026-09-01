package com.lilac.anime.data

import android.util.Log
import com.lilac.anime.Anime
import com.lilac.anime.Episode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

/** Parser for the independent Animenosub catalog and episode pages. */
object AnimenosubParser {
    private const val BASE_URL = "https://animenosub.to"
    private const val ID_PREFIX = "animenosub:"

    fun parseAnimeList(document: Document): List<Anime> {
        val result = linkedMapOf<String, Anime>()

        // The current homepage/list pages often link to the *latest episode*
        // rather than the /anime/... detail page. Accept both forms and derive
        // the series detail URL from an episode URL when necessary.
        document.select("a[href]").forEach { link ->
            val href = link.absUrl("href").trim()
            if (!isAnimenosubUrl(href)) return@forEach

            val isSeries = isSeriesUrl(href)
            val isEpisode = isEpisodeUrl(href)
            if (!isSeries && !isEpisode) return@forEach

            val container = findCardContainer(link)
            val image = sequenceOf(
                link.selectFirst("img"),
                container?.selectFirst("img")
            ).filterNotNull().firstOrNull()
            val poster = image?.let { imageUrl(it) }.orEmpty()
                .ifBlank { extractBackgroundImage(link) }
                .ifBlank { container?.let { extractBackgroundImage(it) }.orEmpty() }

            if (poster.isBlank()) {
                Log.d("AnimenosubParser", "LIST_IMAGE_EMPTY href=$href title=${link.text().trim()}")
            } else {
                Log.d("AnimenosubParser", "LIST_IMAGE href=$href image=$poster")
            }

            val title = when {
                isSeries -> findCardTitle(link).ifBlank { titleFromSlug(href) }
                else -> {
                    val fromImage = image?.attr("alt")?.trim().orEmpty()
                    val fromCard = findCardTitle(link)
                    val fromSlug = titleFromEpisodeSlug(href)
                    listOf(fromImage, stripEpisodeSuffix(fromCard), fromSlug)
                        .firstOrNull { it.isNotBlank() && !it.equals("Watch Now", true) }
                        .orEmpty()
                }
            }
            if (title.isBlank()) return@forEach

            val detailUrl = if (isSeries) href else episodeToAnimeUrl(href)
            val id = ID_PREFIX + extractSlug(detailUrl)
            if (result.containsKey(id)) {
                // Prefer an entry that has a real poster/title from the card.
                val existing = result.getValue(id)
                if (existing.poster.isBlank() && poster.isNotBlank()) {
                    result[id] = existing.copy(poster = poster, backdrop = poster)
                }
                return@forEach
            }

            result[id] = Anime(
                id = id,
                title = title,
                poster = poster,
                backdrop = poster,
                genres = emptyList(),
                description = "",
                detailUrl = detailUrl
            )
        }

        return result.values.toList()
    }

    fun parseAnimeDetail(document: Document, original: Anime): Anime {
        val title = sequenceOf(
            document.selectFirst("h1"),
            document.selectFirst(".film-name, .film-title, .anime-title, .post-title, .entry-title")
        ).map { it?.text()?.trim().orEmpty() }
            .firstOrNull { it.isNotBlank() }
            ?.let(::stripEpisodeSuffix)
            ?.takeIf { it.isNotBlank() }
            ?: original.title

        val synopsisHeading = document.select("h2, h3, h4, strong").firstOrNull { it.text().contains("Synopsis", true) }
        val description = synopsisHeading?.nextElementSibling()?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".description, .desc, .synopsis, .film-description, .film-description-content, .summary")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:description'], meta[name='description']")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: original.description

        val genres = document.select("a[href], .genres a, .genre a, [class*='genre'] a")
            .filter { it.attr("href").contains("/genre/", true) || it.parents().any { p -> p.classNames().any { c -> c.contains("genre", true) } } }
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // 상세 페이지의 큰 대표 이미지는 실제 페이지 구조인
        // <div class="bigcover"><img src="..."></div> 를 사용한다.
        // thumb는 작은 썸네일이므로 상세 화면의 poster에는 사용하지 않는다.
        val bigCoverImage = document
            .selectFirst("div.bigcover img[src]")
            ?.let(::imageUrl)
            .orEmpty()

        val thumbImage = document
            .selectFirst("div.thumb img[src]")
            ?.let(::imageUrl)
            .orEmpty()

        val bigCover = normalizeUrl(bigCoverImage, document.baseUri())
        val thumb = normalizeUrl(thumbImage, document.baseUri())

        // DetailScreen uses backdrop for the large hero image and poster for
        // the smaller portrait card. Animenosub exposes both explicitly.
        val backdrop = bigCover.takeIf { it.isNotBlank() } ?: original.backdrop
        val poster = thumb.takeIf { it.isNotBlank() }
            ?: bigCover.takeIf { it.isNotBlank() }
            ?: original.poster

        Log.d(
            "AnimenosubParser",
            "DETAIL_IMAGES bigcover=$bigCover thumb=$thumb poster=$poster backdrop=$backdrop"
        )
        Log.d("AnimenosubParser", "DETAIL title=$title poster=$poster genres=${genres.size} descriptionLength=${description.length}")
        val parsedEpisodes = parseEpisodes(document, original.copy(title = title))
        val parsedDubEpisodes = parseDubEpisodes(document, original.copy(title = title))
        return original.copy(
            title = title,
            description = description,
            genres = genres,
            poster = poster,
            backdrop = backdrop,
            episodes = parsedEpisodes,
            dubEpisodes = parsedDubEpisodes
        )
    }

    fun parseEpisodes(document: Document, anime: Anime): List<Episode> =
        parseEpisodeLinks(document, anime, dub = false)

    fun parseDubEpisodes(document: Document, anime: Anime): List<Episode> =
        parseEpisodeLinks(document, anime, dub = true)

    private fun parseEpisodeLinks(document: Document, anime: Anime, dub: Boolean): List<Episode> {
        val result = linkedMapOf<String, Episode>()
        document.select("a[href]").forEach { link ->
            val href = link.absUrl("href").trim()
            if (!href.startsWith(BASE_URL + "/") || !href.contains("-episode-")) return@forEach

            val text = link.text().trim()
            val haystack = "$text $href".lowercase(Locale.ROOT)
            val isDub = haystack.contains(" dub") || haystack.contains("-dub") || haystack.contains("english dub") || haystack.contains("dubbed")
            if (dub != isDub) return@forEach

            val match = Regex("episode-(\\d+)([a-z]?)", RegexOption.IGNORE_CASE).find(href)
                ?: Regex("(?:episode|eps)\\s*(\\d+)([a-z]?)", RegexOption.IGNORE_CASE).find(text)
                ?: return@forEach
            val number = match.groupValues[1].toIntOrNull() ?: return@forEach
            val suffix = match.groupValues.getOrNull(2).orEmpty().lowercase(Locale.ROOT)
            val display = "$number$suffix"
            val id = "${anime.id}_${if (dub) "dub_" else ""}ep_${display}"
            result.putIfAbsent(
                id,
                Episode(
                    id = id,
                    number = number,
                    title = text.ifBlank { "${display}화${if (dub) " (더빙)" else ""}" },
                    description = "${anime.title} ${display}화${if (dub) " (더빙)" else ""}",
                    videoUrl = href,
                    displayNumber = display
                )
            )
        }

        return result.values.sortedWith(
            compareBy<Episode> { it.number }.thenBy { it.displayNumber }
        )
    }

    private fun isAnimenosubUrl(url: String): Boolean =
        url.startsWith(BASE_URL + "/") || url.startsWith(BASE_URL + "?")

    private fun isSeriesUrl(url: String): Boolean {
        if (!url.startsWith(BASE_URL + "/anime/")) return false
        return extractSlug(url).isNotBlank()
    }

    private fun isEpisodeUrl(url: String): Boolean {
        if (!isAnimenosubUrl(url) || isSeriesUrl(url)) return false
        return Regex("-episode-\\d+[a-z]?(?:-dub)?/?$", RegexOption.IGNORE_CASE)
            .containsMatchIn(url.trimEnd('/'))
    }

    private fun episodeToAnimeUrl(url: String): String {
        val slug = extractSlug(url)
            .replace(Regex("-episode-\\d+[a-z]?(?:-dub)?$", RegexOption.IGNORE_CASE), "")
            .trim('-')
        return if (slug.isNotBlank()) "$BASE_URL/anime/$slug/" else url
    }

    /** Convert an Animenosub URL slug into a readable fallback title. */
    private fun titleFromSlug(url: String): String {
        val slug = extractSlug(url)
            .replace(Regex("-(?:season|cour|part)-?\\d+[a-z]?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[-_]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (slug.isBlank()) return ""

        return slug.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                when {
                    word.length <= 3 && word.all { it.isLetter() } -> word.uppercase(Locale.ROOT)
                    else -> word.replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) }
                }
            }
    }

    private fun titleFromEpisodeSlug(url: String): String =
        titleFromSlug(episodeToAnimeUrl(url))

    private fun stripEpisodeSuffix(value: String): String =
        value.replace(
            Regex("\\s+episode\\s+\\d+[a-z]?(?:\\s+\\(?(?:dub|dubbed)\\)?)?.*$", RegexOption.IGNORE_CASE),
            ""
        ).trim()

    private fun extractSlug(url: String): String =
        url.trimEnd('/').substringAfterLast('/').lowercase(Locale.ROOT)

    private fun findCardTitle(link: Element): String {
        val container = findCardContainer(link)
        val heading = container?.selectFirst("h1, h2, h3, h4, .title, .film-name, .post-title")
        val headingText = heading?.text()?.trim().orEmpty()
        if (headingText.isNotBlank() && !headingText.equals("Watch Now", true)) return headingText

        val ownText = link.text().trim()
        if (ownText.isNotBlank() && !ownText.equals("Watch Now", true)) return ownText
        return ""
    }

    private fun findCardContainer(link: Element): Element? {
        return link.parents().firstOrNull { parent ->
            parent.select("h1, h2, h3, h4").isNotEmpty() &&
                parent.select("a[href*='/anime/']").size <= 6
        } ?: link.parent()
    }

    private fun imageUrl(image: Element): String {
        val value = sequenceOf(
            "data-original", "data-src", "data-lazy-src", "data-lazy",
            "data-fallback-src", "data-image", "src"
        ).map { image.attr(it).trim() }.firstOrNull { it.isNotBlank() }
            ?: sequenceOf("data-srcset", "data-lazy-srcset", "srcset")
                .map { image.attr(it).trim() }
                .firstOrNull { it.isNotBlank() }
                ?.split(',')
                ?.maxByOrNull { candidate -> candidate.trim().substringBefore(' ').length }
                ?.trim()?.substringBefore(' ')
                .orEmpty()
        return normalizeUrl(value, image.baseUri())
    }

    private fun extractBackgroundImage(element: Element): String {
        val raw = sequenceOf(
            element.attr("data-bg"),
            element.attr("data-background"),
            element.attr("data-background-image"),
            element.attr("style")
        ).firstOrNull { it.contains("url(", ignoreCase = true) } ?: return ""

        val match = Regex("url\\((?:[\"']?)(.*?)(?:[\"']?)\\)", RegexOption.IGNORE_CASE).find(raw)
            ?: return ""
        return normalizeUrl(match.groupValues[1].trim(), element.baseUri())
    }

    private fun normalizeUrl(value: String, base: String = BASE_URL + "/"): String {
        if (value.isBlank()) return ""
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> BASE_URL + value
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            else -> try {
                java.net.URI(base).resolve(value).toString()
            } catch (_: Exception) {
                value
            }
        }
    }
}
