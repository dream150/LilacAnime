package com.lilac.anime.ui.search

import com.lilac.anime.*
import com.lilac.anime.data.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.navigation.AppScaffold
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
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
    var tagPanelOpen by remember { mutableStateOf(false) }

    // 0 = 안함, 1 = 선택(포함), -1 = 제외.
    val tagStates = remember { mutableStateMapOf<String, Int>() }
    val isLinkkf = vm.playerSettings.videoSourcePreference == "linkkf"

    LaunchedEffect(isLinkkf) {
        if (isLinkkf) vm.loadLinkkfFilterTags()
        vm.loadAllAnime()
    }

    val includeFormatIds = tagStates
        .filter { it.value == 1 && it.key.startsWith("format:") }
        .mapNotNull { it.key.removePrefix("format:").toIntOrNull() }
    val includeGenreIds = tagStates
        .filter { it.value == 1 && it.key.startsWith("genre:") }
        .mapNotNull { it.key.removePrefix("genre:").toIntOrNull() }
    val includeYearIds = tagStates
        .filter { it.value == 1 && it.key.startsWith("year:") }
        .mapNotNull { it.key.removePrefix("year:").toIntOrNull() }

    val includeMode = includeFormatIds.isNotEmpty() || includeGenreIds.isNotEmpty() || includeYearIds.isNotEmpty()
    val hasAnyTagState = tagStates.values.any { it != 0 }

    // Linkkf의 포함 필터는 기존 Linkkf API를 사용한다.
    // 선택(포함)이 없을 때는 기존 전체 목록을 사용하고,
    // 제외 태그는 API 결과에 대해 로컬에서 한 번 더 적용한다.
    LaunchedEffect(isLinkkf, includeFormatIds, includeGenreIds, includeYearIds) {
        if (isLinkkf && includeMode) {
            vm.loadLinkkfFilteredAnime(
                page = 1,
                formatIds = includeFormatIds,
                genreIds = includeGenreIds,
                yearIds = includeYearIds
            )
        }
    }

    val excludedGenreNames = tagStates
        .filter { it.value == -1 && it.key.startsWith("genre:") }
        .mapNotNull { entry ->
            vm.linkkfGenreTags.firstOrNull { it.id.toString() == entry.key.removePrefix("genre:") }?.name
        }
        .toSet()
    val excludedFormatNames = tagStates
        .filter { it.value == -1 && it.key.startsWith("format:") }
        .mapNotNull { entry ->
            vm.linkkfFormatTags.firstOrNull { it.id.toString() == entry.key.removePrefix("format:") }?.name
        }
        .toSet()
    val excludedYearNames = tagStates
        .filter { it.value == -1 && it.key.startsWith("year:") }
        .mapNotNull { entry ->
            vm.linkkfYearTags.firstOrNull { it.id.toString() == entry.key.removePrefix("year:") }?.name
        }
        .toSet()

    val q = query.trim()
    val sourceResults = if (isLinkkf && includeMode) {
        vm.linkkfFilterResults
    } else {
        vm.allAnime
    }

    val results = sourceResults
        .distinctBy { it.id }
        .filter { anime ->
            val queryMatch = q.isBlank() ||
                anime.title.contains(q, ignoreCase = true) ||
                anime.genres.any { it.contains(q, ignoreCase = true) }

            val genreExcluded = isLinkkf && excludedGenreNames.any { ex ->
                anime.genres.any { it.equals(ex, ignoreCase = true) }
            }
            val formatExcluded = isLinkkf && excludedFormatNames.any { ex ->
                anime.format.equals(ex, ignoreCase = true)
            }
            val yearExcluded = isLinkkf && excludedYearNames.any { ex ->
                anime.year.equals(ex, ignoreCase = true)
            }

            queryMatch && !genreExcluded && !formatExcluded && !yearExcluded
        }

    fun cycle(key: String) {
        val current = tagStates[key] ?: 0
        tagStates[key] = when (current) {
            0 -> 1
            1 -> -1
            else -> 0
        }
    }

    val includeCount = includeFormatIds.size + includeGenreIds.size + includeYearIds.size
    val excludeCount = tagStates.values.count { it == -1 }

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

                if (isLinkkf) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { tagPanelOpen = true },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("태그 필터")
                        }
                        if (hasAnyTagState) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "선택 $includeCount · 제외 $excludeCount",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .65f)
                            )
                        }
                    }
                }
            }

            Text(
                "${results.size}개 작품",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            if (results.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (isLinkkf && vm.linkkfFilterLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    } else {
                        Text("검색 결과가 없습니다.")
                    }
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
    }

    if (tagPanelOpen && isLinkkf) {
        AlertDialog(
            onDismissRequest = { tagPanelOpen = false },
            title = {
                Column {
                    Text("태그 필터", fontWeight = FontWeight.Bold)
                    Text(
                        "선택 → 제외 → 안함 순으로 변경됩니다.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
                    )
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Text("시즌 타입", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
                    items(vm.linkkfFormatTags, key = { "m-f-${it.id}" }) { tag ->
                        MobileTagChoice(
                            label = tag.name,
                            state = tagStates["format:${tag.id}"] ?: 0,
                            onClick = { cycle("format:${tag.id}") }
                        )
                    }
                    item { Text("장르", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
                    items(vm.linkkfGenreTags, key = { "m-g-${it.id}" }) { tag ->
                        MobileTagChoice(
                            label = tag.name,
                            state = tagStates["genre:${tag.id}"] ?: 0,
                            onClick = { cycle("genre:${tag.id}") }
                        )
                    }
                    item { Text("연도", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
                    items(vm.linkkfYearTags, key = { "m-y-${it.id}" }) { tag ->
                        MobileTagChoice(
                            label = tag.name,
                            state = tagStates["year:${tag.id}"] ?: 0,
                            onClick = { cycle("year:${tag.id}") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { tagPanelOpen = false }) {
                    Text("닫기")
                }
            },
            dismissButton = {
                if (hasAnyTagState) {
                    TextButton(onClick = { tagStates.clear() }) {
                        Text("전체 초기화")
                    }
                }
            }
        )
    }
}

@Composable
private fun MobileTagChoice(
    label: String,
    state: Int,
    onClick: () -> Unit
) {
    val (icon, text) = when (state) {
        1 -> Icons.Default.CheckCircle to "선택"
        -1 -> Icons.Default.RemoveCircle to "제외"
        else -> Icons.Default.RadioButtonUnchecked to "안함"
    }
    val containerColor = when (state) {
        1 -> MaterialTheme.colorScheme.primaryContainer
        -1 -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Surface(
            color = containerColor,
            shape = RoundedCornerShape(7.dp)
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
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
