package com.lilac.anime.data

import android.util.Log
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lilac.anime.Anime
import com.lilac.anime.AppContextHolder
import com.lilac.anime.Episode
import com.lilac.anime.network.LinkkfPlayerResolver
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Android implementation of the desktop 애니24 catalog and direct HLS resolver. */
class Ohli24Client {
    private val http = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true)
        .retryOnConnectionFailure(true).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()

    private fun page(url: String): JSONObject {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                val request = Request.Builder().url(url).header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7")
                    .header("Referer", "$BASE/").build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("애니24 HTTP ${response.code} (${url.substringBefore('?')})")
                    val html = response.body?.string().orEmpty()
                    val doc = Jsoup.parse(html, url)
                    val script = doc.selectFirst("script[data-page=app]")?.data()
                        ?.takeIf { it.isNotBlank() }
                        ?: doc.selectFirst("script[data-page=app]")?.html()?.takeIf { it.isNotBlank() }
                        ?: Regex("(?s)<script[^>]*data-page=[\"']app[\"'][^>]*>(.*?)</script>", RegexOption.IGNORE_CASE)
                            .find(html)?.groupValues?.getOrNull(1)
                        ?: throw IOException("애니24 페이지 데이터를 읽지 못했습니다. url=${url.substringBefore('?')}")
                    val props = JSONObject(script.trim()).optJSONObject("props")
                        ?: throw IOException("애니24 페이지에 props 데이터가 없습니다.")
                    Log.d("Ohli24Page", "PAGE_OK attempt=${attempt + 1} url=${url.substringBefore('?')}")
                    return props
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                lastError = t
                Log.w("Ohli24Page", "PAGE_RETRY attempt=${attempt + 1} url=${url.substringBefore('?')} reason=${t.message}")
                if (attempt < 2) try { Thread.sleep(250L * (attempt + 1)) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
        throw IOException("애니24 페이지 요청 실패: ${lastError?.message ?: "알 수 없는 오류"}", lastError)
    }

    fun catalog(pageNumber: Int = 1, query: String = ""): List<Anime> {
        if (query.isNotBlank()) {
            val airing = runCatching { page("$BASE/방영중").optJSONArray("items").toItems() }.getOrDefault(emptyList())
                .filter { normalize(it.title).contains(normalize(query)) }
            val finished = runCatching { board("완결", 1, query) }.getOrDefault(emptyList())
            val movies = runCatching { board("극장판", 1, query) }.getOrDefault(emptyList())
            return (airing + finished + movies).distinctBy { it.id }
        }
        return if (pageNumber <= 1) {
            page("$BASE/방영중").optJSONArray("items").toItems()
        } else board("완결", pageNumber - 1)
    }

    fun movies(pageNumber: Int = 1): List<Anime> = board("극장판", pageNumber)

    private fun board(name: String, page: Int, query: String = ""): List<Anime> {
        val url = StringBuilder("$BASE/${encPath(name)}")
        val args = mutableListOf<String>()
        if (page > 1) args += "page=$page"
        if (query.isNotBlank()) args += "keyword=${URLEncoder.encode(query.trim(), "UTF-8")}"
        if (args.isNotEmpty()) url.append('?').append(args.joinToString("&"))
        return page(url.toString()).optJSONObject("comics")?.optJSONArray("data").toItems()
    }

    fun detail(anime: Anime): Anime {
        val props = resolveDetail(anime)
        val comic = props.optJSONObject("comic") ?: throw IOException("애니24에서 작품 정보를 찾지 못했습니다.")
        val slug = comic.optString("slug").ifBlank { anime.id.removePrefix("ohli24:") }
        val info = infoRows(comic)
        val epsPage = props.optJSONObject("episodes")
        val all = mutableListOf<JSONObject>()
        epsPage?.optJSONArray("data")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(all::add) }
        val last = epsPage?.optInt("last_page", 1) ?: 1
        for (p in 2..last.coerceAtMost(100)) {
            val pageEpisodes = page("$BASE/c/${encPath(slug)}?page=$p")
                .optJSONObject("episodes")?.optJSONArray("data")
                ?: throw IOException("애니24 ${p}페이지 회차 목록이 비어 있습니다.")
            for (i in 0 until pageEpisodes.length()) pageEpisodes.optJSONObject(i)?.let(all::add)
        }
        val numbered = all.mapIndexed { index, raw ->
            val label = raw.optString("title").ifBlank { "${index + 1}화" }
            val special = SPECIAL.containsMatchIn(label)
            val number = Regex("(\\d+(?:\\.\\d+)?)\\s*화?").find(label)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            Triple(raw, label, if (special || number == null) index + 1 else number.toInt().coerceAtLeast(1))
        }.sortedWith(compareBy<Triple<JSONObject, String, Int>> { SPECIAL.containsMatchIn(it.second) }.thenBy {
            Regex("(\\d+(?:\\.\\d+)?)").find(it.second)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 999999.0
        }.thenBy { it.first.optInt("sort", 0) })
        val episodes = numbered.map { (raw, label, number) ->
            val special = SPECIAL.containsMatchIn(label)
            Episode(id = "ohli24:$slug:${raw.optString("slug", number.toString())}", number = number,
                displayNumber = if (special) label.removeSuffix("화").trim() else (Regex("(\\d+(?:\\.\\d+)?)").find(label)?.value ?: number.toString()),
                title = label, videoUrl = "$BASE/e/${encPath(raw.optString("slug"))}",
                airedDate = raw.optString("published_at").take(10))
        }.distinctBy { it.id }
        val title = comic.optString("title").ifBlank { anime.title }
        val native = comic.optString("original_title").ifBlank { info.optString("원제") }
        val thumbnail = comic.optString("thumbnail").takeIf { it.isNotBlank() }?.let { "$BASE/storage/$it" }.orEmpty()
        val genres = comic.optString("tags_string").split(',').map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { info.optString("장르").split(',').map { it.trim() }.filter { it.isNotBlank() } }
        return anime.copy(id = "ohli24:$slug", title = title, native = native, source = "ohli24",
            detailUrl = "$BASE/c/${encPath(slug)}", poster = thumbnail.ifBlank { anime.poster }, backdrop = thumbnail.ifBlank { anime.backdrop },
            description = text(comic.optString("description")).ifBlank { anime.description }, genres = genres,
            year = Regex("\\d{4}").find(info.optString("방영일"))?.value ?: anime.year,
            format = if (comic.optString("category") == "극장판") "Movie" else "TV", episodes = episodes)
    }

    suspend fun resolve(episode: Episode): LinkkfPlayerResolver.Result {
        val episodeUrl = episode.videoUrl.orEmpty()
        var pageProps: JSONObject? = null
        var pageFailure: Throwable? = null
        try {
            pageProps = withContext(Dispatchers.IO) { page(episodeUrl) }
        } catch (t: Throwable) {
            pageFailure = t
            Log.w("Ohli24Player", "EPISODE_PAGE_DIRECT_FAILED url=${episodeUrl.substringBefore('?')} reason=${t.message}")
        }
        var playingRaw = pageProps?.optJSONObject("playing")?.optJSONObject("body")?.optString("url").orEmpty()
        if (playingRaw.isBlank()) {
            // Old saved episode URLs and occasional pages without Inertia props can still expose the player iframe.
            playingRaw = withContext(Dispatchers.IO) { findPlayerFrame(episodeUrl) }.orEmpty()
        }
        val playing = when {
            playingRaw.startsWith("//") -> "https:$playingRaw"
            playingRaw.startsWith("/") -> "$BASE$playingRaw"
            else -> playingRaw
        }
        if (!playing.startsWith("http")) {
            throw IOException("애니24 영상 플레이어 주소를 찾지 못했습니다.${pageFailure?.message?.let { " 원인: $it" }.orEmpty()}")
        }

        val directFailure = try {
            withContext(Dispatchers.IO) { resolveDirect(episode, playing) }
        } catch (t: Throwable) {
            Log.w("Ohli24Player", "DIRECT_RESOLVE_FAILED episode=${episode.displayNumber} player=${playing.substringBefore('?')} reason=${t.message}", t)
            t
        }
        if (directFailure is LinkkfPlayerResolver.Result) return directFailure

        // Desktop parity: some Ohli24 mirrors only expose master.txt after their JavaScript player starts.
        // Use a hidden WebView as a fallback, but keep the faster direct getVideo request as the primary path.
        return resolveThroughWebView(episode, playing, directFailure as? Throwable)
    }

    private fun resolveDirect(episode: Episode, playing: String): LinkkfPlayerResolver.Result {
        val playerUri = android.net.Uri.parse(playing)
        val origin = "${playerUri.scheme}://${playerUri.host}" + if (playerUri.port > 0) ":${playerUri.port}" else ""
        val hash = playerUri.getQueryParameter("data")?.takeIf { it.isNotBlank() }
            ?: playerUri.lastPathSegment?.takeIf { it.isNotBlank() && !it.equals("index.php", true) }.orEmpty()
        if (hash.isBlank()) throw IOException("애니24 플레이어 식별자가 없습니다.")
        val request = Request.Builder().url("$origin/player/index.php?data=${URLEncoder.encode(hash, "UTF-8")}&do=getVideo")
            .post(FormBody.Builder().add("hash", hash).add("r", "$BASE/").build())
            .header("User-Agent", UA).header("X-Requested-With", "XMLHttpRequest")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Referer", playing).header("Origin", origin).build()
        val info = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("애니24 영상 정보 HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
        val master = info.optString("securedLink").ifBlank { info.optString("url") }
        if (!(info.optBoolean("hls") || info.optString("hls").equals("true", true)) || !master.startsWith("http")) {
            throw IOException("애니24에서 HLS 주소를 반환하지 않았습니다. 응답=${info.toString().take(240)}")
        }
        val playlist = fetchPlaylist(master, playing, emptyMap())
        val variant = chooseVariant(playlist, master) ?: throw IOException("애니24 화질 목록을 읽지 못했습니다.")
        val html = http.newCall(Request.Builder().url(playing).header("User-Agent", UA).header("Referer", "$BASE/").build())
            .execute().use { it.body?.string().orEmpty() }
        val sub = subtitleUrl(html)
        Log.d("Ohli24Player", "RESOLVED_DIRECT episode=${episode.displayNumber} subtitle=${!sub.isNullOrBlank()}")
        return LinkkfPlayerResolver.Result(episode.id, variant, playing, "User-Agent: $UA\nReferer: $playing", sub, playing, playing)
    }

    private data class CapturedPlayerRequest(val url: String, val headers: Map<String, String>)

    private suspend fun resolveThroughWebView(
        episode: Episode,
        playing: String,
        directError: Throwable?
    ): LinkkfPlayerResolver.Result {
        val captured = CompletableDeferred<CapturedPlayerRequest>()
        var webView: WebView? = null
        val mainHandler = Handler(Looper.getMainLooper())
        val nudge = object : Runnable {
            override fun run() {
                val view = webView ?: return
                if (!captured.isCompleted) {
                    view.evaluateJavascript("try{window.jwplayer&&window.jwplayer().play&&window.jwplayer().play()}catch(e){};document.querySelectorAll('video').forEach(v=>{v.muted=true;v.play().catch(()=>{})})", null)
                    mainHandler.postDelayed(this, 1800L)
                }
            }
        }
        try {
            withContext(Dispatchers.Main) {
                val view = WebView(AppContextHolder.context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = UA
                    setBackgroundColor(android.graphics.Color.BLACK)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                            val url = request?.url?.toString().orEmpty()
                            val path = android.net.Uri.parse(url).lastPathSegment.orEmpty()
                            if (!captured.isCompleted && (path.equals("master.txt", true) || path.endsWith(".m3u8", true))) {
                                captured.complete(CapturedPlayerRequest(url, request?.requestHeaders.orEmpty()))
                                Log.i("Ohli24Player", "WEBVIEW_PLAYLIST_CAPTURED episode=${episode.displayNumber} host=${android.net.Uri.parse(url).host} path=$path")
                            } else if (!captured.isCompleted && path.endsWith(".mp4", true)) {
                                captured.complete(CapturedPlayerRequest(url, request?.requestHeaders.orEmpty()))
                                Log.i("Ohli24Player", "WEBVIEW_MEDIA_CAPTURED episode=${episode.displayNumber} host=${android.net.Uri.parse(url).host}")
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript("try{window.jwplayer&&window.jwplayer().play&&window.jwplayer().play()}catch(e){};document.querySelectorAll('video').forEach(v=>{v.muted=true;v.play().catch(()=>{})})", null)
                            if (!captured.isCompleted) mainHandler.postDelayed(nudge, 900L)
                        }
                    }
                }
                webView = view
                view.loadUrl(playing, mapOf("Referer" to "$BASE/", "User-Agent" to UA))
            }
            val request = withTimeout(30_000L) { captured.await() }
            val path = android.net.Uri.parse(request.url).lastPathSegment.orEmpty()
            val requestHeaders = request.headers.filterKeys { key ->
                !key.equals("Host", true) && !key.equals("Connection", true) && !key.equals("Content-Length", true)
            }.toMutableMap()
            requestHeaders.putIfAbsent("User-Agent", UA)
            requestHeaders.putIfAbsent("Referer", playing)
            CookieManager.getInstance().getCookie(request.url)?.takeIf { it.isNotBlank() }?.let { requestHeaders.putIfAbsent("Cookie", it) }
            val headerLines = requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            if (path.endsWith(".mp4", true)) {
                Log.i("Ohli24Player", "RESOLVED_WEBVIEW_MEDIA episode=${episode.displayNumber}")
                return LinkkfPlayerResolver.Result(episode.id, request.url, playing, headerLines, null, playing, playing)
            }
            val playlist = withContext(Dispatchers.IO) { fetchPlaylist(request.url, playing, requestHeaders) }
            val variant = chooseVariant(playlist, request.url) ?: request.url
            val html = withContext(Dispatchers.IO) {
                runCatching { http.newCall(Request.Builder().url(playing).header("User-Agent", UA).header("Referer", "$BASE/").build()).execute().use { it.body?.string().orEmpty() } }.getOrDefault("")
            }
            val sub = subtitleUrl(html)
            Log.i("Ohli24Player", "RESOLVED_WEBVIEW episode=${episode.displayNumber} subtitle=${!sub.isNullOrBlank()} host=${android.net.Uri.parse(variant).host}")
            return LinkkfPlayerResolver.Result(episode.id, variant, playing, headerLines, sub, playing, playing)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) throw t
            Log.e("Ohli24Player", "WEBVIEW_FALLBACK_FAILED episode=${episode.displayNumber} direct=${directError?.message} reason=${t.message}", t)
            throw IOException("애니24 직접 추출과 브라우저 재생 감지가 모두 실패했습니다. 직접 추출: ${directError?.message ?: "알 수 없음"}; 브라우저: ${t.message ?: t.javaClass.simpleName}", t)
        } finally {
            mainHandler.removeCallbacks(nudge)
            withContext(Dispatchers.Main) {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
        }
    }

    private fun fetchPlaylist(url: String, referer: String, extraHeaders: Map<String, String>): String {
        val request = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "*/*").header("Referer", referer).apply {
            extraHeaders.forEach { (key, value) ->
                if (value.isNotBlank() && !key.equals("User-Agent", true) && !key.equals("Referer", true))
                    runCatching { header(key, value) }
            }
        }.build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("애니24 HLS HTTP ${response.code}")
            response.body?.string().orEmpty().also {
                if (!it.trimStart().removePrefix("\uFEFF").startsWith("#EXTM3U")) throw IOException("애니24 HLS 응답이 재생목록이 아닙니다.")
            }
        }
    }

    private fun chooseVariant(playlist: String, master: String): String? {
        val variants = Regex("#EXT-X-STREAM-INF:([^\\r\\n]*)\\r?\\n\\s*([^\\r\\n#][^\\r\\n]*)").findAll(playlist).mapNotNull { match ->
            val bandwidth = Regex("BANDWIDTH=(\\d+)").find(match.groupValues[1])?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            runCatching { bandwidth to java.net.URI(master).resolve(match.groupValues[2].trim()).toString() }.getOrNull()
        }.sortedByDescending { it.first }.firstOrNull()?.second
        return variants ?: master.takeIf { playlist.trimStart().startsWith("#EXTM3U") }
    }

    private fun subtitleUrl(html: String): String? = Regex("playerjsSubtitle\\s*=\\s*\"([^\"]*)\"").find(html)?.groupValues?.getOrNull(1)
        ?.let { Regex("(?:\\[[^]]*])?\\s*(https?://[^,\\s]+)").find(it)?.groupValues?.getOrNull(1) }

    private fun findPlayerFrame(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", UA).header("Referer", "$BASE/").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val doc = Jsoup.parse(response.body?.string().orEmpty(), url)
            val frame = doc.selectFirst("iframe#video, iframe[src*=cdndania], iframe[src*=michealcdn], iframe[src]")?.attr("abs:src").orEmpty()
            frame.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
    }.onFailure { Log.w("Ohli24Player", "IFRAME_FALLBACK_FAILED url=${url.substringBefore('?')} reason=${it.message}") }.getOrNull()

    private fun resolveDetail(anime: Anime): JSONObject {
        val encoded = anime.detailUrl.substringAfter("/c/", "").substringBefore('?')
        val slug = if (encoded.isNotBlank()) runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
            else anime.id.removePrefix("ohli24:")
        return try {
            page("$BASE/c/${encPath(slug)}")
        } catch (first: Throwable) {
            if (first is kotlinx.coroutines.CancellationException) throw first
            // Older saved entries can have an obsolete URL/slug. Resolve them by visible title like the desktop app.
            val query = anime.title.ifBlank { slug }
            val match = runCatching { catalog(query = query) }.getOrDefault(emptyList())
                .firstOrNull { normalize(it.title) == normalize(query) }
                ?: throw first
            val matchedSlug = match.id.removePrefix("ohli24:")
            page("$BASE/c/${encPath(matchedSlug)}")
        }
    }

    private fun infoRows(comic: JSONObject): JSONObject {
        val out = JSONObject(); val rows = comic.optJSONObject("metadata")?.optJSONArray("info_rows") ?: return out
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            if (row.length() >= 2) out.put(row.optString(0), row.optString(1))
        }
        return out
    }

    private fun JSONArrayCompatToItems(array: org.json.JSONArray?): List<Anime> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it)?.let(::itemAnime) }
    private fun org.json.JSONArray?.toItems(): List<Anime> = JSONArrayCompatToItems(this)

    private fun itemAnime(item: JSONObject): Anime {
        val slug = item.optString("slug").ifBlank { item.optString("title") }
        val info = infoRows(item)
        val thumb = item.optString("thumbnail").takeIf { it.isNotBlank() }?.let { "$BASE/storage/$it" }.orEmpty()
        val title = item.optString("title").ifBlank { slug }
        val count = item.optInt("episodes_count", Regex("\\d+").find(item.optString("eps"))?.value?.toIntOrNull() ?: 0)
        return Anime(id = "ohli24:$slug", title = title, native = item.optString("original_title").ifBlank { info.optString("원제") },
            poster = thumb, backdrop = thumb, source = "ohli24", detailUrl = "$BASE/c/${encPath(slug)}",
            year = Regex("\\d{4}").find(info.optString("방영일").ifBlank { item.optString("published_at") })?.value.orEmpty(),
            format = if (item.optString("category") == "극장판") "Movie" else "TV",
            genres = item.optString("tags_string").split(',').map { it.trim() }.filter { it.isNotBlank() },
            description = text(item.optString("description")), note = if (count > 0) "${count}화 공개" else "")
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
    private fun text(value: String) = value.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").trim()
    private fun encPath(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        const val BASE = "https://ani.ohli24.com"
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val SPECIAL = Regex("^\\s*(?:OVA|OAD|ONA|SP|SPECIAL|스페셜|특별편?|번외편?|극장판)(?![a-z])", RegexOption.IGNORE_CASE)
    }
}
