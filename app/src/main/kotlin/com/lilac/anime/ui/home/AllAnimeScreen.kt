package com.lilac.anime.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import com.lilac.anime.Anime
import com.lilac.anime.ui.components.BackdropCard
import com.lilac.anime.ui.components.EmptyState
import com.lilac.anime.ui.components.ScreenHeader
import com.lilac.anime.viewmodel.AnimeViewModel

/**
 * 전체 작품 탐색. 아래로 당겨서 새로고침(Material3 PullToRefreshBox)한다.
 * (직접 포인터 이벤트를 가로채던 수제 당겨서 새로고침은 스크롤과 충돌하고 진행 표시도 없었다.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllAnimeScreen(
    vm: AnimeViewModel,
    openDetail: (Anime) -> Unit,
    onNavigate: (String) -> Unit
) {
    // 목적지 쪽(AppNavigation)에서도 loadAllAnime 을 부르던 중복 호출을 여기 한 곳으로 합쳤다.
    LaunchedEffect(Unit) { vm.loadAllAnime() }

    val gridState = rememberLazyGridState()
    val items = vm.allAnime.distinctBy { it.id }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "탐색",
            subtitle = if (items.isEmpty()) null else "${items.size}개 작품",
            actions = {
                if (vm.isAllAnimeLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        )

        PullToRefreshBox(
            isRefreshing = vm.isAllAnimeLoading,
            onRefresh = { vm.refreshAnime() },
            modifier = Modifier.fillMaxSize()
        ) {
            if (items.isEmpty() && !vm.isAllAnimeLoading) {
                Box(Modifier.fillMaxSize()) {
                    EmptyState(
                        icon = Icons.Filled.Explore,
                        title = "작품이 없어요",
                        message = "아래로 당겨서 다시 불러오세요."
                    )
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 180.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)
                ) {
                    items(items = items, key = { it.id }) { anime ->
                        BackdropCard(
                            anime = anime,
                            onClick = { openDetail(anime) },
                            caption = anime.genres.take(3).joinToString(" · ")
                        )
                    }
                }
            }
        }
    }
}
