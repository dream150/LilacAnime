package com.lilac.anime.network

import android.content.Context
import com.lilac.anime.ChapterSkipSegment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the final OP/ED detection result for each offline episode.
 *
 * Unlike OfflineOpEdFingerprintStore, this store contains only the already
 * resolved chapter ranges. Once an episode has been analyzed successfully,
 * later playback can load these ranges without decoding audio again.
 */
object OfflineOpEdResultStore {
    private const val PREFS = "linkkf_oped_results"
    private const val KEY_PREFIX = "anime_"
    private const val VERSION = 1

    fun load(
        context: Context,
        animeId: String,
        episodeId: String
    ): List<ChapterSkipSegment>? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(animeId, episodeId), null) ?: return null
        val root = JSONObject(raw)
        if (root.optInt("version", -1) != VERSION) return null
        val array = root.optJSONArray("segments") ?: return null
        val result = ArrayList<ChapterSkipSegment>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val type = item.optString("type")
            val start = item.optDouble("start", Double.NaN)
            val end = item.optDouble("end", Double.NaN)
            if (type.isNotBlank() && start.isFinite() && end.isFinite() && end > start) {
                result += ChapterSkipSegment(type, start, end, 0.0)
            }
        }
        result
    }.getOrNull()

    fun save(
        context: Context,
        animeId: String,
        episodeId: String,
        segments: List<ChapterSkipSegment>
    ): Boolean {
        val array = JSONArray()
        segments.forEach { segment ->
            array.put(JSONObject().apply {
                put("type", segment.type)
                put("start", segment.startTime)
                put("end", segment.endTime)
            })
        }
        val root = JSONObject()
            .put("version", VERSION)
            .put("episodeId", episodeId)
            .put("segments", array)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(animeId, episodeId), root.toString())
            .commit()
    }

    private fun key(animeId: String, episodeId: String): String =
        KEY_PREFIX + animeId + "::" + episodeId
}
