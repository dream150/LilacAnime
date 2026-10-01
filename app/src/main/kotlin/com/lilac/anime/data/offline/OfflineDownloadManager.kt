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
import android.content.Intent
import androidx.core.content.ContextCompat
import com.lilac.anime.Episode
import com.lilac.anime.LilacDownloadService

/** Single entry point for creating/cancelling durable offline download jobs. */
object OfflineDownloadManager {
    data class Request(
        val animeId: String,
        val animeTitle: String,
        val episode: Episode,
        val streamUrl: String,
        val referer: String? = null,
        val subtitleUrl: String? = null,
        val subtitleReferer: String? = null,
        val flixCloudPk: String? = null,
        val streamHeaders: String? = null
    )

    fun enqueue(context: Context, request: Request) {
        android.util.Log.i(
            "OfflineDownload",
            "ENQUEUE anime=${request.animeId} episode=${request.episode.id} " +
                "number=${request.episode.number} m3u8=${request.streamUrl.contains(".m3u8", true)} " +
                "flixPkChars=${request.flixCloudPk?.length ?: 0}"
        )
        val app = context.applicationContext
        val intent = Intent(app, LilacDownloadService::class.java).apply {
            action = LilacDownloadService.ACTION_DOWNLOAD
            putExtra(LilacDownloadService.EXTRA_ANIME_ID, request.animeId)
            putExtra(LilacDownloadService.EXTRA_EPISODE_ID, request.episode.id)
            putExtra(LilacDownloadService.EXTRA_TITLE, "${request.animeTitle} - ${request.episode.displayNumber}화")
            putExtra(LilacDownloadService.EXTRA_ANIME_TITLE, request.animeTitle)
            putExtra(LilacDownloadService.EXTRA_URL, request.streamUrl)
            putExtra(LilacDownloadService.EXTRA_EPISODE_NUMBER, request.episode.number)
            putExtra(LilacDownloadService.EXTRA_EPISODE_KEY, request.episode.displayNumber)
            request.referer?.takeIf { it.isNotBlank() }?.let { putExtra(LilacDownloadService.EXTRA_REFERER, it) }
            request.subtitleUrl?.takeIf { it.isNotBlank() }?.let { putExtra(LilacDownloadService.EXTRA_SUBTITLE_URL, it) }
            request.subtitleReferer?.takeIf { it.isNotBlank() }?.let { putExtra(LilacDownloadService.EXTRA_SUBTITLE_REFERER, it) }
            request.flixCloudPk?.takeIf { it.isNotBlank() }?.let { putExtra(LilacDownloadService.EXTRA_FLIX_PK, it) }
            request.streamHeaders?.takeIf { it.isNotBlank() }?.let { putExtra(LilacDownloadService.EXTRA_STREAM_HEADERS, it) }
        }
        android.util.Log.i(
            "OfflineDownload",
            "SERVICE_START action=${LilacDownloadService.ACTION_DOWNLOAD} episode=${request.episode.id}"
        )
        ContextCompat.startForegroundService(app, intent)
    }

    fun resumePending(context: Context) {
        val pending = MpvOfflineStore.listStatuses(context.applicationContext)
            .any { it.state == "queued" || it.state == "downloading" || it.state == "paused" }
        if (!pending) return
        ContextCompat.startForegroundService(
            context.applicationContext,
            Intent(context.applicationContext, LilacDownloadService::class.java)
        )
    }

    fun remove(context: Context, animeId: String, episodeId: String) {
        val app = context.applicationContext
        val intent = Intent(app, LilacDownloadService::class.java).apply {
            action = LilacDownloadService.ACTION_REMOVE
            putExtra(LilacDownloadService.EXTRA_ANIME_ID, animeId)
            putExtra(LilacDownloadService.EXTRA_EPISODE_ID, episodeId)
        }
        app.startService(intent)
    }

    fun cancelAll(context: Context) {
        val app = context.applicationContext
        app.startService(Intent(app, LilacDownloadService::class.java).apply {
            action = LilacDownloadService.ACTION_CANCEL_ALL
        })
    }
}
