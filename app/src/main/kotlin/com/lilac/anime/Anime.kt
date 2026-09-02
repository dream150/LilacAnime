package com.lilac.anime

data class Anime(
    val id: String = "",
    val title: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val genres: List<String> = emptyList(),
    val description: String = "",
    val detailUrl: String = "",
    val episodes: List<Episode> = emptyList(),
    val dubEpisodes: List<Episode> = emptyList()
)

data class Episode(
    val id: String,
    val number: Int,
    val title: String,
    val description: String = "",
    val videoUrl: String? = null,
    val vttUrl: String? = null,
    // Linkkf 회차명이 4a, 5a처럼 숫자+문자로 제공되는 경우를 보존한다.
    // number는 기존 진행률/자막 API 호환을 위해 숫자 부분만 유지한다.
    val displayNumber: String = number.toString()
)

data class WatchProgress(
    val animeId: String,
    val episodeNumber: Int,
    val progress: Float,
    // 숫자만으로는 4화와 4a화를 구분할 수 없으므로 실제 회차 키를 함께 저장한다.
    val episodeKey: String = episodeNumber.toString()
)