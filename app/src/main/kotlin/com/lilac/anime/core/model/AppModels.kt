package com.lilac.anime.core.model

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.update.*
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

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.lilac.anime.data.*
data class StreamQuality(
    val label: String,
    val url: String,
    val referer: String? = null,
    // Headers captured from the WebView request that produced this stream.
    // Protected HLS providers may require the same User-Agent/Cookie as the browser.
    val headers: String? = null,
    // FlixCloud's custom hls.js exposes this browser-side manifest XOR key as
    // window.__pk. It is carried with the detected quality so playback can
    // transparently proxy/decrypt the HLS responses in libmpv.
    val flixCloudPk: String? = null
)

data class PlayerSettings(
    val defaultQuality: String = "1080p",
    // 영상 소스: Linkkf는 기존 동작을 그대로 유지하고, Animenosub은 별도 선택 가능
    val videoSourcePreference: String = "linkkf",
    val subtitleFont: String = "기본체",
    val subtitleSize: Float = 100f,
    val textColor: Int = android.graphics.Color.WHITE,
    val backgroundColor: Int = android.graphics.Color.TRANSPARENT,
    val strokeColor: Int = android.graphics.Color.BLACK,
    val syncOffsetMs: Long = 0L,
    // Media3 SubtitleView 기준: 값이 클수록 VTT/SRT 자막이 화면 위쪽으로 올라간다.
    val subtitleBottomPaddingFraction: Float = 0.12f,
    // 자막 소스: "linkkf" = Linkkf VTT, "kairan" = Kairan ASS, "csora" = Csora ASS
    val subtitleSourcePreference: String = "linkkf",
    val customFontPath: String? = null,
    // Discovered Kairan/Csora ASS font selected by the user.
    val subtitleFontPath: String? = null,
    val subtitleFontSource: String? = null,
    val showChapterSkipButton: Boolean = true,
    // 다운로드 완료 회차에서만 수행하는 OP/ED 자동 분석
    val offlineOpEdAnalysisEnabled: Boolean = true,
    // 좌/우 더블 탭 시 이동할 시간(초)
    val doubleTapSeekSeconds: Long = 10L,
    // 중앙 뒤로/앞으로 버튼으로 이동할 시간(초)
    val seekButtonSeekSeconds: Long = 10L,
    // 기본 재생 배속
    val playbackSpeed: Float = 1.0f,
    val autoPlay: Boolean = true,
    val autoSkip: Boolean = true,
    val vttStyleEnabled: Boolean = true,
    // VTT 전용 표시 설정
    val vttBold: Boolean = true,
    val vttOutlineWidth: Float = 2.0f,
    // ASS/SSA effects can be disabled on lower-powered TV devices.
    val assEffectsEnabled: Boolean = true
)

data class ExoVideoQualityOption(
    val label: String,
    val width: Int,
    val height: Int,
    val isAuto: Boolean = false
)

data class ChapterSkipSegment(
    val type: String,
    val startTime: Double,
    val endTime: Double,
    val episodeLength: Double
)

