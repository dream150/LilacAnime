package com.lilac.anime.ui.settings

import android.content.Context
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores the latest AI/runtime failure so it can be inspected after leaving playback. */
data class AiRuntimeError(
    val time: String,
    val stage: String,
    val summary: String,
    val details: String,
    val advice: String
) {
    fun asCopyText(): String = buildString {
        appendLine("LilacAnime AI/Runtime 오류")
        appendLine("시간: $time")
        appendLine("단계: $stage")
        appendLine("오류: $summary")
        appendLine("해결 방법: $advice")
        appendLine("상세 정보:")
        append(details)
    }
}

object AiRuntimeDiagnostics {
    private const val PREFS = "lilac_ai_runtime_diagnostics"
    private const val TAG = "LilacAiDiagnostics"

    fun record(context: Context, stage: String, error: Throwable) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val summary = redact(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name).take(1200)
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        val details = redact(writer.toString()).take(24000)
        val advice = adviceFor("$stage $summary $details")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        prefs.edit()
            .putString("time", time)
            .putString("stage", stage)
            .putString("summary", summary)
            .putString("details", details)
            .putString("advice", advice)
            .apply()
        Log.e(TAG, "AI_RUNTIME_ERROR stage=$stage summary=$summary advice=$advice", error)
    }

    fun latest(context: Context): AiRuntimeError? {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val summary = p.getString("summary", null)?.takeIf { it.isNotBlank() } ?: return null
        return AiRuntimeError(
            time = p.getString("time", "시간 미상") ?: "시간 미상",
            stage = p.getString("stage", "AI/runtime") ?: "AI/runtime",
            summary = summary,
            details = p.getString("details", summary) ?: summary,
            advice = p.getString("advice", adviceFor(summary)) ?: adviceFor(summary)
        )
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun adviceFor(raw: String): String {
        val s = raw.lowercase(Locale.ROOT)
        return when {
            listOf("runtime이 설치", "호환되지 않습니다", "no compatible runtime", "runtime pack").any(s::contains) ->
                "AI 설정에서 선택한 GGUF 모델과 호환되는 Runtime이 설치되어 있는지 확인하세요. Runtime 추가로 올바른 ZIP을 설치한 뒤 모델별 Runtime 선택을 자동으로 바꾸고 다시 테스트하세요."
            listOf("초기화 실패", "load_model", "model load", "failed to load", "gguf", "invalid model").any(s::contains) ->
                "모델 파일이 완전히 다운로드되었는지, GGUF 형식과 모델 아키텍처가 Runtime에서 지원되는지 확인하세요. 저장 공간을 확인하고 호환 Runtime을 선택한 뒤 다시 실행하세요."
            listOf("out of memory", "oom", "bad_alloc", "메모리", "alloc failed", "mmap").any(s::contains) ->
                "다른 앱을 종료하고 AI 설정에서 컨텍스트 크기와 최대 생성 토큰을 낮추세요. 더 작은 양자화 모델을 사용하거나 CPU/GPU/NPU Runtime을 바꿔 재시험하세요."
            listOf("hexagon", "htp", "qnn", "npu", "backend", "delegate", "opencl", "vulkan", "unsupported device").any(s::contains) ->
                "선택된 GPU/NPU 백엔드가 기기와 Runtime 빌드에 맞는지 확인하세요. 우선 Runtime 자동 선택 또는 CPU 지원 Runtime으로 테스트하고, 같은 오류가 나면 전체 logcat을 함께 보내세요."
            listOf("http 401", "unauthorized", "invalid api key", "api key not valid").any(s::contains) ->
                "AI 설정에서 해당 제공자의 API 키를 다시 확인하고 저장한 뒤 키 테스트를 실행하세요. 키가 노출된 것으로 의심되면 제공자 페이지에서 폐기하고 새 키를 발급하세요."
            listOf("http 403", "forbidden", "permission denied", "access denied").any(s::contains) ->
                "모델/서비스 접근 권한, API 프로젝트 설정, 지역 제한 및 다운로드 주소 권한을 확인하세요. 공개 모델이라도 제공자 정책에 따라 접근 승인이 필요할 수 있습니다."
            listOf("http 404", "not found", "model not found", "unknown model").any(s::contains) ->
                "AI 설정에서 제공자와 모델 ID가 정확한지 확인하고 현재 제공되는 모델을 다시 조회해 선택하세요. API 주소 또는 모델 이름이 변경됐을 가능성도 있습니다."
            listOf("http 429", "rate limit", "quota", "resource_exhausted").any(s::contains) ->
                "요청 한도 또는 사용량 할당량을 확인하고 잠시 후 다시 시도하세요. 모델/계정별 제한을 확인하고 요청 빈도를 낮추세요."
            listOf("unknownhost", "unable to resolve host", "connection timed out", "timeout", "network is unreachable", "failed to connect").any(s::contains) ->
                "네트워크 연결과 DNS/VPN/프록시 설정을 확인하세요. 클라우드 번역은 인터넷 연결이 필요합니다. 로컬 Runtime 오류라면 네트워크가 아닌 모델 파일과 Runtime 초기화 로그를 확인하세요."
            listOf("token", "context length", "maximum context", "too many tokens", "prompt is too long").any(s::contains) ->
                "AI 설정에서 컨텍스트 크기나 최대 토큰을 조정하고, 프롬프트 및 문맥 자막 수를 줄여 다시 테스트하세요. 선택한 모델의 컨텍스트 한도도 확인하세요."
            listOf("native", "jni", "unsatisfiedlinkerror", "dlopen failed", "cannot locate symbol", "undefined symbol", "symbol not found").any(s::contains) ->
                "설치된 Runtime ZIP의 ABI/기기 아키텍처와 네이티브 라이브러리 구성이 맞는지 확인하세요. Runtime을 다시 설치해 테스트하고, 오류가 계속되면 상세 정보와 전체 logcat을 함께 보내세요."
            listOf("json", "parse", "malformed", "empty response", "번역 결과가 비어").any(s::contains) ->
                "모델/제공자의 응답 형식이 예상과 다른 경우일 수 있습니다. 다른 모델 또는 chat/completion 프롬프트 모드를 시험하고, 입력 자막을 짧게 줄여 재현 여부를 확인하세요."
            else ->
                "먼저 AI 설정의 모델 파일, 선택된 Runtime, 제공자/모델 설정을 확인하고 번역 테스트를 다시 실행하세요. 같은 오류가 반복되면 이 화면에서 상세 정보를 복사하고 설정의 전체 로그 추출 기능으로 logcat도 함께 보내세요."
        }
    }

    private fun redact(value: String): String = value
        .replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+"), "\$1[REDACTED]")
        .replace(Regex("(?i)(api[_ -]?key\\s*[:=]\\s*)[^\\s,;]+"), "\$1[REDACTED]")
        .replace(Regex("(?i)(AIza)[A-Za-z0-9_-]{20,}"), "[REDACTED_API_KEY]")
}
