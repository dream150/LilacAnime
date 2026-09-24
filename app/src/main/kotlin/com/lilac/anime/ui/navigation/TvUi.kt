package com.lilac.anime.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import com.lilac.anime.Anime
import com.lilac.anime.Episode
import com.lilac.anime.data.offline.OfflineStore
import com.lilac.anime.ui.AnimeImage
import com.lilac.anime.viewmodel.AnimeViewModel
import com.lilac.anime.viewmodel.Lilac
import com.lilac.anime.viewmodel.ThemeMode

private val TvBg = Color(0xFF080808)
private val TvSurface = Color(0xFF151515)
private val TvText = Color(0xFFF4F4F4)
private val TvMuted = Color(0xFFAAAAAA)

@Composable
private fun TvFocus(modifier: Modifier = Modifier, onClick: () -> Unit): Modifier {
    // TV: one focus target and one OK action. Do not stack clickable/focusable
    // modifiers here because that causes the first OK press to be consumed by
    // the focus layer instead of the actual action.
    val context = LocalContext.current
    val isTv = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    if (!isTv) return modifier.clickableNoIndication(onClick)
    var focused by remember { mutableStateOf(false) }
    return modifier
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
        .onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP &&
                (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ||
                 event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            ) {
                onClick()
                true
            } else false
        }
        .focusable()
}


@Composable
fun TvShell(
    selected: String,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }

    fun activityFrom(context: android.content.Context): android.app.Activity? {
        var current: android.content.Context? = context
        while (current is android.content.ContextWrapper) {
            if (current is android.app.Activity) return current
            current = current.baseContext
        }
        return current as? android.app.Activity
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TvBg)
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val isExitKey = keyCode == android.view.KeyEvent.KEYCODE_ESCAPE ||
                    keyCode == android.view.KeyEvent.KEYCODE_ENDCALL
                if (isExitKey && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP) {
                    showExitDialog = true
                    true
                } else {
                    false
                }
            }
    ) {
        Row(
            Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 54.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LILAC", color = Lilac, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(44.dp))
            listOf("home" to "홈", "all" to "전체", "search" to "검색", "history" to "시청기록", "library" to "내 목록", "settings" to "설정").forEach { (route, label) ->
                Box(
                    TvFocus(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) { onSelect(route) }
                        .padding(horizontal = 13.dp, vertical = 9.dp)
                ) {
                    Text(label, color = if (selected == route) Color.White else TvMuted, fontSize = 17.sp, fontWeight = if (selected == route) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                TvFocus(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TvSurface)
                ) { onRefresh() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "새로고침",
                    tint = TvText,
                    modifier = Modifier.size(23.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.fillMaxSize()) { content() }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("앱 종료") },
            text = { Text("LilacAnime를 종료하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    activityFrom(context)?.finishAndRemoveTask()
                }) { Text("종료") }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun TvPosterCard(anime: Anime, onClick: () -> Unit, width: Int = 190) {
    Column(Modifier.width(width.dp)) {
        Box(
            TvFocus(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Color.Black)) { onClick() }
        ) {
            AnimeImage(anime.poster, anime.title, Modifier.fillMaxSize(), ContentScale.Fit)
        }
        Spacer(Modifier.height(8.dp))
        Text(anime.title, color = TvText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TvLandscapeCard(anime: Anime, onClick: () -> Unit, width: Int = 270) {
    Column(Modifier.width(width.dp)) {
        Box(
            TvFocus(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) { onClick() }
        ) {
            AnimeImage(anime.poster, anime.title, Modifier.fillMaxSize(), ContentScale.Crop)
        }
        Spacer(Modifier.height(8.dp))
        Text(anime.title, color = TvText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TvVerticalPosterCard(anime: Anime, onClick: () -> Unit, width: Int = 190) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            TvFocus(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
            ) { onClick() }
        ) {
            AnimeImage(anime.poster, anime.title, Modifier.fillMaxSize(), ContentScale.Crop)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            anime.title,
            color = TvText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TvVerticalAnimeRow(anime: Anime, onClick: () -> Unit) {
    Row(
        TvFocus(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TvSurface)) { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(118.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(7.dp)).background(Color.Black)) {
            AnimeImage(anime.poster, anime.title, Modifier.fillMaxSize(), ContentScale.Fit)
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(anime.title, color = TvText, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (anime.genres.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                Text(anime.genres.take(4).joinToString(" · "), color = TvMuted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (anime.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(anime.description, color = TvMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TvRail(title: String, items: List<Anime>, open: (Anime) -> Unit, vertical: Boolean = false, landscape: Boolean = false) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
        if (title.isNotBlank()) {
            Text(title, color = TvText, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 54.dp))
            Spacer(Modifier.height(12.dp))
        }
        if (vertical) {
            Column(Modifier.padding(horizontal = 54.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items.take(30).forEach { anime -> TvVerticalAnimeRow(anime, { open(anime) }) }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 54.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(items, key = { it.id }) {
                    if (landscape) TvLandscapeCard(it, { open(it) }) else TvPosterCard(it, { open(it) })
                }
            }
        }
    }
}

@Composable
fun TvHomeScreen(vm: AnimeViewModel, openDetail: (Anime) -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // Home preview is network-only. Never substitute or refresh it from the full catalog cache.
        vm.loadAnime(context)
        if (vm.playerSettings.videoSourcePreference == "linkkf") {
            vm.loadLinkkfHomeSchedule()
            vm.loadLinkkfHomeSections()
        }
    }
    TvShell("home", onNavigate, onRefresh = { vm.refreshAnime(context) }) {
        LazyColumn(contentPadding = PaddingValues(bottom = 50.dp)) {
            item {
                val hero = vm.homeAnime.firstOrNull()
                if (hero != null) {
                    Box(Modifier.fillMaxWidth().height(430.dp)) {
                        AnimeImage(hero.backdrop.ifBlank { hero.poster }, hero.title, Modifier.fillMaxSize(), ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(TvBg, Color.Transparent, TvBg))))
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, TvBg))))
                        Column(Modifier.align(Alignment.BottomStart).padding(start = 54.dp, bottom = 34.dp).width(610.dp)) {
                            Text(hero.title, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Black, maxLines = 2)
                            Spacer(Modifier.height(12.dp))
                            Text(hero.description, color = Color.White.copy(alpha = .82f), fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(20.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { openDetail(hero) }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("재생") }
                                OutlinedButton(onClick = { openDetail(hero) }) { Icon(Icons.Default.Info, null); Spacer(Modifier.width(8.dp)); Text("상세 정보") }
                            }
                        }
                    }
                }
            }
            if (vm.watchHistory.isNotEmpty()) {
                item {
                    TvRail(
                        "계속 시청하기",
                        vm.watchHistory.map { it.animeId }.distinct().mapNotNull { id -> vm.getAnime(context, id) }.take(18),
                        openDetail,
                        vertical = vm.playerSettings.videoSourcePreference == "reanime",
                        landscape = vm.playerSettings.videoSourcePreference == "linkkf"
                    )
                }
            }
            if (vm.playerSettings.videoSourcePreference == "linkkf") {
                val tabs = listOf("UP", "월", "화", "수", "목", "금", "토", "일")
                item { TvScheduleRail(vm, tabs, openDetail) }
                item { TvRail("PV · 트레일러", vm.linkkfPvTrailers, openDetail, landscape = true) }
                item { TvRail("극장판", vm.linkkfMovies, openDetail, landscape = true) }
                item { TvRail("16+", vm.linkkf16Plus, openDetail, landscape = true) }
            }
            item {
                TvRail(
                    "최신 애니메이션",
                    vm.homeAnime.take(18),
                    openDetail,
                    vertical = vm.playerSettings.videoSourcePreference == "reanime",
                    landscape = vm.playerSettings.videoSourcePreference == "linkkf"
                )
            }
            item {
                TvRail(
                    "전체 작품",
                    vm.allAnime.take(18),
                    openDetail,
                    vertical = vm.playerSettings.videoSourcePreference == "reanime",
                    landscape = vm.playerSettings.videoSourcePreference == "linkkf"
                )
            }
            item {
                TvRail(
                    "내 목록",
                    vm.library.mapNotNull { id -> vm.getAnime(context, id) }.take(18),
                    openDetail,
                    vertical = vm.playerSettings.videoSourcePreference == "reanime",
                    landscape = vm.playerSettings.videoSourcePreference == "linkkf"
                )
            }
        }
    }
}

@Composable
fun TvAllAnimeScreen(vm: AnimeViewModel, openDetail: (Anime) -> Unit, onNavigate: (String) -> Unit) {
    val isReAnime = vm.playerSettings.videoSourcePreference == "reanime"
    LaunchedEffect(Unit) { vm.loadAllAnime() }
    TvShell("all", onNavigate, onRefresh = { vm.refreshAnime() }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 54.dp)) {
            Text("전체", color = TvText, fontSize = 34.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 18.dp, bottom = 20.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (isReAnime) 180.dp else 270.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 50.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                gridItems(vm.allAnime, key = { it.id }) { anime ->
                    if (isReAnime) {
                        TvVerticalPosterCard(anime, onClick = { openDetail(anime) })
                    } else {
                        TvLandscapeCard(anime, onClick = { openDetail(anime) }, width = 270)
                    }
                }
            }
        }
    }
}

@Composable
fun TvSearchScreen(vm: AnimeViewModel, open: (Anime) -> Unit, onNavigate: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var tagPanelOpen by remember { mutableStateOf(false) }
    // 0 = not selected, 1 = include, -1 = exclude.
    val tagStates = remember { mutableStateMapOf<String, Int>() }

    LaunchedEffect(Unit) {
        vm.loadAllAnime()
        vm.loadLinkkfFilterTags()
    }

    val genreIncludes = tagStates.filter { it.value == 1 && it.key.startsWith("genre:") }
        .mapNotNull { it.key.removePrefix("genre:").toIntOrNull() }
    val formatIncludes = tagStates.filter { it.value == 1 && it.key.startsWith("format:") }
        .mapNotNull { it.key.removePrefix("format:").toIntOrNull() }
    val yearIncludes = tagStates.filter { it.value == 1 && it.key.startsWith("year:") }
        .mapNotNull { it.key.removePrefix("year:").toIntOrNull() }

    // TV search is catalog-cache only; the home preview is never a search source.
    val localCatalog = vm.allAnime.distinctBy { it.id }
    val includeMode = genreIncludes.isNotEmpty() || formatIncludes.isNotEmpty() || yearIncludes.isNotEmpty()
    val hasAnyTagState = tagStates.values.any { it != 0 }

    fun excludedGenreNames(): Set<String> = tagStates.filter { it.value == -1 && it.key.startsWith("genre:") }
        .mapNotNull { entry -> vm.linkkfGenreTags.firstOrNull { it.id.toString() == entry.key.removePrefix("genre:") }?.name }
        .toSet()
    fun excludedFormatNames(): Set<String> = tagStates.filter { it.value == -1 && it.key.startsWith("format:") }
        .mapNotNull { entry -> vm.linkkfFormatTags.firstOrNull { it.id.toString() == entry.key.removePrefix("format:") }?.name }
        .toSet()
    fun excludedYearNames(): Set<String> = tagStates.filter { it.value == -1 && it.key.startsWith("year:") }
        .mapNotNull { entry -> vm.linkkfYearTags.firstOrNull { it.id.toString() == entry.key.removePrefix("year:") }?.name }
        .toSet()

    val q = query.trim()
    val baseResults = if (vm.playerSettings.videoSourcePreference == "linkkf" && includeMode && vm.linkkfFilterResults.isNotEmpty()) {
        vm.linkkfFilterResults
    } else {
        localCatalog
    }
    val excludedGenres = excludedGenreNames()
    val excludedFormats = excludedFormatNames()
    val excludedYears = excludedYearNames()
    val results = baseResults.filter { anime ->
        val queryMatch = q.isBlank() || anime.title.contains(q, true) || anime.genres.any { it.contains(q, true) }
        val genreExcluded = excludedGenres.any { ex -> anime.genres.any { it.equals(ex, true) } }
        val formatExcluded = excludedFormats.any { ex -> anime.format.equals(ex, true) }
        val yearExcluded = excludedYears.any { ex -> anime.year.equals(ex, true) }
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

    var searchDrawerOpen by remember { mutableStateOf(false) }

    TvShell("search", onNavigate, onRefresh = { vm.refreshAnime() }) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 54.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 18.dp)) {
                    Text("검색", color = TvText, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.weight(1f))
                    TvFocus(Modifier.clip(RoundedCornerShape(8.dp)).background(TvSurface).padding(horizontal = 18.dp, vertical = 12.dp)) { searchDrawerOpen = true }
                        .let { modifier ->
                            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Search, contentDescription = "검색창 열기", tint = TvText)
                                Spacer(Modifier.width(8.dp))
                                Text("검색", color = TvText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    Spacer(Modifier.width(10.dp))
                    TvFocus(Modifier.clip(RoundedCornerShape(8.dp)).background(TvSurface).padding(horizontal = 20.dp, vertical = 12.dp)) { tagPanelOpen = true }
                        .let { modifier ->
                            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tune, contentDescription = "태그 필터", tint = TvText)
                                Spacer(Modifier.width(8.dp))
                                Text("태그 필터", color = TvText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${results.size}개", color = TvMuted, fontSize = 14.sp)
                    if (q.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        Text("‘$q’", color = TvMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (hasAnyTagState) {
                        Spacer(Modifier.width(12.dp))
                        Text("포함 ${genreIncludes.size + formatIncludes.size + yearIncludes.size} · 제외 ${tagStates.values.count { it == -1 }}", color = TvMuted, fontSize = 13.sp)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (vm.playerSettings.videoSourcePreference == "reanime") 180.dp else 270.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    gridItems(results, key = { it.id }) { anime ->
                        if (vm.playerSettings.videoSourcePreference == "reanime") {
                            TvVerticalPosterCard(anime, onClick = { open(anime) })
                        } else {
                            TvLandscapeCard(anime, onClick = { open(anime) }, width = 270)
                        }
                    }
                }
            }

            // Search is a left-side drawer so it never permanently consumes the result area.
            AnimatedVisibility(
                visible = searchDrawerOpen,
                enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)).onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP &&
                            (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE ||
                             event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK)) {
                            searchDrawerOpen = false
                            true
                        } else false
                    })
                    Surface(
                        Modifier.align(Alignment.CenterStart).fillMaxHeight().width(430.dp),
                        color = Color(0xFF121212),
                        tonalElevation = 10.dp
                    ) {
                        Column(Modifier.fillMaxSize().padding(28.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("검색", color = TvText, fontSize = 28.sp, fontWeight = FontWeight.Black)
                                Spacer(Modifier.weight(1f))
                                TvFocus(Modifier.padding(6.dp)) { searchDrawerOpen = false }
                                    .let { Box(it) { Icon(Icons.Default.Close, "닫기", tint = TvText) } }
                            }
                            Spacer(Modifier.height(20.dp))
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth().height(62.dp),
                                singleLine = true,
                                placeholder = { Text("작품명, 태그 검색") },
                                leadingIcon = { Icon(Icons.Default.Search, null) }
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("검색 결과 ${results.size}개", color = TvMuted, fontSize = 14.sp)
                            if (q.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("‘$q’", color = TvText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            if (tagPanelOpen) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)).onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP &&
                        (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE ||
                         event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK)) {
                        tagPanelOpen = false
                        true
                    } else false
                })
                Surface(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(440.dp), color = Color(0xFF121212), tonalElevation = 8.dp) {
                    LazyColumn(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("태그 필터", color = TvText, fontSize = 28.sp, fontWeight = FontWeight.Black)
                                Spacer(Modifier.weight(1f))
                                TvFocus(Modifier.padding(6.dp)) { tagPanelOpen = false }
                                    .let { Box(it) { Icon(Icons.Default.Close, "닫기", tint = TvText) } }
                            }
                            Text("확인 버튼을 누를 때마다 선택 → 제외 → 선택 안함 순으로 바뀝니다.", color = TvMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                        item { Text("장르", color = TvText, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                        items(vm.linkkfGenreTags, key = { "panel-g-${it.id}" }) { tag ->
                            val state = tagStates["genre:${tag.id}"] ?: 0
                            TvTagChoice(tag.name, state) { cycle("genre:${tag.id}") }
                        }
                        item { Text("형식", color = TvText, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp)) }
                        items(vm.linkkfFormatTags, key = { "panel-f-${it.id}" }) { tag ->
                            val state = tagStates["format:${tag.id}"] ?: 0
                            TvTagChoice(tag.name, state) { cycle("format:${tag.id}") }
                        }
                        item { Text("연도", color = TvText, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp)) }
                        items(vm.linkkfYearTags, key = { "panel-y-${it.id}" }) { tag ->
                            val state = tagStates["year:${tag.id}"] ?: 0
                            TvTagChoice(tag.name, state) { cycle("year:${tag.id}") }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                                TvChoice("전체 초기화", false) { tagStates.clear() }
                                TvChoice("닫기", false) { tagPanelOpen = false }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        TvFocus(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(if (selected) Color.White else TvSurface)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            onClick = onClick
        )
    ) {
        Text(
            label,
            color = if (selected) Color.Black else TvText,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable private fun TvTagChoice(label: String, state: Int, onClick: () -> Unit) {
    val icon = when (state) {
        1 -> Icons.Default.CheckCircle
        -1 -> Icons.Default.RemoveCircle
        else -> Icons.Default.RadioButtonUnchecked
    }
    val bg = when (state) {
        1 -> Color.White.copy(alpha = .16f)
        -1 -> Color(0xFF5A2525)
        else -> TvSurface
    }
    Row(
        TvFocus(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg)) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = TvText, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = TvText, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Text(when (state) { 1 -> "선택"; -1 -> "제외"; else -> "안함" }, color = TvMuted, fontSize = 12.sp)
    }
}

@Composable private fun TvScheduleRail(vm: AnimeViewModel, tabs: List<String>, open: (Anime) -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    Column(Modifier.padding(vertical = 18.dp)) {
        Text("방영 일정", color = TvText, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 54.dp))
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 54.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(tabs.size) { i -> TvFilterChip(tabs[i], selected == i) { selected = i } } }
        Spacer(Modifier.height(10.dp))
        TvRail("${tabs[selected]}요일", if (selected == 0) vm.homeAnime else vm.linkkfSchedule[21188 + selected].orEmpty(), open, landscape = true)
    }
}


@Composable
fun TvDetailScreen(vm: AnimeViewModel, anime: Anime, back: () -> Unit, playEpisode: (Episode) -> Unit, openRelated: (Anime) -> Unit = {}) {
    val context = LocalContext.current
    var detail by remember(anime.id) { mutableStateOf(anime) }
    LaunchedEffect(anime.id) {
        vm.loadAnimeDetail(detail, force = false) { detail = it }
        vm.loadEpisodes(context, detail, force = false)
    }
    LaunchedEffect(detail.id, vm.playerSettings.videoSourcePreference) {
        if (vm.playerSettings.videoSourcePreference == "linkkf") {
            vm.loadLinkkfDetailExtras(detail, force = true)
        }
    }
    BackHandlerTv(back)
    LazyColumn(Modifier.fillMaxSize().background(TvBg), contentPadding = PaddingValues(bottom = 60.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(390.dp)) {
                AnimeImage(detail.backdrop.ifBlank { detail.poster }, detail.title, Modifier.fillMaxSize(), ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(TvBg, Color.Transparent, TvBg))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, TvBg))))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 54.dp, bottom = 28.dp).width(720.dp)) {
                    Text(detail.title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black)
                    Text(listOfNotNull(detail.year.takeIf { it.isNotBlank() }, detail.format.takeIf { it.isNotBlank() }, detail.airedDate.takeIf { it.isNotBlank() }).joinToString("  •  "), color = TvMuted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(detail.description, color = TvText.copy(alpha = .86f), fontSize = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val first = vm.episodes(detail).firstOrNull()
                        Button(onClick = { first?.let(playEpisode) }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("재생") }
                        OutlinedButton(onClick = { vm.toggleLibrary(context, detail.id) }) {
                            Icon(
                                if (vm.isInLibrary(detail.id)) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (vm.isInLibrary(detail.id)) "내 목록에서 제거" else "내 목록")
                        }
                    }
                }
            }
        }
        item { Text("회차", color = TvText, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 54.dp, top = 24.dp, bottom = 12.dp)) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 54.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vm.episodes(detail), key = { it.id }) { ep ->
                    var focused by remember { mutableStateOf(false) }
                    Box(TvFocus(Modifier.clip(RoundedCornerShape(8.dp)).background(TvSurface).width(150.dp).height(82.dp)) { playEpisode(ep) }.padding(14.dp)) {
                        Column { Text("${ep.displayNumber}화", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text(ep.title, color = TvMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) }
                    }
                }
            }
        }
        if (vm.playerSettings.videoSourcePreference == "linkkf" && vm.linkkfRelatedSeries.isNotEmpty()) {
            item {
                Spacer(Modifier.height(14.dp))
                Text(
                    "관련 작품",
                    color = TvText,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 54.dp, top = 20.dp, bottom = 12.dp)
                )
            }
            vm.linkkfRelatedSeries.forEach { series ->
                item {
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        if (vm.linkkfRelatedSeries.size > 1) {
                            Text(
                                series.name,
                                color = TvMuted,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 54.dp, bottom = 8.dp)
                            )
                        }
                        TvRail(
                            title = if (vm.linkkfRelatedSeries.size > 1) "" else "관련 작품",
                            items = series.items.take(18),
                            open = { related ->
                                vm.cacheAnime(related)
                                openRelated(related)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackHandlerTv(back: () -> Unit) {
    androidx.activity.compose.BackHandler { back() }
}

@Composable
fun TvHistoryScreen(vm: AnimeViewModel, open: (Anime) -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val items = vm.watchHistory.distinctBy { it.animeId }.mapNotNull { vm.getAnime(context, it.animeId) }
    TvShell("history", onNavigate, onRefresh = { vm.refreshAnime() }) { Column(Modifier.fillMaxSize().padding(horizontal = 54.dp)) { Text("시청기록", color = TvText, fontSize = 34.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(vertical = 20.dp)); LazyVerticalGrid(columns = GridCells.Fixed(6), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) { gridItems(items) { TvLandscapeCard(it, { open(it) }, 270) } } } }
}

@Composable
fun TvLibraryScreen(vm: AnimeViewModel, open: (Anime) -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val items = vm.library.mapNotNull { vm.getAnime(context, it) }
    TvShell("library", onNavigate, onRefresh = { vm.refreshAnime() }) { Column(Modifier.fillMaxSize().padding(horizontal = 54.dp)) { Text("내 목록", color = TvText, fontSize = 34.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(vertical = 20.dp)); LazyVerticalGrid(columns = GridCells.Fixed(6), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) { gridItems(items) { TvLandscapeCard(it, { open(it) }, 270) } } } }
}

@Composable
fun TvSettingsScreen(vm: AnimeViewModel, themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    TvShell("settings", onNavigate) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 110.dp), contentPadding = PaddingValues(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("설정", color = TvText, fontSize = 36.sp, fontWeight = FontWeight.Black) }
            item { TvSettingSection("영상 소스") { listOf("linkkf" to "Linkkf", "animenosub" to "Animenosub", "reanime" to "RE:Anime").forEach { (value, label) -> TvChoice(label, vm.playerSettings.videoSourcePreference == value) { vm.updatePlayerSettings(context, vm.playerSettings.copy(videoSourcePreference = value)) } } } }
            item { TvSettingSection("기본 화질") { listOf("Auto", "720p", "1080p").forEach { TvChoice(it, vm.playerSettings.defaultQuality == it) { vm.updatePlayerSettings(context, vm.playerSettings.copy(defaultQuality = it)) } } } }
            item { TvSettingSection("테마") { listOf(ThemeMode.SYSTEM to "시스템", ThemeMode.DARK to "다크", ThemeMode.LIGHT to "라이트").forEach { (m, label) -> TvChoice(label, themeMode == m) { onThemeChange(m) } } } }
            item { TvSettingSection("재생") { TvChoice("자동 다음 화", vm.playerSettings.autoPlay) { vm.updatePlayerSettings(context, vm.playerSettings.copy(autoPlay = !vm.playerSettings.autoPlay)) }; TvChoice("OP/ED 자동 스킵", vm.playerSettings.autoSkip) { vm.updatePlayerSettings(context, vm.playerSettings.copy(autoSkip = !vm.playerSettings.autoSkip)) } } }
        }
    }
}

@Composable private fun TvSettingSection(title: String, content: @Composable RowScope.() -> Unit) { Column(Modifier.fillMaxWidth().background(TvSurface, RoundedCornerShape(12.dp)).padding(22.dp)) { Text(title, color = TvText, fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(14.dp)); Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = content) } }
@Composable private fun TvChoice(label: String, selected: Boolean, onClick: () -> Unit) { Box(TvFocus(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color.White.copy(alpha=.16f) else Color.Transparent)) { onClick() }.padding(horizontal = 18.dp, vertical = 13.dp)) { Text(label, color = Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) } }
