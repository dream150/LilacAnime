package com.lilac.anime

import android.content.Context
import android.content.Intent
import com.lilac.anime.Episode

/** Starts the mpv-native HLS -> MP4 downloader. */
fun startEpisodeDownload(
    context: Context,
    animeId: String,
    animeTitle: String,
    episode: Episode,
    streamUrl: String
) {
    val intent = Intent(context.applicationContext, LilacDownloadService::class.java).apply {
        action = LilacDownloadService.ACTION_DOWNLOAD
        putExtra(LilacDownloadService.EXTRA_ANIME_ID, animeId)
        putExtra(LilacDownloadService.EXTRA_EPISODE_ID, episode.id)
        putExtra(LilacDownloadService.EXTRA_TITLE, "$animeTitle - ${episode.displayNumber}화")
        putExtra(LilacDownloadService.EXTRA_URL, streamUrl)
    }
    androidx.core.content.ContextCompat.startForegroundService(context.applicationContext, intent)
}

fun offlineDownloadId(animeId: String, episode: Episode): String = "${animeId}::${episode.id}"
