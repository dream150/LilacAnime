package com.lilac.anime.network

import android.content.Context
import android.util.Base64
import com.lilac.anime.ChapterSkipSegment
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Stores actual OP/ED log-mel audio fingerprints, not episode timestamps.
 *
 * A template is made from audio that was found to repeat between a few reference
 * episodes.  The stored data contains only the fingerprint samples themselves;
 * no OP/ED start/end time is persisted.
 */
object OfflineOpEdFingerprintStore {
    private const val PREFS = "linkkf_oped_fingerprints"
    private const val KEY_PREFIX = "anime_"
    private const val RESULT_PREFIX = "result_"
    private const val VERSION = 10

    data class Template(
        val op: FloatArray?,
        val ed: FloatArray?
    )

    data class AnalysisResult(
        val segments: List<ChapterSkipSegment>
    )

    fun load(context: Context, animeId: String): Template? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + animeId, null) ?: return null
        val root = JSONObject(raw)
        if (root.optInt("version", -1) != VERSION) return null
        val op = decode(root.optString("op", ""))
        val ed = decode(root.optString("ed", ""))
        if (op == null && ed == null) null else Template(op, ed)
    }.getOrNull()

    fun save(
        context: Context,
        animeId: String,
        opFingerprint: FloatArray?,
        edFingerprint: FloatArray?
    ): Boolean {
        if (opFingerprint == null && edFingerprint == null) return false
        val root = JSONObject()
            .put("version", VERSION)
            .put("format", "audio-fingerprint")
        opFingerprint?.let { root.put("op", encode(it)) }
        edFingerprint?.let { root.put("ed", encode(it)) }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + animeId, root.toString())
            .commit()
    }

    fun loadAnalysis(
        context: Context,
        animeId: String,
        episodeId: String
    ): List<ChapterSkipSegment>? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(analysisKey(animeId, episodeId), null) ?: return null
        val root = JSONObject(raw)
        if (root.optInt("version", VERSION) != VERSION) return null
        val duration = root.optDouble("episodeLength", 0.0)
        val array = root.optJSONArray("segments") ?: return emptyList()
        val result = ArrayList<ChapterSkipSegment>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val type = item.optString("type")
            val start = item.optDouble("start", Double.NaN)
            val end = item.optDouble("end", Double.NaN)
            if (type.isNotBlank() && start.isFinite() && end.isFinite() && end > start) {
                result += ChapterSkipSegment(type, start, end, duration)
            }
        }
        result
    }.getOrNull()

    fun saveAnalysis(
        context: Context,
        animeId: String,
        episodeId: String,
        segments: List<ChapterSkipSegment>,
        episodeLengthSeconds: Double = segments.firstOrNull()?.episodeLength ?: 0.0
    ): Boolean {
        val root = JSONObject()
            .put("version", VERSION)
            .put("animeId", animeId)
            .put("episodeId", episodeId)
            .put("episodeLength", episodeLengthSeconds)
        val array = JSONArray()
        segments.forEach { segment ->
            array.put(JSONObject()
                .put("type", segment.type)
                .put("start", segment.startTime)
                .put("end", segment.endTime))
        }
        root.put("segments", array)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(analysisKey(animeId, episodeId), root.toString())
            .commit()
    }

    fun deleteAllAnalysis(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val keys = prefs.all.keys.filter { it.startsWith(RESULT_PREFIX) }
        keys.forEach(editor::remove)
        editor.commit()
        return keys.size
    }

    fun deleteAllFingerprints(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val keys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) || it.startsWith(RESULT_PREFIX) }
        keys.forEach(editor::remove)
        editor.commit()
        // 구버전에서 별도로 사용하던 프로필도 함께 제거하여 재학습을 확실히 한다.
        OfflineOpEdProfileStore.deleteAll(context)
        return keys.count { it.startsWith(KEY_PREFIX) }
    }

    fun deleteEverything(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val keys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) || it.startsWith(RESULT_PREFIX) }
        keys.forEach(editor::remove)
        editor.commit()
        return keys.size
    }

    private fun analysisKey(animeId: String, episodeId: String): String =
        RESULT_PREFIX + Base64.encodeToString(
            (animeId + "\u0000" + episodeId).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

    fun isReady(context: Context, animeId: String): Boolean = load(context, animeId)?.let {
        it.op != null || it.ed != null
    } == true

    private fun encode(values: FloatArray): String {
        val raw = ByteBuffer.allocate(values.size * 4)
        values.forEach(raw::putFloat)
        val compressed = ByteArrayOutputStream().also { baos ->
            GZIPOutputStream(baos).use { it.write(raw.array()) }
        }.toByteArray()
        return Base64.encodeToString(compressed, Base64.NO_WRAP)
    }

    private fun decode(value: String): FloatArray? = runCatching {
        if (value.isBlank()) return null
        val compressed = Base64.decode(value, Base64.DEFAULT)
        val raw = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        if (raw.isEmpty() || raw.size % 4 != 0) return null
        val buffer = ByteBuffer.wrap(raw)
        FloatArray(raw.size / 4) { buffer.float }
    }.getOrNull()
}
