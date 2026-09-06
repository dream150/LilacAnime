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
import com.lilac.anime.data.subtitle.downloadSubtitleFile
import com.lilac.anime.network.LinkkfRequestContextStore
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
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val subtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE_URL)
            ?: LinkkfRequestContextStore.getSubtitleUrl(applicationContext, animeId, episodeId)
        val episodeNumber = intent.getIntExtra(EXTRA_EPISODE_NUMBER, 0)
        val episodeKey = intent.getStringExtra(EXTRA_EPISODE_KEY) ?: episodeNumber.toString()
        val subtitleReferer = intent.getStringExtra(EXTRA_SUBTITLE_REFERER)
            ?: LinkkfRequestContextStore.getSubtitle(applicationContext, animeId, episodeId)
            ?: referer
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
                // Linkkf VTT is independent of the video download. Save it first so
                // the completed offline episode always has a local subtitle when the
                // subtitle URL/Referer were already captured by the player WebView.
                var localSubtitle: String? = null
                suspend fun saveLinkkfSubtitle(): String? {
                    val url = subtitleUrl?.takeIf { it.isNotBlank() }
                        ?: LinkkfRequestContextStore.getSubtitleUrl(applicationContext, animeId, episodeId)
                        ?: return null
                    // The VTT endpoint can require a different Referer from the m3u8.
                    // Prefer the exact subtitle request Referer passed by the collector, then
                    // the persisted subtitle Referer. Never downgrade to the video Referer unless
                    // there is no subtitle-specific context at all.
                    val ref = subtitleReferer?.takeIf { it.isNotBlank() }
                        ?: LinkkfRequestContextStore.getSubtitle(applicationContext, animeId, episodeId)
                        ?: referer
                    LinkkfRequestContextStore.saveSubtitleUrl(applicationContext, animeId, episodeId, url)
                    ref?.let { LinkkfRequestContextStore.saveSubtitle(applicationContext, animeId, episodeId, it) }
                    return runCatching {
                        downloadSubtitleFile(
                            context = applicationContext,
                            animeId = animeId,
                            episodeNumber = episodeNumber,
                            episodeKey = episodeKey,
                            vttUrl = url,
                            referer = ref
                        )
                    }.getOrNull()?.also { path ->
                        SubtitleStore.save(
                            context = applicationContext,
                            animeId = animeId,
                            episodeKey = episodeKey,
                            episodeNumber = episodeNumber,
                            source = "linkkf",
                            path = path
                        )
                        android.util.Log.d("OfflineDownload", "SUBTITLE_SAVED_WITH_VIDEO episode=$episodeKey path=$path ref=${ref ?: "<none>"}")
                    }
                }

                // Do this before the potentially long HLS download. If the first
                // attempt fails, the same URL/context is retried after the video.
                localSubtitle = saveLinkkfSubtitle()
                if (localSubtitle == null) {
                    android.util.Log.w("OfflineDownload", "SUBTITLE_FIRST_ATTEMPT_FAILED episode=$episodeKey url=${subtitleUrl ?: "<none>"}")
                }

                val file = MpvHlsDownloader().download(applicationContext, animeId, episodeId, sourceUrl, referer) { progress ->
                    val fraction = if (progress.total > 0) progress.downloaded.toFloat() / progress.total else 0f
                    MpvOfflineStore.saveStatus(applicationContext, MpvOfflineStore.Status(key, fraction, "downloading", title, episodeId))
                    updateNotification("$title - ${episodeId}", fraction)
                }

                val stored = OfflineStore.getEpisodesForAnime(applicationContext, animeId)
                    .firstOrNull { it.id == episodeId }

                // The player can refresh the subtitle URL/Referer while the video is
                // downloading. Re-read the shared context and retry once at the end.
                if (localSubtitle == null) {
                    localSubtitle = saveLinkkfSubtitle()
                }

                if (stored != null) {
                    OfflineStore.saveEpisode(
                        context = applicationContext,
                        animeId = animeId,
                        episode = stored.copy(
                            videoUrl = file.absolutePath,
                            vttUrl = localSubtitle ?: stored.vttUrl
                        )
                    )
                }
                // Persist completion only after the final MP4 has passed the
                // downloader's validation. Player/ViewModel can then recognize
                // this episode as offline without waiting for the old store.
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
        const val EXTRA_EPISODE_NUMBER = "episodeNumber"
        const val EXTRA_EPISODE_KEY = "episodeKey"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_SUBTITLE_URL = "subtitleUrl"
        const val EXTRA_SUBTITLE_REFERER = "subtitleReferer"
        private const val CHANNEL_ID = "lilac_mpv_download"
        private const val NOTIFICATION_ID = 4101
    }
}
