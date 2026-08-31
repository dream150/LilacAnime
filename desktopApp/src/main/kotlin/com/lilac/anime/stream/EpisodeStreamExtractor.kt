package com.lilac.anime.stream

interface EpisodeStreamExtractor {
    suspend fun extract(targetUrl: String): EpisodeStreamInfo
}
