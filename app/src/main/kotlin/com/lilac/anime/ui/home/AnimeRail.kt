package com.lilac.anime.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lilac.anime.Anime
import com.lilac.anime.ui.components.BackdropCard

/** 가로로 넘기는 작품 목록. 카드 모양은 시청 기록/탐색과 같은 BackdropCard. */
@Composable
fun AnimeRail(list: List<Anime>, openDetail: (Anime) -> Unit) {
    // 같은 작품이 두 번 들어오면 LazyRow 의 key 충돌로 앱이 죽으므로 먼저 중복을 없앤다.
    val unique = remember(list) { list.distinctBy { it.id } }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(unique, key = { it.id }) { anime ->
            BackdropCard(
                anime = anime,
                onClick = { openDetail(anime) },
                modifier = Modifier.width(200.dp),
                caption = anime.genres.take(2).joinToString(" · ")
            )
        }
    }
}
