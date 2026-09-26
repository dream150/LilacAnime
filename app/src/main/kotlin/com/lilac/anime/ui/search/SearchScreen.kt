package com.lilac.anime.ui.search

import com.lilac.anime.*
import com.lilac.anime.data.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.navigation.AppScaffold
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    var yearId by rememberSaveable { mutableStateOf<Int?>(null) }
    var filterDrawerOpen by rememberSaveable { mutableStateOf(false) }

    val isLinkkf = vm.playerSettings.videoSourcePreference == "linkkf"
    val selectedFilterActive = formatId != null || genreId != null || yearId != null

    // Filter labels may be refreshed independently, but the anime result set itself
    // is ALWAYS the persisted/full catalog loaded by loadAllAnime().
    LaunchedEffect(isLinkkf) {
        if (isLinkkf) vm.loadLinkkfFilterTags()
    }

    val selectedFormat = vm.linkkfFormatTags.firstOrNull { it.id == formatId }?.name
    val selectedGenre = vm.linkkfGenreTags.firstOrNull { it.id == genreId }?.name
    val selectedYear = vm.linkkfYearTags.firstOrNull { it.id == yearId }?.name
    val q = query.trim()

    val results = remember(q, vm.allAnime, formatId, genreId, yearId, selectedFormat, selectedGenre, selectedYear) {
        vm.allAnime
            .distinctBy { it.id }
            .filter { anime ->
                val queryMatch = q.isBlank() ||
                    anime.title.contains(q, ignoreCase = true) ||
                    anime.genres.any { it.contains(q, ignoreCase = true) }
                val formatMatch = selectedFormat == null || anime.format.equals(selectedFormat, ignoreCase = true)
                val genreMatch = selectedGenre == null || anime.genres.any { it.equals(selectedGenre, ignoreCase = true) }
                val yearMatch = selectedYear == null || anime.year == selectedYear
                queryMatch && formatMatch && genreMatch && yearMatch
            }
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
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, null)
                            }
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    singleLine = true
                )
            }

            if (isLinkkf) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedFilterActive) {
                        Text(
                            "필터 ${listOf(formatId, genreId, yearId).count { it != null }}개",
                            modifier = Modifier.padding(end = 8.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    OutlinedButton(
                        onClick = { filterDrawerOpen = true },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("태그 필터")
                    }
                }
            }

            Text(
                "${results.size}개 작품 · 캐시된 전체 목록",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            if (results.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("검색 결과가 없습니다.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(results, key = { it.id }) { anime ->
                        SearchResultRow(anime, open)
                    }
                }
            }
        }

        if (isLinkkf && filterDrawerOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable { filterDrawerOpen = false }
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.38f))
                )
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(330.dp)
                        .align(Alignment.CenterEnd)
                        .clickable { },
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 18.dp, vertical = 18.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("태그 필터", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "원하는 조건을 선택하세요.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
                                )
                            }
                            IconButton(onClick = { filterDrawerOpen = false }) {
                                Icon(Icons.Default.Close, contentDescription = "닫기")
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Column(
                            Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        ) {
                            FilterSection("시즌 타입", vm.linkkfFormatTags, formatId) {
                                formatId = if (formatId == it) null else it
                            }
                            FilterSection("장르", vm.linkkfGenreTags, genreId) {
                                genreId = if (genreId == it) null else it
                            }
                            FilterSection("연도", vm.linkkfYearTags.take(20), yearId) {
                                yearId = if (yearId == it) null else it
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    formatId = null
                                    genreId = null
                                    yearId = null
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("초기화") }
                            Button(
                                onClick = { filterDrawerOpen = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("적용") }
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
        Text(
            title,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            rowItems(tags) { tag ->
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
        Modifier
            .fillMaxWidth()
            .clickable { open(anime) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimeImage(
            model = anime.poster,
            contentDescription = anime.title,
            modifier = Modifier
                .size(width = 78.dp, height = 110.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                anime.title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (anime.genres.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    anime.genres.joinToString(" · "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
