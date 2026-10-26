package com.lilac.anime.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lilac.anime.Anime
import com.lilac.anime.ui.components.ErrorState
import com.lilac.anime.ui.components.LoadingState
import com.lilac.anime.ui.components.ScreenHeader
import com.lilac.anime.viewmodel.AnimeViewModel

private val ScheduleTabs = listOf("UP", "월", "화", "수", "목", "금", "토", "일")

@Composable
fun HomeScreen(
    vm: AnimeViewModel,
    openDetail: (Anime) -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    var scheduleTab by remember { mutableIntStateOf(0) }
    val source = vm.playerSettings.videoSourcePreference
    val isLinkkf = source == "linkkf"

    LaunchedEffect(vm.sourceRevision, source) {
        if (isLinkkf) {
            vm.loadLinkkfHomeSchedule()
            vm.loadLinkkfHomeSections()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            ScreenHeader(
                title = "오늘은 무엇을 볼까요?",
                subtitle = "안녕하세요",
                actions = {
                    IconButton(onClick = { onNavigate("search") }) {
                        Icon(Icons.Filled.Search, contentDescription = "검색")
                    }
                }
            )
        }

        if (source == "animenosub") {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Animenosub 인증", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "영상 재생 전에 인증을 완료해 주세요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Button(onClick = { onNavigate("animenosub-auth") }) { Text("인증하기") }
                    }
                }
            }
        }

        item {
            when {
                vm.loading && vm.homeAnime.isEmpty() ->
                    LoadingState(Modifier.height(300.dp), message = "작품을 불러오는 중이에요")
                vm.error != null && vm.homeAnime.isEmpty() ->
                    ErrorState(vm.error ?: "오류가 발생했습니다.", onRetry = { vm.loadAnime(context) })
                vm.homeAnime.isNotEmpty() -> HeroCard(anime = vm.homeAnime.first(), open = openDetail)
                else -> Text(
                    "등록된 애니메이션이 없습니다.",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (vm.watchHistory.isNotEmpty()) {
            item { RailTitle("이어보기") }
            item { ContinueWatchingRail(vm = vm, open = openDetail) }
        }

        if (isLinkkf) {
            // linkkf.app: 방영 일정(요일별) → PV → 극장판 → 16+ 순서.
            item { RailTitle("방영 일정") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ScheduleTabs.size) { index ->
                        FilterChip(
                            selected = scheduleTab == index,
                            onClick = { scheduleTab = index },
                            label = { Text(ScheduleTabs[index]) }
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(10.dp))
                val scheduleItems = if (scheduleTab == 0) {
                    vm.homeAnime
                } else {
                    vm.linkkfSchedule[21188 + scheduleTab] ?: emptyList()
                }
                when {
                    vm.linkkfScheduleLoading && scheduleItems.isEmpty() ->
                        LoadingState(Modifier.height(140.dp))
                    scheduleItems.isNotEmpty() -> AnimeRail(scheduleItems.take(20), openDetail)
                    else -> Text(
                        "이 요일에 방영하는 작품이 없어요.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (vm.linkkfPvTrailers.isNotEmpty()) {
                item { RailTitle("PV · 트레일러") }
                item { AnimeRail(vm.linkkfPvTrailers, openDetail) }
            }
            if (vm.linkkfMovies.isNotEmpty()) {
                item { RailTitle("극장판") }
                item { AnimeRail(vm.linkkfMovies, openDetail) }
            }
            if (vm.linkkf16Plus.isNotEmpty()) {
                item { RailTitle("16+ 애니") }
                item { AnimeRail(vm.linkkf16Plus, openDetail) }
            }
        }

        if (vm.homeAnime.isNotEmpty()) {
            item { RailTitle("최신 애니메이션") }
            item { AnimeRail(vm.homeAnime.take(10), openDetail) }
        }
    }
}
