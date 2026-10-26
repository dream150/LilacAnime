package com.lilac.anime.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.lilac.anime.ui.components.BackdropCard
import com.lilac.anime.ui.components.SelectionBar
import com.lilac.anime.viewmodel.AnimeViewModel
import androidx.compose.foundation.layout.Column

/**
 * 홈의 "이어보기" 레일. 길게 눌러 선택 모드로 들어가면 기록을 지울 수 있다.
 * 작품 정보는 현재 목록이 아니라 저장된 스냅샷까지 찾아서 가져오므로, 목록에 없는 작품도 사라지지 않는다.
 */
@Composable
fun ContinueWatchingRail(vm: AnimeViewModel, open: (Anime) -> Unit) {
    val context = LocalContext.current
    val historyIds = vm.watchHistory.map { it.animeId }.distinct()

    var resolved by remember { mutableStateOf<Map<String, Anime>>(emptyMap()) }
    LaunchedEffect(historyIds, vm.homeAnime, vm.allAnime) {
        resolved = vm.resolveAnimeMap(context, historyIds)
    }

    val items = historyIds.mapNotNull { id ->
        val anime = resolved[id] ?: return@mapNotNull null
        val progress = vm.getLatestProgress(id) ?: return@mapNotNull null
        Triple(id, anime, progress)
    }

    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selectedIds.isNotEmpty()

    if (items.isEmpty()) return

    Column(Modifier.fillMaxWidth()) {
        if (selectionMode) {
            SelectionBar(
                selectedCount = selectedIds.size,
                totalCount = items.size,
                onSelectAll = {
                    selectedIds = if (selectedIds.size == items.size) emptySet()
                    else items.map { it.first }.toSet()
                },
                onDelete = {
                    vm.deleteWatchHistory(context, selectedIds)
                    selectedIds = emptySet()
                },
                onCancel = { selectedIds = emptySet() }
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.first }) { (id, anime, progress) ->
                val selected = id in selectedIds
                BackdropCard(
                    anime = anime,
                    modifier = Modifier.width(200.dp),
                    progress = progress.progress,
                    selected = selected,
                    caption = "EP.${progress.episodeKey}",
                    onClick = {
                        if (selectionMode) {
                            selectedIds = if (selected) selectedIds - id else selectedIds + id
                        } else {
                            open(anime)
                        }
                    },
                    onLongClick = { selectedIds = selectedIds + id }
                )
            }
        }
    }
}
