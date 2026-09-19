package com.lilac.anime.ui

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
import com.lilac.anime.data.subtitle.*
import com.lilac.anime.network.*
import com.lilac.anime.player.*
import com.lilac.anime.ui.detail.*
import com.lilac.anime.ui.home.*
import com.lilac.anime.ui.navigation.*
import com.lilac.anime.ui.search.*
import com.lilac.anime.ui.settings.*
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

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
