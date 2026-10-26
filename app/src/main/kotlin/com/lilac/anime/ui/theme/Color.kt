package com.lilac.anime.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * LilacAnime 색상 체계.
 * 라일락(보라) 계열을 브랜드 색으로 쓰되, 흰 글자가 올라가는 버튼에서도 대비가 충분하도록
 * primary 는 기존 연보라(C8A2C8)보다 조금 진하게 잡았다.
 */
internal val LilacLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF6D4C9F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDDFF),
    onPrimaryContainer = Color(0xFF260F4D),
    secondary = Color(0xFF635B70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9DEF8),
    onSecondaryContainer = Color(0xFF1F182B),
    tertiary = Color(0xFF805162),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E3),
    onTertiaryContainer = Color(0xFF31101D),
    background = Color(0xFFFDF8FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFDF8FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EB),
    onSurfaceVariant = Color(0xFF49454E),
    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCBC4CF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F2FA),
    surfaceContainer = Color(0xFFF2ECF4),
    surfaceContainerHigh = Color(0xFFECE6EE),
    surfaceContainerHighest = Color(0xFFE6E0E9)
)

internal val LilacDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF9F82DC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A2F7C),
    onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFCDC2DB),
    onSecondary = Color(0xFF342D40),
    secondaryContainer = Color(0xFF4B4358),
    onSecondaryContainer = Color(0xFFE9DEF8),
    tertiary = Color(0xFFF0B7C9),
    onTertiary = Color(0xFF4A2534),
    tertiaryContainer = Color(0xFF643B4B),
    onTertiaryContainer = Color(0xFFFFD9E3),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCBC4CF),
    outline = Color(0xFF948E99),
    outlineVariant = Color(0xFF49454E),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F24),
    surfaceContainerHigh = Color(0xFF2B292F),
    surfaceContainerHighest = Color(0xFF36343A)
)
