package com.lilac.anime.ui.home

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
import com.lilac.anime.data.subtitle.*
import com.lilac.anime.network.*
import com.lilac.anime.player.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.detail.*
import com.lilac.anime.ui.navigation.*
import com.lilac.anime.ui.search.*
import com.lilac.anime.ui.settings.*
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import com.lilac.anime.ui.AnimeImage
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
    var scheduleTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(vm.sourceRevision, vm.playerSettings.videoSourcePreference) {
        if (vm.playerSettings.videoSourcePreference == "linkkf") {
            vm.loadLinkkfHomeSchedule()
            vm.loadLinkkfHomeSections()
        }
    }


    Box(modifier = Modifier.fillMaxSize()) {
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

            if (vm.playerSettings.videoSourcePreference == "animenosub") {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Animenosub 인증", fontWeight = FontWeight.Bold)
                                Text(
                                    "영상 재생 전에 Animenosub 인증을 완료해주세요.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(onClick = { onNavigate("animenosub-auth") }) {
                                Text("인증하기")
                            }
                        }
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

            if (vm.playerSettings.videoSourcePreference == "linkkf") {
                // linkkf.app: Hero 바로 아래에 방영 일정/요일별 작품을 배치하고,
                // 그 다음 특수 섹션(PV/극장판/16+)을 표시한다.
                item { Spacer(Modifier.height(22.dp)) }
                item {
                    Text(
                        "방영 일정",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    val tabs = listOf("UP", "월", "화", "수", "목", "금", "토", "일")
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(tabs.size) { index ->
                            FilterChip(
                                selected = scheduleTab == index,
                                onClick = { scheduleTab = index },
                                label = { Text(tabs[index]) }
                            )
                        }
                    }
                }
                item {
                    val scheduleItems = if (scheduleTab == 0) {
                        vm.homeAnime
                    } else {
                        vm.linkkfSchedule[21188 + scheduleTab] ?: emptyList()
                    }

                    if (vm.linkkfScheduleLoading && scheduleItems.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().height(170.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Lilac)
                        }
                    } else if (scheduleItems.isNotEmpty()) {
                        // 다른 홈 작품 레일과 동일한 카드 크기/가로 스크롤을 사용한다.
                        AnimeRail(scheduleItems.take(20), openDetail)
                    }
                }

                if (vm.watchHistory.isNotEmpty()) {
                    item { RailTitle("계속 시청하기") }
                    item { ContinueWatchingRail(vm = vm, open = openDetail) }
                }

                if (vm.linkkfPvTrailers.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(22.dp))
                        RailTitle("PV · 트레일러")
                        AnimeRail(vm.linkkfPvTrailers, openDetail)
                    }
                }

                if (vm.linkkfMovies.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(22.dp))
                        RailTitle("극장판")
                        AnimeRail(vm.linkkfMovies, openDetail)
                    }
                }

                if (vm.linkkf16Plus.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(22.dp))
                        RailTitle("16+ 애니")
                        AnimeRail(vm.linkkf16Plus, openDetail)
                    }
                }

                if (vm.homeAnime.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(22.dp))
                        RailTitle("최신 애니메이션")
                    }
                    item { AnimeRail(vm.homeAnime.take(10), openDetail) }
                }
            } else {
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
                }
            }

        }
    }

    }
}

// ============================================================
// ALL ANIME
// ============================================================


@Composable
private fun HomeScheduleCard(anime: Anime, open: (Anime) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickableNoIndication { open(anime) },
        shape = RoundedCornerShape(10.dp)
    ) {
        Column {
            AnimeImage(
                model = anime.poster,
                contentDescription = anime.title,
                modifier = Modifier.fillMaxWidth().height(110.dp),
                contentScale = ContentScale.Crop
            )
            Text(
                anime.title,
                modifier = Modifier.padding(10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
