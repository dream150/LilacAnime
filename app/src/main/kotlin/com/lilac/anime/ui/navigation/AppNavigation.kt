package com.lilac.anime
import android.net.Uri

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lilac.anime.data.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
@Composable
fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick
    )
}

// ==========================================
// 2. 메인 App Composable (DataStore 연동 완료)
// ==========================================
@Composable
fun LilacApp(vm: AnimeViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 1. DataStore에서 테마 상태 읽기
    val themeModeFlow = remember {
        context.dataStore.data.map { prefs ->
            val savedName = prefs[THEME_KEY] ?: ThemeMode.LIGHT.name
            runCatching { ThemeMode.valueOf(savedName) }.getOrDefault(ThemeMode.LIGHT)
        }
    }
    // State 수집 (by 키워드로 값 가져오기)
    val themeMode by themeModeFlow.collectAsState(initial = ThemeMode.LIGHT)

    // 2. DataStore에 테마 저장하는 콜백 함수
    val onThemeChange: (ThemeMode) -> Unit = { newMode ->
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[THEME_KEY] = newMode.name
            }
        }
    }

    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colors = if (darkTheme) {
        darkColorScheme(
            primary = Lilac,
            onPrimary = Color.White,
            secondary = LilacDark,
            onSecondary = Color.White,
            background = DarkBackground,
            onBackground = Color(0xFFF0EDF6),
            surface = DarkSurface,
            onSurface = Color(0xFFF0EDF6)
        )
    } else {
        lightColorScheme(
            primary = Lilac,
            onPrimary = Color.White,
            secondary = LilacDark,
            onSecondary = Color.White,
            background = LightBackground,
            onBackground = Color(0xFF1C1B1F),
            surface = LightSurface,
            onSurface = Color(0xFF1C1B1F)
        )
    }

    val nav = rememberNavController()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "알림 권한이 없어 다운로드 진행 상태가 표시되지 않을 수 있습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    MaterialTheme(colorScheme = colors) {
        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    vm = vm,
                    openDetail = { nav.navigate("detail/${it.id}") },
                    onNavigate = { nav.navigate(it) }
                )
            }

            composable("all") {
                LaunchedEffect(Unit) {
                    vm.loadAllAnime()
                }
                AllAnimeScreen(
                    vm = vm,
                    openDetail = { nav.navigate("detail/${it.id}") },
                    onNavigate = { nav.navigate(it) }
                )
            }

            composable("search") {
                SearchScreen(
                    vm = vm,
                    open = { nav.navigate("detail/${it.id}") },
                    onNavigate = { nav.navigate(it) }
                )
            }

            composable("history") {
                WatchHistoryScreen(
                    vm = vm,
                    open = { nav.navigate("detail/${it.id}") },
                    onNavigate = { nav.navigate(it) }
                )
            }

            composable("library") {
                LibraryScreen(
                    vm = vm,
                    open = { nav.navigate("detail/${it.id}") },
                    onNavigate = { nav.navigate(it) }
                )
            }

            composable("settings") {
                SettingsScreen(
                    vm = vm,
                    themeMode = themeMode,
                    onThemeChange = onThemeChange, // DataStore 저장 함수 연결
                    onNavigate = { nav.navigate(it) }
                )
            }

            composable("detail/{id}") { backStack ->
                val id = backStack.arguments?.getString("id")
                var item by remember { mutableStateOf(id?.let { vm.getAnime(context, it) }) }

                LaunchedEffect(id) {
                    if (item == null && id != null) {
                        item = OfflineStore.getAnime(context, id)
                    }
                }

                val currentItem = item
                when {
                    currentItem != null -> {
                        DetailScreen(
                            vm = vm,
                            anime = currentItem,
                            back = { nav.popBackStack() },
                            playEpisode = { ep ->
                                nav.navigate("player/${currentItem.id}/${Uri.encode(ep.id)}")
                            }
                        )
                    }
                    vm.loading -> {
                        FullScreenState(message = "불러오는 중...", isLoading = true)
                    }
                    else -> {
                        FullScreenState(
                            message = "해당 작품을 찾을 수 없습니다.",
                            isLoading = false,
                            onRetry = { vm.loadAnime(context) },
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
            }

            composable("animenosub-auth") {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                        }
                        Text(
                            "Animenosub 인증",
                            style = MaterialTheme.typography.titleMedium
                        )
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

            composable("player/{animeId}/{episodeKey}") { backStack ->
                val animeId = backStack.arguments?.getString("animeId")
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
                    currentAnime != null && currentEpisode != null -> {
                        PlayerScreen(
                            anime = currentAnime,
                            episode = currentEpisode,
                            vm = vm,
                            back = { nav.popBackStack() }
                        )
                    }
                    vm.loading -> {
                        FullScreenState(message = "불러오는 중...", isLoading = true)
                    }
                    else -> {
                        FullScreenState(
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
}

// ============================================================
// FULL SCREEN STATE & SCAFFOLD
// ============================================================

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
                CircularProgressIndicator(color = Lilac)
            } else {
                Icon(Icons.Default.ErrorOutline, null, tint = LilacDark, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(message, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onRetry != null) {
                    Button(onClick = onRetry) { Text("다시 시도", color = Color.White) }
                }
                if (onBack != null) {
                    OutlinedButton(onClick = onBack) { Text("뒤로", color = MaterialTheme.colorScheme.onBackground) }
                }
            }
        }
    }
}

@Composable
fun AppScaffold(
    selected: String,
    onSelect: (String) -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    val items = listOf(
        Triple("home", "홈", Icons.Default.Home),
        Triple("all", "전체", Icons.Default.GridView),
        Triple("search", "검색", Icons.Default.Search),
        Triple("history", "시청기록", Icons.Default.History),
        Triple("library", "내 목록", Icons.Default.Favorite),
        Triple("settings", "설정", Icons.Default.Settings)
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                items.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = selected == route,
                        onClick = { onSelect(route) },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) }
                    )
                }
            }
        },
        content = content
    )
}

// ============================================================
// HOME
// ============================================================

