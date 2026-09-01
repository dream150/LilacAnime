package com.lilac.anime.network

import android.content.Context
import android.util.Base64
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
    private const val VERSION = 9

    data class Template(
        val op: FloatArray?,
        val ed: FloatArray?
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
