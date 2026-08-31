package com.lilac.anime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lilac.anime.data.*

@Composable
fun AllAnimeScreen(
    vm: AnimeViewModel,
    openDetail: (Anime) -> Unit,
    onNavigate: (String) -> Unit
) {
    LaunchedEffect(Unit) {
        vm.loadAllAnime()
    }

    AppScaffold(selected = "all", onSelect = onNavigate) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("전체 애니메이션", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                if (vm.isAllAnimeLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Lilac,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("${vm.allAnime.size}개", color = LilacDark, fontSize = 14.sp)
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 200.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                // AllAnimeScreen 내의 items 항목 수정
items(
    items = vm.allAnime,
    key = { anime -> anime.id }
) { anime ->
    Column(
        modifier = Modifier.clickableNoIndication { openDetail(anime) }
    ) {
        AsyncImage(
            model = anime.backdrop.ifEmpty { anime.poster }, // backdrop을 사용하고, 없을 경우 poster로 대체
            contentDescription = anime.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f) // 가로 16:9 비율 설정
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(6.dp))
        Text(
            anime.title,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            anime.genres.joinToString(" · "),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
            }
        }
    }
}

// ============================================================
// HOME RAILS & CARDS
// ============================================================
