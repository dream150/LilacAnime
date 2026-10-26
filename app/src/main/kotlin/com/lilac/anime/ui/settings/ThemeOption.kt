package com.lilac.anime.ui.settings

import androidx.compose.runtime.Composable

/** 테마 선택 한 줄. 다른 설정의 선택 줄과 같은 모양(SettingsChoiceRow)을 쓴다. */
@Composable
fun ThemeOption(title: String, selected: Boolean, onClick: () -> Unit) {
    SettingsChoiceRow(title = title, selected = selected, onClick = onClick)
}
