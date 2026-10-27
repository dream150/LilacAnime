package com.lilac.anime.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Cloudflare 보안 확인 화면 (Jetpack Compose).
 * 챌린지가 감지되면 즉시 뜨고, cf_clearance 쿠키가 발급되면 자동으로 닫힌다.
 */
class CloudflareChallengeActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var targetUrl = ""
    private var before: String? = null
    private var done = false

    private val poll = object : Runnable {
        override fun run() {
            val now = CloudflareSession.clearanceValue(targetUrl)
            if (now != null && now != before) finishWith(true) else handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        before = intent.getStringExtra(EXTRA_BEFORE)
        if (targetUrl.isBlank()) { finishWith(false); return }

        setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFF111111))) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        "Cloudflare 보안 확인 · 완료되면 자동으로 닫힙니다",
                        modifier = Modifier.weight(1f),
                        style = TextStyle(color = Color.White, fontSize = 14.sp)
                    )
                    BasicText(
                        "닫기",
                        modifier = Modifier.clickable { finishWith(false) }.padding(8.dp),
                        style = TextStyle(color = Color(0xFF8AB4F8), fontSize = 14.sp)
                    )
                }
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx -> createWebView(ctx).also { webView = it } }
                )
            }
        }
        handler.postDelayed(poll, 800)
        handler.postDelayed({ finishWith(false) }, 120_000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: Context) = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = CloudflareSession.userAgent
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                CloudflareSession.rendererGone = true
                finishWith(false)
                return true
            }

            override fun onPageFinished(view: WebView, finishedUrl: String?) {
                if (done) return
                runCatching {
                    view.evaluateJavascript("(document.body&&document.body.innerText||'').slice(0,800)") { r ->
                        if (r != null && r.contains("you have been blocked", ignoreCase = true)) {
                            CloudflareSession.hardBlocked = true // 챌린지가 아닌 방화벽 차단
                            finishWith(false)
                        }
                    }
                }
            }
        }
        loadUrl(targetUrl)
    }

    private fun finishWith(success: Boolean) {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        if (success) runCatching { CookieManager.getInstance().flush() }
        CloudflareSession.interactiveResult = success
        CloudflareSession.interactiveLatch?.countDown()
        finish()
    }

    override fun onDestroy() {
        if (!done) { done = true; CloudflareSession.interactiveLatch?.countDown() } // 뒤로가기 = 취소
        handler.removeCallbacksAndMessages(null)
        webView?.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_BEFORE = "before"
    }
}
