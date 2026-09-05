package com.lilac.anime.data.offline

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Files owned by the mpv-native offline downloader. */
object MpvOfflineStore {
    private const val ROOT = "mpv_offline"
    private const val META = "metadata.json"
    private const val VIDEO = "episode.mp4"
    private const val LOCAL_HLS = "fallback.m3u8"
    private const val TEMP_TS = "segments.ts"

    data class Status(
        val id: String,
        val progress: Float,
        val state: String,
        val title: String = "",
        val episodeId: String = "",
        val videoPath: String? = null,
        val error: String? = null
    )

    fun root(context: Context): File = File(context.filesDir, ROOT)

    fun episodeDir(context: Context, animeId: String, episodeId: String): File =
        File(root(context), safe("${animeId}__${episodeId}"))

    fun videoFile(context: Context, animeId: String, episodeId: String): File =
        File(episodeDir(context, animeId, episodeId), VIDEO)

    fun fallbackPlaylist(context: Context, animeId: String, episodeId: String): File =
        File(episodeDir(context, animeId, episodeId), LOCAL_HLS)

    fun tempTsFile(context: Context, animeId: String, episodeId: String): File =
        File(episodeDir(context, animeId, episodeId), TEMP_TS)

    fun isCompleted(context: Context, animeId: String, episodeId: String): Boolean =
        completedPath(context, animeId, episodeId) != null

    fun completedPath(context: Context, animeId: String, episodeId: String): String? {
        val mp4 = videoFile(context, animeId, episodeId)
        // Never treat an audio-only/broken MP4 as a completed offline video.
        // This also protects devices from the v43.4 muxing result where some
        // HLS video codecs could produce an MP4 containing only the audio track.
        if (mp4.isFile && mp4.length() > 0L && hasPlayableVideoTrack(mp4)) {
            return mp4.absolutePath
        }
        val fallback = fallbackPlaylist(context, animeId, episodeId)
        if (!fallback.isFile || fallback.length() == 0L) return null

        // A playlist left behind by an interrupted/older migration must not make
        // an episode look playable. Verify that every local media URI it names
        // actually exists before selecting it.
        val valid = runCatching {
            val lines = fallback.readLines(Charsets.UTF_8)
            val base = fallback.parentFile ?: return@runCatching false
            val mediaFiles = lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { File(base, it.substringBefore('?').substringBefore('#')) }
            mediaFiles.isNotEmpty() && mediaFiles.all { it.isFile && it.length() > 0L }
        }.getOrDefault(false)
        return fallback.absolutePath.takeIf { valid }
    }

    private fun hasPlayableVideoTrack(file: File): Boolean = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var video = false
            var audio = false
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    .orEmpty()
                if (mime.startsWith("video/")) video = true
                if (mime.startsWith("audio/")) audio = true
            }
            video && audio
        } finally {
            extractor.release()
        }
    }.getOrDefault(false)

    fun saveStatus(context: Context, status: Status) {
        val dir = episodeDir(context, status.id.substringBefore("::"), status.episodeId)
        dir.mkdirs()
        val obj = JSONObject()
            .put("id", status.id)
            .put("progress", status.progress.coerceIn(0f, 1f).toDouble())
            .put("state", status.state)
            .put("title", status.title)
            .put("episodeId", status.episodeId)
        status.videoPath?.let { obj.put("videoPath", it) }
        status.error?.let { obj.put("error", it) }
        File(dir, META).writeText(obj.toString())
    }

    fun findStatus(context: Context, id: String): Status? {
        val dir = root(context).listFiles()?.firstOrNull { child ->
            File(child, META).takeIf(File::isFile)?.let { file ->
                runCatching { JSONObject(file.readText()).optString("id") == id }.getOrDefault(false)
            } == true
        } ?: return null
        return runCatching {
            val obj = JSONObject(File(dir, META).readText())
            Status(
                id = obj.optString("id"),
                progress = obj.optDouble("progress", 0.0).toFloat(),
                state = obj.optString("state", "queued"),
                title = obj.optString("title"),
                episodeId = obj.optString("episodeId"),
                videoPath = obj.optString("videoPath").takeIf { it.isNotBlank() },
                error = obj.optString("error").takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    fun listStatuses(context: Context): List<Status> =
        root(context).listFiles()?.mapNotNull { dir ->
            runCatching {
                val obj = JSONObject(File(dir, META).takeIf(File::isFile)?.readText() ?: return@runCatching null)
                Status(
                    id = obj.optString("id"),
                    progress = obj.optDouble("progress", 0.0).toFloat(),
                    state = obj.optString("state", "queued"),
                    title = obj.optString("title"),
                    episodeId = obj.optString("episodeId"),
                    videoPath = obj.optString("videoPath").takeIf { it.isNotBlank() },
                    error = obj.optString("error").takeIf { it.isNotBlank() }
                )
            }.getOrNull()
        } ?: emptyList()

    fun delete(context: Context, animeId: String, episodeId: String) {
        episodeDir(context, animeId, episodeId).deleteRecursively()
    }

    private fun safe(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "_")
        .take(180)
}
