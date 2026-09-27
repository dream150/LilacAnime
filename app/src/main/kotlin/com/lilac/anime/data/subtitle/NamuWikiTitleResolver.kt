package com.lilac.anime.data.subtitle

import android.content.Context
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Re:ANIME의 일본어/영어 제목을 나무위키 검색 결과의 한국어 문서 제목으로 변환한다.
 *
 * 핵심은 기존에 정상 동작했던 OnlineAniSkipService의 나무위키 검색 방식이다.
 *
 *   title
 *     -> https://namu.wiki/Search?target=title_content&q=...
 *     -> Jsoup.parse(body).select("a[href^=/w/]")
 *     -> element.text()
 *
 * 여기서는 검색어 자체가 한국어가 아니므로 예전의 한글 유사도 점수는
 * 역방향 검색에 사용할 수 없다. 대신 검색 결과 카드(section) 안에
 * 원래 검색어가 포함되어 있는지를 이용해 해당 카드의 한국어 문서 제목을
 * 선택한다. 문서 페이지를 열거나 <h1>을 읽지는 않는다.
 */
object NamuWikiTitleResolver {
    private const val TAG = "NamuWikiTitle"
    private const val PREFS = "namuwiki_title_cache"
    private const val CACHE_VERSION = 9

    private const val BASE_URL = "https://namu.wiki/Search?q="
    private const val FALLBACK_URL = "https://namu.wiki/Search?target=title_content&q="
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun resolve(context: Context, title: String): String? {
        val original = title.trim()
        if (original.isBlank()) return null

        // 요구사항: 나무위키 검색 직전에만 … -> ... 변환.
        // 그 외의 제목 문자열은 임의로 바꾸지 않는다.
        val searchQuery = original.replace("…", "...")

        // 이미 한국어면 변환할 필요가 없다.
        if (containsHangul(original)) return original

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cacheKey = "v$CACHE_VERSION:$searchQuery"

        prefs.getString(cacheKey, null)
            ?.takeIf { it.isNotBlank() && containsHangul(it) }
            ?.let {
                Log.d(TAG, "CACHE_HIT query=[$searchQuery] korean=[$it]")
                return it
            }

        return runCatching {
            val encoded = URLEncoder.encode(
                searchQuery,
                StandardCharsets.UTF_8.toString()
            )

            fun fetch(url: String): Pair<Int, String> {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header(
                        "Accept-Language",
                        "ko-KR,ko;q=0.9,en-US;q=0.7,en;q=0.6"
                    )
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://namu.wiki/")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()

                Log.d(TAG, "SEARCH_REQUEST url=[$url]")
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    Log.d(
                        TAG,
                        "SEARCH_RESPONSE url=[$url] code=${response.code} " +
                            "success=${response.isSuccessful} finalUrl=${response.request.url} " +
                            "contentType=${response.header("Content-Type")} bodyLength=${body.length}"
                    )
                    return response.code to body
                }
            }

            fun extractCandidates(body: String, sourceUrl: String): List<Triple<String, String, Int>> {
                if (body.isBlank()) return emptyList()

                val document = Jsoup.parse(body, sourceUrl)
                val allAnchors = document.select("a[href]")
                val relativeW = document.select("a[href^=/w/]")
                val absoluteW = allAnchors.filter { anchor ->
                    val href = anchor.attr("href").trim()
                    href.startsWith("https://namu.wiki/w/", ignoreCase = true) ||
                        href.startsWith("http://namu.wiki/w/", ignoreCase = true) ||
                        href.startsWith("//namu.wiki/w/", ignoreCase = true)
                }

                Log.d(
                    TAG,
                    "SEARCH_HTML_STATS url=[$sourceUrl] " +
                        "bodyLength=${body.length} anchors=${allAnchors.size} " +
                        "relativeW=${relativeW.size} absoluteW=${absoluteW.size} " +
                        "sections=${document.select("section").size} " +
                        "h4=${document.select("h4").size} title=${document.title()}"
                )

                // 진단용: 서버가 검색 결과 HTML이 아니라 다른 페이지를 반환하는 경우 확인할 수 있게 한다.
                val bodyPreview = normalizeWhitespace(body).take(1500)
                Log.d(TAG, "SEARCH_HTML_PREVIEW url=[$sourceUrl] html=[$bodyPreview]")

                val anchors = (relativeW + absoluteW).distinctBy { it.outerHtml() }

                anchors.take(30).forEachIndexed { index, element ->
                    Log.d(
                        TAG,
                        "W_LINK_RAW index=$index href=[${element.attr("href")}] " +
                            "text=[${normalizeWhitespace(element.text())}] " +
                            "parent=[${element.parent()?.tagName()}]"
                    )
                }

                return anchors.mapNotNull { element ->
                    val href = element.attr("href").trim()
                    val label = normalizeWhitespace(element.text())
                    if (href.isBlank() || label.isBlank() || !containsHangul(label)) {
                        return@mapNotNull null
                    }

                    val normalizedHref = when {
                        href.startsWith("/w/") -> "https://namu.wiki$href"
                        href.startsWith("//") -> "https:$href"
                        else -> href
                    }

                    val section = element.closest("section")
                    val cardText = normalizeWhitespace(section?.text().orEmpty())
                    val score = scoreSearchResult(searchQuery, label, cardText, element)

                    Triple(label, normalizedHref, score)
                }
            }

            val primaryUrl = BASE_URL + encoded
            val fallbackUrl = FALLBACK_URL + encoded

            // 나무위키 Search는 현재 SPA라서 OkHttp로 받은 초기 HTML에는
            // 실제 검색 결과가 없고 JavaScript 실행 후 DOM에 결과가 삽입된다.
            // 따라서 검색 페이지 자체는 WebView로 렌더링한 뒤 최종 DOM을 가져온다.
            var renderedBody = renderSearchPage(context, primaryUrl)
            var candidates = renderedBody?.let {
                extractCandidates(it, primaryUrl)
            }.orEmpty()

            // 첫 URL에서 결과가 없으면 예전에 사용하던 target=title_content URL도
            // WebView로 렌더링해서 다시 확인한다.
            if (candidates.isEmpty()) {
                Log.w(TAG, "WEBVIEW_PRIMARY_NO_CANDIDATE query=[$searchQuery] fallback=target_title_content")
                renderedBody = renderSearchPage(context, fallbackUrl)
                candidates = renderedBody?.let {
                    extractCandidates(it, fallbackUrl)
                }.orEmpty()
            }

            Log.d(
                TAG,
                "SEARCH_RESULT_CANDIDATES query=[$searchQuery] count=${candidates.size}"
            )

            candidates
                .distinctBy { it.second }
                .sortedByDescending { it.third }
                .take(20)
                .forEachIndexed { index, result ->
                    Log.d(
                        TAG,
                        "RESULT index=$index score=${result.third} " +
                            "korean=[${result.first}] url=${result.second}"
                    )
                }

            val best = candidates
                .distinctBy { it.second }
                .sortedByDescending { it.third }
                .firstOrNull()

            val koreanTitle = best?.first?.takeIf { hasUsefulDocumentTitle(it) }

            if (koreanTitle.isNullOrBlank()) {
                Log.w(
                    TAG,
                    "KOREAN_TITLE_NOT_FOUND query=[$searchQuery] reason=NO_KOREAN_W_LINK"
                )
                return@runCatching null
            }

            prefs.edit()
                .putString(cacheKey, koreanTitle)
                .apply()

            Log.d(
                TAG,
                "KOREAN_TITLE_FOUND query=[$searchQuery] korean=[$koreanTitle] " +
                    "source=SEARCH_RESULT_LINK_TEXT"
            )

            koreanTitle
        }.getOrElse { error ->
            Log.e(
                TAG,
                "RESOLVE_FAILED query=[$searchQuery] " +
                    "${error.javaClass.simpleName}: ${error.message}",
                error
            )
            null
        }
    }

    /**
     * 나무위키 검색 결과가 JavaScript로 렌더링된 뒤의 실제 DOM을 가져온다.
     * 이 함수는 WebView가 필요한 작업만 Main dispatcher에서 수행한다.
     */
    private suspend fun renderSearchPage(context: Context, url: String): String? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context.applicationContext)
                var finished = false
                var attempts = 0

                fun finish(html: String?) {
                    if (finished) return
                    finished = true
                    try {
                        webView.stopLoading()
                        webView.destroy()
                    } catch (_: Throwable) {
                    }
                    if (continuation.isActive) continuation.resume(html)
                }

                fun readRenderedDom() {
                    if (finished || !continuation.isActive) return
                    webView.evaluateJavascript(
                        "(function(){return document.documentElement ? document.documentElement.outerHTML : '';})()"
                    ) { raw ->
                        val html = decodeJavascriptString(raw)
                        val document = if (html.isNotBlank()) Jsoup.parse(html, url) else null
                        val wCount = document?.select("a[href]")?.count { element ->
                            val href = element.attr("href").trim()
                            href.startsWith("/w/") ||
                                href.startsWith("https://namu.wiki/w/", ignoreCase = true) ||
                                href.startsWith("http://namu.wiki/w/", ignoreCase = true) ||
                                href.startsWith("//namu.wiki/w/", ignoreCase = true)
                        } ?: 0

                        Log.d(
                            TAG,
                            "WEBVIEW_DOM_ATTEMPT url=[$url] attempt=$attempts htmlLength=${html.length} wLinks=$wCount"
                        )

                        if (wCount > 0 || attempts >= 10) {
                            Log.d(TAG, "WEBVIEW_DOM_READY url=[$url] htmlLength=${html.length} wLinks=$wCount")
                            finish(html)
                        } else {
                            attempts++
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                { readRenderedDom() },
                                500L
                            )
                        }
                    }
                }

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.loadsImagesAutomatically = false
                webView.settings.userAgentString = USER_AGENT
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        Log.d(TAG, "WEBVIEW_PAGE_FINISHED requested=[$url] loaded=[$pageUrl]")
                        attempts = 0
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            { readRenderedDom() },
                            700L
                        )
                    }
                }

                continuation.invokeOnCancellation {
                    try {
                        webView.stopLoading()
                        webView.destroy()
                    } catch (_: Throwable) {
                    }
                }

                Log.d(TAG, "WEBVIEW_LOAD url=[$url]")
                webView.loadUrl(url)
            }
        }

    private fun decodeJavascriptString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            org.json.JSONTokener(raw).nextValue()?.toString().orEmpty()
        }.getOrElse {
            raw.removePrefix("\"").removeSuffix("\"")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
    }

    private fun scoreSearchResult(
        query: String,
        label: String,
        cardText: String,
        element: org.jsoup.nodes.Element
    ): Int {
        var score = 0

        val normalizedQuery = normalizeForSearchComparison(query)
        val normalizedCard = normalizeForSearchComparison(cardText)

        // 같은 검색 결과 카드 안에 원래 검색어가 있으면 최우선.
        if (normalizedQuery.isNotBlank() &&
            normalizedCard.contains(normalizedQuery)
        ) {
            score += 10000
        }

        // 공백/구두점 차이가 있어도 주요 토큰이 카드에 들어가면 보정.
        val tokens = searchTokens(query)
        if (tokens.isNotEmpty()) {
            val matched = tokens.count { token ->
                normalizeForSearchComparison(cardText)
                    .contains(normalizeForSearchComparison(token))
            }
            score += matched * 500
        }

        // 실제 문서 제목 위치인 h4 링크를 우선한다.
        if (element.parent()?.tagName()?.equals("h4", ignoreCase = true) == true) {
            score += 300
        }

        // 너무 짧은 문서 제목보다 실제 작품명 후보를 조금 우선.
        if (label.length >= 3) score += 10

        return score
    }

    private fun searchTokens(value: String): List<String> =
        value
            .replace("…", "...")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()

    private fun normalizeForSearchComparison(value: String): String =
        value
            .lowercase()
            .replace("…", "...")
            .replace(Regex("\\s+"), "")
            .trim()

    private fun normalizeWhitespace(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private fun hasUsefulDocumentTitle(value: String): Boolean {
        val title = normalizeWhitespace(value)
        if (title.isBlank()) return false
        if (!containsHangul(title)) return false
        if (isNoiseTitle(title)) return false
        return true
    }

    private fun isNoiseTitle(value: String): Boolean {
        val normalized = value
            .replace(Regex("\\s+"), "")
            .lowercase()

        return normalized in setOf(
            "나무위키",
            "최근변경",
            "최근토론",
            "특수기능",
            "분류",
            "파일",
            "틀",
            "문서로가기"
        )
    }

    private fun containsHangul(value: String): Boolean =
        Regex("[가-힣]").containsMatchIn(value)
}
