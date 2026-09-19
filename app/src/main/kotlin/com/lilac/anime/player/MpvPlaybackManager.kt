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
