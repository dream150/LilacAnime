package com.lilac.anime.ui.settings

import com.lilac.anime.ui.navigation.AppScaffold

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import com.lilac.anime.data.subtitle.translation.localai.*
import com.lilac.anime.data.subtitle.translation.providers.LocalAiTranslationRuntime
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

@Composable
fun AiSettingsScreen(vm: AnimeViewModel, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = vm.playerSettings
    var models by remember { mutableStateOf<List<LocalAiModel>>(emptyList()) }
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
    var testing by remember { mutableStateOf(false) }
    var keyProvider by remember { mutableStateOf<String?>(null) }
    var keyText by remember { mutableStateOf("") }
    var keyMessage by remember { mutableStateOf<String?>(null) }
    val aiPrefs = remember { context.getSharedPreferences("lilac_offline_store", android.content.Context.MODE_PRIVATE) }
    val keyProviders = listOf("gemini" to "Gemini", "openai" to "OpenAI", "deepl" to "DeepL", "qwen" to "Qwen")
    var geminiModel by remember { mutableStateOf(aiPrefs.getString("pref_gemini_model", "gemini-3.5-flash-lite") ?: "gemini-3.5-flash-lite") }
    var openAiModel by remember { mutableStateOf(aiPrefs.getString("pref_openai_model", "gpt-4.1-mini") ?: "gpt-4.1-mini") }
    var qwenModel by remember { mutableStateOf(aiPrefs.getString("pref_qwen_model", "qwen-plus") ?: "qwen-plus") }
    var qwenRegion by remember { mutableStateOf(aiPrefs.getString("pref_qwen_region", "international") ?: "international") }
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
            withContext(Dispatchers.Main.immediate) { models = m; runtimes = r; loadingInstalled = false }
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
                }.onFailure { translationImportMessage = "번역 실패: ${it.message.orEmpty()}" }
                translationImportWorking = false
            }
        }
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = "모델 파일 가져오는 중..."
            runCatching { LocalAiModelManager.importFile(context, uri) }
                .onSuccess {
                    refreshInstalled()
                    message = "모델을 추가했습니다: ${it.displayName}"
                }
                .onFailure { message = "모델 추가 실패: ${it.message.orEmpty()}" }
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
                .onFailure { message = "runtime pack 설치 실패: ${it.message.orEmpty()}" }
        }
    }

    AppScaffold(selected = "settings", onSelect = onNavigate) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("AI 설정", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onNavigate("settings") }) { Text("설정으로") }
            }
            Spacer(Modifier.height(8.dp))
            Text("자막 번역 모델, 성능, 문맥, 프롬프트를 별도로 관리합니다.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha=.65f))
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text("번역 엔진", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("local" to "로컬 AI", "gemini" to "Gemini", "openai" to "OpenAI", "deepl" to "DeepL", "qwen" to "Qwen").forEach { (id,label) ->
                    FilterChip(selected = settings.translationProvider == id, onClick = { vm.updatePlayerSettings(context, settings.copy(translationProvider=id)) }, label={Text(label)})
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("현재 선택: ${settings.translationProvider}", fontSize=11.sp, color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))
            if (settings.translationProvider != "local") {
                Text("클라우드 API는 네트워크와 API 사용량이 필요합니다. 재생 중 번역은 미리 자막을 읽어 한 cue씩 요청하므로 로컬 모델보다 지연될 수 있습니다.", fontSize=11.sp, color=MaterialTheme.colorScheme.onBackground.copy(alpha=.65f))
            }

            Spacer(Modifier.height(20.dp))
            Text("설치된 로컬 모델", fontSize=16.sp, fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (loadingInstalled) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("모델 정보를 읽는 중...", fontSize=11.sp)
            } else if (models.isEmpty()) {
                Text("설치된 GGUF 모델이 없습니다.", fontSize=12.sp, color=MaterialTheme.colorScheme.onBackground.copy(alpha=.65f))
            } else {
                models.forEach { model ->
                    val candidates = RuntimeRegistry.compatible(context, model)
                    val selectedId = RuntimeRegistry.selectedId(context, model)
                    val runtimeName = if (selectedId == RuntimeRegistry.AUTO) "자동 선택" else candidates.firstOrNull { it.id == selectedId }?.let { "${it.name} ${it.version}" } ?: "자동 선택"
                    OutlinedButton(onClick={ if(candidates.isNotEmpty()) vm.updatePlayerSettings(context, settings.copy(translationProvider="local", translationModelId=model.id)) }, enabled=candidates.isNotEmpty(), modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(if(settings.translationModelId==model.id) "✓ ${model.displayName}" else model.displayName)
                            Text("${model.sizeLabel} · ${model.quantization ?: "?"} · ${model.architecture ?: "?"}", fontSize=10.sp)
                            val adapter = LocalAiAdapterRegistry.resolve(model)
                            val profile = model.profile
                            val featureNames = buildList {
                                if (profile.supports(LocalAiFeature.THINKING_CONTROL)) add("Thinking")
                                if (profile.supports(LocalAiFeature.VISION)) add("Vision")
                                if (profile.supports(LocalAiFeature.TOOL_CALLING)) add("Tool")
                            }
                            Text("${profile.displayName} · ${adapter.displayName} · ${adapter.id}", fontSize=10.sp, color=MaterialTheme.colorScheme.primary)
                            if (featureNames.isNotEmpty()) {
                                Text("기능: ${featureNames.joinToString(" · ")}", fontSize=10.sp, color=MaterialTheme.colorScheme.onBackground.copy(alpha=.65f))
                            }
                            Text(if(candidates.isEmpty()) "호환 runtime 없음" else "Runtime: $runtimeName", fontSize=10.sp)
                        }
                    }
                    if (candidates.isNotEmpty()) {
                        TextButton(onClick={expandedRuntime=if(expandedRuntime==model.id)null else model.id}, modifier=Modifier.fillMaxWidth()) { Text("Runtime 선택 · $runtimeName") }
                        if(expandedRuntime==model.id) {
                            OutlinedButton(onClick={RuntimeRegistry.setSelectedId(context,model,RuntimeRegistry.AUTO); expandedRuntime=null; refreshInstalled()}, modifier=Modifier.fillMaxWidth()){Text(if(selectedId==RuntimeRegistry.AUTO)"✓ 자동 선택" else "자동 선택")}
                            candidates.forEach { rt -> OutlinedButton(onClick={RuntimeRegistry.setSelectedId(context,model,rt.id);expandedRuntime=null;refreshInstalled()}, modifier=Modifier.fillMaxWidth()){Text(if(selectedId==rt.id)"✓ ${rt.name} ${rt.version}" else "${rt.name} ${rt.version}")}}
                        }
                    }
                    TextButton(onClick={confirmDeleteModel=model}, modifier=Modifier.fillMaxWidth()) { Text("모델 파일 삭제", color=MaterialTheme.colorScheme.error) }
                }
            }
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
                        }) { Text("삭제", color=MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick={confirmDeleteModel=null}) { Text("취소") } }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Hugging Face 모델 추가", fontSize=14.sp, fontWeight=FontWeight.Medium)
            OutlinedTextField(value=repoQuery,onValueChange={repoQuery=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("검색어 또는 repo ID")})
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={scope.launch { searching=true; runCatching { if(repoQuery.contains("/")){selectedRepo=repoQuery.trim(); LocalAiModelManager.listRepoFiles(selectedRepo!!)} else {repos=LocalAiModelManager.searchRepos(repoQuery);emptyList()} }.onSuccess{files=it}.onFailure{message="Hugging Face 조회 실패: ${it.message.orEmpty()}"};searching=false}},enabled=!searching&&repoQuery.isNotBlank(),modifier=Modifier.weight(1f)){Text(if(searching)"조회 중..." else "검색 / 조회")}
                OutlinedButton(onClick={modelPicker.launch(arrayOf("application/octet-stream","application/x-gguf","*/*"))},modifier=Modifier.weight(1f)){Text("모델 파일 추가")}
                OutlinedButton(onClick={runtimePicker.launch(arrayOf("application/zip","application/octet-stream","*/*"))},modifier=Modifier.weight(1f)){Text("Runtime 추가")}
            }
            repos.take(10).forEach { repo -> OutlinedButton(onClick={scope.launch{selectedRepo=repo.id;runCatching{LocalAiModelManager.listRepoFiles(repo.id)}.onSuccess{files=it}.onFailure{message="파일 목록 조회 실패: ${it.message.orEmpty()}"}}},modifier=Modifier.fillMaxWidth()){Text("${repo.id} · ${repo.downloads} downloads")}}
            selectedRepo?.let{Text("선택한 repo: $it",fontSize=11.sp,fontWeight=FontWeight.Medium)}
            files.forEach { file ->
                OutlinedButton(onClick={if(!downloading)scope.launch{downloading=true;received=0;total=file.sizeBytes;runCatching{LocalAiModelManager.download(context,file){r,t->withContext(Dispatchers.Main.immediate){received=r;total=t}}}.onSuccess{refreshInstalled();message="모델이 설치되었습니다."}.onFailure{message="모델 다운로드 실패: ${it.message.orEmpty()}"};downloading=false}},enabled=!downloading,modifier=Modifier.fillMaxWidth()){Text("${file.fileName} · ${if(file.sizeBytes>0)aiFormatBytes(file.sizeBytes) else "크기 미상"}")}
            }
            if(downloading){LinearProgressIndicator(progress={if(total>0)(received.toFloat()/total).coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth());Text("${aiFormatBytes(received)} / ${aiFormatBytes(total)}",fontSize=10.sp)}
            Text("설치된 runtime: "+runtimes.joinToString { "${it.name} ${it.version}" }.ifBlank{"없음"},fontSize=10.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))
            message?.let{Text(it,fontSize=11.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.75f))}

            Spacer(Modifier.height(22.dp)); HorizontalDivider(); Spacer(Modifier.height(18.dp))

            // Local Dream-inspired: keep the most important inference controls
            // visible as compact Material 3 cards instead of hiding them all
            // behind the advanced section. The underlying LilacAnime/JNI
            // runtime remains unchanged.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = MaterialTheme.shapes.large
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("추론 모드", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (thinkingMode == LocalAiTranslationRuntime.THINKING_OFF)
                            "Thinking을 끄고 번역 결과만 생성합니다. Qwen 3.5에서는 chat template + /no_think + 빈 think 블록 보조가 함께 적용됩니다."
                        else if (thinkingMode == LocalAiTranslationRuntime.THINKING_ON)
                            "모델의 Thinking을 강제로 활성화합니다."
                        else
                            "GGUF chat template의 기본 Thinking 설정을 사용합니다.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Thinking Mode", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (thinkingMode == LocalAiTranslationRuntime.THINKING_OFF) "꺼짐 · 빠른 번역" else "켜짐/자동",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = thinkingMode == LocalAiTranslationRuntime.THINKING_ON,
                            onCheckedChange = { enabled ->
                                thinkingMode = if (enabled) LocalAiTranslationRuntime.THINKING_ON else LocalAiTranslationRuntime.THINKING_OFF
                                aiPrefs.edit().putString("pref_ai_thinking_mode", thinkingMode).apply()
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = thinkingMode == LocalAiTranslationRuntime.THINKING_OFF,
                            onClick = { thinkingMode = LocalAiTranslationRuntime.THINKING_OFF; aiPrefs.edit().putString("pref_ai_thinking_mode", thinkingMode).apply() },
                            label = { Text("OFF") }
                        )
                        FilterChip(
                            selected = thinkingMode == LocalAiTranslationRuntime.THINKING_AUTO,
                            onClick = { thinkingMode = LocalAiTranslationRuntime.THINKING_AUTO; aiPrefs.edit().putString("pref_ai_thinking_mode", thinkingMode).apply() },
                            label = { Text("자동") }
                        )
                        FilterChip(
                            selected = thinkingMode == LocalAiTranslationRuntime.THINKING_ON,
                            onClick = { thinkingMode = LocalAiTranslationRuntime.THINKING_ON; aiPrefs.edit().putString("pref_ai_thinking_mode", thinkingMode).apply() },
                            label = { Text("ON") }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("기본 생성 설정", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text("Temperature  ${String.format(java.util.Locale.US, "%.2f", temperature)}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..1.5f, steps = 29, onValueChangeFinished = { vm.updatePlayerSettings(context, settings.copy(aiTemperature = temperature)) })
                    Text("Top P  ${String.format(java.util.Locale.US, "%.2f", topP)}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Slider(value = topP, onValueChange = { topP = it }, valueRange = 0.1f..1f, steps = 17, onValueChangeFinished = { vm.updatePlayerSettings(context, settings.copy(aiTopP = topP)) })
                    Text("Top K  ${topK.roundToInt()}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Slider(value = topK, onValueChange = { topK = it }, valueRange = 1f..100f, steps = 98, onValueChangeFinished = { vm.updatePlayerSettings(context, settings.copy(aiTopK = topK.roundToInt())) })
                    Text("Max Tokens  ${maxTokens.roundToInt()}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Slider(value = maxTokens, onValueChange = { maxTokens = it }, valueRange = 256f..4096f, steps = 15, onValueChangeFinished = { vm.updatePlayerSettings(context, settings.copy(aiMaxTokens = maxTokens.roundToInt())) })
                }
            }

            Spacer(Modifier.height(22.dp)); HorizontalDivider(); Spacer(Modifier.height(18.dp))
            Text("고급 추론 설정",fontSize=18.sp,fontWeight=FontWeight.Bold)
            Text("값을 크게 하면 문맥/품질은 좋아질 수 있지만 메모리 사용량과 지연이 증가합니다.",fontSize=11.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.65f))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick={expandedAdvanced=!expandedAdvanced},modifier=Modifier.fillMaxWidth()){Text(if(expandedAdvanced)"고급 설정 접기" else "고급 설정 열기")}
            if(expandedAdvanced){
                Text("프롬프트 처리 모드",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=promptMode=="chat", onClick={promptMode="chat"; aiPrefs.edit().putString("pref_ai_prompt_mode", promptMode).apply()}, label={Text("Chat template")})
                    FilterChip(selected=promptMode=="completion", onClick={promptMode="completion"; aiPrefs.edit().putString("pref_ai_prompt_mode", promptMode).apply()}, label={Text("Completions / raw prompt")})
                }
                Text(if(promptMode=="chat") "GPU/NPU runtime에서 모델 GGUF의 chat template을 적용합니다." else "Native runtime에서 chat template을 적용하지 않고 프롬프트 원문을 전달합니다. 모델에 따라 출력 품질이 크게 달라질 수 있습니다.",fontSize=10.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))
                Spacer(Modifier.height(12.dp))
                Text("Thinking / 추론",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected=thinkingMode==LocalAiTranslationRuntime.THINKING_AUTO,
                        onClick={thinkingMode=LocalAiTranslationRuntime.THINKING_AUTO; aiPrefs.edit().putString("pref_ai_thinking_mode", thinkingMode).apply()},
                        label={Text("자동")}
                    )
                    FilterChip(
                        selected=thinkingMode==LocalAiTranslationRuntime.THINKING_ON,
                        onClick={thinkingMode=LocalAiTranslationRuntime.THINKING_ON; aiPrefs.edit().putString("pref_ai_thinking_mode", thinkingMode).apply()},
                        label={Text("Thinking ON")}
                    )
                    FilterChip(
                        selected=thinkingMode==LocalAiTranslationRuntime.THINKING_OFF,
                        onClick={thinkingMode=LocalAiTranslationRuntime.THINKING_OFF; aiPrefs.edit().putString("pref_ai_thinking_mode", thinkingMode).apply()},
                        label={Text("Thinking OFF")}
                    )
                }
                Text(
                    when (thinkingMode) {
                        LocalAiTranslationRuntime.THINKING_ON -> "GGUF chat template이 enable_thinking을 지원하면 강제로 켭니다. Qwen3/Qwen3.5 계열에 적용됩니다."
                        LocalAiTranslationRuntime.THINKING_OFF -> "GGUF chat template이 enable_thinking을 지원하면 강제로 끕니다. 지원하지 않는 모델은 기존 동작을 유지합니다."
                        else -> "모델의 GGUF chat template 기본값을 그대로 사용합니다."
                    },
                    fontSize=10.sp,
                    color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f)
                )
                Spacer(Modifier.height(10.dp))
                Text("Context size: ${contextSize.roundToInt()} tokens",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Slider(value=contextSize,onValueChange={contextSize=it},valueRange=1024f..16384f,steps=14,onValueChangeFinished={vm.updatePlayerSettings(context,settings.copy(aiContextSize=contextSize.roundToInt()))})
                Text("번역에 한 번에 유지할 최대 문맥입니다. 일본어 대화 흐름을 많이 볼수록 유리하지만 모델 메모리 사용량이 증가합니다.",fontSize=10.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))
                Spacer(Modifier.height(10.dp))
                Text("Host threads: ${if(settings.aiThreads==0)"자동" else settings.aiThreads}",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf(0,2,4,6,8,12).forEach{n->FilterChip(selected=settings.aiThreads==n,onClick={vm.updatePlayerSettings(context,settings.copy(aiThreads=n))},label={Text(if(n==0)"자동" else n.toString())})}}
                Text("높이면 속도가 빨라질 수 있지만 발열/배터리와 다른 앱의 성능에 영향을 줄 수 있습니다.",fontSize=10.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))
                Spacer(Modifier.height(10.dp))
                Text("최대 출력 토큰: ${maxTokens.roundToInt()}",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Slider(value=maxTokens,onValueChange={maxTokens=it},valueRange=256f..4096f,steps=15,onValueChangeFinished={vm.updatePlayerSettings(context,settings.copy(aiMaxTokens=maxTokens.roundToInt()))})
                Spacer(Modifier.height(10.dp))
                Text("Temperature: ${String.format(java.util.Locale.US, "%.2f", temperature)}",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Slider(value=temperature,onValueChange={temperature=it},valueRange=0.0f..1.5f,steps=29,onValueChangeFinished={vm.updatePlayerSettings(context,settings.copy(aiTemperature=temperature))})
                Text("낮을수록 출력이 안정적이고, 높을수록 표현이 다양해질 수 있습니다.",fontSize=10.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))
                Spacer(Modifier.height(8.dp))
                Text("Top-p: ${String.format(java.util.Locale.US, "%.2f", topP)}",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Slider(value=topP,onValueChange={topP=it},valueRange=0.1f..1.0f,steps=17,onValueChangeFinished={vm.updatePlayerSettings(context,settings.copy(aiTopP=topP))})
                Spacer(Modifier.height(8.dp))
                Text("Top-k: ${topK.roundToInt()}",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Slider(value=topK,onValueChange={topK=it},valueRange=1f..100f,steps=98,onValueChangeFinished={vm.updatePlayerSettings(context,settings.copy(aiTopK=topK.roundToInt()))})
                Spacer(Modifier.height(8.dp))
                Text("Repetition penalty: ${String.format(java.util.Locale.US, "%.2f", repetitionPenalty)}",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Slider(value=repetitionPenalty,onValueChange={repetitionPenalty=it},valueRange=1.0f..1.3f,steps=29,onValueChangeFinished={vm.updatePlayerSettings(context,settings.copy(aiRepetitionPenalty=repetitionPenalty))})
                Spacer(Modifier.height(8.dp))
                Text("Repeat last N: ${repeatLastN.roundToInt()}",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Slider(value=repeatLastN,onValueChange={repeatLastN=it},valueRange=0f..512f,steps=63,onValueChangeFinished={aiPrefs.edit().putInt("pref_ai_repeat_last_n",repeatLastN.roundToInt()).apply()})
                Spacer(Modifier.height(8.dp))
                Text("Min-p: ${String.format(java.util.Locale.US, "%.2f", minP)}",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Slider(value=minP.coerceIn(0f,1f),onValueChange={minP=it},valueRange=0f..0.5f,steps=49,onValueChangeFinished={aiPrefs.edit().putFloat("pref_ai_min_p",minP).apply()})
                Text("0이면 비활성화됩니다. 런타임이 min-p sampler를 지원할 때 적용됩니다.",fontSize=10.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))
                Spacer(Modifier.height(8.dp))
                Text("Typical-p: ${String.format(java.util.Locale.US, "%.2f", typicalP)}",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Slider(value=typicalP.coerceIn(0.01f,1f),onValueChange={typicalP=it},valueRange=0.01f..1f,steps=98,onValueChangeFinished={aiPrefs.edit().putFloat("pref_ai_typical_p",typicalP).apply()})
                Text("1.00이면 비활성화됩니다. 런타임에서 지원할 때만 적용됩니다.",fontSize=10.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))
                Spacer(Modifier.height(8.dp))
                Text("Frequency penalty: ${String.format(java.util.Locale.US, "%.2f", frequencyPenalty)}",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Slider(value=frequencyPenalty,onValueChange={frequencyPenalty=it},valueRange=(-2f)..2f,steps=39,onValueChangeFinished={aiPrefs.edit().putFloat("pref_ai_frequency_penalty",frequencyPenalty).apply()})
                Spacer(Modifier.height(8.dp))
                Text("Presence penalty: ${String.format(java.util.Locale.US, "%.2f", presencePenalty)}",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Slider(value=presencePenalty,onValueChange={presencePenalty=it},valueRange=(-2f)..2f,steps=39,onValueChangeFinished={aiPrefs.edit().putFloat("pref_ai_presence_penalty",presencePenalty).apply()})
                OutlinedTextField(value=seedText,onValueChange={seedText=it.filter { ch -> ch=='-' || ch.isDigit() }.take(11); seedText.toIntOrNull()?.let { value -> if(value>=-1) aiPrefs.edit().putInt("pref_ai_seed",value).apply() }},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Random seed (-1 = default/random)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                Text("샘플러 옵션은 GPU/NPU llama.cpp runtime에 전달됩니다.",fontSize=10.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))
                Spacer(Modifier.height(10.dp))
                Text("유지할 자막 문맥: ${contextCues.roundToInt()}개",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Slider(value=contextCues,onValueChange={contextCues=it},valueRange=0f..10f,steps=9,onValueChangeFinished={vm.updatePlayerSettings(context,settings.copy(aiContextCues=contextCues.roundToInt()))})
                Text("현재 자막을 번역할 때 직전에 나온 자막 몇 개를 문맥으로 함께 참고할지 설정합니다. 0개면 문맥 없이 현재 자막만 번역합니다.",fontSize=10.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("미리 번역(Prefetch)",fontSize=13.sp,fontWeight=FontWeight.Medium);Text("현재 자막보다 앞쪽을 미리 번역합니다.",fontSize=10.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))};Switch(checked=settings.aiPrefetchEnabled,onCheckedChange={vm.updatePlayerSettings(context,settings.copy(aiPrefetchEnabled=it))})}
                Text("미리 번역할 Cue 수: ${prefetchAhead.roundToInt()}",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Slider(value=prefetchAhead,onValueChange={prefetchAhead=it},valueRange=0f..40f,steps=39,onValueChangeFinished={vm.updatePlayerSettings(context,settings.copy(aiPrefetchAhead=prefetchAhead.roundToInt()))})
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick={
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
                    modifier=Modifier.fillMaxWidth()
                ){Text("AI 고급 설정을 기본값으로 복원")}
                Spacer(Modifier.height(8.dp))

            }

            Spacer(Modifier.height(20.dp)); HorizontalDivider(); Spacer(Modifier.height(18.dp))
            Text("시스템 프롬프트",fontSize=16.sp,fontWeight=FontWeight.Bold)
            Text("모델의 역할과 번역 규칙을 지정합니다. Chat template 모드에서는 system 메시지로 전달되고, completions 모드에서는 입력 프롬프트 앞에 붙습니다.",fontSize=10.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))
            OutlinedTextField(value=systemPrompt,onValueChange={systemPrompt=it;aiPrefs.edit().putString("pref_ai_system_prompt",it).apply()},modifier=Modifier.fillMaxWidth(),minLines=4,maxLines=12,label={Text("시스템 프롬프트")})
            OutlinedButton(onClick={systemPrompt=LocalAiTranslationRuntime.DEFAULT_SYSTEM_PROMPT;aiPrefs.edit().putString("pref_ai_system_prompt",systemPrompt).apply()},modifier=Modifier.fillMaxWidth()){Text("기본 시스템 프롬프트로 복원")}

            Spacer(Modifier.height(12.dp))
            Text("번역 프롬프트",fontSize=16.sp,fontWeight=FontWeight.Bold)
            Text("{source_text}는 실제 자막으로 치환됩니다.",fontSize=10.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.6f))
            OutlinedTextField(value=settings.translationPrompt,onValueChange={vm.updatePlayerSettings(context,settings.copy(translationPrompt=it))},modifier=Modifier.fillMaxWidth(),minLines=6,maxLines=14,label={Text("번역 프롬프트")})
            OutlinedButton(onClick={vm.updatePlayerSettings(context,settings.copy(translationPrompt=LocalAiTranslationRuntime.DEFAULT_PROMPT))},modifier=Modifier.fillMaxWidth()){Text("기본 프롬프트로 복원")}

            Spacer(Modifier.height(18.dp)); Text("번역 테스트",fontSize=16.sp,fontWeight=FontWeight.Bold)
            OutlinedTextField(value=testText,onValueChange={testText=it},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=4,label={Text("일본어 문장")},enabled=!testing)
            OutlinedButton(onClick={scope.launch{testing=true;testResult="번역 중...";val r=TranslationManager.test(context,"local",testText);testResult=r.fold({it},{"테스트 실패: ${it.message.orEmpty()}"});testing=false}},enabled=!testing&&models.any{it.compatibility==Compatibility.SUPPORTED},modifier=Modifier.fillMaxWidth()){Text(if(testing)"번역 테스트 중..." else "번역 테스트 실행")}
            testResult?.let{Text(it,fontSize=12.sp)}

            Spacer(Modifier.height(18.dp)); Text("클라우드 번역 API",fontSize=16.sp,fontWeight=FontWeight.Bold)
            keyProviders.forEach{(id,label)->OutlinedButton(onClick={keyProvider=id;keyText=SecureApiKeyStore.get(context,id).orEmpty();keyMessage=null},modifier=Modifier.fillMaxWidth()){Text("$label API Key ${if(SecureApiKeyStore.has(context,id))"(저장됨)" else "(미설정)"}")}}
            Text("API Key는 Android Keystore로 암호화하여 저장합니다.",fontSize=10.sp,color=MaterialTheme.colorScheme.onBackground.copy(alpha=.55f))
            Text("API 모델 이름", fontSize=14.sp, fontWeight=FontWeight.SemiBold)
            OutlinedTextField(value=geminiModel,onValueChange={geminiModel=it;aiPrefs.edit().putString("pref_gemini_model",it.trim()).apply()},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Gemini 모델")},supportingText={Text("기본값: gemini-3.5-flash-lite")})
            OutlinedTextField(value=openAiModel,onValueChange={openAiModel=it;aiPrefs.edit().putString("pref_openai_model",it.trim()).apply()},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("OpenAI 모델")},supportingText={Text("기본값: gpt-4.1-mini")})
            OutlinedTextField(value=qwenModel,onValueChange={qwenModel=it;aiPrefs.edit().putString("pref_qwen_model",it.trim()).apply()},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Qwen 모델")},supportingText={Text("기본값: qwen-plus")})
            Text("Qwen API 지역", fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=qwenRegion=="international",onClick={qwenRegion="international";aiPrefs.edit().putString("pref_qwen_region",qwenRegion).apply()},label={Text("International / Singapore")})
                FilterChip(selected=qwenRegion=="china",onClick={qwenRegion="china";aiPrefs.edit().putString("pref_qwen_region",qwenRegion).apply()},label={Text("China")})
            }

            Spacer(Modifier.height(18.dp)); Text("파일 번역",fontSize=16.sp,fontWeight=FontWeight.Bold)
            OutlinedButton(onClick={translationPicker.launch(arrayOf("text/*","application/*","*/*"))},enabled=!translationImportWorking,modifier=Modifier.fillMaxWidth()){Text(if(translationImportWorking)"번역 중..." else "자막 파일을 넣어 번역본 다운로드")}
            translationImportMessage?.let{Text(it,fontSize=11.sp)}
            OutlinedButton(onClick={TranslationCache.clear(context)},modifier=Modifier.fillMaxWidth()){Text("번역 캐시 모두 삭제")}
        }
    }

    keyProvider?.let { provider ->
        val label=keyProviders.firstOrNull{it.first==provider}?.second?:provider
        Dialog(onDismissRequest={keyProvider=null},properties=DialogProperties(usePlatformDefaultWidth=false)){
            val view=LocalView.current
            DisposableEffect(Unit){val window=(view.context as? Activity)?.window;window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);onDispose{window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}}
            Surface(shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth().padding(24.dp)){Column(Modifier.padding(20.dp)){Text("$label API Key",fontSize=20.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));Text("키는 기기 내부 Keystore로 암호화되어 저장됩니다.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.65f));Spacer(Modifier.height(12.dp));OutlinedTextField(value=keyText,onValueChange={keyText=it},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),label={Text("API Key")});keyMessage?.let{Text(it,fontSize=11.sp,modifier=Modifier.padding(top=8.dp))};Spacer(Modifier.height(12.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={SecureApiKeyStore.put(context,provider,keyText);keyMessage="저장됨"},modifier=Modifier.weight(1f)){Text("저장")};OutlinedButton(onClick={SecureApiKeyStore.put(context,provider,keyText);keyMessage="테스트 중...";scope.launch{val r=TranslationManager.test(context,provider);keyMessage=r.fold({"테스트 성공: $it"},{"테스트 실패: ${it.message?:"오류"}"})}},modifier=Modifier.weight(1f)){Text("키 테스트")}};TextButton(onClick={SecureApiKeyStore.remove(context,provider);keyText="";keyMessage="삭제됨"},modifier=Modifier.fillMaxWidth()){Text("키 삭제")};TextButton(onClick={keyProvider=null},modifier=Modifier.fillMaxWidth()){Text("닫기")}}}
        }
    }
}
