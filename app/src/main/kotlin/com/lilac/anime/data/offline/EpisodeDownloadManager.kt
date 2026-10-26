package com.lilac.anime.data.offline

import com.lilac.anime.*

import android.content.Context
import com.lilac.anime.Episode

/** Backwards-compatible facade for existing UI call sites. */
fun startEpisodeDownload(
    context: Context,
    animeId: String,
    animeTitle: String,
    episode: Episode,
    streamUrl: String,
    referer: String? = null,
    subtitleUrl: String? = null,
    subtitleReferer: String? = null,
    flixCloudPk: String? = null,
    streamHeaders: String? = null,
    videoSource: String = "linkkf"
) {
    OfflineDownloadManager.enqueue(
        context,
        OfflineDownloadManager.Request(
            animeId = animeId,
            animeTitle = animeTitle,
            episode = episode,
            streamUrl = streamUrl,
            referer = referer,
            subtitleUrl = subtitleUrl,
            subtitleReferer = subtitleReferer,
            flixCloudPk = flixCloudPk,
            streamHeaders = streamHeaders,
            videoSource = videoSource
        )
    )
}

fun offlineDownloadId(animeId: String, episode: Episode): String = "${animeId}::${episode.id}"
