package com.lilac.anime.stream

data class StreamQuality(
    val label: String,
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

data class EpisodeStreamInfo(
    val qualities: List<StreamQuality>,
    val subtitleUrl: String?,
    val subtitleHeaders: Map<String, String> = emptyMap()
)
