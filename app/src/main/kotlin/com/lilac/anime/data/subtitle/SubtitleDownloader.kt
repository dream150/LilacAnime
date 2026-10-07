package com.lilac.anime.data.subtitle

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
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
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Downloads protected external subtitles with the same browser context as Linkkf's player. */
suspend fun downloadSubtitleFile(
    context: Context,
    animeId: String,
    episodeNumber: Int,
    vttUrl: String?,
    episodeKey: String = episodeNumber.toString(),
    referer: String? = null,
    source: String = "linkkf"
): String? = withContext(Dispatchers.IO) {
    if (vttUrl.isNullOrBlank()) return@withContext null

    val ref = referer?.trim()?.takeIf { it.isNotBlank() }
    val origin = ref?.let {
        runCatching { java.net.URI(it).let { uri -> "${uri.scheme}://${uri.host}" } }.getOrNull()
    }

    val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"

    // Subtitle Referer is intentionally independent from the video Referer.
    // Try the exact captured Referer first. Some CDN configurations reject an Origin
    // header on VTT even though they accept the browser's Referer, so retry without Origin.
    val attempts = if (ref.isNullOrBlank()) listOf<Pair<String?, Boolean>>(null to false)
                   else listOf(ref to true, ref to false)

    for ((candidateRef, sendOrigin) in attempts) {
        try {
            val candidateOrigin = if (sendOrigin) candidateRef?.let { refValue ->
                runCatching {
                    java.net.URI(refValue).let { "${it.scheme}://${it.host}" }
                }.getOrNull()
            } else null

            val browserCookie = runCatching {
                CookieManager.getInstance().getCookie(vttUrl)
            }.getOrNull()?.takeIf { it.isNotBlank() }

            val request = Request.Builder()
                .url(vttUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "text/vtt,text/plain,*/*;q=0.8")
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .apply {
                    if (!candidateRef.isNullOrBlank()) header("Referer", candidateRef)
                    if (!candidateOrigin.isNullOrBlank()) header("Origin", candidateOrigin)
                    if (!browserCookie.isNullOrBlank()) header("Cookie", browserCookie)
                }
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("Subtitle", "REMOTE_SUBTITLE_HTTP source=$source code=${response.code} referer=${candidateRef ?: "<none>"} url=$vttUrl")
                    return@use
                }

                val bytes = response.body?.bytes() ?: return@use
                if (bytes.isEmpty()) return@use
                Log.d("Subtitle", "REMOTE_SUBTITLE_RESPONSE source=$source code=${response.code} type=${response.header("Content-Type")} bytes=${bytes.size} referer=${candidateRef ?: "<none>"} origin=$sendOrigin")

                // Reject an HTML error/challenge page masquerading as a successful response.
                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                val first = text.trimStart().lowercase(Locale.ROOT)
                if (first.startsWith("<!doctype html") || first.startsWith("<html") || first.startsWith("<head")) {
                    Log.w("Subtitle", "REMOTE_SUBTITLE_HTML_RESPONSE source=$source referer=$candidateRef url=$vttUrl")
                    return@use
                }

                val safeKey = episodeKey.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")
                val safeSource = source.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")
                val normalized = text.trimStart()
                val isWebVtt = normalized.startsWith("WEBVTT", ignoreCase = true)
                val isSrt = Regex("(?m)^\\d+\\s*$").containsMatchIn(normalized) &&
                    normalized.contains(" --> ")
                val extension = if (isSrt && !isWebVtt) "srt" else "vtt"
                val payload = if (isWebVtt) {
                    if (text.startsWith("WEBVTT")) text.toByteArray(Charsets.UTF_8)
                    else ("WEBVTT\n\n" + normalized.removePrefix("WEBVTT")).toByteArray(Charsets.UTF_8)
                } else bytes

                // Keep the local cache filename deterministic for playback, but also
                // remember the real remote subtitle filename so the UI can show the
                // useful source filename instead of sub_<animeId>_ep_....vtt.
                val remoteName = extractRemoteSubtitleName(
                    response.header("Content-Disposition"),
                    vttUrl,
                    extension
                )
                val file = File(context.filesDir, "sub_${animeId}_ep_${safeKey}_${safeSource}.$extension")
                file.writeBytes(payload)
                SubtitleStore.setDisplayName(context, file.absolutePath, remoteName)

                Log.d("Subtitle", "REMOTE_SUBTITLE_SAVED source=$source path=${file.absolutePath} displayName=$remoteName bytes=${file.length()} referer=$candidateRef")
                return@withContext file.absolutePath
            }
        } catch (e: Exception) {
            Log.w("Subtitle", "REMOTE_SUBTITLE_ATTEMPT_FAILED source=$source referer=$candidateRef url=$vttUrl", e)
        }
    }

    Log.e("Subtitle", "REMOTE_SUBTITLE_DOWNLOAD_FAILED source=$source url=$vttUrl")
    null
}

private fun extractRemoteSubtitleName(contentDisposition: String?, subtitleUrl: String, extension: String): String {
    val fromDisposition = contentDisposition?.let { header ->
        Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.getOrNull(1)
            ?: Regex("""filename=\"?([^;\"]+)\"?""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.getOrNull(1)
    }?.let { runCatching { URLDecoder.decode(it.trim(), "UTF-8") }.getOrNull() }

    val fromUrl = runCatching {
        java.net.URI(subtitleUrl).path.substringAfterLast('/').takeIf { it.isNotBlank() }
    }.getOrNull()

    val candidate = (fromDisposition ?: fromUrl ?: "subtitle.$extension")
        .substringAfterLast('/')
        .trim()
        .replace(Regex("[\\r\\n]"), "_")
        .take(180)
    return if (candidate.contains('.')) candidate else "$candidate.$extension"
}
