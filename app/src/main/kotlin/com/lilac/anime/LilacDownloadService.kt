package com.lilac.anime

import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lilac.anime.data.offline.MpvHlsDownloader
import com.lilac.anime.data.offline.MpvOfflineStore
import com.lilac.anime.data.subtitle.downloadSubtitleFile
import com.lilac.anime.network.LinkkfRequestContextStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Owns the offline download queue. Each episode is an independent persistent
 * job. The service lifecycle must never be used as the source of truth for a
 * job's progress or completion.
 */
class LilacDownloadService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val cancelledForRemoval = mutableSetOf<String>()
    private val lock = Any()
    private val slots = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private var foregroundStarted = false
    private var recoveryStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        if (!recoveryStarted) {
            recoveryStarted = true
            recoverPersistentJobs()
        }

        when (intent?.action) {
            ACTION_DOWNLOAD -> enqueue(intent)
            ACTION_REMOVE -> removeDownload(intent)
            ACTION_CANCEL_ALL -> cancelAll()
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        val notification = summaryNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun recoverPersistentJobs() {
        MpvOfflineStore.listStatuses(applicationContext)
            .filter { it.state == STATE_DOWNLOADING || it.state == STATE_QUEUED || it.state == STATE_PAUSED }
            .forEach { status ->
                val source = status.sourceUrl ?: return@forEach
                val intent = Intent(this, LilacDownloadService::class.java).apply {
                    putExtra(EXTRA_ANIME_ID, status.animeId)
                    putExtra(EXTRA_EPISODE_ID, status.episodeId)
                    putExtra(EXTRA_TITLE, status.title)
                    putExtra(EXTRA_URL, source)
                    putExtra(EXTRA_REFERER, status.referer)
                    putExtra(EXTRA_EPISODE_NUMBER, status.episodeNumber)
                    putExtra(EXTRA_EPISODE_KEY, status.episodeKey)
                    action = ACTION_DOWNLOAD
                }
                enqueue(intent, recovering = true)
            }
    }

    private fun enqueue(intent: Intent, recovering: Boolean = false) {
        val animeId = intent.getStringExtra(EXTRA_ANIME_ID) ?: return
        val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return
        val sourceUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: MpvOfflineStore.findStatus(applicationContext, "$animeId::$episodeId")?.sourceUrl
            ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val requestedSubtitleUrl = intent.getStringExtra(EXTRA_SUBTITLE_URL)
        val requestedSubtitleReferer = intent.getStringExtra(EXTRA_SUBTITLE_REFERER)
        val episodeNumber = intent.getIntExtra(EXTRA_EPISODE_NUMBER, 0)
        val episodeKey = intent.getStringExtra(EXTRA_EPISODE_KEY) ?: episodeNumber.toString()
        val key = "$animeId::$episodeId"

        synchronized(lock) {
            if (jobs[key]?.isActive == true) return
        }

        val old = MpvOfflineStore.findStatus(applicationContext, key)
        if (MpvOfflineStore.isCompleted(applicationContext, animeId, episodeId)) {
            updateState(
                key,
                STATE_COMPLETED,
                progress = 1f,
                videoPath = MpvOfflineStore.completedPath(applicationContext, animeId, episodeId),
                title = title.ifBlank { old?.title.orEmpty() },
                episodeId = episodeId,
                sourceUrl = sourceUrl,
                referer = referer ?: old?.referer,
                episodeNumber = episodeNumber,
                episodeKey = episodeKey
            )
            return
        }
        if (old?.sourceUrl != null && old.sourceUrl != sourceUrl) {
            // A genuinely different stream must never reuse another stream's
            // segment files. This is the only condition that clears partial HLS.
            MpvOfflineStore.clearPartial(applicationContext, animeId, episodeId)
        }

        val existingProgress = old?.progress ?: 0f
        val state = if (recovering) STATE_QUEUED else STATE_QUEUED
        MpvOfflineStore.saveStatus(
            applicationContext,
            MpvOfflineStore.Status(
                id = key,
                progress = existingProgress,
                state = state,
                title = title.ifBlank { old?.title.orEmpty() },
                episodeId = episodeId,
                animeId = animeId,
                sourceUrl = sourceUrl,
                referer = referer ?: old?.referer,
                episodeNumber = if (episodeNumber != 0) episodeNumber else old?.episodeNumber ?: 0,
                episodeKey = episodeKey.ifBlank { old?.episodeKey.orEmpty() }
            )
        )

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                slots.withPermit {
                    runDownload(
                        animeId = animeId,
                        episodeId = episodeId,
                        title = title.ifBlank { old?.title.orEmpty() },
                        sourceUrl = sourceUrl,
                        referer = referer ?: old?.referer,
                        episodeNumber = episodeNumber,
                        episodeKey = episodeKey,
                        subtitleUrl = requestedSubtitleUrl,
                        subtitleReferer = requestedSubtitleReferer
                    )
                }
            } catch (cancel: CancellationException) {
                val removed = synchronized(lock) { cancelledForRemoval.contains(key) }
                if (!removed) {
                    val current = MpvOfflineStore.findStatus(applicationContext, key)
                    MpvOfflineStore.saveStatus(
                        applicationContext,
                        (current ?: MpvOfflineStore.Status(key, existingProgress, STATE_PAUSED, title, episodeId))
                            .copy(state = STATE_PAUSED)
                    )
                }
                throw cancel
            } catch (t: Throwable) {
                val current = MpvOfflineStore.findStatus(applicationContext, key)
                MpvOfflineStore.saveStatus(
                    applicationContext,
                    (current ?: MpvOfflineStore.Status(key, existingProgress, STATE_FAILED, title, episodeId))
                        .copy(state = STATE_FAILED, error = t.message)
                )
            } finally {
                synchronized(lock) {
                    jobs.remove(key)
                    cancelledForRemoval.remove(key)
                }
                publishSummary()
                maybeStopService()
            }
        }
        synchronized(lock) { jobs[key] = job }
        job.start()
        publishSummary()
    }

    private suspend fun runDownload(
        animeId: String,
        episodeId: String,
        title: String,
        sourceUrl: String,
        referer: String?,
        episodeNumber: Int,
        episodeKey: String,
        subtitleUrl: String?,
        subtitleReferer: String?
    ) {
        val key = "$animeId::$episodeId"
        updateState(key, STATE_DOWNLOADING)

        // Subtitle download is deliberately best-effort. A subtitle failure
        // can never invalidate a successfully downloaded video.
        var localSubtitle: String? = null
        val effectiveSubtitleUrl = subtitleUrl?.takeIf { it.isNotBlank() }
            ?: LinkkfRequestContextStore.getSubtitleUrl(applicationContext, animeId, episodeId)
        val effectiveSubtitleReferer = subtitleReferer?.takeIf { it.isNotBlank() }
            ?: LinkkfRequestContextStore.getSubtitle(applicationContext, animeId, episodeId)
            ?: referer
        if (!effectiveSubtitleUrl.isNullOrBlank()) {
            localSubtitle = runCatching {
                downloadSubtitleFile(
                    context = applicationContext,
                    animeId = animeId,
                    episodeNumber = episodeNumber,
                    episodeKey = episodeKey,
                    vttUrl = effectiveSubtitleUrl,
                    referer = effectiveSubtitleReferer
                )
            }.getOrNull()?.also { path ->
                SubtitleStore.save(applicationContext, animeId, episodeKey, episodeNumber, "linkkf", path)
            }
        }

        val file = MpvHlsDownloader().download(
            context = applicationContext,
            animeId = animeId,
            episodeId = episodeId,
            sourceUrl = sourceUrl,
            referer = referer
        ) { progress ->
            val fraction = if (progress.total > 0L) progress.downloaded.toFloat() / progress.total else 0f
            updateProgress(key, fraction, title, episodeId, sourceUrl, referer, episodeNumber, episodeKey)
        }

        if (localSubtitle == null && !effectiveSubtitleUrl.isNullOrBlank()) {
            localSubtitle = runCatching {
                downloadSubtitleFile(
                    context = applicationContext,
                    animeId = animeId,
                    episodeNumber = episodeNumber,
                    episodeKey = episodeKey,
                    vttUrl = effectiveSubtitleUrl,
                    referer = effectiveSubtitleReferer
                )
            }.getOrNull()?.also { path ->
                SubtitleStore.save(applicationContext, animeId, episodeKey, episodeNumber, "linkkf", path)
            }
        }

        val stored = OfflineStore.getEpisodesForAnime(applicationContext, animeId)
            .firstOrNull { it.id == episodeId }
        if (stored != null) {
            OfflineStore.saveEpisode(
                applicationContext,
                animeId,
                stored.copy(videoUrl = file.absolutePath, vttUrl = localSubtitle ?: stored.vttUrl)
            )
        }

        updateState(
            key,
            STATE_COMPLETED,
            progress = 1f,
            videoPath = file.absolutePath,
            title = title,
            episodeId = episodeId,
            sourceUrl = sourceUrl,
            referer = referer,
            episodeNumber = episodeNumber,
            episodeKey = episodeKey
        )
    }

    private fun updateProgress(
        key: String,
        progress: Float,
        title: String,
        episodeId: String,
        sourceUrl: String,
        referer: String?,
        episodeNumber: Int,
        episodeKey: String
    ) {
        val old = MpvOfflineStore.findStatus(applicationContext, key)
        MpvOfflineStore.saveStatus(
            applicationContext,
            MpvOfflineStore.Status(
                id = key,
                progress = progress,
                state = STATE_DOWNLOADING,
                title = title,
                episodeId = episodeId,
                videoPath = old?.videoPath,
                animeId = key.substringBefore("::"),
                sourceUrl = sourceUrl,
                referer = referer,
                episodeNumber = episodeNumber,
                episodeKey = episodeKey
            )
        )
        publishSummary()
    }

    private fun updateState(
        key: String,
        state: String,
        progress: Float? = null,
        videoPath: String? = null,
        title: String? = null,
        episodeId: String? = null,
        sourceUrl: String? = null,
        referer: String? = null,
        episodeNumber: Int? = null,
        episodeKey: String? = null
    ) {
        val old = MpvOfflineStore.findStatus(applicationContext, key)
        MpvOfflineStore.saveStatus(
            applicationContext,
            (old ?: MpvOfflineStore.Status(key, 0f, state, title.orEmpty(), episodeId.orEmpty()))
                .copy(
                    state = state,
                    progress = progress ?: old?.progress ?: 0f,
                    videoPath = videoPath ?: old?.videoPath,
                    title = title ?: old?.title.orEmpty(),
                    episodeId = episodeId ?: old?.episodeId.orEmpty(),
                    sourceUrl = sourceUrl ?: old?.sourceUrl,
                    referer = referer ?: old?.referer,
                    episodeNumber = episodeNumber ?: old?.episodeNumber ?: 0,
                    episodeKey = episodeKey ?: old?.episodeKey.orEmpty(),
                    error = null
                )
        )
        publishSummary()
    }

    private fun removeDownload(intent: Intent) {
        val animeId = intent.getStringExtra(EXTRA_ANIME_ID) ?: return
        val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return
        val key = "$animeId::$episodeId"
        synchronized(lock) {
            jobs.remove(key)?.let {
                cancelledForRemoval += key
                it.cancel()
            }
        }
        MpvOfflineStore.delete(applicationContext, animeId, episodeId)
        publishSummary()
        maybeStopService()
    }

    private fun cancelAll() {
        synchronized(lock) {
            jobs.values.toList().forEach { it.cancel() }
            jobs.clear()
        }
        MpvOfflineStore.listStatuses(applicationContext)
            .filter { it.state == STATE_DOWNLOADING || it.state == STATE_QUEUED }
            .forEach { status ->
                MpvOfflineStore.saveStatus(applicationContext, status.copy(state = STATE_PAUSED))
            }
        maybeStopService(force = true)
    }

    private fun maybeStopService(force: Boolean = false) {
        val active = synchronized(lock) { jobs.values.any { it.isActive } }
        if (force || !active) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (force || MpvOfflineStore.listStatuses(applicationContext).none {
                    it.state == STATE_DOWNLOADING || it.state == STATE_QUEUED
                }) stopSelf()
        }
    }

    private fun publishSummary() {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, summaryNotification())
    }

    private fun summaryNotification(): Notification {
        val active = MpvOfflineStore.listStatuses(applicationContext)
            .filter { it.state == STATE_DOWNLOADING || it.state == STATE_QUEUED }
        val running = active.count { it.state == STATE_DOWNLOADING }
        val text = when {
            active.isEmpty() -> "다운로드 대기 없음"
            active.size == 1 -> "${active.first().title} · ${"%.0f".format(active.first().progress * 100)}%"
            else -> "${active.size}개 다운로드 · ${running}개 진행 중"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("LilacAnime 오프라인 저장")
            .setContentText(text)
            .setOngoing(active.isNotEmpty())
            .setOnlyAlertOnce(true)
            .build()
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
        // Persist the interruption before cancelling coroutines. The next
        // service instance can then recover the exact same jobs and the HLS
        // engine will reuse their completed segment files.
        MpvOfflineStore.listStatuses(applicationContext)
            .filter { it.state == STATE_DOWNLOADING || it.state == STATE_QUEUED }
            .forEach { status ->
                MpvOfflineStore.saveStatus(applicationContext, status.copy(state = STATE_PAUSED))
            }
        synchronized(lock) { jobs.values.toList().forEach { it.cancel() }; jobs.clear() }
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
        private const val MAX_CONCURRENT_DOWNLOADS = 2
        private const val STATE_QUEUED = "queued"
        private const val STATE_DOWNLOADING = "downloading"
        private const val STATE_PAUSED = "paused"
        private const val STATE_FAILED = "failed"
        private const val STATE_COMPLETED = "completed"
    }
}
