package com.lilac.anime.stream

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

object SubtitleDownloader {

    fun download(
        url: String,
        destinationDirectory: Path,
        fileName: String = "subtitle.vtt",
        headers: Map<String, String> = emptyMap()
    ): Path? {
        if (url.isBlank()) return null

        Files.createDirectories(destinationDirectory)
        val destination = destinationDirectory.resolve(fileName)
        val connection = URI(url).toURL().openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                headers["User-Agent"] ?: DEFAULT_USER_AGENT
            )

            headers.forEach { (key, value) ->
                if (!key.equals("User-Agent", true)) {
                    connection.setRequestProperty(key, value)
                }
            }

            if (connection.responseCode !in 200..299) return null

            connection.inputStream.use { input ->
                Files.newOutputStream(destination).use { output -> input.copyTo(output) }
            }

            destination
        } finally {
            connection.disconnect()
        }
    }

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"
}
