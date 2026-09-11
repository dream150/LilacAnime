package com.lilac.anime

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lilac.anime.dns.DnsController
import com.lilac.anime.dns.DnsMode
import com.lilac.anime.dns.DnsProfile
import com.lilac.anime.dns.DnsProviders
import com.lilac.anime.dns.DnsRunState
import com.lilac.anime.dns.DnsRuntimeState
import com.lilac.anime.dns.DnsRuntimeStatus
import com.lilac.anime.dns.DnsSettings
import com.lilac.anime.dns.DnsSettingsRepository
import com.lilac.anime.dns.DnsStatusReason
import com.lilac.anime.dns.DnsValidation
import kotlinx.coroutines.launch

/**
 * 설정 > DNS.
 *
 * 일반 사용자에게는 provider 선택과 상태만 보여주고, 기술적인 값(프로토콜 / 주소 /
 * DoH URL / Bootstrap)은 "고급 설정" 안에 둔다. 기본값은 항상 시스템 기본이다.
 */
@Composable
fun DnsSettingsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DnsSettingsRepository(context) }
    val settings by repository.flow.collectAsState(initial = DnsSettings())
    val runtime by DnsRuntimeState.status.collectAsState()

    var showAdvanced by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // VPN 권한 승인/거부 처리
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        scope.launch {
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                DnsController.start(context)
            } else {
                // 거부하면 DNS 변경을 시작하지 않는다.
                val current = repository.current()
                repository.save(current.copy(enabled = false))
                message = "VPN 권한이 없어 DNS 변경을 시작하지 않았습니다."
            }
        }
    }

    fun persist(next: DnsSettings) {
        scope.launch { repository.save(DnsValidation.normalize(next)) }
    }

    /** 설정을 저장하고, 유효할 때만 실제로 적용한다. */
    fun apply(next: DnsSettings) {
        val normalized = DnsValidation.normalize(next)
        persist(normalized)

        if (!normalized.enabled || DnsValidation.validate(normalized).isNotEmpty()) {
            // 유효하지 않으면 VPN 을 켜지 않고, 켜져 있으면 끈다.
            DnsController.stop(context)
            return
        }

        val permissionIntent = DnsController.permissionIntent(context)
        if (permissionIntent != null) {
            permissionLauncher.launch(permissionIntent)
            return
        }
        if (DnsController.isServiceRunning) {
            DnsController.reload(context)
        } else {
            DnsController.start(context)
        }
    }

    fun selectProfile(candidate: DnsProfile) {
        message = null
        if (candidate.id == DnsProviders.SYSTEM) {
            persist(settings.copy(enabled = false, profileId = DnsProviders.SYSTEM, mode = DnsMode.SYSTEM))
            DnsController.stop(context)
            return
        }
        val mode = if (settings.mode == DnsMode.SYSTEM) DnsMode.UDP else settings.mode
        apply(settings.copy(enabled = true, profileId = candidate.id, mode = mode))
    }

    val profile = settings.activeProfile
    val isSystem = settings.profileId == DnsProviders.SYSTEM && settings.mode == DnsMode.SYSTEM
    val errors = if (isSystem) emptyList() else DnsValidation.validate(settings)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "DNS",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "앱의 DNS 질의를 선택한 resolver 로 보냅니다. 루팅 없이 동작하도록 Android VPN 위에서 DNS 만 가로챕니다.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
        )
        Spacer(Modifier.height(12.dp))

        Text(
            "현재 DNS  ·  ${describeStatus(settings, runtime)}",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (runtime.state == DnsRunState.ACTIVE) {
            Spacer(Modifier.height(2.dp))
            Text(
                "질의 ${runtime.queries} · 캐시 적중 ${runtime.cacheHits} · 실패 ${runtime.failures}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(12.dp))

        Text(
            "DNS provider",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DnsProviders.builtIn.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { candidate ->
                        FilterChip(
                            selected = settings.profileId == candidate.id,
                            onClick = { selectProfile(candidate) },
                            label = { Text(candidate.name) },
                        )
                    }
                }
            }
            FilterChip(
                selected = settings.profileId == DnsProviders.CUSTOM,
                onClick = {
                    message = null
                    if (settings.profileId != DnsProviders.CUSTOM) {
                        // 사용자 지정을 고르면 입력할 수 있게만 하고, 유효해질 때 적용한다.
                        persist(
                            settings.copy(
                                profileId = DnsProviders.CUSTOM,
                                mode = if (settings.mode == DnsMode.SYSTEM) DnsMode.UDP else settings.mode,
                                enabled = false,
                            )
                        )
                        DnsController.stop(context)
                    }
                },
                label = { Text("사용자 지정") },
            )
        }

        if (!isSystem) {
            Spacer(Modifier.height(6.dp))
            Text(
                describeProfileAddress(profile),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            )
        }

        if (errors.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            errors.forEach { error ->
                Text(error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "고급 설정 닫기" else "고급 설정")
        }

        if (showAdvanced) {
            if (!isSystem) {
                Text(
                    "프로토콜",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.mode == DnsMode.UDP,
                        onClick = { apply(settings.copy(mode = DnsMode.UDP)) },
                        label = { Text("일반 DNS") },
                    )
                    FilterChip(
                        selected = settings.mode == DnsMode.DOH,
                        onClick = { apply(settings.copy(mode = DnsMode.DOH)) },
                        label = { Text("DNS over HTTPS") },
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            if (settings.profileId == DnsProviders.CUSTOM) {
                CustomDnsFields(
                    settings = settings,
                    onPrimaryChange = { value -> persist(settings.copy(primary = value)) },
                    onSecondaryChange = { value -> persist(settings.copy(secondary = value)) },
                    onDohUrlChange = { value -> persist(settings.copy(dohUrl = value)) },
                    onBootstrapChange = { value -> persist(settings.copy(bootstrap = value)) },
                )
                Spacer(Modifier.height(10.dp))
            }

            if (!isSystem) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "실패 시 시스템 DNS 사용",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            "끄면 선택한 provider 가 실패해도 다른 DNS 로 넘어가지 않습니다.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                    Switch(
                        checked = settings.fallbackToSystem,
                        onCheckedChange = { enabled -> apply(settings.copy(fallbackToSystem = enabled)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                isSystem -> {
                    Button(enabled = false, onClick = { }) { Text("시스템 DNS 사용 중") }
                }

                settings.enabled && DnsController.isServiceRunning -> {
                    OutlinedButton(onClick = { apply(settings) }) { Text("설정 적용") }
                    Button(onClick = {
                        message = null
                        DnsController.stop(context)
                        persist(settings.copy(enabled = false))
                    }) { Text("DNS 끄기") }
                }

                else -> {
                    Button(
                        enabled = errors.isEmpty(),
                        onClick = {
                            message = null
                            apply(settings.copy(enabled = true))
                        },
                    ) { Text("DNS 변경 시작") }
                }
            }
        }

        message?.let { text ->
            Spacer(Modifier.height(6.dp))
            Text(
                text,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Android 일반 앱은 시스템 DNS 설정을 직접 바꿀 수 없습니다. 이 기능은 VPN 기반 DNS proxy 이며, " +
                "앱 자체에 하드코딩된 다른 DNS 를 쓰는 앱의 질의까지 강제로 바꾸지는 않습니다.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CustomDnsFields(
    settings: DnsSettings,
    onPrimaryChange: (String) -> Unit,
    onSecondaryChange: (String) -> Unit,
    onDohUrlChange: (String) -> Unit,
    onBootstrapChange: (String) -> Unit,
) {
    if (settings.mode == DnsMode.DOH) {
        OutlinedTextField(
            value = settings.dohUrl.orEmpty(),
            onValueChange = onDohUrlChange,
            label = { Text("DoH URL") },
            placeholder = { Text("https://example.com/dns-query") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }

    OutlinedTextField(
        value = settings.primary.orEmpty(),
        onValueChange = onPrimaryChange,
        label = { Text("Primary DNS") },
        placeholder = { Text("1.1.1.1") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = settings.secondary.orEmpty(),
        onValueChange = onSecondaryChange,
        label = { Text("Secondary DNS (선택)") },
        placeholder = { Text("1.0.0.1") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (settings.mode == DnsMode.DOH) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.bootstrap.orEmpty(),
            onValueChange = onBootstrapChange,
            label = { Text("Bootstrap DNS (선택)") },
            placeholder = { Text("1.1.1.1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "DoH 서버 주소를 풀기 위해 사용하는 IP 입니다. 비워두면 Primary 를 사용합니다.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }

    Text(
        "IPv4 와 IPv6 주소를 모두 입력할 수 있습니다.",
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
    )
}

private fun describeProfileAddress(profile: DnsProfile): String = when (profile.mode) {
    DnsMode.SYSTEM -> "Android 기본 DNS"
    DnsMode.UDP -> listOfNotNull(profile.primary, profile.secondary).joinToString(" / ")
    DnsMode.DOH -> profile.dohUrl.orEmpty()
}

private fun describeStatus(settings: DnsSettings, runtime: DnsRuntimeStatus): String {
    if (settings.profileId == DnsProviders.SYSTEM && settings.mode == DnsMode.SYSTEM) {
        return "시스템 기본"
    }
    return when (runtime.state) {
        DnsRunState.ACTIVE -> runtime.profileName ?: "사용 중"
        DnsRunState.STARTING -> "연결 중"
        DnsRunState.ERROR -> when (runtime.reason) {
            DnsStatusReason.ANOTHER_VPN_ACTIVE -> "연결 실패 · 다른 VPN 사용 중"
            DnsStatusReason.INVALID_SETTINGS -> "연결 실패 · 설정 오류"
            DnsStatusReason.FOREGROUND_SERVICE_UNAVAILABLE -> "연결 실패 · 백그라운드 실행 불가"
            else -> "연결 실패"
        }

        DnsRunState.OFF -> when {
            !settings.enabled -> "시스템 기본 (DNS 끔)"
            runtime.reason == DnsStatusReason.PERMISSION_REQUIRED -> "권한 필요"
            else -> "중지됨"
        }
    }
}
