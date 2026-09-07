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

                // 목록 카드에 함께 노출되는 장르/태그를 바로 읽는다.
                // 상세 페이지를 작품마다 다시 요청하지 않아도 목록 단계에서
                // genres를 채울 수 있도록 여러 Linkkf 마크업 형태를 허용한다.
                val genres = extractListGenres(item)

                Anime(
                    id = extractAnimeId(detailUrl),
                    title = title,
                    description = "",
                    poster = poster,
                    backdrop = backdrop,
                    genres = genres,
                    episodes = emptyList(),
                    detailUrl = detailUrl
                )
            }
            .distinctBy { it.id }
    }

    // =========================================================
    // 목록 카드의 장르/태그
    // =========================================================

    private fun extractListGenres(
        item: org.jsoup.nodes.Element
    ): List<String> {
        val selectors = listOf(
            ".genres a",
            ".genre a",
            ".tags a",
            ".tag a",
            ".vod-item-genre a",
            ".vod-item-genres a",
            ".vod-item-tag a",
            ".vod-item-tags a",
            "[class*='genre'] a",
            "[class*='tag'] a",
            "a[href*='/genre/']"
        )

        return selectors
            .asSequence()
            .flatMap { selector -> item.select(selector).asSequence() }
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .filterNot {
                it.equals("Watch Now", ignoreCase = true) ||
                    it.equals("더보기", ignoreCase = true) ||
                    it.equals("보기", ignoreCase = true)
            }
            .distinct()
            .toList()
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

        val genres = extractDetailGenres(document)

        return original.copy(
            title = title,
            description = description,
            genres = genres
        )
    }

    /**
     * Linkkf 목록 카드에는 장르가 없는 경우가 많아서 상세 문서에서
     * 장르/태그를 추출할 때도 이 함수를 공용으로 사용한다.
     */
    fun extractDetailGenres(document: Document): List<String> {
        return document
            .select(".detail-info-desc li")
            .firstOrNull { it.text().contains("장르") }
            ?.select("a")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: document.select("a[href*='/genre/'], .genres a, .genre a, [class*='genre'] a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
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