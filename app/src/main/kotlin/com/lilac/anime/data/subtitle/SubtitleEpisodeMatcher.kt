package com.lilac.anime.data.subtitle

import java.util.Locale

/**
 * Single source of truth for subtitle filename episode matching.
 *
 * Important rule: S01E05 is episode 5, never episode 1 just because the
 * filename also contains the number 01 (the season number).
 */
object SubtitleEpisodeMatcher {
    data class Parsed(
        val season: Int? = null,
        val episode: Int? = null,
        val endSeason: Int? = null,
        val endEpisode: Int? = null
    )

    private val seasonEpisode = Regex(
        "(?:^|[^a-z0-9])s(\\d{1,2})e(\\d{1,3})(?:$|[^a-z0-9])",
        RegexOption.IGNORE_CASE
    )
    private val seasonEpisodeRange = Regex(
        "(?:^|[^a-z0-9])s(\\d{1,2})e(\\d{1,3})\\s*[-~〜–—]\\s*(?:s(\\d{1,2})e)?(\\d{1,3})(?:$|[^a-z0-9])",
        RegexOption.IGNORE_CASE
    )
    private val markedEpisode = Regex(
        "(?:^|[^a-z0-9])(?:episode|ep|e)\\s*0*(\\d{1,3})(?:$|[^a-z0-9])",
        RegexOption.IGNORE_CASE
    )
    private val markedRange = Regex(
        "(?:^|[^a-z0-9])(?:episode|ep|e)?\\s*0*(\\d{1,3})\\s*[-~〜–—]\\s*(?:episode|ep|e)?\\s*0*(\\d{1,3})(?:$|[^0-9])",
        RegexOption.IGNORE_CASE
    )
    private val koreanEpisode = Regex(
        "(?:^|[^0-9])0*(\\d{1,3})\\s*(?:화|회|편|話)(?:$|[^0-9])"
    )
    private val standaloneNumber = Regex(
        "(?:^|[^a-z0-9])0*(\\d{1,3})(?:$|[^a-z0-9])",
        RegexOption.IGNORE_CASE
    )

    fun parse(name: String): Parsed? {
        val normalized = name.lowercase(Locale.ROOT)
        seasonEpisodeRange.find(normalized)?.let { m ->
            return Parsed(
                season = m.groupValues[1].toIntOrNull(),
                episode = m.groupValues[2].toIntOrNull(),
                endSeason = m.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull()
                    ?: m.groupValues[1].toIntOrNull(),
                endEpisode = m.groupValues[4].toIntOrNull()
            )
        }
        seasonEpisode.find(normalized)?.let { m ->
            return Parsed(
                season = m.groupValues[1].toIntOrNull(),
                episode = m.groupValues[2].toIntOrNull()
            )
        }
        markedRange.find(normalized)?.let { m ->
            return Parsed(
                episode = m.groupValues[1].toIntOrNull(),
                endEpisode = m.groupValues[2].toIntOrNull()
            )
        }
        markedEpisode.find(normalized)?.let { m ->
            return Parsed(episode = m.groupValues[1].toIntOrNull())
        }
        koreanEpisode.find(normalized)?.let { m ->
            return Parsed(episode = m.groupValues[1].toIntOrNull())
        }
        return null
    }

    fun range(name: String): IntRange? {
        val parsed = parse(name) ?: return null
        val start = parsed.episode ?: return null
        val end = parsed.endEpisode ?: return null
        if (end < start) return null
        return start..end
    }

    fun matches(name: String, episodeNumber: Int, expectedSeason: Int? = null): Boolean {
        if (episodeNumber <= 0) return false
        val normalized = name.lowercase(Locale.ROOT)

        // Season+episode notation is authoritative. Never fall through to a
        // standalone number, because the season number must not become the episode.
        seasonEpisodeRange.find(normalized)?.let { m ->
            val startSeason = m.groupValues[1].toIntOrNull()
            val startEpisode = m.groupValues[2].toIntOrNull()
            val endSeason = m.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull() ?: startSeason
            val endEpisode = m.groupValues[4].toIntOrNull()
            if (expectedSeason != null && startSeason != expectedSeason) return false
            if (expectedSeason != null && endSeason != null && endSeason != expectedSeason) return false
            return startEpisode != null && endEpisode != null && episodeNumber in startEpisode..endEpisode
        }
        seasonEpisode.find(normalized)?.let { m ->
            val season = m.groupValues[1].toIntOrNull()
            val episode = m.groupValues[2].toIntOrNull()
            if (expectedSeason != null && season != expectedSeason) return false
            return episode == episodeNumber
        }

        markedRange.find(normalized)?.let { m ->
            val start = m.groupValues[1].toIntOrNull()
            val end = m.groupValues[2].toIntOrNull()
            return start != null && end != null && episodeNumber in start..end
        }
        markedEpisode.find(normalized)?.let { m ->
            return m.groupValues[1].toIntOrNull() == episodeNumber
        }
        koreanEpisode.find(normalized)?.let { m ->
            return m.groupValues[1].toIntOrNull() == episodeNumber
        }

        // A plain numeric token is accepted only if there is no season/episode
        // token anywhere in the filename. This prevents S01E05 -> episode 1.
        if (seasonEpisode.containsMatchIn(normalized) || markedEpisode.containsMatchIn(normalized)) {
            return false
        }
        return standaloneNumber.findAll(normalized).mapNotNull { it.groupValues[1].toIntOrNull() }
            .any { it == episodeNumber }
    }

    fun score(name: String, episodeNumber: Int): Int {
        if (episodeNumber <= 0) return 0
        val normalized = name.lowercase(Locale.ROOT)
        seasonEpisodeRange.find(normalized)?.let { m ->
            val start = m.groupValues[2].toIntOrNull()
            val end = m.groupValues[4].toIntOrNull()
            if (start != null && end != null && episodeNumber in start..end) return 45
            return 0
        }
        seasonEpisode.find(normalized)?.let { m ->
            return if (m.groupValues[2].toIntOrNull() == episodeNumber) 70 else 0
        }
        markedRange.find(normalized)?.let { m ->
            val start = m.groupValues[1].toIntOrNull()
            val end = m.groupValues[2].toIntOrNull()
            return if (start != null && end != null && episodeNumber in start..end) 45 else 0
        }
        markedEpisode.find(normalized)?.let { m ->
            return if (m.groupValues[1].toIntOrNull() == episodeNumber) 60 else 0
        }
        koreanEpisode.find(normalized)?.let { m ->
            return if (m.groupValues[1].toIntOrNull() == episodeNumber) 55 else 0
        }
        if (seasonEpisode.containsMatchIn(normalized) || markedEpisode.containsMatchIn(normalized)) return 0
        return if (standaloneNumber.findAll(normalized).any { it.groupValues[1].toIntOrNull() == episodeNumber }) 50 else 0
    }
}
