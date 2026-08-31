package com.lilac.anime

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

@Composable
fun WatchHistoryScreen(
    vm: AnimeViewModel,
    open: (Anime) -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectionMode by remember { mutableStateOf(false) }
    var resolvedAnime by remember { mutableStateOf<Map<String, Anime>>(emptyMap()) }

    val historyAnimeIds = vm.watchHistory.map { it.animeId }.distinct()

    LaunchedEffect(historyAnimeIds, vm.homeAnime, vm.allAnime) {
        val available = (vm.homeAnime + vm.allAnime).distinctBy { it.id }.associateBy { it.id }.toMutableMap()
        for (id in historyAnimeIds) {
            if (!available.containsKey(id)) OfflineStore.getAnime(context, id)?.let { available[id] = it }
        }
        resolvedAnime = available
    }

    val historyItems = vm.watchHistory
        .distinctBy { it.animeId }
        .mapNotNull { progress -> resolvedAnime[progress.animeId]?.let { it to progress } }

    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        if (selectedIds.isEmpty()) selectionMode = false
    }

    AppScaffold(selected = "history", onSelect = onNavigate) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (selectionMode) {
                SelectionToolbar(
                    selectedCount = selectedIds.size,
                    totalCount = historyItems.size,
                    onSelectAll = { selectedIds = historyItems.map { it.first.id }.toSet() },
                    onDelete = {
                        vm.deleteWatchHistory(context, selectedIds)
                        selectedIds = emptySet()
                        selectionMode = false
                    },
                    onCancel = { selectedIds = emptySet(); selectionMode = false }
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("시청기록", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("${historyItems.size}개", color = LilacDark, fontSize = 14.sp)
                }
            }

            if (historyItems.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.History, null, Modifier.size(64.dp), tint = Lilac)
                    Spacer(Modifier.height(16.dp))
                    Text("시청기록이 없습니다", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 200.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    items(historyItems, key = { it.first.id }) { (anime, progress) ->
                        val selected = anime.id in selectedIds
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { if (selectionMode) toggleSelection(anime.id) else open(anime) },
                                    onLongClick = {
                                        if (!selectionMode) {
                                            selectionMode = true
                                            selectedIds = setOf(anime.id)
                                        } else toggleSelection(anime.id)
                                    }
                                )
                        ) {
                            Column {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(12.dp))
                                ) {
                                    AsyncImage(
                                        model = anime.backdrop.ifEmpty { anime.poster },
                                        contentDescription = anime.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Box(Modifier.fillMaxSize().background(Lilac.copy(alpha = if (selected) .42f else 0f)))
                                    if (selected) {
                                        Surface(
                                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(32.dp),
                                            shape = RoundedCornerShape(50),
                                            color = Lilac,
                                            shadowElevation = 2.dp
                                        ) {
                                            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.padding(6.dp))
                                        }
                                    }
                                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)) {
                                        LinearProgressIndicator(
                                            progress = { progress.progress.coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().height(4.dp),
                                            color = Lilac
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(anime.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Text("EP.${progress.episodeKey}", color = Lilac, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionToolbar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onSelectAll, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Icon(Icons.Default.SelectAll, null, Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (selectedCount == totalCount) "전체해제" else "전체선택")
        }
        Text("${selectedCount}개 선택", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconButton(enabled = selectedCount > 0, onClick = onDelete) {
            Icon(Icons.Default.Delete, "삭제", tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .35f))
        }
        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "취소") }
    }
}
