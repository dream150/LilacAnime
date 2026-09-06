package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
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
                    Log.w("Subtitle", "LINKKF_VTT_HTTP code=${response.code} referer=${candidateRef ?: "<none>"} url=$vttUrl")
                    return@use
                }

                val bytes = response.body?.bytes() ?: return@use
                if (bytes.isEmpty()) return@use
                Log.d("Subtitle", "LINKKF_VTT_RESPONSE code=${response.code} type=${response.header("Content-Type")} bytes=${bytes.size} referer=${candidateRef ?: "<none>"} origin=$sendOrigin")

                // Reject an HTML error/challenge page masquerading as a successful response.
                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                val first = text.trimStart().lowercase(Locale.ROOT)
                if (first.startsWith("<!doctype html") || first.startsWith("<html") || first.startsWith("<head")) {
                    Log.w("Subtitle", "LINKKF_VTT_HTML_RESPONSE referer=$candidateRef url=$vttUrl")
                    return@use
                }

                val safeKey = episodeKey.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")
                val normalized = text.trimStart()
                val payload = if (normalized.startsWith("WEBVTT", ignoreCase = true)) {
                    if (text.startsWith("WEBVTT")) text.toByteArray(Charsets.UTF_8)
                    else ("WEBVTT\n\n" + normalized.removePrefix("WEBVTT")).toByteArray(Charsets.UTF_8)
                } else bytes
                val file = File(context.filesDir, "sub_${animeId}_ep_${safeKey}.vtt")
                file.writeBytes(payload)

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
