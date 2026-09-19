package com.lilac.anime.network

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
import com.lilac.anime.data.subtitle.*
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

/** Stores the exact Linkkf player page used as Referer for a specific episode. */
object LinkkfRequestContextStore {
    private const val PREFS = "linkkf_request_context"
    private const val PREFIX = "referer::"
    private const val SUBTITLE_PREFIX = "subtitle_referer::"
    private const val SUBTITLE_URL_PREFIX = "subtitle_url::"

    fun save(context: Context, animeId: String, episodeId: String, referer: String?) {
        val value = referer?.trim()?.takeIf { it.isNotBlank() } ?: return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(animeId, episodeId), value)
            .apply()
    }

    fun saveSubtitle(context: Context, animeId: String, episodeId: String, referer: String?) {
        val value = referer?.trim()?.takeIf { it.isNotBlank() } ?: return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(subtitleKey(animeId, episodeId), value)
            .apply()
    }

    fun saveSubtitleUrl(context: Context, animeId: String, episodeId: String, url: String?) {
        val value = url?.trim()?.takeIf { it.isNotBlank() } ?: return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(subtitleUrlKey(animeId, episodeId), value)
            .apply()
    }

    fun getSubtitleUrl(context: Context, animeId: String, episodeId: String): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(subtitleUrlKey(animeId, episodeId), null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun getSubtitle(context: Context, animeId: String, episodeId: String): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(subtitleKey(animeId, episodeId), null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun get(context: Context, animeId: String, episodeId: String): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(animeId, episodeId), null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun key(animeId: String, episodeId: String): String =
        PREFIX + animeId + "::" + episodeId

    private fun subtitleKey(animeId: String, episodeId: String): String =
        SUBTITLE_PREFIX + animeId + "::" + episodeId

    private fun subtitleUrlKey(animeId: String, episodeId: String): String =
        SUBTITLE_URL_PREFIX + animeId + "::" + episodeId
}
