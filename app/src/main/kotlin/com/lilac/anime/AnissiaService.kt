package com.lilac.anime

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class AnissiaSubtitle(
    val episode: String,
    val updateDate: String,
    val website: String?,
    val creator: String
)

data class AnissiaAnime(
    val animeNo: Long,
    val subject: String,
    val originalSubject: String?
)

object AnissiaService {

    private const val TAG = "Anissia"

    private suspend fun get(urlString: String): String =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "REQUEST $urlString")

            val connection =
                URL(urlString).openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )
                connection.setRequestProperty(
                    "User-Agent",
                    "LilacAnime/1.0"
                )

                val code = connection.responseCode
                val body = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use {
                        it.readText()
                    }
                } else {
                    connection.errorStream?.bufferedReader()?.use {
                        it.readText()
                    } ?: ""
                }

                Log.d(TAG, "HTTP=$code length=${body.length}")

                if (code !in 200..299) {
                    throw IllegalStateException(
                        "Anissia HTTP $code"
                    )
                }

                body
            } finally {
                connection.disconnect()
            }
        }

    suspend fun getSchedule(day: Int): List<AnissiaAnime> {
        require(day in 0..8)

        val body = get(
            "https://api.anissia.net/anime/schedule/$day"
        )

        val result = mutableListOf<AnissiaAnime>()
        val array = JSONArray(body)

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)

            val animeNo =
                item.optLong("animeNo", -1L)

            if (animeNo <= 0L) continue

            result += AnissiaAnime(
                animeNo = animeNo,
                subject = item.optString("subject"),
                originalSubject =
                    item.optString("originalSubject")
                        .takeIf { it.isNotBlank() }
            )
        }

        Log.d(
            TAG,
            "SCHEDULE day=$day count=${result.size}"
        )

        return result
    }

    suspend fun findAnime(
        title: String
    ): AnissiaAnime? {
        val cleaned = cleanTitle(title)

        Log.d(
            TAG,
            "FIND title=$title cleaned=$cleaned"
        )

        for (day in 0..8) {
            try {
                val list = getSchedule(day)

                val exact = list.firstOrNull {
                    cleanTitle(it.subject)
                        .equals(cleaned, ignoreCase = true) ||
                    cleanTitle(it.originalSubject ?: "")
                        .equals(cleaned, ignoreCase = true)
                }

                if (exact != null) {
                    Log.d(
                        TAG,
                        "FOUND animeNo=${exact.animeNo} subject=${exact.subject}"
                    )
                    return exact
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "SCHEDULE ERROR day=$day",
                    e
                )
            }
        }

        return null
    }

    suspend fun getSubtitles(
        animeNo: Long
    ): List<AnissiaSubtitle> {
        val url =
            "https://api.anissia.net/anime/caption/animeNo/$animeNo"

        val body = get(url)

        val array = JSONArray(body)
        val result = mutableListOf<AnissiaSubtitle>()

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)

            val episode =
                item.optString("episode")

            if (episode.isBlank()) continue

            result += AnissiaSubtitle(
                episode = episode,
                updateDate = item.optString("updDt"),
                website =
                    item.optString("website")
                        .takeIf { it.isNotBlank() },
                creator = item.optString("name")
            )
        }

        result.sortWith(
            compareBy<AnissiaSubtitle> {
                episodeNumber(it.episode)
            }.thenByDescending {
                it.updateDate
            }
        )

        Log.d(
            TAG,
            "CAPTION animeNo=$animeNo count=${result.size}"
        )

        result.forEach {
            Log.d(
                TAG,
                "CAPTION episode=${it.episode} " +
                    "creator=${it.creator} " +
                    "date=${it.updateDate} " +
                    "website=${it.website}"
            )
        }

        return result
    }

    suspend fun getEpisodeSubtitle(
        animeNo: Long,
        episode: String
    ): AnissiaSubtitle? {
        val subtitles = getSubtitles(animeNo)

        val target =
            episode.toDoubleOrNull()

        if (target == null) {
            return subtitles.firstOrNull {
                it.episode.equals(
                    episode,
                    ignoreCase = true
                )
            }
        }

        return subtitles
            .filter {
                it.episode.toDoubleOrNull() != null
            }
            .minByOrNull {
                kotlin.math.abs(
                    it.episode.toDouble() - target
                )
            }
    }

    private fun episodeNumber(
        value: String
    ): Double {
        return value
            .toDoubleOrNull()
            ?: Double.MAX_VALUE
    }

    private fun cleanTitle(
        value: String
    ): String {
        return value
            .replace(
                Regex(
                    """\[[^\]]*]"""
                ),
                ""
            )
            .replace(
                Regex(
                    """\([^)]*\)"""
                ),
                ""
            )
            .replace(
                Regex(
                    """【[^】]*】"""
                ),
                ""
            )
            .replace(
                Regex(
                    """[^\p{L}\p{N}\s]"""
                ),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }
}