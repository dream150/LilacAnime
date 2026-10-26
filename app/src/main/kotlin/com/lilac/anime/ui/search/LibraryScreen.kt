package com.lilac.anime.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lilac.anime.Anime
import com.lilac.anime.ui.components.EmptyState
import com.lilac.anime.ui.components.PosterRow
import com.lilac.anime.ui.components.ScreenHeader
import com.lilac.anime.ui.home.WatchHistoryContent
import com.lilac.anime.viewmodel.AnimeViewModel

private const val TAB_SAVED = 0
private const val TAB_HISTORY = 1
private const val TAB_DOWNLOADS = 2

/**
 * 보관함: 내 목록 / 시청 기록 / 다운로드를 한 화면의 탭으로 모았다.
 * 하단 탭이 6개에서 5개로 줄고, 비슷한 성격의 목록이 한곳에 모인다.
 */
@Composable
fun LibraryScreen(
    vm: AnimeViewModel,
    open: (Anime) -> Unit,
    onNavigate: (String) -> Unit,
    initialTab: Int = TAB_SAVED
) {
    val context = LocalContext.current
    val isOffline by vm.isOffline.collectAsState()
    val downloadedIds by vm.downloadedIds.collectAsState()

    LaunchedEffect(Unit) { vm.refreshDownloads() }

    var downloaded by remember { mutableStateOf<List<Anime>>(emptyList()) }
    LaunchedEffect(downloadedIds, vm.homeAnime, vm.allAnime) {
        downloaded = vm.getDownloadedAnimeList(context)
    }

    // 내 목록: 현재 불러온 목록에 없어도 저장된 스냅샷에서 찾아 보여준다.
    var saved by remember { mutableStateOf<List<Anime>>(emptyList()) }
    LaunchedEffect(vm.library, vm.homeAnime, vm.allAnime) {
        val map = vm.resolveAnimeMap(context, vm.library)
        saved = vm.library.mapNotNull { map[it] }
    }

    var selectedTab by remember {
        mutableIntStateOf(if (initialTab != TAB_SAVED) initialTab else if (isOffline) TAB_DOWNLOADS else TAB_SAVED)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "보관함")

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedTab == TAB_SAVED,
                onClick = { selectedTab = TAB_SAVED },
                text = { Text("내 목록 ${saved.size}") }
            )
            Tab(
                selected = selectedTab == TAB_HISTORY,
                onClick = { selectedTab = TAB_HISTORY },
                text = { Text("시청 기록") }
            )
            Tab(
                selected = selectedTab == TAB_DOWNLOADS,
                onClick = { selectedTab = TAB_DOWNLOADS },
                text = { Text("다운로드 ${downloaded.size}") }
            )
        }

        when (selectedTab) {
            TAB_HISTORY -> WatchHistoryContent(vm = vm, open = open)
            TAB_DOWNLOADS -> AnimeList(
                list = downloaded,
                open = open,
                emptyIcon = { Icons.Filled.DownloadDone },
                emptyTitle = "다운로드한 작품이 없어요",
                emptyMessage = "작품 상세에서 회차를 내려받으면 오프라인에서도 볼 수 있어요."
            )
            else -> AnimeList(
                list = saved,
                open = open,
                emptyIcon = { Icons.Filled.FavoriteBorder },
                emptyTitle = "내 목록이 비어 있어요",
                emptyMessage = "작품 상세의 하트를 눌러 담아 두세요.",
                trailing = { anime ->
                    IconButton(onClick = { vm.toggleLibrary(context, anime.id) }) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = "내 목록에서 빼기",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun AnimeList(
    list: List<Anime>,
    open: (Anime) -> Unit,
    emptyIcon: () -> androidx.compose.ui.graphics.vector.ImageVector,
    emptyTitle: String,
    emptyMessage: String,
    trailing: (@Composable (Anime) -> Unit)? = null
) {
    if (list.isEmpty()) {
        EmptyState(icon = emptyIcon(), title = emptyTitle, message = emptyMessage)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(list.distinctBy { it.id }, key = { it.id }) { anime ->
            PosterRow(
                anime = anime,
                onClick = { open(anime) },
                trailing = if (trailing != null) {
                    { trailing(anime) }
                } else null
            )
        }
    }
}
