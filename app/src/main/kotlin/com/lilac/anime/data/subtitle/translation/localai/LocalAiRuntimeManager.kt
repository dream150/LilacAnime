package com.lilac.anime.data.subtitle.translation.localai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** Installs and discovers model-agnostic GPU/NPU llama.cpp runtime packs. */
object LocalAiRuntimeManager {
    private const val TAG = "LocalAiRuntime"
    private const val ROOT = "local_ai"
    private const val RUNTIMES = "runtimes"
    private const val CONTRACT = "lilac-local-ai-v3"

    fun runtimeRoot(context: Context) = File(context.filesDir, "$ROOT/$RUNTIMES").apply { mkdirs() }

    fun listInstalled(context: Context): List<RuntimePack> = runtimeRoot(context).listFiles()
        ?.mapNotNull { dir ->
            val manifest = File(dir, "runtime.json")
            if (!manifest.isFile) return@mapNotNull null
            runCatching { RuntimePack.fromJson(JSONObject(manifest.readText()), dir.absolutePath) }.getOrNull()
        }
        ?.filter { it.abi == "arm64-v8a" && it.jniContract == CONTRACT }
        ?.sortedBy { it.name.lowercase() }
        ?: emptyList()

    fun findForModel(context: Context, inspection: GgufInspection): RuntimePack? =
        listInstalled(context).firstOrNull { runtime ->
            val archOk = runtime.supportedArchitectures.isEmpty() ||
                runtime.supportedArchitectures.any { it.equals(inspection.architecture.orEmpty(), true) }
            val quantOk = runtime.supportedQuantizations.isEmpty() ||
                inspection.tensorTypes.all { type -> runtime.supportedQuantizations.contains(type.uppercase()) }
            archOk && quantOk
        }

    suspend fun installZip(context: Context, zipFile: File): RuntimePack = withContext(Dispatchers.IO) {
        val staging = File(runtimeRoot(context), ".staging-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith("/") || name.contains("../")) throw IOException("잘못된 runtime pack 경로입니다.")
                    val relative = name.substringAfter('/')
                    if (relative.isBlank()) continue
                    val out = File(staging, relative)
                    if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        throw IOException("잘못된 runtime pack 경로입니다.")
                    }
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }

            val manifest = File(staging, "runtime.json")
            if (!manifest.isFile) throw IOException("runtime.json이 없습니다.")
            val pack = RuntimePack.fromJson(JSONObject(manifest.readText()), staging.absolutePath)
            if (pack.abi != "arm64-v8a") throw IOException("이 기기용 arm64-v8a runtime이 아닙니다.")
            if (pack.jniContract != CONTRACT) throw IOException("지원하지 않는 runtime contract입니다: ${pack.jniContract}")
            if (!File(staging, pack.libraryFile).isFile) throw IOException("runtime JNI bridge가 없습니다: ${pack.libraryFile}")

            val finalDir = File(runtimeRoot(context), pack.id).apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            staging.copyRecursively(finalDir, overwrite = true)
            // Files extracted from a ZIP are normally created as writable (0644/0755).
            // Android warns when native libraries are loaded directly from an app-private
            // writable directory. Native binaries only need read/execute permission.
            normalizeRuntimePermissions(finalDir)
            staging.deleteRecursively()
            Log.i(TAG, "RUNTIME_INSTALLED id=${pack.id} version=${pack.version}")
            RuntimePack.fromJson(JSONObject(File(finalDir, "runtime.json").readText()), finalDir.absolutePath)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    /**
     * Make native runtime payloads read/execute-only. This is also called by the
     * native loader for runtimes installed by an older app version.
     */
    fun normalizeRuntimePermissions(directory: File) {
        if (!directory.isDirectory) return
        directory.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val native = file.extension.equals("so", true) ||
                    file.name == "llama-server"
                if (native) {
                    // 0555: owner/group/other can read and execute, nobody can write.
                    runCatching {
                        file.setReadable(true, false)
                        file.setWritable(false, false)
                        file.setExecutable(true, false)
                    }.onFailure {
                        Log.w(TAG, "RUNTIME_PERMISSION_FIX_FAILED path=${file.absolutePath}", it)
                    }
                }
            }
    }
}
