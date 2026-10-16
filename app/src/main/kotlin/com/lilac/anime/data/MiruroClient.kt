package com.lilac.anime.data

import android.util.Log
import com.lilac.anime.Anime
import com.lilac.anime.Episode
import com.lilac.anime.network.LinkkfPlayerResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Android port of the desktop Miruro API/catalog/playback adapter. */
class MiruroClient {
    private val http = OkHttpClient.Builder()
        .followRedirects(true).retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).build()

    private fun api(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("www.miruro.to")
            .addPathSegments("api/v1/${path.trimStart('/')}").apply {
                params.forEach { (key, value) -> if (value.isNotBlank()) addQueryParameter(key, value) }
            }.build()
        val request = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept", "*/*").header("Referer", "$BASE/").build()
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                http.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes() ?: byteArrayOf()
                    if (!response.isSuccessful) {
                        val detail = bytes.toString(Charsets.UTF_8).replace(Regex("[\r\n\t]+"), " ").take(240)
                        Log.e("MiruroApi", "API_HTTP_ERROR path=$path status=${response.code} bytes=${bytes.size} body=$detail")
                        throw java.io.IOException("Miruro HTTP ${response.code}${if (detail.isNotBlank()) ": $detail" else ""}")
                    }
                    val decoded = decodeApiBody(bytes)
                    val json = JSONObject(decoded)
                    Log.d("MiruroApi", "API_OK path=$path attempt=${attempt + 1} bytes=${bytes.size} keys=${json.keys().asSequence().take(12).joinToString(",")}")
                    return json
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                lastError = t
                Log.w("MiruroApi", "API_RETRY path=$path attempt=${attempt + 1}: ${t.message}")
                if (attempt < 2) try { Thread.sleep(300L * (attempt + 1)) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
        throw java.io.IOException("Miruro API 요청 실패: ${lastError?.message ?: "알 수 없는 오류"}", lastError)
    }

    /** Supports the site's usual XOR+gzip response and plain JSON/gzip responses. */
    private fun decodeApiBody(input: ByteArray): String {
        fun isGzip(bytes: ByteArray) = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        fun decode(bytes: ByteArray): String {
            val payload = if (isGzip(bytes)) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
            return payload.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        }
        if (input.isEmpty()) throw java.io.IOException("Miruro 응답 본문이 비어 있습니다.")
        val key = "miruro/catalog".toByteArray(Charsets.UTF_8)
        val xor = input.copyOf()
        for (i in xor.indices) xor[i] = (xor[i].toInt() xor key[i % key.size].toInt()).toByte()
        for (candidate in listOf(input, xor)) {
            try {
                val text = decode(candidate)
                if (text.startsWith("{") || text.startsWith("[")) return text
            } catch (_: Exception) { }
        }
        throw java.io.IOException("Miruro 응답을 JSON으로 해독하지 못했습니다.")
    }

    fun catalog(query: String = "", cursor: String = ""): Pair<List<Anime>, String?> {
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

    fun detail(anime: Anime): Anime {
        val rawId = anime.id.removePrefix("miruro:")
        val root = api("anime/${rawId}")
        val parsed = anime(root).copy(id = "miruro:$rawId", source = "miruro", detailUrl = "$BASE/watch/$rawId")
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
                videoUrl = "$BASE/watch/$rawId?ep=$n", airedDate = e.optString("aired_on"),
                isFiller = e.optString("canon_type") == "filler", isRecap = e.optString("canon_type") == "recap",
                playable = !notYetReleased, thumbnailUrl = e.optString("thumbnail_url"))
        }.distinctBy { it.number }.sortedBy { it.number }
        return parsed.copy(episodes = episodes)
    }

    fun resolve(episode: Episode): LinkkfPlayerResolver.Result {
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
            throw java.io.IOException("Miruro에서 이 회차의 영상 서버 목록을 찾지 못했습니다. 설정에서 진단 로그를 추출해 주세요.")
        }

        // The desktop adapter tests each playlist and skips stale/dead mirrors; returning the first URL alone
        // frequently selects a server that exists in the API response but currently returns 403/HTML.
        var selected: Candidate? = null
        var failed = 0
        for (candidate in candidates.sortedBy { it.rank }) {
            try {
                val builder = Request.Builder().url(candidate.url).get()
                    .header("User-Agent", UA).header("Accept", "*/*")
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
            throw java.io.IOException("Miruro 영상 서버 ${candidates.size}개를 확인했지만 재생목록에 접근할 수 없습니다. 설정에서 로그 추출을 눌러 MiruroPlayer 로그를 보내 주세요.")
        }
        val referer = best.headers.optString("Referer").ifBlank { best.headers.optString("referer") }.ifBlank { "$BASE/" }
        val headers = JSONObject(best.headers.toString()).apply {
            if (!has("User-Agent") && !has("user-agent")) put("User-Agent", UA)
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

    private fun htmlText(value: String) = value.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&mdash;", "—").trim()

    companion object {
        const val BASE = "https://www.miruro.to"
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
