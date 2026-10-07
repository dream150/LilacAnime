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
import com.lilac.anime.Episode
import com.lilac.anime.ReAnimeRelated
import com.lilac.anime.ui.AnimeImage
import com.lilac.anime.ui.navigation.AppScaffold
import com.lilac.anime.viewmodel.AnimeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ReAnimeLilac = Color(0xFFC8A2C8)

@Composable
fun ReAnimeHomeScreen(
    vm: AnimeViewModel,
    open: (Anime) -> Unit,
    onNavigate: (String) -> Unit
) {
    LaunchedEffect(Unit) { vm.loadReAnimeHome() }
    val progress by vm.downloadProgressMap.collectAsState()

    AppScaffold(selected = "home", onSelect = onNavigate) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                ReAnimeHeroHome(
                    loading = vm.reAnimeHomeLoading,
                    onSearch = { onNavigate("search") }
                )
            }
            if (vm.reAnimeTop.isNotEmpty()) {
                item { HomeSectionHeader("오늘의 인기", "지금 Re:ANIME에서 많이 보는 작품", Icons.Default.Whatshot) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(vm.reAnimeTop.take(10), key = { it.id }) { ReAnimeLargePoster(it, open) }
                    }
                }
            }
            if (vm.reAnimeSchedule.isNotEmpty()) {
                item { HomeSectionHeader("이번 주 방영", "Asia/Seoul · 방송 일정", Icons.Default.CalendarMonth) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(vm.reAnimeSchedule.take(12), key = { it.id }) { anime ->
                            ReAnimeScheduleCard(anime, open)
                        }
                    }
                }
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(22.dp),
                    tonalElevation = 2.dp
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onNavigate("search") }.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(Modifier.size(48.dp), shape = RoundedCornerShape(15.dp), color = ReAnimeLilac.copy(alpha = .16f)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Search, null, tint = ReAnimeLilac) }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text("작품을 찾아볼까요?", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Text("검색 탭에서 제목 검색과 상세 필터를 사용할 수 있어요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            }
            if (progress.isNotEmpty()) {
                item {
                    Text(
                        "다운로드 진행 중 ${progress.count { it.value in 0f..0.99f }}개",
                        Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ReAnimeHeroHome(loading: Boolean, onSearch: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(250.dp)
            .background(Brush.linearGradient(listOf(ReAnimeLilac.copy(.26f), MaterialTheme.colorScheme.background)))
    ) {
        Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
            Text("RE:ANIME", fontSize = 13.sp, fontWeight = FontWeight.Black, color = ReAnimeLilac, letterSpacing = 2.sp)
            Spacer(Modifier.height(4.dp))
            Text("오늘 볼 애니를\n찾아보세요.", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(14.dp))
            Button(onClick = onSearch, shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(7.dp))
                Text("작품 검색")
            }
        }
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(22.dp).size(22.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun HomeSectionHeader(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = ReAnimeLilac, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = 9.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReAnimeLargePoster(anime: Anime, open: (Anime) -> Unit) {
    Column(Modifier.width(132.dp).clickable { open(anime) }) {
        AnimeImage(anime.poster, anime.title, Modifier.fillMaxWidth().height(188.dp).clip(RoundedCornerShape(15.dp)), ContentScale.Crop)
        Spacer(Modifier.height(7.dp))
        Text(anime.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        Text(listOf(anime.year, anime.format).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReAnimeScheduleCard(anime: Anime, open: (Anime) -> Unit) {
    Surface(
        Modifier.width(245.dp).clickable { open(anime) },
        shape = RoundedCornerShape(17.dp),
        tonalElevation = 1.dp
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimeImage(anime.poster, anime.title, Modifier.size(62.dp, 82.dp).clip(RoundedCornerShape(11.dp)), ContentScale.Crop)
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text(anime.title, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text(anime.note.ifBlank { anime.format }, fontSize = 11.sp, color = ReAnimeLilac, maxLines = 1)
                Text(anime.year, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

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
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("검색", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Re:ANIME 전체 검색", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { filtersOpen = !filtersOpen }) { Icon(Icons.Default.Tune, "필터") }
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReAnimeDetailScreen(vm: AnimeViewModel, anime: Anime, back: () -> Unit, playEpisode: (Episode) -> Unit, openRelated: (Anime) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var detail by remember(anime.id) { mutableStateOf(anime) }
    var loading by remember(anime.id) { mutableStateOf(true) }
    var tab by rememberSaveable(anime.id) { mutableIntStateOf(0) }
    LaunchedEffect(anime.id) { vm.loadAnimeDetail(anime, true) { detail = it }; vm.loadEpisodes(context, anime, true); loading = false }
    val episodes = vm.episodes(detail).sortedBy { it.number }
    val episodeLoading = vm.isEpisodesLoading(detail)
    val latest = vm.getLatestProgress(detail.id)
    val downloaded = vm.downloadedIds.collectAsState().value
    Scaffold(topBar = { TopAppBar(title = { Text("Re:ANIME") }, navigationIcon = { IconButton(back) { Icon(Icons.Default.ArrowBack, "뒤로") } }) }) { padding ->
        if ((loading || episodeLoading) && episodes.isEmpty()) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 28.dp)) {
            item { ReAnimeHero(detail, episodes.size) }
            item {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { (latest?.let { p -> episodes.firstOrNull { it.number == p.episodeNumber } } ?: episodes.firstOrNull())?.let(playEpisode) }, enabled = episodes.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text(if (latest != null) "이어보기" else "첫 화 재생") }
                    OutlinedButton(onClick = { vm.toggleLibrary(context, detail.id) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.BookmarkAdd, null); Spacer(Modifier.width(5.dp)); Text(if (vm.isInLibrary(detail.id)) "보관 중" else "보관") }
                }
            }
            item { ScrollableTabRow(selectedTabIndex = tab, edgePadding = 18.dp) { listOf("회차", "정보", "관계작").forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) } } }
            when (tab) {
                0 -> items(episodes, key = { it.id }) { ep -> ReAnimeEpisodeRow(ep, detail, downloaded.contains("${detail.id}::${ep.id}") || vm.isEpisodeDownloaded(detail.id, ep), { playEpisode(ep) }, { vm.enqueueReAnimeDownload(context, detail, ep) }) }
                1 -> item { ReAnimeInfo(detail) }
                else -> items(detail.reAnimeRelated, key = { it.id }) { rel -> ReAnimeRelatedRow(rel) { openRelated(Anime(id = rel.id, title = rel.title, native = rel.nativeTitle, romaji = rel.romaji, poster = rel.poster, source = "reanime", detailUrl = "${com.lilac.anime.data.ReAnimeHarClient.BASE_URL}/anime/${rel.id.removePrefix("reanime:")}")) } }
            }
        }
    }
}

@Composable private fun ReAnimeHero(anime: Anime, episodeCount: Int) { Box(Modifier.fillMaxWidth().height(330.dp)) { AnimeImage(anime.poster, anime.title, Modifier.fillMaxSize(), ContentScale.Crop); Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.92f))))); Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) { Text(anime.title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold); if (anime.native.isNotBlank()) Text(anime.native, color = Color.White.copy(.78f), fontSize = 13.sp); Text("${episodeCount}화 · ${anime.genres.take(3).joinToString(" · ")}", color = Color.White.copy(.8f), fontSize = 12.sp) } } }

@Composable private fun ReAnimeEpisodeRow(ep: Episode, anime: Anime, downloaded: Boolean, play: () -> Unit, download: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(48.dp), shape = RoundedCornerShape(12.dp), color = if (ep.playable) ReAnimeLilac else MaterialTheme.colorScheme.surfaceVariant) { Box(contentAlignment = Alignment.Center) { Text(ep.displayNumber, fontWeight = FontWeight.ExtraBold, color = Color.White) } }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp).clickable(enabled = ep.playable, onClick = play)) { Text(ep.title, fontWeight = FontWeight.SemiBold); Text(listOfNotNull(ep.airedDate.takeIf { it.isNotBlank() }, if (ep.isFiller) "Filler" else null, if (ep.isRecap) "Recap" else null).joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(onClick = play, enabled = ep.playable) { Icon(Icons.Default.PlayCircleOutline, null, tint = ReAnimeLilac) }
        IconButton(onClick = download, enabled = !downloaded && ep.playable) { Icon(if (downloaded) Icons.Default.DownloadDone else Icons.Default.Download, null, tint = if (downloaded) ReAnimeLilac else MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable private fun ReAnimeInfo(anime: Anime) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { if (anime.description.isNotBlank()) Text(android.text.Html.fromHtml(anime.description, android.text.Html.FROM_HTML_MODE_LEGACY).toString(), fontSize = 14.sp, lineHeight = 21.sp); if (anime.genres.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { anime.genres.forEach { AssistChip(onClick = {}, label = { Text(it) }) } }; listOf("상태" to anime.note, "방영" to anime.airedDate, "원어" to anime.native, "로마자" to anime.romaji, "제작" to anime.studios.joinToString(", ")).filter { it.second.isNotBlank() }.forEach { Row(Modifier.fillMaxWidth()) { Text(it.first, Modifier.width(55.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp); Text(it.second, Modifier.weight(1f), fontSize = 13.sp) } } } }

@Composable private fun ReAnimeRelatedRow(rel: ReAnimeRelated, open: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 18.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { AnimeImage(rel.poster, rel.title, Modifier.size(58.dp, 78.dp).clip(RoundedCornerShape(9.dp)), ContentScale.Crop); Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(rel.relationType.ifBlank { "관련 작품" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary); Text(rel.title, fontWeight = FontWeight.Bold); if (rel.nativeTitle.isNotBlank()) Text(rel.nativeTitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Default.ChevronRight, null) } }
