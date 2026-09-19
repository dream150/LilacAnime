package com.lilac.anime.data.offline

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.subtitle.*
import com.lilac.anime.network.*
import com.lilac.anime.player.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.detail.*
import com.lilac.anime.ui.home.*
import com.lilac.anime.ui.navigation.*
import com.lilac.anime.ui.search.*
import com.lilac.anime.ui.settings.*
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Persistent state and filesystem layout for offline downloads.
 *
 * The filesystem is the source of truth. JSON is only the durable job index/UI
 * metadata; a progress value is never used to decide which bytes exist.
 */
object MpvOfflineStore {
    private const val ROOT = "mpv_offline"
    private const val META = "metadata.json"
    private const val VIDEO = "episode.mp4"
    private const val VIDEO_PART = "episode.mp4.part"
    private const val LOCAL_HLS = "fallback.m3u8"
    private const val TEMP_TS = "segments.ts"

    data class Status(
        val id: String,
        val progress: Float,
        val state: String,
        val title: String = "",
        val episodeId: String = "",
        val videoPath: String? = null,
        val error: String? = null,
        val animeId: String = "",
        val sourceUrl: String? = null,
        val referer: String? = null,
        val episodeNumber: Int = 0,
        val episodeKey: String = ""
    )

    fun root(context: Context): File = File(context.filesDir, ROOT)

    fun episodeDir(context: Context, animeId: String, episodeId: String): File =
        File(root(context), safe("${animeId}__${episodeId}"))

    fun videoFile(context: Context, animeId: String, episodeId: String): File =
        File(episodeDir(context, animeId, episodeId), VIDEO)

    fun videoPartFile(context: Context, animeId: String, episodeId: String): File =
        File(episodeDir(context, animeId, episodeId), VIDEO_PART)

    fun fallbackPlaylist(context: Context, animeId: String, episodeId: String): File =
        File(episodeDir(context, animeId, episodeId), LOCAL_HLS)

    fun tempTsFile(context: Context, animeId: String, episodeId: String): File =
        File(episodeDir(context, animeId, episodeId), TEMP_TS)

    fun isCompleted(context: Context, animeId: String, episodeId: String): Boolean =
        completedPath(context, animeId, episodeId) != null

    fun completedPath(context: Context, animeId: String, episodeId: String): String? {
        val mp4 = videoFile(context, animeId, episodeId)
        return mp4.absolutePath.takeIf {
            mp4.isFile && mp4.length() > 0L && hasPlayableVideoTrack(mp4)
        }
    }

    private fun hasPlayableVideoTrack(file: File): Boolean = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var video = false
            var audio = false
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) video = true
                if (mime.startsWith("audio/")) audio = true
            }
            video && audio
        } finally {
            extractor.release()
        }
    }.getOrDefault(false)

    @Synchronized
    fun saveStatus(context: Context, status: Status) {
        val animeId = status.animeId.takeIf { it.isNotBlank() }
            ?: status.id.substringBefore("::")
        val episodeId = status.episodeId.takeIf { it.isNotBlank() }
            ?: status.id.substringAfter("::")
        val dir = episodeDir(context, animeId, episodeId)
        dir.mkdirs()
        val obj = JSONObject()
            .put("id", status.id)
            .put("animeId", animeId)
            .put("episodeId", episodeId)
            .put("progress", status.progress.coerceIn(0f, 1f).toDouble())
            .put("state", status.state)
            .put("title", status.title)
            .put("episodeNumber", status.episodeNumber)
            .put("episodeKey", status.episodeKey)
        status.videoPath?.let { obj.put("videoPath", it) }
        status.error?.let { obj.put("error", it) }
        status.sourceUrl?.let { obj.put("sourceUrl", it) }
        status.referer?.let { obj.put("referer", it) }
        val temp = File(dir, "$META.tmp")
        temp.writeText(obj.toString(), Charsets.UTF_8)
        if (!temp.renameTo(File(dir, META))) {
            File(dir, META).delete()
            check(temp.renameTo(File(dir, META))) { "다운로드 상태 저장 실패" }
        }
    }

    fun findStatus(context: Context, id: String): Status? =
        listStatuses(context).firstOrNull { it.id == id }

    fun listStatuses(context: Context): List<Status> =
        root(context).listFiles()?.mapNotNull { dir ->
            val meta = File(dir, META)
            if (!meta.isFile) return@mapNotNull null
            readStatus(meta)
        } ?: emptyList()

    private fun readStatus(file: File): Status? = runCatching {
        val obj = JSONObject(file.readText(Charsets.UTF_8))
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@runCatching null
        Status(
            id = id,
            progress = obj.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
            state = obj.optString("state", "queued"),
            title = obj.optString("title"),
            episodeId = obj.optString("episodeId", id.substringAfter("::")),
            videoPath = obj.optString("videoPath").takeIf { it.isNotBlank() },
            error = obj.optString("error").takeIf { it.isNotBlank() },
            animeId = obj.optString("animeId", id.substringBefore("::")),
            sourceUrl = obj.optString("sourceUrl").takeIf { it.isNotBlank() },
            referer = obj.optString("referer").takeIf { it.isNotBlank() },
            episodeNumber = obj.optInt("episodeNumber", 0),
            episodeKey = obj.optString("episodeKey")
        )
    }.getOrNull()

    fun clearPartial(context: Context, animeId: String, episodeId: String) {
        val dir = episodeDir(context, animeId, episodeId)
        File(dir, "hls").deleteRecursively()
        videoPartFile(context, animeId, episodeId).delete()
        File(dir, "video_source.bin").delete()
        File(dir, "audio_source.bin").delete()
    }

    fun delete(context: Context, animeId: String, episodeId: String) {
        episodeDir(context, animeId, episodeId).deleteRecursively()
    }

    private fun safe(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "_")
        .take(180)
}
