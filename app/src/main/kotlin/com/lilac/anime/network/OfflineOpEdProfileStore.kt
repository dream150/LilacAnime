package com.lilac.anime.network

import android.content.Context
import com.lilac.anime.ChapterSkipSegment
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Persists learned OP/ED timing per anime.
 *
 * Training is performed only for episodes 1..5.  After all five have been
 * observed, the median position is used as the normal profile and positional
 * outliers are stored as episode-specific overrides (for example a special
 * episode whose OP starts much later than the normal episodes).
 */
object OfflineOpEdProfileStore {
    private const val PREFS = "linkkf_oped_profiles"
    private const val KEY_PREFIX = "anime_"
    private const val VERSION = 3
    private const val OUTLIER_TOLERANCE_SECONDS = 60.0
    private const val MAX_CHAPTER_SECONDS = 85.0
    private const val MIN_MATCH_SECONDS = 30.0

    data class Profile(
        val baseline: List<ChapterSkipSegment>,
        val overrides: Map<Int, List<ChapterSkipSegment>>,
        val trained: Boolean
    )

    private data class SimpleSegment(
        val type: String,
        val start: Double,
        val end: Double
    )

    fun load(context: Context, animeId: String): Profile? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + animeId, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("version", VERSION) != VERSION) return null
            val baseline = readSegments(root.optJSONArray("baseline"), 0.0)
            val overridesObject = root.optJSONObject("overrides")
            val overrides = mutableMapOf<Int, List<ChapterSkipSegment>>()
            if (overridesObject != null) {
                for (key in overridesObject.keys()) {
                    val episode = key.toIntOrNull() ?: continue
                    val list = readSegments(overridesObject.optJSONArray(key), 0.0)
                    if (list.isNotEmpty()) overrides[episode] = list
                }
            }
            Profile(
                baseline = baseline,
                overrides = overrides,
                trained = root.optBoolean("trained", false)
            )
        }.getOrNull()
    }

    fun delete(context: Context, animeId: String): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX + animeId)
            .commit()
    }

    /** Returns the baseline with only the episode-specific OP/ED types replaced by overrides. */
    fun segmentsForEpisode(profile: Profile, episodeNumber: Int): List<ChapterSkipSegment> {
        val override = profile.overrides[episodeNumber].orEmpty()
        if (override.isEmpty()) return profile.baseline
        val overriddenTypes = override.map { it.type.lowercase() }.toSet()
        return (profile.baseline.filter { it.type.lowercase() !in overriddenTypes } + override)
            .sortedBy { it.startTime }
    }

    fun recordTrainingResult(
        context: Context,
        animeId: String,
        episodeNumber: Int,
        segments: List<ChapterSkipSegment>,
        log: (String) -> Unit = {}
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = runCatching {
            prefs.getString(KEY_PREFIX + animeId, null)?.let { JSONObject(it) }
        }.getOrNull() ?: JSONObject()

        root.put("version", VERSION)
        root.put("animeId", animeId)
        val training = root.optJSONObject("training") ?: JSONObject().also { root.put("training", it) }
        training.put(episodeNumber.toString(), segmentsToJson(segments))

        val trainingNumbers = (1..5).filter { training.has(it.toString()) }
        log("OFFLINE_PROFILE_TRAINING episode=$episodeNumber ready=${trainingNumbers.joinToString(",")}")

        if ((1..5).all { training.has(it.toString()) }) {
            buildProfile(root, training, log)
        }

        val saved = prefs.edit().putString(KEY_PREFIX + animeId, root.toString()).commit()
        log("OFFLINE_PROFILE_SAVE anime=$animeId episode=$episodeNumber trained=${root.optBoolean("trained", false)} success=$saved")
    }

    private fun buildProfile(root: JSONObject, training: JSONObject, log: (String) -> Unit) {
        val byType = mapOf(
            "op" to mutableListOf<Pair<Int, SimpleSegment>>(),
            "ed" to mutableListOf<Pair<Int, SimpleSegment>>()
        )

        for (episode in 1..5) {
            val list = readSimple(training.optJSONArray(episode.toString()))
            list.forEach { seg -> byType[seg.type]?.add(episode to seg) }
        }

        val baseline = mutableListOf<SimpleSegment>()
        val overrides = mutableMapOf<Int, MutableList<SimpleSegment>>()

        for ((type, entries) in byType) {
            if (entries.size < 3) continue
            val starts = entries.map { it.second.start }.sorted()
            val lengths = entries.map { it.second.end - it.second.start }.sorted()
            val medianStart = median(starts)
            val medianLength = median(lengths)
            val kept = entries.filter {
                abs(it.second.start - medianStart) <= OUTLIER_TOLERANCE_SECONDS &&
                    abs((it.second.end - it.second.start) - medianLength) <= 30.0
            }
            val removed = entries.filter { it !in kept }

            removed.forEach { (ep, seg) ->
                overrides.getOrPut(ep) { mutableListOf() }.add(seg)
                log("OFFLINE_PROFILE_${type.uppercase()}_OUTLIER episode=$ep start=${seg.start} end=${seg.end} medianStart=$medianStart medianLength=$medianLength")
            }

            if (kept.isNotEmpty()) {
                val start = median(kept.map { it.second.start })
                val end = median(kept.map { it.second.end })
                val cappedEnd = if (end - start > MAX_CHAPTER_SECONDS) start + MAX_CHAPTER_SECONDS else end
                baseline += SimpleSegment(type, start, cappedEnd)
                log("OFFLINE_PROFILE_${type.uppercase()}_BASELINE start=$start end=$cappedEnd samples=${kept.size}")
            }
        }

        root.put("baseline", segmentsToJson(baseline.map { ChapterSkipSegment(it.type, it.start, it.end, 0.0) }))
        val overrideJson = JSONObject()
        overrides.forEach { (episode, list) ->
            overrideJson.put(episode.toString(), segmentsToJson(list.map { ChapterSkipSegment(it.type, it.start, it.end, 0.0) }))
        }
        root.put("overrides", overrideJson)
        root.put("trained", true)
        root.put("trainedEpisodes", "1,2,3,4,5")
        log("OFFLINE_PROFILE_READY baseline=${baseline.size} overrides=${overrides.keys.sorted()}")
    }

    private fun segmentsToJson(segments: List<ChapterSkipSegment>): JSONArray {
        val array = JSONArray()
        segments.forEach {
            array.put(JSONObject().apply {
                put("type", it.type)
                put("start", it.startTime)
                put("end", it.endTime)
            })
        }
        return array
    }

    private fun readSegments(array: JSONArray?, episodeLength: Double): List<ChapterSkipSegment> {
        if (array == null) return emptyList()
        val result = mutableListOf<ChapterSkipSegment>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val type = o.optString("type")
            val start = o.optDouble("start", Double.NaN)
            val end = o.optDouble("end", Double.NaN)
            if (type.isNotBlank() && start.isFinite() && end.isFinite() && end > start) {
                result += ChapterSkipSegment(type, start, end, episodeLength)
            }
        }
        return result
    }

    private fun readSimple(array: JSONArray?): List<SimpleSegment> = readSegments(array, 0.0)
        .map { SimpleSegment(it.type, it.startTime, it.endTime) }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return 0.0
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }
}
