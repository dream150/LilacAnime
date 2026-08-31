package com.lilac.anime.network

import com.lilac.anime.data.matcher.HangulSimilarityMatcher

/** Scores a query against every title known for a MAL candidate. */
object MalAnimeMatcher {
    fun bestScore(query: String, titles: Iterable<String>): Int =
        titles.filter { it.isNotBlank() }
            .maxOfOrNull { HangulSimilarityMatcher.score(query, it) }
            ?: 0
}
