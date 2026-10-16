package com.lilac.anime.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Exports the complete logcat buffer set after the user explicitly taps the button. */
object DiagnosticLogExporter {
    fun share(context: Context): File {
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val output = File(dir, "LilacAnime-diagnostics-$stamp.txt")
        val source = context.getSharedPreferences("lilac_offline_store", Context.MODE_PRIVATE)
            .getString("pref_video_source", "linkkf") ?: "linkkf"
        val lines = readLogcat()
        output.writeText(buildString {
            appendLine("LilacAnime full logcat export")
            appendLine("time=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(now))}")
            appendLine("package=${context.packageName}")
            appendLine("source=$source")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("log_lines=${lines.lineSequence().count()}")
            appendLine("=".repeat(72))
            append(lines)
        }, Charsets.UTF_8)

        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LilacAnime 진단 로그 · $source")
            putExtra(Intent.EXTRA_TEXT, "LilacAnime 전체 logcat 로그입니다. 기기 정보와 추출 가능한 전체 로그 버퍼가 포함됩니다.")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, output.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "진단 로그 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return output
    }

    private fun readLogcat(): String {
        return try {
            // -b all collects every log buffer available to this process; do not filter or truncate.
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-b", "all")
                .redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.waitFor(10, TimeUnit.SECONDS)
            if (text.isBlank()) "logcat 명령은 실행됐지만 추출 가능한 로그가 없습니다.\n" else text
        } catch (t: Throwable) {
            "logcat 추출 실패: ${t.javaClass.simpleName}: ${t.message}\n"
        }
    }
}