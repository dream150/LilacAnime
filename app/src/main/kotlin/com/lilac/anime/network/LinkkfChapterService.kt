package com.lilac.anime.network

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import java.io.ByteArrayOutputStream
import com.lilac.anime.ChapterSkipSegment
import com.lilac.anime.Episode
import com.lilac.anime.LilacApplication
import com.lilac.anime.offlineDownloadId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Episode-to-episode chapter detector.
 *
 * No AniSkip API and no AnimeThemes API are used here.  The detector compares
 * the audio of the current Linkkf episode with other episodes of the SAME anime.
 * Repeated audio near the beginning is treated as an OP candidate; repeated audio
 * near the end is treated as an ED candidate.
 */
object LinkkfChapterService {
    private const val TAG = "EpisodeChapters"
    private const val WINDOW_SECONDS = 300.0
    private const val FINGERPRINT_BINS = 32
    private const val FINGERPRINT_FRAME_SECONDS = 0.1
    private const val FFT_SIZE = 512
    // OP/ED detection is intentionally capped so a bad cross-episode match
    // cannot turn a large portion of an episode into a skip chapter.
    private const val MAX_DETECTED_CHAPTER_SECONDS = 85.0
    // Ignore candidate matches whose position is too far from the consensus
    // position of the other comparison episodes.
    private const val POSITION_OUTLIER_TOLERANCE_SECONDS = 60.0
    private const val MIN_MATCH_SECONDS = 30.0
    private const val PLAY_REFERER = "https://play.sub3.top/"
    private const val PLAY_ORIGIN = "https://play.sub3.top"
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class Match(val startSeconds: Double, val endSeconds: Double, val score: Double)

    /**
     * Online OP/ED analysis is intentionally disabled.
     * OP/ED detection is performed only for episodes that are already stored offline.
     */
    suspend fun detectSkipSegments(
        context: Context,
        animeId: String,
        currentEpisode: Episode,
        streamUrl: String,
        episodeDurationSeconds: Int,
        episodes: List<Episode>,
        onStatus: (String) -> Unit = {}
    ): List<ChapterSkipSegment> {
        Log.d(TAG, "ONLINE_OP_ED_DISABLED episode=${currentEpisode.number}")
        return emptyList()
    }

    private data class OnlineAnalysisWindows(
        val head: File,
        val tail: File,
        val tailStartSeconds: Double,
        val totalDurationSeconds: Double
    )

    private data class OnlineFingerprint(
        val combined: FloatArray,
        val headFrames: Int,
        val tailStartSeconds: Double,
        val totalDurationSeconds: Double
    )

    private fun materializeOnlineAnalysisWindows(
        context: Context,
        url: String,
        seconds: Double
    ): OnlineAnalysisWindows? = runCatching {
        val playlistResult = fetchPlaylistIfHls(url) ?: return@runCatching null
        var mediaUrl = playlistResult.url
        var playlist = playlistResult.text
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val variants = parseMasterVariants(playlist, mediaUrl)
            mediaUrl = variants.maxByOrNull { it.bandwidth }?.url ?: return@runCatching null
            playlist = getText(mediaUrl)
        }
        val segments = parseMediaSegments(playlist, mediaUrl)
        val total = segments.lastOrNull()?.end ?: return@runCatching null
        val headEnd = min(seconds, total)
        val tailStart = max(0.0, total - seconds)
        statusLog("ONLINE_HLS_WINDOWS head=0..$headEnd tail=$tailStart..$total total=$total")
        val head = materializeHlsWindow(context, mediaUrl, 0.0, headEnd, playlist)
            ?: return@runCatching null
        val tail = materializeHlsWindow(context, mediaUrl, tailStart, total, playlist)
        if (tail == null) {
            head.delete()
            return@runCatching null
        }
        OnlineAnalysisWindows(head, tail, tailStart, total)
    }.onFailure {
        Log.e(TAG, "ONLINE_ANALYSIS_WINDOWS_FAILED ${it.javaClass.simpleName}: ${it.message}", it)
    }.getOrNull()

    private fun fingerprintOnlineWindows(
        windows: OnlineAnalysisWindows,
        status: (String) -> Unit
    ): OnlineFingerprint? {
        val head = decodeFingerprint(windows.head, 0.0, WINDOW_SECONDS)
        val tail = decodeFingerprint(windows.tail, 0.0, WINDOW_SECONDS)
        if (head == null || tail == null) return null
        val combined = FloatArray(head.size + tail.size)
        head.copyInto(combined, 0)
        tail.copyInto(combined, head.size)
        val headFrames = fingerprintFrames(head)
        status("ONLINE_FINGERPRINT_WINDOWS_OK headFrames=$headFrames tailFrames=${fingerprintFrames(tail)} combinedFrames=${fingerprintFrames(combined)} tailStart=${windows.tailStartSeconds}")
        return OnlineFingerprint(combined, headFrames, windows.tailStartSeconds, windows.totalDurationSeconds)
    }

    private fun deleteOnlineWindows(windows: OnlineAnalysisWindows) {
        try { windows.head.delete() } catch (_: Throwable) { }
        try { windows.tail.delete() } catch (_: Throwable) { }
    }

    private fun matchSavedOnlineFingerprintTemplate(
        current: OnlineFingerprint,
        template: OfflineOpEdFingerprintStore.Template,
        duration: Double,
        status: (String) -> Unit
    ): List<ChapterSkipSegment> {
        val out = ArrayList<ChapterSkipSegment>()
        val headEndFrame = current.headFrames.coerceAtMost(fingerprintFrames(current.combined))
        val opCurrent = if (headEndFrame > 0) current.combined.copyOfRange(0, headEndFrame * FINGERPRINT_BINS) else FloatArray(0)
        val opMatch = template.op?.let {
            scoreTemplateAgainstCurrent(opCurrent, it, "OP", status, minStartSeconds = 0.0)
        }
        opMatch?.let {
            val start = it.startSeconds.coerceIn(0.0, duration)
            val end = it.endSeconds.coerceIn(start, duration)
            if (end > start) out += ChapterSkipSegment("op", start, end, duration)
        }

        // ED is searched only in the tail window, which is physically after the
        // head window used for OP. This keeps the previous OP-before-ED rule.
        val tailOffset = current.headFrames * FINGERPRINT_FRAME_SECONDS
        val tailStartFrame = headEndFrame
        val tailFrames = fingerprintFrames(current.combined) - tailStartFrame
        val edCurrent = if (tailFrames > 0) {
            current.combined.copyOfRange(tailStartFrame * FINGERPRINT_BINS, current.combined.size)
        } else FloatArray(0)
        val edSearchStart = opMatch?.endSeconds?.coerceIn(0.0, duration) ?: 0.0
        status("FINGERPRINT_ED_SEARCH_RANGE start=$edSearchStart end=$duration tailStart=${current.tailStartSeconds}")
        template.ed?.let {
            scoreTemplateAgainstCurrent(edCurrent, it, "ED", status, minStartSeconds = 0.0)?.let { m ->
                val start = (current.tailStartSeconds + m.startSeconds).coerceIn(edSearchStart, duration)
                val end = (current.tailStartSeconds + m.endSeconds).coerceIn(start, duration)
                if (end > start && start >= edSearchStart) {
                    out += ChapterSkipSegment("ed", start, end, duration)
                }
            }
        }
        return out.filter { it.endTime > it.startTime }.sortedBy { it.startTime }
    }


    private fun materializeFullHlsEpisode(context: Context, url: String): File? = runCatching {
        val playlistResult = fetchPlaylistIfHls(url) ?: return@runCatching download(context, url, "episode_full")
        var mediaUrl = playlistResult.url
        var playlist = playlistResult.text
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val variants = parseMasterVariants(playlist, mediaUrl)
            mediaUrl = variants.maxByOrNull { it.bandwidth }?.url ?: return@runCatching null
            playlist = getText(mediaUrl)
        }
        val segments = parseMediaSegments(playlist, mediaUrl)
        val total = segments.lastOrNull()?.end ?: return@runCatching null
        statusLog("ONLINE_FULL_HLS_WINDOW start=0 end=$total segments=${segments.size}")
        materializeHlsWindow(context, mediaUrl, 0.0, total, playlist)
    }.onFailure {
        Log.e(TAG, "ONLINE_FULL_EPISODE_FAILED ${it.javaClass.simpleName}: ${it.message}", it)
    }.getOrNull()

    private fun durationForHls(context: Context, url: String): Double? = runCatching {
        val p = fetchPlaylistIfHls(url) ?: return@runCatching null
        var mediaUrl = p.url
        var text = p.text
        if (text.contains("#EXT-X-STREAM-INF")) {
            val v = parseMasterVariants(text, mediaUrl).maxByOrNull { it.bandwidth } ?: return@runCatching null
            mediaUrl = v.url
            text = getText(mediaUrl)
        }
        parseMediaSegments(text, mediaUrl).lastOrNull()?.end
    }.getOrNull()

    private fun cosine(a: FloatArray, ai: Int, b: FloatArray, bi: Int, length: Int): Double {
        if (length <= 0 || ai < 0 || bi < 0 || ai + length > a.size || bi + length > b.size) return 0.0
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (k in 0 until length) {
            val x = a[ai + k].toDouble()
            val y = b[bi + k].toDouble()
            dot += x * y
            aa += x * x
            bb += y * y
        }
        return if (aa <= 1e-9 || bb <= 1e-9) 0.0 else dot / kotlin.math.sqrt(aa * bb)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun matchSavedFingerprintTemplate(
        current: FloatArray,
        template: OfflineOpEdFingerprintStore.Template,
        duration: Double,
        status: (String) -> Unit
    ): List<ChapterSkipSegment> {
        val out = ArrayList<ChapterSkipSegment>()
        val opMatch = template.op?.let { scoreTemplateAgainstCurrent(current, it, "OP", status, minStartSeconds = 0.0) }
        opMatch?.let {
            val end = it.endSeconds.coerceIn(it.startSeconds, duration)
            out += ChapterSkipSegment("op", it.startSeconds.coerceIn(0.0, duration), end, duration)
        }
        val edStart = opMatch?.endSeconds?.coerceIn(0.0, duration) ?: 0.0
        status("FINGERPRINT_ED_SEARCH_RANGE start=$edStart end=$duration")
        template.ed?.let {
            scoreTemplateAgainstCurrent(current, it, "ED", status, minStartSeconds = edStart)?.let { m ->
                val start = m.startSeconds.coerceIn(edStart, duration)
                val end = m.endSeconds.coerceIn(start, duration)
                if (end > start) out += ChapterSkipSegment("ed", start, end, duration)
            }
        }
        return out.filter { it.endTime > it.startTime }.sortedBy { it.startTime }
    }


    private fun trainFingerprintTemplates(
        fps: List<Pair<Episode, FloatArray>>,
        status: (String) -> Unit
    ): Pair<FloatArray?, FloatArray?>? {
            // Build real audio templates from complete-episode repetition.
            // IMPORTANT: no episode timestamp is averaged or persisted here.  Every
            // candidate is an actual slice of audio fingerprint data.  We validate
            // that the same audio occurs in every training episode, then cluster
            // equivalent fingerprints so OP/ED are selected by recurrence rather
            // than by their absolute position in any one episode.
            data class Occurrence(
                val episodeId: String,
                val episodeNumber: Int,
                val startSeconds: Double,
                val endSeconds: Double,
                val score: Double
            )
            data class TrainingCandidate(
                val fingerprint: FloatArray,
                val occurrences: MutableList<Occurrence>,
                val quality: Double
            )
            data class TrainingCluster(
                var fingerprint: FloatArray,
                val occurrences: MutableList<Occurrence>,
                var quality: Double
            )

            val candidates = ArrayList<TrainingCandidate>()
            for (aIndex in fps.indices) {
                for (bIndex in aIndex + 1 until fps.size) {
                    val found = findTopRepeatedRegions(fps[aIndex].second, fps[bIndex].second, 12)
                    status(
                        "FINGERPRINT_TRAIN_PAIR a=${fps[aIndex].first.number} " +
                            "b=${fps[bIndex].first.number} candidates=${found.size}"
                    )

                    for (seed in found) {
                        val templateCandidate = fps[aIndex].second
                            .sliceFingerprint(seed.startSeconds, seed.endSeconds)
                        if (templateCandidate.size < FINGERPRINT_BINS * 300) continue // at least 30 seconds

                        val occurrences = ArrayList<Occurrence>()
                        var qualitySum = 0.0
                        var valid = true

                        // Search the ENTIRE fingerprint of every training episode for
                        // this exact audio template.  The location is only an
                        // occurrence discovered during training; it is never stored.
                        for ((episode, fingerprint) in fps) {
                            val match = scoreTemplateAgainstCurrent(
                                fingerprint,
                                templateCandidate,
                                "TRAIN_OCCURRENCE_${episode.number}",
                                { message: String -> status(message) }
                            )
                            if (match == null || match.score < 0.82 || match.endSeconds <= match.startSeconds) {
                                valid = false
                                break
                            }
                            occurrences += Occurrence(
                                episode.id,
                                episode.number,
                                match.startSeconds,
                                match.endSeconds,
                                match.score
                            )
                            qualitySum += match.score
                        }

                        if (valid && occurrences.size == fps.size) {
                            candidates += TrainingCandidate(
                                templateCandidate,
                                occurrences,
                                qualitySum / occurrences.size.coerceAtLeast(1)
                            )
                        }
                    }
                }
            }

            if (candidates.isEmpty()) {
                status("FINGERPRINT_TRAINING_FAILED no_common_audio_templates")
                return null
            }

            // Merge the same OP/ED audio found from different episode pairs.
            // Clustering is based on the fingerprint samples themselves, never on
            // start/end timestamps.  This is what allows episode 1 to have the OP
            // at a completely different position from the other episodes.
            val clusters = ArrayList<TrainingCluster>()
            for (candidate in candidates.sortedByDescending { it.quality }) {
                var matchedCluster: TrainingCluster? = null
                for (cluster in clusters) {
                    val minLength = minOf(candidate.fingerprint.size, cluster.fingerprint.size)
                    val maxLength = maxOf(candidate.fingerprint.size, cluster.fingerprint.size)
                    if (minLength < 300 || minLength.toDouble() / maxLength.toDouble() < 0.60) continue
                    val similarity = cosine(
                        candidate.fingerprint, 0,
                        cluster.fingerprint, 0,
                        minLength
                    )
                    if (similarity >= 0.90) {
                        matchedCluster = cluster
                        break
                    }
                }

                if (matchedCluster == null) {
                    clusters += TrainingCluster(
                        candidate.fingerprint.copyOf(),
                        ArrayList(candidate.occurrences),
                        candidate.quality
                    )
                } else {
                    // Keep the best-quality actual audio fingerprint as the
                    // representative; merge only the discovered occurrences.
                    for (occurrence in candidate.occurrences) {
                        val old = matchedCluster.occurrences.indexOfFirst { it.episodeId == occurrence.episodeId }
                        if (old < 0) {
                            matchedCluster.occurrences += occurrence
                        } else if (occurrence.score > matchedCluster.occurrences[old].score) {
                            matchedCluster.occurrences[old] = occurrence
                        }
                    }
                    if (candidate.quality > matchedCluster.quality) {
                        matchedCluster.fingerprint = candidate.fingerprint.copyOf()
                        matchedCluster.quality = candidate.quality
                    }
                }
            }

            val recurring = clusters
                .filter { it.occurrences.size >= fps.size }
                .sortedByDescending { cluster ->
                    val averageScore = cluster.occurrences.map { it.score }.average()
                    averageScore + (cluster.occurrences.size.toDouble() / fps.size.toDouble()) * 0.10
                }

            if (recurring.isEmpty()) {
                status("FINGERPRINT_TRAINING_FAILED no_recurring_templates")
                return null
            }

            // Select two DISTINCT recurring audio templates.  OP/ED labels are
            // assigned from the temporal order of their discovered occurrences in
            // the majority of training episodes.  We do NOT average those times.
            // If episode 1 places OP at the end, its occurrence simply votes in the
            // opposite order; the majority of the remaining episodes can still
            // identify the two templates correctly.
            var opCluster: TrainingCluster? = null
            var edCluster: TrainingCluster? = null
            var bestPairScore = Double.NEGATIVE_INFINITY

            for (i in recurring.indices) {
                for (j in i + 1 until recurring.size) {
                    val a = recurring[i]
                    val b = recurring[j]
                    var aEarlier = 0
                    var bEarlier = 0
                    var comparable = 0
                    for (episode in fps) {
                        val ao = a.occurrences.firstOrNull { it.episodeId == episode.first.id }
                        val bo = b.occurrences.firstOrNull { it.episodeId == episode.first.id }
                        if (ao != null && bo != null) {
                            comparable++
                            when {
                                ao.startSeconds + 5.0 < bo.startSeconds -> aEarlier++
                                bo.startSeconds + 5.0 < ao.startSeconds -> bEarlier++
                            }
                        }
                    }
                    if (comparable == 0) continue

                    val earlierRatio = maxOf(aEarlier, bEarlier).toDouble() / comparable.toDouble()
                    val pairScore =
                        (a.quality + b.quality) * 0.5 +
                            earlierRatio * 0.20 +
                            minOf(a.occurrences.size, b.occurrences.size).toDouble() /
                                fps.size.toDouble() * 0.10
                    if (pairScore > bestPairScore && earlierRatio >= 0.60) {
                        bestPairScore = pairScore
                        if (aEarlier >= bEarlier) {
                            opCluster = a
                            edCluster = b
                        } else {
                            opCluster = b
                            edCluster = a
                        }
                    }
                }
            }

            // If the two templates have no stable majority ordering, do not average
            // timestamps. Pick the strongest distinct pair and use only the earliest
            // observed occurrence as a deterministic fallback. These timestamps are
            // training-only observations and are never persisted.
            if (opCluster == null || edCluster == null) {
                val pair = recurring.take(2)
                if (pair.size < 2) {
                    status("FINGERPRINT_TRAINING_FAILED only_one_recurring_template")
                    return null
                }
                val first = pair[0]
                val second = pair[1]
                val firstFirst = first.occurrences.minOf { it.startSeconds }
                val secondFirst = second.occurrences.minOf { it.startSeconds }
                if (firstFirst <= secondFirst) {
                    opCluster = first
                    edCluster = second
                } else {
                    opCluster = second
                    edCluster = first
                }
                status("FINGERPRINT_TRAIN_ORDER_FALLBACK")
            }

            val opTemplate = opCluster?.fingerprint?.copyOf()
            val edTemplate = edCluster?.fingerprint?.copyOf()
            status(
                "FINGERPRINT_TEMPLATES_SELECTED " +
                    "opSeconds=${opTemplate?.let { fingerprintFrames(it) * FINGERPRINT_FRAME_SECONDS } ?: 0.0} " +
                    "edSeconds=${edTemplate?.let { fingerprintFrames(it) * FINGERPRINT_FRAME_SECONDS } ?: 0.0} " +
                    "clusters=${recurring.size}"
            )
            opCluster?.let { cluster ->
                status("FINGERPRINT_OP_OCCURRENCES " + cluster.occurrences.joinToString(";") {
                    "ep=${it.episodeNumber}@${it.startSeconds}-${it.endSeconds},score=${it.score}"
                })
            }
            edCluster?.let { cluster ->
                status("FINGERPRINT_ED_OCCURRENCES " + cluster.occurrences.joinToString(";") {
                    "ep=${it.episodeNumber}@${it.startSeconds}-${it.endSeconds},score=${it.score}"
                })
            }


            return opTemplate to edTemplate
    }

    private fun decodeFingerprint(file: File, start: Double, duration: Double): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.seekTo((start * 1_000_000L).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            // Decode and fingerprint incrementally. Never keep the complete PCM
            // stream in memory: a full episode can contain tens of millions of samples.
            val estimatedFrames = (duration / FINGERPRINT_FRAME_SECONDS).toInt() + 4
            val fingerprint = StreamingLogMelFingerprint(sampleRate, estimatedFrames)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val limitUs = ((start + duration) * 1_000_000L).toLong()

            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val input = codec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(input, 0)
                        val sampleTime = extractor.sampleTime
                        if (size < 0 || sampleTime < 0 || sampleTime > limitUs) {
                            codec.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val idx = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (idx >= 0) {
                        val out = codec.getOutputBuffer(idx)
                        if (out != null && info.size > 0) appendPcmMonoStreaming(out, info, channels, fingerprint)
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(idx, false)
                        if (eos) outputDone = true
                    }
                }
            }
            val result = fingerprint.finish()
            Log.d(TAG, "FINGERPRINT_LOGMEL_OK file=${file.name} frames=${result?.size?.div(FINGERPRINT_BINS) ?: 0} bins=$FINGERPRINT_BINS sampleRate=$sampleRate")
            if (result == null || result.size < FINGERPRINT_BINS * 10) null else result
        } catch (e: Exception) {
            Log.w(TAG, "PCM_DECODE_FAILED ${file.name}: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            extractor.release()
        }
    }

    private fun appendPcmMonoStreaming(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        fingerprint: StreamingLogMelFingerprint
    ) {
        val duplicate = buffer.duplicate()
        val begin = info.offset.coerceAtLeast(0)
        val end = (info.offset + info.size).coerceAtMost(buffer.capacity())
        if (begin >= end) return
        duplicate.position(begin)
        duplicate.limit(end)
        while (duplicate.remaining() >= channels * 2) {
            var sum = 0.0
            repeat(channels) { sum += duplicate.short.toInt() }
            fingerprint.append((sum / channels / 32768.0).toFloat())
        }
    }

    private class StreamingLogMelFingerprint(
        sampleRate: Int,
        estimatedFrames: Int
    ) {
        private val hop = max(1, (sampleRate * FINGERPRINT_FRAME_SECONDS).toInt())
        private val frameSize = FFT_SIZE
        private val melBank = buildMelFilterBank(sampleRate, frameSize, FINGERPRINT_BINS)
        private val window = DoubleArray(frameSize) { i -> 0.5 - kotlin.math.cos(2.0 * Math.PI * i / (frameSize - 1)) * 0.5 }
        private val real = DoubleArray(frameSize)
        private val imag = DoubleArray(frameSize)
        private val mel = DoubleArray(FINGERPRINT_BINS)
        private var pending = FloatArray(frameSize)
        private var pendingSize = 0
        private var skip = 0
        private var output = FloatArray(max(FINGERPRINT_BINS * 16, estimatedFrames * FINGERPRINT_BINS))
        private var outputSize = 0

        fun append(sample: Float) {
            if (skip > 0) { skip--; return }
            if (pendingSize == pending.size) pending = pending.copyOf(pending.size * 2)
            pending[pendingSize++] = sample
            if (pendingSize >= frameSize) {
                appendFrame(pending)
                pendingSize = 0
                skip = max(0, hop - frameSize)
            }
        }

        private fun appendFrame(samples: FloatArray) {
            for (i in 0 until frameSize) { real[i] = samples[i].toDouble() * window[i]; imag[i] = 0.0 }
            fft(real, imag)
            java.util.Arrays.fill(mel, 0.0)
            for (bin in 0..frameSize / 2) {
                val power = real[bin] * real[bin] + imag[bin] * imag[bin]
                for (m in 0 until FINGERPRINT_BINS) mel[m] += power * melBank[m][bin]
            }
            var mean = 0.0
            for (m in 0 until FINGERPRINT_BINS) { mel[m] = kotlin.math.ln(mel[m] + 1e-10); mean += mel[m] }
            mean /= FINGERPRINT_BINS
            var variance = 0.0
            for (m in 0 until FINGERPRINT_BINS) { mel[m] -= mean; variance += mel[m] * mel[m] }
            val scale = kotlin.math.sqrt(variance / FINGERPRINT_BINS + 1e-8)
            ensureCapacity(outputSize + FINGERPRINT_BINS)
            for (m in 0 until FINGERPRINT_BINS) output[outputSize++] = (mel[m] / scale).toFloat()
        }

        private fun ensureCapacity(required: Int) {
            if (required <= output.size) return
            var capacity = output.size
            while (capacity < required) capacity *= 2
            output = output.copyOf(capacity)
        }

        fun finish(): FloatArray? = if (outputSize < FINGERPRINT_BINS * 10) null else output.copyOf(outputSize)
    }

    private fun buildMelFilterBank(sampleRate: Int, fftSize: Int, bands: Int): Array<DoubleArray> {
        fun hzToMel(hz: Double) = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
        val low = hzToMel(20.0)
        val high = hzToMel(sampleRate / 2.0)
        val points = IntArray(bands + 2) { i ->
            val hz = melToHz(low + (high - low) * i / (bands + 1))
            ((fftSize + 1) * hz / sampleRate).toInt().coerceIn(0, fftSize / 2)
        }
        return Array(bands) { m ->
            val row = DoubleArray(fftSize / 2 + 1)
            val left = points[m]
            val center = points[m + 1]
            val right = points[m + 2]
            for (k in left until center) if (center > left) row[k] = (k - left).toDouble() / (center - left)
            for (k in center..right) if (right > center && k < row.size) row[k] = (right - k).toDouble() / (right - center)
            row
        }
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wLenR = kotlin.math.cos(angle)
            val wLenI = kotlin.math.sin(angle)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * wr - imag[i + k + len / 2] * wi
                    val vI = real[i + k + len / 2] * wi + imag[i + k + len / 2] * wr
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR
                    imag[i + k + len / 2] = uI - vI
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun Double.pow(exp: Double): Double = kotlin.math.exp(exp * kotlin.math.ln(this))

    private data class TailWindow(val file: File, val startSeconds: Double, val endSeconds: Double)

    private fun materializeTailWindow(context: Context, url: String, seconds: Double): TailWindow? {
        Log.d(TAG, "MATERIALIZE_TAIL_WINDOW_START url=$url seconds=$seconds")
        return runCatching {
            val playlistResult = fetchPlaylistIfHls(url)
            if (playlistResult == null) {
                Log.w(TAG, "MATERIALIZE_TAIL_NOT_HLS url=$url")
                return@runCatching null
            }
            var mediaUrl = playlistResult.url
            var playlist = playlistResult.text
            if (playlist.contains("#EXT-X-STREAM-INF")) {
                val variants = parseMasterVariants(playlist, mediaUrl)
                Log.d(TAG, "HLS_MASTER_VARIANTS count=${variants.size} url=$mediaUrl")
                mediaUrl = variants.maxByOrNull { it.bandwidth }?.url ?: return@runCatching null
                playlist = getText(mediaUrl)
            }
            val segments = parseMediaSegments(playlist, mediaUrl)
            Log.d(TAG, "HLS_MEDIA_PLAYLIST segments=${segments.size} url=$mediaUrl")
            val total = segments.lastOrNull()?.end ?: return@runCatching null
            val actualStart = max(0.0, total - seconds)
            Log.d(TAG, "HLS_ACTUAL_DURATION playlist=${total}s requestedWindow=${seconds}s tailStart=${actualStart}s tailEnd=${total}s")
            materializeHlsWindow(context, mediaUrl, actualStart, total, playlist)?.let { file ->
                TailWindow(file, actualStart, total)
            }
        }.onFailure {
            Log.e(TAG, "MATERIALIZE_TAIL_FAILED ${it.javaClass.simpleName}: ${it.message}", it)
        }.getOrNull()
    }

    private fun materializeWindow(context: Context, url: String, start: Double, end: Double): File? {
        Log.d(TAG, "MATERIALIZE_WINDOW_START url=$url start=$start end=$end")
        return runCatching {
            val playlistResult = fetchPlaylistIfHls(url)
            if (playlistResult != null) {
                Log.d(TAG, "MATERIALIZE_HLS_DETECTED url=${playlistResult.url} contentType=${playlistResult.contentType}")
                var mediaUrl = playlistResult.url
                var playlist = playlistResult.text
                if (playlist.contains("#EXT-X-STREAM-INF")) {
                    val variants = parseMasterVariants(playlist, mediaUrl)
                    Log.d(TAG, "HLS_MASTER_VARIANTS count=${variants.size} url=$mediaUrl")
                    mediaUrl = variants.maxByOrNull { it.bandwidth }?.url
                        ?: throw IllegalStateException("HLS master has no variants")
                    playlist = getText(mediaUrl)
                    Log.d(TAG, "HLS_VARIANT_SELECTED url=$mediaUrl")
                }
                return@runCatching materializeHlsWindow(context, mediaUrl, start, end, playlist)
            }
            Log.d(TAG, "MATERIALIZE_NOT_HLS_DIRECT_DOWNLOAD url=$url")
            download(context, url, "episode")
        }.onFailure {
            Log.e(TAG, "MATERIALIZE_WINDOW_FAILED ${it.javaClass.simpleName}: ${it.message}", it)
        }.getOrNull()
    }

    private data class PlaylistResult(val url: String, val text: String, val contentType: String?)

    /**
     * Detects HLS by both URL and HTTP response. Some Linkkf stream URLs are
     * redirects or do not contain the literal `.m3u8`, so URL-only detection is
     * insufficient and silently sent the playlist through the binary downloader.
     */
    private fun fetchPlaylistIfHls(url: String): PlaylistResult? {
        val request = buildHlsRequest(url).build()
        http.newCall(request).execute().use { response ->
            val contentType = response.header("Content-Type")
            val finalUrl = response.request.url.toString()
            Log.d(TAG, "HLS_PROBE_HTTP code=${response.code} type=$contentType finalUrl=$finalUrl requestedUrl=$url")
            if (!response.isSuccessful) {
                throw IllegalStateException("HLS probe HTTP ${response.code} url=$url")
            }
            val body = response.body ?: throw IllegalStateException("HLS probe empty body url=$url")
            val bytes = body.bytes()
            val prefix = bytes.copyOfRange(0, min(bytes.size, 4096)).toString(Charsets.UTF_8)
            val looksLikePlaylist = prefix.trimStart().startsWith("#EXTM3U") ||
                contentType?.contains("mpegurl", true) == true ||
                contentType?.contains("vnd.apple.mpegurl", true) == true
            if (!looksLikePlaylist) return null
            return PlaylistResult(finalUrl, bytes.toString(Charsets.UTF_8), contentType)
        }
    }

    private fun buildHlsRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
        .header("Referer", PLAY_REFERER)
        .header("Origin", PLAY_ORIGIN)
        .header("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, */*")

    /**
     * Downloads only the HLS segments overlapping the requested timeline window and
     * concatenates MPEG-TS segments into a temporary file. This avoids downloading a
     * complete episode just to fingerprint the OP/ED search area.
     */
    private fun materializeHlsWindow(
        context: Context,
        playlistUrl: String,
        start: Double,
        end: Double,
        suppliedPlaylist: String? = null
    ): File? = runCatching {
        var mediaUrl = playlistUrl
        var playlist = suppliedPlaylist ?: getText(mediaUrl)
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val variants = parseMasterVariants(playlist, mediaUrl)
            mediaUrl = variants.maxByOrNull { it.bandwidth }?.url ?: return null
            playlist = getText(mediaUrl)
        }

        // Encrypted HLS cannot be concatenated safely without implementing key/decrypt
        // handling. Fail cleanly rather than producing a false fingerprint.
        if (playlist.contains("#EXT-X-KEY") && !playlist.contains("METHOD=NONE")) {
            Log.w(TAG, "HLS_ENCRYPTED_UNSUPPORTED")
            return null
        }

        val segments = parseMediaSegments(playlist, mediaUrl)
        if (segments.isEmpty()) return null
        val selected = segments.filter { it.end > start && it.start < end }
        if (selected.isEmpty()) return null

        // fMP4 HLS requires the EXT-X-MAP initialization segment before media
        // fragments. Without it MediaExtractor sees a file full of fragments but
        // cannot discover the audio track, which results in a null fingerprint.
        val initUrl = parseInitSegmentUrl(playlist, mediaUrl)
        val firstUrl = selected.first().url.substringBefore('?')
        val extension = if (firstUrl.endsWith(".ts", true)) ".ts" else ".mp4"
        val file = File.createTempFile("hls_window_", extension, context.cacheDir)
        file.outputStream().buffered().use { output ->
            if (initUrl != null) {
                statusLog("HLS_INIT_SEGMENT url=$initUrl")
                downloadBytes(initUrl, output)
            }
            for (segment in selected) {
                downloadBytes(segment.url, output)
            }
        }
        Log.d(TAG, "HLS_WINDOW start=$start end=$end segments=${selected.size} first=${selected.first().url} last=${selected.last().url} file=${file.length()}")
        if (file.length() == 0L) {
            file.delete()
            throw IllegalStateException("HLS window produced empty file")
        }
        file
    }.onFailure {
        Log.e(TAG, "HLS_WINDOW_FAILED ${it.javaClass.simpleName}: ${it.message}", it)
    }.getOrNull()

    private data class HlsVariant(val url: String, val bandwidth: Long)
    private data class HlsSegment(val url: String, val start: Double, val end: Double)

    private fun parseMasterVariants(text: String, baseUrl: String): List<HlsVariant> {
        val lines = text.lineSequence().map { it.trim() }.toList()
        val out = ArrayList<HlsVariant>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("(?:AVERAGE-)?BANDWIDTH=(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
            out += HlsVariant(resolveUrl(baseUrl, next), bw)
        }
        return out
    }

    private fun parseInitSegmentUrl(text: String, baseUrl: String): String? {
        val line = text.lineSequence().firstOrNull { it.trim().startsWith("#EXT-X-MAP:") } ?: return null
        val match = Regex("URI=\"([^\"]+)\"").find(line) ?: return null
        return resolveUrl(baseUrl, match.groupValues[1])
    }

    private fun statusLog(message: String) {
        Log.d(TAG, message)
    }

    private fun downloadBytes(url: String, output: java.io.OutputStream) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .header("Referer", PLAY_REFERER)
            .header("Origin", PLAY_ORIGIN)
            .header("Accept", "*/*")
            .build()
        http.newCall(request).execute().use { response ->
            Log.d(TAG, "HLS_HTTP code=${response.code} bytes=${response.body?.contentLength()} url=$url")
            if (!response.isSuccessful) throw IllegalStateException("HLS segment HTTP ${response.code} url=$url")
            val body = response.body ?: throw IllegalStateException("Empty HLS segment body url=$url")
            body.byteStream().use { it.copyTo(output) }
        }
    }

    private fun parseMediaSegments(text: String, baseUrl: String): List<HlsSegment> {
        val out = ArrayList<HlsSegment>()
        var cursor = 0.0
        var pendingDuration: Double? = null
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                }
                line.isNotBlank() && !line.startsWith("#") && pendingDuration != null -> {
                    val duration = pendingDuration ?: return@forEach
                    out += HlsSegment(resolveUrl(baseUrl, line), cursor, cursor + duration)
                    cursor += duration
                    pendingDuration = null
                }
            }
        }
        return out
    }

    private fun resolveUrl(base: String, child: String): String = URI(base).resolve(child).toString()

    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .header("Referer", PLAY_REFERER)
            .header("Origin", PLAY_ORIGIN)
            .header("Accept", "*/*")
            .build()
        http.newCall(request).execute().use { response ->
            Log.d(TAG, "HLS_PLAYLIST_HTTP code=${response.code} url=$url")
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} playlist=$url")
            return (response.body ?: throw IllegalStateException("Empty response body playlist=$url")).string()
        }
    }

    private fun download(context: Context, url: String, prefix: String): File? = runCatching {
        Log.d(TAG, "DIRECT_DOWNLOAD_START url=$url")
        val request = buildHlsRequest(url).header("Accept", "*/*").build()
        http.newCall(request).execute().use { response ->
            Log.d(TAG, "DIRECT_DOWNLOAD_HTTP code=${response.code} type=${response.header("Content-Type")} bytes=${response.body?.contentLength()} finalUrl=${response.request.url}")
            if (!response.isSuccessful) throw IllegalStateException("Direct download HTTP ${response.code} url=$url")
            val ext = response.request.url.toString().substringBefore('?').substringAfterLast('.', "bin").take(5)
            val file = File.createTempFile("${prefix}_", ".${ext}", context.cacheDir)
            val body = response.body ?: throw IllegalStateException("Empty response body url=$url")
            body.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            Log.d(TAG, "DIRECT_DOWNLOAD_OK bytes=${file.length()} file=${file.name}")
            file
        }
    }.onFailure {
        Log.e(TAG, "DIRECT_DOWNLOAD_FAILED ${it.javaClass.simpleName}: ${it.message}", it)
    }.getOrNull()

    /**
     * Offline-only OP/ED detector.
     *
     * This path never opens WebView and never performs HTTP requests. It uses the
     * Media3 DownloadManager index to find completed downloads for the same anime,
     * reads the cached HLS playlists/segments from LilacApplication.downloadCache,
     * builds front/tail audio fingerprints, and compares the current episode with
     * up to five nearby downloaded episodes.
     */
    /**
     * Offline OP/ED detector using real audio fingerprints.
     *
     * The persistent store contains only the fingerprint samples. Episode
     * timestamps are never persisted. During first training we compare a few
     * complete downloaded episodes, choose the earliest and latest strong
     * repeated regions as OP/ED templates, and persist those audio samples.
     * Later episodes are scanned as a whole and matched against the templates.
     * No training-time start/end value is persisted; positions are only temporary
     * observations used to distinguish the earlier recurring template (OP) from
     * the later recurring template (ED).
     */
    @OptIn(UnstableApi::class)
    suspend fun detectSkipSegmentsOffline(
        context: Context,
        animeId: String,
        currentEpisode: Episode,
        episodes: List<Episode>,
        episodeDurationSeconds: Int,
        onStatus: (String) -> Unit = {}
    ): List<ChapterSkipSegment> = withContext(Dispatchers.Default) {
        fun status(message: String) { Log.d(TAG, message); onStatus(message) }
        runCatching {
            // The player supplies the real duration during foreground playback.
            // Background analysis may not have that value yet, so 0 means
            // "derive it from the decoded fingerprint" below.
            var duration = episodeDurationSeconds.toDouble()

            // If this episode was already analyzed in the background, reuse the
            // persisted result immediately. This makes subsequent playback skip
            // fingerprint analysis entirely.
            OfflineOpEdResultStore.load(context, animeId, currentEpisode.id)?.let { cached ->
                status("FINGERPRINT_CACHED_RESULT_HIT episode=${currentEpisode.number} segments=${cached.size}")
                return@runCatching cached.map {
                    ChapterSkipSegment(it.type, it.startTime, it.endTime, duration)
                }
            }

            status("FINGERPRINT_ANALYSIS_START episode=${currentEpisode.number} duration=${duration}s")

            val template = OfflineOpEdFingerprintStore.load(context, animeId)
            if (template != null && (template.op != null || template.ed != null)) {
                status("FINGERPRINT_TEMPLATE_HIT format=audio-only opSamples=${template.op?.size ?: 0} edSamples=${template.ed?.size ?: 0}")
                val current = loadCompleteCachedFingerprint(context, animeId, currentEpisode, { message: String -> status(message) })
                    ?: return@runCatching emptyList()
                if (duration <= 0.0) {
                    duration = fingerprintFrames(current) * FINGERPRINT_FRAME_SECONDS
                }
                val result = ArrayList<ChapterSkipSegment>()
                val opMatch = template.op?.let { op ->
                    scoreTemplateAgainstCurrent(current, op, "OP", { message: String -> status(message) }, minStartSeconds = 0.0)
                }
                opMatch?.let { m ->
                    result += ChapterSkipSegment("op", m.startSeconds, m.endSeconds.coerceAtMost(duration), duration)
                }

                // ED is searched only after the detected OP. This prevents the ED
                // template from selecting a high-correlation region inside the OP
                // (which can happen when both templates share similar instrumentation
                // or the training template is short). If OP cannot be found, fall back
                // to the full episode rather than inventing an OP boundary.
                val edSearchStart = opMatch?.endSeconds?.coerceIn(0.0, duration) ?: 0.0
                status("FINGERPRINT_ED_SEARCH_RANGE start=$edSearchStart end=$duration")
                template.ed?.let { ed ->
                    scoreTemplateAgainstCurrent(
                        current,
                        ed,
                        "ED",
                        { message: String -> status(message) },
                        minStartSeconds = edSearchStart
                    )?.let { m ->
                        result += ChapterSkipSegment("ed", m.startSeconds, m.endSeconds.coerceAtMost(duration), duration)
                    }
                }
                val out = result.filter { it.endTime > it.startTime }.sortedBy { it.startTime }
                OfflineOpEdResultStore.save(context, animeId, currentEpisode.id, out)
                // The persisted OP/ED templates are the only fingerprints that must
                // survive this analysis. The current episode fingerprint is temporary
                // and must not be retained after the result has been cached.
                status("FINGERPRINT_TEMP_CURRENT_RELEASE episode=${currentEpisode.number}")
                status("FINGERPRINT_ANALYSIS_COMPLETE segments=${out.size} template=audio-only cached=true")
                return@runCatching out
            }

            val completed = episodes.filter { ep -> isOfflineEpisodeCompleted(animeId, ep) }
                .sortedBy { ep -> kotlin.math.abs(ep.number - currentEpisode.number) }
                .take(3)
            if (completed.size < 2) {
                status("FINGERPRINT_TRAINING_WAIT completed=${completed.size}")
                return@runCatching emptyList()
            }
            status("FINGERPRINT_TRAINING_START references=${completed.joinToString(",") { it.number.toString() }}")

            // Training fingerprints are temporary working data. Only the final OP/ED
            // templates are persisted; these episode fingerprints are released after
            // the analysis result has been saved.
            val fps = ArrayList<Pair<Episode, FloatArray>>()
            for (ep in completed) {
                loadCompleteCachedFingerprint(context, animeId, ep, { message: String -> status(message) })
                    ?.let { fps += ep to it }
            }
            if (fps.size < 2) {
                status("FINGERPRINT_TRAINING_FAILED fingerprints=${fps.size}")
                return@runCatching emptyList()
            }

            // Build real audio templates from complete-episode repetition.
            // IMPORTANT: no episode timestamp is averaged or persisted here.  Every
            // candidate is an actual slice of audio fingerprint data.  We validate
            // that the same audio occurs in every training episode, then cluster
            // equivalent fingerprints so OP/ED are selected by recurrence rather
            // than by their absolute position in any one episode.
            data class Occurrence(
                val episodeId: String,
                val episodeNumber: Int,
                val startSeconds: Double,
                val endSeconds: Double,
                val score: Double
            )
            data class TrainingCandidate(
                val fingerprint: FloatArray,
                val occurrences: MutableList<Occurrence>,
                val quality: Double
            )
            data class TrainingCluster(
                var fingerprint: FloatArray,
                val occurrences: MutableList<Occurrence>,
                var quality: Double
            )

            val candidates = ArrayList<TrainingCandidate>()
            for (aIndex in fps.indices) {
                for (bIndex in aIndex + 1 until fps.size) {
                    val found = findTopRepeatedRegions(fps[aIndex].second, fps[bIndex].second, 12)
                    status(
                        "FINGERPRINT_TRAIN_PAIR a=${fps[aIndex].first.number} " +
                            "b=${fps[bIndex].first.number} candidates=${found.size}"
                    )

                    for (seed in found) {
                        val templateCandidate = fps[aIndex].second
                            .sliceFingerprint(seed.startSeconds, seed.endSeconds)
                        if (templateCandidate.size < FINGERPRINT_BINS * 300) continue // at least 30 seconds

                        val occurrences = ArrayList<Occurrence>()
                        var qualitySum = 0.0
                        var valid = true

                        // Search the ENTIRE fingerprint of every training episode for
                        // this exact audio template.  The location is only an
                        // occurrence discovered during training; it is never stored.
                        for ((episode, fingerprint) in fps) {
                            val match = scoreTemplateAgainstCurrent(
                                fingerprint,
                                templateCandidate,
                                "TRAIN_OCCURRENCE_${episode.number}",
                                { message: String -> status(message) }
                            )
                            if (match == null || match.score < 0.82 || match.endSeconds <= match.startSeconds) {
                                valid = false
                                break
                            }
                            occurrences += Occurrence(
                                episode.id,
                                episode.number,
                                match.startSeconds,
                                match.endSeconds,
                                match.score
                            )
                            qualitySum += match.score
                        }

                        if (valid && occurrences.size == fps.size) {
                            candidates += TrainingCandidate(
                                templateCandidate,
                                occurrences,
                                qualitySum / occurrences.size.coerceAtLeast(1)
                            )
                        }
                    }
                }
            }

            if (candidates.isEmpty()) {
                status("FINGERPRINT_TRAINING_FAILED no_common_audio_templates")
                return@runCatching emptyList()
            }

            // Merge the same OP/ED audio found from different episode pairs.
            // Clustering is based on the fingerprint samples themselves, never on
            // start/end timestamps.  This is what allows episode 1 to have the OP
            // at a completely different position from the other episodes.
            val clusters = ArrayList<TrainingCluster>()
            for (candidate in candidates.sortedByDescending { it.quality }) {
                var matchedCluster: TrainingCluster? = null
                for (cluster in clusters) {
                    val minLength = minOf(candidate.fingerprint.size, cluster.fingerprint.size)
                    val maxLength = maxOf(candidate.fingerprint.size, cluster.fingerprint.size)
                    if (minLength < 300 || minLength.toDouble() / maxLength.toDouble() < 0.60) continue
                    val similarity = cosine(
                        candidate.fingerprint, 0,
                        cluster.fingerprint, 0,
                        minLength
                    )
                    if (similarity >= 0.90) {
                        matchedCluster = cluster
                        break
                    }
                }

                if (matchedCluster == null) {
                    clusters += TrainingCluster(
                        candidate.fingerprint.copyOf(),
                        ArrayList(candidate.occurrences),
                        candidate.quality
                    )
                } else {
                    // Keep the best-quality actual audio fingerprint as the
                    // representative; merge only the discovered occurrences.
                    for (occurrence in candidate.occurrences) {
                        val old = matchedCluster.occurrences.indexOfFirst { it.episodeId == occurrence.episodeId }
                        if (old < 0) {
                            matchedCluster.occurrences += occurrence
                        } else if (occurrence.score > matchedCluster.occurrences[old].score) {
                            matchedCluster.occurrences[old] = occurrence
                        }
                    }
                    if (candidate.quality > matchedCluster.quality) {
                        matchedCluster.fingerprint = candidate.fingerprint.copyOf()
                        matchedCluster.quality = candidate.quality
                    }
                }
            }

            val recurring = clusters
                .filter { it.occurrences.size >= fps.size }
                .sortedByDescending { cluster ->
                    val averageScore = cluster.occurrences.map { it.score }.average()
                    averageScore + (cluster.occurrences.size.toDouble() / fps.size.toDouble()) * 0.10
                }

            if (recurring.isEmpty()) {
                status("FINGERPRINT_TRAINING_FAILED no_recurring_templates")
                return@runCatching emptyList()
            }

            // Select two DISTINCT recurring audio templates.  OP/ED labels are
            // assigned from the temporal order of their discovered occurrences in
            // the majority of training episodes.  We do NOT average those times.
            // If episode 1 places OP at the end, its occurrence simply votes in the
            // opposite order; the majority of the remaining episodes can still
            // identify the two templates correctly.
            var opCluster: TrainingCluster? = null
            var edCluster: TrainingCluster? = null
            var bestPairScore = Double.NEGATIVE_INFINITY

            for (i in recurring.indices) {
                for (j in i + 1 until recurring.size) {
                    val a = recurring[i]
                    val b = recurring[j]
                    var aEarlier = 0
                    var bEarlier = 0
                    var comparable = 0
                    for (episode in fps) {
                        val ao = a.occurrences.firstOrNull { it.episodeId == episode.first.id }
                        val bo = b.occurrences.firstOrNull { it.episodeId == episode.first.id }
                        if (ao != null && bo != null) {
                            comparable++
                            when {
                                ao.startSeconds + 5.0 < bo.startSeconds -> aEarlier++
                                bo.startSeconds + 5.0 < ao.startSeconds -> bEarlier++
                            }
                        }
                    }
                    if (comparable == 0) continue

                    val earlierRatio = maxOf(aEarlier, bEarlier).toDouble() / comparable.toDouble()
                    val pairScore =
                        (a.quality + b.quality) * 0.5 +
                            earlierRatio * 0.20 +
                            minOf(a.occurrences.size, b.occurrences.size).toDouble() /
                                fps.size.toDouble() * 0.10
                    if (pairScore > bestPairScore && earlierRatio >= 0.60) {
                        bestPairScore = pairScore
                        if (aEarlier >= bEarlier) {
                            opCluster = a
                            edCluster = b
                        } else {
                            opCluster = b
                            edCluster = a
                        }
                    }
                }
            }

            // If the two templates have no stable majority ordering, do not average
            // timestamps. Pick the strongest distinct pair and use only the earliest
            // observed occurrence as a deterministic fallback. These timestamps are
            // training-only observations and are never persisted.
            if (opCluster == null || edCluster == null) {
                val pair = recurring.take(2)
                if (pair.size < 2) {
                    status("FINGERPRINT_TRAINING_FAILED only_one_recurring_template")
                    return@runCatching emptyList()
                }
                val first = pair[0]
                val second = pair[1]
                val firstFirst = first.occurrences.minOf { it.startSeconds }
                val secondFirst = second.occurrences.minOf { it.startSeconds }
                if (firstFirst <= secondFirst) {
                    opCluster = first
                    edCluster = second
                } else {
                    opCluster = second
                    edCluster = first
                }
                status("FINGERPRINT_TRAIN_ORDER_FALLBACK")
            }

            val opTemplate = opCluster?.fingerprint?.copyOf()
            val edTemplate = edCluster?.fingerprint?.copyOf()
            status(
                "FINGERPRINT_TEMPLATES_SELECTED " +
                    "opSeconds=${opTemplate?.let { fingerprintFrames(it) * FINGERPRINT_FRAME_SECONDS } ?: 0.0} " +
                    "edSeconds=${edTemplate?.let { fingerprintFrames(it) * FINGERPRINT_FRAME_SECONDS } ?: 0.0} " +
                    "clusters=${recurring.size}"
            )
            opCluster?.let { cluster ->
                status("FINGERPRINT_OP_OCCURRENCES " + cluster.occurrences.joinToString(";") {
                    "ep=${it.episodeNumber}@${it.startSeconds}-${it.endSeconds},score=${it.score}"
                })
            }
            edCluster?.let { cluster ->
                status("FINGERPRINT_ED_OCCURRENCES " + cluster.occurrences.joinToString(";") {
                    "ep=${it.episodeNumber}@${it.startSeconds}-${it.endSeconds},score=${it.score}"
                })
            }

            if (!OfflineOpEdFingerprintStore.save(context, animeId, opTemplate, edTemplate)) {
                status("FINGERPRINT_TEMPLATE_SAVE_FAILED")
            } else {
                status(
                    "FINGERPRINT_TEMPLATE_SAVED format=audio-only " +
                        "opSamples=${opTemplate?.size ?: 0} edSamples=${edTemplate?.size ?: 0}"
                )
            }

            val current = fps.firstOrNull { it.first.id == currentEpisode.id }?.second
                ?: loadCompleteCachedFingerprint(context, animeId, currentEpisode, { message: String -> status(message) })
                ?: return@runCatching emptyList()
            if (duration <= 0.0) {
                duration = fingerprintFrames(current) * FINGERPRINT_FRAME_SECONDS
            }
            val result = ArrayList<ChapterSkipSegment>()
            opTemplate?.let { scoreTemplateAgainstCurrent(current, it, "OP", { message: String -> status(message) })?.let { m -> result += ChapterSkipSegment("op", m.startSeconds, m.endSeconds.coerceAtMost(duration), duration) } }
            edTemplate?.let { scoreTemplateAgainstCurrent(current, it, "ED", { message: String -> status(message) })?.let { m -> result += ChapterSkipSegment("ed", m.startSeconds, m.endSeconds.coerceAtMost(duration), duration) } }
            val out = result.filter { it.endTime > it.startTime }.sortedBy { it.startTime }
            OfflineOpEdResultStore.save(context, animeId, currentEpisode.id, out)

            // Release every comparison fingerprint generated for training. The only
            // fingerprint data that survives is opTemplate/edTemplate inside
            // OfflineOpEdFingerprintStore. The chapter result itself is persisted
            // separately in OfflineOpEdResultStore.
            fps.clear()
            candidates.clear()
            status("FINGERPRINT_TEMP_COMPARISON_RELEASED")
            status("FINGERPRINT_ANALYSIS_COMPLETE segments=${out.size} template=audio-only cached=true")
            out
        }.onFailure {
            Log.e(TAG, "FINGERPRINT_ANALYSIS_FAILED ${it.javaClass.simpleName}: ${it.message}", it)
            onStatus("FINGERPRINT_ANALYSIS_FAILED ${it.javaClass.simpleName}: ${it.message ?: "unknown"}")
        }.getOrDefault(emptyList())
    }

    @OptIn(UnstableApi::class)
    fun isOfflineEpisodeCompleted(animeId: String, ep: Episode): Boolean {
        val cursor = LilacApplication.downloadManager.downloadIndex.getDownloads()
        return try {
            val ids = HashSet<String>()
            while (cursor.moveToNext()) if (cursor.download.state == Download.STATE_COMPLETED) ids += cursor.download.request.id
            val exact = offlineDownloadId(animeId, ep)
            exact in ids || ep.id in ids || (ep.displayNumber == ep.number.toString() && "${animeId}_${ep.number}" in ids)
        } finally { cursor.close() }
    }

    @OptIn(UnstableApi::class)
    private fun loadCompleteCachedFingerprint(
        context: Context,
        animeId: String,
        episode: Episode,
        status: (String) -> Unit
    ): FloatArray? {
        val cursor = LilacApplication.downloadManager.downloadIndex.getDownloads()
        val download = try {
            var found: Download? = null
            while (cursor.moveToNext()) {
                val d = cursor.download
                if (d.state == Download.STATE_COMPLETED && (d.request.id == offlineDownloadId(animeId, episode) || d.request.id == episode.id || d.request.id == "${animeId}_${episode.number}")) found = d
            }
            found
        } finally { cursor.close() }
        if (download == null) { status("FINGERPRINT_EPISODE_MISSING episode=${episode.number}"); return null }
        val media = resolveCachedMediaPlaylist(download.request.uri.toString()) ?: return null
        val total = media.segments.lastOrNull()?.end ?: return null
        status("FINGERPRINT_FULL_WINDOW episode=${episode.number} start=0 end=$total segments=${media.segments.size}")
        val file = materializeCachedSegments(context, media, 0.0, total) ?: return null
        return try {
            decodeFingerprint(file, 0.0, total)?.also {
                status("FINGERPRINT_CURRENT_FULL_OK episode=${episode.number} samples=${it.size}")
            }
        } finally {
            // materializeCachedSegments() creates a temporary media file only for
            // decoding. It must never remain after the fingerprint has been created.
            try { file.delete() } catch (_: Throwable) { }
        }
    }

    /**
     * Finds long, contiguous repeated audio regions between two complete episodes.
     * A 20-second seed is only the starting point.  The candidate is extended while
     * adjacent 5-second blocks continue to match, so the persisted template is the
     * actual repeated audio region rather than a fixed analysis window.
     */
    private fun findTopRepeatedRegions(a: FloatArray, b: FloatArray, maxResults: Int): List<Match> {
        // Work in fingerprint frames, not flattened mel-bin samples.
        // A seed is a 20-second sequence (200 x 0.1s frames).  The score is the
        // average cosine similarity of corresponding 32-bin frames.  This makes
        // the meaning of the score independent of FINGERPRINT_BINS and avoids
        // treating a flattened 454,752-element array as a single frame sequence.
        val seedBlock = 200 // 20 seconds
        val extensionBlock = 50 // 5 seconds
        val step = 20 // 2 seconds during discovery
        val aFrames = fingerprintFrames(a)
        val bFrames = fingerprintFrames(b)
        if (aFrames < seedBlock || bFrames < seedBlock) return emptyList()

        val seeds = ArrayList<Match>()
        var globalBest = -1.0
        var globalBestA = -1
        var globalBestB = -1

        var ai = 0
        while (ai + seedBlock <= aFrames) {
            var bestBi = -1
            var bestScore = -1.0
            var bi = 0
            while (bi + seedBlock <= bFrames) {
                val score = sequenceCosineFrames(a, ai, b, bi, seedBlock)
                if (score > bestScore) {
                    bestScore = score
                    bestBi = bi
                }
                bi += step
            }

            if (bestScore > globalBest) {
                globalBest = bestScore
                globalBestA = ai
                globalBestB = bestBi
            }

            // Do not require an unrealistically high score at the discovery stage.
            // The later full-template validation decides whether the repeated audio
            // is strong enough to become a persisted template.
            if (bestBi >= 0 && bestScore >= 0.55) {
                var leftA = ai
                var leftB = bestBi
                var rightA = ai + seedBlock
                var rightB = bestBi + seedBlock
                var goodBlocks = 1
                var totalBlocks = 1
                var extensionScoreSum = bestScore

                while (leftA - extensionBlock >= 0 && leftB - extensionBlock >= 0) {
                    val score = sequenceCosineFrames(a, leftA - extensionBlock, b, leftB - extensionBlock, extensionBlock)
                    totalBlocks++
                    if (score < 0.55) break
                    goodBlocks++
                    extensionScoreSum += score
                    leftA -= extensionBlock
                    leftB -= extensionBlock
                }

                while (rightA + extensionBlock <= aFrames && rightB + extensionBlock <= bFrames) {
                    val score = sequenceCosineFrames(a, rightA, b, rightB, extensionBlock)
                    totalBlocks++
                    if (score < 0.55) break
                    goodBlocks++
                    extensionScoreSum += score
                    rightA += extensionBlock
                    rightB += extensionBlock
                }

                val continuity = goodBlocks.toDouble() / totalBlocks.coerceAtLeast(1)
                val average = extensionScoreSum / totalBlocks.coerceAtLeast(1)
                val score = bestScore * 0.45 + average * 0.35 + continuity * 0.20
                val duration = (rightA - leftA) * FINGERPRINT_FRAME_SECONDS
                if (score >= 0.60 && continuity >= 0.50 && duration >= 40.0) {
                    seeds += Match(
                        leftA * FINGERPRINT_FRAME_SECONDS,
                        rightA * FINGERPRINT_FRAME_SECONDS,
                        score
                    )
                }
            }
            ai += step
        }

        // Always expose the best pair score.  This is diagnostic information, not
        // a persisted template: it lets us distinguish "no similarity" from a
        // candidate being rejected by a later validation rule.
        Log.d(
            TAG,
            "FINGERPRINT_PAIR_BEST score=$globalBest " +
                "aStart=${globalBestA * FINGERPRINT_FRAME_SECONDS} " +
                "bStart=${globalBestB * FINGERPRINT_FRAME_SECONDS}"
        )

        val selected = ArrayList<Match>()
        for (candidate in seeds.sortedByDescending { it.score }) {
            if (selected.none {
                    kotlin.math.abs(it.startSeconds - candidate.startSeconds) < 30.0 ||
                        rangesOverlap(it.startSeconds, it.endSeconds, candidate.startSeconds, candidate.endSeconds)
                }) {
                selected += candidate
                if (selected.size >= maxResults) break
            }
        }
        return selected.sortedBy { it.startSeconds }
    }

    private fun rangesOverlap(aStart: Double, aEnd: Double, bStart: Double, bEnd: Double): Boolean =
        aStart < bEnd && bStart < aEnd

    private fun FloatArray.sliceFingerprint(startSeconds: Double, endSeconds: Double): FloatArray {
        val frameCount = size / FINGERPRINT_BINS
        val sFrame = (startSeconds / FINGERPRINT_FRAME_SECONDS).toInt().coerceIn(0, frameCount)
        val eFrame = (endSeconds / FINGERPRINT_FRAME_SECONDS).toInt().coerceIn(sFrame, frameCount)
        return copyOfRange(sFrame * FINGERPRINT_BINS, eFrame * FINGERPRINT_BINS)
    }

    private fun fingerprintFrames(values: FloatArray): Int = values.size / FINGERPRINT_BINS

    private fun cosineFrames(a: FloatArray, aFrame: Int, b: FloatArray, bFrame: Int, frames: Int): Double =
        cosine(a, aFrame * FINGERPRINT_BINS, b, bFrame * FINGERPRINT_BINS, frames * FINGERPRINT_BINS)

    private fun sequenceCosineFrames(
        a: FloatArray,
        aFrame: Int,
        b: FloatArray,
        bFrame: Int,
        frames: Int
    ): Double {
        val availableA = fingerprintFrames(a) - aFrame
        val availableB = fingerprintFrames(b) - bFrame
        val count = minOf(frames, availableA, availableB)
        if (count <= 0 || aFrame < 0 || bFrame < 0) return 0.0

        var sum = 0.0
        var valid = 0
        for (frame in 0 until count) {
            val score = cosine(
                a,
                (aFrame + frame) * FINGERPRINT_BINS,
                b,
                (bFrame + frame) * FINGERPRINT_BINS,
                FINGERPRINT_BINS
            )
            sum += score
            valid++
        }
        return if (valid == 0) 0.0 else sum / valid
    }

    /**
     * Searches the complete current-episode fingerprint for the stored audio template.
     * The template length is authoritative: a match is never expanded beyond the
     * fingerprint that was actually persisted.  This avoids turning a short OP/ED
     * template into an arbitrary 90/400-second chapter because adjacent audio also
     * happens to correlate.
     */
    private fun scoreTemplateAgainstCurrent(
        current: FloatArray,
        template: FloatArray,
        label: String,
        status: (String) -> Unit,
        minStartSeconds: Double = 0.0
    ): Match? {
        if (template.size < FINGERPRINT_BINS * 10 || current.size < template.size) return null

        val scanStep = 10 // 1 second coarse scan in 0.1s frames; final score uses the whole template.
        val currentFrames = fingerprintFrames(current)
        val templateFrames = fingerprintFrames(template)
        val minStartFrame = kotlin.math.ceil(
            minStartSeconds / FINGERPRINT_FRAME_SECONDS
        ).toInt().coerceIn(0, currentFrames)
        status(
            "FINGERPRINT_${label}_SEARCH_START seconds=$minStartSeconds frame=$minStartFrame " +
                "duration=${currentFrames * FINGERPRINT_FRAME_SECONDS}"
        )
        var bestStart = -1
        var bestCosine = -1.0
        var i = minStartFrame
        while (i + templateFrames <= currentFrames) {
            val c = sequenceCosineFrames(current, i, template, 0, templateFrames)
            if (c > bestCosine) {
                bestCosine = c
                bestStart = i
            }
            i += scanStep
        }

        if (bestStart < 0 || bestCosine < 0.65) {
            status("FINGERPRINT_${label}_REJECT cosine=$bestCosine minStartSeconds=$minStartSeconds")
            return null
        }

        // Validate the same candidate over the entire template in 5-second blocks.
        // This prevents one short high-correlation patch from determining the match.
        val block = 50 // 5 seconds in fingerprint frames
        var good = 0
        var total = 0
        var sum = 0.0
        var q = 0
        while (q + block <= templateFrames) {
            val c = sequenceCosineFrames(current, bestStart + q, template, q, block)
            sum += c
            total++
            if (c >= 0.55) good++
            q += block
        }
        if (q < templateFrames) {
            val remaining = templateFrames - q
            if (remaining >= 10) {
                val c = sequenceCosineFrames(current, bestStart + q, template, q, remaining)
                sum += c
                total++
                if (c >= 0.55) good++
            }
        }

        val continuity = good.toDouble() / total.coerceAtLeast(1)
        val average = sum / total.coerceAtLeast(1)
        val score = bestCosine * 0.40 + continuity * 0.35 + average * 0.25
        status(
            "FINGERPRINT_${label}_SCORE cosine=$bestCosine " +
                "continuity=$continuity average=$average blocks=$good/$total score=$score"
        )

        if (score < 0.65 || continuity < 0.60) {
            status("FINGERPRINT_${label}_REJECT reason=continuity score=$score continuity=$continuity")
            return null
        }

        // The persisted fingerprint length defines the chapter length exactly.
        val start = bestStart * FINGERPRINT_FRAME_SECONDS
        val end = (bestStart + fingerprintFrames(template)) * FINGERPRINT_FRAME_SECONDS
        status(
            "FINGERPRINT_${label}_MATCH start=$start end=$end " +
                "score=$score templateSeconds=${fingerprintFrames(template) * FINGERPRINT_FRAME_SECONDS}"
        )
        return Match(start, end, score)
    }

    @OptIn(UnstableApi::class)
    private fun materializeCachedTailWindow(
        context: Context,
        url: String,
        seconds: Double
    ): TailWindow? {
        return runCatching {
            val media = resolveCachedMediaPlaylist(url) ?: return@runCatching null
            val total = media.segments.lastOrNull()?.end ?: return@runCatching null
            val start = max(0.0, total - seconds)
            Log.d(TAG, "OFFLINE_HLS_ACTUAL_DURATION total=$total tailStart=$start tailEnd=$total")
            materializeCachedSegments(context, media, start, total)?.let {
                TailWindow(it, start, total)
            }
        }.onFailure {
            Log.e(TAG, "OFFLINE_TAIL_FAILED ${it.javaClass.simpleName}: ${it.message}", it)
        }.getOrNull()
    }

    @OptIn(UnstableApi::class)
    private fun materializeCachedWindow(
        context: Context,
        url: String,
        start: Double,
        end: Double
    ): File? {
        return runCatching {
            val media = resolveCachedMediaPlaylist(url) ?: return@runCatching null
            materializeCachedSegments(context, media, start, end)
        }.onFailure {
            Log.e(TAG, "OFFLINE_WINDOW_FAILED ${it.javaClass.simpleName}: ${it.message}", it)
        }.getOrNull()
    }

    private data class CachedMedia(
        val playlistUrl: String,
        val playlistText: String,
        val segments: List<HlsSegment>,
        val initUrl: String?
    )

    @OptIn(UnstableApi::class)
    private fun resolveCachedMediaPlaylist(url: String): CachedMedia? {
        val cache = LilacApplication.downloadCache
        var mediaUrl = url
        var playlistText = readCachedText(cache, mediaUrl)
        if (playlistText == null) {
            Log.w(TAG, "OFFLINE_PLAYLIST_CACHE_MISS url=$mediaUrl")
            return null
        }

        if (playlistText.contains("#EXT-X-STREAM-INF")) {
            val variants = parseMasterVariants(playlistText, mediaUrl)
            val cachedVariant = variants
                .filter { readCachedText(cache, it.url) != null }
                .maxByOrNull { it.bandwidth }
            if (cachedVariant == null) {
                Log.w(TAG, "OFFLINE_VARIANT_CACHE_MISS variants=${variants.size} master=$mediaUrl")
                return null
            }
            mediaUrl = cachedVariant.url
            playlistText = readCachedText(cache, mediaUrl) ?: return null
            Log.d(TAG, "OFFLINE_VARIANT_SELECTED url=$mediaUrl")
        }

        if (playlistText.contains("#EXT-X-KEY") && !playlistText.contains("METHOD=NONE")) {
            Log.w(TAG, "OFFLINE_HLS_ENCRYPTED_UNSUPPORTED")
            return null
        }

        val segments = parseMediaSegments(playlistText, mediaUrl)
        if (segments.isEmpty()) {
            Log.w(TAG, "OFFLINE_MEDIA_PLAYLIST_EMPTY url=$mediaUrl")
            return null
        }

        val initUrl = parseInitSegmentUrl(playlistText, mediaUrl)
        Log.d(TAG, "OFFLINE_MEDIA_PLAYLIST segments=${segments.size} url=$mediaUrl")
        return CachedMedia(mediaUrl, playlistText, segments, initUrl)
    }

    @OptIn(UnstableApi::class)
    private fun materializeCachedSegments(
        context: Context,
        media: CachedMedia,
        start: Double,
        end: Double
    ): File? {
        val selected = media.segments.filter { it.end > start && it.start < end }
        if (selected.isEmpty()) {
            Log.w(TAG, "OFFLINE_SELECTED_SEGMENTS_EMPTY start=$start end=$end")
            return null
        }

        val cache = LilacApplication.downloadCache
        val firstUrl = selected.first().url.substringBefore('?')
        val extension = if (firstUrl.endsWith(".ts", true)) ".ts" else ".mp4"
        val file = File.createTempFile("offline_hls_window_", extension, context.cacheDir)

        file.outputStream().buffered().use { output ->
            media.initUrl?.let { init ->
                val bytes = readCachedBytes(cache, init)
                    ?: throw IllegalStateException("offline init segment cache miss: $init")
                output.write(bytes)
            }

            for (segment in selected) {
                val bytes = readCachedBytes(cache, segment.url)
                    ?: throw IllegalStateException("offline segment cache miss: ${segment.url}")
                output.write(bytes)
            }
        }

        if (file.length() == 0L) {
            file.delete()
            return null
        }
        Log.d(
            TAG,
            "OFFLINE_HLS_WINDOW_OK start=$start end=$end segments=${selected.size} " +
                "bytes=${file.length()} first=${selected.first().url} last=${selected.last().url}"
        )
        return file
    }

    @OptIn(UnstableApi::class)
    private fun readCachedText(cache: androidx.media3.datasource.cache.Cache, key: String): String? {
        val bytes = readCachedBytes(cache, key) ?: return null
        return runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
    }

    @OptIn(UnstableApi::class)
    private fun readCachedBytes(
        cache: androidx.media3.datasource.cache.Cache,
        key: String
    ): ByteArray? {
        val spans = cache.getCachedSpans(key).sortedBy { it.position }
        if (spans.isEmpty()) return null

        val output = ByteArrayOutputStream()
        var expected = 0L
        for (span in spans) {
            val file = span.file ?: return null
            if (span.position != expected) {
                Log.w(TAG, "OFFLINE_CACHE_HOLE key=$key expected=$expected actual=${span.position}")
                return null
            }
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = span.length
                while (remaining > 0L) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) return null
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            expected += span.length
        }
        return output.toByteArray()
    }


}
