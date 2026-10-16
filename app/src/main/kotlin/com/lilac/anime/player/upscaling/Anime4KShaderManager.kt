package com.lilac.anime.player.upscaling

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object Anime4KShaderManager {
    private const val TAG = "LilacAnime4K"
    private const val API_URL = "https://api.github.com/repos/bloc97/Anime4K/releases/latest"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val modeFiles = mapOf(
        "A" to listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl"
        ),
        "B" to listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_Soft_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl"
        ),
        "C" to listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl"
        ),
        "A+A" to listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_Restore_CNN_S.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl"
        ),
        "B+B" to listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_Soft_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_Restore_CNN_Soft_S.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl"
        ),
        "C+A" to listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Restore_CNN_S.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl"
        )
    )

    suspend fun ensureShaders(context: Context, mode: String = "A"): List<File> = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "mpv-shaders/anime4k").apply { mkdirs() }
        val selectedMode = mode.takeIf { it in modeFiles } ?: "A"
        val expected = modeFiles.getValue(selectedMode)
        if (expected.all { File(directory, it).isFile && File(directory, it).length() > 100 }) {
            return@withContext expected.map { File(directory, it) }
        }

        val apiRequest = Request.Builder().url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "LilacAnime-Android")
            .build()
        val releaseJson = client.newCall(apiRequest).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Anime4K 릴리스 정보 요청 실패: HTTP ${response.code}")
            JSONObject(response.body?.string() ?: throw IllegalStateException("Anime4K 릴리스 응답이 비어 있습니다."))
        }
        val assets = releaseJson.optJSONArray("assets") ?: throw IllegalStateException("Anime4K 릴리스에 파일 목록이 없습니다.")
        val candidates = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".zip", true) }
            .sortedByDescending { it.optString("name").contains("GLSL", true) || it.optString("name").contains("shader", true) }
        if (candidates.isEmpty()) throw IllegalStateException("Anime4K GLSL ZIP을 찾을 수 없습니다.")

        var extracted = false
        var lastError: Throwable? = null
        for (asset in candidates) {
            try {
                val url = asset.optString("browser_download_url")
                if (url.isBlank()) continue
                val request = Request.Builder().url(url).header("User-Agent", "LilacAnime-Android").build()
                val temp = File(directory, "anime4k-release.tmp")
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Anime4K 셰이더 다운로드 실패: HTTP ${response.code}")
                    val body = response.body ?: throw IllegalStateException("Anime4K 다운로드 응답이 비어 있습니다.")
                    body.byteStream().use { input -> FileOutputStream(temp).use { output -> input.copyTo(output) } }
                }
                ZipInputStream(temp.inputStream().buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.substringAfterLast('/')
                        if (!entry.isDirectory && name.endsWith(".glsl", true) && name.matches(Regex("[A-Za-z0-9_.-]+\\.glsl", RegexOption.IGNORE_CASE))) {
                            val out = File(directory, name)
                            FileOutputStream(out).use { output -> zip.copyTo(output) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                temp.delete()
                if (expected.all { File(directory, it).isFile && File(directory, it).length() > 100 }) {
                    extracted = true
                    break
                }
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Anime4K asset extraction failed: ${asset.optString("name")}", t)
            }
        }
        if (!extracted || expected.any { !File(directory, it).isFile || File(directory, it).length() <= 100 }) {
            throw IllegalStateException("Anime4K $selectedMode 모드에 필요한 셰이더 파일이 없습니다. 로그를 확인하세요.", lastError)
        }
        expected.map { File(directory, it) }.also { Log.i(TAG, "Anime4K mode=$selectedMode shaders ready: ${it.size} files at ${directory.absolutePath}") }
    }

}
