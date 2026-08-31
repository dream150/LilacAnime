package com.lilac.anime

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.lilac.anime.data.*
@OptIn(UnstableApi::class)
fun startEpisodeDownload(
    context: Context, 
    animeId: String, 
    animeTitle: String, 
    episode: Episode, 
    streamUrl: String
) {
    val mimeType = if (streamUrl.contains(".m3u8")) {
        MimeTypes.APPLICATION_M3U8
    } else {
        MimeTypes.VIDEO_MP4
    }

    val displayTitle = "$animeTitle - ${episode.displayNumber}화"
    val customData = displayTitle.toByteArray(Charsets.UTF_8)
    // displayNumber까지 포함된 Episode.id를 사용해 4화와 4a화를 완전히 별도 다운로드로 취급한다.
    val downloadId = episode.id

    val downloadRequest = DownloadRequest.Builder(
        downloadId,
        Uri.parse(streamUrl)
    )
    .setMimeType(mimeType)
    .setData(customData)
    .build()

    DownloadService.sendAddDownload(
        context,
        LilacDownloadService::class.java,
        downloadRequest,
        false
    )

}
