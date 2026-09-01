package com.lilac.anime

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.edit
import com.lilac.anime.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
object OfflineStore {
    private const val PREF_NAME = "lilac_offline_store"

    suspend fun savePlayerSettings(context: Context, settings: PlayerSettings) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("pref_default_quality", settings.defaultQuality)
            putString("pref_video_source", settings.videoSourcePreference)
            putString("pref_subtitle_font", settings.subtitleFont)
            putFloat("pref_subtitle_size", settings.subtitleSize)
            putInt("pref_text_color", settings.textColor)
            putInt("pref_background_color", settings.backgroundColor)
            putInt("pref_stroke_color", settings.strokeColor)
            putLong("pref_sync_offset_ms", settings.syncOffsetMs)
            putFloat(
                "pref_subtitle_bottom_padding_fraction",
                settings.subtitleBottomPaddingFraction
            )
            putString("pref_subtitle_source", settings.subtitleSourcePreference)
            putString("pref_custom_font_path", settings.customFontPath)
            putString("pref_subtitle_font_path", settings.subtitleFontPath)
            putString("pref_subtitle_font_source", settings.subtitleFontSource)
            putBoolean("pref_show_chapter_skip_button", settings.showChapterSkipButton)
            putInt("pref_double_tap_seek_seconds", settings.doubleTapSeekSeconds)
            putFloat("pref_playback_speed", settings.playbackSpeed)
            putBoolean("pref_vtt_bold", settings.vttBold)
            putFloat("pref_vtt_outline_width", settings.vttOutlineWidth)
            apply()
        }
    }

    suspend fun getPlayerSettings(context: Context): PlayerSettings = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        // Older Csora builds temporarily applied a global +1000ms offset. That
        // offset was stored in the normal player preference, so clear only that
        // one legacy value once and then leave all future user adjustments alone.
        val syncOffset = prefs.getLong("pref_sync_offset_ms", 0L)
        val migratedLegacyCsoraSync = prefs.getBoolean("migrated_legacy_csora_sync_1000", false)
        val normalizedSyncOffset = if (!migratedLegacyCsoraSync && syncOffset == 1000L) {
            prefs.edit()
                .putLong("pref_sync_offset_ms", 0L)
                .putBoolean("migrated_legacy_csora_sync_1000", true)
                .apply()
            0L
        } else {
            if (!migratedLegacyCsoraSync) {
                prefs.edit().putBoolean("migrated_legacy_csora_sync_1000", true).apply()
            }
            syncOffset
        }

        PlayerSettings(
            defaultQuality = prefs.getString("pref_default_quality", "1080p") ?: "1080p",
            videoSourcePreference = prefs.getString("pref_video_source", "linkkf")
                ?.takeIf { it == "linkkf" || it == "animenosub" } ?: "linkkf",
            subtitleFont = prefs.getString("pref_subtitle_font", "기본체") ?: "기본체",
            subtitleSize = prefs.getFloat("pref_subtitle_size", 100f),
            textColor = prefs.getInt("pref_text_color", android.graphics.Color.WHITE),
            backgroundColor = prefs.getInt("pref_background_color", android.graphics.Color.TRANSPARENT),
            strokeColor = prefs.getInt("pref_stroke_color", android.graphics.Color.BLACK),
            syncOffsetMs = normalizedSyncOffset,
            subtitleBottomPaddingFraction = prefs.getFloat(
                "pref_subtitle_bottom_padding_fraction",
                0.12f
            ).coerceIn(0.03f, 0.45f),
            subtitleSourcePreference = prefs.getString("pref_subtitle_source", "linkkf")
                ?.takeIf { it == "linkkf" || it == "kairan" || it == "csora" } ?: "linkkf",
            customFontPath = prefs.getString("pref_custom_font_path", null),
            subtitleFontPath = prefs.getString("pref_subtitle_font_path", null),
            subtitleFontSource = prefs.getString("pref_subtitle_font_source", null),
            showChapterSkipButton = prefs.getBoolean("pref_show_chapter_skip_button", true),
            doubleTapSeekSeconds = prefs.getInt("pref_double_tap_seek_seconds", 10).coerceIn(1, 120),
            playbackSpeed = prefs.getFloat("pref_playback_speed", 1.0f).let { saved ->
                val options = floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                options.minByOrNull { kotlin.math.abs(it - saved) } ?: 1.0f
            },
            vttBold = prefs.getBoolean("pref_vtt_bold", true),
            vttOutlineWidth = prefs.getFloat("pref_vtt_outline_width", 2.0f).coerceIn(0.5f, 6.0f)
        )
    }


    suspend fun saveEpisodeSortOrder(context: Context, animeId: String, newestFirst: Boolean) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("episode_sort_newest_$animeId", newestFirst).apply()
    }

    suspend fun getEpisodeSortOrder(context: Context, animeId: String): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 기본값은 오래된화순
        prefs.getBoolean("episode_sort_newest_$animeId", false)
    }

    suspend fun saveWatchHistory(context: Context, history: List<WatchProgress>) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        history.forEach { item ->
            val json = JSONObject().apply {
                put("animeId", item.animeId)
                put("episodeNumber", item.episodeNumber)
                put("episodeKey", item.episodeKey)
                put("progress", item.progress.toDouble())
            }
            array.put(json)
        }
        prefs.edit().putString("saved_watch_history", array.toString()).apply()
    }

    suspend fun getWatchHistory(context: Context): List<WatchProgress> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString("saved_watch_history", null) ?: return@withContext emptyList()
        try {
            val array = JSONArray(jsonString)
            val list = mutableListOf<WatchProgress>()
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i)
                list.add(
                    WatchProgress(
                        animeId = json.getString("animeId"),
                        episodeNumber = json.getInt("episodeNumber"),
                        progress = json.getDouble("progress").toFloat(),
                        episodeKey = json.optString("episodeKey", json.getInt("episodeNumber").toString())
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveLibrary(context: Context, library: Set<String>) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray(library.toList())
        prefs.edit().putString("saved_library", jsonArray.toString()).apply()
    }

    suspend fun getLibrary(context: Context): Set<String> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString("saved_library", null) ?: return@withContext emptySet()
        try {
            val jsonArray = JSONArray(jsonString)
            val set = mutableSetOf<String>()
            for (i in 0 until jsonArray.length()) {
                set.add(jsonArray.getString(i))
            }
            set
        } catch (e: Exception) {
            emptySet()
        }
    }

    suspend fun saveAnimeList(context: Context, list: List<Anime>, source: String = "linkkf") = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        list.forEach { anime ->
            val json = JSONObject().apply {
                put("id", anime.id)
                put("title", anime.title)
                put("poster", anime.poster)
                put("backdrop", anime.backdrop)
                put("description", anime.description)
                put("genres", JSONArray(anime.genres))
            }
            array.put(json)
        }
        prefs.edit().putString("cached_anime_list_$source", array.toString()).apply()
    }

    suspend fun getSavedAnimeList(context: Context, source: String = "linkkf"): List<Anime> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString("cached_anime_list_$source", null) ?: return@withContext emptyList()
        try {
            val array = JSONArray(jsonString)
            val list = mutableListOf<Anime>()
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i)
                val genresJson = json.optJSONArray("genres")
                val genresList = mutableListOf<String>()
                if (genresJson != null) {
                    for (j in 0 until genresJson.length()) {
                        genresList.add(genresJson.getString(j))
                    }
                }
                list.add(
                    Anime(
                        id = json.getString("id"),
                        title = json.getString("title"),
                        poster = json.optString("poster", ""),
                        backdrop = json.optString("backdrop", ""),
                        description = json.optString("description", ""),
                        genres = genresList
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveAnime(context: Context, anime: Anime) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("id", anime.id)
            put("title", anime.title)
            put("poster", anime.poster)
            put("backdrop", anime.backdrop)
            put("description", anime.description)
            put("genres", JSONArray(anime.genres))
        }
        prefs.edit().putString("anime_${anime.id}", json.toString()).apply()
    }

    suspend fun getAnime(context: Context, animeId: String): Anime? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString("anime_$animeId", null) ?: return@withContext null
        try {
            val json = JSONObject(jsonString)
            val genresJson = json.optJSONArray("genres")
            val genresList = mutableListOf<String>()
            if (genresJson != null) {
                for (i in 0 until genresJson.length()) {
                    genresList.add(genresJson.getString(i))
                }
            }
            Anime(
                id = json.getString("id"),
                title = json.getString("title"),
                poster = json.optString("poster", ""),
                backdrop = json.optString("backdrop", ""),
                description = json.optString("description", ""),
                genres = genresList
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveEpisode(context: Context, animeId: String, episode: Episode) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 회차 표시값까지 포함된 ID를 키로 사용한다. 4화와 4a화가 서로 덮어쓰지 않는다.
        val key = "ep_${animeId}_${episode.id}"
        val json = JSONObject().apply {
            put("id", episode.id)
            put("number", episode.number)
            put("displayNumber", episode.displayNumber)
            put("title", episode.title)
            put("videoUrl", episode.videoUrl)
            put("vttUrl", episode.vttUrl)
        }
        prefs.edit().putString(key, json.toString()).apply()
    }

    suspend fun getEpisode(context: Context, animeId: String, episodeNumber: Int): Episode? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val key = "ep_${animeId}_${episodeNumber}"
        val jsonString = prefs.getString(key, null) ?: return@withContext null
        try {
            val json = JSONObject(jsonString)
            Episode(
                id = json.getString("id"),
                number = json.getInt("number"),
                title = json.getString("title"),
                displayNumber = json.optString("displayNumber", json.getInt("number").toString()),
                videoUrl = if (json.has("videoUrl") && !json.isNull("videoUrl")) json.getString("videoUrl") else null,
                vttUrl = if (json.has("vttUrl") && !json.isNull("vttUrl")) json.getString("vttUrl") else null
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getEpisode(context: Context, animeId: String, episode: Episode): Episode? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val exactKey = "ep_${animeId}_${episode.id}"
        val exactJson = prefs.getString(exactKey, null)
        if (exactJson != null) return@withContext parseEpisodeJson(exactJson)

        // 이전 버전에서 저장한 일반 회차(1, 2, 3...) 데이터와 호환한다.
        if (episode.displayNumber == episode.number.toString()) {
            getEpisode(context, animeId, episode.number)
        } else {
            null
        }
    }

    private fun parseEpisodeJson(jsonString: String): Episode? {
        return try {
            val json = JSONObject(jsonString)
            Episode(
                id = json.getString("id"),
                number = json.getInt("number"),
                title = json.getString("title"),
                displayNumber = json.optString("displayNumber", json.getInt("number").toString()),
                videoUrl = if (json.has("videoUrl") && !json.isNull("videoUrl")) json.getString("videoUrl") else null,
                vttUrl = if (json.has("vttUrl") && !json.isNull("vttUrl")) json.getString("vttUrl") else null
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getEpisodesForAnime(context: Context, animeId: String): List<Episode> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val episodes = mutableListOf<Episode>()
        val prefix = "ep_${animeId}_"

        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { key ->
            val jsonString = prefs.getString(key, null)
            if (jsonString != null) {
                try {
                    val json = JSONObject(jsonString)
                    episodes.add(
                        Episode(
                            id = json.getString("id"),
                            number = json.getInt("number"),
                            title = json.getString("title"),
                            displayNumber = json.optString("displayNumber", json.getInt("number").toString()),
                            videoUrl = if (json.has("videoUrl") && !json.isNull("videoUrl")) json.getString("videoUrl") else null,
                            vttUrl = if (json.has("vttUrl") && !json.isNull("vttUrl")) json.getString("vttUrl") else null
                        )
                    )
                } catch (_: Exception) {}
            }
        }
        episodes.distinctBy { it.id.lowercase(java.util.Locale.ROOT) }.sortedWith(compareBy<Episode> { it.number }.thenBy { it.displayNumber })
    }

    suspend fun removeEpisode(context: Context, animeId: String, episodeNumber: Int) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // Legacy API: remove the old numeric key.
        prefs.edit().remove("ep_${animeId}_${episodeNumber}").apply()
    }

    suspend fun removeEpisode(context: Context, animeId: String, episode: Episode) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("ep_${animeId}_${episode.id}").apply()
        if (episode.displayNumber == episode.number.toString()) {
            prefs.edit().remove("ep_${animeId}_${episode.number}").apply()
        }
    }
}

