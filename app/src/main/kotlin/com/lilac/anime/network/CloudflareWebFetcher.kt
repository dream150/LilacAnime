package com.lilac.anime.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * OkHttp 요청이 Cloudflare 에 계속 막힐 때(TLS 지문/헤더 차이) 사용하는 대체 경로.
 * 보이지 않는 WebView 안에서 fetch() 로 요청해서 실제 브라우저와 동일하게 보낸다.
 */
object CloudflareWebFetcher {
    private const val TAG = "CloudflareWebFetcher"

    class Reply(val status: Int, val mitigated: String?, val server: String?, val body: ByteArray)

    private class Pending {
        val latch = CountDownLatch(1)
        @Volatile var status = -1
        @Volatile var mitigated: String? = null
        @Volatile var server: String? = null
        @Volatile var base64: String? = null
        @Volatile var error: String? = null
    }

    class Bridge {
        @JavascriptInterface
        fun onResult(id: String, status: Int, mitigated: String, server: String, b64: String) {
            pending[id]?.apply {
                this.status = status
                this.mitigated = mitigated.ifBlank { null }
                this.server = server.ifBlank { null }
                base64 = b64
                latch.countDown()
            }
        }

        @JavascriptInterface
        fun onError(id: String, message: String) {
            pending[id]?.apply { error = message; latch.countDown() }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val pending = ConcurrentHashMap<String, Pending>()
    @Volatile private var webView: WebView? = null
    @Volatile private var ready: CountDownLatch? = null
    @Volatile private var readyOrigin: String? = null
    @Volatile private var broken = false

    /** 백그라운드 스레드에서만 호출(블로킹). */
    fun fetch(url: String, timeoutMs: Long = 30_000): Reply {
        check(Looper.myLooper() != Looper.getMainLooper()) { "메인 스레드에서 호출할 수 없습니다." }
        val ctx = CloudflareSession.context() ?: throw IOException("Context 를 찾지 못했습니다.")
        val origin = Uri.parse(url).let { "${it.scheme}://${it.host}/" }
        if (!ensureReady(ctx, origin)) throw IOException("WebView 요청 환경을 준비하지 못했습니다.")

        val id = UUID.randomUUID().toString()
        val pend = Pending()
        pending[id] = pend
        val js = "(function(){var id=${JSONObject.quote(id)},url=${JSONObject.quote(url)};" +
            "fetch(url,{credentials:'include'}).then(function(r){return r.arrayBuffer().then(function(b){" +
            "var u=new Uint8Array(b),s='',c=32768;for(var i=0;i<u.length;i+=c){s+=String.fromCharCode.apply(null,u.subarray(i,i+c));}" +
            "CfBridge.onResult(id,r.status,r.headers.get('cf-mitigated')||'',r.headers.get('server')||'',btoa(s));});})" +
            ".catch(function(e){CfBridge.onError(id,String(e));});})();"
        main.post {
            val wv = webView
            if (wv == null || broken) { pend.error = "WebView 가 없습니다."; pend.latch.countDown() }
            else wv.evaluateJavascript(js, null)
        }
        try {
            if (!pend.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("WebView 요청 시간이 초과되었습니다.")
        } finally {
            pending.remove(id)
        }
        pend.error?.let { throw IOException("WebView 요청 실패: $it") }
        val bytes = Base64.decode(pend.base64.orEmpty(), Base64.DEFAULT)
        Log.d(TAG, "FETCH_OK status=${pend.status} bytes=${bytes.size}")
        return Reply(pend.status, pend.mitigated, pend.server, bytes)
    }

    private fun ensureReady(ctx: Context, origin: String): Boolean {
        val latch: CountDownLatch
        synchronized(lock) {
            val current = ready
            if (current != null && readyOrigin == origin && !broken) {
                latch = current
            } else {
                val old = webView
                webView = null
                latch = CountDownLatch(1)
                ready = latch
                readyOrigin = origin
                broken = false
                main.post { runCatching { old?.destroy() }; createAndLoad(ctx, origin, latch) }
            }
        }
        return latch.await(15, TimeUnit.SECONDS) && !broken && webView != null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAndLoad(ctx: Context, origin: String, latch: CountDownLatch) {
        try {
            val wv = WebView(ctx)
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null) // 화면에 붙지 않는 WebView 의 GPU 렌더러 크래시 방지
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = CloudflareSession.userAgent
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.addJavascriptInterface(Bridge(), "CfBridge")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) { latch.countDown() }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Log.e(TAG, "RENDERER_GONE crashed=${detail.didCrash()}")
                    broken = true
                    pending.values.forEach { it.error = "렌더러가 종료되었습니다."; it.latch.countDown() }
                    latch.countDown()
                    return true
                }
            }
            webView = wv
            // 사이트 origin 을 가진 빈 페이지에서 same-origin fetch 를 수행 (SPA 전체를 로드하지 않음)
            wv.loadDataWithBaseURL(origin, "<html><body></body></html>", "text/html", "utf-8", null)
        } catch (t: Throwable) {
            Log.e(TAG, "CREATE_FAILED ${t.message}")
            broken = true
            latch.countDown()
        }
    }
}
