package com.lilac.anime.network

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 요청이 Cloudflare 챌린지에 막혔음을 알리는 내부 신호. */
class CloudflareChallengeRequired : IOException("Cloudflare challenge required")

/** 챌린지 해결에 실패했을 때 사용자에게 보여줄 오류. */
class CloudflareChallengeException(message: String) : IOException(message)

/**
 * Cloudflare 인증 세션 관리.
 *
 * 1) 챌린지를 감지하면 WebView로 풀어서 cf_clearance 쿠키를 얻고
 * 2) 그 쿠키를 WebView CookieManager <-> OkHttp 가 공유하며
 * 3) cf_clearance 는 User-Agent 에 묶여 있으므로 OkHttp 도 같은 UA 를 사용한다.
 *
 * Application.onCreate 에서 [init] 을 한 번 호출해야 한다.
 */
object CloudflareSession {
    private const val TAG = "CloudflareSession"
    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val HEADLESS_TIMEOUT_MS = 25_000L
    private const val INTERACTIVE_TIMEOUT_MS = 120_000L
    private const val RECENT_SOLVE_MS = 15_000L
    private const val FAIL_COOLDOWN_MS = 10_000L
    /** true 로 바꾸면 인증창을 띄우기 전에 보이지 않는 WebView 로 먼저 시도(최대 25초). */
    private const val TRY_HEADLESS_FIRST = false

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedUa: String? = null
    @Volatile private var lastSolvedAt = 0L
    @Volatile private var lastFailedAt = 0L
    @Volatile internal var hardBlocked = false
    /** OkHttp 가 인증 후에도 막히면 true: 이후 API 요청은 WebView fetch 로 보낸다. */
    @Volatile var useWebViewFetch = false
    @Volatile internal var rendererGone = false
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    // CloudflareChallengeActivity 와 통신용
    @Volatile internal var interactiveLatch: CountDownLatch? = null
    @Volatile internal var interactiveResult = false

    /** init() 을 못 불렀어도 현재 실행 중인 Application 을 찾아 쓴다. */
    internal fun context(): Context? = appContext ?: runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()?.also { appContext = it; Log.w(TAG, "init() 없이 Application 을 자동 탐색했습니다.") }

    fun init(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "INIT_OK")
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }
    }

    /** WebView 와 동일한 UA. OkHttp 요청에도 반드시 이 값을 써야 cf_clearance 가 유효하다. */
    val userAgent: String
        get() {
            cachedUa?.let { return it }
            val ctx = context() ?: return FALLBACK_UA
            val ua = runCatching {
                WebSettings.getDefaultUserAgent(ctx).replace("; wv", "").replace("Version/4.0 ", "")
            }.getOrDefault(FALLBACK_UA)
            cachedUa = ua
            return ua
        }

    /** OkHttp 쿠키 저장소를 WebView CookieManager 와 공유. */
    val cookieJar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            runCatching {
                val cm = CookieManager.getInstance()
                cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = runCatching {
            CookieManager.getInstance().getCookie(url.toString())
                ?.split(';')?.mapNotNull { Cookie.parse(url, it.trim()) }
        }.getOrNull().orEmpty()
    }

    fun cookieHeader(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }

    internal fun clearanceValue(url: String): String? =
        cookieHeader(url)?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("cf_clearance=") }?.substringAfter('=')

    /** Cloudflare 챌린지 응답인지 판별. */
    fun isChallenge(code: Int, mitigated: String?, server: String?, body: ByteArray): Boolean {
        if (mitigated.equals("challenge", ignoreCase = true)) return true
        if (code != 403 && code != 503) return false
        if (server?.contains("cloudflare", ignoreCase = true) != true) return false
        val head = String(body, 0, minOf(body.size, 8192), Charsets.UTF_8)
        return head.contains("Just a moment", true) || head.contains("challenge-platform") || head.contains("cf-chl")
    }

    /**
     * 챌린지를 풀고 cf_clearance 쿠키를 확보한다. 백그라운드 스레드에서만 호출할 것(블로킹).
     * 실패하면 [CloudflareChallengeException].
     */
    fun solve(url: String) {
        val ctx = context() ?: throw CloudflareChallengeException(
            "Context 를 찾지 못했습니다. Application.onCreate 에서 CloudflareSession.init(this) 를 호출해 주세요.")
        check(Looper.myLooper() != Looper.getMainLooper()) { "CloudflareSession.solve 는 메인 스레드에서 호출할 수 없습니다." }
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - lastSolvedAt < RECENT_SOLVE_MS) return // 다른 스레드가 방금 해결함
            if (now - lastFailedAt < FAIL_COOLDOWN_MS) throw CloudflareChallengeException("Cloudflare 인증에 실패했습니다. 잠시 후 다시 시도해 주세요.")
            // API 주소를 직접 열면 WAF 에 막히기 쉬우므로 사이트 메인 페이지에서 챌린지를 푼다.
            val page = Uri.parse(url).let { "${it.scheme}://${it.host}/" }
            val before = clearanceValue(page)
            hardBlocked = false
            rendererGone = false
            Log.i(TAG, "SOLVE_START page=$page")
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val pkg = WebView.getCurrentWebViewPackage()
                    Log.i(TAG, "WEBVIEW_INFO package=${pkg?.packageName} version=${pkg?.versionName} sdk=${android.os.Build.VERSION.SDK_INT} ua=$userAgent")
                }
            }
            val ok = (TRY_HEADLESS_FIRST && runHeadless(ctx, page, before)) || (!hardBlocked && runInteractive(ctx, page, before))
            if (ok) {
                lastSolvedAt = System.currentTimeMillis()
                runCatching { CookieManager.getInstance().flush() }
                Log.i(TAG, "SOLVE_OK")
            } else {
                lastFailedAt = System.currentTimeMillis()
                Log.e(TAG, "SOLVE_FAILED hardBlocked=$hardBlocked")
                throw CloudflareChallengeException(
                    if (rendererGone) "WebView 렌더러가 비정상 종료되었습니다. 기기의 Android System WebView/Chrome 을 업데이트하거나 메모리 여유를 확인해 주세요."
                    else if (hardBlocked) "Cloudflare 방화벽이 이 네트워크/기기의 접근을 차단했습니다(챌린지가 아닌 차단 페이지). 다른 네트워크에서 시도하거나 사이트 상태를 확인해 주세요."
                    else "Cloudflare 보안 확인을 통과하지 못했습니다.")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun runHeadless(ctx: Context, url: String, before: String?): Boolean {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<WebView>(1)
        val poll = object : Runnable {
            override fun run() {
                val now = clearanceValue(url)
                if (now != null && now != before) latch.countDown() else main.postDelayed(this, 500)
            }
        }
        main.post {
            try {
                val wv = WebView(ctx)
                holder[0] = wv
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        // true 를 반환해야 렌더러가 죽어도 앱이 같이 종료되지 않는다.
                        Log.e(TAG, "RENDERER_GONE crashed=${detail.didCrash()}")
                        rendererGone = true
                        latch.countDown()
                        return true
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                        if (rendererGone) return
                        runCatching { view.evaluateJavascript("(document.body&&document.body.innerText||'').slice(0,800)") { r ->
                            if (r != null && r.contains("you have been blocked", ignoreCase = true)) {
                                hardBlocked = true
                                latch.countDown()
                            }
                        } }
                    }
                }
                wv.loadUrl(url)
                main.postDelayed(poll, 1000)
            } catch (t: Throwable) {
                Log.e(TAG, "WEBVIEW_FAILED ${t.message}")
                latch.countDown()
            }
        }
        latch.await(HEADLESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        main.post {
            main.removeCallbacks(poll)
            holder[0]?.apply { stopLoading(); destroy() }
        }
        val after = clearanceValue(url)
        return !hardBlocked && !rendererGone && after != null && after != before
    }

    /** 체크박스 등 사용자 조작이 필요한 챌린지: 화면에 WebView 를 띄운다. */
    private fun runInteractive(ctx: Context, url: String, before: String?): Boolean {
        Log.i(TAG, "INTERACTIVE_START")
        val latch = CountDownLatch(1)
        interactiveResult = false
        interactiveLatch = latch
        try {
            ctx.startActivity(
                Intent(ctx, CloudflareChallengeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(CloudflareChallengeActivity.EXTRA_URL, url)
                    .putExtra(CloudflareChallengeActivity.EXTRA_BEFORE, before)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "INTERACTIVE_LAUNCH_FAILED ${t.message}")
            interactiveLatch = null
            return false
        }
        latch.await(INTERACTIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        interactiveLatch = null
        val after = clearanceValue(url)
        return interactiveResult && after != null && after != before
    }
}
