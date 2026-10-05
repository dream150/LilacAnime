package com.lilac.anime.data.subtitle.translation

import android.content.Context
import java.io.File
import java.util.Properties

/** Persistent storage for realtime-translated subtitle parts. */
object TranslationPartStore {
    data class Entry(
        val animeId: String,
        val animeTitle: String,
        val episodeKey: String,
        val episodeLabel: String,
        val sourceName: String,
        val extension: String,
        val providerId: String,
        val parts: Int,
        val root: File
    )

    private fun root(context: Context): File = File(context.filesDir, "translation_parts").apply { mkdirs() }
    private fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "unknown" }
    private fun entryDir(context: Context, animeId: String, episodeKey: String): File =
        File(root(context), "${safe(animeId)}__${safe(episodeKey)}")

    fun savePart(
        context: Context,
        animeId: String,
        animeTitle: String,
        episodeKey: String,
        episodeLabel: String,
        sourceName: String,
        extension: String,
        providerId: String,
        partIndex: Int,
        content: String
    ) {
        val dir = entryDir(context, animeId, episodeKey).apply { mkdirs() }
        val meta = Properties().apply {
            setProperty("animeId", animeId)
            setProperty("animeTitle", animeTitle)
            setProperty("episodeKey", episodeKey)
            setProperty("episodeLabel", episodeLabel)
            setProperty("sourceName", sourceName)
            setProperty("extension", extension)
            setProperty("providerId", providerId)
        }
        dir.resolve("meta.properties").outputStream().use { meta.store(it, "LilacAnime realtime translation") }
        val part = dir.resolve("part_${partIndex.toString().padStart(5, '0')}.$extension")
        val tmp = File(dir, part.name + ".tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (part.exists()) part.delete()
        tmp.renameTo(part)
    }

    fun list(context: Context): List<Entry> = root(context).listFiles { f -> f.isDirectory }
        ?.mapNotNull { dir ->
            val p = Properties()
            runCatching { dir.resolve("meta.properties").inputStream().use { p.load(it) } }.getOrNull() ?: return@mapNotNull null
            val ext = p.getProperty("extension", "srt")
            val parts = dir.listFiles { f -> f.isFile && f.name.startsWith("part_") && f.extension == ext }?.size ?: 0
            if (parts <= 0) null else Entry(
                p.getProperty("animeId", ""), p.getProperty("animeTitle", ""),
                p.getProperty("episodeKey", ""), p.getProperty("episodeLabel", ""),
                p.getProperty("sourceName", "subtitle"), ext, p.getProperty("providerId", "local"), parts, dir
            )
        }?.sortedWith(compareBy<Entry> { it.animeTitle.lowercase() }.thenBy { it.episodeLabel }) ?: emptyList()

    fun delete(context: Context, entry: Entry): Boolean = entry.root.deleteRecursively()

    fun merge(context: Context, entry: Entry): File? {
        val parts = entry.root.listFiles { f -> f.isFile && f.name.startsWith("part_") && f.extension == entry.extension }
            ?.sortedBy { it.name } ?: return null
        if (parts.isEmpty()) return null
        val outDir = File(context.cacheDir, "translation_exports").apply { mkdirs() }
        val out = File(outDir, "${safe(entry.animeTitle)}_${safe(entry.episodeLabel)}_ko.${entry.extension}")
        val ext = entry.extension.lowercase()
        val merged = if (ext == "ass" || ext == "ssa") {
            val first = parts.first().readText(Charsets.UTF_8).trimEnd()
            val dialogue = parts.drop(1).flatMap { f -> f.readLines(Charsets.UTF_8).filter { it.trimStart().startsWith("Dialogue:", true) } }
            first + if (dialogue.isEmpty()) "" else "\n" + dialogue.joinToString("\n")
        } else {
            val separator = when (ext) {
                "srt", "vtt", "sbv", "smi", "sami", "sub" -> "\n\n"
                else -> "\n"
            }
            parts.joinToString(separator) { it.readText(Charsets.UTF_8).trimEnd() }
        }
        out.writeText(merged.trimEnd() + "\n", Charsets.UTF_8)
        return out.takeIf { it.isFile && it.length() > 0L }
    }
}
