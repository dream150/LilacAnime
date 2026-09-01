package com.lilac.anime.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

@Composable
fun AnimeImage(
    model: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val normalized = model.trim()

    if (normalized.isBlank()) {
        Log.w("AnimeImage", "EMPTY_URL title=$contentDescription")
        return
    }

    AsyncImage(
        model = normalized,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onSuccess = {
            Log.d(
                "AnimeImage",
                "LOADED title=$contentDescription url=$normalized"
            )
        },
        onError = { state ->
            Log.e(
                "AnimeImage",
                "FAILED title=$contentDescription url=$normalized error=${state.result.throwable}",
                state.result.throwable
            )
        }
    )
}
