package com.lilac.anime.data.offline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Downloads a VOD HLS stream completely to local storage and then creates
 * one MP4 file that libmpv can play without network access.
 *
 * Both MPEG-TS and fragmented MP4 HLS are supported:
 *   TS  -> concatenate segments -> MediaExtractor/MediaMuxer -> episode.mp4
 *   fMP4 -> concatenate EXT-X-MAP + fragments -> MediaExtractor/MediaMuxer -> episode.mp4
 *
 * If the master playlist has a separate AUDIO rendition, that rendition is
 * downloaded as a second local playlist and muxed together with the video.
 *
 * No video/audio re-encoding is performed. Android MediaExtractor reads the
 * local elementary streams and MediaMuxer writes them into an MP4 container.
 */
class MpvHlsDownloader(
    private val client: OkHttpClient = defaultClient
) {
    data class Progress(val downloaded: Long, val total: Long)

    private data class ByteRange(
        val length: Long,
        val offset: Long?
    )

    private data class Segment(
        val url: String,
        val duration: Double,
        val range: ByteRange? = null
    )

    private data class PlaylistInfo(
        val mediaUrl: String,
        val audioUrl: String?
    )

    private data class LocalPlaylist(
        val file: File,
        val segmentCount: Int,
        val initFile: File?,
        val parts: List<File>
    )

    suspend fun download(
        context: Context,
        animeId: String,
        episodeId: String,
        sourceUrl: String,
        onProgress: suspend (Progress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = MpvOfflineStore.episodeDir(context, animeId, episodeId).apply {
            mkdirs()
        }

        val output = MpvOfflineStore.videoFile(context, animeId, episodeId)
        val tempOutput = File(dir, "episode.mp4.part")
        val localRoot = File(dir, "hls").apply {
            deleteRecursively()
            mkdirs()
        }

        // A direct MP4 URL is also accepted. It still goes through the same
        // final audio/video-track validation before becoming "completed".
        if (!sourceUrl.contains(".m3u8", ignoreCase = true)) {
            tempOutput.delete()
            downloadToFile(sourceUrl, tempOutput)
            validatePlayableMp4(tempOutput)
            atomicReplace(tempOutput, output)
            onProgress(Progress(1L, 1L))
            return@withContext output
        }

        try {
            val playlistInfo = resolvePlaylists(sourceUrl)

            val videoText = getText(playlistInfo.mediaUrl)
            rejectEncryptedHls(videoText)

            val audioText = playlistInfo.audioUrl?.let { getText(it) }
            audioText?.let(::rejectEncryptedHls)

            val videoSegments = parseSegments(playlistInfo.mediaUrl, videoText)
            require(videoSegments.isNotEmpty()) {
                "HLS 비디오 세그먼트가 없습니다."
            }

            val audioSegments = if (playlistInfo.audioUrl != null && audioText != null) {
                parseSegments(playlistInfo.audioUrl, audioText)
            } else {
                emptyList()
            }

            val totalSegments =
                videoSegments.size.toLong() + audioSegments.size.toLong()

            var completedSegments = 0L

            suspend fun segmentProgress() {
                completedSegments++
                onProgress(Progress(completedSegments, totalSegments))
            }

            val videoLocal = downloadPlaylist(
                playlistText = videoText,
                baseUrl = playlistInfo.mediaUrl,
                outputDir = File(localRoot, "video").apply { mkdirs() },
                segments = videoSegments,
                onSegment = { segmentProgress() }
            )

            val audioLocal = if (audioSegments.isNotEmpty() && audioText != null) {
                downloadPlaylist(
                    playlistText = audioText,
                    baseUrl = playlistInfo.audioUrl!!,
                    outputDir = File(localRoot, "audio").apply { mkdirs() },
                    segments = audioSegments,
                    onSegment = { segmentProgress() }
                )
            } else {
                null
            }

            tempOutput.delete()

            Log.i(TAG, "Starting local HLS -> MP4 with MediaExtractor/MediaMuxer")
            Log.i(TAG, "Video playlist=${videoLocal.file.absolutePath}")
            Log.i(TAG, "Audio playlist=${audioLocal?.file?.absolutePath}")

            convertLocalHlsToMp4(
                video = videoLocal,
                audio = audioLocal,
                output = tempOutput
            )

            check(tempOutput.isFile && tempOutput.length() > 0L) {
                "로컬 HLS를 MP4로 변환하지 못했습니다."
            }

            // Do not mark the episode completed until both video and audio
            // tracks are actually present.
            validatePlayableMp4(tempOutput)

            atomicReplace(tempOutput, output)

            // Only the final MP4 is needed for offline playback.
            localRoot.deleteRecursively()

            onProgress(Progress(totalSegments, totalSegments))
            output
        } catch (t: Throwable) {
            tempOutput.delete()
            // Never leave a partial MP4 that could be mistaken for a complete
            // offline video.
            output.delete()
            Log.e(TAG, "HLS download failed", t)
            throw t
        }
    }

    /**
     * Resolves a master playlist into:
     *   - highest-bandwidth video variant
     *   - its AUDIO rendition, when audio is declared separately.
     */
    private fun resolvePlaylists(url: String): PlaylistInfo {
        val master = getText(url)
        if (!master.contains("#EXT-X-STREAM-INF", ignoreCase = false)) {
            return PlaylistInfo(mediaUrl = url, audioUrl = null)
        }

        data class Variant(
            val url: String,
            val bandwidth: Long,
            val audioGroup: String?
        )

        val lines = master.lines().map { it.trim() }
        val audioRenditions = mutableMapOf<String, String>()

        for (line in lines) {
            if (!line.startsWith("#EXT-X-MEDIA:")) continue
            val attrs = parseAttributes(line.substringAfter(':'))
            if (attrs["TYPE"]?.equals("AUDIO", true) != true) continue

            val group = attrs["GROUP-ID"] ?: continue
            val uri = attrs["URI"] ?: continue
            audioRenditions[group] = resolveUrl(url, uri)
        }

        val variants = mutableListOf<Variant>()

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue

            val attrs = parseAttributes(line.substringAfter(':'))
            val bandwidth =
                attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                    ?: attrs["BANDWIDTH"]?.toLongOrNull()
                    ?: 0L

            val child = lines.drop(i + 1)
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: continue

            variants += Variant(
                url = resolveUrl(url, child),
                bandwidth = bandwidth,
                audioGroup = attrs["AUDIO"]
            )
        }

        val selected = variants.maxByOrNull { it.bandwidth }
            ?: return PlaylistInfo(url, null)

        return PlaylistInfo(
            mediaUrl = selected.url,
            audioUrl = selected.audioGroup?.let { audioRenditions[it] }
        )
    }

    private suspend fun downloadPlaylist(
        playlistText: String,
        baseUrl: String,
        outputDir: File,
        segments: List<Segment>,
        onSegment: suspend () -> Unit
    ): LocalPlaylist = coroutineScope {
        outputDir.mkdirs()

        val semaphore = Semaphore(6)

        val files = segments.mapIndexed { index, segment ->
            async {
                semaphore.withPermit {
                    val suffix = when {
                        segment.url.substringBefore('?')
                            .endsWith(".m4s", true) -> "m4s"

                        segment.url.substringBefore('?')
                            .endsWith(".mp4", true) -> "mp4"

                        else -> "ts"
                    }

                    val target = File(
                        outputDir,
                        "%06d.%s".format(Locale.US, index, suffix)
                    )

                    if (!target.isFile || target.length() == 0L) {
                        downloadToFile(
                            url = segment.url,
                            target = target,
                            range = segment.range
                        )
                    }

                    check(target.isFile && target.length() > 0L) {
                        "세그먼트 다운로드 실패: ${segment.url}"
                    }

                    onSegment()
                    index to target
                }
            }
        }.awaitAll().sortedBy { it.first }.map { it.second }

        var initFile: File? = null
        val mapLine = playlistText.lineSequence()
            .firstOrNull { it.trim().startsWith("#EXT-X-MAP:") }

        if (mapLine != null) {
            val attrs = parseAttributes(mapLine.substringAfter(':'))
            val uri = attrs["URI"]
                ?: error("EXT-X-MAP URI가 없습니다.")

            val mapUrl = resolveUrl(baseUrl, uri)
            val mapRange = attrs["BYTERANGE"]?.let(::parseByteRange)

            initFile = File(outputDir, "init.mp4")
            if (!initFile.isFile || initFile.length() == 0L) {
                downloadToFile(mapUrl, initFile, mapRange)
            }

            check(initFile.isFile && initFile.length() > 0L) {
                "HLS 초기화 세그먼트 다운로드 실패"
            }
        }

        val localPlaylist = File(outputDir, "playlist.m3u8")
        writeLocalPlaylist(
            file = localPlaylist,
            original = playlistText,
            parts = files,
            initFile = initFile
        )

        LocalPlaylist(localPlaylist, segments.size, initFile, files)
    }

    /**
     * Converts every remote segment URI into a local filename.
     *
     * Because BYTERANGE data is downloaded as a separate local file, the
     * corresponding BYTERANGE tag is removed from the local playlist.
     */
    private fun writeLocalPlaylist(
        file: File,
        original: String,
        parts: List<File>,
        initFile: File?
    ) {
        val lines = original.lines()
        val out = StringBuilder()
        var segmentIndex = 0

        for (raw in lines) {
            val line = raw.trim()

            when {
                line.startsWith("#EXT-X-MAP:") && initFile != null -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                        .toMutableMap()
                    attrs["URI"] = "\"${initFile.name}\""
                    attrs.remove("BYTERANGE")
                    out.append("#EXT-X-MAP:")
                        .append(formatAttributes(attrs))
                        .append('\n')
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    // The exact byte range has already been downloaded into
                    // an independent local file, so this tag must disappear.
                }

                line.isNotEmpty() && !line.startsWith("#") -> {
                    val part = parts.getOrNull(segmentIndex++)
                        ?: error("로컬 HLS 세그먼트 수가 일치하지 않습니다.")
                    out.append(part.name).append('\n')
                }

                else -> {
                    out.append(raw).append('\n')
                }
            }
        }

        file.writeText(out.toString(), Charsets.UTF_8)
    }

    private fun parseSegments(
        baseUrl: String,
        text: String
    ): List<Segment> {
        val out = mutableListOf<Segment>()
        var duration = 0.0
        var pendingRange: ByteRange? = null
        val previousEndByUri = mutableMapOf<String, Long>()

        for (raw in text.lines()) {
            val line = raw.trim()

            when {
                line.startsWith("#EXTINF:") -> {
                    duration = line.substringAfter(':')
                        .substringBefore(',')
                        .toDoubleOrNull() ?: 0.0
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    pendingRange = parseByteRange(
                        line.substringAfter(':')
                    )
                }

                line.isNotEmpty() && !line.startsWith("#") -> {
                    val url = resolveUrl(baseUrl, line)

                    val range = pendingRange?.let { r ->
                        val offset = r.offset
                            ?: previousEndByUri[url]
                            ?: 0L

                        previousEndByUri[url] = offset + r.length

                        ByteRange(r.length, offset)
                    }

                    out += Segment(
                        url = url,
                        duration = duration,
                        range = range
                    )

                    duration = 0.0
                    pendingRange = null
                }
            }
        }

        return out
    }

    private fun parseInit(
        text: String,
        baseUrl: String
    ): Pair<String, ByteRange?>? {
        val line = text.lineSequence()
            .firstOrNull { it.trim().startsWith("#EXT-X-MAP:") }
            ?: return null

        val attrs = parseAttributes(line.substringAfter(':'))
        val uri = attrs["URI"] ?: return null
        val range = attrs["BYTERANGE"]?.let(::parseByteRange)

        return resolveUrl(baseUrl, uri) to range
    }

    private fun parseByteRange(value: String): ByteRange {
        val parts = value.trim().split('@', limit = 2)
        val length = parts[0].toLongOrNull()
            ?: error("잘못된 HLS BYTERANGE: $value")
        val offset = parts.getOrNull(1)?.toLongOrNull()
        return ByteRange(length, offset)
    }

    private fun rejectEncryptedHls(text: String) {
        if (text.contains("#EXT-X-KEY", ignoreCase = true)) {
            throw IllegalStateException(
                "암호화된 HLS는 현재 오프라인 MP4 변환을 지원하지 않습니다."
            )
        }
    }

    /**
     * Builds a real MP4 without Media3 Transformer.
     *
     * Media3 Transformer ultimately reaches Android's MPEG4Writer. On some
     * Android 16 builds, a malformed/unsupported HLS sample can make the
     * platform writer receive a size of -1 and abort the whole process
     * natively. That cannot be caught by Kotlin.
     *
     * Here we first join the already-downloaded HLS media into one local
     * elementary/container stream, then let MediaExtractor expose the tracks
     * and MediaMuxer write only valid samples into a normal MP4.
     */
    private suspend fun convertLocalHlsToMp4(
        video: LocalPlaylist,
        audio: LocalPlaylist?,
        output: File
    ) = withContext(Dispatchers.IO) {
        val dir = output.parentFile ?: error("MP4 출력 디렉터리가 없습니다.")
        val videoSource = File(dir, "video_source.bin")
        val audioSource = audio?.let { File(dir, "audio_source.bin") }

        try {
            concatenateMedia(video, videoSource)
            if (audio != null && audioSource != null) {
                concatenateMedia(audio, audioSource)
            }

            muxSourcesToMp4(
                videoSource = videoSource,
                audioSource = audioSource,
                output = output
            )
        } finally {
            videoSource.delete()
            audioSource?.delete()
        }
    }

    private fun concatenateMedia(
        playlist: LocalPlaylist,
        target: File
    ) {
        target.delete()

        FileOutputStream(target).buffered(DISK_COPY_BUFFER_SIZE).use { out ->
            if (playlist.initFile != null) {
                playlist.initFile.inputStream().buffered(DISK_COPY_BUFFER_SIZE).use {
                    it.copyTo(out, DISK_COPY_BUFFER_SIZE)
                }
            }

            for (part in playlist.parts) {
                part.inputStream().buffered(DISK_COPY_BUFFER_SIZE).use {
                    it.copyTo(out, DISK_COPY_BUFFER_SIZE)
                }
            }
        }

        check(target.isFile && target.length() > 0L) {
            "로컬 미디어 결합에 실패했습니다."
        }
    }

    private fun muxSourcesToMp4(
        videoSource: File,
        audioSource: File?,
        output: File
    ) {
        val temp = File(output.parentFile, "${output.name}.mux.part")
        temp.delete()

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()

        var muxer: MediaMuxer? = null
        var videoTrack = -1
        var audioTrack = -1

        try {
            videoExtractor.setDataSource(videoSource.absolutePath)
            check(videoExtractor.trackCount > 0) {
                "결합된 비디오 스트림을 열 수 없습니다."
            }

            videoTrack = findTrack(videoExtractor, "video/")
            check(videoTrack >= 0) {
                "비디오 트랙을 찾을 수 없습니다."
            }

            audioExtractor.setDataSource(
                (audioSource ?: videoSource).absolutePath
            )
            audioTrack = findTrack(audioExtractor, "audio/")
            check(audioTrack >= 0) {
                "오디오 트랙을 찾을 수 없습니다."
            }

            muxer = MediaMuxer(
                temp.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val videoFormat = repairedTrackFormat(
                extractor = videoExtractor,
                track = videoTrack,
                label = "video"
            )
            val audioFormat = repairedTrackFormat(
                extractor = audioExtractor,
                track = audioTrack,
                label = "audio"
            )

            Log.i(TAG, "Mux video format=$videoFormat")
            Log.i(TAG, "Mux audio format=$audioFormat")

            val outVideoTrack = muxer.addTrack(videoFormat)
            val outAudioTrack = muxer.addTrack(audioFormat)

            muxer.start()

            val firstVideoTime = peekFirstSampleTime(videoExtractor, videoTrack)
            val firstAudioTime =
                peekFirstSampleTime(audioExtractor, audioTrack)

            val baseTime = minOf(
                if (firstVideoTime >= 0L) firstVideoTime else Long.MAX_VALUE,
                if (firstAudioTime >= 0L) firstAudioTime else Long.MAX_VALUE
            ).takeIf { it != Long.MAX_VALUE } ?: 0L

            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            writeInterleavedSamples(
                videoExtractor = videoExtractor,
                videoTrack = outVideoTrack,
                videoBaseTime = baseTime,
                audioExtractor = audioExtractor,
                audioTrack = outAudioTrack,
                audioBaseTime = baseTime,
                muxer = muxer
            )

            muxer.stop()
            muxer.release()
            muxer = null

            validatePlayableMp4(temp)
            atomicReplace(temp, output)

            Log.i(
                TAG,
                "Local HLS -> MP4 completed: " +
                    "file=${output.length()} bytes, audio=true"
            )
        } finally {
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
            temp.delete()
        }
    }

    /**
     * MediaExtractor can expose an AVC/HEVC track without usable codec-specific
     * data when the source is MPEG-TS. Android's MPEG4Writer then fails at
     * stop() with "Missing codec specific data" / -1007.
     *
     * Before addTrack(), make a mutable copy of the format and, when necessary,
     * recover SPS/PPS (or VPS/SPS/PPS) from the first keyframe samples.
     * AAC ADTS is handled similarly by constructing AudioSpecificConfig.
     */
    private fun repairedTrackFormat(
        extractor: MediaExtractor,
        track: Int,
        label: String
    ): MediaFormat {
        val original = extractor.getTrackFormat(track)
        val format = MediaFormat(original)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()

        Log.i(TAG, "$label source mime=$mime format=$original")

        when {
            mime.equals("video/avc", ignoreCase = true) -> {
                if (!hasUsableCsd(format, 0) || !hasUsableCsd(format, 1)) {
                    val csd = findAvcCsd(extractor, track)
                    if (csd != null) {
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd.sps))
                        format.setByteBuffer("csd-1", ByteBuffer.wrap(csd.pps))
                        Log.i(
                            TAG,
                            "$label repaired AVC CSD: " +
                                "sps=${csd.sps.size}, pps=${csd.pps.size}"
                        )
                    }
                }
            }

            mime.equals("video/hevc", ignoreCase = true) ||
                mime.equals("video/h265", ignoreCase = true) -> {
                if (!hasUsableCsd(format, 0) ||
                    !hasUsableCsd(format, 1) ||
                    !hasUsableCsd(format, 2)
                ) {
                    val csd = findHevcCsd(extractor, track)
                    if (csd != null) {
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd.vps))
                        format.setByteBuffer("csd-1", ByteBuffer.wrap(csd.sps))
                        format.setByteBuffer("csd-2", ByteBuffer.wrap(csd.pps))
                        Log.i(
                            TAG,
                            "$label repaired HEVC CSD: " +
                                "vps=${csd.vps.size}, sps=${csd.sps.size}, " +
                                "pps=${csd.pps.size}"
                        )
                    }
                }
            }

            mime.equals("audio/mp4a-latm", ignoreCase = true) -> {
                if (!hasUsableCsd(format, 0)) {
                    val asc = findAacAudioSpecificConfig(extractor, track)
                    if (asc != null) {
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
                        Log.i(TAG, "$label repaired AAC CSD: ${asc.size} bytes")
                    }
                }
            }
        }

        return format
    }

    private fun hasUsableCsd(format: MediaFormat, index: Int): Boolean =
        runCatching {
            format.getByteBuffer("csd-$index")?.remaining() ?: 0
        }.getOrDefault(0) >= 2

    private data class AvcCsd(val sps: ByteArray, val pps: ByteArray)
    private data class HevcCsd(
        val vps: ByteArray,
        val sps: ByteArray,
        val pps: ByteArray
    )

    private fun findAvcCsd(
        extractor: MediaExtractor,
        track: Int
    ): AvcCsd? {
        return scanSamples(extractor, track, 64) { sample ->
            val nalUnits = splitAnnexBNalUnits(sample)
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            for (nal in nalUnits) {
                if (nal.isEmpty()) continue
                when (nal[0].toInt() and 0x1f) {
                    7 -> if (sps == null) sps = nal
                    8 -> if (pps == null) pps = nal
                }
                if (sps != null && pps != null) {
                    return@scanSamples AvcCsd(sps!!, pps!!)
                }
            }
            null
        }
    }

    private fun findHevcCsd(
        extractor: MediaExtractor,
        track: Int
    ): HevcCsd? {
        return scanSamples(extractor, track, 64) { sample ->
            val nalUnits = splitAnnexBNalUnits(sample)
            var vps: ByteArray? = null
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            for (nal in nalUnits) {
                if (nal.isEmpty()) continue
                val type = (nal[0].toInt() ushr 1) and 0x3f
                when (type) {
                    32 -> if (vps == null) vps = nal
                    33 -> if (sps == null) sps = nal
                    34 -> if (pps == null) pps = nal
                }
                if (vps != null && sps != null && pps != null) {
                    return@scanSamples HevcCsd(vps!!, sps!!, pps!!)
                }
            }
            null
        }
    }

    private fun findAacAudioSpecificConfig(
        extractor: MediaExtractor,
        track: Int
    ): ByteArray? {
        val sample = firstSample(extractor, track) ?: return null
        if (sample.size < 7) return null

        // ADTS syncword: 0xFFF. Build AudioSpecificConfig from the header.
        val b0 = sample[0].toInt() and 0xff
        val b1 = sample[1].toInt() and 0xff
        if (b0 != 0xff || (b1 and 0xf6) != 0xf0) return null

        val profile = ((sample[2].toInt() and 0xc0) ushr 6) + 1
        val sampleRateIndex = (sample[2].toInt() and 0x3c) ushr 2
        val channelConfig =
            ((sample[2].toInt() and 0x01) shl 2) or
                ((sample[3].toInt() and 0xc0) ushr 6)

        if (sampleRateIndex == 15 || channelConfig == 0) return null

        return byteArrayOf(
            ((profile shl 3) or (sampleRateIndex ushr 1)).toByte(),
            (((sampleRateIndex and 1) shl 7) or (channelConfig shl 3)).toByte()
        )
    }

    private fun firstSample(
        extractor: MediaExtractor,
        track: Int
    ): ByteArray? {
        extractor.unselectTrack(track)
        extractor.selectTrack(track)
        val buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_SIZE)
        val size = extractor.readSampleData(buffer, 0)
        if (size <= 0) {
            extractor.unselectTrack(track)
            return null
        }
        val result = ByteArray(size)
        buffer.position(0)
        buffer.get(result)
        extractor.unselectTrack(track)
        return result
    }

    private fun <T> scanSamples(
        extractor: MediaExtractor,
        track: Int,
        maxSamples: Int,
        visitor: (ByteArray) -> T?
    ): T? {
        extractor.unselectTrack(track)
        extractor.selectTrack(track)
        val buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_SIZE)
        var count = 0
        try {
            while (count < maxSamples) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0) return null
                val sample = ByteArray(size)
                buffer.position(0)
                buffer.get(sample)
                val result = visitor(sample)
                if (result != null) return result
                count++
                if (!extractor.advance()) return null
            }
            return null
        } finally {
            extractor.unselectTrack(track)
        }
    }

    private fun splitAnnexBNalUnits(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            val is3 = data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            val is4 = i + 4 < data.size &&
                data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() &&
                data[i + 3] == 1.toByte()
            if (is3 || is4) {
                starts += i
                i += if (is4) 4 else 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return emptyList()

        return starts.mapIndexed { index, start ->
            val prefix = if (
                start + 3 < data.size &&
                data[start] == 0.toByte() &&
                data[start + 1] == 0.toByte() &&
                data[start + 2] == 0.toByte() &&
                data[start + 3] == 1.toByte()
            ) 4 else 3
            val end = starts.getOrNull(index + 1) ?: data.size
            data.copyOfRange(start + prefix, end)
        }
    }

    private fun findTrack(
        extractor: MediaExtractor,
        prefix: String
    ): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                .orEmpty()
            if (mime.startsWith(prefix)) return index
        }
        return -1
    }

    private fun peekFirstSampleTime(
        extractor: MediaExtractor,
        track: Int
    ): Long {
        extractor.selectTrack(track)
        val buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_SIZE)
        val size = extractor.readSampleData(buffer, 0)
        val time = if (size > 0) extractor.sampleTime else -1L
        extractor.unselectTrack(track)
        return time
    }

    private fun writeInterleavedSamples(
        videoExtractor: MediaExtractor,
        videoTrack: Int,
        videoBaseTime: Long,
        audioExtractor: MediaExtractor,
        audioTrack: Int,
        audioBaseTime: Long,
        muxer: MediaMuxer
    ) {
        val videoBuffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_SIZE)
        val audioBuffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_SIZE)

        var videoSize = readSample(videoExtractor, videoBuffer)
        var audioSize = readSample(audioExtractor, audioBuffer)

        while (videoSize >= 0 || audioSize >= 0) {
            val videoTime =
                if (videoSize >= 0) videoExtractor.sampleTime else Long.MAX_VALUE
            val audioTime =
                if (audioSize >= 0) audioExtractor.sampleTime else Long.MAX_VALUE

            if (videoTime <= audioTime) {
                if (videoSize > 0) {
                    writeSample(
                        muxer = muxer,
                        track = videoTrack,
                        buffer = videoBuffer,
                        size = videoSize,
                        sampleTime = videoTime,
                        baseTime = videoBaseTime,
                        flags = videoExtractor.sampleFlags
                    )
                }
                if (!videoExtractor.advance()) {
                    videoSize = -1
                } else {
                    videoSize = readSample(videoExtractor, videoBuffer)
                }
            } else {
                val extractor = audioExtractor

                if (audioSize > 0) {
                    writeSample(
                        muxer = muxer,
                        track = audioTrack,
                        buffer = audioBuffer,
                        size = audioSize,
                        sampleTime = audioTime,
                        baseTime = audioBaseTime,
                        flags = extractor.sampleFlags
                    )
                }
                if (!extractor.advance()) {
                    audioSize = -1
                } else {
                    audioSize = readSample(extractor, audioBuffer)
                }
            }
        }
    }

    private fun readSample(
        extractor: MediaExtractor,
        buffer: ByteBuffer
    ): Int {
        buffer.clear()
        return extractor.readSampleData(buffer, 0)
    }

    private fun writeSample(
        muxer: MediaMuxer,
        track: Int,
        buffer: ByteBuffer,
        size: Int,
        sampleTime: Long,
        baseTime: Long,
        flags: Int
    ) {
        if (size <= 0) return

        val normalizedTime = maxOf(0L, sampleTime - baseTime)
        buffer.position(0)
        buffer.limit(size)

        muxer.writeSampleData(
            track,
            buffer,
            MediaCodec.BufferInfo().apply {
                set(
                    0,
                    size,
                    normalizedTime,
                    flags
                )
            }
        )
    }

    /**
     * Final acceptance check:
     *   - valid MP4 container
     *   - at least one video track
     *   - at least one audio track
     *
     * The file is not considered downloaded until all three conditions pass.
     */
    private fun validatePlayableMp4(file: File) {
        require(file.isFile && file.length() > 0L) {
            "MP4 파일이 없습니다."
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)

            var video = false
            var audio = false

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()

                if (mime.startsWith("video/")) video = true
                if (mime.startsWith("audio/")) audio = true
            }

            check(video) { "MP4 검증 실패: 비디오 트랙이 없습니다." }
            check(audio) { "MP4 검증 실패: 오디오 트랙이 없습니다." }

            Log.i(
                TAG,
                "MP4 validation OK: ${file.length()} bytes, " +
                    "video=$video, audio=$audio"
            )
        } finally {
            extractor.release()
        }
    }

    private fun downloadToFile(
        url: String,
        target: File,
        range: ByteRange? = null
    ) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)

        if (range != null) {
            val end = range.offset?.let { it + range.length - 1L }
            val start = range.offset ?: 0L
            builder.header(
                "Range",
                if (end != null) {
                    "bytes=$start-$end"
                } else {
                    "bytes=$start-${start + range.length - 1L}"
                }
            )
        }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                error("segment HTTP ${response.code}: $url")
            }

            val body = response.body
                ?: error("empty response: $url")

            target.outputStream().buffered(DISK_COPY_BUFFER_SIZE).use { out ->
                body.byteStream().use { input ->
                    input.copyTo(out, DISK_COPY_BUFFER_SIZE)
                }
            }

            if (range != null && target.length() != range.length) {
                error(
                    "HLS byte-range 길이가 다릅니다: " +
                        "expected=${range.length}, actual=${target.length()}"
                )
            }
        }
    }

    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("playlist HTTP ${response.code}: $url")
            }

            return response.body?.string()
                ?: error("empty playlist: $url")
        }
    }

    private fun parseAttributes(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val regex = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")

        for (match in regex.findAll(value)) {
            result[match.groupValues[1]] =
                match.groupValues[3].ifEmpty { match.groupValues[2] }
        }

        return result
    }

    private fun formatAttributes(attrs: Map<String, String>): String =
        attrs.entries.joinToString(",") { (key, value) ->
            "$key=$value"
        }

    private fun quote(path: String): String =
        "'" + path.replace("'", "'\\''") + "'"

    private fun atomicReplace(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (to.exists()) to.delete()
        check(from.renameTo(to)) {
            "MP4 파일 저장에 실패했습니다."
        }
    }

    private fun resolveUrl(base: String, child: String): String =
        runCatching {
            URL(URL(base), child).toString()
        }.getOrElse {
            child
        }

    companion object {
        private const val TAG = "MpvHlsDownloader"
        private const val DISK_COPY_BUFFER_SIZE = 4 * 1024 * 1024
        private const val SAMPLE_BUFFER_SIZE = 16 * 1024 * 1024

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "Chrome/112 Mobile Safari/537.36"

        private const val REFERER = "https://play.sub3.top/"
        private const val ORIGIN = "https://play.sub3.top"

        val defaultClient: OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
