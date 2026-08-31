package com.lilac.anime

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler

@UnstableApi
class LilacDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    androidx.media3.exoplayer.R.string.exo_download_notification_channel_name,
    0
) {
    companion object {
        private const val CHANNEL_ID = "lilac_download_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 1
    }

    private lateinit var notificationHelper: DownloadNotificationHelper

    override fun onCreate() {
        super.onCreate()
        notificationHelper = DownloadNotificationHelper(this, CHANNEL_ID)
    }

    override fun getDownloadManager(): DownloadManager {
        return LilacApplication.downloadManager
    }

    override fun getScheduler(): Scheduler? {
        return null
    }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        // 현재 진행 중이거나 대기 중인 다운로드 항목에서 회차 이름 추출
        val activeDownload = downloads.firstOrNull { 
            it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED 
        }
        
        val currentTitle = activeDownload?.request?.data?.let { bytes ->
            String(bytes, Charsets.UTF_8)
        }

        // multiple 다운로드일 경우 "원피스 - 1화 (외 N개)" 등의 문구를 메세지에 표기
        val message = if (downloads.size > 1 && currentTitle != null) {
            "$currentTitle (외 ${downloads.size - 1}개)"
        } else {
            currentTitle
        }

        return notificationHelper.buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            null,
            message, // 👈 세번째 메시지 인자값으로 "작품명 - N화" 가 출력됩니다.
            downloads,
            notMetRequirements
        )
    }
}