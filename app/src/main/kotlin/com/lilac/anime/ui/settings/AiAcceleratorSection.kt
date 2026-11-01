package com.lilac.anime.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lilac.anime.ai.AcceleratorHealth
import com.lilac.anime.ai.AcceleratorPolicy
import com.lilac.anime.ai.AiHardware
import com.lilac.anime.data.subtitle.translation.providers.LocalAiTranslationRuntime

/**
 * AiSettingsScreen 안의 원하는 위치에 `AiAcceleratorSection()` 한 줄로 넣으면 된다.
 * 칩셋/NPU 판정 결과, 현재 세션(엔진·가속기·모델), 가속기 선택(자동/NPU/GPU/CPU),
 * 실패 기록 초기화를 보여 준다.
 */
@Composable
internal fun AiAcceleratorSection() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(AcceleratorPolicy.mode(context)) }
    var refresh by remember { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("AI 가속기", style = MaterialTheme.typography.titleMedium)
        // refresh를 읽어 초기화 직후 다시 계산되게 한다
        key(refresh) {
            AiHardware.statusLines(context).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text("현재 세션: ${LocalAiTranslationRuntime.sessionSummary()}", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("auto" to "자동", "npu" to "NPU 우선", "gpu" to "GPU", "cpu" to "CPU").forEach { (value, label) ->
                OutlinedButton(
                    onClick = {
                        mode = value
                        prefs.edit().putString(AcceleratorPolicy.PREF_KEY, value).apply()
                        refresh++
                    }
                ) { Text(if (mode == value) "✓ $label" else label) }
            }
        }
        OutlinedButton(
            onClick = { AcceleratorHealth.reset(context); refresh++ },
            modifier = Modifier.fillMaxWidth()
        ) { Text("가속기 실패 기록 초기화") }
        Text(
            "NPU/GPU 초기화에 실패하면 자동으로 다음 가속기로 전환하고, 같은 조합은 7일간 건너뜁니다. " +
                "OS/앱 업데이트 후에는 자동으로 다시 시도합니다.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
