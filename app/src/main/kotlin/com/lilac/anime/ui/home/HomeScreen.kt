package com.lilac.anime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.data.*

@Composable
fun HomeScreen(
    vm: AnimeViewModel,
    openDetail: (Anime) -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    AppScaffold(
        selected = "home",
        onSelect = onNavigate
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "안녕하세요",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                        Text("오늘은 무엇을 볼까요?", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    }
                    IconButton(onClick = { onNavigate("search") }) {
                        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }

            item {
                if (vm.loading && vm.homeAnime.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Lilac)
                    }
                } else if (vm.error != null && vm.homeAnime.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = vm.error ?: "오류가 발생했습니다.", color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.loadAnime(context) }) { Text("다시 시도", color = Color.White) }
                    }
                } else if (vm.homeAnime.isNotEmpty()) {
                    HeroCard(anime = vm.homeAnime.first(), open = openDetail)
                } else {
                    Text("등록된 애니메이션이 없습니다.", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onBackground)
                }
            }

            if (vm.watchHistory.isNotEmpty()) {
                item { RailTitle("계속 시청하기") }
                item { ContinueWatchingRail(vm = vm, open = openDetail) }
            }

            if (vm.homeAnime.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    RailTitle("최신 애니메이션")
                }
                item { AnimeRail(vm.homeAnime.take(10), openDetail) }

                item {
                    Spacer(Modifier.height(20.dp))
                    RailTitle("추천 애니메이션")
                }
                item { AnimeRail(vm.homeAnime.reversed().take(10), openDetail) }
            }
        }
    }
}

// ============================================================
// ALL ANIME
// ============================================================
