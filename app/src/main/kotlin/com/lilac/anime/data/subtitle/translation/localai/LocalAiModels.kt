package com.lilac.anime.data.subtitle.translation.localai

import org.json.JSONObject

private const val MB = 1024L * 1024L
private const val GB = 1024L * MB

data class LocalAiModel(
    val id: String,
    val repoId: String,
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val architecture: String?,
    val quantization: String?,
    val chatTemplate: String?,
    val localPath: String,
    val runtimeId: String?,
    val compatibility: Compatibility = Compatibility.UNKNOWN
) {
    val profile: LocalAiModelProfile
        get() = LocalAiModelProfiles.resolve(this)

    val sizeLabel: String
        get() = when {
            sizeBytes >= GB -> String.format(java.util.Locale.US, "%.2f GB", sizeBytes / GB.toDouble())
            sizeBytes >= MB -> String.format(java.util.Locale.US, "%.1f MB", sizeBytes / MB.toDouble())
            else -> "${sizeBytes / 1024L} KB"
        }
}

data class HuggingFaceRepo(
    val id: String,
    val downloads: Long,
    val likes: Long
)

data class HuggingFaceModelFile(
    val repoId: String,
    val fileName: String,
    val sizeBytes: Long
)

data class GgufInspection(
    val valid: Boolean,
    val version: Long = 0,
    val architecture: String? = null,
    val quantization: String? = null,
    val chatTemplate: String? = null,
    val tensorTypes: Set<String> = emptySet(),
    val tensorCount: Long = 0,
    val metadataCount: Long = 0,
    val error: String? = null
)

enum class Compatibility {
    SUPPORTED,
    RUNTIME_REQUIRED,
    UNSUPPORTED,
    UNKNOWN
}

data class RuntimePack(
    val id: String,
    val name: String,
    val version: String,
    val abi: String,
    val jniContract: String,
    val libraryFile: String,
    val supportedArchitectures: Set<String>,
    val supportedQuantizations: Set<String>,
    val nativeLibraries: List<String> = emptyList(),
    val backendOrder: List<String> = listOf("npu", "gpu"),
    val directory: String,
    val installed: Boolean = true
) {
    companion object {
        fun fromJson(json: JSONObject, directory: String): RuntimePack = RuntimePack(
            id = json.getString("id"),
            name = json.optString("name", json.getString("id")),
            version = json.optString("version", "1"),
            abi = json.optString("abi", "arm64-v8a"),
            jniContract = json.optString("jniContract", "lilac-local-ai-v3"),
            libraryFile = json.getString("libraryFile"),
            supportedArchitectures = json.optJSONArray("supportedArchitectures")?.let { array ->
                buildSet { for (i in 0 until array.length()) add(array.optString(i).lowercase()) }
            } ?: emptySet(),
            supportedQuantizations = json.optJSONArray("supportedQuantizations")?.let { array ->
                buildSet { for (i in 0 until array.length()) add(array.optString(i).uppercase()) }
            } ?: emptySet(),
            nativeLibraries = json.optJSONArray("nativeLibraries")?.let { array ->
                buildList { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
            } ?: emptyList(),
            backendOrder = json.optJSONArray("backendOrder")?.let { array ->
                buildList { for (i in 0 until array.length()) array.optString(i).trim().lowercase().takeIf { it.isNotBlank() }?.let(::add) }
            }?.ifEmpty { listOf("npu", "gpu") } ?: listOf("npu", "gpu"),
            directory = directory
        )
    }
}
