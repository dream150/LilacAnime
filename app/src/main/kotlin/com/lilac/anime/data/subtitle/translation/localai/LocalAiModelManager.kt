package com.lilac.anime.data.subtitle.translation.localai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object LocalAiModelManager {
    private const val ROOT = "local_ai/models"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(2, TimeUnit.HOURS)
        .retryOnConnectionFailure(true)
        .build()

    fun modelRoot(context: Context) = File(context.filesDir, ROOT).apply { mkdirs() }

    fun installed(context: Context): List<LocalAiModel> = modelRoot(context).listFiles()
        ?.filter { it.isFile && it.extension.equals("gguf", true) }
        ?.mapNotNull { file ->
            val inspection = GgufInspector.inspect(file)
            if (!inspection.valid) return@mapNotNull null
            buildModel(context, file, "local", file.nameWithoutExtension, inspection)
        }
        ?.sortedBy { it.displayName.lowercase() }
        ?: emptyList()

    suspend fun searchRepos(query: String): List<HuggingFaceRepo> = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://huggingface.co/api/models?search=$encoded&limit=20&sort=downloads&direction=-1"
        val request = Request.Builder().url(url).header("User-Agent", "LilacAnime/0.3.9").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Hugging Face 검색 HTTP ${response.code}")
            val array = JSONArray(response.body?.string().orEmpty())
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    add(HuggingFaceRepo(id, item.optLong("downloads"), item.optLong("likes")))
                }
            }
        }
    }

    suspend fun listRepoFiles(repoId: String): List<HuggingFaceModelFile> = withContext(Dispatchers.IO) {
        val url = "https://huggingface.co/api/models/${repoId.trim().trim('/') }?blobs=true"
        val request = Request.Builder().url(url).header("User-Agent", "LilacAnime/0.3.9").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Hugging Face API HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val siblings = json.optJSONArray("siblings") ?: JSONArray()
            buildList {
                for (i in 0 until siblings.length()) {
                    val item = siblings.optJSONObject(i) ?: continue
                    val name = item.optString("rfilename")
                    if (!name.lowercase().endsWith(".gguf")) continue
                    val size = item.optLong("size", -1L)
                    add(HuggingFaceModelFile(repoId.trim().trim('/'), name, size))
                }
            }.sortedWith(compareBy<HuggingFaceModelFile> { it.sizeBytes }.thenBy { it.fileName })
        }
    }

    suspend fun download(
        context: Context,
        model: HuggingFaceModelFile,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> }
    ): LocalAiModel = withContext(Dispatchers.IO) {
        val dir = modelRoot(context)
        val repoPart = model.repoId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val filePart = model.fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeName = "${repoPart}__${filePart}"
        val target = File(dir, safeName)
        val partial = File(dir, "$safeName.part")
        if (target.isFile && target.length() > 0L) {
            val inspection = GgufInspector.inspect(target)
            if (inspection.valid) {
                return@withContext buildModel(
                    context, target, model.repoId,
                    model.repoId.substringAfterLast('/') + " / " + target.name, inspection
                )
            }
            target.delete()
        }
        var received = if (partial.isFile) partial.length() else 0L
        val url = "https://huggingface.co/${model.repoId}/resolve/main/${model.fileName}?download=true"
        val builder = Request.Builder().url(url).header("User-Agent", "LilacAnime/0.3.9")
        if (received > 0L) builder.header("Range", "bytes=$received-")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("모델 다운로드 HTTP ${response.code}")
            val resumed = received > 0L && response.code == 206
            if (received > 0L && !resumed) { received = 0L; partial.delete() }
            val body = response.body ?: throw IOException("모델 응답이 비어 있습니다.")
            val total = if (resumed) received + body.contentLength().coerceAtLeast(0L) else body.contentLength().coerceAtLeast(0L)
            body.byteStream().use { input ->
                java.io.FileOutputStream(partial, resumed).buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var last = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        received += n
                        val now = System.currentTimeMillis()
                        if (now - last >= 500 || (total > 0L && received >= total)) { last = now; onProgress(received, total) }
                    }
                }
            }
        }
        if (!partial.isFile || partial.length() == 0L) throw IOException("모델 파일이 비어 있습니다.")
        target.delete()
        if (!partial.renameTo(target)) { partial.copyTo(target, true); partial.delete() }
        val inspection = GgufInspector.inspect(target)
        if (!inspection.valid) { target.delete(); throw IOException("다운로드한 파일이 유효한 GGUF가 아닙니다: ${inspection.error}") }
        buildModel(
            context, target, model.repoId,
            model.repoId.substringAfterLast('/') + " / " + target.name, inspection
        )
    }

    private fun buildModel(
        context: Context,
        file: File,
        repoId: String,
        displayName: String,
        inspection: GgufInspection
    ): LocalAiModel {
        val runtime = LocalAiRuntimeManager.findForModel(context, inspection)
        return LocalAiModel(
            id = "local:${file.name}",
            repoId = repoId,
            fileName = file.name,
            displayName = displayName,
            sizeBytes = file.length(),
            architecture = inspection.architecture,
            quantization = inspection.quantization,
            chatTemplate = inspection.chatTemplate,
            localPath = file.absolutePath,
            runtimeId = runtime?.id,
            compatibility = if (runtime != null) Compatibility.SUPPORTED else Compatibility.RUNTIME_REQUIRED
        )
    }

    fun delete(context: Context, model: LocalAiModel): Boolean = File(model.localPath).delete()
}
