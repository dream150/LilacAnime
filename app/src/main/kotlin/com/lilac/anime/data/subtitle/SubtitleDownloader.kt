package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Downloads protected external subtitles with the same browser context as Linkkf's player. */
suspend fun downloadSubtitleFile(
    context: Context,
    animeId: String,
    episodeNumber: Int,
    vttUrl: String?,
    episodeKey: String = episodeNumber.toString(),
    referer: String? = null
): String? = withContext(Dispatchers.IO) {
    if (vttUrl.isNullOrBlank()) return@withContext null

    val ref = referer?.takeIf { it.isNotBlank() } ?: "https://playv2.sub3.top/"
    val origin = runCatching {
        java.net.URI(ref).let { "${it.scheme}://${it.host}" }
    }.getOrNull() ?: "https://playv2.sub3.top"

    val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"

    // Linkkf video and subtitle are commonly split across playv2.sub3.top and
    // k1.sub1.top. Try the normal player referer first, then the subtitle host's
    // own origin. A working response is cached locally and mpv never needs to
    // contact the protected VTT URL directly.
    val referers = linkedSetOf(
        ref,
        "https://playv2.sub3.top/",
        "https://k1.sub1.top/"
    )

    for (candidateRef in referers) {
        try {
            val candidateOrigin = runCatching {
                java.net.URI(candidateRef).let { "${it.scheme}://${it.host}" }
            }.getOrNull() ?: origin

            val request = Request.Builder()
                .url(vttUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "text/vtt,text/plain,*/*;q=0.8")
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Referer", candidateRef)
                .header("Origin", candidateOrigin)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("Subtitle", "LINKKF_VTT_HTTP code=${response.code} referer=$candidateRef url=$vttUrl")
                    return@use
                }

                val bytes = response.body?.bytes() ?: return@use
                if (bytes.isEmpty()) return@use

                // Reject an HTML error/challenge page masquerading as a successful response.
                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                val first = text.trimStart().lowercase(Locale.ROOT)
                if (first.startsWith("<!doctype html") || first.startsWith("<html") || first.startsWith("<head")) {
                    Log.w("Subtitle", "LINKKF_VTT_HTML_RESPONSE referer=$candidateRef url=$vttUrl")
                    return@use
                }

                val safeKey = episodeKey.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")
                val file = File(context.filesDir, "sub_${animeId}_${safeKey}.vtt")
                file.writeBytes(if (text.startsWith("WEBVTT")) text.toByteArray(Charsets.UTF_8) else bytes)

                Log.d("Subtitle", "LINKKF_VTT_SAVED path=${file.absolutePath} bytes=${file.length()} referer=$candidateRef")
                return@withContext file.absolutePath
            }
        } catch (e: Exception) {
            Log.w("Subtitle", "LINKKF_VTT_ATTEMPT_FAILED referer=$candidateRef url=$vttUrl", e)
        }
    }

    Log.e("Subtitle", "LINKKF_VTT_DOWNLOAD_FAILED url=$vttUrl")
    null
}
