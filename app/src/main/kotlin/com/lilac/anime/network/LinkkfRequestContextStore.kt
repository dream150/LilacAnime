package com.lilac.anime.network

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
