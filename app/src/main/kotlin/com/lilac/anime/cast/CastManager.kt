package com.lilac.anime.cast

import android.content.Context
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession

object CastManager {
    private const val HLS = "application/x-mpegurl"

    fun currentSession(context: Context): CastSession? =
        runCatching { CastContext.getSharedInstance(context).sessionManager.currentCastSession }.getOrNull()

    fun cast(
        context: Context,
        streamUrl: String,
        title: String,
        subtitle: String?,
        positionMs: Long,
        autoplay: Boolean = true
    ): Boolean {
        val session = currentSession(context) ?: return false
        val client = session.remoteMediaClient ?: return false
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            subtitle?.takeIf { it.isNotBlank() }?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
        }
        val mediaInfo = MediaInfo.Builder(streamUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(HLS)
            .setMetadata(metadata)
            .build()
        client.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(autoplay)
                .setCurrentTime(positionMs.coerceAtLeast(0L))
                .build()
        )
        return true
    }
}
