package com.lilac.anime.data.offline

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
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
import java.io.File
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

    // Full catalog TTL: avoid requesting hundreds of catalog pages on every app launch.
    const val ANIME_LIST_CACHE_TTL_MS = 12L * 60L * 60L * 1000L

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
            putBoolean("pref_offline_oped_analysis_enabled", settings.offlineOpEdAnalysisEnabled)
            putLong("pref_double_tap_seek_seconds", settings.doubleTapSeekSeconds)
            putLong("pref_seek_button_seek_seconds", settings.seekButtonSeekSeconds)
            putFloat("pref_playback_speed", settings.playbackSpeed)
            putBoolean("pref_auto_play", settings.autoPlay)
            putBoolean("pref_auto_skip", settings.autoSkip)
            putBoolean("pref_vtt_style_enabled", settings.vttStyleEnabled)
            putBoolean("pref_vtt_bold", settings.vttBold)
            putFloat("pref_vtt_outline_width", settings.vttOutlineWidth)
            putBoolean("pref_ass_effects_enabled", settings.assEffectsEnabled)
            putBoolean("pref_translation_auto_enabled", settings.translationAutoEnabled)
            putString("pref_translation_provider", settings.translationProvider)
            putString("pref_translation_model_id", settings.translationModelId)
            putString("pref_translation_prompt", settings.translationPrompt)
            putInt("pref_ai_context_size", settings.aiContextSize)
            putInt("pref_ai_threads", settings.aiThreads)
            putInt("pref_ai_max_tokens", settings.aiMaxTokens)
            putFloat("pref_ai_temperature", settings.aiTemperature)
            putFloat("pref_ai_top_p", settings.aiTopP)
            putInt("pref_ai_top_k", settings.aiTopK)
            putFloat("pref_ai_repetition_penalty", settings.aiRepetitionPenalty)
            putInt("pref_ai_context_cues", settings.aiContextCues)
            putBoolean("pref_ai_prefetch_enabled", settings.aiPrefetchEnabled)
            putInt("pref_ai_prefetch_ahead", settings.aiPrefetchAhead)
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
                ?.takeIf { it in setOf("linkkf", "animenosub", "reanime") } ?: "linkkf",
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
                ?.takeIf { it in setOf("linkkf", "reanime", "jimaku", "kairan", "csora", "user") } ?: "linkkf",
            customFontPath = prefs.getString("pref_custom_font_path", null),
            subtitleFontPath = prefs.getString("pref_subtitle_font_path", null),
            subtitleFontSource = prefs.getString("pref_subtitle_font_source", null),
            showChapterSkipButton = prefs.getBoolean("pref_show_chapter_skip_button", true),
            offlineOpEdAnalysisEnabled = prefs.getBoolean("pref_offline_oped_analysis_enabled", true),
            doubleTapSeekSeconds = run {
                val saved = try {
                    prefs.getLong("pref_double_tap_seek_seconds", Long.MIN_VALUE)
                } catch (_: ClassCastException) {
                    Long.MIN_VALUE
                }
                if (saved != Long.MIN_VALUE) saved
                else prefs.getInt("pref_double_tap_seek_seconds", 10).toLong()
            },
            seekButtonSeekSeconds = run {
                val saved = try {
                    prefs.getLong("pref_seek_button_seek_seconds", Long.MIN_VALUE)
                } catch (_: ClassCastException) {
                    Long.MIN_VALUE
                }
                if (saved != Long.MIN_VALUE) saved else 10L
            },
            playbackSpeed = prefs.getFloat("pref_playback_speed", 1.0f).let { saved ->
                val options = floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                options.minByOrNull { kotlin.math.abs(it - saved) } ?: 1.0f
            },
            autoPlay = prefs.getBoolean("pref_auto_play", true),
            autoSkip = prefs.getBoolean("pref_auto_skip", true),
            vttStyleEnabled = prefs.getBoolean("pref_vtt_style_enabled", true),
            vttBold = prefs.getBoolean("pref_vtt_bold", true),
            vttOutlineWidth = prefs.getFloat("pref_vtt_outline_width", 2.0f).coerceIn(0.5f, 6.0f),
            assEffectsEnabled = prefs.getBoolean("pref_ass_effects_enabled", true),
            translationAutoEnabled = prefs.getBoolean("pref_translation_auto_enabled", false),
            translationProvider = prefs.getString("pref_translation_provider", "local")
                ?.takeIf { it in setOf("local", "openai", "deepl", "qwen") } ?: "local",
            translationModelId = prefs.getString("pref_translation_model_id", null),
            translationPrompt = prefs.getString("pref_translation_prompt", null)
                ?.takeIf { it.isNotBlank() && !it.contains("LILAC_N") }
                ?.let { saved ->
                    if (saved == "Translate the following subtitle segment into Korean, without additional explanation.\n\nPreserve all subtitle formatting, timing, positioning, styling, effect, control, and metadata tags exactly as they are. Do not translate, remove, rename, reorder, or modify any tags, tag parameters, timestamps, escape sequences, or special characters. Preserve line breaks and the original structure. Translate only natural-language subtitle text. Output only the translated subtitle.\n\n{source_text}") com.lilac.anime.data.subtitle.translation.providers.LocalAiTranslationRuntime.DEFAULT_PROMPT else saved
                }
                ?: com.lilac.anime.data.subtitle.translation.providers.LocalAiTranslationRuntime.DEFAULT_PROMPT,
            aiContextSize = prefs.getInt("pref_ai_context_size", 4096).coerceIn(1024, 16384),
            aiThreads = prefs.getInt("pref_ai_threads", 0).coerceIn(0, 12),
            aiMaxTokens = prefs.getInt("pref_ai_max_tokens", 512).coerceIn(256, 4096),
            aiTemperature = prefs.getFloat("pref_ai_temperature", 0.25f).coerceIn(0.0f, 2.0f),
            aiTopP = prefs.getFloat("pref_ai_top_p", 0.85f).coerceIn(0.05f, 1.0f),
            aiTopK = prefs.getInt("pref_ai_top_k", 40).coerceIn(1, 100),
            aiRepetitionPenalty = prefs.getFloat("pref_ai_repetition_penalty", 1.05f).coerceIn(1.0f, 1.5f),
            aiContextCues = prefs.getInt("pref_ai_context_cues", 6).coerceIn(0, 10),
            aiPrefetchEnabled = prefs.getBoolean("pref_ai_prefetch_enabled", true),
            aiPrefetchAhead = prefs.getInt("pref_ai_prefetch_ahead", 10).coerceIn(0, 40)
        )
    }


    /**
     * Persist AniSkip OP/ED timestamps with the actual offline episode.
     *
     * Primary storage is aniskip.json next to episode.mp4. The SharedPreferences
     * copy is kept as a compatibility fallback for episodes saved by older builds.
     */
    suspend fun saveChapterSkipSegments(
        context: Context,
        animeId: String,
        episodeId: String,
        segments: List<com.lilac.anime.core.model.ChapterSkipSegment>
    ) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        segments.forEach { segment ->
            array.put(JSONObject().apply {
                put("type", segment.type)
                put("startTime", segment.startTime)
                put("endTime", segment.endTime)
                put("episodeLength", segment.episodeLength)
            })
        }
        val raw = array.toString()

        val dir = MpvOfflineStore.episodeDir(context, animeId, episodeId)
        dir.mkdirs()
        val target = File(dir, "aniskip.json")
        val temp = File(dir, "aniskip.json.part")
        runCatching {
            temp.writeText(raw, Charsets.UTF_8)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                target.writeText(raw, Charsets.UTF_8)
                temp.delete()
            }
        }.getOrThrow()

        // Legacy/quick lookup copy. This also preserves compatibility with
        // builds that already created chapter_skip_* entries.
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString("chapter_skip_${animeId}_${episodeId}", raw).apply()
    }

    suspend fun getChapterSkipSegments(
        context: Context,
        animeId: String,
        episodeId: String
    ): List<com.lilac.anime.core.model.ChapterSkipSegment> = withContext(Dispatchers.IO) {
        val file = File(MpvOfflineStore.episodeDir(context, animeId, episodeId), "aniskip.json")
        val raw = if (file.isFile && file.length() > 0L) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString("chapter_skip_${animeId}_${episodeId}", null)
        } ?: return@withContext emptyList()

        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val json = array.optJSONObject(i) ?: continue
                    val type = json.optString("type")
                    val start = json.optDouble("startTime", Double.NaN)
                    val end = json.optDouble("endTime", Double.NaN)
                    val length = json.optDouble("episodeLength", 0.0)
                    if (type.isNotBlank() && start.isFinite() && end.isFinite() && end > start) {
                        add(com.lilac.anime.core.model.ChapterSkipSegment(type, start, end, length))
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    suspend fun removeChapterSkipSegments(context: Context, animeId: String, episodeId: String) = withContext(Dispatchers.IO) {
        File(MpvOfflineStore.episodeDir(context, animeId, episodeId), "aniskip.json").delete()
        File(MpvOfflineStore.episodeDir(context, animeId, episodeId), "aniskip.json.part").delete()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().remove("chapter_skip_${animeId}_${episodeId}").apply()
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

    suspend fun saveAnimeList(
        context: Context,
        list: List<Anime>,
        source: String = "linkkf",
        markFresh: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        list.forEach { anime ->
            val json = JSONObject().apply {
                put("id", anime.id)
                put("anilistId", anime.anilistId ?: JSONObject.NULL)
                put("malId", anime.malId ?: JSONObject.NULL)
                put("title", anime.title)
                put("poster", anime.poster)
                put("backdrop", anime.backdrop)
                put("description", anime.description)
                put("genres", JSONArray(anime.genres))
                put("detailUrl", anime.detailUrl)
                put("source", anime.source)
                put("romaji", anime.romaji)
                put("english", anime.english)
                put("native", anime.native)
                put("synonyms", anime.synonyms)
            }
            array.put(json)
        }
        val editor = prefs.edit().putString("cached_anime_list_$source", array.toString())
        if (markFresh) {
            editor.putLong("cached_anime_list_time_$source", System.currentTimeMillis())
        }
        editor.apply()
    }

    /**
     * Merge a newly fetched catalog batch into the persisted catalog without
     * replacing existing entries. The existing order is preserved and new
     * entries are appended in the order returned by the source.
     *
     * Used by Re:Anime's incremental/background catalog loader so every page
     * can become available even if the full crawl is still running.
     */
    suspend fun mergeAnimeListCache(
        context: Context,
        list: List<Anime>,
        source: String = "linkkf",
        markFresh: Boolean = false,
        newFirst: Boolean = false
    ) = withContext(Dispatchers.IO) {
        if (list.isEmpty()) return@withContext
        val existing = getSavedAnimeList(context, source)
        val byId = LinkedHashMap<String, Anime>()
        if (!newFirst) existing.forEach { byId[it.id] = it }
        list.forEach { byId[it.id] = it }
        if (newFirst) {
            existing.forEach { if (!byId.containsKey(it.id)) byId[it.id] = it }
        }
        saveAnimeList(context, byId.values.toList(), source, markFresh)
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
                        anilistId = if (json.isNull("anilistId")) null else json.optInt("anilistId").takeIf { it > 0 },
                        malId = if (json.isNull("malId")) null else json.optInt("malId").takeIf { it > 0 },
                        title = json.getString("title"),
                        poster = json.optString("poster", ""),
                        backdrop = json.optString("backdrop", ""),
                        description = json.optString("description", ""),
                        genres = genresList,
                        detailUrl = json.optString("detailUrl", ""),
                        source = json.optString("source", ""),
                        romaji = json.optString("romaji", ""),
                        english = json.optString("english", ""),
                        native = json.optString("native", ""),
                        synonyms = json.optString("synonyms", "")
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
            anime.anilistId?.let { put("anilistId", it) }
            anime.malId?.let { put("malId", it) }
            put("title", anime.title)
            put("poster", anime.poster)
            put("backdrop", anime.backdrop)
            put("description", anime.description)
            put("genres", JSONArray(anime.genres))
        }
        prefs.edit().putString("anime_${anime.id}", json.toString()).apply()
    }

    suspend fun isAnimeListCacheFresh(context: Context, source: String = "linkkf"): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val cachedAt = prefs.getLong("cached_anime_list_time_$source", 0L)
        if (cachedAt <= 0L) return@withContext false
        val age = System.currentTimeMillis() - cachedAt
        age in 0..ANIME_LIST_CACHE_TTL_MS
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
                anilistId = json.optInt("anilistId", 0).takeIf { it > 0 },
                malId = json.optInt("malId", 0).takeIf { it > 0 },
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
            put("nativeTitle", episode.nativeTitle)
            put("airedDate", episode.airedDate)
            put("isFiller", episode.isFiller)
            put("isRecap", episode.isRecap)
            put("playable", episode.playable)
            put("subbed", episode.subbed)
            put("dubbed", episode.dubbed)
            put("thumbnailUrl", episode.thumbnailUrl)
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
                vttUrl = if (json.has("vttUrl") && !json.isNull("vttUrl")) json.getString("vttUrl") else null,
                nativeTitle = json.optString("nativeTitle", ""),
                airedDate = json.optString("airedDate", ""),
                isFiller = json.optBoolean("isFiller", false),
                isRecap = json.optBoolean("isRecap", false),
                playable = json.optBoolean("playable", true),
                subbed = json.optBoolean("subbed", false),
                dubbed = json.optBoolean("dubbed", false),
                thumbnailUrl = json.optString("thumbnailUrl", "")
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
                vttUrl = if (json.has("vttUrl") && !json.isNull("vttUrl")) json.getString("vttUrl") else null,
                nativeTitle = json.optString("nativeTitle", ""),
                airedDate = json.optString("airedDate", ""),
                isFiller = json.optBoolean("isFiller", false),
                isRecap = json.optBoolean("isRecap", false),
                playable = json.optBoolean("playable", true),
                subbed = json.optBoolean("subbed", false),
                dubbed = json.optBoolean("dubbed", false),
                thumbnailUrl = json.optString("thumbnailUrl", "")
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

