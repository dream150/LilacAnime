package com.lilac.anime.network

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
import com.lilac.anime.data.subtitle.*
import com.lilac.anime.player.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.detail.*
import com.lilac.anime.ui.home.*
import com.lilac.anime.ui.navigation.*
import com.lilac.anime.ui.search.*
import com.lilac.anime.ui.settings.*
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.lilac.anime.R

/** Keeps the OP/ED audio fingerprint analysis visible while it runs. */
class OpEdAnalysisForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.lilac.anime.oped.START"
        const val ACTION_UPDATE = "com.lilac.anime.oped.UPDATE"
        const val ACTION_STOP = "com.lilac.anime.oped.STOP"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "oped_analysis_v2"
        private const val NOTIFICATION_ID = 2405

        fun start(context: android.content.Context) {
            val intent = Intent(context, OpEdAnalysisForegroundService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun update(context: android.content.Context, text: String) {
            context.startService(Intent(context, OpEdAnalysisForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TEXT, text)
            })
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, OpEdAnalysisForegroundService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification("분석을 준비하는 중..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification("분석을 준비하는 중..."))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotBlank()) {
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID, notification(toNotificationText(text))
                    )
                }
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START, null -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun toNotificationText(raw: String): String = when {
        raw.startsWith("FINGERPRINT_TRAINING_START") -> "1~5화 오디오에서 OP/ED Fingerprint를 학습하는 중..."
        raw.startsWith("FINGERPRINT_TRAINING_WAIT") -> "1~5화 오프라인 영상 확인 중..."
        raw.startsWith("FINGERPRINT_TRAIN_PAIR") -> "1~5화의 오디오 패턴을 비교하는 중..."
        raw.startsWith("FINGERPRINT_POSITION") -> "반복 음악의 OP/ED 위치 비율을 계산하는 중..."
        raw.startsWith("FINGERPRINT_TEMPLATES_SELECTED") -> "OP/ED Fingerprint를 저장하는 중..."
        raw.startsWith("FINGERPRINT_TEMPLATE_HIT") -> "저장된 OP/ED Fingerprint로 분석하는 중..."
        raw.startsWith("FINGERPRINT_ANALYSIS_CACHE_HIT") -> "저장된 OP/ED 분석 결과를 불러오는 중..."
        raw.startsWith("FINGERPRINT_OP_MATCH") -> "OP 구간을 찾았습니다."
        raw.startsWith("FINGERPRINT_ED_MATCH") -> "ED 구간을 찾았습니다."
        raw.startsWith("FINGERPRINT_ANALYSIS_SAVED") -> "OP/ED 분석 결과를 저장하는 중..."
        raw.startsWith("FINGERPRINT_ANALYSIS_COMPLETE") -> "OP/ED 분석 완료"
        raw.startsWith("FINGERPRINT_TRAINING_COMPLETE") -> "OP/ED Fingerprint 학습 완료"
        raw.startsWith("ANALYSIS_FAILED") || raw.startsWith("FINGERPRINT_TRAINING_FAILED") -> "OP/ED 분석 실패"
        else -> "OP/ED 분석 중..."
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentTitle("LilacAnime · OP/ED 분석")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OP/ED 분석",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "오프라인 영상 OP/ED 오디오 Fingerprint 분석 진행 상태" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
