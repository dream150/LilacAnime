package com.lilac.anime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import com.lilac.anime.network.OfflineOpEdFingerprintStore

@Composable
fun SettingsScreen(
    vm: AnimeViewModel,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
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
                listOf("linkkf" to "Linkkf", "animenosub" to "Animenosub").forEach { (source, label) ->
                    FilterChip(
                        selected = settings.videoSourcePreference == source,
                        onClick = {
                            vm.updatePlayerSettings(context, settings.copy(videoSourcePreference = source))
                        },
                        label = { Text(label) }
                    )
                }
            }
            Text(
                "선택한 사이트의 애니 목록·제목·회차·영상 소스를 함께 사용합니다. Linkkf는 기존 방식 그대로 유지됩니다.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
            )

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

            Spacer(Modifier.height(16.dp))

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

            Text(
                "기본 VTT 자막 위치 (${(settings.subtitleBottomPaddingFraction * 100).toInt()}%)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Slider(
                value = settings.subtitleBottomPaddingFraction,
                onValueChange = {
                    vm.updatePlayerSettings(
                        context,
                        settings.copy(subtitleBottomPaddingFraction = it)
                    )
                },
                valueRange = 0.03f..0.30f,
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
                "더블 탭 이동 (${settings.doubleTapSeekSeconds}초)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text("재생 화면의 왼쪽/오른쪽을 두 번 탭하면 지정한 시간만큼 뒤로/앞으로 이동합니다.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f))
            Spacer(Modifier.height(8.dp))
            Slider(
                value = settings.doubleTapSeekSeconds.toFloat(),
                onValueChange = { value ->
                    vm.updatePlayerSettings(context, settings.copy(doubleTapSeekSeconds = value.toInt()))
                },
                valueRange = 5f..60f,
                steps = 10
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
