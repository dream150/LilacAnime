package com.lilac.anime.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * 작품 이미지. 로딩 중/실패/주소 없음일 때도 자리를 유지해서 목록이 덜컥거리지 않게 한다.
 * (이전에는 주소가 비면 아무것도 그리지 않아 카드 크기가 줄어들었고,
 * 이미지 하나가 로드될 때마다 로그를 남겨서 스크롤이 무거웠다.)
 */
@Composable
fun AnimeImage(
    model: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val normalized = model.trim()
    val placeholderColor = MaterialTheme.colorScheme.surfaceContainerHigh

    if (normalized.isBlank()) {
        Box(modifier.background(placeholderColor))
        return
    }

    val painter = ColorPainter(placeholderColor)
    AsyncImage(
        model = normalized,
        contentDescription = contentDescription,
        modifier = modifier,
        placeholder = painter,
        error = painter,
        contentScale = contentScale,
        onError = { state ->
            Log.w("AnimeImage", "FAILED title=$contentDescription url=$normalized error=${state.result.throwable}")
        }
    )
}
