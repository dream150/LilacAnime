package com.lilac.anime

import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
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

data class Anime(
    val id: String = "",
    /** AniList media ID supplied by Linkkf.app, when available. */
    val anilistId: Int? = null,
    /** MyAnimeList ID supplied directly by a source, when available. */
    val malId: Int? = null,
    val title: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val genres: List<String> = emptyList(),
    val description: String = "",
    // Linkkf detail metadata (single.php / singlefilter.php)
    val airedDate: String = "",
    val year: String = "",
    val format: String = "",
    val studios: List<String> = emptyList(),
    val source: String = "",
    val romaji: String = "",
    val english: String = "",
    val native: String = "",
    val synonyms: String = "",
    val note: String = "",
    val seasonTypeTagIds: List<Int> = emptyList(),
    val studioTagIds: List<Int> = emptyList(),
    val sourceTagIds: List<Int> = emptyList(),
    val yearTagId: Int? = null,
    val seriesTagIds: List<Int> = emptyList(),
    val detailUrl: String = "",
    val episodes: List<Episode> = emptyList(),
    val dubEpisodes: List<Episode> = emptyList(),
    /** Re:Anime detail-page relations (prequel/sequel/side story/etc.). */
    val reAnimeRelated: List<ReAnimeRelated> = emptyList()
)

data class ReAnimeRelated(
    val id: String,
    val title: String,
    val nativeTitle: String = "",
    val romaji: String = "",
    val poster: String = "",
    val format: String = "",
    val relationType: String = "",
    val season: String = "",
    val seasonYear: Int? = null
)

data class Episode(
    val id: String,
    val number: Int,
    val title: String,
    val description: String = "",
    val videoUrl: String? = null,
    val vttUrl: String? = null,
    // Linkkf/Re:Anime 회차명이 4a, 5a처럼 숫자+문자로 제공되는 경우를 보존한다.
    // number는 기존 진행률/자막 API 호환을 위해 숫자 부분만 유지한다.
    val displayNumber: String = number.toString(),
    // Re:Anime 상세회차에서 제공하는 추가 메타데이터.
    val nativeTitle: String = "",
    val airedDate: String = "",
    val isFiller: Boolean = false,
    val isRecap: Boolean = false,
    val playable: Boolean = true,
    val subbed: Boolean = false,
    val dubbed: Boolean = false,
    val thumbnailUrl: String = ""
)

data class WatchProgress(
    val animeId: String,
    val episodeNumber: Int,
    val progress: Float,
    // 숫자만으로는 4화와 4a화를 구분할 수 없으므로 실제 회차 키를 함께 저장한다.
    val episodeKey: String = episodeNumber.toString()
)