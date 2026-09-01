package com.lilac.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.ui.AnimeImage

@Composable
fun ContinueWatchingRail(vm: AnimeViewModel, open: (Anime) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val allAvailableAnime = (vm.homeAnime + vm.allAnime).distinctBy { it.id }
    val watchingAnime = vm.watchHistory.map { it.animeId }.distinct().mapNotNull { id -> allAvailableAnime.firstOrNull { it.id == id } }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectionMode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        if (selectionMode) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { selectedIds = if (selectedIds.size == watchingAnime.size) emptySet() else watchingAnime.map { it.id }.toSet() },
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.SelectAll, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (selectedIds.size == watchingAnime.size) "전체해제" else "전체선택")
                }
                Text("${selectedIds.size}개 선택", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(enabled = selectedIds.isNotEmpty(), onClick = {
                    vm.deleteWatchHistory(context, selectedIds)
                    selectedIds = emptySet()
                    selectionMode = false
                }) { Icon(Icons.Default.Delete, "삭제", tint = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .35f)) }
                IconButton(onClick = { selectedIds = emptySet(); selectionMode = false }) { Icon(Icons.Default.Close, "취소") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            watchingAnime.forEach { anime ->
                val progress = vm.getLatestProgress(anime.id) ?: return@forEach
                val selected = anime.id in selectedIds
                Box(
                    Modifier.width(190.dp).clip(RoundedCornerShape(16.dp)).combinedClickable(
                        onClick = { if (selectionMode) {
                            selectedIds = if (selected) selectedIds - anime.id else selectedIds + anime.id
                            if (selectedIds.isEmpty()) selectionMode = false
                        } else open(anime) },
                        onLongClick = { selectionMode = true; selectedIds = selectedIds + anime.id }
                    )
                ) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(16.dp))) {
                            AnimeImage(model = anime.backdrop.ifEmpty { anime.poster }, contentDescription = anime.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(Modifier.fillMaxSize().background(Lilac.copy(alpha = if (selected) .42f else 0f)))
                            Icon(Icons.Default.PlayArrow, null, Modifier.align(Alignment.Center).size(42.dp), tint = Color.White)
                            if (selected) {
                                Surface(Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp), shape = RoundedCornerShape(50), color = Lilac, shadowElevation = 2.dp) {
                                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.padding(5.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(anime.title, fontWeight = FontWeight.SemiBold, maxLines = 1, color = MaterialTheme.colorScheme.onBackground)
                        Text("EP.${progress.episodeKey}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        Spacer(Modifier.height(5.dp))
                        LinearProgressIndicator(progress = { progress.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Lilac)
                    }
                }
            }
        }
    }
}
