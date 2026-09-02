package com.lilac.anime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SubtitleStore {
    private const val PREF_NAME = "lilac_subtitle_store"
    private const val IGNORED_PREFIX = "ignored_"
    private const val FONT_PATH_PREFIX = "font_path_"
    private const val FONT_SOURCE_PREFIX = "font_source_"
    private const val ALL_PATHS_PREFIX = "all_paths_"

    data class SavedSubtitle(
        val source: String,
        val path: String,
        val ignored: Boolean,
        val episodeMatch: Boolean
    )

    /** Reject stale subtitle paths whose filename explicitly identifies another episode. */
    fun pathMatchesEpisode(path: String, episodeNumber: Int): Boolean {
        val file = File(path)
        if (!file.isFile || episodeNumber <= 0) return false
        val name = file.nameWithoutExtension.lowercase(java.util.Locale.ROOT)
        // Our generated cache names contain the authoritative episode segment: _<ep>_direct / _<ep>_archive.
        val generated = Regex("_(\\d{1,3})_(?:direct|archive)(?:_|$)", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (generated != null) return generated == episodeNumber

        val patterns = listOf(
            Regex("(?:^|[^0-9])(?:episode|ep|e|#)\\s*0*(\\d{1,3})(?:$|[^0-9])", RegexOption.IGNORE_CASE),
            Regex("(?:^|[^0-9])0*(\\d{1,3})\\s*(?:화|회|편|話)(?:$|[^0-9])")
        )
        val explicit = patterns.asSequence()
            .flatMap { it.findAll(name).asSequence() }
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .firstOrNull()
        return explicit == null || explicit == episodeNumber
    }

    private fun safeEpisodeKey(episodeKey: String): String = episodeKey.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")
    private fun key(animeId: String, episodeKey: String, source: String) = "${animeId}_${safeEpisodeKey(episodeKey)}_$source"
    private fun key(animeId: String, episodeNumber: Int, source: String) = key(animeId, episodeNumber.toString(), source)
    private fun fontPathKey(animeId: String, source: String) = FONT_PATH_PREFIX + "${animeId}_$source"
    private fun fontSourceKey(animeId: String, source: String) = FONT_SOURCE_PREFIX + "${animeId}_$source"
    private fun allPathsKey(animeId: String, episodeKey: String, source: String) = ALL_PATHS_PREFIX + key(animeId, episodeKey, source)

    /**
     * Checks both the filename and ASS/SSA header metadata. Dialogue text is
     * intentionally ignored because it can legitimately mention another episode.
     * If the subtitle contains an explicit episode number in its header, it must
     * match the requested episode. With no explicit metadata we fall back to the
     * filename check.
     */
    fun subtitleMatchesEpisode(path: String, episodeKey: String, episodeNumber: Int): Boolean {
        if (!pathMatchesEpisode(path, episodeNumber)) return false
        val normalizedKey = episodeKey.lowercase(java.util.Locale.ROOT)
        val name = File(path).nameWithoutExtension.lowercase(java.util.Locale.ROOT)
        // When the subtitle filename explicitly carries a letter suffix (4a/5a),
        // require that exact suffix rather than treating it as numeric 4/5.
        val label = Regex("(?:episode|ep|e|#|_|-)\\s*0*(\\d{1,3}[a-z]?)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)
        if (label != null && label != normalizedKey) return false
        return subtitleMatchesEpisode(path, episodeNumber)
    }

    fun subtitleMatchesEpisode(path: String, episodeNumber: Int): Boolean {
        if (!pathMatchesEpisode(path, episodeNumber)) return false
        val file = File(path)
        if (!file.isFile || episodeNumber <= 0) return false
        val ext = file.extension.lowercase(java.util.Locale.ROOT)
        if (ext !in setOf("ass", "ssa")) return true
        return try {
            val text = file.inputStream().bufferedReader().use { it.readText().take(120_000) }
            val header = text.lineSequence()
                .takeWhile { !it.trim().equals("[Events]", true) }
                .filter {
                    val t = it.trimStart()
                    t.startsWith("Title:", true) ||
                        t.startsWith("Original Script:", true) ||
                        t.startsWith("Comment:", true) ||
                        t.startsWith("Notes:", true)
                }
                .joinToString(" ")
            if (header.isBlank()) return true

            val normalized = header.lowercase(java.util.Locale.ROOT)
            val explicitNumbers = mutableListOf<Int>()
            val patterns = listOf(
                Regex("(?:episode|ep)\\s*0*(\\d{1,3})\\b", RegexOption.IGNORE_CASE),
                Regex("(?:^|[^0-9])0*(\\d{1,3})\\s*(?:화|회|편|話)(?:$|[^0-9])"),
                Regex("(?:^|[^a-z0-9])e0*(\\d{1,3})(?:$|[^a-z0-9])", RegexOption.IGNORE_CASE)
            )
            patterns.forEach { regex ->
                regex.findAll(normalized).forEach { m ->
                    m.groupValues.getOrNull(1)?.toIntOrNull()?.let { explicitNumbers += it }
                }
            }
            explicitNumbers.isEmpty() || explicitNumbers.all { it == episodeNumber }
        } catch (_: Exception) {
            true
        }
    }

    suspend fun save(context: Context, animeId: String, episodeKey: String, episodeNumber: Int, source: String, path: String?) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val primaryKey = key(animeId, episodeKey, source)
        val allKey = allPathsKey(animeId, episodeKey, source)
        val existing = prefs.getStringSet(allKey, emptySet()).orEmpty().toMutableSet()
        if (path.isNullOrBlank()) {
            prefs.edit().remove(primaryKey).remove(allKey).remove(ignoredKey(animeId, episodeKey, source)).apply()
        } else {
            existing.add(path)
            prefs.edit().putString(primaryKey, path).putStringSet(allKey, existing)
                .remove(ignoredKey(animeId, episodeKey, source)).apply()
        }
    }

    /** Persist every subtitle asset discovered for this episode while keeping [primaryPath] as the default. */
    suspend fun saveAll(
        context: Context, animeId: String, episodeKey: String, episodeNumber: Int,
        source: String, paths: Collection<String>, primaryPath: String? = paths.firstOrNull()
    ) = withContext(Dispatchers.IO) {
        val valid = paths.filter { it.isNotBlank() && File(it).isFile }.distinct()
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val primaryKey = key(animeId, episodeKey, source)
        val allKey = allPathsKey(animeId, episodeKey, source)
        if (valid.isEmpty()) {
            prefs.edit().remove(primaryKey).remove(allKey).apply()
        } else {
            val primary = primaryPath?.takeIf { it in valid } ?: valid.first()
            prefs.edit().putString(primaryKey, primary).putStringSet(allKey, valid.toSet())
                .remove(ignoredKey(animeId, episodeKey, source)).apply()
        }
    }

    suspend fun get(context: Context, animeId: String, episodeKey: String, episodeNumber: Int, source: String): String? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val k = key(animeId, episodeKey, source)
        val path = prefs.getString(k, null) ?: return@withContext null
        if (prefs.getBoolean(ignoredKey(animeId, episodeKey, source), false)) return@withContext null
        if (subtitleMatchesEpisode(path, episodeKey, episodeNumber)) return@withContext path
        prefs.edit().remove(k).remove(ignoredKey(animeId, episodeKey, source)).apply()
        File(path).takeIf(File::isFile)?.delete()
        null
    }

    suspend fun list(context: Context, animeId: String, episodeKey: String, episodeNumber: Int): List<SavedSubtitle> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        listOf("linkkf", "kairan", "csora").flatMap { source ->
            val primary = prefs.getString(key(animeId, episodeKey, source), null)
            val stored = prefs.getStringSet(allPathsKey(animeId, episodeKey, source), emptySet()).orEmpty()
            val paths = (stored + listOfNotNull(primary)).distinct()
            val ignored = prefs.getBoolean(ignoredKey(animeId, episodeKey, source), false)
            paths.mapNotNull { path ->
                if (!File(path).isFile) null
                else SavedSubtitle(source, path, ignored, subtitleMatchesEpisode(path, episodeKey, episodeNumber))
            }
        }
    }

    suspend fun setIgnored(context: Context, animeId: String, episodeKey: String, episodeNumber: Int, source: String, ignored: Boolean) = withContext(Dispatchers.IO) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putBoolean(ignoredKey(animeId, episodeKey, source), ignored).apply()
    }

    suspend fun delete(context: Context, animeId: String, episodeKey: String, episodeNumber: Int, source: String) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val k = key(animeId, episodeKey, source)
        val paths = prefs.getStringSet(allPathsKey(animeId, episodeKey, source), emptySet()).orEmpty() +
            listOfNotNull(prefs.getString(k, null))
        prefs.edit().remove(k).remove(allPathsKey(animeId, episodeKey, source)).remove(ignoredKey(animeId, episodeKey, source)).apply()
        paths.forEach { File(it).takeIf(File::isFile)?.delete() }
    }

    fun getSelectedFont(context: Context, animeId: String, source: String): String? =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(fontPathKey(animeId, source), null)
            ?.takeIf { File(it).isFile }

    fun saveSelectedFont(context: Context, animeId: String, source: String, path: String?) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .apply {
                if (path.isNullOrBlank()) remove(fontPathKey(animeId, source))
                else putString(fontPathKey(animeId, source), path)
                putString(fontSourceKey(animeId, source), source)
            }.apply()
    }

    fun clearSelectedFont(context: Context, animeId: String, source: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .remove(fontPathKey(animeId, source))
            .remove(fontSourceKey(animeId, source))
            .apply()
    }
    private fun ignoredKey(animeId: String, episodeNumber: Int, source: String) = IGNORED_PREFIX + key(animeId, episodeNumber, source)
    private fun ignoredKey(animeId: String, episodeKey: String, source: String) = IGNORED_PREFIX + key(animeId, episodeKey, source)

    suspend fun save(context: Context, animeId: String, episodeNumber: Int, source: String, path: String?) =
        save(context, animeId, episodeNumber.toString(), episodeNumber, source, path)

    suspend fun get(context: Context, animeId: String, episodeNumber: Int, source: String): String? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val k = key(animeId, episodeNumber, source)
        val path = prefs.getString(k, null) ?: return@withContext null
        if (prefs.getBoolean(ignoredKey(animeId, episodeNumber, source), false)) return@withContext null
        if (subtitleMatchesEpisode(path, episodeNumber)) return@withContext path
        prefs.edit().remove(k).remove(ignoredKey(animeId, episodeNumber, source)).apply()
        File(path).takeIf(File::isFile)?.delete()
        null
    }

    suspend fun list(context: Context, animeId: String, episodeNumber: Int): List<SavedSubtitle> =
        list(context, animeId, episodeNumber.toString(), episodeNumber)

    suspend fun setIgnored(context: Context, animeId: String, episodeNumber: Int, source: String, ignored: Boolean) = withContext(Dispatchers.IO) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(ignoredKey(animeId, episodeNumber, source), ignored)
            .apply()
    }

    suspend fun delete(context: Context, animeId: String, episodeNumber: Int, source: String) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val k = key(animeId, episodeNumber, source)
        val paths = prefs.getStringSet(allPathsKey(animeId, episodeNumber.toString(), source), emptySet()).orEmpty() +
            listOfNotNull(prefs.getString(k, null))
        prefs.edit().remove(k).remove(allPathsKey(animeId, episodeNumber.toString(), source)).remove(ignoredKey(animeId, episodeNumber, source))
            .remove(fontPathKey(animeId, source)).remove(fontSourceKey(animeId, source)).apply()
        paths.forEach { File(it).takeIf(File::isFile)?.delete() }
    }
}
