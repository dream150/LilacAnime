package com.lilac.anime.data.subtitle.translation

interface TranslationProvider {
    val id: String
    val displayName: String
    suspend fun translateBatch(lines: List<String>): List<String>
}

interface TranslationSessionProvider : TranslationProvider {
    suspend fun beginSession()
    suspend fun endSession()
}
