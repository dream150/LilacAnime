package com.lilac.anime.ui.home

import androidx.compose.runtime.Composable
import com.lilac.anime.ui.components.SectionHeader

/** 홈 화면의 레일 제목. 공통 SectionHeader 를 그대로 쓴다. */
@Composable
fun RailTitle(title: String) {
    SectionHeader(title = title)
}
