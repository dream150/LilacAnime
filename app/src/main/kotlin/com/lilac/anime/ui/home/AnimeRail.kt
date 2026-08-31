package com.lilac.anime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lilac.anime.data.*

@Composable
fun AnimeRail(list: List<Anime>, openDetail: (Anime) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(list) { anime ->
            Column(
                modifier = Modifier
                    .width(190.dp) // 계속 시청하기와 동일한 가로 너비
                    .clickableNoIndication { openDetail(anime) }
            ) {
                AsyncImage(
                    model = anime.backdrop.ifEmpty { anime.poster }, // backdrop 사용[cite: 8]
                    contentDescription = anime.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp) // 계속 시청하기와 동일한 가로 높이[cite: 8]
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    anime.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

// ============================================================
// SEARCH & LIBRARY
// ============================================================
