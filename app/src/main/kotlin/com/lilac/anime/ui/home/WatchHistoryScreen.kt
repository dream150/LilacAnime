package com.lilac.anime.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lilac.anime.Anime
import com.lilac.anime.WatchProgress
import com.lilac.anime.ui.components.BackdropCard
import com.lilac.anime.ui.components.EmptyState
import com.lilac.anime.ui.components.ScreenHeader
import com.lilac.anime.ui.components.SelectionBar
import com.lilac.anime.viewmodel.AnimeViewModel

/** 예전 "history" 경로용 화면. 내용은 보관함의 '시청 기록' 탭과 같다. */
@Composable
fun WatchHistoryScreen(
    vm: AnimeViewModel,
    open: (Anime) -> Unit,
    onNavigate: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "시청 기록")
        WatchHistoryContent(vm = vm, open = open)
    }
}

/**
 * 시청 기록 목록(Scaffold 없는 알맹이). 길게 눌러 여러 개를 골라 지울 수 있다.
 * 작품 정보는 캐시에 없으면 저장된 스냅샷에서 찾는다.
 */
@Composable
fun WatchHistoryContent(vm: AnimeViewModel, open: (Anime) -> Unit) {
    val context = LocalContext.current
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selectedIds.isNotEmpty()
    var resolved by remember { mutableStateOf<Map<String, Anime>>(emptyMap()) }

    val historyIds = vm.watchHistory.map { it.animeId }.distinct()
    LaunchedEffect(historyIds, vm.homeAnime, vm.allAnime) {
        resolved = vm.resolveAnimeMap(context, historyIds)
    }

    val items: List<Pair<Anime, WatchProgress>> = vm.watchHistory
        .distinctBy { it.animeId }
        .mapNotNull { progress -> resolved[progress.animeId]?.let { it to progress } }

    Column(Modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionBar(
                selectedCount = selectedIds.size,
                totalCount = items.size,
                onSelectAll = {
                    selectedIds = if (selectedIds.size == items.size) emptySet()
                    else items.map { it.first.id }.toSet()
                },
                onDelete = {
                    vm.deleteWatchHistory(context, selectedIds)
                    selectedIds = emptySet()
                },
                onCancel = { selectedIds = emptySet() }
            )
        }

        if (items.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.History,
                title = "시청 기록이 없어요",
                message = "작품을 보기 시작하면 여기에 이어보기 목록이 쌓여요."
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)
            ) {
                items(items, key = { it.first.id }) { (anime, progress) ->
                    val selected = anime.id in selectedIds
                    BackdropCard(
                        anime = anime,
                        progress = progress.progress,
                        selected = selected,
                        caption = "EP.${progress.episodeKey}",
                        onClick = {
                            if (selectionMode) {
                                selectedIds = if (selected) selectedIds - anime.id else selectedIds + anime.id
                            } else {
                                open(anime)
                            }
                        },
                        onLongClick = { selectedIds = selectedIds + anime.id }
                    )
                }
            }
        }
    }
}
