package com.lilac.anime.network

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Debug-only diagnostics for the FlixCloud HLS transformation path.
 * Raw content keys are intentionally not persisted; SHA-256 fingerprints and lengths are.
 */
object FlixCloudDebugDump {
    private const val TAG = "FlixCloudDebug"
    @Volatile private var root: File? = null

    fun init(context: Context) {
        root = File(context.getExternalFilesDir(null) ?: context.cacheDir, "flixcloud_debug").apply { mkdirs() }
        log("INIT dir=${root?.absolutePath}")
    }

    fun sessionDir(sessionId: String): File? = root?.let {
        File(it, sessionId).apply { mkdirs() }
    }

    fun log(message: String) = Log.d(TAG, message)
    fun warn(message: String) = Log.w(TAG, message)
    fun error(message: String, t: Throwable? = null) = Log.e(TAG, message, t)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun writeText(sessionId: String, name: String, text: String): File? {
        val dir = sessionDir(sessionId) ?: return null
        val file = File(dir, name)
        file.writeText(text, Charsets.UTF_8)
        log("DUMP_TEXT name=${file.absolutePath} bytes=${text.toByteArray(Charsets.UTF_8).size}")
        return file
    }

    fun writeBytes(sessionId: String, name: String, bytes: ByteArray): File? {
        val dir = sessionDir(sessionId) ?: return null
        val file = File(dir, name)
        file.writeBytes(bytes)
        log("DUMP_BYTES name=${file.absolutePath} bytes=${bytes.size} sha256=${sha256(bytes)}")
        return file
    }

    fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}
