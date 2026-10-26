package com.lilac.anime.ui.navigation

import android.net.Uri
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lilac.anime.Anime
import com.lilac.anime.Episode
import com.lilac.anime.data.offline.OfflineStore
import com.lilac.anime.player.PlayerScreen
import com.lilac.anime.ui.components.LilacBottomBar
import com.lilac.anime.ui.components.rememberIsTv
import com.lilac.anime.ui.detail.DetailScreen
import com.lilac.anime.ui.home.AllAnimeScreen
import com.lilac.anime.ui.home.HomeScreen
import com.lilac.anime.ui.home.WatchHistoryScreen
import com.lilac.anime.ui.reanime.ReAnimeDetailScreen
import com.lilac.anime.ui.reanime.ReAnimeHomeScreen
import com.lilac.anime.ui.reanime.ReAnimeSearchScreen
import com.lilac.anime.ui.search.LibraryScreen
import com.lilac.anime.ui.search.SearchScreen
import com.lilac.anime.ui.settings.AiSettingsScreen
import com.lilac.anime.ui.settings.SettingsScreen
import com.lilac.anime.ui.theme.LilacTheme
import com.lilac.anime.viewmodel.AnimeViewModel
import com.lilac.anime.viewmodel.THEME_KEY
import com.lilac.anime.viewmodel.ThemeMode
import com.lilac.anime.viewmodel.dataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// ============================================================================
// 경로
// ============================================================================

/** 화면 경로. 문자열을 여기저기 직접 쓰지 않도록 한곳에 모았다. */
object Routes {
    const val HOME = "home"
    const val ALL = "all"
    const val SEARCH = "search"
    const val HISTORY = "history"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val SETTINGS_AI = "settings/ai"
    const val ANIMENOSUB_AUTH = "animenosub-auth"
    const val DETAIL = "detail/{id}"
    const val PLAYER = "player/{animeId}/{episodeKey}"

    /** 작품 id 에 '/', '?', 한글 등이 들어 있어도 경로가 깨지지 않도록 인코딩한다. */
    fun detail(id: String) = "detail/${Uri.encode(id)}"
    fun player(animeId: String, episodeKey: String) = "player/${Uri.encode(animeId)}/${Uri.encode(episodeKey)}"
}

/** 하단 탭에서 어느 칸을 밝힐지. 탭이 아닌 화면이면 null → 하단 바를 숨긴다. */
private fun tabFor(route: String?): String? = when (route) {
    Routes.HOME, Routes.ALL, Routes.SEARCH, Routes.LIBRARY, Routes.SETTINGS -> route
    Routes.HISTORY -> Routes.LIBRARY
    Routes.SETTINGS_AI -> Routes.SETTINGS
    else -> null
}

/**
 * 탭 이동. 같은 탭을 다시 눌러도 쌓이지 않고(singleTop), 시작 화면까지만 되돌려
 * 탭마다 스크롤/상태를 복원한다. (예전에는 누를 때마다 새 화면이 쌓여 뒤로가기가 탭을 전부 거쳐 갔다.)
 */
private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// ============================================================================
// 포커스(TV) / 클릭 도우미
// ============================================================================

@Composable
fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isTv = rememberIsTv()
    var focused by remember { mutableStateOf(false) }

    return this
        .onFocusChanged { focused = it.isFocused }
        .focusable(enabled = isTv)
        .drawBehind {
            if (isTv && focused) {
                drawRoundRect(
                    color = Color.Gray.copy(alpha = 0.72f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())
                )
            }
        }
        .clickable(
            interactionSource = interactionSource,
            // 이름과 달리 이제는 눌림 효과(리플)를 보여준다. 터치 피드백이 없어 눌렀는지 알기 어려웠다.
            indication = LocalIndication.current,
            onClick = onClick
        )
}

@Composable
fun Modifier.tvFocusable(): Modifier {
    val isTv = rememberIsTv()
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .focusable(enabled = isTv)
        .drawBehind {
            if (isTv && focused) {
                drawRoundRect(
                    color = Color.Gray.copy(alpha = 0.72f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())
                )
            }
        }
}

// ============================================================================
// 앱 루트
// ============================================================================

@Composable
fun LilacApp(vm: AnimeViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 1. DataStore 에서 테마 설정 읽기 (저장된 값이 없으면 시스템 설정을 따른다)
    val themeModeFlow = remember {
        context.dataStore.data.map { prefs ->
            val savedName = prefs[THEME_KEY] ?: ThemeMode.SYSTEM.name
            runCatching { ThemeMode.valueOf(savedName) }.getOrDefault(ThemeMode.SYSTEM)
        }
    }
    val themeMode by themeModeFlow.collectAsState(initial = ThemeMode.SYSTEM)
    val onThemeChange: (ThemeMode) -> Unit = { newMode ->
        scope.launch {
            context.dataStore.edit { prefs -> prefs[THEME_KEY] = newMode.name }
        }
    }

    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    LilacTheme(darkTheme = darkTheme) {
        LilacNavHost(vm = vm, themeMode = themeMode, onThemeChange = onThemeChange)
    }
}

@Composable
private fun LilacNavHost(
    vm: AnimeViewModel,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val isTv = rememberIsTv()

    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val selectedTab = if (isTv) null else tabFor(currentRoute)

    val openDetail: (Anime) -> Unit = { nav.navigate(Routes.detail(it.id)) }
    val navigateTo: (String) -> Unit = { route ->
        if (route != Routes.SETTINGS_AI && tabFor(route) != null) nav.navigateTab(route) else nav.navigate(route)
    }
    val reanime = vm.playerSettings.videoSourcePreference == "reanime"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 탭 화면에서만 시스템 바 여백을 Scaffold 가 처리한다. 상세/플레이어는 예전처럼 각자 처리.
        contentWindowInsets = if (selectedTab != null) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
        bottomBar = { if (selectedTab != null) LilacBottomBar(selected = selectedTab, onSelect = navigateTo) }
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(if (selectedTab != null) inner else PaddingValues(0.dp)),
            enterTransition = { fadeIn(tween(160)) },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(160)) },
            popExitTransition = { fadeOut(tween(120)) }
        ) {
            composable(Routes.HOME) {
                when {
                    isTv -> TvHomeScreen(vm, openDetail, navigateTo)
                    reanime -> ReAnimeHomeScreen(vm, openDetail, navigateTo)
                    else -> HomeScreen(vm, openDetail, navigateTo)
                }
            }

            composable(Routes.ALL) {
                if (isTv) TvAllAnimeScreen(vm, openDetail, navigateTo)
                else AllAnimeScreen(vm, openDetail, navigateTo)
            }

            composable(Routes.SEARCH) {
                when {
                    isTv -> TvSearchScreen(vm, openDetail, navigateTo)
                    reanime -> ReAnimeSearchScreen(vm, openDetail, navigateTo)
                    else -> SearchScreen(vm, openDetail, navigateTo)
                }
            }

            composable(Routes.HISTORY) {
                if (isTv) TvHistoryScreen(vm, openDetail, navigateTo)
                else LibraryScreen(vm, openDetail, navigateTo, initialTab = 1)
            }

            composable(Routes.LIBRARY) {
                if (isTv) TvLibraryScreen(vm, openDetail, navigateTo)
                else LibraryScreen(vm, openDetail, navigateTo)
            }

            composable(Routes.SETTINGS) {
                if (isTv) TvSettingsScreen(vm, themeMode, onThemeChange, navigateTo)
                else SettingsScreen(vm, themeMode, onThemeChange, navigateTo)
            }
            composable(Routes.SETTINGS_AI) {
                if (isTv) TvAiSettingsScreen(vm, navigateTo)
                else AiSettingsScreen(vm, navigateTo)
            }

            composable(Routes.DETAIL) { backStack ->
                val id = backStack.arguments?.getString("id")?.let(Uri::decode)
                var item by remember { mutableStateOf(id?.let { vm.getAnime(context, it) }) }

                LaunchedEffect(id) {
                    if (item == null && id != null) {
                        item = OfflineStore.getAnime(context, id)
                    }
                }

                val currentItem = item
                val play: (Episode) -> Unit = { ep ->
                    currentItem?.let { nav.navigate(Routes.player(it.id, ep.id)) }
                }
                val openRelated: (Anime) -> Unit = { related ->
                    vm.cacheAnime(related)
                    nav.navigate(Routes.detail(related.id))
                }
                when {
                    currentItem != null -> when {
                        isTv -> TvDetailScreen(
                            vm = vm,
                            anime = currentItem,
                            back = { nav.popBackStack() },
                            playEpisode = play,
                            openRelated = openRelated
                        )
                        reanime -> ReAnimeDetailScreen(
                            vm = vm,
                            anime = currentItem,
                            back = { nav.popBackStack() },
                            playEpisode = play,
                            openRelated = openRelated
                        )
                        else -> DetailScreen(
                            vm = vm,
                            anime = currentItem,
                            back = { nav.popBackStack() },
                            playEpisode = play,
                            openRelated = openRelated
                        )
                    }
                    vm.loading -> FullScreenState(message = "불러오는 중...", isLoading = true)
                    else -> FullScreenState(
                        message = "해당 작품을 찾을 수 없습니다.",
                        isLoading = false,
                        onRetry = { vm.loadAnime(context) },
                        onBack = { nav.popBackStack() }
                    )
                }
            }

            composable(Routes.ANIMENOSUB_AUTH) {
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                        }
                        Text("Animenosub 인증", style = MaterialTheme.typography.titleMedium)
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                webViewClient = WebViewClient()
                                loadUrl("https://animenosub.to/mushoku-tensei-jobless-reincarnation-season-3-episode-10/")
                            }
                        }
                    )
                }
            }

            composable(Routes.PLAYER) { backStack ->
                val animeId = backStack.arguments?.getString("animeId")?.let(Uri::decode)
                val episodeKey = backStack.arguments?.getString("episodeKey")?.let(Uri::decode)
                var anime by remember { mutableStateOf(animeId?.let { vm.getAnime(context, it) }) }
                var episode by remember { mutableStateOf<Episode?>(null) }

                LaunchedEffect(animeId, episodeKey) {
                    if (anime == null && animeId != null) {
                        anime = OfflineStore.getAnime(context, animeId)
                    }
                    val targetAnime = anime
                    if (targetAnime != null && !episodeKey.isNullOrBlank()) {
                        val epFromCache = vm.episodes(targetAnime).firstOrNull { it.id == episodeKey }
                            ?: vm.dubEpisodes(targetAnime).firstOrNull { it.id == episodeKey }
                            ?: vm.episodes(targetAnime).firstOrNull { it.displayNumber.equals(episodeKey, ignoreCase = true) }
                            ?: vm.dubEpisodes(targetAnime).firstOrNull { it.displayNumber.equals(episodeKey, ignoreCase = true) }
                        if (epFromCache != null) {
                            episode = epFromCache
                        } else {
                            val number = Regex("^\\d+").find(episodeKey)?.value?.toIntOrNull()
                            val stored = if (number != null) {
                                OfflineStore.getEpisodesForAnime(context, targetAnime.id)
                                    .firstOrNull { it.id == episodeKey || it.displayNumber.equals(episodeKey, ignoreCase = true) }
                                    ?: OfflineStore.getEpisode(context, targetAnime.id, number)
                            } else null
                            if (stored != null) {
                                episode = stored
                            } else if (number != null) {
                                episode = Episode(
                                    id = episodeKey,
                                    number = number,
                                    title = "${episodeKey}화",
                                    displayNumber = episodeKey
                                )
                            }
                        }
                    }
                }

                val currentAnime = anime
                val currentEpisode = episode

                when {
                    currentAnime != null && currentEpisode != null -> PlayerScreen(
                        anime = currentAnime,
                        episode = currentEpisode,
                        vm = vm,
                        back = { nav.popBackStack() }
                    )
                    vm.loading -> FullScreenState(message = "불러오는 중...", isLoading = true)
                    else -> FullScreenState(
                        message = "재생할 항목을 찾을 수 없습니다.",
                        isLoading = false,
                        onRetry = { vm.loadAnime(context) },
                        onBack = { nav.popBackStack() }
                    )
                }
            }
        }
    }
}

// ============================================================================
// 전체 화면 상태 / 예전 Scaffold 호환
// ============================================================================

@Composable
fun FullScreenState(
    message: String,
    isLoading: Boolean,
    onRetry: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isLoading) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(message, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onRetry != null) {
                    Button(onClick = onRetry) { Text("다시 시도") }
                }
                if (onBack != null) {
                    OutlinedButton(onClick = onBack) { Text("뒤로") }
                }
            }
        }
    }
}

/**
 * 예전에는 화면마다 자기 Scaffold(+하단 바)를 만들어서 탭을 옮길 때마다 하단 바가 새로 그려지고
 * 상태가 사라졌다. 하단 바는 이제 LilacApp 의 루트 Scaffold 하나가 맡는다.
 * 아직 이 함수를 쓰는 화면(설정, 검색 등)이 그대로 동작하도록 내용만 그대로 내보낸다.
 */
@Composable
fun AppScaffold(
    @Suppress("UNUSED_PARAMETER") selected: String,
    @Suppress("UNUSED_PARAMETER") onSelect: (String) -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    content(PaddingValues(0.dp))
}
