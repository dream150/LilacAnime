package com.lilac.anime.data.subtitle.translation

import android.content.Context

interface SubtitleTranslator {
    suspend fun translateFile(context: Context, sourcePath: String, cacheKey: String): String?
}

/** Format-aware subtitle translator used by TranslationManager. */
class DefaultSubtitleTranslator(
    private val provider: TranslationProvider
) : SubtitleTranslator {
    override suspend fun translateFile(context: Context, sourcePath: String, cacheKey: String): String? =
        SubtitleFormatTranslator.translate(context, sourcePath, cacheKey, provider)
}
