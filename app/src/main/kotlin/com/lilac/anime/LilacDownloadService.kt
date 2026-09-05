package com.lilac.anime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.lilac.anime.data.offline.MpvHlsDownloader
import com.lilac.anime.data.offline.MpvOfflineStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Native HLS -> MP4 downloader used for all new downloads. */
class LilacDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> startDownload(intent, startId)
            ACTION_REMOVE -> {
                removeDownload(intent)
                stopSelfResult(startId)
            }
            ACTION_CANCEL_ALL -> {
                jobs.values.forEach { it.cancel() }
                jobs.clear()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(intent: Intent, startId: Int) {
        val animeId = intent.getStringExtra(EXTRA_ANIME_ID) ?: return
        val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val sourceUrl = intent.getStringExtra(EXTRA_URL) ?: return
        val key = "${animeId}::${episodeId}"
        if (jobs[key]?.isActive == true) return

        // startForegroundService() has a strict startup deadline.  Promote the
        // service synchronously before launching any coroutine/network work.
        val initialNotification = notification("$title - ${episodeId}", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
        MpvOfflineStore.saveStatus(applicationContext, MpvOfflineStore.Status(key, 0f, "downloading", title, episodeId))
        jobs[key] = scope.launch {
            try {
                val file = MpvHlsDownloader().download(applicationContext, animeId, episodeId, sourceUrl) { progress ->
                    val fraction = if (progress.total > 0) progress.downloaded.toFloat() / progress.total else 0f
                    MpvOfflineStore.saveStatus(applicationContext, MpvOfflineStore.Status(key, fraction, "downloading", title, episodeId))
                    updateNotification("$title - ${episodeId}", fraction)
                }
                val stored = OfflineStore.getEpisodesForAnime(applicationContext, animeId)
                    .firstOrNull { it.id == episodeId }
                if (stored != null) {
                    OfflineStore.saveEpisode(applicationContext, animeId, stored.copy(videoUrl = file.absolutePath))
                }
                MpvOfflineStore.saveStatus(applicationContext, MpvOfflineStore.Status(key, 1f, "completed", title, episodeId, file.absolutePath))
                updateNotification("$title - ${episodeId}", 1f, completed = true)
            } catch (t: Throwable) {
                MpvOfflineStore.saveStatus(applicationContext, MpvOfflineStore.Status(key, 0f, "failed", title, episodeId, error = t.message))
                updateNotification("$title - ${episodeId}", 0f, failed = true)
            } finally {
                jobs.remove(key)
                if (jobs.isEmpty()) stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
    }

    private fun removeDownload(intent: Intent) {
        val animeId = intent.getStringExtra(EXTRA_ANIME_ID) ?: return
        val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return
        val key = "${animeId}::${episodeId}"
        jobs.remove(key)?.cancel()
        MpvOfflineStore.delete(applicationContext, animeId, episodeId)
        if (jobs.isEmpty()) stopSelf()
    }

    private fun notification(text: String, progress: Int, completed: Boolean = false, failed: Boolean = false): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download)
            .setContentTitle("LilacAnime")
            .setContentText(if (completed) "$text 저장 완료" else if (failed) "$text 저장 실패" else "$text 저장 중")
            .setOngoing(!completed && !failed)
            .setProgress(100, (progress.coerceIn(0, 1) * 100).toInt(), false)
            .build()

    private fun updateNotification(text: String, fraction: Float, completed: Boolean = false, failed: Boolean = false) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, (fraction * 100).toInt(), completed, failed))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "LilacAnime 다운로드", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.lilac.anime.action.MPV_DOWNLOAD"
        const val ACTION_REMOVE = "com.lilac.anime.action.MPV_REMOVE"
        const val ACTION_CANCEL_ALL = "com.lilac.anime.action.MPV_CANCEL_ALL"
        const val EXTRA_ANIME_ID = "animeId"
        const val EXTRA_EPISODE_ID = "episodeId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"
        private const val CHANNEL_ID = "lilac_mpv_download"
        private const val NOTIFICATION_ID = 4101
    }
}
