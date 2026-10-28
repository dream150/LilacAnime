package com.lilac.anime.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lilac.anime.Anime
import com.lilac.anime.WatchProgress
import com.lilac.anime.data.offline.OfflineStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device sync client for the user's own LilacAnime server. Passwords are never persisted. */
object DeviceSyncClient {
    private const val PREFS = "lilac_device_sync"
    private const val KEY_ALIAS = "lilacanime_device_sync_token_v1"
    private const val KEY_URL = "server_url"
    private const val KEY_TOKEN = "encrypted_token"
    private const val KEY_REV = "revision"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val KEY_REMOTE = "remote_ids"
    private const val MAX_BATCH = 500

    data class SyncResult(val library: Set<String>, val history: List<WatchProgress>, val sent: Int, val received: Int, val revision: Long)

    fun savedServer(context: Context): String = prefs(context).getString(KEY_URL, "") ?: ""
    fun isConnected(context: Context): Boolean = decryptToken(context).isNotBlank()

    fun disconnect(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).remove(KEY_REV).remove(KEY_SNAPSHOT).remove(KEY_REMOTE).apply()
    }

    fun connect(context: Context, rawUrl: String, password: String): String {
        val base = normalizeUrl(rawUrl)
        val status = request("GET", "$base/api/status", null, null)
        if (status.optString("app") != "lilacanime-server") throw IllegalStateException("LilacAnime 동기화 서버가 아닙니다.")
        if (!status.optBoolean("ready", false)) throw IllegalStateException(status.optJSONArray("problems")?.let { a -> (0 until a.length()).joinToString(" ") { a.optString(it) } } ?: "서버 설정이 완료되지 않았습니다.")
        if (password.length < 8) throw IllegalArgumentException("서버 비밀번호는 8자 이상이어야 합니다.")
        val login = request("POST", "$base/api/login", JSONObject().put("password", password), null)
        val token = login.optString("token").takeIf { it.isNotBlank() } ?: throw IllegalStateException(login.optString("error", "로그인 토큰을 받지 못했습니다."))
        prefs(context).edit().putString(KEY_URL, base).putString(KEY_TOKEN, encryptToken(context, token)).apply()
        return "서버 연결 완료"
    }

    suspend fun sync(context: Context, library: Set<String>, history: List<WatchProgress>): SyncResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val base = savedServer(context)
        val token = decryptToken(context)
        require(base.isNotBlank() && token.isNotBlank()) { "먼저 동기화 서버에 연결하세요." }
        val p = prefs(context)
        val oldSnapshot = try { JSONObject(p.getString(KEY_SNAPSHOT, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        // The server's id (and, for history, its copy) of each item taken in from there, by this app's id (see DeviceSyncMapping).
        val remote = try { JSONObject(p.getString(KEY_REMOTE, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val now = System.currentTimeMillis()
        val local = localCollections(library, history, now)
        // Titles and pictures of the works, from the phone's copy of them, for the desktop to show.
        val works = HashMap<String, DeviceSyncMapping.Work>()
        for (id in library + history.map { it.animeId }) {
            if (id in works) continue
            OfflineStore.getAnime(context, id)?.let { a -> works[id] = DeviceSyncMapping.Work(a.id, a.title, a.poster, a.anilistId, a.malId, a.description) }
        }
        val outgoing = DeviceSyncMapping.outgoing(local, oldSnapshot, remote, works, now) { hashJson(it) }
        val incoming = mutableListOf<JSONObject>()
        var sent = 0
        var start = 0
        while (start < outgoing.length()) {
            val batch = JSONArray()
            for (i in start until minOf(start + MAX_BATCH, outgoing.length())) batch.put(outgoing.getJSONObject(i))
            val response = request("POST", "$base/api/sync", JSONObject().put("items", batch), token)
            sent += response.optInt("accepted", 0)
            val current = response.optJSONArray("current") ?: JSONArray()
            for (i in 0 until current.length()) incoming += current.getJSONObject(i)
            start += MAX_BATCH
        }
        var rev = p.getLong(KEY_REV, 0L)
        var more = true
        var received = 0
        while (more) {
            val page = request("GET", "$base/api/sync?since=$rev", null, token)
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) { incoming += items.getJSONObject(i); received++ }
            val next = page.optLong("rev", rev)
            if (next < rev) throw IllegalStateException("서버 리비전이 역행했습니다.")
            rev = next
            more = page.optBoolean("more", false)
            if (more && items.length() == 0) throw IllegalStateException("서버가 다음 페이지를 제공하지 않았습니다.")
        }
        val merged = DeviceSyncMapping.merge(local, incoming, remote)
        // A work the phone never opened is kept from what the other device sent, so 내 목록 and 이어보기 can show it.
        for (work in merged.works) {
            if (OfflineStore.getAnime(context, work.id) == null) OfflineStore.saveAnime(context, Anime(id = work.id, title = work.title, poster = work.poster, backdrop = work.poster, anilistId = work.anilistId, malId = work.malId, description = work.description))
        }
        val newLibrary = merged.values.optJSONObject("library")?.keys()?.asSequence()?.toSet() ?: library
        val newHistory = merged.values.optJSONObject("history")?.let { obj ->
            obj.keys().asSequence().mapNotNull { id ->
                val d = obj.optJSONObject(id) ?: return@mapNotNull null
                val identity = DeviceSyncMapping.historyIdentity(id, d) ?: return@mapNotNull null
                WatchProgress(identity.animeId, identity.episodeNumber.coerceAtLeast(1), DeviceSyncMapping.progressFraction(d), identity.episodeKey, merged.updated[id] ?: 0L)
            }.distinctBy { "${it.animeId}|${it.episodeKey}" }.sortedByDescending { merged.updated[historyId(it)] ?: 0L }.take(300).toList()
        } ?: history
        OfflineStore.saveLibrary(context, newLibrary)
        OfflineStore.saveWatchHistory(context, newHistory)
        p.edit().putLong(KEY_REV, rev).putString(KEY_SNAPSHOT, hashes(localCollections(newLibrary, newHistory, now)).toString()).putString(KEY_REMOTE, remote.toString()).apply()
        SyncResult(newLibrary, newHistory, sent, received, rev)
    }

    private fun localCollections(library: Set<String>, history: List<WatchProgress>, now: Long): JSONObject {
        val lib = JSONObject(); library.forEach { id -> lib.put(id, JSONObject().put("provider", "android").put("id", id).put("mal_id", id).put("title", id).put("savedAt", now)) }
        val hist = JSONObject(); history.forEach { w ->
            val key = historyId(w)
            hist.put(key, JSONObject().put("key", key).put("androidAnimeId", w.animeId).put("androidEpisodeKey", w.episodeKey).put("name", "${w.animeId} · ${w.episodeKey}화").put("episodeNumber", w.episodeNumber).put("episode", JSONObject().put("number", w.episodeNumber).put("key", w.episodeKey)).put("anime", JSONObject().put("id", w.animeId).put("mal_id", w.animeId).put("title", w.animeId)).put("progress", w.progress.toDouble()).put("updated", w.updatedAt.takeIf { it > 0L } ?: now))
        }
        return JSONObject().put("library", lib).put("history", hist)
    }

    private fun hashes(local: JSONObject): JSONObject {
        val out = JSONObject()
        for (c in listOf("library", "history")) { val values = JSONObject(); val obj = local.optJSONObject(c) ?: JSONObject(); val keys = obj.keys(); while (keys.hasNext()) { val id = keys.next(); values.put(id, hashJson(obj.optJSONObject(id) ?: JSONObject()) ) }; out.put(c, values) }
        return out
    }

    private fun historyId(w: WatchProgress) = "${w.animeId}:${w.episodeKey}"
    private fun hashJson(value: JSONObject): String {
        // These are server timestamps, not user-visible content changes. Excluding them prevents endless re-upload loops.
        val stable = JSONObject(value.toString()).apply { remove("updated"); remove("savedAt") }
        return MessageDigest.getInstance("SHA-256").digest(stable.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun normalizeUrl(raw: String): String { val value = raw.trim().trimEnd('/'); require(value.startsWith("https://", true) || value.startsWith("http://", true)) { "서버 주소는 http:// 또는 https://로 시작해야 합니다." }
        require(!value.contains('@')) { "주소에 사용자 정보는 넣을 수 없습니다." }
        if (value.startsWith("http://", true)) require(value.substringAfter("http://").substringBefore('/').substringBefore(':').equals("localhost", true) || value.substringAfter("http://").substringBefore('/').substringBefore(':') == "127.0.0.1") { "비밀번호와 토큰 보호를 위해 외부 서버는 HTTPS 주소를 사용하세요." }
        return value }

    private fun request(method: String, url: String, body: JSONObject?, token: String?): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 12000; readTimeout = 20000; setRequestProperty("Accept", "application/json"); if (token != null) setRequestProperty("Authorization", "Bearer $token"); if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") } }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (code !in 200..299) throw IllegalStateException(json.optString("error").ifBlank { "서버 요청 실패 (HTTP $code)" })
            return json
        } finally { conn.disconnect() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    private fun encryptToken(context: Context, token: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8)); return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }
    private fun decryptToken(context: Context): String {
        val stored = prefs(context).getString(KEY_TOKEN, null) ?: return ""
        return try { val raw = Base64.decode(stored, Base64.NO_WRAP); val iv = raw.copyOfRange(0, 12); val data = raw.copyOfRange(12, raw.size); val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); String(cipher.doFinal(data), Charsets.UTF_8) } catch (_: Exception) { prefs(context).edit().remove(KEY_TOKEN).apply(); "" }
    }
}
