package com.lilac.anime.ui.settings

import kotlinx.coroutines.withContext
import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.DeviceSyncClient
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
import com.lilac.anime.data.subtitle.*
import com.lilac.anime.network.*
import com.lilac.anime.player.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.detail.*
import com.lilac.anime.ui.home.*
import com.lilac.anime.ui.navigation.*
import com.lilac.anime.ui.search.*
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import kotlin.math.roundToInt
import com.lilac.anime.network.OfflineOpEdFingerprintStore

private fun formatModelBytes(bytes: Long): String {
    if (bytes < 1024L * 1024L) return "${bytes / 1024L} KB"
    if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

@Composable
private fun DeviceSyncSettings(vm: AnimeViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(DeviceSyncClient.savedServer(context)) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (DeviceSyncClient.isConnected(context)) "토큰이 기기에 안전하게 저장되어 있습니다." else "서버 주소와 비밀번호로 연결하세요.") }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(18.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("기기 동기화", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("내 목록과 시청 기록을 개인 LilacAnime 서버와 동기화합니다.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
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
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
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
                        val message = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DeviceSyncClient.connect(context, url, secret) }
                        status = message
                        serverUrl = DeviceSyncClient.savedServer(context)
                    } catch (e: Exception) { status = e.message ?: "서버 연결에 실패했습니다." }
                    finally { busy = false }
                }
            }) { Text("연결") }
            Button(enabled = !busy && DeviceSyncClient.isConnected(context), onClick = {
                busy = true
                status = "동기화 중…"
                scope.launch {
                    try {
                        val result = DeviceSyncClient.sync(context, vm.library, vm.watchHistory)
                        vm.applySyncedCollections(context, result.library, result.history)
                        status = "동기화 완료 · 보냄 ${result.sent}개 · 받음 ${result.received}개 · 리비전 ${result.revision}"
                    } catch (e: Exception) { status = e.message ?: "동기화에 실패했습니다." }
                    finally { busy = false }
                }
            }) { Text("지금 동기화") }
            OutlinedButton(enabled = !busy && DeviceSyncClient.isConnected(context), onClick = {
                DeviceSyncClient.disconnect(context)
                status = "연결 해제됨. 서버 주소는 유지됩니다."
            }) { Text("연결 해제") }
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
        Text("비밀번호는 저장하지 않습니다. 로그인 토큰은 Android Keystore 기반 AES-GCM으로 암호화해 저장합니다. 외부 서버는 HTTPS를 사용하세요.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
    }
}

@Composable
fun SettingsScreen(
    vm: AnimeViewModel,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = vm.playerSettings
    var deleteTarget by remember { mutableStateOf<String?>(null) }


    AppScaffold(selected = "settings", onSelect = onNavigate) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("설정", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

            DeviceSyncSettings(vm = vm)
            
            Spacer(Modifier.height(24.dp))
            Text("테마", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(12.dp))

            ThemeOption("시스템 설정", themeMode == ThemeMode.SYSTEM) { onThemeChange(ThemeMode.SYSTEM) }
            ThemeOption("라이트 모드", themeMode == ThemeMode.LIGHT) { onThemeChange(ThemeMode.LIGHT) }
            ThemeOption("다크 모드", themeMode == ThemeMode.DARK) { onThemeChange(ThemeMode.DARK) }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text("재생 및 자막 설정", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(16.dp))

            Text("콘텐츠 / 영상 소스", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("linkkf" to "Linkkf / 링크애니", "animenosub" to "Animenosub", "reanime" to "RE:Anime").forEach { (source, label) ->
                    FilterChip(
                        selected = settings.videoSourcePreference == source,
                        onClick = { vm.updatePlayerSettings(context, settings.copy(videoSourcePreference = source)) },
                        label = { Text(label) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("miruro" to "Miruro", "ohli24" to "애니24").forEach { (source, label) ->
                    FilterChip(
                        selected = settings.videoSourcePreference == source,
                        onClick = { vm.updatePlayerSettings(context, settings.copy(videoSourcePreference = source)) },
                        label = { Text(label) }
                    )
                }
            }
            Text(
                "선택한 사이트의 애니 목록·제목·회차·영상 소스를 함께 사용합니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
            )
            Spacer(Modifier.height(12.dp))
            Text("재생 문제 진단", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            Text("Miruro·애니24 재생 실패 시 최근 관련 로그를 파일로 추출해 공유합니다.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f))
            Button(onClick = {
                runCatching { DiagnosticLogExporter.share(context) }
                    .onFailure { android.widget.Toast.makeText(context, "로그 추출 실패: ${it.message ?: it.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show() }
            }) { Text("진단 로그 추출 / 공유") }

            Spacer(Modifier.height(16.dp))

            Text("기본 화질", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Auto", "720p", "1080p").forEach { quality ->
                    FilterChip(
                        selected = settings.defaultQuality == quality,
                        onClick = { vm.updatePlayerSettings(context, settings.copy(defaultQuality = quality)) },
                        label = { Text(quality) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))
            Text("실시간 영상 업스케일링", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("Anime4K는 mpv GLSL 셰이더를 사용합니다. AnimeJaNai의 원본 실시간 엔진은 Windows 전용이므로 Android용 추론 경로가 준비되기 전까지 선택 시 기본 GPU 스케일러로 대체됩니다.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("업스케일링 사용", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                    Text("끄면 일반 재생 스케일러를 사용합니다.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                }
                Switch(
                    checked = settings.aiUpscalingEnabled,
                    onCheckedChange = { vm.updatePlayerSettings(context, settings.copy(aiUpscalingEnabled = it)) }
                )
            }
            if (settings.aiUpscalingEnabled) {
                Text("엔진", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("anime4k" to "Anime4K (실시간 셰이더)", "animejanai" to "AnimeJaNai (Android 포팅 대기)", "gpu" to "기본 GPU 스케일러").forEach { (value, label) ->
                        FilterChip(
                            selected = settings.aiUpscalingBackend == value,
                            onClick = { vm.updatePlayerSettings(context, settings.copy(aiUpscalingBackend = value)) },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    when (settings.aiUpscalingBackend) {
                        "anime4k" -> "최초 사용 시 공식 Anime4K 릴리스에서 GLSL 파일을 내려받아 앱 내부 저장소에 보관합니다. 셰이더 적용에는 네트워크가 필요하며, 이후에는 저장된 파일을 재사용합니다."
                        "animejanai" -> "현재 Android에서 원본 AnimeJaNai 엔진을 실행할 수 없어 GPU 스케일러로 대체됩니다. 이 항목은 Android 포팅이 완료된 상태가 아닙니다."
                        else -> "AI 추론 없이 mpv의 GPU 스케일러를 사용합니다."
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f)
                )
                if (settings.aiUpscalingBackend == "anime4k") {
                    Spacer(Modifier.height(8.dp))
                    Text("Anime4K 모드", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                    val modes = listOf(
                        "A" to "A · 1080p / 복원 강함",
                        "B" to "B · 720p / 균형",
                        "C" to "C · 깨끗한 원본",
                        "A+A" to "A+A · 고품질 복원",
                        "B+B" to "B+B · 고품질 균형",
                        "C+A" to "C+A · 노이즈 제거 + 복원"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        modes.chunked(2).forEach { rowModes ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowModes.forEach { (mode, label) ->
                                    FilterChip(
                                        modifier = Modifier.weight(1f),
                                        selected = settings.aiUpscalingMode == mode,
                                        onClick = { vm.updatePlayerSettings(context, settings.copy(aiUpscalingMode = mode)) },
                                        label = { Text(label, maxLines = 2) }
                                    )
                                }
                                if (rowModes.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    Text(
                        when (settings.aiUpscalingMode) {
                            "A" -> "대부분의 1080p 애니와 흐림·압축 흔적이 있는 영상에 적합합니다."
                            "B" -> "대부분의 720p 애니와 다운스케일된 영상에 적합합니다."
                            "C" -> "이미 선명한 영상이나 아트워크에 적합하지만, 복원 효과는 약할 수 있습니다."
                            "A+A" -> "A 모드를 두 단계로 적용하는 고부하 모드입니다. 과도한 링잉이나 밴딩이 생길 수 있습니다."
                            "B+B" -> "B 모드의 복원 효과를 높이는 고부하 모드입니다."
                            else -> "노이즈 제거 후 복원하는 고부하 모드입니다."
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "기본 재생 배속 (${String.format(java.util.Locale.US, "%.2f", settings.playbackSpeed)}x)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Slider(
                value = listOf(0.1f, 0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                    .indexOf(settings.playbackSpeed).coerceAtLeast(0).toFloat(),
                onValueChange = { value ->
                    val options = listOf(0.1f, 0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                    vm.updatePlayerSettings(context, settings.copy(playbackSpeed = options[value.toInt().coerceIn(options.indices)]))
                },
                valueRange = 0f..8f,
                steps = 7
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf(0.1f, 0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { speed ->
                    Text(
                        "${speed}x",
                        fontSize = 9.sp,
                        color = if (speed == settings.playbackSpeed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        fontWeight = if (speed == settings.playbackSpeed) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Text("기본 자막 크기 (${settings.subtitleSize.toInt()}%)", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            Slider(
                value = settings.subtitleSize,
                onValueChange = { vm.updatePlayerSettings(context, settings.copy(subtitleSize = it)) },
                valueRange = 50f..300f,
                steps = 10
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("VTT 자막 굵게", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                Switch(
                    checked = settings.vttBold,
                    onCheckedChange = { vm.updatePlayerSettings(context, settings.copy(vttBold = it)) }
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "VTT 자막 테두리 두께 (${String.format(java.util.Locale.US, "%.1f", settings.vttOutlineWidth)}dp)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Slider(
                value = settings.vttOutlineWidth,
                onValueChange = { vm.updatePlayerSettings(context, settings.copy(vttOutlineWidth = it)) },
                valueRange = 0.5f..6.0f,
                steps = 10
            )
            Text(
                "2dp는 Media3 기본 VTT 배치를 사용하고, 다른 값에서는 사용자 지정 테두리를 적용합니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
            )

            Spacer(Modifier.height(16.dp))

            val subtitlePositionPercent = (settings.subtitleBottomPaddingFraction * 100f)
                .roundToInt()
                .coerceIn(3, 30)
            Text(
                "기본 VTT 자막 위치 (${subtitlePositionPercent}%)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Slider(
                value = subtitlePositionPercent.toFloat(),
                onValueChange = { raw ->
                    val percent = raw.roundToInt().coerceIn(3, 30)
                    vm.updatePlayerSettings(
                        context,
                        settings.copy(subtitleBottomPaddingFraction = percent / 100f)
                    )
                },
                valueRange = 3f..30f,
                steps = 26
            )

            Spacer(Modifier.height(16.dp))

            Text("자막 싱크 미세 조정 (${settings.syncOffsetMs} ms)", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { 
                    vm.updatePlayerSettings(context, settings.copy(syncOffsetMs = settings.syncOffsetMs - 250L)) 
                }) {
                    Text("-250ms")
                }
                OutlinedButton(onClick = { 
                    vm.updatePlayerSettings(context, settings.copy(syncOffsetMs = 0L)) 
                }) {
                    Text("초기화")
                }
                OutlinedButton(onClick = { 
                    vm.updatePlayerSettings(context, settings.copy(syncOffsetMs = settings.syncOffsetMs + 250L)) 
                }) {
                    Text("+250ms")
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "더블탭 이동 시간 (${settings.doubleTapSeekSeconds}초)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "재생 화면의 왼쪽/오른쪽을 두 번 탭했을 때 이동할 시간을 설정합니다.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
            )
            Spacer(Modifier.height(8.dp))
            var doubleTapSecondsInput by remember(settings.doubleTapSeekSeconds) {
                mutableStateOf(settings.doubleTapSeekSeconds.toString())
            }
            OutlinedTextField(
                value = doubleTapSecondsInput,
                onValueChange = { input ->
                    if (input.all { it.isDigit() }) {
                        doubleTapSecondsInput = input
                        input.toLongOrNull()?.let { seconds ->
                            vm.updatePlayerSettings(context, settings.copy(doubleTapSeekSeconds = seconds))
                        }
                    }
                },
                singleLine = true,
                suffix = { Text("초") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(180.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "뒤로/앞으로 버튼 이동 시간 (${settings.seekButtonSeekSeconds}초)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "재생 화면 중앙의 뒤로/앞으로 버튼을 눌렀을 때 이동할 시간을 설정합니다. 더블탭 설정과 별도로 사용할 수 있습니다.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
            )
            Spacer(Modifier.height(8.dp))
            var buttonSeekSecondsInput by remember(settings.seekButtonSeekSeconds) {
                mutableStateOf(settings.seekButtonSeekSeconds.toString())
            }
            OutlinedTextField(
                value = buttonSeekSecondsInput,
                onValueChange = { input ->
                    if (input.all { it.isDigit() }) {
                        buttonSeekSecondsInput = input
                        input.toLongOrNull()?.let { seconds ->
                            vm.updatePlayerSettings(context, settings.copy(seekButtonSeekSeconds = seconds))
                        }
                    }
                },
                singleLine = true,
                suffix = { Text("초") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(180.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text("기본 자막 폰트", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("기본체", "나눔고딕", "명조체").forEach { font ->
                    FilterChip(
                        selected = settings.subtitleFont == font,
                        onClick = { vm.updatePlayerSettings(context, settings.copy(subtitleFont = font)) },
                        label = { Text(font) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text("AI 번역", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Text("로컬 AI 모델, 번역 프롬프트, 성능과 고급 추론 설정은 별도의 AI 설정에서 관리합니다.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .65f))
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { onNavigate("settings/ai") }, modifier = Modifier.fillMaxWidth()) { Text("AI 설정 열기") }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text(
                "OP/ED 분석 데이터",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "다운로드된 회차의 OP/ED 분석 결과와 학습된 오디오 Fingerprint를 저장합니다. 저장된 데이터가 있으면 다음 재생부터 다시 분석하지 않습니다.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("오프라인 OP/ED 자동 분석", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                    Text("다운로드 완료 회차에서만 자동 분석합니다.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
                Switch(
                    checked = settings.offlineOpEdAnalysisEnabled,
                    onCheckedChange = { enabled ->
                        vm.updatePlayerSettings(context, settings.copy(offlineOpEdAnalysisEnabled = enabled))
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { deleteTarget = "analysis" },
                    modifier = Modifier.weight(1f)
                ) { Text("분석 결과 삭제") }
                OutlinedButton(
                    onClick = { deleteTarget = "fingerprint" },
                    modifier = Modifier.weight(1f)
                ) { Text("Fingerprint 삭제") }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { deleteTarget = "all" },
                modifier = Modifier.fillMaxWidth()
            ) { Text("분석 결과 + Fingerprint 모두 삭제") }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text("Lilac Anime", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            val appVersion = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                .orEmpty()
                .ifBlank { "Unknown" }
            Text("Version $appVersion", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
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
