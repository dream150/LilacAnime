package com.lilac.anime.data

import com.lilac.anime.Anime
import com.lilac.anime.Episode
import org.jsoup.nodes.Document
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log


object LinkkfParser {

    private const val BASE_URL = "https://linkkf.tv"

    // =========================================================
    // 애니 목록
    // =========================================================

    fun parseAnimeList(
        document: Document
    ): List<Anime> {

        return document
            .select("div.vod-item")
            .mapNotNull { item ->

                val titleLink =
                    item.selectFirst("h3.vod-item-title a")
                        ?: return@mapNotNull null

                val title =
                    titleLink.text().trim()

                val detailUrl =
                    titleLink.absUrl("href").trim()

                if (title.isBlank() || detailUrl.isBlank()) {
                    return@mapNotNull null
                }

                // =================================================
                // 이미지
                //
                // 실제 구조:
                //
                // data-original=
                // https://rez1.imgdarr.top/370x/https://k2.1imgdarr.top/...
                //
                // poster   -> 리사이즈 이미지
                // backdrop -> 원본 이미지
                // =================================================

                val imageWrapper =
                    item.selectFirst(".img-wrapper")

                val imageUrl =
                    imageWrapper
                        ?.attr("data-original")
                        ?.trim()
                        .orEmpty()

                val poster =
                    normalizeImageUrl(imageUrl)

                val backdrop =
                    extractOriginalImageUrl(imageUrl)

                Anime(
                    id = extractAnimeId(detailUrl),
                    title = title,
                    description = "",
                    poster = poster,
                    backdrop = backdrop,
                    genres = emptyList(),
                    episodes = emptyList(),
                    detailUrl = detailUrl
                )
            }
            .distinctBy { it.id }
    }

    /**
     * Returns the highest Linkkf catalog page advertised by the current list page.
     * We prefer the site's own pagination instead of assuming a fixed page count.
     */
    fun parseLastCatalogPage(document: Document): Int {
        var lastPage = 1

        // Linkkf has used several pagination markups over time. Inspect the
        // pagination container first, then fall back to every anchor on the page.
        val paginationLinks = document.select(
            "nav.pagination a[href], .pagination a[href], ul.page-numbers a[href], " +
                "a.page-numbers[href], a[rel=last][href], a[href*='/list/2/page/']"
        )

        fun updateFromLink(link: org.jsoup.nodes.Element) {
            val href = link.absUrl("href").ifBlank { link.attr("href") }.trim()

            val patterns = listOf(
                Regex("/list/2/page/(\\d+)(?:/)?(?:\\?.*)?$"),
                Regex("/list/2/page/(\\d+)(?:/|\\?.*)"),
                Regex("(?:[?&]page=)(\\d+)(?:&|$)")
            )

            for (pattern in patterns) {
                val page = pattern.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (page != null) {
                    lastPage = maxOf(lastPage, page)
                    return
                }
            }

            // Some themes render the last page as plain numeric text with a
            // relative URL. Use the visible number only when the URL is clearly
            // a catalog pagination link.
            if (href.contains("/list/2/")) {
                link.text().trim().toIntOrNull()?.let {
                    lastPage = maxOf(lastPage, it)
                }
            }
        }

        paginationLinks.forEach(::updateFromLink)

        // If the site's pagination container was omitted from the response, the
        // broad fallback still finds numeric /page/N links.
        if (lastPage == 1) {
            document.select("a[href]").forEach(::updateFromLink)
        }

        Log.d("LinkkfParser", "CATALOG_LAST_PAGE=$lastPage")
        return lastPage
    }
    // =========================================================
    // 이미지 URL 정리
    // =========================================================

    private fun normalizeImageUrl(
        url: String
    ): String {

        if (url.isBlank()) {
            return ""
        }

        return when {
            url.startsWith("http://") ||
            url.startsWith("https://") -> {
                url
            }

            url.startsWith("//") -> {
                "https:$url"
            }

            url.startsWith("/") -> {
                "$BASE_URL$url"
            }

            else -> {
                "$BASE_URL/$url"
            }
        }
    }

    // =========================================================
    // 원본 이미지 URL 추출
    //
    // 예:
    //
    // https://rez1.imgdarr.top/370x/
    // https://k2.1imgdarr.top/anime/196974/xxxxx.webp
    //
    // =>
    //
    // https://k2.1imgdarr.top/anime/196974/xxxxx.webp
    // =========================================================

    private fun extractOriginalImageUrl(
        url: String
    ): String {

        if (url.isBlank()) {
            return ""
        }

        val normalized =
            normalizeImageUrl(url)

        val resizePrefix =
            "/370x/"

        val index =
            normalized.indexOf(resizePrefix)

        if (index >= 0) {
            val original =
                normalized.substring(
                    index + resizePrefix.length
                )

            if (
                original.startsWith("http://") ||
                original.startsWith("https://")
            ) {
                return original
            }
        }

        // 이미 원본 URL인 경우
        return normalized
    }

    // =========================================================
    // 작품 상세
    // =========================================================

    fun parseAnimeDetail(
        document: Document,
        original: Anime
    ): Anime {

        val title =
            document
                .selectFirst(".detail-info-title")
                ?.text()
                ?.trim()
                ?: original.title

        val description =
            document
                .selectFirst(".detail-desc-content")
                ?.text()
                ?.trim()
                ?: original.description

        val genres =
            document
                .select(".detail-info-desc li")
                .firstOrNull {
                    it.text().contains("장르")
                }
                ?.select("a")
                ?.map {
                    it.text().trim()
                }
                ?.filter {
                    it.isNotBlank()
                }
                ?.distinct()
                ?: emptyList()

        return original.copy(
            title = title,
            description = description,
            genres = genres
        )
    }

    // =========================================================
    // 회차 (자막)
    // =========================================================

    fun parseEpisodes(
        document: Document,
        anime: Anime
    ): List<Episode> {

        return document
            .select(
                ".episode-box ul#ewave-playlist-1 a.ep"
            )
            .mapNotNull { link ->

                val pageUrl =
                    link.absUrl("href").trim()

                val rawLabel = link.text().trim()
                val match = Regex("""(\d+)([A-Za-z]+)?""").find(rawLabel)
                val epNum = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                val suffix = match?.groupValues?.getOrNull(2).orEmpty().lowercase()
                val displayNumber = if (epNum != null) epNum.toString() + suffix else ""

                if (
                    pageUrl.isBlank() ||
                    epNum == null ||
                    displayNumber.isBlank()
                ) {
                    null
                } else {
                    Episode(
                        id = "${anime.id}_ep_${displayNumber.lowercase()}",
                        number = epNum,
                        title = "${displayNumber}화",
                        description = "${anime.title} ${displayNumber}화",
                        videoUrl = pageUrl,
                        displayNumber = displayNumber
                    )
                }
            }
            .distinctBy {
                it.videoUrl ?: it.id
            }
            .sortedWith(
                compareByDescending<Episode> { it.number }
                    .thenByDescending { it.displayNumber }
            )
    }

    // =========================================================
    // 회차 (더빙)
    // =========================================================

    fun parseDubEpisodes(
        document: Document,
        anime: Anime
    ): List<Episode> {

        return document
            .select(
                ".episode-box ul#ewave-playlist-2 a.ep"
            )
            .mapNotNull { link ->

                val pageUrl =
                    link.absUrl("href").trim()

                val rawLabel = link.text().trim()
                val match = Regex("""(\d+)([A-Za-z]+)?""").find(rawLabel)
                val epNum = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                val suffix = match?.groupValues?.getOrNull(2).orEmpty().lowercase()
                val displayNumber = if (epNum != null) epNum.toString() + suffix else ""

                if (
                    pageUrl.isBlank() ||
                    epNum == null ||
                    displayNumber.isBlank()
                ) {
                    null
                } else {
                    Episode(
                        id = "${anime.id}_dub_ep_${displayNumber.lowercase()}",
                        number = epNum,
                        title = "${displayNumber}화 (더빙)",
                        description = "${anime.title} ${displayNumber}화 (더빙)",
                        videoUrl = pageUrl,
                        displayNumber = displayNumber
                    )
                }
            }
            .distinctBy {
                it.videoUrl ?: it.id
            }
            .sortedWith(
                compareByDescending<Episode> { it.number }
                    .thenByDescending { it.displayNumber }
            )
    }

    // =========================================================
    // 유틸
    // =========================================================

    private fun extractAnimeId(
        url: String
    ): String {
        return url
            .trimEnd('/')
            .substringAfterLast('/')
    }
}