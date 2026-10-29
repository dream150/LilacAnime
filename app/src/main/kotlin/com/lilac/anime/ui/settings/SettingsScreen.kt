package com.lilac.anime.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lilac.anime.data.DeviceSyncClient
import com.lilac.anime.data.subtitle.whisper.WhisperTranscriptionService
import com.lilac.anime.network.OfflineOpEdFingerprintStore
import com.lilac.anime.ui.components.ScreenHeader
import com.lilac.anime.viewmodel.AnimeViewModel
import com.lilac.anime.viewmodel.ThemeMode
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 설정의 하위 페이지. 상위 목록에서 고르면 같은 화면 안에서 해당 페이지가 열린다. */
private enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector) {
    GENERAL("일반", "테마 · 기기 동기화", Icons.Filled.Tune),
    SOURCE("콘텐츠 소스", "영상 소스 · 재생 문제 진단", Icons.Filled.Dns),
    PLAYBACK("재생", "화질 · 배속 · 이동 시간", Icons.Filled.PlayCircle),
    SUBTITLE("자막", "크기 · 위치 · 싱크 · 폰트", Icons.Filled.Subtitles),
    AI("AI 재생", "재생 방식 · Whisper · 업스케일링", Icons.Filled.AutoAwesome),
    OPED("OP/ED 데이터", "분석 결과 · Fingerprint", Icons.Filled.GraphicEq)
}

private val SPEED_OPTIONS = listOf(0.1f, 0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

@Composable
fun SettingsScreen(
    vm: AnimeViewModel,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onNavigate: (String) -> Unit
) {
    // 열려 있는 페이지는 화면 회전/프로세스 복원 후에도 유지한다.
    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    val page = pageName?.let { name -> SettingsPage.values().firstOrNull { it.name == name } }

    // 하위 페이지에서 뒤로가기를 누르면 앱이 닫히는 대신 설정 목록으로 돌아온다.
    BackHandler(enabled = page != null) { pageName = null }

    if (page == null) {
        SettingsHome(onOpen = { pageName = it.name }, onNavigate = onNavigate)
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { pageName = null }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "설정 목록으로")
                }
                Text(
                    page.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp)
            ) {
                when (page) {
                    SettingsPage.GENERAL -> GeneralPage(vm, themeMode, onThemeChange)
                    SettingsPage.SOURCE -> SourcePage(vm)
                    SettingsPage.PLAYBACK -> PlaybackPage(vm)
                    SettingsPage.SUBTITLE -> SubtitlePage(vm)
                    SettingsPage.AI -> AiPlaybackPage(vm)
                    SettingsPage.OPED -> OpEdPage(vm)
                }
            }
        }
    }
}

// ============================================================================
// 상위 목록
// ============================================================================

@Composable
private fun SettingsHome(onOpen: (SettingsPage) -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty().ifBlank { "Unknown" }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        ScreenHeader(title = "설정")

        SettingsGroup {
            listOf(SettingsPage.GENERAL, SettingsPage.SOURCE, SettingsPage.PLAYBACK, SettingsPage.SUBTITLE)
                .forEach { page ->
                    SettingsNavRow(page.icon, page.title, page.subtitle) { onOpen(page) }
                }
        }

        SettingsGroup {
            SettingsNavRow(SettingsPage.AI.icon, SettingsPage.AI.title, SettingsPage.AI.subtitle) {
                onOpen(SettingsPage.AI)
            }
            SettingsNavRow(Icons.Filled.Translate, "AI 번역", "로컬 모델 · 번역 프롬프트 · 고급 추론") {
                onNavigate("settings/ai")
            }
            SettingsNavRow(SettingsPage.OPED.icon, SettingsPage.OPED.title, SettingsPage.OPED.subtitle) {
                onOpen(SettingsPage.OPED)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Lilac Anime",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Text(
            "Version $appVersion",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

// ============================================================================
// 일반: 테마 / 기기 동기화
// ============================================================================

@Composable
private fun GeneralPage(vm: AnimeViewModel, themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    SettingsGroup(title = "테마") {
        ThemeOption("시스템 설정 따르기", themeMode == ThemeMode.SYSTEM) { onThemeChange(ThemeMode.SYSTEM) }
        ThemeOption("라이트 모드", themeMode == ThemeMode.LIGHT) { onThemeChange(ThemeMode.LIGHT) }
        ThemeOption("다크 모드", themeMode == ThemeMode.DARK) { onThemeChange(ThemeMode.DARK) }
    }
    DeviceSyncSettings(vm = vm)
}

@Composable
private fun DeviceSyncSettings(vm: AnimeViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(DeviceSyncClient.savedServer(context)) }
    var password by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf(
            if (DeviceSyncClient.isConnected(context)) "토큰이 기기에 안전하게 저장되어 있습니다."
            else "서버 주소와 비밀번호로 연결하세요."
        )
    }
    var busy by remember { mutableStateOf(false) }

    SettingsGroup(
        title = "기기 동기화",
        description = "내 목록과 시청 기록을 개인 LilacAnime 서버와 동기화합니다."
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("서버 주소") },
                placeholder = { Text("https://lilacanime-server.vercel.app") },
                singleLine = true,
                enabled = !busy
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("서버 비밀번호") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !busy
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy && serverUrl.isNotBlank() && password.length >= 8, onClick = {
                    busy = true
                    status = "서버 확인 및 로그인 중…"
                    val url = serverUrl
                    val secret = password
                    password = ""
                    scope.launch {
                        try {
                            val message = withContext(Dispatchers.IO) { DeviceSyncClient.connect(context, url, secret) }
                            status = message
                            serverUrl = DeviceSyncClient.savedServer(context)
                        } catch (e: Exception) {
                            status = e.message ?: "서버 연결에 실패했습니다."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("연결") }
                Button(enabled = !busy && DeviceSyncClient.isConnected(context), onClick = {
                    busy = true
                    status = "동기화 중…"
                    scope.launch {
                        try {
                            val result = vm.runDeviceSync(context)
                            status = if (result == null) "이미 동기화 중입니다. 끝나면 한 번 더 동기화합니다." else "동기화 완료 · 보냄 ${result.sent}개 · 받음 ${result.received}개 · 리비전 ${result.revision}"
                        } catch (e: Exception) {
                            status = e.message ?: "동기화에 실패했습니다."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("지금 동기화") }
                OutlinedButton(enabled = !busy && DeviceSyncClient.isConnected(context), onClick = {
                    DeviceSyncClient.disconnect(context)
                    status = "연결 해제됨. 서버 주소는 유지됩니다."
                }) { Text("연결 해제") }
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        SettingsNote("비밀번호는 저장하지 않습니다. 로그인 토큰은 Android Keystore 기반 AES-GCM으로 암호화해 저장합니다. 외부 서버는 HTTPS를 사용하세요.")
    }
}

// ============================================================================
// 콘텐츠 소스
// ============================================================================

@Composable
private fun SourcePage(vm: AnimeViewModel) {
    val context = LocalContext.current
    val settings = vm.playerSettings

    SettingsGroup(title = "콘텐츠 / 영상 소스") {
        SettingsChipRow(
            title = "사용할 사이트",
            options = listOf(
                "linkkf" to "Linkkf / 링크애니",
                "animenosub" to "Animenosub",
                "reanime" to "RE:Anime",
                "miruro" to "Miruro",
                "ohli24" to "애니24"
            ),
            selected = settings.videoSourcePreference,
            onSelect = { vm.updatePlayerSettings(context, settings.copy(videoSourcePreference = it)) },
            description = "선택한 사이트의 애니 목록·제목·회차·영상 소스를 함께 사용합니다."
        )
    }

    SettingsGroup(
        title = "재생 문제 진단",
        description = "Miruro·애니24 재생 실패 시 최근 관련 로그를 파일로 추출해 공유합니다."
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Button(onClick = {
                runCatching { DiagnosticLogExporter.share(context) }
                    .onFailure {
                        Toast.makeText(
                            context,
                            "로그 추출 실패: ${it.message ?: it.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }) { Text("진단 로그 추출 / 공유") }
        }
    }
}

// ============================================================================
// 재생
// ============================================================================

@Composable
private fun PlaybackPage(vm: AnimeViewModel) {
    val context = LocalContext.current
    val settings = vm.playerSettings

    SettingsGroup(title = "화질 · 속도") {
        SettingsChipRow(
            title = "기본 화질",
            options = listOf("Auto" to "Auto", "720p" to "720p", "1080p" to "1080p"),
            selected = settings.defaultQuality,
            onSelect = { vm.updatePlayerSettings(context, settings.copy(defaultQuality = it)) }
        )
        SettingsDivider()
        // 저장된 값이 목록에 없어도(예: 1.1x) 가장 가까운 칸에 표시한다.
        val speedIndex = SPEED_OPTIONS.indices.minByOrNull { abs(SPEED_OPTIONS[it] - settings.playbackSpeed) } ?: 4
        SettingsSliderRow(
            title = "기본 재생 배속",
            valueText = "${String.format(java.util.Locale.US, "%.2f", settings.playbackSpeed)}x",
            value = speedIndex.toFloat(),
            onValueChange = { value ->
                val chosen = SPEED_OPTIONS[value.roundToInt().coerceIn(SPEED_OPTIONS.indices)]
                vm.updatePlayerSettings(context, settings.copy(playbackSpeed = chosen))
            },
            valueRange = 0f..(SPEED_OPTIONS.size - 1).toFloat(),
            steps = SPEED_OPTIONS.size - 2
        )
    }

    SettingsGroup(
        title = "이동 시간",
        description = "재생 화면에서 건너뛸 시간을 초 단위로 설정합니다."
    ) {
        SecondsField(
            title = "더블탭 이동 시간",
            description = "재생 화면의 왼쪽/오른쪽을 두 번 탭했을 때 이동할 시간입니다.",
            seconds = settings.doubleTapSeekSeconds,
            onChange = { vm.updatePlayerSettings(context, settings.copy(doubleTapSeekSeconds = it)) }
        )
        SettingsDivider()
        SecondsField(
            title = "뒤로/앞으로 버튼 이동 시간",
            description = "재생 화면 중앙의 버튼을 눌렀을 때 이동할 시간입니다. 더블탭 설정과 별도로 사용할 수 있습니다.",
            seconds = settings.seekButtonSeekSeconds,
            onChange = { vm.updatePlayerSettings(context, settings.copy(seekButtonSeekSeconds = it)) }
        )
    }
}

@Composable
private fun SecondsField(title: String, description: String, seconds: Long, onChange: (Long) -> Unit) {
    var input by remember(seconds) { mutableStateOf(seconds.toString()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { value ->
                if (value.all { it.isDigit() }) {
                    input = value
                    value.toLongOrNull()?.let(onChange)
                }
            },
            singleLine = true,
            suffix = { Text("초") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(180.dp)
        )
    }
}

// ============================================================================
// 자막
// ============================================================================

@Composable
private fun SubtitlePage(vm: AnimeViewModel) {
    val context = LocalContext.current
    val settings = vm.playerSettings

    SettingsGroup(title = "모양") {
        SettingsSliderRow(
            title = "기본 자막 크기",
            valueText = "${settings.subtitleSize.toInt()}%",
            value = settings.subtitleSize,
            onValueChange = { vm.updatePlayerSettings(context, settings.copy(subtitleSize = it)) },
            valueRange = 50f..300f,
            steps = 10
        )
        SettingsDivider()
        SettingsChipRow(
            title = "기본 자막 폰트",
            options = listOf("기본체" to "기본체", "나눔고딕" to "나눔고딕", "명조체" to "명조체"),
            selected = settings.subtitleFont,
            onSelect = { vm.updatePlayerSettings(context, settings.copy(subtitleFont = it)) }
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "VTT 자막 굵게",
            checked = settings.vttBold,
            onCheckedChange = { vm.updatePlayerSettings(context, settings.copy(vttBold = it)) }
        )
        SettingsDivider()
        SettingsSliderRow(
            title = "VTT 자막 테두리 두께",
            valueText = "${String.format(java.util.Locale.US, "%.1f", settings.vttOutlineWidth)}dp",
            value = settings.vttOutlineWidth,
            onValueChange = { vm.updatePlayerSettings(context, settings.copy(vttOutlineWidth = it)) },
            valueRange = 0.5f..6.0f,
            steps = 10,
            description = "2dp는 Media3 기본 VTT 배치를 사용하고, 다른 값에서는 사용자 지정 테두리를 적용합니다."
        )
    }

    SettingsGroup(title = "위치 · 싱크") {
        val positionPercent = (settings.subtitleBottomPaddingFraction * 100f).roundToInt().coerceIn(3, 30)
        SettingsSliderRow(
            title = "기본 VTT 자막 위치",
            valueText = "${positionPercent}%",
            value = positionPercent.toFloat(),
            onValueChange = { raw ->
                val percent = raw.roundToInt().coerceIn(3, 30)
                vm.updatePlayerSettings(context, settings.copy(subtitleBottomPaddingFraction = percent / 100f))
            },
            valueRange = 3f..30f,
            steps = 26
        )
        SettingsDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "자막 싱크 미세 조정",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${settings.syncOffsetMs} ms",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    vm.updatePlayerSettings(context, settings.copy(syncOffsetMs = settings.syncOffsetMs - 250L))
                }) { Text("-250ms") }
                OutlinedButton(onClick = {
                    vm.updatePlayerSettings(context, settings.copy(syncOffsetMs = 0L))
                }) { Text("초기화") }
                OutlinedButton(onClick = {
                    vm.updatePlayerSettings(context, settings.copy(syncOffsetMs = settings.syncOffsetMs + 250L))
                }) { Text("+250ms") }
            }
        }
    }
}

// ============================================================================
// AI 재생: 재생 방식 / Whisper / 업스케일링
// ============================================================================

@Composable
private fun AiPlaybackPage(vm: AnimeViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = vm.playerSettings

    var whisperDownloadProgress by remember { mutableStateOf(-1) }
    var whisperModelStatus by remember { mutableStateOf("") }
    var whisperSelectedModel by remember { mutableStateOf(WhisperTranscriptionService.selectedModelId(context)) }
    var whisperModelMenuExpanded by remember { mutableStateOf(false) }

    SettingsGroup(
        title = "재생 방식",
        description = "재생 엔진과 AI 처리를 선택합니다. Whisper는 일본어 음성으로 SRT를 생성하고 기존 한국어 번역 기능에 전달합니다."
    ) {
        listOf(
            Triple("default", "기본 재생", "기존 mpv 재생 기능을 그대로 사용합니다."),
            Triple("anime4k", "Anime4K 업스케일링", "mpv 셰이더로 실시간 업스케일링합니다."),
            Triple("whisper", "Whisper AI 자막", "일본어 음성을 인식해 자막을 생성한 뒤 한국어로 번역합니다."),
            Triple("animejanai", "AnimeJaNai", "Android 추론 엔진이 없어 현재 사용할 수 없습니다.")
        ).forEach { (mode, label, description) ->
            SettingsChoiceRow(
                title = label,
                description = description,
                selected = settings.playbackAiMode == mode,
                enabled = mode != "animejanai",
                onClick = {
                    val updated = when (mode) {
                        "anime4k" -> settings.copy(playbackAiMode = mode, aiUpscalingEnabled = true, aiUpscalingBackend = "anime4k")
                        "whisper" -> settings.copy(playbackAiMode = mode, aiUpscalingEnabled = false)
                        else -> settings.copy(playbackAiMode = "default", aiUpscalingEnabled = false)
                    }
                    vm.updatePlayerSettings(context, updated)
                }
            )
        }
    }

    if (settings.playbackAiMode == "whisper") {
        val selectedOption = WhisperTranscriptionService.modelOptions.first { it.id == whisperSelectedModel }
        val installed = WhisperTranscriptionService.isModelInstalled(context, whisperSelectedModel)

        SettingsGroup(
            title = "Whisper 모델",
            description = "모델 크기가 클수록 메모리 사용량이 증가합니다. OOM이 발생하면 Tiny를 권장합니다."
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { whisperModelMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${selectedOption.title} · ${if (installed) "설치됨" else "미설치"}")
                    }
                    DropdownMenu(expanded = whisperModelMenuExpanded, onDismissRequest = { whisperModelMenuExpanded = false }) {
                        WhisperTranscriptionService.modelOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(option.title)
                                        Text(
                                            option.note,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    whisperSelectedModel = option.id
                                    WhisperTranscriptionService.setSelectedModel(context, option.id)
                                    whisperModelMenuExpanded = false
                                    whisperModelStatus = ""
                                    whisperDownloadProgress = -1
                                }
                            )
                        }
                    }
                }
                Text(
                    if (installed) "${selectedOption.title} 모델이 설치되어 있습니다."
                    else "${selectedOption.title} 모델을 다운로드해야 합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (whisperDownloadProgress in 0..100) {
                    LinearProgressIndicator(
                        progress = { whisperDownloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (whisperModelStatus.isNotBlank()) {
                    Text(
                        whisperModelStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    enabled = whisperDownloadProgress !in 0..100 && !installed,
                    onClick = {
                        val modelId = whisperSelectedModel
                        scope.launch {
                            whisperDownloadProgress = 0
                            whisperModelStatus = "${selectedOption.title} 모델 다운로드 중"
                            runCatching { WhisperTranscriptionService.downloadModel(context, modelId) { whisperDownloadProgress = it } }
                                .onSuccess {
                                    whisperDownloadProgress = -1
                                    whisperModelStatus = "${selectedOption.title} 모델 다운로드 완료"
                                }
                                .onFailure {
                                    whisperDownloadProgress = -1
                                    whisperModelStatus = "모델 다운로드 실패: ${it.message ?: "알 수 없는 오류"}"
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (installed) "모델 설치 완료" else "${selectedOption.title} 다운로드") }
            }
        }
    }

    SettingsGroup(
        title = "실시간 영상 업스케일링",
        description = "Anime4K는 mpv GLSL 셰이더를 사용합니다. AnimeJaNai의 원본 실시간 엔진은 Windows 전용이므로 Android용 추론 경로가 준비되기 전까지 선택 시 기본 GPU 스케일러로 대체됩니다."
    ) {
        SettingsSwitchRow(
            title = "업스케일링 사용",
            subtitle = "끄면 일반 재생 스케일러를 사용합니다.",
            checked = settings.aiUpscalingEnabled,
            onCheckedChange = {
                vm.updatePlayerSettings(
                    context,
                    settings.copy(
                        aiUpscalingEnabled = it,
                        playbackAiMode = if (it && settings.aiUpscalingBackend == "anime4k") "anime4k"
                        else if (!it && settings.playbackAiMode == "anime4k") "default"
                        else settings.playbackAiMode
                    )
                )
            }
        )
        if (settings.aiUpscalingEnabled) {
            SettingsDivider()
            SettingsChipRow(
                title = "엔진",
                options = listOf(
                    "anime4k" to "Anime4K (실시간 셰이더)",
                    "animejanai" to "AnimeJaNai (Android 포팅 대기)",
                    "gpu" to "기본 GPU 스케일러"
                ),
                selected = settings.aiUpscalingBackend,
                onSelect = { value ->
                    vm.updatePlayerSettings(
                        context,
                        settings.copy(
                            aiUpscalingBackend = value,
                            playbackAiMode = if (value == "anime4k") "anime4k"
                            else if (settings.playbackAiMode == "anime4k") "default"
                            else settings.playbackAiMode
                        )
                    )
                },
                description = when (settings.aiUpscalingBackend) {
                    "anime4k" -> "최초 사용 시 공식 Anime4K 릴리스에서 GLSL 파일을 내려받아 앱 내부 저장소에 보관합니다. 셰이더 적용에는 네트워크가 필요하며, 이후에는 저장된 파일을 재사용합니다."
                    "animejanai" -> "현재 Android에서 원본 AnimeJaNai 엔진을 실행할 수 없어 GPU 스케일러로 대체됩니다. 이 항목은 Android 포팅이 완료된 상태가 아닙니다."
                    else -> "AI 추론 없이 mpv의 GPU 스케일러를 사용합니다."
                }
            )
            if (settings.aiUpscalingBackend == "anime4k") {
                SettingsDivider()
                SettingsChipRow(
                    title = "Anime4K 모드",
                    options = listOf(
                        "A" to "A · 1080p / 복원 강함",
                        "B" to "B · 720p / 균형",
                        "C" to "C · 깨끗한 원본",
                        "A+A" to "A+A · 고품질 복원",
                        "B+B" to "B+B · 고품질 균형",
                        "C+A" to "C+A · 노이즈 제거 + 복원"
                    ),
                    selected = settings.aiUpscalingMode,
                    onSelect = { vm.updatePlayerSettings(context, settings.copy(aiUpscalingMode = it)) },
                    description = when (settings.aiUpscalingMode) {
                        "A" -> "대부분의 1080p 애니와 흐림·압축 흔적이 있는 영상에 적합합니다."
                        "B" -> "대부분의 720p 애니와 다운스케일된 영상에 적합합니다."
                        "C" -> "이미 선명한 영상이나 아트워크에 적합하지만, 복원 효과는 약할 수 있습니다."
                        "A+A" -> "A 모드를 두 단계로 적용하는 고부하 모드입니다. 과도한 링잉이나 밴딩이 생길 수 있습니다."
                        "B+B" -> "B 모드의 복원 효과를 높이는 고부하 모드입니다."
                        else -> "노이즈 제거 후 복원하는 고부하 모드입니다."
                    }
                )
            }
        }
    }
}

// ============================================================================
// OP/ED 분석 데이터
// ============================================================================

@Composable
private fun OpEdPage(vm: AnimeViewModel) {
    val context = LocalContext.current
    val settings = vm.playerSettings
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    SettingsGroup(
        title = "자동 분석",
        description = "다운로드된 회차의 OP/ED 분석 결과와 학습된 오디오 Fingerprint를 저장합니다. 저장된 데이터가 있으면 다음 재생부터 다시 분석하지 않습니다."
    ) {
        SettingsSwitchRow(
            title = "오프라인 OP/ED 자동 분석",
            subtitle = "다운로드 완료 회차에서만 자동 분석합니다.",
            checked = settings.offlineOpEdAnalysisEnabled,
            onCheckedChange = { vm.updatePlayerSettings(context, settings.copy(offlineOpEdAnalysisEnabled = it)) }
        )
    }

    SettingsGroup(title = "저장된 데이터 삭제") {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { deleteTarget = "analysis" }, modifier = Modifier.weight(1f)) {
                    Text("분석 결과")
                }
                OutlinedButton(onClick = { deleteTarget = "fingerprint" }, modifier = Modifier.weight(1f)) {
                    Text("Fingerprint")
                }
            }
            Button(onClick = { deleteTarget = "all" }, modifier = Modifier.fillMaxWidth()) {
                Text("모두 삭제")
            }
        }
    }

    deleteTarget?.let { target ->
        val title = when (target) {
            "analysis" -> "분석 결과를 삭제할까요?"
            "fingerprint" -> "Fingerprint를 삭제할까요?"
            else -> "OP/ED 데이터를 모두 삭제할까요?"
        }
        val message = when (target) {
            "analysis" -> "저장된 회차별 OP/ED 분석 결과만 삭제합니다. Fingerprint는 유지됩니다."
            "fingerprint" -> "학습된 OP/ED Fingerprint와 이를 사용해 저장된 회차별 분석 결과를 함께 삭제합니다. 이후 다운로드된 회차를 다시 분석하고 Fingerprint를 다시 학습할 수 있습니다."
            else -> "저장된 회차별 분석 결과와 학습된 Fingerprint를 모두 삭제합니다. 이후 다운로드된 회차를 다시 분석할 수 있습니다."
        }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    when (target) {
                        "analysis" -> OfflineOpEdFingerprintStore.deleteAllAnalysis(context)
                        "fingerprint" -> OfflineOpEdFingerprintStore.deleteAllFingerprints(context)
                        else -> OfflineOpEdFingerprintStore.deleteEverything(context)
                    }
                    deleteTarget = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("취소") }
            }
        )
    }
}
