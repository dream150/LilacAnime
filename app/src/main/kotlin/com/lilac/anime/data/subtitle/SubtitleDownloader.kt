package com.lilac.anime

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.lilac.anime.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

suspend fun downloadSubtitleFile(
    context: Context, 
    animeId: String, 
    episodeNumber: Int,
    vttUrl: String?,
    episodeKey: String = episodeNumber.toString()
): String? {
    if (vttUrl.isNullOrBlank()) return null
    return withContext(Dispatchers.IO) {
        try {
            val url = URL(vttUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            if (connection.responseCode == 200) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val safeKey = episodeKey.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")
                val file = File(context.filesDir, "sub_${animeId}_${safeKey}.vtt")
                file.writeText(text)
                file.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
