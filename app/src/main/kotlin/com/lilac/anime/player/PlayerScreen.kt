package com.lilac.anime.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lilac.anime.Anime
import com.lilac.anime.ui.navigation.tvFocusable
import com.lilac.anime.Episode
import com.lilac.anime.MainActivity
import com.lilac.anime.core.model.ChapterSkipSegment
import com.lilac.anime.core.model.StreamQuality
import com.lilac.anime.data.offline.MpvOfflineStore
import com.lilac.anime.data.offline.OfflineStore
import com.lilac.anime.data.subtitle.SubtitleStore
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
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
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

    BackHandler { leave() }

    // Playback is always landscape while this screen owns the player.
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
            localSubtitle = withContext(Dispatchers.IO) {
                SubtitleStore.list(
                    context,
                    anime.id,
                    currentEpisode.id,
                    currentEpisode.number
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
        localSubtitle = withContext(Dispatchers.IO) {
            SubtitleStore.list(
                context,
                anime.id,
                currentEpisode.id,
                currentEpisode.number
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

    // Load persisted AniSkip timestamps first. If this episode has no local
    // timestamp file (streaming, or an older offline download), resolve them
    // online as a fallback so the same skip button works in every playback path.
    LaunchedEffect(currentEpisode.id, anime.id, anime.title, anime.anilistId) {
        val saved = withContext(Dispatchers.IO) {
            OfflineStore.getChapterSkipSegments(
                context,
                anime.id,
                currentEpisode.id
            )
        }
        if (saved.isNotEmpty()) {
            chapters = saved
            android.util.Log.d("AniSkip", "PLAYER_LOCAL_TIMESTAMP_HIT anime=${anime.id} episode=${currentEpisode.number} segments=${saved.size}")
        } else {
            chapters = emptyList()
            val online = runCatching {
                com.lilac.anime.network.OnlineAniSkipService.getSkipSegments(
                    title = anime.title,
                    episodeNumber = currentEpisode.number,
                    episodeLengthSeconds = 0,
                    anilistId = anime.anilistId
                )
            }.getOrElse {
                android.util.Log.w("AniSkip", "PLAYER_TIMESTAMP_LOOKUP_FAILED anime=${anime.id} episode=${currentEpisode.number}", it)
                emptyList()
            }
            if (online.isNotEmpty()) {
                chapters = online
                android.util.Log.d("AniSkip", "PLAYER_ONLINE_TIMESTAMP_HIT anime=${anime.id} episode=${currentEpisode.number} segments=${online.size}")
            } else {
                android.util.Log.d("AniSkip", "PLAYER_TIMESTAMP_NONE anime=${anime.id} episode=${currentEpisode.number}")
            }
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
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MpvPlayerSurfaceView(ctx, engine).apply {
                    seekSeconds = vm.playerSettings.doubleTapSeekSeconds.coerceIn(1, 120)
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
                }
            },
            update = { view ->
                view.gesturesLocked = locked
                view.seekSeconds = vm.playerSettings.doubleTapSeekSeconds.coerceIn(1, 120)
            }
        )

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
        } else if (controlsVisible) {
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

                Surface(
                    onClick = { settingsOpen = !settingsOpen },
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
                    containerColor = Color(0xFF1E1E1E),
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 6.dp)
                    ) {
                        Text(
                            "플레이어 설정",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                        )

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
                                                        SubtitleStore.getUser(context, anime.id, currentEpisode.id, currentEpisode.number)
                                                    } else {
                                                        SubtitleStore.get(context, anime.id, currentEpisode.id, currentEpisode.number, source)
                                                    }
                                                    if (!path.isNullOrBlank()) {
                                                        engine.replaceSubtitleTrack(path)
                                                        engine.setSubtitleDelay(subtitleSyncMs)
                                                        engine.setSubtitleVisible(subtitleEnabled)
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
                    modifier = Modifier.size(episodeButtonSize).tvFocusable(),
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
                    onClick = { engine.seekBy(-10.0) },
                    modifier = Modifier.size(50.dp).tvFocusable(),
                    shape = CircleShape,
                    color = episodeButtonColor,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .12f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Replay10, "뒤로", tint = Color.White, modifier = Modifier.size(23.dp))
                    }
                }

                Surface(
                    onClick = {
                        if (engine.isPlaying) engine.pause() else engine.play()
                        MainActivity.isVideoPlaying = engine.isPlaying
                    },
                    modifier = Modifier.size(68.dp).tvFocusable(),
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
                    onClick = { engine.seekBy(10.0) },
                    modifier = Modifier.size(50.dp).tvFocusable(),
                    shape = CircleShape,
                    color = episodeButtonColor,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .12f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.FastForward, "앞으로", tint = Color.White, modifier = Modifier.size(23.dp))
                    }
                }

                Surface(
                    onClick = { nextEpisode?.let(::switchEpisode) },
                    modifier = Modifier.size(episodeButtonSize).tvFocusable(),
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
                    .size(50.dp),
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
