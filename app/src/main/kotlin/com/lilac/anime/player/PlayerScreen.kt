package com.lilac.anime.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.graphics.Typeface
import android.widget.Toast
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lilac.anime.Anime
import com.lilac.anime.ui.navigation.tvFocusable
import com.lilac.anime.Episode
import com.lilac.anime.MainActivity
import com.lilac.anime.core.model.ChapterSkipSegment
import com.lilac.anime.core.model.StreamQuality
import com.lilac.anime.data.offline.MpvOfflineStore
import com.lilac.anime.data.offline.OfflineStore
import com.lilac.anime.data.subtitle.SubtitleStore
import com.lilac.anime.data.subtitle.SubtitleAssetUtil
import com.lilac.anime.data.subtitle.KairanSubtitleService
import com.lilac.anime.data.subtitle.CsoraSubtitleService
import com.lilac.anime.data.subtitle.KairanSubtitleResult
import com.lilac.anime.data.subtitle.StreamUrlExtractor
import com.lilac.anime.data.subtitle.downloadSubtitleFile
import com.lilac.anime.network.LinkkfPlayerResolver
import com.lilac.anime.network.FlixCloudHlsProxy
import com.lilac.anime.viewmodel.AnimeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * New LinkKF player.
 *
 * There is exactly one playback path:
 *   Episode -> LinkkfPlayerResolver -> captured HLS -> libmpv.
 *
 * The WebView is a resolver only; it is never the playback surface.
 */
@Composable
fun PlayerScreen(
    anime: Anime,
    episode: Episode,
    vm: AnimeViewModel,
    back: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val engine = remember { MpvPlaybackManager.engine(context) }

    var currentEpisode by remember(episode.id) { mutableStateOf(episode) }
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var streamHeaders by remember { mutableStateOf<String?>(null) }
    var streamReferer by remember { mutableStateOf<String?>(null) }
    var subtitleUrl by remember { mutableStateOf<String?>(null) }
    var subtitleReferer by remember { mutableStateOf<String?>(null) }
    var localSubtitle by remember { mutableStateOf<String?>(null) }
    var resolvedVideoPageUrl by remember { mutableStateOf<String?>(null) }
    var parsedStreamingQualities by remember { mutableStateOf<List<StreamQuality>>(emptyList()) }
    var selectedStreamingQuality by remember { mutableStateOf<StreamQuality?>(null) }

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val isTv = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    var controlsVisible by rememberSaveable { mutableStateOf(isTv) }
    val tvPreviousRequester = remember { FocusRequester() }
    val tvRewindRequester = remember { FocusRequester() }
    val tvPlayRequester = remember { FocusRequester() }
    val tvForwardRequester = remember { FocusRequester() }
    val tvNextRequester = remember { FocusRequester() }
    val tvBackRequester = remember { FocusRequester() }
    val tvSettingsRequester = remember { FocusRequester() }
    val tvSkipRequester = remember { FocusRequester() }
    val tvLockRequester = remember { FocusRequester() }
    val tvRootRequester = remember { FocusRequester() }
    var locked by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var tvUiInteractionMode by rememberSaveable { mutableStateOf(false) }
    var tvNavRow by rememberSaveable { mutableStateOf(1) }
    var tvNavCol by rememberSaveable { mutableStateOf(2) }
    var tvSettingsIndex by rememberSaveable { mutableStateOf(0) }
    val focusManager = LocalFocusManager.current
    var subtitleSettingsOpen by remember { mutableStateOf(false) }
    var subtitleSize by rememberSaveable { mutableFloatStateOf(vm.playerSettings.subtitleSize) }
    var subtitleSyncMs by rememberSaveable { mutableLongStateOf(vm.playerSettings.syncOffsetMs) }
    var subtitlePosition by rememberSaveable { mutableFloatStateOf(vm.playerSettings.subtitleBottomPaddingFraction * 100f) }
    var vttBold by rememberSaveable { mutableStateOf(vm.playerSettings.vttBold) }
    var vttStyleEnabled by rememberSaveable { mutableStateOf(vm.playerSettings.vttStyleEnabled) }
    var subtitleSource by rememberSaveable { mutableStateOf(vm.playerSettings.subtitleSourcePreference) }
    val playerScope = rememberCoroutineScope()
    var showUnlockButton by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableFloatStateOf(0f) }
    var volume by remember { mutableFloatStateOf(100f) }
    var speed by rememberSaveable { mutableFloatStateOf(vm.playerSettings.playbackSpeed) }
    var showSkipButton by rememberSaveable { mutableStateOf(vm.playerSettings.showChapterSkipButton) }
    var autoSkip by rememberSaveable { mutableStateOf(vm.playerSettings.autoSkip) }
    var autoPlay by rememberSaveable { mutableStateOf(vm.playerSettings.autoPlay) }
    var chapters by remember { mutableStateOf<List<ChapterSkipSegment>>(emptyList()) }
    var subtitleEnabled by rememberSaveable { mutableStateOf(true) }
    var surfaceAttached by remember { mutableStateOf(false) }
    var vttOutlineWidth by rememberSaveable { mutableFloatStateOf(vm.playerSettings.vttOutlineWidth) }
    var customFontName by remember { mutableStateOf<String?>(null) }
    var selectedSubtitleFontPath by remember(anime.id) { mutableStateOf(vm.playerSettings.subtitleFontPath) }
    var discoveredSubtitleFonts by remember { mutableStateOf<List<SubtitleAssetUtil.FontInfo>>(emptyList()) }
    var savedSubtitles by remember { mutableStateOf<List<SubtitleStore.SavedSubtitle>>(emptyList()) }
    var userSubtitles by remember { mutableStateOf<List<SubtitleStore.SavedSubtitle>>(emptyList()) }
    var showSubtitleManager by remember { mutableStateOf(false) }
    var showUserSubtitleManager by remember { mutableStateOf(false) }

    LaunchedEffect(anime.title, subtitleSource, localSubtitle) {
        discoveredSubtitleFonts = withContext(Dispatchers.IO) {
            val source = when (subtitleSource) { "kairan" -> "kairan"; "csora" -> "csora"; else -> subtitleSource }
            if (source == "kairan" || source == "csora") SubtitleAssetUtil.listFonts(context, anime.title, source) else emptyList()
        }
    }

    LaunchedEffect(anime.id, currentEpisode.displayNumber, currentEpisode.number) {
        savedSubtitles = SubtitleStore.list(context, anime.id, currentEpisode.displayNumber, currentEpisode.number)
        userSubtitles = SubtitleStore.listUser(context, anime.id, currentEpisode.displayNumber, currentEpisode.number)
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        playerScope.launch(Dispatchers.IO) {
            try {
                val mime = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT)
                val ext = if (mime == "font/otf" || uri.toString().contains(".otf", true)) "otf" else "ttf"
                val dir = File(context.filesDir, "custom_fonts").apply { mkdirs() }
                val target = File(dir, "player_custom_font.$ext")
                context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
                    ?: error("font stream null")
                customFontName = target.name
                vm.updatePlayerSettings(context, vm.playerSettings.copy(customFontPath = target.absolutePath))
                withContext(kotlinx.coroutines.Dispatchers.Main) { Toast.makeText(context, "커스텀 폰트를 적용했습니다.", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Log.e("Subtitle", "CUSTOM_FONT_IMPORT_FAILED", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) { Toast.makeText(context, "폰트를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    val subtitleFilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        playerScope.launch(Dispatchers.IO) {
            try {
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "subtitle"
                val lower = name.lowercase(Locale.ROOT)
                val ext = listOf("ass","ssa","srt","vtt","smi").firstOrNull { lower.endsWith(".$it") } ?: error("unsupported")
                val dir = File(context.filesDir, "user_subtitles").apply { mkdirs() }
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val target = File(dir, "${anime.id}_${currentEpisode.displayNumber}_$safe")
                context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } } ?: error("subtitle stream null")
                SubtitleStore.save(context, anime.id, currentEpisode.displayNumber, currentEpisode.number, "user", target.absolutePath)
                userSubtitles = SubtitleStore.listUser(context, anime.id, currentEpisode.displayNumber, currentEpisode.number)
                localSubtitle = target.absolutePath
                engine.replaceSubtitleTrack(target.absolutePath)
                engine.setSubtitleVisible(true)
                withContext(kotlinx.coroutines.Dispatchers.Main) { Toast.makeText(context, "사용자 자막을 적용했습니다.", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Log.e("Subtitle", "USER_SUBTITLE_IMPORT_FAILED", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) { Toast.makeText(context, "자막 파일을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    val orderedEpisodes = remember(anime.episodes) {
        anime.episodes.sortedWith(compareBy<Episode> { it.number }.thenBy { it.displayNumber })
    }

    val currentEpisodeIndex = remember(currentEpisode.id, orderedEpisodes) {
        orderedEpisodes.indexOfFirst { it.id == currentEpisode.id }
    }

    val previousEpisode = orderedEpisodes.getOrNull(currentEpisodeIndex - 1)
    val nextEpisode = orderedEpisodes.getOrNull(currentEpisodeIndex + 1)

    fun switchEpisode(target: Episode) {
        if (target.id == currentEpisode.id) return
        currentEpisode = target
        streamUrl = null
        streamHeaders = null
        streamReferer = null
        subtitleUrl = null
        subtitleReferer = null
        localSubtitle = null
        resolvedVideoPageUrl = null
        parsedStreamingQualities = emptyList()
        selectedStreamingQuality = null
        chapters = emptyList()
        error = null
        loading = true
        controlsVisible = false
        showUnlockButton = false
    }

    fun leave() {
        vm.updateProgress(
            context,
            anime.id,
            currentEpisode.number,
            if (engine.duration > 0) {
                (engine.currentPosition.toDouble() / engine.duration.toDouble())
                    .toFloat().coerceIn(0f, 1f)
            } else 0f,
            currentEpisode.id
        )
        engine.pause()
        engine.detachSurface()
        MainActivity.isVideoPlaying = false
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        back()
    }

    BackHandler {
        if (isTv) {
            when {
                settingsOpen -> settingsOpen = false
                controlsVisible -> { controlsVisible = false; tvUiInteractionMode = false }
                else -> leave()
            }
        } else {
            leave()
        }
    }

    // Playback is always landscape while this screen owns the player.
    // Hide the Android status/navigation bars (including the gesture handle) only
    // while the mobile player is on screen. They are restored when leaving it.
    DisposableEffect(activity, isTv) {
        val host = activity
        if (host != null) {
            host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

            if (!isTv) {
                val controller = WindowCompat.getInsetsController(host.window, host.window.decorView)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        onDispose {
            if (host != null) {
                if (!isTv && host.isFinishing.not()) {
                    val controller = WindowCompat.getInsetsController(host.window, host.window.decorView)
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
                if (host.isChangingConfigurations != true) {
                    host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    // Keep Compose state synchronized with libmpv without making the player itself
    // depend on Compose recomposition.
    LaunchedEffect(engine, currentEpisode.id) {
        while (isActive) {
            if (!isSeeking) {
                position = engine.currentPosition
                if (engine.duration > 0L) {
                    seekPreview = engine.currentPosition.toFloat().coerceIn(0f, engine.duration.toFloat())
                }
            }
            duration = engine.duration
            isPlaying = engine.isPlaying
            delay(100)
        }
    }

    suspend fun resolveCachedSubtitle(episode: Episode, source: String): String? {
        if (source == "user") {
            return withContext(Dispatchers.IO) {
                SubtitleStore.getUser(context, anime.id, episode.id, episode.number)
            }?.takeIf { File(it).isFile }
        }
        if (source !in setOf("kairan", "csora")) return null
        return withContext(Dispatchers.IO) {
            SubtitleStore.get(context, anime.id, episode.id, episode.number, source)
        }?.takeIf { File(it).isFile }?.also {
            Log.d("SubtitleSelect", "CACHE_HIT source=$source path=$it")
        }
    }

    suspend fun resolvePreferredSubtitle(
        episode: Episode,
        sourceOverride: String? = null
    ): String? {
        val preferred = sourceOverride ?: vm.playerSettings.subtitleSourcePreference
        Log.d(
            "SubtitleSelect",
            "REQUEST source=$preferred title=[${anime.title}] episode=${episode.displayNumber}"
        )

        resolveCachedSubtitle(episode, preferred)?.let { return it }

        return when (preferred) {
            "kairan" -> {
                val result = runCatching {
                    KairanSubtitleService.findSubtitle(
                        context,
                        anime.title,
                        episode.number,
                        episode.displayNumber
                    )
                }.onFailure {
                    Log.w("SubtitleSelect", "SEARCH_FAILED source=kairan", it)
                }.getOrNull()

                val path = (result as? KairanSubtitleResult.DirectFile)?.path
                    ?.takeIf { File(it).isFile }

                Log.d("SubtitleSelect", "RESULT source=kairan path=$path")
                path
            }

            "csora" -> {
                val result = runCatching {
                    CsoraSubtitleService.findSubtitle(
                        context,
                        anime.title,
                        episode.number,
                        episode.displayNumber
                    )
                }.onFailure {
                    Log.w("SubtitleSelect", "SEARCH_FAILED source=csora", it)
                }.getOrNull()

                val path = (result as? KairanSubtitleResult.DirectFile)?.path
                    ?.takeIf { File(it).isFile }

                Log.d("SubtitleSelect", "RESULT source=csora path=$path")
                path
            }

            else -> null
        }
    }

    // Resolve playback in three distinct paths:
    // 1) a completed mpv-native offline file is used directly;
    // 2) Re:ANIME is resolved by the hidden WebView + FlixCloud decrypting proxy;
    // 3) LinkKF keeps its direct resolver path.
    LaunchedEffect(currentEpisode.id, currentEpisode.videoUrl) {
        loading = true
        error = null
        resolvedVideoPageUrl = currentEpisode.videoUrl

        val offlinePath = withContext(Dispatchers.IO) {
            MpvOfflineStore.completedPath(context, anime.id, currentEpisode.id)
                ?.takeIf { File(it).isFile && File(it).length() > 0L }
        }

        if (offlinePath != null) {
            streamUrl = offlinePath
            streamHeaders = null
            streamReferer = null
            subtitleUrl = null
            subtitleReferer = null
            localSubtitle = resolveCachedSubtitle(
                currentEpisode,
                vm.playerSettings.subtitleSourcePreference
            ) ?: withContext(Dispatchers.IO) {
                SubtitleStore.list(
                    context, anime.id, currentEpisode.id, currentEpisode.number
                ).firstOrNull { !it.ignored }?.path
                    ?: currentEpisode.vttUrl?.takeIf { File(it).isFile }
            }
            engine.configureNetworkHeaders("", null)
            engine.load(
                url = offlinePath,
                subtitlePath = localSubtitle,
                syncOffsetMs = vm.playerSettings.syncOffsetMs,
                customFontPath = vm.playerSettings.subtitleFontPath
                    ?: vm.playerSettings.customFontPath,
                autoPlay = false
            )
            engine.setAssEffectsEnabled(vm.playerSettings.assEffectsEnabled)
            engine.setSpeed(speed)
            engine.setSubtitleDelay(vm.playerSettings.syncOffsetMs)
            engine.applySubtitleStyle(
                vm.playerSettings.textColor,
                vm.playerSettings.strokeColor,
                vm.playerSettings.subtitleSize,
                vm.playerSettings.vttBold,
                vm.playerSettings.vttOutlineWidth,
                vm.playerSettings.subtitleBottomPaddingFraction,
                false
            )
            repeat(60) {
                if (engine.duration > 0L) return@repeat
                delay(100)
            }
            val saved = vm.getProgress(anime.id, currentEpisode.id, currentEpisode.number)
            val resume = saved?.progress
                ?.takeUnless { it >= 0.95f }
                ?.let { (engine.duration * it).toLong() } ?: 0L
            if (resume > 0L && engine.duration > 0L) {
                engine.seekTo(resume.coerceAtMost(engine.duration - 250L))
            }
            engine.play()
            MainActivity.isVideoPlaying = true
            loading = false
            return@LaunchedEffect
        }

        val source = vm.playerSettings.videoSourcePreference
        if (source == "reanime") {
            // StreamUrlExtractor will capture the real FlixCloud M3U8 asynchronously.
            // Do not run LinkKFPlayerResolver against a Re:ANIME watch page.
            streamUrl = null
            streamHeaders = null
            streamReferer = null
            subtitleUrl = null
            subtitleReferer = null
            localSubtitle = null
            return@LaunchedEffect
        }

        val resolved = withContext(Dispatchers.Main.immediate) {
            runCatching {
                LinkkfPlayerResolver.resolve(context, currentEpisode)
            }.getOrNull()
        }

        if (resolved?.m3u8Url.isNullOrBlank()) {
            loading = false
            error = "영상 스트림을 찾지 못했습니다."
            return@LaunchedEffect
        }

        streamUrl = resolved!!.m3u8Url
        streamHeaders = resolved.headers
        streamReferer = resolved.referer
        subtitleUrl = resolved.subtitleUrl
        subtitleReferer = resolved.subtitleReferer

        // Never block video startup on a remote subtitle download. Reuse an already
        // cached subtitle immediately, then download a missing one in parallel and
        // attach it to the running mpv instance when it arrives.
        localSubtitle = resolveCachedSubtitle(
            currentEpisode,
            vm.playerSettings.subtitleSourcePreference
        ) ?: withContext(Dispatchers.IO) {
            SubtitleStore.list(
                context, anime.id, currentEpisode.id, currentEpisode.number
            ).firstOrNull { !it.ignored }?.path
        }

        engine.configureNetworkHeaders(
            streamHeaders.orEmpty(),
            streamReferer
        )

        engine.load(
            url = resolved.m3u8Url,
            subtitlePath = localSubtitle,
            syncOffsetMs = vm.playerSettings.syncOffsetMs,
            customFontPath = vm.playerSettings.subtitleFontPath
                ?: vm.playerSettings.customFontPath,
            autoPlay = false
        )
        engine.setAssEffectsEnabled(vm.playerSettings.assEffectsEnabled)
        engine.setSpeed(speed)
        engine.setSubtitleDelay(vm.playerSettings.syncOffsetMs)
        engine.applySubtitleStyle(
            vm.playerSettings.textColor,
            vm.playerSettings.strokeColor,
            vm.playerSettings.subtitleSize,
            vm.playerSettings.vttBold,
            vm.playerSettings.vttOutlineWidth,
            vm.playerSettings.subtitleBottomPaddingFraction,
            false
        )
        repeat(60) {
            if (engine.duration > 0L) return@repeat
            delay(100)
        }
        val saved = vm.getProgress(anime.id, currentEpisode.id, currentEpisode.number)
        val resume = saved?.progress
            ?.takeUnless { it >= 0.95f }
            ?.let { (engine.duration * it).toLong() } ?: 0L
        if (resume > 0L && engine.duration > 0L) {
            engine.seekTo(resume.coerceAtMost((engine.duration - 250L).coerceAtLeast(0L)))
        }
        engine.play()
        MainActivity.isVideoPlaying = true

        val subtitleToFetch = resolved.subtitleUrl ?: currentEpisode.vttUrl
        if (localSubtitle.isNullOrBlank() && !subtitleToFetch.isNullOrBlank()) {
            playerScope.launch(Dispatchers.IO) {
                val downloaded = runCatching {
                    downloadSubtitleFile(
                        context = context,
                        animeId = anime.id,
                        episodeNumber = currentEpisode.number,
                        vttUrl = subtitleToFetch,
                        episodeKey = currentEpisode.id,
                        referer = resolved.subtitleReferer ?: resolved.referer
                    )
                }.getOrNull()
                if (!downloaded.isNullOrBlank() && File(downloaded).isFile) {
                    localSubtitle = downloaded
                    withContext(Dispatchers.Main) {
                        engine.replaceSubtitleTrack(downloaded)
                        engine.setSubtitleDelay(vm.playerSettings.syncOffsetMs)
                        engine.setSubtitleVisible(subtitleEnabled)
                    }
                }
            }
        }
    }

    // Re:ANIME/FlixCloud stream resolver. The WebView is deliberately 1dp and transparent:
    // it only observes the provider request; libmpv remains the visible playback surface.
    if (vm.playerSettings.videoSourcePreference == "reanime" &&
        !resolvedVideoPageUrl.isNullOrBlank() &&
        streamUrl == null
    ) {
        val extractorTargetUrl = resolvedVideoPageUrl.orEmpty()
        StreamUrlExtractor(
            targetUrl = extractorTargetUrl,
            modifier = Modifier.size(1.dp),
            reAnimeAnilistId = Regex("(?:bx|/anime/)(\\d+)")
                .find(anime.poster)
                ?.groupValues?.getOrNull(1)
                ?.toIntOrNull(),
            reAnimeEpisodeNumber = currentEpisode.number,
            onQualitiesFound = { qualities ->
                if (resolvedVideoPageUrl != extractorTargetUrl ||
                    currentEpisode.videoUrl != extractorTargetUrl ||
                    vm.playerSettings.videoSourcePreference != "reanime"
                ) return@StreamUrlExtractor

                parsedStreamingQualities = qualities
                val selected = qualities.firstOrNull() ?: return@StreamUrlExtractor
                selectedStreamingQuality = selected
                streamHeaders = selected.headers
                selected.referer?.let { streamReferer = it }

                val pk = selected.flixCloudPk
                streamUrl = if (!pk.isNullOrBlank() &&
                    selected.url.contains("m3u8", ignoreCase = true)
                ) {
                    FlixCloudHlsProxy.createProxyUrl(
                        upstreamUrl = selected.url,
                        pkBase64 = pk,
                        headers = selected.headers
                    ).also {
                        android.util.Log.d(
                            "LilacMpv",
                            "FLIXCLOUD_PROXY_CREATED episode=${currentEpisode.displayNumber} url=$it"
                        )
                    }
                } else {
                    selected.url
                }
                loading = true
                error = null
            },
            onSubtitleFound = { foundUrl ->
                if (resolvedVideoPageUrl == extractorTargetUrl &&
                    currentEpisode.videoUrl == extractorTargetUrl &&
                    foundUrl.isNotBlank()
                ) {
                    subtitleUrl = foundUrl
                    localSubtitle = foundUrl
                }
            },
            onSubtitleRefererFound = { _, referer ->
                if (resolvedVideoPageUrl == extractorTargetUrl) {
                    subtitleReferer = referer
                }
            },
            onRefererFound = { referer ->
                if (resolvedVideoPageUrl == extractorTargetUrl) {
                    streamReferer = referer
                }
            },
            onFlixCloudPkFound = { pk ->
                android.util.Log.d(
                    "ReAnimeStream",
                    "FLIXCLOUD_PK_READY episode=${currentEpisode.displayNumber} length=${pk.length}"
                )
            }
        )
    }

    // Kairan/Csora are searched in the background for BOTH LinkKF and Re:ANIME.
    // Never block video startup on a blog search/download. If a subtitle was already
    // cached it is attached immediately; otherwise the blog search runs in parallel
    // and the resulting ASS file is attached to the running mpv instance.
    LaunchedEffect(
        anime.id,
        currentEpisode.id,
        currentEpisode.number,
        vm.playerSettings.subtitleSourcePreference
    ) {
        val preferred = vm.playerSettings.subtitleSourcePreference
        if (preferred !in setOf("kairan", "csora")) return@LaunchedEffect

        Log.d(
            "SubtitleSelect",
            "BACKGROUND_SEARCH source=$preferred title=[${anime.title}] episode=${currentEpisode.displayNumber}"
        )

        val path = resolvePreferredSubtitle(currentEpisode, preferred)
        if (!isActive) return@LaunchedEffect

        if (!path.isNullOrBlank() && File(path).isFile) {
            localSubtitle = path
            Log.d(
                "SubtitleSelect",
                "BACKGROUND_ATTACH source=$preferred path=$path"
            )
            engine.replaceSubtitleTrack(path)
            engine.setSubtitleDelay(vm.playerSettings.syncOffsetMs)
            engine.setSubtitleVisible(subtitleEnabled)
        } else {
            Log.d(
                "SubtitleSelect",
                "BACKGROUND_NONE source=$preferred episode=${currentEpisode.displayNumber}"
            )
        }
    }

    // Once the Re:ANIME extractor has produced the decrypted/proxied HLS URL,
    // hand it to libmpv. This is separate from the page resolver because the WebView
    // callback is asynchronous.
    LaunchedEffect(streamUrl, currentEpisode.id) {
        if (vm.playerSettings.videoSourcePreference != "reanime") return@LaunchedEffect
        val url = streamUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (url.startsWith("/") || url.startsWith("file://")) return@LaunchedEffect

        engine.configureNetworkHeaders(
            streamHeaders.orEmpty(),
            streamReferer
        )

        val saved = vm.getProgress(anime.id, currentEpisode.id, currentEpisode.number)
        engine.load(
            url = url,
            subtitlePath = localSubtitle?.takeIf { File(it).isFile },
            syncOffsetMs = vm.playerSettings.syncOffsetMs,
            customFontPath = vm.playerSettings.subtitleFontPath
                ?: vm.playerSettings.customFontPath,
            autoPlay = false
        )
        engine.setAssEffectsEnabled(vm.playerSettings.assEffectsEnabled)
        engine.setSpeed(speed)
        engine.setSubtitleDelay(vm.playerSettings.syncOffsetMs)
        engine.applySubtitleStyle(
            vm.playerSettings.textColor,
            vm.playerSettings.strokeColor,
            vm.playerSettings.subtitleSize,
            vm.playerSettings.vttBold,
            vm.playerSettings.vttOutlineWidth,
            vm.playerSettings.subtitleBottomPaddingFraction,
            false
        )

        repeat(80) {
            if (engine.duration > 0L) return@repeat
            delay(100)
        }
        val resume = saved?.progress
            ?.takeUnless { it >= 0.95f }
            ?.let { (engine.duration * it).toLong() } ?: 0L
        if (resume > 0L && engine.duration > 0L) {
            engine.seekTo(resume.coerceAtMost(engine.duration - 250L))
        }
        engine.play()
        MainActivity.isVideoPlaying = true
    }

    // OP/ED resolution is deliberately split by playback mode.
    // Streaming -> AniSkip only.
    // Offline -> saved AniSkip first, then local audio analysis when AniSkip
    // was unavailable at download time (or this is an older offline file).
    LaunchedEffect(currentEpisode.id, anime.id, anime.title, anime.anilistId) {
        chapters = emptyList()
        val offlineEpisodes = withContext(Dispatchers.IO) {
            val localPath = MpvOfflineStore.completedPath(context, anime.id, currentEpisode.id)
            if (localPath != null) OfflineStore.getEpisodesForAnime(context, anime.id) else emptyList()
        }

        if (offlineEpisodes.isNotEmpty()) {
            val resolved = com.lilac.anime.network.OpEdSkipResolver.resolveOffline(
                context = context,
                animeId = anime.id,
                episode = currentEpisode,
                episodes = offlineEpisodes
            )
            chapters = resolved
            android.util.Log.d(
                "AniSkip",
                "PLAYER_OFFLINE_RESOLVED anime=${anime.id} episode=${currentEpisode.number} segments=${resolved.size}"
            )
        } else {
            val online = com.lilac.anime.network.OpEdSkipResolver.resolveOnline(
                title = anime.title,
                episodeNumber = currentEpisode.number,
                anilistId = anime.anilistId
            )
            chapters = online
            android.util.Log.d(
                "AniSkip",
                "PLAYER_ONLINE_RESOLVED anime=${anime.id} episode=${currentEpisode.number} segments=${online.size}"
            )
        }
    }

    LaunchedEffect(engine, currentEpisode.id, streamUrl) {
        if (streamUrl == null) return@LaunchedEffect
        var ready = false
        repeat(160) {
            if (engine.playbackState == MpvPlayerEngine.STATE_READY && engine.duration > 0L) {
                ready = true
                return@repeat
            }
            delay(100)
        }
        if (ready) {
            loading = false
            controlsVisible = true
            tvUiInteractionMode = false
        }
    }

    // Automatic OP/ED skip is deliberately state-based: seeking back into the
    // same chapter creates a new opportunity to skip it.
    LaunchedEffect(currentEpisode.id, chapters, autoSkip) {
        var skippedKey: String? = null
        while (isActive) {
            if (autoSkip && chapters.isNotEmpty()) {
                val seconds = engine.currentPosition / 1000.0
                val active = chapters.firstOrNull {
                    seconds >= it.startTime && seconds < it.endTime
                }
                if (active == null) {
                    skippedKey = null
                } else {
                    val key = "${active.type}:${active.startTime}:${active.endTime}"
                    if (skippedKey != key) {
                        val target = active.endTime
                        if (target > seconds && target - seconds <= 30.0) {
                            engine.seekTo((target * 1000.0).toLong())
                            skippedKey = key
                        }
                    }
                }
            }
            delay(200)
        }
    }

    // Natural end-of-file -> next episode. This uses the engine's real EOF
    // event, not a guessed percentage threshold.
    LaunchedEffect(currentEpisode.id, nextEpisode) {
        var seenGeneration = engine.endFileEventGeneration
        while (isActive) {
            val generation = engine.endFileEventGeneration
            if (generation != seenGeneration) {
                seenGeneration = generation
                if (vm.playerSettings.autoPlay && nextEpisode != null) {
                    switchEpisode(nextEpisode)
                    break
                }
            }
            delay(100)
        }
    }

    LaunchedEffect(anime.title, currentEpisode.id) {
        MpvPlaybackManager.updateNowPlaying(
            context,
            anime.title,
            currentEpisode.title.ifBlank { "${currentEpisode.number}화" }
        )
    }

    fun skipCurrentChapter() {
        val current = chapters.firstOrNull {
            val s = engine.currentPosition / 1000.0
            s >= it.startTime && s < it.endTime
        } ?: return
        engine.seekTo((current.endTime * 1000.0).toLong())
    }

    val currentChapter = chapters.firstOrNull {
        val s = position / 1000.0
        s >= it.startTime && s < it.endTime
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(tvRootRequester)
            .focusable(enabled = isTv && !controlsVisible && !settingsOpen)
            .onPreviewKeyEvent { event ->
                if (!isTv || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val center = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (event.key == Key.Back || event.key == Key.Escape) {
                    when {
                        settingsOpen -> { settingsOpen = false; true }
                        controlsVisible -> { controlsVisible = false; tvRootRequester.requestFocus(); true }
                        else -> { leave(); true }
                    }
                } else if (!controlsVisible && center) {
                    controlsVisible = true
                    if (engine.isPlaying) engine.pause() else engine.play()
                    MainActivity.isVideoPlaying = engine.isPlaying
                    true
                } else false
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MpvPlayerSurfaceView(ctx, engine).apply {
                    // On TV the video surface must not keep D-pad focus.
                    // Focus belongs to the Compose player controls instead.
                    seekSeconds = vm.playerSettings.doubleTapSeekSeconds.coerceAtLeast(0L)
                    gesturesLocked = locked
                    onSingleTap = {
                        if (locked) showUnlockButton = true
                        else controlsVisible = !controlsVisible
                    }
                    onUnlockTap = {
                        locked = false
                        showUnlockButton = false
                        controlsVisible = true
                    }
                    // MpvPlayerSurfaceView performs the actual seek exactly once.
                    // This callback is intentionally UI-only to avoid double-seeking.
                    onDoubleTap = { _ -> }
                    if (isTv) {
                        isFocusable = false
                        isFocusableInTouchMode = false
                    }
                }
            },
            update = { view ->
                view.gesturesLocked = locked
                view.seekSeconds = vm.playerSettings.doubleTapSeekSeconds.coerceAtLeast(0L)
            }
        )

        LaunchedEffect(isTv, controlsVisible, settingsOpen) {
            if (isTv) {
                if (controlsVisible && !settingsOpen) tvPlayRequester.requestFocus()
                else if (!controlsVisible) tvRootRequester.requestFocus()
            }
        }

        // TV controls should behave like an overlay, not a permanently focused
        // menu. While playback is active they disappear automatically.
        LaunchedEffect(isTv, controlsVisible, isPlaying, settingsOpen, locked) {
            if (isTv && controlsVisible && isPlaying && !settingsOpen && !locked) {
                delay(3500)
                if (isActive && isPlaying && !settingsOpen && !locked) {
                    controlsVisible = false
                    tvUiInteractionMode = false
                    tvRootRequester.requestFocus()
                }
            }
        }

        // Mobile controls auto-hide after exactly 2 seconds. Opening the settings
        // menu keeps the controls alive until the menu is dismissed.
        LaunchedEffect(isTv, controlsVisible, settingsOpen, locked) {
            if (!isTv && controlsVisible && !settingsOpen && !locked) {
                delay(2000)
                if (isActive && !settingsOpen && !locked) {
                    controlsVisible = false
                }
            }
        }

        // Subtle cinematic scrims keep controls readable without permanently
        // darkening the actual video.
        if (!locked && controlsVisible) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = .72f), Color.Transparent)
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = .84f))
                        )
                    )
            )
        }

        if (loading) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = .72f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .14f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("재생 준비 중", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("영상 스트림을 연결하고 있습니다…", color = Color.White.copy(.65f), fontSize = 12.sp)
                }
            }
        }

        if (error != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = .82f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .14f))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("재생할 수 없습니다", color = Color.White, fontSize = 17.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(error.orEmpty(), color = Color.White.copy(.68f), fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        onClick = {
                            loading = true
                            error = null
                            streamUrl = null
                            controlsVisible = false
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = .12f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Replay10, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("다시 시도", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        if (locked) {
            // The locked state deliberately leaves only the unlock affordance.
            if (showUnlockButton) {
                Surface(
                    onClick = {
                        locked = false
                        showUnlockButton = false
                        controlsVisible = true
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = .68f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .22f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.LockOpen, "잠금 해제", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
        } else if (isTv && controlsVisible) {
            // Netflix-style 10-foot player: one focus target at a time, no
            // synthetic navigation state and no nested clickable/focusable layers.
            val tvAccent = Color(0xFFE50914)
            val remaining = (duration - position).coerceAtLeast(0L)

            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(220.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(.90f), Color.Transparent))))
                Box(Modifier.fillMaxWidth().height(340.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.96f)))))

                Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 52.dp, top = 38.dp, end = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvActionButton(Icons.Default.ArrowBack, "뒤로", tvBackRequester, compact = true) { leave() }
                    Spacer(Modifier.width(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(anime.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("${currentEpisode.number}화${currentEpisode.title.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""}", color = Color.White.copy(.70f), fontSize = 14.sp, maxLines = 1)
                    }
                    Box {
                        TvActionButton(Icons.Default.Settings, "설정", tvSettingsRequester, compact = true) { settingsOpen = true }
                        TvPlayerSettingsMenu(
                            expanded = settingsOpen,
                            onDismiss = { settingsOpen = false },
                            autoPlay = autoPlay,
                            showSkipButton = showSkipButton,
                            autoSkip = autoSkip,
                            subtitleEnabled = subtitleEnabled,
                            assEffectsEnabled = vm.playerSettings.assEffectsEnabled,
                            subtitleSource = subtitleSource,
                            subtitleSize = subtitleSize,
                            subtitlePosition = subtitlePosition,
                            subtitleSyncMs = subtitleSyncMs,
                            speed = speed,
                            onActivate = { index ->
                                when (index) {
                                    0 -> { autoPlay = !autoPlay; vm.updatePlayerSettings(context, vm.playerSettings.copy(autoPlay = autoPlay)) }
                                    1 -> { showSkipButton = !showSkipButton; vm.updatePlayerSettings(context, vm.playerSettings.copy(showChapterSkipButton = showSkipButton)) }
                                    2 -> { autoSkip = !autoSkip; vm.updatePlayerSettings(context, vm.playerSettings.copy(autoSkip = autoSkip)) }
                                    3 -> {
                                        subtitleEnabled = !subtitleEnabled
                                        engine.setSubtitleVisible(subtitleEnabled)
                                    }
                                    4 -> {
                                        val enabled = !vm.playerSettings.assEffectsEnabled
                                        vm.updatePlayerSettings(context, vm.playerSettings.copy(assEffectsEnabled = enabled))
                                        engine.setAssEffectsEnabled(enabled)
                                    }
                                    5 -> {
                                        val sources = listOf("linkkf", "kairan", "csora", "user")
                                        val idx = sources.indexOf(subtitleSource).coerceAtLeast(0)
                                        subtitleSource = sources[(idx + 1) % sources.size]
                                        vm.updatePlayerSettings(context, vm.playerSettings.copy(subtitleSourcePreference = subtitleSource))
                                        playerScope.launch {
                                            val path = if (subtitleSource == "user") {
                                                resolveCachedSubtitle(currentEpisode, "user")
                                            } else if (subtitleSource == "kairan" || subtitleSource == "csora") {
                                                resolvePreferredSubtitle(currentEpisode, subtitleSource)
                                            } else {
                                                withContext(Dispatchers.IO) {
                                                    SubtitleStore.get(context, anime.id, currentEpisode.id, currentEpisode.number, subtitleSource)
                                                }
                                            }
                                            if (!path.isNullOrBlank() && File(path).isFile) {
                                                localSubtitle = path
                                                engine.replaceSubtitleTrack(path)
                                                engine.setSubtitleDelay(subtitleSyncMs)
                                                engine.setSubtitleVisible(subtitleEnabled)
                                            }
                                        }
                                    }
                                    6 -> {
                                        subtitleSize = (subtitleSize + 10f).let { if (it > 200f) 50f else it }
                                        vm.updatePlayerSettings(context, vm.playerSettings.copy(subtitleSize = subtitleSize))
                                        engine.applySubtitleStyle(vm.playerSettings.textColor, vm.playerSettings.strokeColor, subtitleSize, vttBold, vm.playerSettings.vttOutlineWidth, subtitlePosition / 100f, false)
                                    }
                                    7 -> {
                                        subtitlePosition = (subtitlePosition + 5f).let { if (it > 30f) 3f else it }
                                        vm.updatePlayerSettings(context, vm.playerSettings.copy(subtitleBottomPaddingFraction = subtitlePosition / 100f))
                                        engine.applySubtitleStyle(vm.playerSettings.textColor, vm.playerSettings.strokeColor, subtitleSize, vttBold, vm.playerSettings.vttOutlineWidth, subtitlePosition / 100f, false)
                                    }
                                    8 -> {
                                        subtitleSyncMs += 250L
                                        vm.updatePlayerSettings(context, vm.playerSettings.copy(syncOffsetMs = subtitleSyncMs))
                                        engine.setSubtitleDelay(subtitleSyncMs)
                                    }
                                    9 -> {
                                        speed = when { speed < 1.0f -> 1.0f; speed < 1.25f -> 1.25f; speed < 1.5f -> 1.5f; speed < 2.0f -> 2.0f; else -> 0.5f }
                                        engine.setSpeed(speed)
                                        vm.updatePlayerSettings(context, vm.playerSettings.copy(playbackSpeed = speed))
                                    }
                                }
                            }
                        )
                    }
                }

                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(34.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvActionButton(Icons.Default.SkipPrevious, "이전 화", tvPreviousRequester) { previousEpisode?.let(::switchEpisode) }
                    TvActionButton(Icons.Default.FastRewind, "${vm.playerSettings.seekButtonSeekSeconds}초 뒤로", tvRewindRequester) { engine.seekBy(-vm.playerSettings.seekButtonSeekSeconds.toDouble()) }
                    TvActionButton(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "일시정지" else "재생", tvPlayRequester, large = true) {
                        if (engine.isPlaying) engine.pause() else engine.play()
                        MainActivity.isVideoPlaying = engine.isPlaying
                    }
                    TvActionButton(Icons.Default.FastForward, "${vm.playerSettings.seekButtonSeekSeconds}초 앞으로", tvForwardRequester) { engine.seekBy(vm.playerSettings.seekButtonSeekSeconds.toDouble()) }
                    TvActionButton(Icons.Default.SkipNext, "다음 화", tvNextRequester) { nextEpisode?.let(::switchEpisode) }
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 52.dp, vertical = 34.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(position), color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("/", color = Color.White.copy(.35f))
                        Spacer(Modifier.width(8.dp))
                        Text(formatTime(duration), color = Color.White.copy(.68f), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("-${formatTime(remaining)}", color = Color.White.copy(.68f), fontSize = 13.sp)
                    }
                    Slider(
                        value = if (duration > 0L) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                        onValueChange = { position = it.toLong(); isSeeking = true },
                        onValueChangeFinished = { engine.seekTo(position.coerceIn(0L, duration.coerceAtLeast(0L))); isSeeking = false },
                        valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(thumbColor = tvAccent, activeTrackColor = tvAccent, inactiveTrackColor = Color.White.copy(.30f)),
                        modifier = Modifier.fillMaxWidth().height(30.dp)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (showSkipButton && currentChapter != null) {
                            TvTextAction(if (currentChapter.type.contains("ed", true)) "ED 건너뛰기" else "OP 건너뛰기", tvSkipRequester) { skipCurrentChapter() }
                            Spacer(Modifier.width(12.dp))
                        }
                        TvTextAction("화면 잠금", tvLockRequester) { locked = true; controlsVisible = false; tvRootRequester.requestFocus() }
                    }
                }
            }
        } else if (!isTv && controlsVisible) {
            // Mobile/tablet player controls. TV uses the dedicated overlay above.
            // Top bar: back + episode context on the left, settings on the right.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { leave() },
                    modifier = Modifier.tvFocusable(),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = .48f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .10f))
                ) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ArrowBack, "뒤로", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }

                Spacer(Modifier.size(10.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black.copy(alpha = .40f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .10f))
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(
                            anime.title,
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                        Text(
                            currentEpisode.title.ifBlank { "${currentEpisode.number}화" },
                            color = Color.White.copy(.62f),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                Box {
                    Surface(
                        onClick = { settingsOpen = !settingsOpen },
                        modifier = Modifier.tvFocusable(),
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = .48f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .10f))
                    ) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Settings, "플레이어 설정", tint = Color.White, modifier = Modifier.size(21.dp))
                        }
                    }

                DropdownMenu(
                    expanded = settingsOpen,
                    onDismissRequest = { settingsOpen = false },
                    offset = DpOffset((-350).dp, 8.dp),
                    modifier = Modifier
                        .widthIn(min = 340.dp, max = 390.dp)
                        .heightIn(max = 680.dp),
                    shape = RoundedCornerShape(22.dp),
                    containerColor = Color(0xFF111116),
                    tonalElevation = 10.dp,
                    shadowElevation = 22.dp
                ) {
                    Column(
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .heightIn(max = 660.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = RoundedCornerShape(10.dp), color = Color.White.copy(.10f)) {
                                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Settings, null, tint = Color.White, modifier = Modifier.size(19.dp))
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("플레이어 설정", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text("재생 · 화질 · 자막", color = Color.White.copy(.50f), fontSize = 10.sp)
                            }
                            IconButton(onClick = { settingsOpen = false }) {
                                Icon(Icons.Default.ArrowBack, "닫기", tint = Color.White.copy(.75f))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(.10f)))

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text("다음 화 자동재생", color = Color.White, fontSize = 13.sp)
                                Text("영상이 끝나면 다음 화를 재생합니다", color = Color.White.copy(.55f), fontSize = 11.sp)
                            }
                            Switch(
                                checked = autoPlay,
                                onCheckedChange = {
                                    autoPlay = it
                                    vm.updatePlayerSettings(context, vm.playerSettings.copy(autoPlay = it))
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text("OP/ED 스킵 버튼", color = Color.White, fontSize = 13.sp)
                                Text("재생 화면에 스킵 버튼을 표시합니다", color = Color.White.copy(.55f), fontSize = 11.sp)
                            }
                            Switch(
                                checked = showSkipButton,
                                onCheckedChange = {
                                    showSkipButton = it
                                    vm.updatePlayerSettings(context, vm.playerSettings.copy(showChapterSkipButton = it))
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text("OP/ED 자동 스킵", color = Color.White, fontSize = 13.sp)
                                Text("감지된 OP/ED 구간을 자동으로 건너뜁니다", color = Color.White.copy(.55f), fontSize = 11.sp)
                            }
                            Switch(
                                checked = autoSkip,
                                onCheckedChange = {
                                    autoSkip = it
                                    vm.updatePlayerSettings(context, vm.playerSettings.copy(autoSkip = it))
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(if (subtitleEnabled) "자막 켜짐" else "자막 꺼짐", color = Color.White, fontSize = 13.sp)
                                Text("현재 자막 표시 상태", color = Color.White.copy(.55f), fontSize = 11.sp)
                            }
                            Switch(
                                checked = subtitleEnabled,
                                onCheckedChange = {
                                    subtitleEnabled = it
                                    engine.setSubtitleVisible(it)
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text("ASS 자막 효과", color = Color.White, fontSize = 13.sp)
                                Text(
                                    if (vm.playerSettings.assEffectsEnabled) "원본 위치·색상·효과를 유지합니다"
                                    else "효과를 단순화해 TV 성능을 우선합니다",
                                    color = Color.White.copy(.55f), fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = vm.playerSettings.assEffectsEnabled,
                                onCheckedChange = { enabled ->
                                    vm.updatePlayerSettings(context, vm.playerSettings.copy(assEffectsEnabled = enabled))
                                    engine.setAssEffectsEnabled(enabled)
                                }
                            )
                        }

                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("자막 세부 설정", color = Color.White, fontSize = 13.sp)
                                    Text("크기 · 위치 · 싱크 · 스타일 · 소스", color = Color.White.copy(.55f), fontSize = 11.sp)
                                }
                            },
                            onClick = { subtitleSettingsOpen = !subtitleSettingsOpen }
                        )

                        if (subtitleSettingsOpen) {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                                Text("자막 소스", color = Color.White.copy(.72f), fontSize = 11.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 5.dp)) {
                                    listOf("linkkf" to "Linkkf", "kairan" to "Kairan", "csora" to "Csora", "user" to "사용자").forEach { (source, label) ->
                                        Surface(
                                            onClick = {
                                                subtitleSource = source
                                                vm.updatePlayerSettings(context, vm.playerSettings.copy(subtitleSourcePreference = source))
                                                playerScope.launch {
                                                    val path = if (source == "user") {
                                                        resolveCachedSubtitle(currentEpisode, "user")
                                                    } else if (source == "kairan" || source == "csora") {
                                                        resolvePreferredSubtitle(currentEpisode, source)
                                                    } else {
                                                        withContext(Dispatchers.IO) {
                                                            SubtitleStore.get(
                                                                context,
                                                                anime.id,
                                                                currentEpisode.id,
                                                                currentEpisode.number,
                                                                source
                                                            )
                                                        }
                                                    }
                                                    if (!path.isNullOrBlank() && File(path).isFile) {
                                                        localSubtitle = path
                                                        engine.replaceSubtitleTrack(path)
                                                        engine.setSubtitleDelay(subtitleSyncMs)
                                                        engine.setSubtitleVisible(subtitleEnabled)
                                                    } else {
                                                        Log.d(
                                                            "SubtitleSelect",
                                                            "MANUAL_NONE source=$source episode=${currentEpisode.displayNumber}"
                                                        )
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (subtitleSource == source) Color.White else Color.White.copy(.10f)
                                        ) {
                                            Text(label, color = if (subtitleSource == source) Color.Black else Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp))
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                Text("자막 크기 ${subtitleSize.toInt()}%", color = Color.White.copy(.72f), fontSize = 11.sp)
                                Slider(
                                    value = subtitleSize,
                                    onValueChange = {
                                        subtitleSize = it
                                        vm.updatePlayerSettings(context, vm.playerSettings.copy(subtitleSize = it))
                                        engine.applySubtitleStyle(vm.playerSettings.textColor, vm.playerSettings.strokeColor, it, vttBold, vm.playerSettings.vttOutlineWidth, subtitlePosition / 100f, false)
                                    },
                                    valueRange = 50f..300f,
                                    steps = 24
                                )

                                Text("자막 위치 ${subtitlePosition.toInt()}%", color = Color.White.copy(.72f), fontSize = 11.sp)
                                Slider(
                                    value = subtitlePosition,
                                    onValueChange = {
                                        subtitlePosition = it
                                        vm.updatePlayerSettings(context, vm.playerSettings.copy(subtitleBottomPaddingFraction = it / 100f))
                                        engine.applySubtitleStyle(vm.playerSettings.textColor, vm.playerSettings.strokeColor, subtitleSize, vttBold, vm.playerSettings.vttOutlineWidth, it / 100f, false)
                                    },
                                    valueRange = 3f..30f,
                                    steps = 26
                                )

                                Text("자막 싱크 ${subtitleSyncMs}ms", color = Color.White.copy(.72f), fontSize = 11.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    listOf(-250L, 0L, 250L).forEach { delta ->
                                        Surface(
                                            onClick = {
                                                subtitleSyncMs = if (delta == 0L) 0L else subtitleSyncMs + delta
                                                vm.updatePlayerSettings(context, vm.playerSettings.copy(syncOffsetMs = subtitleSyncMs))
                                                engine.setSubtitleDelay(subtitleSyncMs)
                                            },
                                            shape = RoundedCornerShape(8.dp), color = Color.White.copy(.10f)
                                        ) { Text(if (delta == 0L) "초기화" else if (delta < 0) "-250ms" else "+250ms", color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) }
                                    }
                                }

                                Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("VTT 원본 스타일 유지", color = Color.White, fontSize = 11.sp)
                                    Switch(checked = vttStyleEnabled, onCheckedChange = {
                                        vttStyleEnabled = it
                                        vm.updatePlayerSettings(context, vm.playerSettings.copy(vttStyleEnabled = it))
                                    })
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("VTT 자막 굵게", color = Color.White, fontSize = 11.sp)
                                    Switch(checked = vttBold, onCheckedChange = {
                                        vttBold = it
                                        vm.updatePlayerSettings(context, vm.playerSettings.copy(vttBold = it))
                                        engine.applySubtitleStyle(vm.playerSettings.textColor, vm.playerSettings.strokeColor, subtitleSize, it, vm.playerSettings.vttOutlineWidth, subtitlePosition / 100f, false)
                                    })
                                }
                            }
                        }

                        if (parsedStreamingQualities.isNotEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                                Text("화질", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("현재 스트림에서 선택 가능한 화질", color = Color.White.copy(.50f), fontSize = 10.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    parsedStreamingQualities.forEach { quality ->
                                        val selected = selectedStreamingQuality?.url == quality.url
                                        Surface(
                                            onClick = {
                                                selectedStreamingQuality = quality
                                                streamHeaders = quality.headers
                                                quality.referer?.let { streamReferer = it }
                                                streamUrl = if (!quality.flixCloudPk.isNullOrBlank() && quality.url.contains("m3u8", true)) {
                                                    FlixCloudHlsProxy.createProxyUrl(quality.url, quality.flixCloudPk, quality.headers)
                                                } else quality.url
                                                loading = true
                                                error = null
                                                engine.load(
                                                    streamUrl.orEmpty(),
                                                    localSubtitle,
                                                    vm.playerSettings.syncOffsetMs,
                                                    null,
                                                    autoPlay = true
                                                )
                                                vm.updatePlayerSettings(context, vm.playerSettings.copy(defaultQuality = quality.label))
                                                settingsOpen = false
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (selected) Color.White else Color.White.copy(.08f),
                                            border = BorderStroke(1.dp, if (selected) Color.White.copy(.75f) else Color.White.copy(.10f))
                                        ) {
                                            Text(quality.label, color = if (selected) Color.Black else Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // Subtitle source / font / saved subtitle management / user subtitle.
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                            Text("자막 소스", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("현재 프로젝트에서 사용할 저장 자막을 선택합니다.", color = Color.White.copy(.50f), fontSize = 10.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("linkkf" to "Linkkf VTT", "kairan" to "Kairan ASS", "csora" to "Csora ASS", "user" to "사용자").forEach { (key, label) ->
                                    Surface(onClick = {
                                        subtitleSource = key
                                        subtitleSettingsOpen = true
                                        vm.updatePlayerSettings(context, vm.playerSettings.copy(subtitleSourcePreference = key))
                                    }, shape = RoundedCornerShape(9.dp), color = if (subtitleSource == key) Color.White else Color.White.copy(.08f)) {
                                        Text(label, color = if (subtitleSource == key) Color.Black else Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp))
                                    }
                                }
                            }
                        }

                        if (discoveredSubtitleFonts.isNotEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                                Text("발견된 ASS 폰트", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    discoveredSubtitleFonts.forEach { font ->
                                        val selected = selectedSubtitleFontPath == font.path
                                        Surface(onClick = {
                                            selectedSubtitleFontPath = font.path
                                            vm.updatePlayerSettings(context, vm.playerSettings.copy(subtitleFontPath = font.path, subtitleFontSource = font.source))
                                            engine.replaceSubtitleTrack(localSubtitle ?: return@Surface)
                                        }, shape = RoundedCornerShape(9.dp), color = if (selected) Color.White else Color.White.copy(.08f)) {
                                            Text(font.displayName, color = if (selected) Color.Black else Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp))
                                        }
                                    }
                                }
                            }
                        }

                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("커스텀 폰트", color = Color.White, fontSize = 13.sp)
                                Text(customFontName ?: "기본 폰트 사용 중", color = Color.White.copy(.50f), fontSize = 10.sp)
                            }
                            TextButton(onClick = { fontPickerLauncher.launch("font/*") }) { Text("불러오기", color = Color.White, fontSize = 11.sp) }
                        }

                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("사용자 자막", color = Color.White, fontSize = 13.sp)
                                Text("ASS / SSA / SRT / VTT / SMI", color = Color.White.copy(.50f), fontSize = 10.sp)
                            }
                            TextButton(onClick = { subtitleFilePickerLauncher.launch(arrayOf("text/*", "application/*")) }) { Text("추가", color = Color.White, fontSize = 11.sp) }
                        }

                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                            Text("이 회차의 저장 자막", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (savedSubtitles.isEmpty()) Text("저장된 원본 자막이 없습니다.", color = Color.White.copy(.45f), fontSize = 10.sp)
                            savedSubtitles.forEach { saved ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(saved.source.uppercase(Locale.ROOT), color = if (saved.ignored) Color.Gray else Color.White, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { playerScope.launch { SubtitleStore.setIgnored(context, anime.id, currentEpisode.displayNumber, currentEpisode.number, saved.source, !saved.ignored); savedSubtitles = SubtitleStore.list(context, anime.id, currentEpisode.displayNumber, currentEpisode.number) } }) { Text(if (saved.ignored) "사용" else "제외", color = Color.White, fontSize = 9.sp) }
                                    TextButton(onClick = { playerScope.launch { SubtitleStore.deleteOne(context, anime.id, currentEpisode.displayNumber, currentEpisode.number, saved.source, saved.path); savedSubtitles = SubtitleStore.list(context, anime.id, currentEpisode.displayNumber, currentEpisode.number) } }) { Text("삭제", color = Color(0xFFFF8A80), fontSize = 9.sp) }
                                }
                            }
                        }

                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                            Text("사용자 자막 관리", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            userSubtitles.forEach { saved ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(File(saved.path).name, color = Color.White.copy(.75f), fontSize = 9.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { playerScope.launch { localSubtitle = saved.path; subtitleSource = "user"; engine.replaceSubtitleTrack(saved.path); engine.setSubtitleVisible(true) } }) { Text("사용", color = Color.White, fontSize = 9.sp) }
                                    TextButton(onClick = { playerScope.launch { SubtitleStore.deleteOne(context, anime.id, currentEpisode.displayNumber, currentEpisode.number, "user", saved.path); userSubtitles = SubtitleStore.listUser(context, anime.id, currentEpisode.displayNumber, currentEpisode.number) } }) { Text("삭제", color = Color(0xFFFF8A80), fontSize = 9.sp) }
                                }
                            }
                        }

                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("VTT 원본 스타일", color = Color.White, fontSize = 12.sp); Text("원본 색상/스타일 유지", color = Color.White.copy(.50f), fontSize = 10.sp) }
                            Switch(checked = vttStyleEnabled, onCheckedChange = { vttStyleEnabled = it; vm.updatePlayerSettings(context, vm.playerSettings.copy(vttStyleEnabled = it)) })
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("VTT 굵게", color = Color.White, fontSize = 12.sp); Text("자막 글자를 굵게 표시", color = Color.White.copy(.50f), fontSize = 10.sp) }
                            Switch(checked = vttBold, onCheckedChange = { vttBold = it; vm.updatePlayerSettings(context, vm.playerSettings.copy(vttBold = it)); engine.applySubtitleStyle(vm.playerSettings.textColor, vm.playerSettings.strokeColor, subtitleSize, it, vttOutlineWidth, subtitlePosition / 100f, false) })
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                            Text("VTT 테두리 ${String.format(Locale.US, "%.1f", vttOutlineWidth)}dp", color = Color.White.copy(.72f), fontSize = 11.sp)
                            Slider(value = vttOutlineWidth, onValueChange = { vttOutlineWidth = it; vm.updatePlayerSettings(context, vm.playerSettings.copy(vttOutlineWidth = it)); engine.applySubtitleStyle(vm.playerSettings.textColor, vm.playerSettings.strokeColor, subtitleSize, vttBold, it, subtitlePosition / 100f, false) }, valueRange = 0f..6f, steps = 11)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("재생 속도", color = Color.White, fontSize = 13.sp)
                                Text("${"%.2f".format(Locale.US, speed)}x", color = Color.White.copy(.55f), fontSize = 11.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { rate ->
                                    Surface(
                                        onClick = {
                                            speed = rate
                                            engine.setSpeed(rate)
                                            vm.updatePlayerSettings(context, vm.playerSettings.copy(playbackSpeed = rate))
                                        },
                                        modifier = Modifier.tvFocusable(),
                                        shape = RoundedCornerShape(9.dp),
                                        color = if (speed == rate) Color.White else Color.White.copy(.10f)
                                    ) {
                                        Text(
                                            "${"%.2f".format(Locale.US, rate)}x",
                                            color = if (speed == rate) Color.Black else Color.White,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            "더블터치 이동: ${vm.playerSettings.doubleTapSeekSeconds}초",
                            color = Color.White.copy(.45f),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                        )
                    }
                }
                }
            }

            // Center transport controls: previous / rewind / play / forward / next.
            // The two outer episode buttons stay visible for a stable, symmetric layout.
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val episodeButtonSize = 50.dp
                val episodeButtonColor = Color.Black.copy(alpha = .46f)
                val disabledEpisodeColor = Color.Black.copy(alpha = .26f)
                val disabledTint = Color.White.copy(alpha = .28f)

                Surface(
                    onClick = { previousEpisode?.let(::switchEpisode) },
                    modifier = Modifier.size(episodeButtonSize).focusRequester(tvPreviousRequester).focusProperties { left = tvPreviousRequester; right = tvRewindRequester }.tvFocusable(),
                    shape = CircleShape,
                    color = if (previousEpisode != null) episodeButtonColor else disabledEpisodeColor,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = if (previousEpisode != null) .12f else .06f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            "이전 화",
                            tint = if (previousEpisode != null) Color.White else disabledTint,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                }

                Surface(
                    onClick = { engine.seekBy(-vm.playerSettings.seekButtonSeekSeconds.toDouble()) },
                    modifier = Modifier.size(50.dp).focusRequester(tvRewindRequester).focusProperties { left = tvPreviousRequester; right = tvPlayRequester }.tvFocusable(),
                    shape = CircleShape,
                    color = episodeButtonColor,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .12f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.FastRewind, "뒤로", tint = Color.White, modifier = Modifier.size(23.dp))
                    }
                }

                Surface(
                    onClick = {
                        if (engine.isPlaying) engine.pause() else engine.play()
                        MainActivity.isVideoPlaying = engine.isPlaying
                    },
                    modifier = Modifier.size(68.dp).focusRequester(tvPlayRequester).focusProperties { left = tvRewindRequester; right = tvForwardRequester }.tvFocusable(),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = .92f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "일시정지" else "재생",
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Surface(
                    onClick = { engine.seekBy(vm.playerSettings.seekButtonSeekSeconds.toDouble()) },
                    modifier = Modifier.size(50.dp).focusRequester(tvForwardRequester).focusProperties { left = tvPlayRequester; right = tvNextRequester }.tvFocusable(),
                    shape = CircleShape,
                    color = episodeButtonColor,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .12f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.FastForward, "${vm.playerSettings.seekButtonSeekSeconds}초 앞으로", tint = Color.White, modifier = Modifier.size(23.dp))
                    }
                }

                Surface(
                    onClick = { nextEpisode?.let(::switchEpisode) },
                    modifier = Modifier.size(episodeButtonSize).focusRequester(tvNextRequester).focusProperties { left = tvForwardRequester; right = tvNextRequester }.tvFocusable(),
                    shape = CircleShape,
                    color = if (nextEpisode != null) episodeButtonColor else disabledEpisodeColor,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = if (nextEpisode != null) .12f else .06f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.SkipNext,
                            "다음 화",
                            tint = if (nextEpisode != null) Color.White else disabledTint,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                }
            }

            // Bottom progress/control dock.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 88.dp, top = 12.dp, bottom = 12.dp)
                    .align(Alignment.BottomCenter),
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = .52f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .10f))
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(position), color = Color.White.copy(.78f), fontSize = 11.sp)
                        Slider(
                            value = if (duration > 0L) {
                                if (isSeeking) seekPreview.coerceIn(0f, duration.toFloat())
                                else position.toFloat().coerceIn(0f, duration.toFloat())
                            } else 0f,
                            onValueChange = { value ->
                                isSeeking = true
                                seekPreview = value.coerceIn(0f, duration.toFloat().coerceAtLeast(0f))
                                position = seekPreview.toLong()
                            },
                            onValueChangeFinished = {
                                val target = seekPreview.toLong().coerceIn(0L, duration)
                                engine.seekTo(target)
                                position = target
                                isSeeking = false
                            },
                            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text(formatTime(duration), color = Color.White.copy(.58f), fontSize = 11.sp)
                    }
                }
            }

            // OP/ED button remains immediately above the lock button, as in the previous UI.
            if (showSkipButton && currentChapter != null) {
                Surface(
                    onClick = { skipCurrentChapter() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .tvFocusable()
                        .padding(end = 18.dp, bottom = 78.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = Color.Black.copy(alpha = .62f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .14f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (currentChapter.type.contains("ed", true)) "ED 스킵" else "OP 스킵",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Surface(
                onClick = {
                    locked = true
                    controlsVisible = false
                    showUnlockButton = false
                    settingsOpen = false
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 16.dp)
                    .size(50.dp)
                    .tvFocusable(),
                shape = CircleShape,
                color = Color.Black.copy(alpha = .62f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .14f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Lock, "화면 잠금", tint = Color.White, modifier = Modifier.size(21.dp))
                }
            }
        }
    }
}

@Composable
private fun TvActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    focusRequester: FocusRequester? = null,
    large: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(when { large -> 92.dp; compact -> 54.dp; else -> 68.dp })
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = CircleShape,
        color = Color.Black.copy(.58f),
        tonalElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(if (large) 38.dp else if (compact) 22.dp else 27.dp))
        }
    }
}


@Composable
private fun TvTextAction(text: String, focusRequester: FocusRequester, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.focusRequester(focusRequester), shape = RoundedCornerShape(8.dp), color = Color.Black.copy(.62f)) {
        Text(text, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
    }
}

@Composable
private fun TvPlayerSettingsMenu(
    expanded: Boolean, onDismiss: () -> Unit, autoPlay: Boolean, showSkipButton: Boolean, autoSkip: Boolean,
    subtitleEnabled: Boolean, assEffectsEnabled: Boolean, subtitleSource: String, subtitleSize: Float,
    subtitlePosition: Float, subtitleSyncMs: Long, speed: Float, onActivate: (Int) -> Unit
) {
    val rows = listOf(
        "다음 화 자동재생" to if (autoPlay) "켜짐" else "꺼짐",
        "OP/ED 스킵 버튼" to if (showSkipButton) "켜짐" else "꺼짐",
        "OP/ED 자동 스킵" to if (autoSkip) "켜짐" else "꺼짐",
        "자막" to if (subtitleEnabled) "켜짐" else "꺼짐",
        "ASS 자막 효과" to if (assEffectsEnabled) "원본 효과" else "효과 끄기",
        "자막 소스" to subtitleSource.uppercase(),
        "자막 크기" to "${subtitleSize.toInt()}%",
        "자막 위치" to "${subtitlePosition.toInt()}%",
        "자막 싱크" to "${subtitleSyncMs}ms",
        "재생 속도" to "${String.format(Locale.US, "%.2f", speed)}x"
    )
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.width(360.dp)) {
        rows.forEachIndexed { index, row ->
            DropdownMenuItem(text = { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(row.first, Modifier.weight(1f)); Text(row.second, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, onClick = { onActivate(index) })
        }
    }
}


@Composable
private fun TvSettingsPanel(
    selectedIndex: Int,
    autoPlay: Boolean,
    showSkipButton: Boolean,
    autoSkip: Boolean,
    subtitleEnabled: Boolean,
    assEffectsEnabled: Boolean,
    subtitleSource: String,
    subtitleSize: Float,
    subtitlePosition: Float,
    subtitleSyncMs: Long,
    speed: Float,
    onClose: () -> Unit,
    onSettingClick: (Int) -> Unit,
    onAdjust: (Int, Int) -> Unit,
    onActivate: (Int) -> Unit
) {
    val rows = listOf(
        "다음 화 자동재생" to if (autoPlay) "켜짐" else "꺼짐",
        "OP/ED 스킵 버튼" to if (showSkipButton) "켜짐" else "꺼짐",
        "OP/ED 자동 스킵" to if (autoSkip) "켜짐" else "꺼짐",
        "자막" to if (subtitleEnabled) "켜짐" else "꺼짐",
        "ASS 자막 효과" to if (assEffectsEnabled) "원본 효과" else "효과 끄기",
        "자막 소스" to subtitleSource.uppercase(),
        "자막 크기" to "${subtitleSize.toInt()}%",
        "자막 위치" to "${subtitlePosition.toInt()}%",
        "자막 싱크" to "${subtitleSyncMs}ms",
        "재생 속도" to "${String.format(Locale.US, "%.2f", speed)}x",
        "화면 잠금" to "선택"
    )
    val rowRequesters = remember(rows.size) { List(rows.size) { FocusRequester() } }

    LaunchedEffect(selectedIndex) {
        rowRequesters.getOrNull(selectedIndex)?.requestFocus()
    }

    Surface(
        modifier = Modifier.fillMaxSize().padding(horizontal = 120.dp, vertical = 50.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color.Black.copy(alpha = .90f),
        border = BorderStroke(2.dp, Color.White.copy(.12f))
    ) {
        Column(Modifier.padding(28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("플레이어 설정", color = Color.White, fontSize = 24.sp)
                    Text("리모컨 ↑↓ 선택 · ←→ 변경 · OK 실행", color = Color.White.copy(.55f), fontSize = 12.sp)
                }
                TvActionButton(icon = Icons.Default.ArrowBack, contentDescription = "설정 닫기", compact = true, onClick = onClose)
            }
            Spacer(Modifier.height(18.dp))
            rows.forEachIndexed { index, pair ->
                Surface(
                    onClick = { onActivate(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .focusRequester(rowRequesters[index])
                        .onFocusChanged { if (it.isFocused) onSettingClick(index) }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> { onAdjust(index, -1); true }
                                Key.DirectionRight -> { onAdjust(index, 1); true }
                                else -> false
                            }
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selectedIndex == index) Color.Gray.copy(alpha = .28f) else Color.White.copy(alpha = .055f),
                    border = BorderStroke(2.dp, if (selectedIndex == index) Color.Gray.copy(.72f) else Color.Transparent)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(pair.first, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(pair.second, color = Color.White.copy(.70f), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}
