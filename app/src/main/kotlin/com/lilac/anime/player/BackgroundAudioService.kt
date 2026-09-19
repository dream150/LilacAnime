package com.lilac.anime.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.lilac.anime.MainActivity
import com.lilac.anime.R

/**
 * Keeps the shared libmpv engine alive while the app is in the background.
 * Uses the platform MediaSession API so lock-screen/headset/media controls work
 * without introducing another media player implementation.
 */
class BackgroundAudioService : Service() {
    companion object {
        private const val CHANNEL_ID = "lilac_background_audio"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_START = "com.lilac.anime.action.START_BACKGROUND_AUDIO"
        private const val ACTION_STOP = "com.lilac.anime.action.STOP_BACKGROUND_AUDIO"
        private const val ACTION_UPDATE = "com.lilac.anime.action.UPDATE_BACKGROUND_AUDIO"
        private const val ACTION_PLAY_PAUSE = "com.lilac.anime.action.BACKGROUND_PLAY_PAUSE"
        private const val ACTION_NEXT = "com.lilac.anime.action.BACKGROUND_NEXT"

        fun start(context: Context) {
            val intent = Intent(context, BackgroundAudioService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BackgroundAudioService::class.java).setAction(ACTION_STOP)
            )
        }

        fun updateNotification(context: Context) {
            context.startService(
                Intent(context, BackgroundAudioService::class.java).setAction(ACTION_UPDATE)
            )
        }
    }

    private lateinit var mediaSession: MediaSession
    private var audioFocusGranted = false
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val stateUpdater = object : Runnable {
        override fun run() {
            if (::mediaSession.isInitialized) updatePlaybackState(notify = false)
            handler.postDelayed(this, 500L)
        }
    }

    private val mediaCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            val engine = MpvPlaybackManager.engine(this@BackgroundAudioService)
            engine.play()
            updatePlaybackState(notify = true)
        }

        override fun onPause() {
            MpvPlaybackManager.engine(this@BackgroundAudioService).pause()
            updatePlaybackState(notify = true)
        }

        override fun onStop() {
            MpvPlaybackManager.engine(this@BackgroundAudioService).pause()
            updatePlaybackState(notify = true)
        }

        override fun onSeekTo(pos: Long) {
            MpvPlaybackManager.engine(this@BackgroundAudioService).seekTo(pos)
            updatePlaybackState(notify = true)
        }

        override fun onSkipToNext() {
            // The PlayerScreen callback remains registered while its Activity is
            // stopped, so the same episode-switching logic is used as the UI.
            MpvPlaybackManager.requestNextEpisode()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(AudioManager::class.java)

        mediaSession = MediaSession(this, "LilacAnimeBackground")
        mediaSession.setCallback(mediaCallback)
        mediaSession.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSession.isActive = true

        requestAudioFocus()
        handler.post(stateUpdater)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPlaybackService()
                return START_NOT_STICKY
            }
            ACTION_PLAY_PAUSE -> {
                val engine = MpvPlaybackManager.engine(this)
                if (engine.isPlaying) engine.pause() else engine.play()
                startForeground(NOTIFICATION_ID, buildNotification())
                updatePlaybackState(notify = true)
                return START_STICKY
            }
            ACTION_NEXT -> {
                MpvPlaybackManager.requestNextEpisode()
                return START_STICKY
            }
            ACTION_UPDATE -> Unit
            ACTION_START, null -> Unit
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        updatePlaybackState(notify = true)
        return START_STICKY
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            audioFocusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        MpvPlaybackManager.engine(this).pause()
                        updatePlaybackState(notify = true)
                    }
                }
                .build()
            audioFocusGranted = audioManager.requestAudioFocus(audioFocusRequest!!) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioFocusGranted = audioManager.requestAudioFocus(
                { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        MpvPlaybackManager.engine(this).pause()
                        updatePlaybackState(notify = true)
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun updatePlaybackState(notify: Boolean) {
        if (!::mediaSession.isInitialized) return
        val engine = MpvPlaybackManager.engine(this)
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_SKIP_TO_NEXT
        val state = if (engine.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, engine.currentPosition, engine.getSpeed().toFloat(), SystemClock.elapsedRealtime())
                .build()
        )
        mediaSession.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, MpvPlaybackManager.episodeTitle)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, MpvPlaybackManager.animeTitle)
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, engine.duration)
                .build()
        )
        if (notify) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, BackgroundAudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = PendingIntent.getService(
            this,
            12,
            Intent(this, BackgroundAudioService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getService(
            this,
            13,
            Intent(this, BackgroundAudioService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val engine = MpvPlaybackManager.engine(this)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(MpvPlaybackManager.episodeTitle.ifBlank { "LilacAnime" })
            .setContentText(MpvPlaybackManager.animeTitle)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        if (engine.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                    ),
                    if (engine.isPlaying) "일시정지" else "재생",
                    playPauseIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next),
                    "다음 화",
                    nextIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "종료",
                    stopIntent
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "백그라운드 재생",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "LilacAnime 백그라운드 오디오 재생"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopPlaybackService() {
        handler.removeCallbacks(stateUpdater)
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        if (audioFocusGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            audioFocusGranted = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(stateUpdater)
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
