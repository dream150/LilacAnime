package com.lilac.anime.ai.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lilac.anime.ai.AiHardware
import com.lilac.anime.ai.NpuStatus
import com.lilac.anime.data.subtitle.translation.localai.HuggingFaceModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * .litertlm 모델 관리.
 *
 * NPU용 LLM은 SoC별로 AOT 컴파일된 파일이 따로 있다
 * (예: Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm, gemma-4-E2B-it_Google_Tensor_G5.litertlm).
 * 그래서 "파일명에 들어 있는 SoC 토큰"이 이 기기와 맞는지로 NPU 빌드 여부를 판정한다.
 * 토큰이 없는 파일은 범용(GPU/CPU) 빌드, 다른 SoC 토큰이면 이 기기에서 못 쓰는 빌드다.
 *
 * 일부 litert-community 저장소는 gated라서 Hugging Face 로그인 + 약관 동의 후 토큰이 필요하다.
 */
object LiteRtModelStore {
    private const val UA = "LilacAnime/AI"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(3, TimeUnit.HOURS)
        .retryOnConnectionFailure(true)
        .build()

    private val SOC_TOKEN = Regex(
        """(sm\d{4}|sdm\d{3,4}|qcs\d{4}|sa\d{4}|mt\d{4}|s5e\d{4}|tensor[_\s-]?g\d+|gs\d{3})""",
        RegexOption.IGNORE_CASE
    )

    fun root(context: Context): File = File(context.filesDir, "local_ai/litert").apply { mkdirs() }

    fun socTokenOf(fileName: String): String? =
        SOC_TOKEN.find(fileName)?.value?.lowercase()?.filter { it.isLetterOrDigit() }

    private fun tokenMatchesDevice(token: String): Boolean {
        val device = AiHardware.soc().modelKey
        if (device.isBlank()) return false
        // SOC_MODEL 표기가 "SM8750-AC"처럼 접미사를 달기도 하므로 접두 비교
        return device.startsWith(token) || token.startsWith(device)
    }

    fun installed(context: Context): List<LocalLlmModel> =
        root(context).listFiles()
            ?.filter { it.isFile && it.extension.equals("litertlm", true) }
            ?.map { file ->
                val token = socTokenOf(file.name)
                val matches = token != null && tokenMatchesDevice(token)
                LocalLlmModel(
                    id = "litertlm:${file.name}",
                    displayName = file.nameWithoutExtension,
                    format = ModelFormat.LITERTLM,
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    npuTarget = matches,
                    deviceCompatible = token == null || matches
                )
            }
            ?.sortedBy { it.displayName.lowercase() }
            ?: emptyList()

    suspend fun listRepoFiles(
        repoId: String,
        hfToken: String? = null,
        extensions: Set<String> = setOf(".litertlm")
    ): List<HuggingFaceModelFile> =
        withContext(Dispatchers.IO) {
            val id = repoId.trim().trim('/')
            val request = Request.Builder()
                .url("https://huggingface.co/api/models/$id?blobs=true")
                .header("User-Agent", UA)
                .apply { if (!hfToken.isNullOrBlank()) header("Authorization", "Bearer $hfToken") }
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Hugging Face API HTTP ${response.code}")
                val siblings = JSONObject(response.body?.string().orEmpty()).optJSONArray("siblings") ?: JSONArray()
                buildList {
                    for (i in 0 until siblings.length()) {
                        val item = siblings.optJSONObject(i) ?: continue
                        val name = item.optString("rfilename")
                        if (extensions.none { name.lowercase().endsWith(it) }) continue
                        add(HuggingFaceModelFile(id, name, item.optLong("size", -1L)))
                    }
                }.sortedBy { it.fileName }
            }
        }

    /**
     * 저장소 파일 목록에서 이 기기에 맞는 빌드를 고른다.
     * NPU 사용 가능하고 SoC 토큰이 일치하는 파일 → 없으면 범용(토큰 없음) 파일 중 가장 작은 것.
     */
    fun pickForDevice(context: Context, files: List<HuggingFaceModelFile>): HuggingFaceModelFile? {
        if (AiHardware.npuStatus(context) == NpuStatus.READY) {
            files.firstOrNull { f -> socTokenOf(f.fileName)?.let(::tokenMatchesDevice) == true }?.let { return it }
        }
        return files.filter { socTokenOf(it.fileName) == null }
            .minByOrNull { if (it.sizeBytes > 0) it.sizeBytes else Long.MAX_VALUE }
    }

    suspend fun download(
        context: Context,
        file: HuggingFaceModelFile,
        hfToken: String? = null,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> }
    ): LocalLlmModel = withContext(Dispatchers.IO) {
        val safe = (file.repoId.replace(Regex("[^A-Za-z0-9._-]"), "_") + "__" +
            file.fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val target = File(root(context), safe)
        val partial = File(root(context), "$safe.part")
        var received = if (partial.isFile) partial.length() else 0L
        val url = "https://huggingface.co/${file.repoId}/resolve/main/${file.fileName}?download=true"
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        if (!hfToken.isNullOrBlank()) builder.header("Authorization", "Bearer $hfToken")
        if (received > 0L) builder.header("Range", "bytes=$received-")
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw IOException("접근 거부(HTTP ${response.code}): gated 모델이면 Hugging Face 약관 동의 후 토큰을 입력하세요.")
            }
            if (!response.isSuccessful) throw IOException("모델 다운로드 HTTP ${response.code}")
            val resumed = received > 0L && response.code == 206
            if (received > 0L && !resumed) { received = 0L; partial.delete() }
            val body = response.body ?: throw IOException("모델 응답이 비어 있습니다.")
            val total = if (resumed) received + body.contentLength().coerceAtLeast(0L)
            else body.contentLength().coerceAtLeast(0L)
            body.byteStream().use { input ->
                FileOutputStream(partial, resumed).buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var last = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        received += n
                        val now = System.currentTimeMillis()
                        if (now - last >= 500 || (total > 0L && received >= total)) {
                            last = now
                            onProgress(received, total)
                        }
                    }
                }
            }
        }
        if (!partial.isFile || partial.length() < 1024L * 1024L) {
            partial.delete()
            throw IOException("다운로드한 모델 파일이 비정상적으로 작습니다.")
        }
        target.delete()
        if (!partial.renameTo(target)) { partial.copyTo(target, true); partial.delete() }
        installed(context).firstOrNull { it.path == target.absolutePath }
            ?: throw IOException("설치된 모델을 확인할 수 없습니다.")
    }

    fun delete(model: LocalLlmModel): Boolean = File(model.path).delete()

    fun displayNameOf(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** 파일 선택기로 고른 .litertlm을 앱 저장소로 복사한다. */
    suspend fun importFile(context: Context, uri: Uri): LocalLlmModel = withContext(Dispatchers.IO) {
        val name = displayNameOf(context, uri) ?: "model.litertlm"
        if (!name.endsWith(".litertlm", true)) throw IOException(".litertlm 파일만 추가할 수 있습니다.")
        val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "model.litertlm" }
        val target = File(root(context), safe)
        val partial = File(root(context), "$safe.importing")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                partial.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) }
            } ?: throw IOException("모델 파일을 읽을 수 없습니다.")
            if (!partial.isFile || partial.length() < 1024L * 1024L) throw IOException("모델 파일이 비어 있거나 비정상적으로 작습니다.")
            if (target.exists() && !target.delete()) throw IOException("기존 모델 파일을 교체할 수 없습니다.")
            if (!partial.renameTo(target)) { partial.copyTo(target, true); partial.delete() }
            installed(context).firstOrNull { it.path == target.absolutePath }
                ?: throw IOException("설치된 모델을 확인할 수 없습니다.")
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
    }
}
