package com.lilac.anime

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lilac.anime.data.*

@Composable
fun LibraryScreen(
    vm: AnimeViewModel,
    open: (Anime) -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val isOffline by vm.isOffline.collectAsState()
    val downloadedIds by vm.downloadedIds.collectAsState()
    
    LaunchedEffect(Unit) {
        vm.refreshDownloads()
    }

    var downloadedAnimeList by remember { mutableStateOf<List<Anime>>(emptyList()) }
    LaunchedEffect(downloadedIds, vm.homeAnime, vm.allAnime) {
        downloadedAnimeList = vm.getDownloadedAnimeList(context)
    }

    val allAnimeList = (vm.allAnime + vm.homeAnime).distinctBy { it.id }
    val savedAnime = allAnimeList.filter { it.id in vm.library }

    var selectedTab by remember { mutableIntStateOf(if (isOffline) 1 else 0) }

    AppScaffold(selected = "library", onSelect = onNavigate) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "보관함", 
                fontSize = 28.sp, 
                fontWeight = FontWeight.Bold, 
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp)
            )

            Spacer(Modifier.height(12.dp))

            TabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surface) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("내 목록", color = MaterialTheme.colorScheme.onSurface) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("다운로드 완료 (${downloadedAnimeList.size})", color = MaterialTheme.colorScheme.onSurface) }
                )
            }

            Spacer(Modifier.height(16.dp))

            val currentList = if (selectedTab == 0) savedAnime else downloadedAnimeList

            if (currentList.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (selectedTab == 0) Icons.Default.FavoriteBorder else Icons.Default.DownloadDone,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Lilac
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (selectedTab == 0) "내 목록이 비어 있습니다" else "다운로드된 애니메이션이 없습니다",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(currentList) { anime ->
                        Row(modifier = Modifier.fillMaxWidth().clickableNoIndication { open(anime) }) {
                            AsyncImage(
                                model = anime.poster,
                                contentDescription = anime.title,
                                modifier = Modifier.size(width = 90.dp, height = 130.dp).clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(anime.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                                Spacer(Modifier.height(6.dp))
                                Text(anime.genres.joinToString(" · "), color = LilacDark)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    anime.description,
                                    maxLines = 2,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
