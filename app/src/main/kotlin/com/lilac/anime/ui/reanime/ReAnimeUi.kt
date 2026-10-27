package com.lilac.anime.ui.reanime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.lilac.anime.Anime
import com.lilac.anime.ui.AnimeImage
import com.lilac.anime.ui.components.ScreenHeader
import com.lilac.anime.ui.navigation.AppScaffold
import com.lilac.anime.viewmodel.AnimeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReAnimeSearchScreen(vm: AnimeViewModel, open: (Anime) -> Unit, onNavigate: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf("") }
    var season by rememberSaveable { mutableStateOf("") }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.loadReAnimeHome() }
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(350)
        vm.searchReAnime(query, context)
    }

    fun runSearch() {
        vm.searchReAnimeFiltered(query, genre, year.toIntOrNull(), season, status, format, context)
    }

    AppScaffold(selected = "search", onSelect = onNavigate) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = "검색",
                subtitle = "Re:ANIME 전체 검색",
                actions = {
                    IconButton(onClick = { filtersOpen = !filtersOpen }) { Icon(Icons.Default.Tune, "필터") }
                }
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(17.dp),
                    placeholder = { Text("작품명, 영어명, 로마자") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = ::runSearch) { Icon(Icons.Default.ArrowForward, "검색") }
            }

            AnimatedVisibility(filtersOpen) {
                Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("상세 필터", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FacetDropDown("장르", genre, vm.reAnimeFacets.genres, Modifier.weight(1f)) { genre = it }
                            FacetDropDown("연도", year, vm.reAnimeFacets.years, Modifier.weight(1f)) { year = it }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FacetDropDown("시즌", season, vm.reAnimeFacets.seasons, Modifier.weight(1f)) { season = it }
                            FacetDropDown("상태", status, vm.reAnimeFacets.statuses, Modifier.weight(1f)) { status = it }
                        }
                        FacetDropDown("포맷", format, vm.reAnimeFacets.formats, Modifier.fillMaxWidth()) { format = it }
                        Button(onClick = ::runSearch, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FilterAlt, null); Spacer(Modifier.width(6.dp)); Text("필터 적용")
                        }
                    }
                }
            }

            if (vm.reAnimeSearchLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            if (!vm.reAnimeSearchLoading && query.isNotBlank() && vm.reAnimeSearchResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp)); Text("검색 결과가 없습니다.")
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(145.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(vm.reAnimeSearchResults, key = { it.id }) { ReAnimeSearchCard(it, open) }
                }
            }
        }
    }
}

@Composable
private fun ReAnimeSearchCard(anime: Anime, open: (Anime) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { open(anime) }) {
        AnimeImage(anime.poster, anime.title, Modifier.fillMaxWidth().height(205.dp).clip(RoundedCornerShape(14.dp)), ContentScale.Crop)
        Spacer(Modifier.height(7.dp))
        Text(anime.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        Text(listOf(anime.year, anime.format, anime.note).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun FacetDropDown(label: String, value: String, options: List<String>, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(value.ifBlank { label }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("전체") }, onClick = { onSelect(""); expanded = false })
            options.take(40).forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}
