package com.lilac.anime.ui.settings

import com.lilac.anime.ui.navigation.AppScaffold

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lilac.anime.core.model.PlayerSettings
import com.lilac.anime.data.subtitle.translation.SecureApiKeyStore
import com.lilac.anime.data.subtitle.translation.TranslationCache
import com.lilac.anime.data.subtitle.translation.TranslationManager
import com.lilac.anime.data.subtitle.TmdbTitleResolver
import com.lilac.anime.data.subtitle.translation.localai.*
import com.lilac.anime.ai.llm.LiteRtModelStore
import com.lilac.anime.ai.llm.LocalLlmModel
import com.lilac.anime.ai.llm.LocalModelHub
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.lilac.anime.data.subtitle.translation.providers.LocalAiTranslationRuntime
import com.lilac.anime.data.subtitle.translation.providers.CloudModelCatalog
import com.lilac.anime.viewmodel.AnimeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private fun aiFormatBytes(bytes: Long): String = when {
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    bytes < 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun aiFmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

private enum class AiTab(val label: String) {
    ENGINE("엔진"),
    MODELS("로컬 모델"),
    GENERATION("생성"),
    PROMPT("프롬프트"),
    TOOLS("도구")
}

// ───────────────────────── 재사용 UI 조각 ─────────────────────────

@Composable
private fun AiHint(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun AiSectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (subtitle != null) AiHint(subtitle)
            }
            content()
        }
    }
}

@Composable
private fun AiChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun AiSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onFinish: () -> Unit,
    hint: String? = null,
    enabled: Boolean = true
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onFinish,
            enabled = enabled
        )
        if (hint != null) AiHint(hint)
    }
}

/** 라디오 + 본문 + (선택) 하단 액션이 있는 선택형 카드. 선택되면 테두리와 배경이 강조된다. */
@Composable
private fun AiSelectableCard(
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (selected) cs.primaryContainer.copy(alpha = .35f) else cs.surface),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outline.copy(alpha = .4f))
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).alpha(if (enabled) 1f else .5f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                content()
            }
        }
        if (footer != null) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.End) {
                footer(this)
            }
        }
    }
}

@Composable
private fun AiCloudModelRow(
    label: String,
    current: String,
    items: List<String>,
    expanded: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onMainClick: () -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = onMainClick, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
                Text("$label: $current", maxLines = 2)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.heightIn(max = 360.dp)) {
                items.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { onPick(item) }) }
            }
        }
        OutlinedButton(onClick = onReload, enabled = enabled) { Text(if (loading) "조회 중" else "목록 조회") }
    }
}

@Composable
fun AiSettingsScreen(vm: AnimeViewModel, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val settings = vm.playerSettings
    var models by remember { mutableStateOf<List<LocalAiModel>>(emptyList()) }
    var litertModels by remember { mutableStateOf<List<LocalLlmModel>>(emptyList()) }
    var confirmDeleteLiteRt by remember { mutableStateOf<LocalLlmModel?>(null) }
    var hfToken by remember { mutableStateOf(SecureApiKeyStore.get(context, "huggingface").orEmpty()) }
    var runtimes by remember { mutableStateOf<List<RuntimePack>>(emptyList()) }
    var loadingInstalled by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var repoQuery by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<HuggingFaceRepo>>(emptyList()) }
    var files by remember { mutableStateOf<List<HuggingFaceModelFile>>(emptyList()) }
    var selectedRepo by remember { mutableStateOf<String?>(null) }
    var received by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(0L) }
    var expandedRuntime by remember { mutableStateOf<String?>(null) }
    var testText by remember { mutableStateOf("こんにちは、今日はいい天気ですね。") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var latestAiError by remember { mutableStateOf(AiRuntimeDiagnostics.latest(context)) }
    var showAiErrorDetails by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var keyProvider by remember { mutableStateOf<String?>(null) }
    var keyText by remember { mutableStateOf("") }
    var keyMessage by remember { mutableStateOf<String?>(null) }
    var tmdbKeyText by remember { mutableStateOf(SecureApiKeyStore.get(context, "tmdb").orEmpty()) }
    var tmdbMessage by remember { mutableStateOf<String?>(null) }
    val aiPrefs = remember { context.getSharedPreferences("lilac_offline_store", android.content.Context.MODE_PRIVATE) }
    val keyProviders = listOf("gemini" to "Gemini", "openai" to "OpenAI", "deepl" to "DeepL", "qwen" to "Qwen")
    var geminiModel by remember { mutableStateOf(aiPrefs.getString("pref_gemini_model", "gemini-2.5-flash") ?: "gemini-2.5-flash") }
    var openAiModel by remember { mutableStateOf(aiPrefs.getString("pref_openai_model", "gpt-4.1-mini") ?: "gpt-4.1-mini") }
    var qwenModel by remember { mutableStateOf(aiPrefs.getString("pref_qwen_model", "qwen-plus") ?: "qwen-plus") }
    var qwenRegion by remember { mutableStateOf(aiPrefs.getString("pref_qwen_region", "international") ?: "international") }
    var geminiModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var openAiModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var qwenModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelListLoading by remember { mutableStateOf<String?>(null) }
    var modelListMessage by remember { mutableStateOf<String?>(null) }
    var expandedCloudModel by remember { mutableStateOf<String?>(null) }
    fun loadCloudModels(provider: String) {
        scope.launch {
            modelListLoading = provider
            modelListMessage = "$provider 모델 목록 조회 중..."
            runCatching { CloudModelCatalog.fetch(context, provider, qwenRegion) }
                .onSuccess { fetched ->
                    when (provider) {
                        "gemini" -> { geminiModels = fetched; if (geminiModel !in fetched && fetched.isNotEmpty()) { geminiModel = fetched.first(); aiPrefs.edit().putString("pref_gemini_model", geminiModel).apply() } }
                        "openai" -> { openAiModels = fetched; if (openAiModel !in fetched && fetched.isNotEmpty()) { openAiModel = fetched.first(); aiPrefs.edit().putString("pref_openai_model", openAiModel).apply() } }
                        "qwen" -> { qwenModels = fetched; if (qwenModel !in fetched && fetched.isNotEmpty()) { qwenModel = fetched.first(); aiPrefs.edit().putString("pref_qwen_model", qwenModel).apply() } }
                    }
                    modelListMessage = if (fetched.isEmpty()) "${provider}에서 사용할 수 있는 모델을 찾지 못했습니다." else "$provider 사용 가능 모델 ${fetched.size}개"
                    expandedCloudModel = provider
                }
                .onFailure { AiRuntimeDiagnostics.record(context, "클라우드 모델 목록 조회", it); latestAiError = AiRuntimeDiagnostics.latest(context); modelListMessage = "모델 목록 조회 실패: ${it.message.orEmpty()}" }
            modelListLoading = null
        }
    }
    var expandedAdvanced by remember { mutableStateOf(false) }
    var confirmDeleteModel by remember { mutableStateOf<LocalAiModel?>(null) }
    var promptMode by remember { mutableStateOf(aiPrefs.getString("pref_ai_prompt_mode", "chat") ?: "chat") }
    var thinkingMode by remember { mutableStateOf(aiPrefs.getString("pref_ai_thinking_mode", LocalAiTranslationRuntime.THINKING_OFF) ?: LocalAiTranslationRuntime.THINKING_OFF) }
    var minP by remember { mutableStateOf(aiPrefs.getFloat("pref_ai_min_p", 0.0f)) }
    var typicalP by remember { mutableStateOf(aiPrefs.getFloat("pref_ai_typical_p", 1.0f)) }
    var repeatLastN by remember { mutableStateOf(aiPrefs.getInt("pref_ai_repeat_last_n", 64).toFloat()) }
    var frequencyPenalty by remember { mutableStateOf(aiPrefs.getFloat("pref_ai_frequency_penalty", 0.0f)) }
    var presencePenalty by remember { mutableStateOf(aiPrefs.getFloat("pref_ai_presence_penalty", 0.0f)) }
    var seedText by remember { mutableStateOf(aiPrefs.getInt("pref_ai_seed", -1).toString()) }
    var systemPrompt by remember { mutableStateOf(aiPrefs.getString("pref_ai_system_prompt", LocalAiTranslationRuntime.DEFAULT_SYSTEM_PROMPT) ?: LocalAiTranslationRuntime.DEFAULT_SYSTEM_PROMPT) }
    var contextSize by remember(settings.aiContextSize) { mutableStateOf(settings.aiContextSize.toFloat()) }
    var maxTokens by remember(settings.aiMaxTokens) { mutableStateOf(settings.aiMaxTokens.toFloat()) }
    var temperature by remember(settings.aiTemperature) { mutableStateOf(settings.aiTemperature) }
    var topP by remember(settings.aiTopP) { mutableStateOf(settings.aiTopP) }
    var topK by remember(settings.aiTopK) { mutableStateOf(settings.aiTopK.toFloat()) }
    var repetitionPenalty by remember(settings.aiRepetitionPenalty) { mutableStateOf(settings.aiRepetitionPenalty) }
    var contextCues by remember(settings.aiContextCues) { mutableStateOf(settings.aiContextCues.toFloat()) }
    var prefetchAhead by remember(settings.aiPrefetchAhead) { mutableStateOf(settings.aiPrefetchAhead.toFloat()) }
    var translationImportMessage by remember { mutableStateOf<String?>(null) }
    var translationImportWorking by remember { mutableStateOf(false) }
    fun refreshInstalled() {
        scope.launch(Dispatchers.IO) {
            val m = LocalAiModelManager.installed(context)
            val r = LocalAiRuntimeManager.listInstalled(context)
            val lm = runCatching { LiteRtModelStore.installed(context) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main.immediate) { models = m; litertModels = lm; runtimes = r; loadingInstalled = false }
        }
    }
    LaunchedEffect(Unit) { refreshInstalled() }

    val translationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !translationImportWorking) {
            scope.launch {
                translationImportWorking = true
                translationImportMessage = "자막 번역 중..."
                runCatching {
                    val sourceName = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "subtitle.srt"
                    val temp = File(context.cacheDir, "subtitle_import_${System.currentTimeMillis()}_${sourceName.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                    context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } } ?: error("자막 파일을 읽을 수 없습니다.")
                    try {
                        val translated = TranslationManager.translateFile(context, temp.absolutePath, "settings", temp.nameWithoutExtension, settings.translationProvider) ?: error("자막을 번역하지 못했습니다.")
                        val base = sourceName.substringBeforeLast('.', sourceName)
                        val ext = sourceName.substringAfterLast('.', "srt")
                        val name = "${base}_ko.${ext}"
                        TranslationManager.exportTranslatedFile(context, translated, name) ?: error("번역본 파일을 저장하지 못했습니다.")
                        translationImportMessage = "번역본을 다운로드 폴더의 LilacAnime에 저장했습니다: $name"
                    } finally { temp.delete() }
                }.onFailure { AiRuntimeDiagnostics.record(context, "자막 파일 번역", it); latestAiError = AiRuntimeDiagnostics.latest(context); translationImportMessage = "번역 실패: ${it.message.orEmpty()}" }
                translationImportWorking = false
            }
        }
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = "모델 파일 가져오는 중..."
            runCatching {
                val picked = LiteRtModelStore.displayNameOf(context, uri).orEmpty()
                if (picked.endsWith(".litertlm", true)) LiteRtModelStore.importFile(context, uri).displayName
                else LocalAiModelManager.importFile(context, uri).displayName
            }
                .onSuccess {
                    refreshInstalled()
                    message = "모델을 추가했습니다: $it"
                }
                .onFailure { AiRuntimeDiagnostics.record(context, "모델 파일 가져오기", it); latestAiError = AiRuntimeDiagnostics.latest(context); message = "모델 추가 실패: ${it.message.orEmpty()}" }
        }
    }

    val runtimePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = "runtime pack 설치 중..."
            runCatching {
                val temp = File.createTempFile("runtime-", ".zip", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } } ?: error("runtime pack을 읽을 수 없습니다.")
                try { LocalAiRuntimeManager.installZip(context, temp) } finally { temp.delete() }
            }.onSuccess { runtimes = LocalAiRuntimeManager.listInstalled(context); message = "runtime pack이 설치되었습니다: ${it.name}" }
                .onFailure { AiRuntimeDiagnostics.record(context, "Runtime pack 설치", it); latestAiError = AiRuntimeDiagnostics.latest(context); message = "runtime pack 설치 실패: ${it.message.orEmpty()}" }
        }
    }


    // ───────────────────────── 화면 전용 상태/헬퍼 ─────────────────────────
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var cacheMessage by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    LaunchedEffect(tabIndex) { scrollState.scrollTo(0) }
    val tabs = AiTab.values()

    fun saveThinking(mode: String) {
        thinkingMode = mode
        aiPrefs.edit().putString("pref_ai_thinking_mode", mode).apply()
    }
    fun savePromptMode(mode: String) {
        promptMode = mode
        aiPrefs.edit().putString("pref_ai_prompt_mode", mode).apply()
    }
    fun selectLocalModel(id: String?) {
        vm.updatePlayerSettings(context, settings.copy(translationProvider = "local", translationModelId = id))
        if (id == null) aiPrefs.edit().remove("pref_translation_model_id").apply()
    }
    fun pickCloudModel(provider: String, label: String, item: String) {
        when (provider) {
            "gemini" -> { geminiModel = item; aiPrefs.edit().putString("pref_gemini_model", item).apply() }
            "openai" -> { openAiModel = item; aiPrefs.edit().putString("pref_openai_model", item).apply() }
            "qwen" -> { qwenModel = item; aiPrefs.edit().putString("pref_qwen_model", item).apply() }
        }
        expandedCloudModel = null
        modelListMessage = "$label 모델 선택: $item"
    }
    fun toggleCloudMenu(provider: String, hasItems: Boolean) {
        if (!hasItems) loadCloudModels(provider)
        else expandedCloudModel = if (expandedCloudModel == provider) null else provider
    }
    fun searchOrListRepo() {
        scope.launch {
            searching = true
            runCatching {
                if (repoQuery.contains("/")) {
                    selectedRepo = repoQuery.trim()
                    LocalModelHub.listFiles(selectedRepo!!, hfToken.trim().ifBlank { null })
                } else {
                    repos = LocalAiModelManager.searchRepos(repoQuery)
                    emptyList()
                }
            }.onSuccess { files = it }
                .onFailure {
                    AiRuntimeDiagnostics.record(context, "Hugging Face 모델 조회", it)
                    latestAiError = AiRuntimeDiagnostics.latest(context)
                    message = "Hugging Face 조회 실패: ${it.message.orEmpty()}"
                }
            searching = false
        }
    }
    fun pickRepo(repoId: String) {
        scope.launch {
            selectedRepo = repoId
            runCatching { LocalModelHub.listFiles(repoId, hfToken.trim().ifBlank { null }) }
                .onSuccess { files = it }
                .onFailure {
                    AiRuntimeDiagnostics.record(context, "모델 파일 목록 조회", it)
                    latestAiError = AiRuntimeDiagnostics.latest(context)
                    message = "파일 목록 조회 실패: ${it.message.orEmpty()}"
                }
        }
    }
    fun startDownload(file: HuggingFaceModelFile) {
        if (downloading) return
        scope.launch {
            downloading = true
            received = 0L
            total = file.sizeBytes
            runCatching {
                LocalModelHub.download(context, file, hfToken.trim().ifBlank { null }) { r, t ->
                    withContext(Dispatchers.Main.immediate) { received = r; total = t }
                }
            }.onSuccess { refreshInstalled(); message = "모델이 설치되었습니다." }
                .onFailure {
                    AiRuntimeDiagnostics.record(context, "모델 다운로드", it)
                    latestAiError = AiRuntimeDiagnostics.latest(context)
                    message = "모델 다운로드 실패: ${it.message.orEmpty()}"
                }
            downloading = false
        }
    }

    val providerLabel = when (settings.translationProvider) {
        "local" -> "로컬 AI"
        "gemini" -> "Gemini"
        "openai" -> "OpenAI"
        "deepl" -> "DeepL"
        "qwen" -> "Qwen"
        else -> settings.translationProvider
    }
    val activeModelLabel = when (settings.translationProvider) {
        "local" -> settings.translationModelId?.let { id ->
            models.firstOrNull { it.id == id }?.displayName
                ?: litertModels.firstOrNull { it.id == id }?.displayName
                ?: id
        } ?: "자동 선택"
        "gemini" -> geminiModel
        "openai" -> openAiModel
        "qwen" -> qwenModel
        else -> "기본 모델"
    }
    val thinkingLabel = when (thinkingMode) {
        LocalAiTranslationRuntime.THINKING_ON -> "ON"
        LocalAiTranslationRuntime.THINKING_OFF -> "OFF"
        else -> "자동"
    }

    AppScaffold(selected = "settings", onSelect = onNavigate) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── 헤더 ──
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("AI 설정", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    AiHint("번역 엔진, 모델, 생성 옵션, 프롬프트를 관리합니다.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = .65f))
                }
                TextButton(onClick = { onNavigate("settings") }) { Text("설정으로") }
            }

            // ── 탭 ──
            ScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 12.dp, containerColor = Color.Transparent) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tabIndex == i, onClick = { tabIndex = i }, text = { Text(t.label, maxLines = 1) })
                }
            }

            // ── 본문 ──
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 현재 설정 요약 (모든 탭 공통)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("현재 사용 중", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                        Text("$providerLabel · $activeModelLabel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            if (settings.translationProvider == "local")
                                "Context ${settings.aiContextSize} · Thinking $thinkingLabel · 직전 자막 ${settings.aiContextCues}개 참고"
                            else "클라우드 API · 네트워크 필요",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f)
                        )
                    }
                }

                // 최근 오류 (모든 탭 공통)
                latestAiError?.let { err ->
                    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("최근 AI / Runtime 오류", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("${err.time} · ${err.stage}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(err.summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("해결 방법: ${err.advice}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                            AiChipRow {
                                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(err.asCopyText())) }) { Text("오류 전체 복사") }
                                TextButton(onClick = { showAiErrorDetails = true }) { Text("상세 정보") }
                                TextButton(onClick = { AiRuntimeDiagnostics.clear(context); latestAiError = null }) { Text("지우기") }
                            }
                        }
                    }
                }

                when (tabs[tabIndex]) {
                    // ════════════════════════ 엔진 ════════════════════════
                    AiTab.ENGINE -> {
                        AiSectionCard("번역 엔진", "자막 번역에 사용할 엔진을 선택합니다.") {
                            AiChipRow {
                                listOf("local" to "로컬 AI", "gemini" to "Gemini", "openai" to "OpenAI", "deepl" to "DeepL", "qwen" to "Qwen").forEach { (id, label) ->
                                    FilterChip(
                                        selected = settings.translationProvider == id,
                                        onClick = { vm.updatePlayerSettings(context, settings.copy(translationProvider = id)) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            if (settings.translationProvider == "local") {
                                AiHint("기기 안에서 번역하므로 네트워크가 필요 없습니다. 모델은 '로컬 모델' 탭에서 관리합니다.")
                                TextButton(onClick = { tabIndex = AiTab.MODELS.ordinal }) { Text("로컬 모델 관리") }
                            } else {
                                AiHint("클라우드 API는 네트워크와 API 사용량이 필요합니다. 재생 중 번역은 미리 자막을 읽어 한 cue씩 요청하므로 로컬 모델보다 지연될 수 있습니다.")
                            }
                        }

                        AiSectionCard("클라우드 API 키", "API Key는 Android Keystore로 암호화하여 저장합니다.") {
                            keyProviders.forEach { (id, label) ->
                                val saved = SecureApiKeyStore.has(context, id)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(label, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (saved) "저장됨" else "미설정",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(onClick = { keyProvider = id; keyText = SecureApiKeyStore.get(context, id).orEmpty(); keyMessage = null }) {
                                        Text(if (saved) "변경" else "입력")
                                    }
                                }
                            }
                        }

                        AiSectionCard("클라우드 모델", "모델명을 직접 입력하지 않고, 저장된 API Key로 조회한 목록에서 선택합니다.") {
                            AiCloudModelRow(
                                label = "Gemini", current = geminiModel, items = geminiModels,
                                expanded = expandedCloudModel == "gemini", loading = modelListLoading == "gemini", enabled = modelListLoading == null,
                                onMainClick = { toggleCloudMenu("gemini", geminiModels.isNotEmpty()) },
                                onReload = { loadCloudModels("gemini") },
                                onDismiss = { expandedCloudModel = null },
                                onPick = { pickCloudModel("gemini", "Gemini", it) }
                            )
                            AiCloudModelRow(
                                label = "OpenAI", current = openAiModel, items = openAiModels,
                                expanded = expandedCloudModel == "openai", loading = modelListLoading == "openai", enabled = modelListLoading == null,
                                onMainClick = { toggleCloudMenu("openai", openAiModels.isNotEmpty()) },
                                onReload = { loadCloudModels("openai") },
                                onDismiss = { expandedCloudModel = null },
                                onPick = { pickCloudModel("openai", "OpenAI", it) }
                            )
                            AiCloudModelRow(
                                label = "Qwen", current = qwenModel, items = qwenModels,
                                expanded = expandedCloudModel == "qwen", loading = modelListLoading == "qwen", enabled = modelListLoading == null,
                                onMainClick = { toggleCloudMenu("qwen", qwenModels.isNotEmpty()) },
                                onReload = { loadCloudModels("qwen") },
                                onDismiss = { expandedCloudModel = null },
                                onPick = { pickCloudModel("qwen", "Qwen", it) }
                            )
                            modelListMessage?.let { AiHint(it) }
                            HorizontalDivider()
                            Text("Qwen API 지역", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            AiChipRow {
                                FilterChip(selected = qwenRegion == "international", onClick = { qwenRegion = "international"; qwenModels = emptyList(); aiPrefs.edit().putString("pref_qwen_region", qwenRegion).apply() }, label = { Text("International / Singapore") })
                                FilterChip(selected = qwenRegion == "china", onClick = { qwenRegion = "china"; qwenModels = emptyList(); aiPrefs.edit().putString("pref_qwen_region", qwenRegion).apply() }, label = { Text("China") })
                            }
                        }
                    }

                    // ════════════════════════ 로컬 모델 ════════════════════════
                    AiTab.MODELS -> {
                        message?.let { msg ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(msg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    TextButton(onClick = { message = null }) { Text("닫기") }
                                }
                            }
                        }

                        AiSectionCard("설치된 로컬 모델", "사용할 모델을 선택하세요. GGUF와 .litertlm을 지원합니다.") {
                            when {
                                loadingInstalled -> {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    AiHint("모델 정보를 읽는 중...")
                                }
                                models.isEmpty() && litertModels.isEmpty() ->
                                    AiHint("설치된 로컬 모델이 없습니다. 아래 '모델 추가'에서 받거나 파일을 가져오세요.")
                                else -> {
                                    AiSelectableCard(selected = settings.translationModelId == null, onClick = { selectLocalModel(null) }) {
                                        Text("자동 선택 (권장)", fontWeight = FontWeight.SemiBold)
                                        AiHint("NPU 빌드 > GGUF NPU 팩 > GPU 빌드 > 나머지 순으로 시도하고, 실패하면 다음 후보로 넘어갑니다.")
                                    }

                                    litertModels.forEach { model ->
                                        AiSelectableCard(
                                            selected = settings.translationModelId == model.id,
                                            enabled = model.deviceCompatible,
                                            onClick = { selectLocalModel(model.id) },
                                            footer = {
                                                TextButton(onClick = { confirmDeleteLiteRt = model }) {
                                                    Text("삭제", color = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        ) {
                                            Text(model.displayName, fontWeight = FontWeight.SemiBold)
                                            AiHint("${aiFormatBytes(model.sizeBytes)} · LiteRT-LM (.litertlm)")
                                            Text(
                                                when {
                                                    !model.deviceCompatible -> "다른 SoC 전용 빌드 — 이 기기에서는 사용할 수 없음"
                                                    model.npuTarget -> "이 기기용 NPU 빌드"
                                                    else -> "범용 빌드 (GPU/CPU)"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    models.forEach { model ->
                                        val candidates = RuntimeRegistry.compatible(context, model)
                                        val selectedId = RuntimeRegistry.selectedId(context, model)
                                        val runtimeName = if (selectedId == RuntimeRegistry.AUTO) "자동 선택" else candidates.firstOrNull { it.id == selectedId }?.let { "${it.name} ${it.version}" } ?: "자동 선택"
                                        val adapter = LocalAiAdapterRegistry.resolve(model)
                                        val profile = model.profile
                                        val featureNames = buildList {
                                            if (profile.supports(LocalAiFeature.THINKING_CONTROL)) add("Thinking")
                                            if (profile.supports(LocalAiFeature.VISION)) add("Vision")
                                            if (profile.supports(LocalAiFeature.TOOL_CALLING)) add("Tool")
                                        }
                                        AiSelectableCard(
                                            selected = settings.translationModelId == model.id,
                                            enabled = candidates.isNotEmpty(),
                                            onClick = { selectLocalModel(model.id) },
                                            footer = {
                                                if (candidates.isNotEmpty()) {
                                                    TextButton(onClick = { expandedRuntime = if (expandedRuntime == model.id) null else model.id }) {
                                                        Text("Runtime · $runtimeName")
                                                    }
                                                }
                                                TextButton(onClick = { confirmDeleteModel = model }) {
                                                    Text("삭제", color = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        ) {
                                            Text(model.displayName, fontWeight = FontWeight.SemiBold)
                                            AiHint("${model.sizeLabel} · ${model.quantization ?: "?"} · ${model.architecture ?: "?"}")
                                            Text("${profile.displayName} · ${adapter.displayName} · ${adapter.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                            if (featureNames.isNotEmpty()) AiHint("기능: ${featureNames.joinToString(" · ")}")
                                            AiHint(if (candidates.isEmpty()) "호환 runtime 없음" else "Runtime: $runtimeName")
                                        }
                                        if (candidates.isNotEmpty() && expandedRuntime == model.id) {
                                            AiChipRow {
                                                FilterChip(
                                                    selected = selectedId == RuntimeRegistry.AUTO,
                                                    onClick = { RuntimeRegistry.setSelectedId(context, model, RuntimeRegistry.AUTO); expandedRuntime = null; refreshInstalled() },
                                                    label = { Text("자동 선택") }
                                                )
                                                candidates.forEach { rt ->
                                                    FilterChip(
                                                        selected = selectedId == rt.id,
                                                        onClick = { RuntimeRegistry.setSelectedId(context, model, rt.id); expandedRuntime = null; refreshInstalled() },
                                                        label = { Text("${rt.name} ${rt.version}") }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AiSectionCard("모델 추가", "Hugging Face에서 검색하거나 기기의 파일을 가져옵니다.") {
                            OutlinedTextField(
                                value = repoQuery, onValueChange = { repoQuery = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text("검색어 또는 repo ID (예: litert-community/...)") }
                            )
                            OutlinedTextField(
                                value = hfToken,
                                onValueChange = { hfToken = it; SecureApiKeyStore.put(context, "huggingface", it.trim()) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                label = { Text("Hugging Face 토큰 (gated .litertlm용, 선택)") }
                            )
                            Button(onClick = { searchOrListRepo() }, enabled = !searching && repoQuery.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Text(if (searching) "조회 중..." else "검색 / 조회")
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { modelPicker.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*")) }, modifier = Modifier.weight(1f)) { Text("모델 파일 가져오기") }
                                OutlinedButton(onClick = { runtimePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, modifier = Modifier.weight(1f)) { Text("Runtime 추가") }
                            }

                            if (repos.isNotEmpty()) {
                                HorizontalDivider()
                                Text("검색 결과", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                repos.take(10).forEach { repo ->
                                    OutlinedButton(onClick = { pickRepo(repo.id) }, modifier = Modifier.fillMaxWidth()) {
                                        Text("${repo.id} · ${repo.downloads} downloads", maxLines = 1)
                                    }
                                }
                            }

                            selectedRepo?.let { repoId ->
                                HorizontalDivider()
                                Text("선택한 repo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(repoId, fontWeight = FontWeight.Medium)
                            }

                            val recommendedFile = remember(files) { LocalModelHub.recommendedFileName(context, files) }
                            files.forEach { file ->
                                val isRecommended = file.fileName == recommendedFile
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(
                                        if (isRecommended) 2.dp else 1.dp,
                                        if (isRecommended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .4f)
                                    )
                                ) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            if (isRecommended) Text("★ 이 기기 추천", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            Text(file.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2)
                                            AiHint("${LocalModelHub.kindOf(file.fileName)?.label ?: "?"} · ${if (file.sizeBytes > 0) aiFormatBytes(file.sizeBytes) else "크기 미상"}")
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        FilledTonalButton(onClick = { startDownload(file) }, enabled = !downloading) { Text("받기") }
                                    }
                                }
                            }
                            if (downloading) {
                                LinearProgressIndicator(
                                    progress = { if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                AiHint("${aiFormatBytes(received)} / ${aiFormatBytes(total)}")
                            }
                            HorizontalDivider()
                            AiHint("설치된 runtime: " + runtimes.joinToString { "${it.name} ${it.version}" }.ifBlank { "없음" })
                        }

                        AiAcceleratorSection()
                    }

                    // ════════════════════════ 생성 ════════════════════════
                    AiTab.GENERATION -> {
                        AiSectionCard(
                            "추론 모드",
                            when (thinkingMode) {
                                LocalAiTranslationRuntime.THINKING_OFF -> "Thinking을 끄고 번역 결과만 생성합니다. Qwen 3.5에서는 chat template + /no_think + 빈 think 블록 보조가 함께 적용됩니다."
                                LocalAiTranslationRuntime.THINKING_ON -> "모델의 Thinking을 강제로 활성화합니다. GGUF chat template이 enable_thinking을 지원하는 Qwen3/Qwen3.5 계열에 적용됩니다."
                                else -> "GGUF chat template의 기본 Thinking 설정을 사용합니다."
                            }
                        ) {
                            Text("Thinking", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            AiChipRow {
                                FilterChip(selected = thinkingMode == LocalAiTranslationRuntime.THINKING_OFF, onClick = { saveThinking(LocalAiTranslationRuntime.THINKING_OFF) }, label = { Text("OFF · 빠른 번역") })
                                FilterChip(selected = thinkingMode == LocalAiTranslationRuntime.THINKING_AUTO, onClick = { saveThinking(LocalAiTranslationRuntime.THINKING_AUTO) }, label = { Text("자동") })
                                FilterChip(selected = thinkingMode == LocalAiTranslationRuntime.THINKING_ON, onClick = { saveThinking(LocalAiTranslationRuntime.THINKING_ON) }, label = { Text("ON") })
                            }
                        }

                        AiSectionCard("기본 생성 설정") {
                            AiSliderRow(
                                "Temperature", aiFmt(temperature), temperature, 0f..1.5f, 29,
                                onChange = { temperature = it },
                                onFinish = { vm.updatePlayerSettings(context, settings.copy(aiTemperature = temperature)) },
                                hint = "낮을수록 출력이 안정적이고, 높을수록 표현이 다양해질 수 있습니다."
                            )
                            AiSliderRow(
                                "Top P", aiFmt(topP), topP, 0.1f..1f, 17,
                                onChange = { topP = it },
                                onFinish = { vm.updatePlayerSettings(context, settings.copy(aiTopP = topP)) }
                            )
                            AiSliderRow(
                                "Top K", "${topK.roundToInt()}", topK, 1f..100f, 98,
                                onChange = { topK = it },
                                onFinish = { vm.updatePlayerSettings(context, settings.copy(aiTopK = topK.roundToInt())) }
                            )
                            AiSliderRow(
                                "최대 출력 토큰", "${maxTokens.roundToInt()}", maxTokens, 256f..4096f, 15,
                                onChange = { maxTokens = it },
                                onFinish = { vm.updatePlayerSettings(context, settings.copy(aiMaxTokens = maxTokens.roundToInt())) }
                            )
                        }

                        AiSectionCard("번역 문맥 · 미리 번역") {
                            AiSliderRow(
                                "유지할 자막 문맥", "${contextCues.roundToInt()}개", contextCues, 0f..10f, 9,
                                onChange = { contextCues = it },
                                onFinish = { vm.updatePlayerSettings(context, settings.copy(aiContextCues = contextCues.roundToInt())) },
                                hint = "현재 자막을 번역할 때 직전에 나온 자막 몇 개를 문맥으로 함께 참고할지 설정합니다. 0개면 문맥 없이 현재 자막만 번역합니다."
                            )
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("미리 번역 (Prefetch)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    AiHint("현재 자막보다 앞쪽을 미리 번역합니다.")
                                }
                                Switch(checked = settings.aiPrefetchEnabled, onCheckedChange = { vm.updatePlayerSettings(context, settings.copy(aiPrefetchEnabled = it)) })
                            }
                            AiSliderRow(
                                "미리 번역할 Cue 수", "${prefetchAhead.roundToInt()}개", prefetchAhead, 0f..40f, 39,
                                onChange = { prefetchAhead = it },
                                onFinish = { vm.updatePlayerSettings(context, settings.copy(aiPrefetchAhead = prefetchAhead.roundToInt())) },
                                enabled = settings.aiPrefetchEnabled
                            )
                        }

                        Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth().clickable { expandedAdvanced = !expandedAdvanced }, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("고급 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        AiHint("값을 크게 하면 문맥/품질은 좋아질 수 있지만 메모리 사용량과 지연이 증가합니다.")
                                    }
                                    Text(if (expandedAdvanced) "접기" else "열기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                }
                                if (expandedAdvanced) {
                                    HorizontalDivider()
                                    Text("프롬프트 처리 모드", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    AiChipRow {
                                        FilterChip(selected = promptMode == "chat", onClick = { savePromptMode("chat") }, label = { Text("Chat template") })
                                        FilterChip(selected = promptMode == "completion", onClick = { savePromptMode("completion") }, label = { Text("Completions / raw prompt") })
                                    }
                                    AiHint(if (promptMode == "chat") "GPU/NPU runtime에서 모델 GGUF의 chat template을 적용합니다." else "Native runtime에서 chat template을 적용하지 않고 프롬프트 원문을 전달합니다. 모델에 따라 출력 품질이 크게 달라질 수 있습니다.")

                                    AiSliderRow(
                                        "Context size", "${contextSize.roundToInt()} tokens", contextSize, 1024f..16384f, 14,
                                        onChange = { contextSize = it },
                                        onFinish = { vm.updatePlayerSettings(context, settings.copy(aiContextSize = contextSize.roundToInt())) },
                                        hint = "번역에 한 번에 유지할 최대 문맥입니다. 일본어 대화 흐름을 많이 볼수록 유리하지만 모델 메모리 사용량이 증가합니다."
                                    )

                                    Text("Host threads: ${if (settings.aiThreads == 0) "자동" else settings.aiThreads}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    AiChipRow {
                                        listOf(0, 2, 4, 6, 8, 12).forEach { n ->
                                            FilterChip(selected = settings.aiThreads == n, onClick = { vm.updatePlayerSettings(context, settings.copy(aiThreads = n)) }, label = { Text(if (n == 0) "자동" else n.toString()) })
                                        }
                                    }
                                    AiHint("높이면 속도가 빨라질 수 있지만 발열/배터리와 다른 앱의 성능에 영향을 줄 수 있습니다.")

                                    HorizontalDivider()
                                    Text("샘플러", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    AiSliderRow(
                                        "Repetition penalty", aiFmt(repetitionPenalty), repetitionPenalty, 1.0f..1.3f, 29,
                                        onChange = { repetitionPenalty = it },
                                        onFinish = { vm.updatePlayerSettings(context, settings.copy(aiRepetitionPenalty = repetitionPenalty)) }
                                    )
                                    AiSliderRow(
                                        "Repeat last N", "${repeatLastN.roundToInt()}", repeatLastN, 0f..512f, 63,
                                        onChange = { repeatLastN = it },
                                        onFinish = { aiPrefs.edit().putInt("pref_ai_repeat_last_n", repeatLastN.roundToInt()).apply() }
                                    )
                                    AiSliderRow(
                                        "Min-p", aiFmt(minP), minP.coerceIn(0f, 1f), 0f..0.5f, 49,
                                        onChange = { minP = it },
                                        onFinish = { aiPrefs.edit().putFloat("pref_ai_min_p", minP).apply() },
                                        hint = "0이면 비활성화됩니다. 런타임이 min-p sampler를 지원할 때 적용됩니다."
                                    )
                                    AiSliderRow(
                                        "Typical-p", aiFmt(typicalP), typicalP.coerceIn(0.01f, 1f), 0.01f..1f, 98,
                                        onChange = { typicalP = it },
                                        onFinish = { aiPrefs.edit().putFloat("pref_ai_typical_p", typicalP).apply() },
                                        hint = "1.00이면 비활성화됩니다. 런타임에서 지원할 때만 적용됩니다."
                                    )
                                    AiSliderRow(
                                        "Frequency penalty", aiFmt(frequencyPenalty), frequencyPenalty, (-2f)..2f, 39,
                                        onChange = { frequencyPenalty = it },
                                        onFinish = { aiPrefs.edit().putFloat("pref_ai_frequency_penalty", frequencyPenalty).apply() }
                                    )
                                    AiSliderRow(
                                        "Presence penalty", aiFmt(presencePenalty), presencePenalty, (-2f)..2f, 39,
                                        onChange = { presencePenalty = it },
                                        onFinish = { aiPrefs.edit().putFloat("pref_ai_presence_penalty", presencePenalty).apply() }
                                    )
                                    OutlinedTextField(
                                        value = seedText,
                                        onValueChange = {
                                            seedText = it.filter { ch -> ch == '-' || ch.isDigit() }.take(11)
                                            seedText.toIntOrNull()?.let { value -> if (value >= -1) aiPrefs.edit().putInt("pref_ai_seed", value).apply() }
                                        },
                                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                                        label = { Text("Random seed (-1 = default/random)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                    )
                                    AiHint("샘플러 옵션은 GPU/NPU llama.cpp runtime에 전달됩니다.")

                                    HorizontalDivider()
                                    OutlinedButton(
                                        onClick = {
                                            vm.updatePlayerSettings(
                                                context,
                                                settings.copy(
                                                    aiContextSize = 4096,
                                                    aiThreads = 0,
                                                    aiMaxTokens = 512,
                                                    aiTemperature = 0.25f,
                                                    aiTopP = 0.85f,
                                                    aiTopK = 40,
                                                    aiRepetitionPenalty = 1.05f,
                                                    aiContextCues = 6,
                                                    aiPrefetchEnabled = true,
                                                    aiPrefetchAhead = 10
                                                )
                                            )
                                            promptMode = "chat"
                                            minP = 0.0f
                                            typicalP = 1.0f
                                            repeatLastN = 64f
                                            frequencyPenalty = 0.0f
                                            presencePenalty = 0.0f
                                            seedText = "-1"
                                            thinkingMode = LocalAiTranslationRuntime.THINKING_OFF
                                            aiPrefs.edit()
                                                .putString("pref_ai_prompt_mode", "chat")
                                                .putString("pref_ai_thinking_mode", LocalAiTranslationRuntime.THINKING_OFF)
                                                .putFloat("pref_ai_min_p", 0.0f)
                                                .putFloat("pref_ai_typical_p", 1.0f)
                                                .putInt("pref_ai_repeat_last_n", 64)
                                                .putFloat("pref_ai_frequency_penalty", 0.0f)
                                                .putFloat("pref_ai_presence_penalty", 0.0f)
                                                .putInt("pref_ai_seed", -1)
                                                .apply()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("AI 고급 설정을 기본값으로 복원") }
                                }
                            }
                        }
                    }

                    // ════════════════════════ 프롬프트 ════════════════════════
                    AiTab.PROMPT -> {
                        AiSectionCard(
                            "시스템 프롬프트",
                            "로컬 AI와 클라우드 번역에서 사용할 시스템 프롬프트입니다. Gemini/OpenAI/Qwen은 가능한 경우 system/developer 지시로 전달하고, 지원하지 않는 provider는 해당 방식으로 최대한 반영합니다."
                        ) {
                            OutlinedTextField(
                                value = systemPrompt,
                                onValueChange = { systemPrompt = it; aiPrefs.edit().putString("pref_ai_system_prompt", it).apply() },
                                modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 12,
                                label = { Text("시스템 프롬프트") }
                            )
                            OutlinedButton(
                                onClick = { systemPrompt = LocalAiTranslationRuntime.DEFAULT_SYSTEM_PROMPT; aiPrefs.edit().putString("pref_ai_system_prompt", systemPrompt).apply() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("기본 시스템 프롬프트로 복원") }
                        }

                        AiSectionCard("사용자 프롬프트", "{source_text}는 실제 자막으로 치환됩니다.") {
                            OutlinedTextField(
                                value = settings.translationPrompt,
                                onValueChange = { vm.updatePlayerSettings(context, settings.copy(translationPrompt = it)) },
                                modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 14,
                                label = { Text("사용자 프롬프트") }
                            )
                            OutlinedButton(
                                onClick = { vm.updatePlayerSettings(context, settings.copy(translationPrompt = LocalAiTranslationRuntime.DEFAULT_PROMPT)) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("기본 사용자 프롬프트로 복원") }
                        }

                        AiSectionCard("번역 테스트", "위 프롬프트와 현재 엔진으로 한 문장을 번역해 봅니다.") {
                            OutlinedTextField(
                                value = testText, onValueChange = { testText = it },
                                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                                label = { Text("일본어 문장") }, enabled = !testing
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        testing = true
                                        testResult = "번역 중..."
                                        val r = TranslationManager.test(context, settings.translationProvider, testText)
                                        testResult = r.fold({ it }, { err ->
                                            AiRuntimeDiagnostics.record(context, "번역 테스트 (${settings.translationProvider})", err)
                                            latestAiError = AiRuntimeDiagnostics.latest(context)
                                            "테스트 실패: ${err.message.orEmpty()}"
                                        })
                                        testing = false
                                    }
                                },
                                enabled = !testing && (settings.translationProvider != "local" || models.any { it.compatibility == Compatibility.SUPPORTED } || litertModels.any { it.deviceCompatible }),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (testing) "번역 테스트 중..." else "번역 테스트 실행") }
                            testResult?.let { result ->
                                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                                    Text(result, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                                }
                                if (result.startsWith("테스트 실패")) {
                                    AiChipRow {
                                        TextButton(onClick = { latestAiError?.let { clipboard.setText(AnnotatedString(it.asCopyText())) } }) { Text("오류 복사") }
                                        TextButton(onClick = { showAiErrorDetails = true }) { Text("상세 / 해결 방법") }
                                    }
                                }
                            }
                        }
                    }

                    // ════════════════════════ 도구 ════════════════════════
                    AiTab.TOOLS -> {
                        AiSectionCard("파일 번역", "자막 파일(srt 등)을 골라 번역본을 다운로드 폴더의 LilacAnime에 저장합니다.") {
                            Button(
                                onClick = { translationPicker.launch(arrayOf("text/*", "application/*", "*/*")) },
                                enabled = !translationImportWorking, modifier = Modifier.fillMaxWidth()
                            ) { Text(if (translationImportWorking) "번역 중..." else "자막 파일을 넣어 번역본 다운로드") }
                            translationImportMessage?.let { AiHint(it) }
                        }

                        AiSectionCard("TMDB 제목 매칭", "자막 검색에서 일본어/영문 제목을 한국어 제목으로 보정할 때 사용합니다.") {
                            OutlinedTextField(
                                value = tmdbKeyText, onValueChange = { tmdbKeyText = it; tmdbMessage = null },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                label = { Text("TMDB API Key") }
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { TmdbTitleResolver.setApiKey(context, tmdbKeyText); tmdbMessage = "저장됨" }, modifier = Modifier.weight(1f)) { Text("키 저장") }
                                OutlinedButton(
                                    onClick = { scope.launch { tmdbMessage = "테스트 중..."; tmdbMessage = TmdbTitleResolver.test(context).fold({ it }, { "테스트 실패: ${it.message.orEmpty()}" }) } },
                                    modifier = Modifier.weight(1f)
                                ) { Text("연결 테스트") }
                            }
                            tmdbMessage?.let { AiHint(it) }
                        }

                        AiSectionCard("번역 캐시", "저장된 번역 결과를 모두 지웁니다. 다음 재생 때 다시 번역됩니다.") {
                            OutlinedButton(
                                onClick = { TranslationCache.clear(context); cacheMessage = "번역 캐시를 삭제했습니다." },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("번역 캐시 모두 삭제", color = MaterialTheme.colorScheme.error) }
                            cacheMessage?.let { AiHint(it) }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ───────────────────────── 다이얼로그 ─────────────────────────
    confirmDeleteModel?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDeleteModel = null },
            title = { Text("다운로드한 모델 삭제") },
            text = { Text("${target.fileName}\n${aiFormatBytes(target.sizeBytes)}\n\n모델 파일을 기기에서 영구 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = LocalAiModelManager.delete(context, target)
                    if (deleted) {
                        if (settings.translationModelId == target.id) {
                            vm.updatePlayerSettings(context, settings.copy(translationModelId = null))
                            aiPrefs.edit().remove("pref_translation_model_id").apply()
                        }
                        message = "모델을 삭제했습니다."
                    } else message = "모델 파일을 삭제하지 못했습니다."
                    confirmDeleteModel = null
                    refreshInstalled()
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteModel = null }) { Text("취소") } }
        )
    }
    confirmDeleteLiteRt?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDeleteLiteRt = null },
            title = { Text("다운로드한 모델 삭제") },
            text = { Text("${File(target.path).name}\n${aiFormatBytes(target.sizeBytes)}\n\n모델 파일을 기기에서 영구 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = LiteRtModelStore.delete(target)
                    if (deleted) {
                        if (settings.translationModelId == target.id) {
                            vm.updatePlayerSettings(context, settings.copy(translationModelId = null))
                            aiPrefs.edit().remove("pref_translation_model_id").apply()
                        }
                        message = "모델을 삭제했습니다."
                    } else message = "모델 파일을 삭제하지 못했습니다."
                    confirmDeleteLiteRt = null
                    refreshInstalled()
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteLiteRt = null }) { Text("취소") } }
        )
    }
    if (showAiErrorDetails) {
        val err = latestAiError
        if (err != null) AlertDialog(
            onDismissRequest = { showAiErrorDetails = false },
            title = { Text("AI / Runtime 오류 상세") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${err.time} · ${err.stage}", fontSize = 12.sp)
                    Text("오류: ${err.summary}", fontWeight = FontWeight.Bold)
                    Text("해결 방법: ${err.advice}")
                    Text("Stack trace", fontWeight = FontWeight.Bold)
                    Text(err.details, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(err.asCopyText())) }) { Text("전체 복사") } },
            dismissButton = { TextButton(onClick = { showAiErrorDetails = false }) { Text("닫기") } }
        )
    }

    keyProvider?.let { provider ->
        val label=keyProviders.firstOrNull{it.first==provider}?.second?:provider
        Dialog(onDismissRequest={keyProvider=null},properties=DialogProperties(usePlatformDefaultWidth=false)){
            val view=LocalView.current
            DisposableEffect(Unit){val window=(view.context as? Activity)?.window;window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);onDispose{window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}}
            Surface(shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth().padding(24.dp)){Column(Modifier.padding(20.dp)){Text("$label API Key",fontSize=20.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));Text("키는 기기 내부 Keystore로 암호화되어 저장됩니다.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.65f));Spacer(Modifier.height(12.dp));OutlinedTextField(value=keyText,onValueChange={keyText=it},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),label={Text("API Key")});keyMessage?.let{Text(it,fontSize=11.sp,modifier=Modifier.padding(top=8.dp))};Spacer(Modifier.height(12.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={SecureApiKeyStore.put(context,provider,keyText);keyMessage="저장됨"},modifier=Modifier.weight(1f)){Text("저장")};OutlinedButton(onClick={SecureApiKeyStore.put(context,provider,keyText);keyMessage="테스트 중...";scope.launch{val r=TranslationManager.testConnection(context,provider);keyMessage=r.fold({"테스트 성공: $it"},{err->AiRuntimeDiagnostics.record(context, "${provider} API 키 테스트", err); latestAiError=AiRuntimeDiagnostics.latest(context); "테스트 실패: ${err.message?:"오류"}"})}},modifier=Modifier.weight(1f)){Text("키 테스트")}};TextButton(onClick={SecureApiKeyStore.remove(context,provider);keyText="";keyMessage="삭제됨"},modifier=Modifier.fillMaxWidth()){Text("키 삭제")};TextButton(onClick={keyProvider=null},modifier=Modifier.fillMaxWidth()){Text("닫기")}}}
        }
    }
}
