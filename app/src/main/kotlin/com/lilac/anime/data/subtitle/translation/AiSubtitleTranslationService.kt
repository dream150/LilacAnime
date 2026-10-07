package com.lilac.anime.data.subtitle.translation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single entry point for cloud/local AI subtitle translation.
 * Player code should call this facade instead of knowing provider implementations.
 */
object AiSubtitleTranslationService {
    data class Result(
        val outputPath: String,
        val providerId: String,
        val fromCache: Boolean = false
    )

    suspend fun translateFile(
        context: Context,
        sourcePath: String,
        animeId: String,
        episodeKey: String,
        providerId: String? = null
    ): Result? = withContext(Dispatchers.IO) {
        val provider = providerId?.takeIf { it.isNotBlank() }
            ?: context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
                .getString("pref_translation_provider", "local")
                .orEmpty()
                .ifBlank { "local" }

        val source = File(sourcePath)
        if (!source.isFile || source.length() == 0L) return@withContext null

        val output = TranslationManager.translateFile(
            context = context,
            sourcePath = source.absolutePath,
            animeId = animeId,
            episodeKey = episodeKey,
            providerId = provider
        ) ?: return@withContext null

        Result(outputPath = output, providerId = provider)
    }

    suspend fun translateText(
        context: Context,
        text: String,
        providerId: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val provider = providerId?.takeIf { it.isNotBlank() }
            ?: context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
                .getString("pref_translation_provider", "local")
                .orEmpty()
                .ifBlank { "local" }
        TranslationManager.translateText(context, provider, text)
    }

    suspend fun test(context: Context, providerId: String? = null): kotlin.Result<String> {
        val provider = providerId?.takeIf { it.isNotBlank() }
            ?: context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
                .getString("pref_translation_provider", "local")
                .orEmpty()
                .ifBlank { "local" }
        return TranslationManager.test(context, provider)
    }
}
