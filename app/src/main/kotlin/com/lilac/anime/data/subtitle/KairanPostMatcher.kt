package com.lilac.anime

import java.util.Locale
import com.lilac.anime.data.matcher.HangulSimilarityMatcher
import android.util.Log
import com.lilac.anime.data.subtitle.KairanTitleNormalizer

data class KairanPost(val title: String, val url: String)
data class KairanMatch(val post: KairanPost, val similarity: Double)

object KairanPostMatcher {
    private const val MIN_SIMILARITY = 0.52

    fun findBestMatch(animeTitle: String, episodeNumber: Int, posts: List<KairanPost>, episodeKey: String = episodeNumber.toString()): KairanMatch? {
        if (animeTitle.isBlank() || episodeNumber <= 0) return null
        val normalizedAnimeTitle = KairanTitleNormalizer.normalize(animeTitle)
        Log.d("KairanMatcher", "QUERY_NORMALIZED original=[$animeTitle] normalized=[$normalizedAnimeTitle] posts=${posts.size}")

        val candidates = posts.asSequence()
            .filter {
                episodeMatch(it.title, it.url, episodeNumber, episodeKey) &&
                    seasonCompatible(animeTitle, it.title, it.url)
            }
            .map { post ->
                val candidateTitle = removeEpisodeTokens(post.title, episodeNumber)
                val normalizedCandidate = KairanTitleNormalizer.normalize(candidateTitle)
                val score = weightedSimilarity(normalizedAnimeTitle, normalizedCandidate)
                Log.d("KairanMatcher", "COMPARE query=[$normalizedAnimeTitle] candidate=[${post.title}] normalized=[$normalizedCandidate] score=$score")
                KairanMatch(post, score)
            }
            .toList()

        val strictMatch = candidates.maxByOrNull { it.similarity }
            ?.takeIf { it.similarity >= MIN_SIMILARITY }
        if (strictMatch != null) return strictMatch

        // 일부 작품(특히 극장판/단편)은 Blogger 제목에 회차 번호가 없다.
        // 앱에서는 이런 작품도 Episode 1로 표현될 수 있으므로,
        // 회차 토큰을 요구하지 않는 '정확한 제목 근접 매칭'을 두 번째 단계로 사용한다.
        if (episodeNumber == 1) {
            val titleOnly = posts.asSequence()
                .map { post ->
                    val candidateTitle = removeEpisodeTokens(post.title, episodeNumber)
                    val normalizedCandidate = normalizePostTitleForTitleOnly(candidateTitle)
                    val score = weightedSimilarity(normalizedAnimeTitle, normalizedCandidate)
                    Log.d(
                        "KairanMatcher",
                        "TITLE_ONLY_COMPARE query=[$normalizedAnimeTitle] candidate=[${post.title}] normalized=[$normalizedCandidate] score=$score"
                    )
                    KairanMatch(post, score)
                }
                .filter { match ->
                    seasonCompatible(animeTitle, match.post.title, match.post.url)
                }
                .filter { match ->
                    val normalizedCandidate = normalizePostTitleForTitleOnly(match.post.title)
                    val exactLike = normalizedCandidate == normalizedAnimeTitle ||
                        normalizedCandidate.contains(normalizedAnimeTitle) ||
                        normalizedAnimeTitle.contains(normalizedCandidate)
                    exactLike || match.similarity >= 0.78
                }
                .maxByOrNull { it.similarity }

            if (titleOnly != null) {
                Log.d(
                    "KairanMatcher",
                    "TITLE_ONLY_MATCH title=[${titleOnly.post.title}] similarity=${titleOnly.similarity}"
                )
                return titleOnly
            }
        }

        return null
    }

    private fun normalizePostTitleForTitleOnly(title: String): String {
        var value = title
            .replace(Regex("\\b(?:한글\\s*)?자막\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(?:한국어\\s*)?자막\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(?:subtitle|sub)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("[\\[\\]【】()（）{}<>〈〉:：|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Blogger/커뮤니티에서 '카구야'와 '가구야'가 혼용되는 경우가 있어
        // 제목 매칭 단계에서만 두 표기를 같은 후보군으로 취급한다.
        value = value.replace("카구야", "가구야")
        return KairanTitleNormalizer.normalize(value)
    }

    /**
     * Re:Zero and similar titles reuse the same base title across seasons.
     * The old normalizer discarded digits, so a 4th-season query could match a
     * 1st-season post. When the query or candidate explicitly declares a season,
     * require the same season number.
     */
    private fun seasonCompatible(queryTitle: String, candidateTitle: String, candidateUrl: String): Boolean {
        val querySeason = extractSeasonNumber(queryTitle)
        if (querySeason == null) return true

        val candidateSeason = extractSeasonNumber(candidateTitle)
            ?: extractSeasonNumber(candidateUrl)

        // If the candidate explicitly declares another season, it is never a match.
        if (candidateSeason != null && candidateSeason != querySeason) return false

        // Prefer an explicitly season-tagged candidate. Candidates without a season
        // marker are allowed because some Blogger posts omit it from the title.
        return true
    }

    private fun extractSeasonNumber(value: String): Int? {
        val text = value.lowercase(Locale.ROOT)
        val patterns = listOf(
            Regex("""(?<!\d)(\d{1,2})\s*(?:기|期|시즌|season)(?![a-z])"""),
            Regex("""(?<![a-z])(\d{1,2})(?:st|nd|rd|th)\s+season(?![a-z])""", RegexOption.IGNORE_CASE),
            Regex("""(?:season|s)\s*(\d{1,2})(?!\d)""", RegexOption.IGNORE_CASE)
        )
        return patterns.asSequence()
            .mapNotNull { it.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .firstOrNull()
    }

    fun filterNoise(input: String): String = HangulSimilarityMatcher.filterNoise(input)

    fun weightedEditDistance(first: String, second: String): Double =
        HangulSimilarityMatcher.weightedEditDistance(first, second)

    fun weightedSimilarity(first: String, second: String): Double =
        HangulSimilarityMatcher.similarity(first, second)

    // 기존 서비스의 엄격한 회차 판정을 유지한다. 제목에서 회차를 제거한 뒤 유사도를 계산한다.
    fun episodeMatch(postTitle: String, url: String, episode: Int, episodeKey: String = episode.toString()): Boolean {
        val key = episodeKey.lowercase(Locale.ROOT)
        if (key != episode.toString()) {
            val escaped = Regex.escape(key)
            val labelPatterns = listOf(
                Regex("(?:^|\\s|[\\[\\]()._-])0*$escaped\\s*(?:화|회|편|話)?(?=$|\\s|[\\[\\]()._-])", RegexOption.IGNORE_CASE),
                Regex("(?:^|\\s|[\\[\\]()._-])(?:ep|e|episode|#)\\s*0*$escaped(?=$|\\s|[\\[\\]()._-])", RegexOption.IGNORE_CASE)
            )
            if (labelPatterns.any { it.containsMatchIn(postTitle.lowercase(Locale.ROOT)) }) return true
            if (Regex("(?:-|_)0*${episode}a\\.html(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(url.lowercase(Locale.ROOT)) && key == "${episode}a") return true
            return false
        }
        return episodeMatchNumeric(postTitle, url, episode)
    }


    private fun episodeMatchNumeric(postTitle: String, url: String, episode: Int): Boolean {
        val title = postTitle.lowercase(Locale.ROOT)
        val ep = episode.toString()
        val explicit = listOf(
            Regex("(?:^|\\s|[\\[\\]()._-])0*$ep(?:\\s*(?:화|회|편|話))(?=$|\\s|[\\[\\]()._-])", RegexOption.IGNORE_CASE),
            Regex("(?:^|\\s|[\\[\\]()._-])(?:ep|e|episode|#)\\s*0*$ep(?=$|\\s|[\\[\\]()._-])", RegexOption.IGNORE_CASE),
            Regex("(?:^|\\s|[\\[\\]()._-])0*$ep(?=$|\\s|[\\[\\]()._-])", RegexOption.IGNORE_CASE)
        )
        if (explicit.any { it.containsMatchIn(title) }) return true
        return Regex("(?:-|_)0*$ep\\.html(?:$|[?#])", RegexOption.IGNORE_CASE)
            .containsMatchIn(url.lowercase(Locale.ROOT))
    }

    private fun removeEpisodeTokens(title: String, episode: Int): String {
        val ep = episode.toString()
        return title
            .replace(Regex("(?:ep|episode|e)\\s*0*$ep", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("0*$ep\\s*(?:화|회|편|話)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?<!\\d)0*$ep(?!\\d)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
