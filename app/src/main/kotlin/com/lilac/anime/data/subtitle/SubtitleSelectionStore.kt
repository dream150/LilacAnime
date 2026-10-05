package com.lilac.anime.data.subtitle

import android.content.Context
import java.util.Locale

/**
 * Stores the subtitle choices selected for an anime, independent of episode.
 * Episode-specific paths/URLs are never persisted here; only a stable choice
 * signature is stored so the same fansub/track can be matched on the next episode.
 */
object SubtitleSelectionStore {
    private const val PREF = "lilac_subtitle_selection_profile"
    private const val KEY_PREFIX = "profile_"

    fun signature(choice: SubtitleDiscoveryService.Choice): String {
        val source = choice.source.name.lowercase(Locale.ROOT)
        return when (choice.source) {
            SubtitleDiscoveryService.Source.JIMAKU -> {
                val raw = choice.jimaku?.name ?: choice.title
                "$source|${normalizeJimakuName(raw)}"
            }
            SubtitleDiscoveryService.Source.REANIME -> {
                "$source|${normalize(choice.language)}|${normalize(choice.title)}"
            }
            else -> "$source|${normalize(choice.language)}"
        }
    }

    fun save(context: Context, animeId: String, choices: Collection<SubtitleDiscoveryService.Choice>) {
        val signatures = choices.map(::signature).filter { it.isNotBlank() }.toSet()
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PREFIX + animeId, signatures)
            .apply()
    }

    fun load(context: Context, animeId: String): Set<String> =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_PREFIX + animeId, emptySet())
            .orEmpty()

    fun clear(context: Context, animeId: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(KEY_PREFIX + animeId).apply()
    }

    fun matches(choice: SubtitleDiscoveryService.Choice, signatures: Set<String>): Boolean =
        signature(choice) in signatures

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("\\.[a-z0-9]{2,5}$"), "")
        .replace(Regex("[\\[\\]{}()]+"), " ")
        .replace(Regex("(?:episode|ep|e|\\#)\\s*0*\\d{1,4}"), " ")
        .replace(Regex("(?:第\\s*)?0*\\d{1,4}\\s*(?:화|회|편|話)"), " ")
        // Only remove standalone episode numbers. Keep numbers that are part
        // of a title/quality token (for example 1080p or a season number).
        .replace(Regex("\\b0*\\d{1,4}\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun normalizeJimakuName(value: String): String {
        val normalized = normalize(value)
        return normalized
            .replace(Regex("\\b(?:complete|batch|multi|all)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
