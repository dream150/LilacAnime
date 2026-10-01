package com.lilac.anime.data.subtitle.translation.localai

import android.content.Context
import android.os.Build

object RuntimeRegistry {
    const val AUTO = "auto"
    const val LLAMA_ANDROID_0_1_1 = "llama-android-0.1.1"

    data class Candidate(
        val id: String,
        val name: String,
        val version: String,
        val kind: Kind,
        val pack: RuntimePack? = null
    )

    enum class Kind { BUILTIN_LLAMA, NATIVE_PACK }

    fun isQualcommDevice(): Boolean {
        val hardware = Build.HARDWARE.orEmpty().lowercase()
        val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.orEmpty().lowercase() else ""
        return manufacturer.contains("qualcomm") || manufacturer.contains("qcom") || hardware.contains("qcom") || hardware.contains("qualcomm")
    }

    fun all(context: Context): List<Candidate> = buildList {
        // On Qualcomm devices the Snapdragon pack is preferred in AUTO. The
        // bundled llama-android runtime remains the universal CPU fallback.
        if (isQualcommDevice()) {
            LocalAiRuntimeManager.listInstalled(context).forEach { pack ->
                add(Candidate(pack.id, pack.name, pack.version, Kind.NATIVE_PACK, pack))
            }
        }
        add(Candidate(LLAMA_ANDROID_0_1_1, "llama.cpp Android", "0.1.1", Kind.BUILTIN_LLAMA))
    }

    fun compatible(context: Context, model: LocalAiModel): List<Candidate> = all(context).filter { candidate ->
        when (candidate.kind) {
            Kind.BUILTIN_LLAMA -> !LocalAiRuntimeManager.requiresSpecialRuntime(model.architecture, model.quantization)
            Kind.NATIVE_PACK -> candidate.pack?.let { pack ->
                val archOk = pack.supportedArchitectures.isEmpty() || pack.supportedArchitectures.any { it.equals(model.architecture.orEmpty(), true) }
                val quantParts = model.quantization.orEmpty().split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() }
                val quantOk = pack.supportedQuantizations.isEmpty() || quantParts.all { pack.supportedQuantizations.contains(it) }
                archOk && quantOk
            } ?: false
        }
    }

    fun preferenceKey(modelId: String): String = "pref_translation_runtime_${modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")}"

    fun selectedId(context: Context, model: LocalAiModel): String =
        context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            .getString(preferenceKey(model.id), AUTO) ?: AUTO

    fun setSelectedId(context: Context, model: LocalAiModel, runtimeId: String) {
        context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            .edit().putString(preferenceKey(model.id), runtimeId).apply()
    }

    fun select(context: Context, model: LocalAiModel): Candidate? {
        val candidates = compatible(context, model)
        if (candidates.isEmpty()) return null
        val preferred = selectedId(context, model)
        if (preferred != AUTO) candidates.firstOrNull { it.id == preferred }?.let { return it }
        return candidates.firstOrNull { it.kind == Kind.NATIVE_PACK } ?: candidates.firstOrNull { it.kind == Kind.BUILTIN_LLAMA }
    }
}
