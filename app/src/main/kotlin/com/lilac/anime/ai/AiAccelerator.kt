package com.lilac.anime.ai

import android.content.Context
import android.os.Build

enum class AiAccelerator(val label: String) {
    NPU("NPU"), GPU("GPU"), CPU("CPU")
}

/** 사용자 설정(pref_ai_accelerator)에 따른 시도 순서. 항상 아래 단계로 폴백한다. */
object AcceleratorPolicy {
    const val PREF_KEY = "pref_ai_accelerator" // auto | npu | gpu | cpu
    private const val PREFS = "lilac_offline_store"

    fun mode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_KEY, "auto") ?: "auto"

    fun order(context: Context): List<AiAccelerator> = when (mode(context)) {
        "gpu" -> listOf(AiAccelerator.GPU, AiAccelerator.CPU)
        "cpu" -> listOf(AiAccelerator.CPU)
        else -> listOf(AiAccelerator.NPU, AiAccelerator.GPU, AiAccelerator.CPU) // auto, npu
    }
}

/**
 * NPU 초기화는 벤더 드라이버 문제로 네이티브 크래시(SIGSEGV)가 날 수 있다.
 * 예외로는 못 잡으므로 "시도 중" 표식을 동기 저장해 두고, 다음 실행에서 표식이 남아 있으면
 * 크래시로 간주한다. 2회 연속이면 해당 (엔진, 모델, 가속기) 조합은 건너뛴다.
 * 키에 Build.FINGERPRINT와 앱 업데이트 시각이 들어가므로 OS/앱 업데이트 후엔 자동으로 재시도한다.
 */
object AcceleratorHealth {
    private const val PREFS = "lilac_ai_accelerator_health"
    private const val MAX_CRASHES = 2
    private const val FAIL_TTL_MS = 7L * 24L * 3600L * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(context: Context, engineId: String, modelId: String, accelerator: AiAccelerator): String {
        val updated = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return listOf(engineId, modelId, accelerator.name, Build.FINGERPRINT, updated)
            .joinToString("|").replace(Regex("\\s+"), "_")
    }

    /** 건너뛰어야 하면 사유를, 시도해도 되면 null을 반환. */
    fun skipReason(context: Context, key: String): String? {
        val p = prefs(context)
        val failedAt = p.getLong("fail_at:$key", 0L)
        if (failedAt > 0L && System.currentTimeMillis() - failedAt < FAIL_TTL_MS) {
            return p.getString("fail_why:$key", null) ?: "이전 시도 실패"
        }
        if (p.getInt("crash:$key", 0) >= MAX_CRASHES) return "초기화 중 프로세스 종료가 반복됨"
        return null
    }

    fun begin(context: Context, key: String) {
        val p = prefs(context)
        val crashes = p.getInt("crash:$key", 0) + if (p.getBoolean("pending:$key", false)) 1 else 0
        p.edit().putBoolean("pending:$key", true).putInt("crash:$key", crashes).commit()
    }

    fun success(context: Context, key: String, elapsedMs: Long) {
        prefs(context).edit()
            .remove("pending:$key").remove("crash:$key")
            .remove("fail_at:$key").remove("fail_why:$key")
            .putLong("ms:$key", elapsedMs)
            .commit()
    }

    fun failure(context: Context, key: String, reason: String) {
        prefs(context).edit()
            .remove("pending:$key").remove("crash:$key")
            .putLong("fail_at:$key", System.currentTimeMillis())
            .putString("fail_why:$key", reason.take(300))
            .commit()
    }

    /** 사용자가 설정에서 "가속기 상태 초기화"를 눌렀을 때. */
    fun reset(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
