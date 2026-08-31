package com.lilac.anime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import com.lilac.anime.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.content.Intent

// ============================================================
// DATA MODELS
// ============================================================


data class GithubReleaseInfo(
    val tag: String,
    val name: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val body: String
)

object GithubReleaseChecker {
    private const val REPOSITORY = "dream150/LilacAnime"
    private const val PREF_LAST_NOTIFIED_TAG = "github_last_notified_release_tag"
    private const val CHANNEL_ID = "github_release_updates"
    private const val NOTIFICATION_ID = 7401

    suspend fun check(context: Context): GithubReleaseInfo? = withContext(Dispatchers.IO) {
        if (REPOSITORY == "OWNER/REPOSITORY") return@withContext null
        try {
            val connection = (URL("https://api.github.com/repos/$REPOSITORY/releases/latest").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 10000; readTimeout = 10000; useCaches = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "LilacAnime")
            }
            try {
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299 || body.isBlank()) return@withContext null
                val json = JSONObject(body)
                val tag = json.optString("tag_name").trim()
                val name = json.optString("name").trim().ifBlank { tag }
                val releaseUrl = json.optString("html_url").trim()
                val releaseBody = json.optString("body").trim()
                if (tag.isBlank()) return@withContext null
                var apkUrl: String? = null
                json.optJSONArray("assets")?.let { assets ->
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        if (asset.optString("name").lowercase(Locale.ROOT).endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url").trim().takeIf { it.isNotBlank() }
                            if (apkUrl != null) break
                        }
                    }
                }
                val prefs = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
                val previous = prefs.getString(PREF_LAST_NOTIFIED_TAG, null)
                if (previous == null) { prefs.edit().putString(PREF_LAST_NOTIFIED_TAG, tag).apply(); return@withContext null }
                if (previous == tag) return@withContext null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).createNotificationChannel(
                        android.app.NotificationChannel(CHANNEL_ID, "GitHub 업데이트", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
                val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (canNotify) {
                    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("새 GitHub 릴리스가 있습니다")
                        .setContentText(name)
                        .setStyle(NotificationCompat.BigTextStyle().bigText("새 릴리스 $tag 를 사용할 수 있습니다."))
                        .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    if (releaseUrl.isNotBlank()) {
                        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
                        builder.setContentIntent(android.app.PendingIntent.getActivity(context, NOTIFICATION_ID, Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)), flags))
                    }
                    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
                }
                prefs.edit().putString(PREF_LAST_NOTIFIED_TAG, tag).apply()
                GithubReleaseInfo(tag, name, releaseUrl, apkUrl, releaseBody)
            } finally { connection.disconnect() }
        } catch (e: Exception) { Log.w("GithubRelease", "CHECK_FAILED", e); null }
    }

    suspend fun downloadApk(context: Context, apkUrl: String): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "lilac_update.apk")
        val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000; requestMethod = "GET"; setRequestProperty("User-Agent", "LilacAnime"); instanceFollowRedirects = true }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("APK 다운로드 실패: HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
            file
        } finally { connection.disconnect() }
    }
}

fun installApkWithPackageInstaller(context: Context, apkFile: File) {
    if (!apkFile.isFile || apkFile.length() <= 0L) {
        throw IllegalStateException("다운로드한 APK 파일이 없습니다.")
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        throw SecurityException("알 수 없는 앱 설치 권한이 허용되지 않았습니다.")
    }

    val apkUri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )

    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(installIntent)
    } catch (e: android.content.ActivityNotFoundException) {
        throw IllegalStateException("APK 설치 화면을 열 수 없습니다.", e)
    }
}
