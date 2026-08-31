package com.lilac.anime

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.lilac.anime.data.*
data class StreamQuality(
    val label: String,
    val url: String
)

data class PlayerSettings(
    val defaultQuality: String = "1080p",
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
    val showAniSkipButton: Boolean = true,
    // 좌/우 더블 탭 시 이동할 시간(초)
    val doubleTapSeekSeconds: Int = 10,
    // 기본 재생 배속
    val playbackSpeed: Float = 1.0f,
    // VTT 전용 표시 설정
    val vttBold: Boolean = true,
    val vttOutlineWidth: Float = 2.0f
)

data class ExoVideoQualityOption(
    val label: String,
    val width: Int,
    val height: Int,
    val isAuto: Boolean = false
)

data class AniSkipSegment(
    val type: String,
    val startTime: Double,
    val endTime: Double,
    val episodeLength: Double
)

