package com.lilac.anime.viewmodel

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/** 테마(라이트/다크/시스템) 설정을 저장하는 DataStore. */
val Context.dataStore by preferencesDataStore(name = "theme_settings")
val THEME_KEY = stringPreferencesKey("theme_mode")

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

// ---------------------------------------------------------------------------
// 브랜드 색상 (기존 코드 호환용 별칭). 새 UI는 MaterialTheme.colorScheme 을 사용한다.
// ---------------------------------------------------------------------------
val Lilac = Color(0xFFC8A2C8)
val LilacDark = Color(0xFF9A7B9A)
val DarkBackground = Color(0xFF141218)
val DarkSurface = Color(0xFF211F24)
val LightBackground = Color(0xFFFDF8FF)
val LightSurface = Color(0xFFF2ECF4)
