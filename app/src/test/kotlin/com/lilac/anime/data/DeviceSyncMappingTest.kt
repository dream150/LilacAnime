package com.lilac.anime.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSyncMappingTest {
    private val hash: (JSONObject) -> String = { JSONObject(it.toString()).apply { remove("updated"); remove("savedAt") }.toString() }

    // As the desktop app writes them (LilacAnime-desktop src/app.js and src/device-sync.js): progress in percent.
    private fun desktopAnime(provider: String, id: String, title: String) = JSONObject()
        .put("provider", provider).put("id", id).put("mal_id", "$provider:$id").put("title", title)
        .put("images", JSONObject().put("webp", JSONObject().put("large_image_url", "https://img/$id.jpg"))).put("anilistId", 154587)

    private fun desktopEpisode(provider: String, slug: String, number: Int, progress: Double, updated: Long, url: String, name: String = "$number"): Pair<String, JSONObject> =
        "$provider:$url" to JSONObject().put("key", "$provider:$url").put("src", "").put("name", "작품 · ${number}화")
            .put("episode", JSONObject().put("provider", provider).put("number", number).put("name", name).put("url", url))
            .put("anime", desktopAnime(provider, slug, "작품")).put("progress", progress).put("updated", updated)

    private fun reanimeEpisode(slug: String, number: Int, progress: Double, updated: Long) =
        desktopEpisode("reanime", slug, number, progress, updated, "https://reanime.to/watch/$slug?ep=$number")

    private fun item(collection: String, id: String, data: JSONObject?, updated: Long, deleted: Boolean = false) = JSONObject()
        .put("collection", collection).put("id", id).put("updated", updated).apply { if (deleted) put("deleted", true) else put("data", data) }

    // This app's own shape (DeviceSyncClient.localCollections): the episode key is the episode's id, progress 0–1.
    private fun local(library: Set<String> = emptySet(), history: List<Triple<String, String, Double>> = emptyList(), now: Long = 1000L) = JSONObject()
        .put("library", JSONObject().apply { library.forEach { put(it, JSONObject().put("provider", "android").put("id", it).put("mal_id", it).put("title", it).put("savedAt", now)) } })
        .put("history", JSONObject().apply {
            history.forEach { (anime, key, progress) ->
                val number = DeviceSyncMapping.displayOf(key)?.takeWhile { it.isDigit() }?.toInt() ?: 1
                put("$anime:$key", JSONObject().put("key", "$anime:$key").put("androidAnimeId", anime).put("androidEpisodeKey", key).put("episodeNumber", number)
                    .put("episode", JSONObject().put("number", number).put("key", key)).put("anime", JSONObject().put("id", anime).put("mal_id", anime).put("title", anime))
                    .put("progress", progress).put("updated", now))
            }
        })

    private fun snapshotOf(values: JSONObject) = JSONObject().apply {
        for (c in DeviceSyncMapping.COLLECTIONS) put(c, JSONObject().apply { val o = values.optJSONObject(c) ?: JSONObject(); o.keys().forEach { put(it, hash(o.getJSONObject(it))) } })
    }

    @Test
    fun `an episode watched on the desktop is this app's entry for that episode, resumable here`() {
        val (id, data) = reanimeEpisode("kaguya", 2, 72.0, 2000L)
        val remote = JSONObject()
        val merged = DeviceSyncMapping.merge(local(), listOf(item("history", id, data, 2000L)), remote)
        val entry = merged.values.getJSONObject("history").getJSONObject("reanime:kaguya:reanime:kaguya:ep-2")
        // The player looks the position up by the episode's id (ReAnimeHarParser: "reanime:<slug>:ep-<n>").
        assertEquals(DeviceSyncMapping.Identity("reanime:kaguya", "reanime:kaguya:ep-2", 2, "2"), DeviceSyncMapping.historyIdentity("x", entry))
        assertEquals(0.72f, DeviceSyncMapping.progressFraction(entry), 0.0001f)
        assertEquals(id, remote.getJSONObject("history").getJSONObject("reanime:kaguya:reanime:kaguya:ep-2").getString("id"))
        assertEquals("작품", merged.works.single().title)
    }

    @Test
    fun `each source's episode is keyed as its parser keys it`() {
        fun keyOf(provider: String, slug: String, url: String, name: String = "5", dub: Boolean = false): String {
            val (_, data) = desktopEpisode(provider, slug, 5, 10.0, 1L, url, name)
            data.getJSONObject("episode").put("dub", dub)
            return DeviceSyncMapping.historyIdentity("x", data)!!.episodeKey
        }
        assertEquals("miruro:123:5", keyOf("miruro", "123", "https://www.miruro.to/watch/123?ep=5"))
        assertEquals("animenosub:bleach_ep_5", keyOf("animenosub", "bleach", "https://animenosub.to/bleach-episode-5/"))
        assertEquals("animenosub:bleach_dub_ep_5", keyOf("animenosub", "bleach", "https://animenosub.to/bleach-episode-5-dub/", dub = true))
        assertEquals("animenosub:bleach_ep_5b", keyOf("animenosub", "bleach", "https://animenosub.to/bleach-episode-5b/", name = "5b"))
        assertEquals("ohli24:슬라임:슬라임 5화", keyOf("ohli24", "슬라임", "https://ani.ohli24.com/e/${DeviceSyncMapping.encodeComponent("슬라임 5화")}"))
        val linkani = desktopEpisode("linkani", "12345", 3, 10.0, 1L, "https://linkani.tv/watch/12345/a1/k3/").second
        linkani.getJSONObject("anime").put("mal_id", "linkani:12345")
        assertEquals("12345_ep_3", DeviceSyncMapping.historyIdentity("x", linkani)!!.episodeKey)
    }

    @Test
    fun `a change made here to the desktop's episode goes back under its id, in percent`() {
        val (id, data) = reanimeEpisode("kaguya", 2, 72.0, 2000L)
        val remote = JSONObject()
        DeviceSyncMapping.merge(local(), listOf(item("history", id, data, 2000L)), remote)
        val snapshot = snapshotOf(local(history = listOf(Triple("reanime:kaguya", "reanime:kaguya:ep-2", 0.72))))
        val out = DeviceSyncMapping.outgoing(local(history = listOf(Triple("reanime:kaguya", "reanime:kaguya:ep-2", 0.9)), now = 5000L), snapshot, remote, emptyMap(), 5000L, hash)
        assertEquals(1, out.length())
        val sent = out.getJSONObject(0)
        assertEquals(id, sent.getString("id"))
        val sentData = sent.getJSONObject("data")
        assertEquals(90.0, sentData.getDouble("progress"), 0.001)
        assertEquals("https://reanime.to/watch/kaguya?ep=2", sentData.getJSONObject("episode").getString("url"))
        assertEquals("reanime:kaguya:ep-2", sentData.getString("androidEpisodeKey"))
    }

    @Test
    fun `a Re-Anime episode first watched here goes out under the desktop's id, playable there`() {
        val work = DeviceSyncMapping.Work("reanime:kaguya", "카구야 님은 고백받고 싶어", "https://img/p.jpg", null, null, "")
        val out = DeviceSyncMapping.outgoing(local(history = listOf(Triple("reanime:kaguya", "reanime:kaguya:ep-2", 0.1064))), JSONObject(), JSONObject(), mapOf("reanime:kaguya" to work), 1000L, hash)
        val sent = out.getJSONObject(0)
        assertEquals("reanime:https://reanime.to/watch/kaguya?ep=2", sent.getString("id"))
        val data = sent.getJSONObject("data")
        assertEquals("reanime:https://reanime.to/watch/kaguya?ep=2", data.getString("key"))
        assertEquals("https://reanime.to/watch/kaguya?ep=2", data.getJSONObject("episode").getString("url"))
        assertEquals("2", data.getJSONObject("episode").getString("name"))
        assertEquals(11.0, data.getDouble("progress"), 0.0)
        assertEquals("카구야 님은 고백받고 싶어 · 2화", data.getString("name"))
        // And back here it is the same episode, at the same place.
        val identity = DeviceSyncMapping.historyIdentity(sent.getString("id"), data)!!
        assertEquals("reanime:kaguya:ep-2", identity.episodeKey)
        assertEquals(0.11f, DeviceSyncMapping.progressFraction(data), 0.0001f)
    }

    @Test
    fun `an Animenosub episode keeps this app's id but goes out with its work`() {
        val out = DeviceSyncMapping.outgoing(local(history = listOf(Triple("animenosub:bleach", "animenosub:bleach_ep_5", 0.1))), JSONObject(), JSONObject(), emptyMap(), 1000L, hash)
        val sent = out.getJSONObject(0)
        assertEquals("animenosub:bleach:animenosub:bleach_ep_5", sent.getString("id"))
        val data = sent.getJSONObject("data")
        assertEquals("animenosub", data.getJSONObject("anime").getString("provider"))
        assertEquals("https://animenosub.to/anime/bleach/", data.getJSONObject("anime").getString("url"))
        assertEquals("5", data.getJSONObject("episode").getString("name"))
        assertFalse(data.getJSONObject("episode").has("url"))
    }

    @Test
    fun `Linkkf works are the desktop's linkani ones, both ways`() {
        val remote = JSONObject()
        val desktop = desktopAnime("linkani", "777", "링크 작품").put("mal_id", "linkani:777").put("savedAt", 500L)
        val merged = DeviceSyncMapping.merge(local(), listOf(item("library", "linkani:777", desktop, 500L)), remote)
        assertTrue(merged.values.getJSONObject("library").has("777"))
        assertEquals("링크 작품", merged.works.single { it.id == "777" }.title)
        val out = DeviceSyncMapping.outgoing(local(library = setOf("456")), JSONObject(), JSONObject(), emptyMap(), 1000L, hash)
        val sent = out.getJSONObject(0)
        assertEquals("linkani:456", sent.getString("id"))
        assertEquals("linkani", sent.getJSONObject("data").getString("provider"))
        assertEquals("https://linkani.tv/ani/456/", sent.getJSONObject("data").getString("url"))
    }

    @Test
    fun `a removal here goes out under the desktop's id`() {
        val (id, data) = reanimeEpisode("kaguya", 2, 72.0, 2000L)
        val remote = JSONObject()
        DeviceSyncMapping.merge(local(), listOf(item("history", id, data, 2000L)), remote)
        val snapshot = snapshotOf(local(history = listOf(Triple("reanime:kaguya", "reanime:kaguya:ep-2", 0.72))))
        val out = DeviceSyncMapping.outgoing(local(), snapshot, remote, emptyMap(), 5000L, hash)
        assertEquals(id, out.getJSONObject(0).getString("id"))
        assertTrue(out.getJSONObject(0).getBoolean("deleted"))
        // Without a server id remembered, the id is worked out from this app's own.
        val first = DeviceSyncMapping.outgoing(local(), snapshotOf(local(history = listOf(Triple("reanime:kaguya", "reanime:kaguya:ep-3", 0.5)))), JSONObject(), emptyMap(), 5000L, hash)
        assertEquals("reanime:https://reanime.to/watch/kaguya?ep=3", first.getJSONObject(0).getString("id"))
    }

    @Test
    fun `a removal on the desktop removes this app's entry`() {
        val (id, data) = reanimeEpisode("kaguya", 2, 72.0, 2000L)
        val remote = JSONObject()
        val first = DeviceSyncMapping.merge(local(), listOf(item("history", id, data, 2000L)), remote)
        val second = DeviceSyncMapping.merge(first.values, listOf(item("history", id, null, 3000L, deleted = true)), remote)
        assertFalse(second.values.getJSONObject("history").has("reanime:kaguya:reanime:kaguya:ep-2"))
    }

    @Test
    fun `an Animenosub episode the desktop takes under its own id stays, despite the removal of this app's id`() {
        val androidId = "animenosub:bleach:animenosub:bleach_ep_3"
        val remote = JSONObject()
        // This app's own entry, back from the server.
        val mine = DeviceSyncMapping.outgoing(local(history = listOf(Triple("animenosub:bleach", "animenosub:bleach_ep_3", 0.1))), JSONObject(), JSONObject(), emptyMap(), 1000L, hash).getJSONObject(0)
        val first = DeviceSyncMapping.merge(local(), listOf(mine), remote)
        // The desktop played it from its list: its entry, then the removal of this app's.
        val (desktopId, desktopData) = desktopEpisode("animenosub", "bleach", 3, 40.0, 2000L, "https://animenosub.to/bleach-episode-3/")
        val second = DeviceSyncMapping.merge(first.values, listOf(item("history", desktopId, desktopData, 2000L), item("history", androidId, null, 3000L, deleted = true)), remote)
        val entry = second.values.getJSONObject("history").getJSONObject(androidId)
        assertEquals(0.4f, DeviceSyncMapping.progressFraction(entry), 0.0001f)
        assertEquals(desktopId, remote.getJSONObject("history").getJSONObject(androidId).getString("id"))
    }

    @Test
    fun `a Re-Anime episode id that is a number of its own is not taken for the episode's number`() {
        // ReAnimeHarParser.episodeFromTable: "reanime:<slug>:<episodeId>".
        val out = DeviceSyncMapping.outgoing(local(history = listOf(Triple("reanime:apothecary", "reanime:apothecary:48213", 0.38))).apply {
            getJSONObject("history").getJSONObject("reanime:apothecary:reanime:apothecary:48213").put("episodeNumber", 1).getJSONObject("episode").put("number", 1)
        }, JSONObject(), JSONObject(), emptyMap(), 1000L, hash)
        val sent = out.getJSONObject(0)
        assertEquals("reanime:https://reanime.to/watch/apothecary?ep=1", sent.getString("id"))
        assertEquals("1", sent.getJSONObject("data").getJSONObject("episode").getString("name"))
        assertEquals("reanime:apothecary:48213", sent.getJSONObject("data").getString("androidEpisodeKey"))
    }

    @Test
    fun `progress goes out in whole percents, as the desktop keeps it`() {
        val out = DeviceSyncMapping.outgoing(local(history = listOf(Triple("reanime:kaguya", "reanime:kaguya:ep-2", 0.10638))), JSONObject(), JSONObject(), emptyMap(), 1000L, hash)
        assertEquals(11.0, out.getJSONObject(0).getJSONObject("data").getDouble("progress"), 0.0)
    }

    @Test
    fun `an episode played again here under its own id replaces the synced one without removing it there`() {
        val (id, data) = reanimeEpisode("apothecary", 1, 38.0, 2000L)
        val remote = JSONObject()
        DeviceSyncMapping.merge(local(), listOf(item("history", id, data, 2000L)), remote)
        // The synced entry ("…:ep-1") gives way to the player's own ("…:48213", episode 1).
        val before = snapshotOf(local(history = listOf(Triple("reanime:apothecary", "reanime:apothecary:ep-1", 0.38))))
        val now = local(history = listOf(Triple("reanime:apothecary", "reanime:apothecary:48213", 0.6)), now = 5000L)
        now.getJSONObject("history").getJSONObject("reanime:apothecary:reanime:apothecary:48213").put("episodeNumber", 1).getJSONObject("episode").put("number", 1)
        val out = DeviceSyncMapping.outgoing(now, before, remote, emptyMap(), 5000L, hash)
        assertEquals(1, out.length())
        assertEquals(id, out.getJSONObject(0).getString("id"))
        assertFalse(out.getJSONObject(0).optBoolean("deleted"))
        assertEquals(60.0, out.getJSONObject(0).getJSONObject("data").getDouble("progress"), 0.0)
    }

    @Test
    fun `a desktop work this app has no source for is left out`() {
        val mal = JSONObject().put("mal_id", 52991).put("title", "Frieren").put("savedAt", 1L)
        val merged = DeviceSyncMapping.merge(local(), listOf(item("library", "52991", mal, 1L)), JSONObject())
        assertEquals(0, merged.values.getJSONObject("library").length())
        assertNull(DeviceSyncMapping.libraryId("linkkf:1", JSONObject().put("provider", "linkkf").put("id", "1")))
    }

    @Test
    fun `an entry from an older version of this app is still read, its progress a fraction`() {
        val legacy = local(history = listOf(Triple("ohli24:slime", "ohli24:slime:2화", 0.3))).getJSONObject("history").getJSONObject("ohli24:slime:ohli24:slime:2화")
        legacy.getJSONObject("anime").remove("provider")
        val merged = DeviceSyncMapping.merge(local(), listOf(item("history", "ohli24:slime:ohli24:slime:2화", legacy, 1000L)), JSONObject())
        val entry = merged.values.getJSONObject("history").getJSONObject("ohli24:slime:ohli24:slime:2화")
        assertEquals(0.3f, DeviceSyncMapping.progressFraction(entry), 0.0001f)
        assertEquals(0, merged.works.size)
    }
}
