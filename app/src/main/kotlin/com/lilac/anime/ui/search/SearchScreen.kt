package com.lilac.anime.ui.search

import com.lilac.anime.*
import com.lilac.anime.data.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*
import com.lilac.anime.ui.navigation.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SearchScreen(
    vm: AnimeViewModel,
    open: (Anime) -> Unit,
    onNavigate: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var formatId by remember { mutableStateOf<Int?>(null) }
    var genreId by remember { mutableStateOf<Int?>(null) }
    var yearId by remember { mutableStateOf<Int?>(null) }

    val isLinkkf = vm.playerSettings.videoSourcePreference == "linkkf"
    val selectedFilterActive = formatId != null || genreId != null || yearId != null

    LaunchedEffect(isLinkkf) {
        if (isLinkkf) {
            vm.loadLinkkfFilterTags()
            vm.loadLinkkfFilteredAnime()
        }
    }

    LaunchedEffect(formatId, genreId, yearId, isLinkkf) {
        if (isLinkkf) {
            vm.loadLinkkfFilteredAnime(
                page = 1,
                formatIds = listOfNotNull(formatId),
                genreIds = listOfNotNull(genreId),
                yearIds = listOfNotNull(yearId)
            )
        }
    }

    val keywordResults = remember(query, vm.allAnime, vm.homeAnime) {
        if (query.isBlank()) emptyList()
        else (vm.allAnime + vm.homeAnime)
            .distinctBy { it.id }
            .filter { anime ->
                anime.title.contains(query.trim(), ignoreCase = true) ||
                    anime.genres.any { it.contains(query.trim(), ignoreCase = true) }
            }
    }

    val results = when {
        !isLinkkf -> keywordResults
        query.isNotBlank() -> keywordResults
        else -> vm.linkkfFilterResults
    }

    AppScaffold(selected = "search", onSelect = onNavigate) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text("검색", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("작품명 또는 장르 검색") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, null) }
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    singleLine = true
                )
            }

            if (isLinkkf) {
                FilterSection("시즌 타입", vm.linkkfFormatTags, formatId) { formatId = if (formatId == it) null else it }
                FilterSection("장르", vm.linkkfGenreTags, genreId) { genreId = if (genreId == it) null else it }
                FilterSection("연도", vm.linkkfYearTags.take(20), yearId) { yearId = if (yearId == it) null else it }
                if (selectedFilterActive) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                        TextButton(onClick = { formatId = null; genreId = null; yearId = null }) { Text("필터 초기화") }
                    }
                }
            }

            if (isLinkkf && query.isBlank() && vm.linkkfFilterTotalResults > 0) {
                Text(
                    "${vm.linkkfFilterTotalResults}개 작품",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }

            if (isLinkkf && vm.linkkfFilterLoading && results.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Lilac)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(results, key = { it.id }) { anime -> SearchResultRow(anime, open) }
                    if (isLinkkf && query.isBlank() && vm.linkkfFilterPage < vm.linkkfFilterTotalPages) {
                        item {
                            Button(
                                onClick = {
                                    vm.loadLinkkfFilteredAnime(
                                        page = vm.linkkfFilterPage + 1,
                                        formatIds = listOfNotNull(formatId),
                                        genreIds = listOfNotNull(genreId),
                                        yearIds = listOfNotNull(yearId)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("다음 페이지") }
                        }
                    }
                    if (results.isEmpty() && !vm.linkkfFilterLoading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                Text("검색 결과가 없습니다.")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    tags: List<LinkkfApiClient.FilterTag>,
    selected: Int?,
    onSelect: (Int) -> Unit
) {
    if (tags.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(title, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items(tags) { tag ->
                FilterChip(
                    selected = selected == tag.id,
                    onClick = { onSelect(tag.id) },
                    label = { Text(tag.name, maxLines = 1) }
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(anime: Anime, open: (Anime) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickableNoIndication { open(anime) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimeImage(
            model = anime.poster,
            contentDescription = anime.title,
            modifier = Modifier.size(width = 78.dp, height = 110.dp).clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(anime.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (anime.genres.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Text(anime.genres.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
