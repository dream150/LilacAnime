package com.lilac.anime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler

/** Compatibility-only service for downloads created by versions before v43. */
@UnstableApi
class LegacyLilacDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    androidx.media3.exoplayer.R.string.exo_download_notification_channel_name,
    0
) {
    companion object {
        private const val CHANNEL_ID = "lilac_legacy_download_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 4102
    }

    private lateinit var notificationHelper: DownloadNotificationHelper

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "기존 LilacAnime 다운로드", NotificationManager.IMPORTANCE_LOW)
            )
        }
        notificationHelper = DownloadNotificationHelper(this, CHANNEL_ID)
    }

    override fun getDownloadManager(): DownloadManager = LilacApplication.downloadManager
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        val active = downloads.firstOrNull { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
        val title = active?.request?.data?.let { String(it, Charsets.UTF_8) }
        return notificationHelper.buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            null,
            title,
            downloads,
            notMetRequirements
        )
    }
}
