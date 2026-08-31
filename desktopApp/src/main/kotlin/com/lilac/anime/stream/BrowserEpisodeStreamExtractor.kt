package com.lilac.anime.stream

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Request
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Desktop equivalent of Android WebView.shouldInterceptRequest().
 *
 * The Android implementation does not find a hard-coded m3u8 in HTML; it
 * observes real browser network requests after JavaScript has executed.
 * Playwright provides the same kind of request observation on Desktop.
 */
class BrowserEpisodeStreamExtractor(
    private val browserExecutable: String? = findBrowserExecutable()
) : EpisodeStreamExtractor {

    override suspend fun extract(targetUrl: String): EpisodeStreamInfo {
        require(targetUrl.isNotBlank()) { "Target URL is empty" }

        if (targetUrl.contains(".m3u8", ignoreCase = true)) {
            return EpisodeStreamInfo(
                qualities = M3u8QualityResolver.resolve(
                    listOf(targetUrl)
                ),
                subtitleUrl = null
            )
        }

        Playwright.create().use { playwright ->
            val launchOptions = BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(
                    listOf(
                        "--autoplay-policy=no-user-gesture-required",
                        "--disable-blink-features=AutomationControlled"
                    )
                )

            if (!browserExecutable.isNullOrBlank()) {
                launchOptions.setExecutablePath(Path.of(browserExecutable))
            }

            val browser = playwright.chromium().launch(launchOptions)
            try {
                return extractWithBrowser(browser, targetUrl)
            } finally {
                browser.close()
            }
        }
    }

    private fun extractWithBrowser(
        browser: Browser,
        targetUrl: String
    ): EpisodeStreamInfo {
        val context = browser.newContext(
            Browser.NewContextOptions()
                .setUserAgent(USER_AGENT)
                .setIgnoreHTTPSErrors(true)
        )

        try {
            val m3u8 = LinkedHashMap<String, Map<String, String>>()
            var subtitleUrl: String? = null
            var subtitleHeaders: Map<String, String> = emptyMap()

            val requestListener: (Request) -> Unit = { request ->
                val url = request.url()
                val lower = url.lowercase()

                if (lower.contains(".m3u8") && !lower.contains("ad")) {
                    m3u8.putIfAbsent(url, request.allHeaders())
                }

                if (subtitleUrl == null && lower.contains(".vtt")) {
                    subtitleUrl = url
                    subtitleHeaders = request.allHeaders()
                }
            }

            context.onRequest(requestListener)

            val page = context.newPage()
            page.onPageError { /* Some player scripts fail harmlessly. Keep observing requests. */ }

            page.navigate(
                targetUrl,
                Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30_000.0)
            )

            // The Android WebView implementation simply loads the page and waits
            // for requests. Give JS players time to initialize and request media.
            runCatching {
                page.evaluate(
                    """
                    () => {
                      for (const video of document.querySelectorAll('video')) {
                        video.muted = true;
                        const p = video.play();
                        if (p) p.catch(() => {});
                      }
                    }
                    """.trimIndent()
                )
            }

            val deadline = System.currentTimeMillis() + REQUEST_WAIT_MS
            while (System.currentTimeMillis() < deadline && m3u8.isEmpty()) {
                Thread.sleep(250)
            }

            // VTT may be requested slightly after the video manifest.
            if (subtitleUrl == null) {
                val subtitleDeadline = System.currentTimeMillis() + SUBTITLE_EXTRA_WAIT_MS
                while (System.currentTimeMillis() < subtitleDeadline && subtitleUrl == null) {
                    Thread.sleep(250)
                }
            }

            val streams = m3u8.entries.map { (url, headers) ->
                StreamQuality(
                    label = if (url.contains("/sd/")) "720p" else "1080p",
                    url = url,
                    headers = headers
                )
            }

            return EpisodeStreamInfo(
                qualities = numberDuplicateQualities(streams),
                subtitleUrl = subtitleUrl,
                subtitleHeaders = subtitleHeaders
            )
        } finally {
            context.close()
        }
    }

    private fun numberDuplicateQualities(
        streams: List<StreamQuality>
    ): List<StreamQuality> = streams
        .groupBy { it.label }
        .flatMap { (label, sameQuality) ->
            if (sameQuality.size > 1) {
                sameQuality.mapIndexed { index, stream ->
                    stream.copy(label = "$label #${index + 1}")
                }
            } else {
                sameQuality
            }
        }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"

        private const val REQUEST_WAIT_MS = 15_000L
        private const val SUBTITLE_EXTRA_WAIT_MS = 3_000L

        private fun findBrowserExecutable(): String? {
            val env = System.getenv("LILAC_CHROMIUM_PATH")
                ?: System.getenv("CHROME_PATH")
            if (!env.isNullOrBlank() && Files.isExecutable(Path.of(env))) {
                return env
            }

            val candidates = buildList {
                when {
                    System.getProperty("os.name").lowercase().contains("windows") -> {
                        add(System.getenv("PROGRAMFILES")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" })
                        add(System.getenv("PROGRAMFILES(X86)")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" })
                    }
                }

                add("/usr/bin/google-chrome")
                add("/usr/bin/google-chrome-stable")
                add("/usr/bin/chromium")
                add("/usr/bin/chromium-browser")
                add("/snap/bin/chromium")
                add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
            }.filterNotNull()

            return candidates.firstOrNull { Path.of(it).exists() && Files.isExecutable(Path.of(it)) }
        }
    }
}
