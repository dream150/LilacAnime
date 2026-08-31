package com.lilac.anime

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.app.PictureInPictureParams
import android.util.Rational
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import com.lilac.anime.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.ZipInputStream
import java.net.URLConnection
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {

    companion object {
        var isVideoPlaying: Boolean = false
        var isInPictureInPicture: Boolean by mutableStateOf(false)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        isInPictureInPicture = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isVideoPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }

    private var refreshInstallPermission: (() -> Unit)? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            Log.d(
                "NotificationPermission",
                if (granted) {
                    "알림 권한 허용됨"
                } else {
                    "알림 권한 거부됨"
                }
            )
        }

    override fun onResume() {
        super.onResume()
        refreshInstallPermission?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermission()

        setContent {
            val viewModel: AnimeViewModel = viewModel()
            val context = LocalContext.current

            var pendingRelease by remember {
                mutableStateOf<GithubReleaseInfo?>(null)
            }

            var updateBusy by remember {
                mutableStateOf(false)
            }

            var updateError by remember {
                mutableStateOf<String?>(null)
            }

            var installPermissionGranted by remember {
                mutableStateOf(
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                        packageManager.canRequestPackageInstalls()
                )
            }

            LaunchedEffect(Unit) {
                refreshInstallPermission = {
                    installPermissionGranted =
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                            packageManager.canRequestPackageInstalls()
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    refreshInstallPermission = null
                }
            }

            LaunchedEffect(Unit) {
                viewModel.monitorNetwork(context)
                viewModel.loadAnime(context)

                val release = GithubReleaseChecker.check(context)

                if (release != null) {
                    pendingRelease = release
                }
            }

            LilacApp(vm = viewModel)

            pendingRelease?.let { release ->
                AlertDialog(
                    onDismissRequest = {
                        if (!updateBusy) {
                            pendingRelease = null
                        }
                    },

                    title = {
                        Text("새 버전이 있습니다")
                    },

                    text = {
                        Column {
                            Text("Lilac Anime ${release.tag}")

                            if (release.name != release.tag) {
                                Text(
                                    text = release.name,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }

                            if (release.body.isNotBlank()) {
                                Text(
                                    text = release.body.take(700),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                            }

                            if (updateBusy) {
                                Text(
                                    text = "업데이트 파일을 다운로드하고 설치 준비 중입니다...",
                                    modifier = Modifier.padding(top = 12.dp),
                                    fontSize = 12.sp
                                )
                            }

                            updateError?.let { error ->
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 8.dp),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    },

                    confirmButton = {
                        if (release.apkUrl != null) {
                            TextButton(
                                enabled = !updateBusy,

                                onClick = {
                                    updateError = null

                                    // Android 8.0 이상:
                                    // 알 수 없는 앱 설치 권한 확인
                                    if (
                                        Build.VERSION.SDK_INT >=
                                        Build.VERSION_CODES.O &&
                                        !packageManager.canRequestPackageInstalls()
                                    ) {
                                        installPermissionGranted = false

                                        try {
                                            startActivity(
                                                Intent(
                                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                    Uri.parse(
                                                        "package:$packageName"
                                                    )
                                                )
                                            )
                                        } catch (_: Exception) {
                                            startActivity(
                                                Intent(
                                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
                                                )
                                            )
                                        }

                                    } else {
                                        installPermissionGranted = true
                                        updateBusy = true

                                        CoroutineScope(
                                            Dispatchers.Main
                                        ).launch {
                                            try {
                                                val apk =
                                                    GithubReleaseChecker
                                                        .downloadApk(
                                                            context,
                                                            release.apkUrl
                                                        )

                                                installApkWithPackageInstaller(
                                                    context,
                                                    apk
                                                )

                                            } catch (e: Exception) {
                                                updateError =
                                                    "업데이트 설치를 시작하지 못했습니다: " +
                                                    "${e.message ?: "알 수 없는 오류"}"

                                                updateBusy = false
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text(
                                    if (installPermissionGranted) {
                                        "앱에서 설치"
                                    } else {
                                        "설치 권한 허용"
                                    }
                                )
                            }

                        } else {
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(release.releaseUrl)
                                        )
                                    )

                                    pendingRelease = null
                                }
                            ) {
                                Text("릴리스 페이지")
                            }
                        }
                    },

                    dismissButton = {
                        TextButton(
                            enabled = !updateBusy,

                            onClick = {
                                pendingRelease = null
                            }
                        ) {
                            Text("나중에")
                        }
                    }
                )
            }
        }
    }

    private fun requestNotificationPermission() {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }
}