package com.lilac.anime.player

import android.content.Context

/**
 * Process-wide holder for the single libmpv instance used by the player screen
 * and the background-audio service. Keeping one engine is important: switching
 * to background audio must not create a second player or reset playback.
 */
object MpvPlaybackManager {
    @Volatile
    private var sharedEngine: MpvPlayerEngine? = null

    @Volatile
    var isBackgroundAudio: Boolean = false
        private set

    @Volatile
    var animeTitle: String = "LilacAnime"
        private set

    @Volatile
    var episodeTitle: String = ""
        private set

    @Volatile
    private var nextEpisodeCallback: (() -> Unit)? = null

    @Synchronized
    fun engine(context: Context): MpvPlayerEngine {
        return sharedEngine ?: MpvPlayerEngine(context.applicationContext).also {
            sharedEngine = it
        }
    }

    /**
     * Acquires a fresh libmpv instance for a normal foreground PlayerScreen.
     *
     * The app also has a background-audio service which intentionally owns the
     * process-wide engine.  Outside that mode, reusing a stopped engine after a
     * TextureView/Surface teardown is unreliable on some Android/libmpv builds,
     * especially for local files.  A foreground screen therefore gets a clean
     * native session on every entry.
     */
    @Synchronized
    fun foregroundEngine(context: Context): MpvPlayerEngine {
        if (isBackgroundAudio) return engine(context)

        sharedEngine?.let { old ->
            runCatching { old.stop() }
            runCatching { old.detachSurface() }
            runCatching { old.release() }
        }

        return MpvPlayerEngine(context.applicationContext).also {
            sharedEngine = it
        }
    }

    /**
     * Releases a foreground engine after its Surface has been detached.
     * Background-audio playback is never released here.
     */
    @Synchronized
    fun releaseForegroundEngine(engine: MpvPlayerEngine) {
        if (isBackgroundAudio) return
        if (sharedEngine === engine) {
            runCatching { engine.stop() }
            runCatching { engine.detachSurface() }
            runCatching { engine.release() }
            sharedEngine = null
        }
    }

    fun setNextEpisodeCallback(callback: (() -> Unit)?) {
        nextEpisodeCallback = callback
    }

    fun requestNextEpisode() {
        nextEpisodeCallback?.invoke()
    }

    fun enterBackgroundAudio(
        context: Context,
        animeTitle: String,
        episodeTitle: String
    ) {
        this.animeTitle = animeTitle.ifBlank { "LilacAnime" }
        this.episodeTitle = episodeTitle
        isBackgroundAudio = true
        BackgroundAudioService.start(context.applicationContext)
    }

    fun exitBackgroundAudio(context: Context) {
        isBackgroundAudio = false
        BackgroundAudioService.stop(context.applicationContext)
    }

    fun updateNowPlaying(
        context: Context,
        animeTitle: String,
        episodeTitle: String
    ) {
        this.animeTitle = animeTitle.ifBlank { "LilacAnime" }
        this.episodeTitle = episodeTitle
        if (isBackgroundAudio) {
            BackgroundAudioService.updateNotification(context.applicationContext)
        }
    }
}
