package com.lilac.anime.data.offline

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.matcher.*
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
    subtitleReferer: String? = null
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
            subtitleReferer = subtitleReferer
        )
    )
}

fun offlineDownloadId(animeId: String, episode: Episode): String = "${animeId}::${episode.id}"
