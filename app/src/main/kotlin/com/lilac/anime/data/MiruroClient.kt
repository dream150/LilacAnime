package com.lilac.anime.data

import android.util.Log
import com.lilac.anime.Anime
import com.lilac.anime.Episode
import com.lilac.anime.network.CloudflareChallengeException
import com.lilac.anime.network.CloudflareChallengeRequired
import com.lilac.anime.network.CloudflareSession
import com.lilac.anime.network.CloudflareWebFetcher
import com.lilac.anime.network.LinkkfPlayerResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Miruro 재생 어댑터 (파이프 우선 + 구버전 REST 폴백).
 *
 * 2026-11 실증 분석(공식 도메인 공지 + 리버스엔지니어링 구현 2종 교차 검증 + 4개 도메인 live 테스트) 결과:
 *  - 공식 도메인은 www.miruro.cx(파트너 anivault.tv). 구 도메인(miruro.to)은 단계적 종료 중.
 *  - 사이트 프론트엔드는 GET {host}/api/secure/pipe?e={base64url(JSON)} 단일 엔드포인트로 에피소드/소스를
 *    가져온다. 요청 {path:"episodes"|"sources", method:"GET", query, body, version:"0.1.0"},
 *    응답은 base64url → (x-obfuscated:2 면 XOR) → gzip → JSON.
 *  - Cloudflare 가 데이터센터 IP 의 pipe 호출을 전부 차단한다(실측 403). 앱은 기기의 일반 IP +
 *    CloudflareSession(WebView 인증)의 cf_clearance 쿠키로 통과하며, WAF 를 피하려면 브라우저
 *    헤더 스택(sec-ch-ua / sec-fetch-* / Origin)을 같이 보내야 한다.
 *  - 카탈로그/검색/상세는 AniList GraphQL(사이트와 동일 데이터 소스)로 대체한다.
 *  - 구 데스크톱 어댑터의 GET {host}/api/v1/anime... REST 엔드포인트는 현재 사이트에 존재하지 않아
 *    파이프 실패 시에만 시도하는 레거시 폴백으로 강등했다(구버전 시청 기록 호환용).
 */
class MiruroClient {
    private val http = OkHttpClient.Builder()
        .cookieJar(CloudflareSession.cookieJar) // WebView 와 쿠키(cf_clearance) 공유
        .followRedirects(true).retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).build()

    private class RawReply(val code: Int, val mitigated: String?, val server: String?, val bytes: ByteArray)

    /** OkHttp 로 보내되, Cloudflare 때문에 OkHttp 가 통과하지 못한 뒤에는 WebView fetch 로 보낸다. */
    private fun send(request: Request): RawReply {
        if (CloudflareSession.useWebViewFetch) {
            val r = CloudflareWebFetcher.fetch(request.url.toString())
            return RawReply(r.status, r.mitigated, r.server, r.body)
        }
        return http.newCall(request).execute().use { r ->
            RawReply(r.code, r.header("cf-mitigated"), r.header("Server"), r.body?.bytes() ?: byteArrayOf())
        }
    }

    /** 챌린지 해결/WebView 전환 재시도를 포함한 공통 fetch. 성공 시 JSONObject 로 파싱해 반환한다. */
    private fun fetchJson(url: String, referer: String, tag: String, decode: (ByteArray) -> String): JSONObject {
        // Cloudflare WAF 는 브라우저 헤더가 빠진 API 직접 호출을 차단한다. 사이트 프론트엔드가
        // 보내는 fetch 헤더 스택(Origin, sec-fetch-*, sec-ch-ua)을 UA 와 일관되게 재현한다.
        val uri = android.net.Uri.parse(url)
        val origin = "${uri.scheme}://${uri.host}" + if (uri.port > 0) ":${uri.port}" else ""
        val ua = CloudflareSession.userAgent
        val chromeVersion = Regex("Chrome/(\\d+)").find(ua)?.groupValues?.getOrNull(1)
        val request = Request.Builder().url(url).header("User-Agent", ua)
            .header("Accept", "*/*").header("Referer", referer)
            .header("Origin", origin)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-origin")
            .apply {
                if (chromeVersion != null) {
                    header("sec-ch-ua", "\"Chromium\";v=\"$chromeVersion\", \"Google Chrome\";v=\"$chromeVersion\", \"Not?A_Brand\";v=\"24\"")
                    header("sec-ch-ua-mobile", if (ua.contains("Android", true) || ua.contains("Mobile", true)) "?1" else "?0")
                    header("sec-ch-ua-platform", when {
                        ua.contains("Android", true) -> "\"Android\""
                        ua.contains("Windows", true) -> "\"Windows\""
                        ua.contains("iPhone", true) || ua.contains("iPad", true) -> "\"iOS\""
                        else -> "\"Linux\""
                    })
                }
            }
            .build()
        var lastError: Throwable? = null
        var cfSolved = false
        var attempt = 0
        while (attempt < 3) {
            attempt++
            try {
                val reply = send(request)
                val bytes = reply.bytes
                if (CloudflareSession.isChallenge(reply.code, reply.mitigated, reply.server, bytes)) {
                    throw CloudflareChallengeRequired()
                }
                if (reply.code !in 200..299) {
                    val detail = bytes.toString(Charsets.UTF_8).replace(Regex("[\\r\\n\\t]+"), " ").take(240)
                    Log.e(tag, "HTTP_ERROR url=$url status=${reply.code} bytes=${bytes.size} body=$detail")
                    throw IOException("HTTP ${reply.code}${if (detail.isNotBlank()) ": $detail" else ""}")
                }
                val json = JSONObject(decode(bytes))
                Log.d(tag, "OK url=$url attempt=$attempt via=${if (CloudflareSession.useWebViewFetch) "webview" else "okhttp"} bytes=${bytes.size}")
                return json
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (t is CloudflareChallengeRequired) {
                    if (!cfSolved) {
                        Log.w(tag, "CLOUDFLARE_CHALLENGE -> 인증창 표시")
                        CloudflareSession.solve(url) // 실패 시 CloudflareChallengeException 전파
                        cfSolved = true
                        attempt-- // 인증은 재시도 횟수에서 제외
                        continue
                    }
                    if (!CloudflareSession.useWebViewFetch) {
                        // 인증은 됐지만 OkHttp(TLS 지문/헤더)는 계속 막힘 -> WebView 로 직접 요청
                        Log.w(tag, "OKHTTP_STILL_BLOCKED -> WebView fetch 로 전환")
                        CloudflareSession.useWebViewFetch = true
                        attempt--
                        continue
                    }
                    throw CloudflareChallengeException("Cloudflare 인증 후에도 요청이 차단되었습니다(WebView 요청 포함).")
                }
                lastError = t
                Log.w(tag, "RETRY url=$url attempt=$attempt: ${t.message}")
                if (attempt < 3) try { Thread.sleep(300L * attempt) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
        throw IOException("Miruro 요청 실패: ${lastError?.message ?: "알 수 없는 오류"}", lastError)
    }

    // =====================================================================
    // REST 모드 (api/v1) — 기존 데스크톱 어댑터 호환
    // =====================================================================

    private fun apiUrl(host: String, path: String, params: Map<String, String>): String =
        okhttp3.HttpUrl.Builder().scheme("https").host(host)
            .addPathSegments("api/v1/${path.trimStart('/')}").apply {
                params.forEach { (key, value) -> if (value.isNotBlank()) addQueryParameter(key, value) }
            }.build().toString()

    private fun api(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        var lastError: Throwable? = null
        for (host in hostsFor(restHost)) {
            val url = apiUrl(host, path, params)
            try {
                // same-origin 헤더 일관성: Referer/Origin 은 요청 host 와 같아야 한다(미러 로테이션 대비).
                val json = fetchJson(url, "https://$host/", "MiruroApi", ::decodeApiBody)
                restHost = host
                return json
            } catch (t: Throwable) {
                if (t is CloudflareChallengeException) throw t
                lastError = t
                Log.w("MiruroApi", "HOST_FAILED host=$host path=$path err=${t.message}")
            }
        }
        throw IOException("Miruro API 요청 실패: ${lastError?.message ?: "알 수 없는 오류"}", lastError)
    }

    /** Supports the site's usual XOR+gzip response and plain JSON/gzip responses. */
    private fun decodeApiBody(input: ByteArray): String {
        fun isGzip(bytes: ByteArray) = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        fun decode(bytes: ByteArray): String {
            val payload = if (isGzip(bytes)) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
            return payload.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        }
        if (input.isEmpty()) throw IOException("Miruro 응답 본문이 비어 있습니다.")
        val key = "miruro/catalog".toByteArray(Charsets.UTF_8)
        val xor = input.copyOf()
        for (i in xor.indices) xor[i] = (xor[i].toInt() xor key[i % key.size].toInt()).toByte()
        for (candidate in listOf(input, xor)) {
            try {
                val text = decode(candidate)
                if (text.startsWith("{") || text.startsWith("[")) return text
            } catch (_: Exception) { }
        }
        throw IOException("Miruro 응답을 JSON으로 해독하지 못했습니다.")
    }

    private fun restCatalog(query: String = "", cursor: String = ""): Pair<List<Anime>, String?> {
        val params = linkedMapOf("limit" to "15")
        if (query.isNotBlank()) { params["q"] = query.trim(); params["sort"] = "-popularity" }
        else params["sort"] = "-popularity"
        if (cursor.isNotBlank()) params["cursor"] = cursor
        val root = api("anime", params)
        val data = root.optJSONArray("data") ?: root.optJSONArray("results") ?: root.optJSONArray("items")
            ?: root.optJSONObject("data")?.optJSONArray("items")
        val items = (0 until (data?.length() ?: 0)).mapNotNull { i -> data?.optJSONObject(i)?.let(::anime) }
        val nextCursor = root.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
            ?: root.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" }
        val next = nextCursor.takeIf { root.optBoolean("has_more", root.optBoolean("hasMore", nextCursor != null)) }
        return items to next
    }

    private fun restDetail(anime: Anime): Anime {
        val rawId = anime.id.removePrefix("miruro:")
        val root = api("anime/${rawId}")
        val parsed = anime(root).copy(id = anime.id, source = "miruro", detailUrl = "$BASE/watch/$rawId")
        val episodeRoot = api("anime/$rawId/episodes", mapOf("limit" to "10000"))
        val rawEpisodes = episodeRoot?.optJSONArray("data") ?: episodeRoot?.optJSONArray("episodes")
            ?: episodeRoot?.optJSONObject("data")?.optJSONArray("items")
        val kind = if (root.optString("format").equals("MOVIE", true)) "film" else "regular"
        val matching = (0 until (rawEpisodes?.length() ?: 0)).mapNotNull { rawEpisodes?.optJSONObject(it) }
        val selected = matching.filter { it.optString("kind").equals(kind, true) }.ifEmpty { matching }
        val counts = root.optJSONObject("episode_counts")
        val episodeLimits = listOfNotNull(
            counts?.takeIf { it.has("raw") && !it.isNull("raw") }?.optInt("raw"),
            root.takeIf { it.has("episode_count") && !it.isNull("episode_count") }?.optInt("episode_count")
        )
        val rawLimit = episodeLimits.minOrNull() ?: Int.MAX_VALUE
        val now = System.currentTimeMillis()
        // pipe 폴백 재생에 필요한 AniList ID 힌트를 URL 에 싣는다(사이트는 무시하는 파라미터).
        val alHint = anime.anilistId?.let { "&al=$it" }.orEmpty()
        val episodes = selected.mapNotNull { e ->
            val n = e.optInt("episode_number", 0)
            if (n <= 0 || n > rawLimit) return@mapNotNull null
            // Match the desktop's playable-episode rules: don't mark future episodes
            // as playable when the API doesn't expose a raw broadcast count.
            val hasRawCount = counts?.has("raw") == true && !counts.isNull("raw")
            val releasedCount = maxOf(counts?.optInt("sub", 0) ?: 0, counts?.optInt("dub", 0) ?: 0)
            val aired = e.optString("aired_on").takeIf { it.isNotBlank() && it != "null" }
                ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            val notYetReleased = !hasRawCount && n > releasedCount &&
                (aired?.let { it > now } ?: root.optString("status").equals("NOT_YET_RELEASED", true))
            val title = e.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: "Episode $n"
            Episode(id = "miruro:$rawId:$n", number = n, title = title,
                videoUrl = "$BASE/watch/$rawId?ep=$n$alHint", airedDate = e.optString("aired_on"),
                isFiller = e.optString("canon_type") == "filler", isRecap = e.optString("canon_type") == "recap",
                playable = !notYetReleased, thumbnailUrl = e.optString("thumbnail_url"))
        }.distinctBy { it.number }.sortedBy { it.number }
        return parsed.copy(episodes = episodes)
    }

    private fun restResolve(episode: Episode): LinkkfPlayerResolver.Result {
        val uri = android.net.Uri.parse(episode.videoUrl.orEmpty())
        val animeId = uri.lastPathSegment?.takeIf { it.isNotBlank() && it != "watch" }
            ?: episode.id.removePrefix("miruro:").substringBefore(':').takeIf { it.isNotBlank() }
            ?: return LinkkfPlayerResolver.Result(episode.id, null, null, null)
        val number = uri.getQueryParameter("ep")?.toIntOrNull() ?: episode.number
        Log.i("MiruroPlayer", "RESOLVE_START episode=$number animeId=$animeId page=${episode.videoUrl?.substringBefore('?')}")
        val responseRoot = api("anime/$animeId/episodes/$number/play")
        val root = responseRoot.optJSONObject("data") ?: responseRoot
        val tracks = root.optJSONArray("tracks")
        Log.i("MiruroPlayer", "PLAY_RESPONSE episode=$number keys=${root.keys().asSequence().take(16).joinToString(",")} tracks=${tracks?.length() ?: 0}")
        data class Candidate(
            val url: String,
            val headers: JSONObject,
            val subtitle: String?,
            val rank: Int,
            val provider: String,
            val kind: String
        )
        val candidates = mutableListOf<Candidate>()
        val providerOrder = listOf("anikoto", "kickassanime", "icarus", "aniwaves")
        for (i in 0 until (tracks?.length() ?: 0)) {
            val track = tracks?.optJSONObject(i) ?: continue
            val trackName = track.optString("track").lowercase(Locale.ROOT)
            val kind = when (trackName) {
                "raw" -> 0
                "ssub", "soft", "softsub", "soft-sub" -> 1
                "sub", "hardsub", "hard-sub" -> 2
                "dub" -> 3
                else -> {
                    Log.d("MiruroPlayer", "TRACK_SKIPPED episode=$number track=$trackName")
                    continue
                }
            }
            val providers = track.optJSONArray("providers") ?: continue
            for (j in 0 until providers.length()) {
                val provider = providers.optJSONObject(j) ?: continue
                val subtitles = provider.optJSONArray("subtitles")
                var koreanSubtitle: String? = null
                var hasEnglishSubtitle = false
                var hasAnySubtitle = false
                for (k in 0 until (subtitles?.length() ?: 0)) {
                    val sub = subtitles?.optJSONObject(k) ?: continue
                    val lang = (sub.optString("language") + " " + sub.optString("label")).lowercase(Locale.ROOT)
                    val file = sub.optString("file")
                    if (file.startsWith("https://")) hasAnySubtitle = true
                    if ((lang.startsWith("en") || lang.contains("english")) && !lang.contains("forced") && file.startsWith("https://")) hasEnglishSubtitle = true
                    if ((lang.contains("ko") || lang.contains("korean") || lang.contains("한국어")) && file.startsWith("https://")) {
                        koreanSubtitle = file
                    }
                }
                // The desktop player treats an ssub stream without subtitle files as SUB (often English is burned in).
                val effectiveKind = if (kind == 1 && !hasAnySubtitle) 2 else kind
                val servers = provider.optJSONArray("servers") ?: continue
                for (serverIndex in 0 until servers.length()) {
                    val server = servers.optJSONObject(serverIndex) ?: continue
                    val streams = server.optJSONArray("streams") ?: continue
                    val headers = JSONObject((server.optJSONObject("headers") ?: JSONObject()).toString())
                    val referer = headers.optString("Referer").ifBlank { headers.optString("referer") }
                    if (referer.isNotBlank() && !headers.has("Origin") && !headers.has("origin")) runCatching {
                        val refUri = android.net.Uri.parse(referer)
                        headers.put("Origin", "${refUri.scheme}://${refUri.host}" + if (refUri.port > 0) ":${refUri.port}" else "")
                    }
                    for (streamIndex in 0 until streams.length()) {
                        val stream = streams.optJSONObject(streamIndex) ?: continue
                        val url = stream.optString("url")
                        val format = stream.optString("format", stream.optString("type")).lowercase(Locale.ROOT)
                        val normalizedFormat = format.lowercase(Locale.ROOT)
                        val isHls = normalizedFormat in setOf("hls", "m3u8", "application/vnd.apple.mpegurl", "application/x-mpegurl") ||
                            url.substringBefore('?').endsWith(".m3u8", true)
                        if (isHls && url.startsWith("https://")) {
                            val providerName = provider.optString("provider", provider.optString("name", "unknown"))
                            val serverName = server.optString("server", server.optString("name", "server-$serverIndex"))
                            val baseRank = when {
                                koreanSubtitle != null && effectiveKind <= 1 -> 50
                                effectiveKind == 1 && hasEnglishSubtitle -> 100
                                effectiveKind == 2 -> 200
                                effectiveKind == 0 || effectiveKind == 1 -> 300
                                else -> 400
                            }
                            val providerRank = providerOrder.indexOf(providerName.lowercase(Locale.ROOT)).let { if (it < 0) providerOrder.size else it }
                            candidates += Candidate(url, JSONObject(headers.toString()), koreanSubtitle,
                                baseRank + providerRank, providerName, when (effectiveKind) { 0 -> "raw"; 1 -> "soft"; 2 -> "sub"; else -> "dub" })
                            Log.d("MiruroPlayer", "CANDIDATE episode=$number track=$trackName kind=${candidates.last().kind} provider=$providerName server=$serverName rank=${candidates.last().rank} englishSub=$hasEnglishSubtitle koreanSub=${koreanSubtitle != null} format=$normalizedFormat host=${android.net.Uri.parse(url).host}")
                        } else {
                            Log.d("MiruroPlayer", "STREAM_SKIPPED episode=$number track=$trackName format=$normalizedFormat hasUrl=${url.isNotBlank()}")
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            Log.e("MiruroPlayer", "NO_CANDIDATES episode=$number tracks=${tracks?.length() ?: 0}")
            throw IOException("Miruro에서 이 회차의 영상 서버 목록을 찾지 못했습니다. 설정에서 진단 로그를 추출해 주세요.")
        }

        // The desktop adapter tests each playlist and skips stale/dead mirrors; returning the first URL alone
        // frequently selects a server that exists in the API response but currently returns 403/HTML.
        var selected: Candidate? = null
        var failed = 0
        for (candidate in candidates.sortedBy { it.rank }) {
            try {
                val builder = Request.Builder().url(candidate.url).get()
                    .header("User-Agent", CloudflareSession.userAgent).header("Accept", "*/*")
                val keys = candidate.headers.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = candidate.headers.optString(key)
                    if (value.isNotBlank() && !key.equals("User-Agent", true)) builder.header(key, value)
                }
                val valid = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
                    .newCall(builder.build()).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        val playlist = response.isSuccessful && body.trimStart().removePrefix("\uFEFF").startsWith("#EXTM3U")
                        Log.d("MiruroPlayer", "PLAYLIST_PROBE episode=$number host=${android.net.Uri.parse(candidate.url).host} status=${response.code} contentType=${response.header("Content-Type")} bytes=${body.length} playlist=$playlist preview=${body.trimStart().take(32).replace(Regex("[\r\n]+"), " ")}")
                        playlist
                    }
                if (valid) { selected = candidate; break }
                failed++
                Log.w("MiruroPlayer", "SERVER_REJECTED episode=$number host=${android.net.Uri.parse(candidate.url).host}")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                failed++
                Log.w("MiruroPlayer", "SERVER_FAILED episode=$number reason=${t.message}")
            }
        }
        val best = selected ?: run {
            Log.e("MiruroPlayer", "NO_PLAYABLE_SERVER episode=$number candidates=${candidates.size} failed=$failed")
            throw IOException("Miruro 영상 서버 ${candidates.size}개를 확인했지만 재생목록에 접근할 수 없습니다. 설정에서 로그 추출을 눌러 MiruroPlayer 로그를 보내 주세요.")
        }
        val referer = best.headers.optString("Referer").ifBlank { best.headers.optString("referer") }.ifBlank { "$BASE/" }
        val headers = JSONObject(best.headers.toString()).apply {
            if (!has("User-Agent") && !has("user-agent")) put("User-Agent", CloudflareSession.userAgent)
            // 영상 URL 이 miruro 도메인이면 Cloudflare 쿠키도 함께 전달 (구 도메인 .to 도 인정)
            if (!has("Cookie") && !has("cookie") && isMiruroHost(android.net.Uri.parse(best.url).host))
                CloudflareSession.cookieHeader(best.url)?.let { put("Cookie", it) }
            if (!has("Referer") && !has("referer")) put("Referer", referer)
        }.toString()
        Log.i("MiruroPlayer", "RESOLVED episode=$number provider=${best.provider} kind=${best.kind} candidates=${candidates.size} failed=$failed subtitle=${best.subtitle != null} host=${android.net.Uri.parse(best.url).host}")
        val headerLines = JSONObject(headers).let { json ->
            val keys = json.keys(); val lines = mutableListOf<String>()
            while (keys.hasNext()) { val key = keys.next(); val value = json.optString(key); if (value.isNotBlank()) lines += "$key: $value" }
            lines.joinToString("\n")
        }
        return LinkkfPlayerResolver.Result(episode.id, best.url, referer, headerLines, best.subtitle, referer, episode.videoUrl)
    }

    private fun anime(raw: JSONObject): Anime {
        val title = raw.optJSONObject("title") ?: JSONObject()
        val id = raw.optString("id")
        val genres = raw.optJSONArray("genres")
        val count = raw.optJSONObject("episode_counts")
        var available = 0
        if (count != null) for (key in listOf("raw", "sub", "dub")) if (!count.isNull(key)) available = maxOf(available, count.optInt(key))
        val external = raw.optJSONObject("external_ids")
        fun externalId(key: String): Int? = external?.optJSONArray(key)?.optInt(0)?.takeIf { it > 0 }
        return Anime(id = "miruro:$id", anilistId = externalId("anilist"), malId = externalId("mal"),
            title = title.optString("english").takeIf { it.isNotBlank() && it != "null" }
                ?: title.optString("romaji").takeIf { it.isNotBlank() && it != "null" }
                ?: title.optString("native").ifBlank { id },
            native = title.optString("native"), romaji = title.optString("romaji"),
            poster = raw.optString("cover_url"), backdrop = raw.optString("cover_url"),
            genres = (0 until (genres?.length() ?: 0)).mapNotNull { genres?.optString(it)?.takeIf(String::isNotBlank) },
            description = htmlText(raw.optString("description")), year = raw.optString("season_year"), season = raw.optString("season"),
            format = raw.optString("format"), airedDate = raw.optString("started_on"), source = "miruro",
            detailUrl = "$BASE/watch/$id", episodes = emptyList(), note = if (available > 0) "${available}화 공개" else "")
    }

    // =====================================================================
    // 파이프 모드 (AniList GraphQL + /api/secure/pipe) — 폴백
    // =====================================================================

    /** base64url 무패딩 인코더(android.util.Base64 는 API 레벨 제약이 없다). */
    private fun b64Url(payload: ByteArray): String =
        android.util.Base64.encodeToString(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

    private fun b64UrlDecode(value: String): ByteArray =
        android.util.Base64.decode(value, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

    private fun isGzip(bytes: ByteArray) = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /**
     * pipe 응답 해독. 사이트는 x-obfuscated 헤더 값에 따라 세 가지 형태 중 하나로 응답한다:
     *  1) 평문 JSON (obfuscation 미적용)
     *  2) base64url → gzip → JSON
     *  3) base64url → XOR(공개된 obfuscation 키) → gzip → JSON  (x-obfuscated: 2)
     * WebView fetch 경로에서는 응답 헤더를 볼 수 없으므로 마법 바이트로 판별한다.
     */
    private fun decodePipeBody(input: ByteArray): String {
        if (input.isEmpty()) throw IOException("Miruro pipe 응답이 비어 있습니다.")
        val text = input.toString(Charsets.UTF_8).trim()
        if (text.startsWith("{") || text.startsWith("[")) return text
        val raw = try {
            b64UrlDecode(text)
        } catch (_: IllegalArgumentException) {
            throw IOException("Miruro pipe 응답이 base64 가 아닙니다(형식 변경 의심).")
        }
        val payload = if (isGzip(raw)) raw else run {
            val xored = ByteArray(raw.size) { i -> (raw[i].toInt() xor PIPE_OBF_KEY[i % PIPE_OBF_KEY.size].toInt()).toByte() }
            if (isGzip(xored)) xored else raw
        }
        return try {
            GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            throw IOException("Miruro pipe 응답을 해독하지 못했습니다(gzip/XOR 형식 변경 의심).")
        }
    }

    private fun pipeUrl(host: String, payload: JSONObject): String {
        val e = b64Url(payload.toString().toByteArray(Charsets.UTF_8))
        return okhttp3.HttpUrl.Builder().scheme("https").host(host)
            .addPathSegments("api/secure/pipe").addQueryParameter("e", e).build().toString()
    }

    private fun pipe(payload: JSONObject): JSONObject {
        var lastError: Throwable? = null
        for (host in hostsFor(pipeHost)) {
            val url = pipeUrl(host, payload)
            try {
                // same-origin 헤더 일관성: Referer/Origin 은 요청 host 와 같아야 한다(미러 로테이션 대비).
                val json = fetchJson(url, "https://$host/", "MiruroPipe", ::decodePipeBody)
                pipeHost = host
                return json
            } catch (t: Throwable) {
                if (t is CloudflareChallengeException) throw t
                lastError = t
                Log.w("MiruroPipe", "HOST_FAILED host=$host path=${payload.optString("path")} err=${t.message}")
            }
        }
        throw IOException("Miruro pipe 요청 실패: ${lastError?.message ?: "알 수 없는 오류"}", lastError)
    }

    private fun pipeEpisodesPayload(anilistId: Int): JSONObject = JSONObject()
        .put("path", "episodes").put("method", "GET")
        .put("query", JSONObject().put("anilistId", anilistId))
        .put("body", JSONObject.NULL).put("version", "0.1.0")

    private fun pipeSourcesPayload(anilistId: Int, provider: String, category: String, episodeId: String): JSONObject =
        JSONObject().put("path", "sources").put("method", "GET")
            .put("query", JSONObject()
                .put("episodeId", b64Url(episodeId.toByteArray(Charsets.UTF_8)))
                .put("provider", provider).put("category", category).put("anilistId", anilistId))
            .put("body", JSONObject.NULL).put("version", "0.1.0")

    /** pipe 가 주는 episode id 는 base64url("provider:원래id") 형태다. */
    private fun decodePipeId(value: String): String = runCatching {
        val decoded = String(b64UrlDecode(value), Charsets.UTF_8)
        if (decoded.contains(':')) decoded else value
    }.getOrDefault(value)

    /** 에피소드 목록은 resolve 에서도 재사용하므로 세션 캐시(10분)에 둔다. */
    private fun fetchPipeEpisodes(anilistId: Int): JSONObject {
        synchronized(pipeEpisodesCache) {
            val hit = pipeEpisodesCache[anilistId]
            if (hit != null && System.currentTimeMillis() - hit.second < 10 * 60_000L) return hit.first
        }
        val root = pipe(pipeEpisodesPayload(anilistId))
        synchronized(pipeEpisodesCache) {
            if (pipeEpisodesCache.size > 24) pipeEpisodesCache.clear()
            pipeEpisodesCache[anilistId] = root to System.currentTimeMillis()
        }
        return root
    }

    private fun anilistQuery(query: String, variables: JSONObject): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables)
        val request = Request.Builder().url(ANILIST_URL)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .header("User-Agent", CloudflareSession.userAgent)
            .header("Accept", "application/json").build()
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("AniList HTTP ${r.code}: ${text.take(160)}")
            val json = JSONObject(text)
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) throw IOException("AniList 오류: ${errors.optJSONObject(0)?.optString("message")}")
            return json.optJSONObject("data") ?: JSONObject()
        }
    }

    private fun aniListPageQuery(search: Boolean): String {
        val mediaArgs = if (search) "search:\$search," else ""
        return "query(\$page:Int${if (search) ",\$search:String" else ""}){Page(page:\$page,perPage:15){" +
            "pageInfo{hasNextPage}media(type:ANIME,${mediaArgs}isAdult:false,sort:${if (search) "SEARCH_MATCH" else "TRENDING_DESC"}){" +
            "id idMal title{romaji english native} coverImage{extraLarge large} bannerImage genres description" +
            "season seasonYear format startDate{year month day} episodes}}}"
    }

    /** org.json 의 optString 은 JSON null 을 "null" 문자열로 돌려주므로 한 번에 정리한다. */
    private fun jstr(obj: JSONObject, key: String): String =
        obj.optString(key).takeIf { it.isNotBlank() && it != "null" }.orEmpty()

    private fun animeFromAniList(raw: JSONObject): Anime? {
        val id = raw.optInt("id", 0)
        if (id <= 0) return null
        val title = raw.optJSONObject("title") ?: JSONObject()
        val start = raw.optJSONObject("startDate")
        val startYear = start?.optInt("year", 0) ?: 0
        val startMonth = start?.optInt("month", 0) ?: 0
        val startDay = start?.optInt("day", 0) ?: 0
        val airedDate = if (startYear > 0) "%04d-%02d-%02d".format(startYear, startMonth.coerceAtLeast(1), startDay.coerceAtLeast(1)) else ""
        val genres = raw.optJSONArray("genres")
        val episodeCount = raw.optInt("episodes", 0)
        val cover = raw.optJSONObject("coverImage")
        val poster = jstr(cover ?: JSONObject(), "extraLarge").ifBlank { jstr(cover ?: JSONObject(), "large") }
        val banner = jstr(raw, "bannerImage")
        return Anime(id = "miruro:$id", anilistId = id, malId = raw.optInt("idMal", 0).takeIf { it > 0 },
            title = jstr(title, "english").ifBlank { jstr(title, "romaji").ifBlank { jstr(title, "native").ifBlank { id.toString() } } },
            native = jstr(title, "native"), romaji = jstr(title, "romaji"), english = jstr(title, "english"),
            poster = poster, backdrop = banner.ifBlank { poster },
            genres = (0 until (genres?.length() ?: 0)).mapNotNull { genres?.optString(it)?.takeIf(String::isNotBlank) },
            description = htmlText(jstr(raw, "description")),
            year = raw.optInt("seasonYear", 0).takeIf { it > 0 }?.toString().orEmpty(),
            season = jstr(raw, "season"), format = jstr(raw, "format"), airedDate = airedDate, source = "miruro",
            detailUrl = "$BASE/watch/$id", episodes = emptyList(),
            note = if (episodeCount > 0) "${episodeCount}화 공개" else "")
    }

    private fun pipeCatalog(query: String, cursor: String): Pair<List<Anime>, String?> {
        val page = cursor.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val search = query.isNotBlank()
        val variables = JSONObject().put("page", page)
        if (search) variables.put("search", query.trim())
        val data = anilistQuery(aniListPageQuery(search), variables)
        val pageObj = data.optJSONObject("Page")
        val media = pageObj?.optJSONArray("media")
        val items = (0 until (media?.length() ?: 0)).mapNotNull { media?.optJSONObject(it)?.let(::animeFromAniList) }
        val next = if (pageObj?.optJSONObject("pageInfo")?.optBoolean("hasNextPage") == true) (page + 1).toString() else null
        Log.d("MiruroCatalog", "PIPE_PAGE_LOADED page=$page items=${items.size} next=$next")
        return items to next
    }

    private fun pipeDetail(anime: Anime): Anime {
        val anilistId = anime.anilistId
            ?: anime.id.removePrefix("miruro:").takeIf { it.matches(Regex("\\d+")) }?.toIntOrNull()
            ?: throw IOException("Miruro(pipe): AniList ID 를 알 수 없는 항목입니다(${anime.id}).")
        Log.i("MiruroPlayer", "PIPE_DETAIL anilistId=$anilistId title=${anime.title}")
        val root = fetchPipeEpisodes(anilistId)
        val providers = root.optJSONObject("providers") ?: JSONObject()
        data class PipeEp(val provider: String, val category: String, val rawId: String,
            val title: String, val image: String, val airDate: String, val filler: Boolean)
        val byNumber = LinkedHashMap<Int, MutableList<PipeEp>>()
        for (p in providers.keys()) {
            val eps = providers.optJSONObject(p)?.optJSONObject("episodes") ?: continue
            for (category in listOf("sub", "dub")) {
                val list = eps.optJSONArray(category) ?: continue
                for (i in 0 until list.length()) {
                    val e = list.optJSONObject(i) ?: continue
                    val n = e.optInt("number", 0)
                    if (n <= 0) continue
                    val rawId = decodePipeId(e.optString("id"))
                    if (rawId.isBlank()) continue
                    byNumber.getOrPut(n) { mutableListOf() }
                        .add(PipeEp(p, category, rawId, jstr(e, "title"), jstr(e, "image"), jstr(e, "airDate"), e.optBoolean("filler")))
                }
            }
        }
        if (byNumber.isEmpty()) {
            Log.e("MiruroPlayer", "PIPE_DETAIL_EMPTY anilistId=$anilistId providers=${providers.length()}")
            throw IOException("Miruro(pipe)에서 에피소드를 찾지 못했습니다.")
        }
        val episodes = byNumber.entries.sortedBy { it.key }.map { (n, list) ->
            val primary = list.firstOrNull { it.category == "sub" } ?: list.first()
            Episode(id = "miruro:$anilistId:$n", number = n,
                title = primary.title.takeIf { it.isNotBlank() } ?: "Episode $n",
                videoUrl = "$BASE/watch/$anilistId?ep=$n&al=$anilistId",
                airedDate = primary.airDate, isFiller = primary.filler,
                playable = true, subbed = list.any { it.category == "sub" }, dubbed = list.any { it.category == "dub" },
                thumbnailUrl = primary.image)
        }
        val malId = root.optJSONObject("mappings")?.optInt("malId", 0)?.takeIf { it > 0 } ?: anime.malId
        return anime.copy(malId = malId, episodes = episodes, note = anime.note.ifBlank { "${episodes.size}화 공개" })
    }

    private fun pipeResolve(episode: Episode, anilistId: Int): LinkkfPlayerResolver.Result {
        val number = android.net.Uri.parse(episode.videoUrl.orEmpty()).getQueryParameter("ep")?.toIntOrNull() ?: episode.number
        Log.i("MiruroPlayer", "PIPE_RESOLVE_START anilistId=$anilistId episode=$number")
        val root = fetchPipeEpisodes(anilistId)
        val providers = root.optJSONObject("providers") ?: JSONObject()
        data class PipeCand(val provider: String, val category: String, val rawId: String)
        val candidates = mutableListOf<PipeCand>()
        for (p in providers.keys()) {
            val eps = providers.optJSONObject(p)?.optJSONObject("episodes") ?: continue
            for (category in listOf("sub", "dub")) {
                val list = eps.optJSONArray(category) ?: continue
                for (i in 0 until list.length()) {
                    val e = list.optJSONObject(i) ?: continue
                    if (e.optInt("number", -1) != number) continue
                    val rawId = decodePipeId(e.optString("id"))
                    if (rawId.isNotBlank()) candidates.add(PipeCand(p, category, rawId))
                }
            }
        }
        candidates.sortBy { if (it.category == "sub") 0 else 1 }
        if (candidates.isEmpty()) {
            Log.e("MiruroPlayer", "PIPE_NO_CANDIDATES anilistId=$anilistId episode=$number providers=${providers.length()}")
            throw IOException("Miruro(pipe)에서 이 회차($number)의 제공자를 찾지 못했습니다.")
        }
        var lastError: Throwable? = null
        for ((index, cand) in candidates.withIndex()) {
            try {
                val src = pipe(pipeSourcesPayload(anilistId, cand.provider, cand.category, cand.rawId))
                val streams = src.optJSONArray("streams")
                val subtitles = src.optJSONArray("subtitles")
                var korean: String? = null; var english: String? = null; var anySub: String? = null
                for (k in 0 until (subtitles?.length() ?: 0)) {
                    val s = subtitles?.optJSONObject(k) ?: continue
                    val file = s.optString("file")
                    val label = (s.optString("label") + " " + s.optString("language") + " " + s.optString("lang")).lowercase(Locale.ROOT)
                    if (!file.startsWith("http")) continue
                    if (anySub == null) anySub = file
                    if (korean == null && (label.contains("ko") || label.contains("korean") || label.contains("한국어"))) korean = file
                    if (english == null && (label.startsWith("en") || label.contains("english"))) english = file
                }
                for (k in 0 until (streams?.length() ?: 0)) {
                    val stream = streams?.optJSONObject(k) ?: continue
                    val url = stream.optString("url")
                    val type = stream.optString("type", stream.optString("format")).lowercase(Locale.ROOT)
                    val isHls = type in setOf("hls", "m3u8", "application/vnd.apple.mpegurl", "application/x-mpegurl") ||
                        url.substringBefore('?').endsWith(".m3u8", true)
                    if (!isHls || !url.startsWith("https://")) {
                        Log.d("MiruroPlayer", "PIPE_STREAM_SKIPPED provider=${cand.provider} type=$type hasUrl=${url.isNotBlank()}")
                        continue
                    }
                    val headers = JSONObject().put("User-Agent", CloudflareSession.userAgent).put("Referer", "$BASE/")
                    // 스트림이 miruro 도메인에 있으면 Cloudflare 쿠키도 전달(.cx/.to 모두 인정).
                    if (isMiruroHost(android.net.Uri.parse(url).host))
                        CloudflareSession.cookieHeader(url)?.let { headers.put("Cookie", it) }
                    if (probePlaylist(url, headers)) {
                        Log.i("MiruroPlayer", "PIPE_RESOLVED anilistId=$anilistId episode=$number provider=${cand.provider} category=${cand.category} quality=${stream.optString("quality")} candidate=$index/${candidates.size} subtitle=${(korean ?: english ?: anySub) != null}")
                        val headerLines = headers.keys().asSequence()
                            .mapNotNull { key -> headers.optString(key).takeIf { it.isNotBlank() }?.let { "$key: $it" } }
                            .joinToString("\n")
                        return LinkkfPlayerResolver.Result(episode.id, url, "$BASE/", headerLines,
                            korean ?: english ?: anySub, "$BASE/", episode.videoUrl)
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                lastError = t
                Log.w("MiruroPlayer", "PIPE_CANDIDATE_FAILED provider=${cand.provider} category=${cand.category} err=${t.message}")
            }
        }
        throw IOException("Miruro(pipe) 재생목록을 가져오지 못했습니다(${candidates.size}개 시도): ${lastError?.message ?: "가능한 서버 없음"}", lastError)
    }

    /** 후보 스트림 URL 이 실제 HLS 재생목록인지 확인한다(죽은 미러 걸러내기). */
    private fun probePlaylist(url: String, headers: JSONObject): Boolean = try {
        val builder = Request.Builder().url(url).get()
            .header("User-Agent", CloudflareSession.userAgent).header("Accept", "*/*")
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = headers.optString(key)
            if (value.isNotBlank() && !key.equals("User-Agent", true)) builder.header(key, value)
        }
        http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
            .newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val playlist = response.isSuccessful && body.trimStart().removePrefix("\uFEFF").startsWith("#EXTM3U")
                Log.d("MiruroPlayer", "PIPE_PLAYLIST_PROBE host=${android.net.Uri.parse(url).host} status=${response.code} playlist=$playlist")
                playlist
            }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        Log.w("MiruroPlayer", "PIPE_PROBE_FAILED host=${android.net.Uri.parse(url).host} reason=${t.message}")
        false
    }

    // =====================================================================
    // 공개 API — 파이프 우선, 실패 시 구버전 REST 폴백 (세션 상태는 companion 에서 관리)
    // =====================================================================

    fun catalog(query: String = "", cursor: String = ""): Pair<List<Anime>, String?> {
        // 1순위: pipe(현재 사이트의 실제 API). 실패 시에만 구버전 REST 를 마지막 폴백으로 시도한다.
        return try {
            pipeCatalog(query, cursor)
        } catch (t: Throwable) {
            if (t is CloudflareChallengeException) throw t
            if (restAvailable()) {
                try { return restCatalog(query, cursor) }
                catch (t2: Throwable) {
                    if (t2 is CloudflareChallengeException) throw t2
                    markRestDead(t2)
                    Log.w("MiruroApi", "REST_FALLBACK_FAILED err=${t2.message}")
                }
            }
            throw t
        }
    }

    fun detail(anime: Anime): Anime {
        // 1순위: pipe. REST 는 구버전 시청 기록(비숫자 ID) 호환용 마지막 폴백.
        val numericId = anime.id.removePrefix("miruro:").takeIf { it.matches(Regex("\\d+")) }
        if (numericId != null || anime.anilistId != null) {
            try { return pipeDetail(anime) }
            catch (t: Throwable) {
                if (t is CloudflareChallengeException) throw t
                Log.w("MiruroPlayer", "PIPE_DETAIL_FAILED err=${t.message}")
            }
        }
        return restDetail(anime)
    }

    fun resolve(episode: Episode): LinkkfPlayerResolver.Result {
        val anilistId = android.net.Uri.parse(episode.videoUrl.orEmpty()).getQueryParameter("al")?.toIntOrNull()
            ?: episode.id.removePrefix("miruro:").substringBefore(':').takeIf { it.matches(Regex("\\d+")) }?.toIntOrNull()
        if (anilistId == null) {
            // AniList 힌트가 없는 구버전 시청 기록은 REST 엔드포인트로만 해석된다.
            return restResolve(episode)
        }
        // 1순위: pipe(현재 사이트의 실제 API). 실패 시에만 구버전 REST 를 시도한다.
        return try {
            pipeResolve(episode, anilistId)
        } catch (t: Throwable) {
            if (t is CloudflareChallengeException) throw t
            Log.w("MiruroPlayer", "PIPE_RESOLVE_FALLBACK err=${t.message}")
            restResolve(episode)
        }
    }

    // =====================================================================
    // 세션 상태 & 유틸
    // =====================================================================

    private fun hostsFor(preferred: String?): List<String> {
        val hosts = mutableListOf<String>()
        preferred?.let { hosts.add(it) }
        for (host in HOSTS) if (host !in hosts) hosts.add(host)
        return hosts
    }

    private fun restAvailable(): Boolean = System.currentTimeMillis() >= restDeadUntil

    private fun markRestDead(t: Throwable) {
        restDeadUntil = System.currentTimeMillis() + REST_COOLDOWN_MS
        Log.w("MiruroApi", "REST_MARKED_DEAD ${REST_COOLDOWN_MS / 1000}초간 pipe 모드 사용: ${t.message}")
    }

    /** 현재 도메인(miruro.cx)과 예전 버전이 저장했던 구 도메인(miruro.to)을 모두 인정한다. */
    private fun isMiruroHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return false
        return h == "miruro.cx" || h.endsWith(".miruro.cx") || h == "miruro.to" || h.endsWith(".miruro.to")
    }

    private fun htmlText(value: String) = value.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&mdash;", "—").trim()

    companion object {
        /**
         * 2026-11 공식 도메인 이전: miruro.com/status.miruro.com 공지에 따라 miruro.cx 가 기본 도메인이고,
         * 구 도메인(miruro.to)은 단계적 종료되어 API 가 차단된다.
         */
        const val BASE = "https://www.miruro.cx"

        // 로테이션 순서: 공식 도메인(.cx) 우선, 이후 공식 파트너/구 미러. (실측: 전부 Cloudflare 방어 중)
        private val HOSTS = listOf("www.miruro.cx", "miruro.cx", "www.miruro.tv", "www.miruro.to")

        /**
         * pipe 응답 XOR obfuscation 키(x-obfuscated: 2 헤더일 때).
         * 사이트 프론트엔드 난독화 키로, 공개 리버스엔지니어링 구현 2종(2026-07/08)에서 교차 확인된 값.
         */
        private val PIPE_OBF_KEY = "71951034f8fbcf53d89db52ceb3dc22c"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private const val ANILIST_URL = "https://graphql.anilist.co"
        private const val REST_COOLDOWN_MS = 10 * 60_000L

        // PlayerScreen/DetailScreen 이 매 호출마다 새 인스턴스를 만들므로 모드/캐시는 프로세스 전역으로 유지한다.
        @Volatile private var restHost: String? = null
        @Volatile private var pipeHost: String? = null
        @Volatile private var restDeadUntil = 0L
        private val pipeEpisodesCache = HashMap<Int, Pair<JSONObject, Long>>() // anilistId -> (response, fetchedAt)
    }
}
