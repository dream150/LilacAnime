package com.lilac.anime.data.offline


import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.subtitle.*
import com.lilac.anime.network.*
import com.lilac.anime.player.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.detail.*
import com.lilac.anime.ui.home.*
import com.lilac.anime.ui.navigation.*
import com.lilac.anime.ui.search.*
import com.lilac.anime.ui.settings.*
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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

    private data class HlsEncryption(
        val method: String,
        val keyUrl: String,
        val iv: ByteArray?
    )

    private data class Segment(
        val url: String,
        val duration: Double,
        val range: ByteRange? = null,
        val encryption: HlsEncryption? = null,
        val sequence: Long = 0L
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
        referer: String? = null,
        onProgress: suspend (Progress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = MpvOfflineStore.episodeDir(context, animeId, episodeId).apply {
            mkdirs()
        }

        Log.i(
            TAG,
            "DOWNLOAD_ENTER anime=$animeId episode=$episodeId source=${sourceUrl.take(180)} " +
                "proxy=${FlixCloudHlsProxy.isProxyUrl(sourceUrl)} referer=${referer?.take(100) ?: "<none>"}"
        )

        val output = MpvOfflineStore.videoFile(context, animeId, episodeId)
        val tempOutput = MpvOfflineStore.videoPartFile(context, animeId, episodeId)

        // A validated final file is immutable. A duplicate enqueue must never
        // restart or truncate an already completed episode.
        if (MpvOfflineStore.isCompleted(context, animeId, episodeId)) {
            onProgress(Progress(1L, 1L))
            return@withContext output
        }
        // Partial HLS data is durable. Never delete it at the start of a retry.
        val localRoot = File(dir, "hls").apply { mkdirs() }

        // A local FlixCloud proxy URL intentionally has no .m3u8 suffix.
        // It is still an HLS playlist endpoint, so it MUST go through the
        // playlist resolver instead of the direct-file branch. Otherwise the
        //  playlist text itself is written to the MP4 temp file and
        // MediaExtractor fails on the first validation attempt.
        val isProxyHls = FlixCloudHlsProxy.isProxyUrl(sourceUrl)
        val isHlsSource = sourceUrl.contains(".m3u8", ignoreCase = true) || isProxyHls
        Log.i(TAG, "SOURCE_CLASSIFY isHls=$isHlsSource isProxyHls=$isProxyHls hasM3u8=${sourceUrl.contains(".m3u8", ignoreCase = true)}")

        // A direct MP4 URL is also accepted. It still goes through the same
        // final audio/video-track validation before becoming "completed".
        if (!isHlsSource) {
            Log.i(TAG, "DIRECT_FILE_START url=${sourceUrl.take(180)}")
            tempOutput.delete()
            downloadToFile(sourceUrl, tempOutput, referer = referer)
            Log.i(TAG, "DIRECT_FILE_DOWNLOADED bytes=${tempOutput.length()}")
            validatePlayableMp4(tempOutput)
            atomicReplace(tempOutput, output)
            Log.i(TAG, "DIRECT_FILE_COMPLETE path=${output.absolutePath} bytes=${output.length()}")
            onProgress(Progress(1L, 1L))
            return@withContext output
        }

        try {
            Log.i(TAG, "DOWNLOAD_START source=$sourceUrl proxy=${FlixCloudHlsProxy.isProxyUrl(sourceUrl)}")
            val playlistInfo = resolvePlaylists(sourceUrl, referer)
            Log.i(
                TAG,
                "PLAYLIST_RESOLVED video=${playlistInfo.mediaUrl} audio=${playlistInfo.audioUrl ?: "<none>"} " +
                    "videoProxy=${FlixCloudHlsProxy.isProxyUrl(playlistInfo.mediaUrl)} " +
                    "audioProxy=${playlistInfo.audioUrl?.let(FlixCloudHlsProxy::isProxyUrl) ?: false}"
            )

            val videoText = getText(playlistInfo.mediaUrl, referer)
            Log.i(TAG, "VIDEO_PLAYLIST_READY chars=${videoText.length} segments=${countMediaUris(videoText)}")
            validateHlsEncryption(videoText)

            val audioText = playlistInfo.audioUrl?.let { getText(it, referer) }
            audioText?.let(::validateHlsEncryption)
            Log.i(TAG, "AUDIO_PLAYLIST_READY present=${audioText != null} chars=${audioText?.length ?: 0} segments=${audioText?.let(::countMediaUris) ?: 0}")

            val videoSegments = parseSegments(playlistInfo.mediaUrl, videoText)
            require(videoSegments.isNotEmpty()) {
                "HLS 비디오 세그먼트가 없습니다."
            }

            val audioSegments = if (playlistInfo.audioUrl != null && audioText != null) {
                parseSegments(playlistInfo.audioUrl, audioText)
            } else {
                emptyList()
            }

            Log.i(
                TAG,
                "PLAYLIST_PARSED videoSegments=${videoSegments.size} audioSegments=${audioSegments.size} " +
                    "videoEncrypted=${videoSegments.count { it.encryption != null }} " +
                    "audioEncrypted=${audioSegments.count { it.encryption != null }}"
            )

            val totalSegments =
                videoSegments.size.toLong() + audioSegments.size.toLong()

            val completedSegments = AtomicLong(0L)

            suspend fun segmentProgress() {
                val current = completedSegments.incrementAndGet()
                onProgress(Progress(current, totalSegments))
            }

            Log.i(TAG, "VIDEO_SEGMENT_PHASE_START count=${videoSegments.size} proxy=${FlixCloudHlsProxy.isProxyUrl(playlistInfo.mediaUrl)}")
            val videoLocal = downloadPlaylist(
                playlistText = videoText,
                baseUrl = playlistInfo.mediaUrl,
                outputDir = File(localRoot, "video").apply { mkdirs() },
                segments = videoSegments,
                referer = referer,
                alreadyDecryptedByProxy = FlixCloudHlsProxy.isProxyUrl(playlistInfo.mediaUrl),
                onSegment = { segmentProgress() }
            )

            Log.i(TAG, "VIDEO_SEGMENT_PHASE_DONE files=${videoLocal.parts.size} init=${videoLocal.initFile?.length() ?: 0} playlist=${videoLocal.file.length()}")

            val audioLocal = if (audioSegments.isNotEmpty() && audioText != null) {
                Log.i(TAG, "AUDIO_SEGMENT_PHASE_START count=${audioSegments.size} proxy=${FlixCloudHlsProxy.isProxyUrl(playlistInfo.audioUrl!!)}")
                downloadPlaylist(
                    playlistText = audioText,
                    baseUrl = playlistInfo.audioUrl!!,
                    outputDir = File(localRoot, "audio").apply { mkdirs() },
                    segments = audioSegments,
                    referer = referer,
                    alreadyDecryptedByProxy = FlixCloudHlsProxy.isProxyUrl(playlistInfo.audioUrl!!),
                    onSegment = { segmentProgress() }
                )
            } else {
                null
            }

            Log.i(TAG, "AUDIO_SEGMENT_PHASE_DONE present=${audioLocal != null} files=${audioLocal?.parts?.size ?: 0} init=${audioLocal?.initFile?.length() ?: 0}")

            tempOutput.delete()

            Log.i(TAG, "MUX_START output=${tempOutput.absolutePath} video=${videoLocal.file.absolutePath} audio=${audioLocal?.file?.absolutePath ?: "<none>"}")
            Log.i(TAG, "Video playlist=${videoLocal.file.absolutePath}")
            Log.i(TAG, "Audio playlist=${audioLocal?.file?.absolutePath}")

            convertLocalHlsToMp4(
                video = videoLocal,
                audio = audioLocal,
                output = tempOutput
            )

            Log.i(TAG, "MUX_DONE outputExists=${tempOutput.isFile} bytes=${tempOutput.length()}")

            check(tempOutput.isFile && tempOutput.length() > 0L) {
                "로컬 HLS를 MP4로 변환하지 못했습니다."
            }

            // Do not mark the episode completed until both video and audio
            // tracks are actually present.
            Log.i(TAG, "MP4_VALIDATE_START path=${tempOutput.absolutePath} bytes=${tempOutput.length()}")
            validatePlayableMp4(tempOutput)
            Log.i(TAG, "MP4_VALIDATE_DONE path=${tempOutput.absolutePath}")

            atomicReplace(tempOutput, output)
            Log.i(TAG, "DOWNLOAD_COMPLETE path=${output.absolutePath} bytes=${output.length()}")

            // Only the final MP4 is needed for offline playback.
            localRoot.deleteRecursively()

            onProgress(Progress(totalSegments, totalSegments))
            output
        } catch (t: Throwable) {
            // Keep every completed HLS segment. Only the muxing scratch file is
            // disposable; the next run will verify and reuse the segment files.
            tempOutput.delete()
            Log.e(TAG, "HLS download failed; partial data preserved", t)
            throw t
        }
    }

    /**
     * Resolves a master playlist into:
     *   - highest-bandwidth video variant
     *   - its AUDIO rendition, when audio is declared separately.
     */
    private fun resolvePlaylists(url: String, referer: String? = null): PlaylistInfo {
        Log.d(TAG, "RESOLVE_MASTER_START url=${url.take(180)} proxy=${FlixCloudHlsProxy.isProxyUrl(url)}")
        val master = getText(url, referer)
        if (!master.contains("#EXT-X-STREAM-INF", ignoreCase = false)) {
            Log.d(TAG, "RESOLVE_MEDIA_PLAYLIST_DIRECT chars=${master.length}")
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

        Log.i(
            TAG,
            "MASTER_VARIANTS count=${variants.size} selectedBandwidth=${selected.bandwidth} " +
                "selected=${selected.url} audioGroup=${selected.audioGroup ?: "<none>"} audioRenditions=${audioRenditions.size}"
        )

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
        referer: String? = null,
        alreadyDecryptedByProxy: Boolean = false,
        onSegment: suspend () -> Unit
    ): LocalPlaylist = coroutineScope {
        outputDir.mkdirs()

        val semaphore = Semaphore(3)
        val keyCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        Log.i(
            TAG,
            "SEGMENT_BATCH_START dir=${outputDir.absolutePath} count=${segments.size} " +
                "proxy=$alreadyDecryptedByProxy encrypted=${segments.count { it.encryption != null }}"
        )
        val completedBefore = segments.indices.count { index ->
            val segment = segments[index]
            val suffix = segmentSuffix(segment.url)
            val target = File(outputDir, "%06d.%s".format(Locale.US, index, suffix))
            target.isFile && target.length() > 0L &&
                (segment.range == null || target.length() == segment.range.length)
        }
        repeat(completedBefore) { onSegment() }

        val files = segments.mapIndexed { index, segment ->
            async {
                semaphore.withPermit {
                    val suffix = segmentSuffix(segment.url)
                    val target = File(outputDir, "%06d.%s".format(Locale.US, index, suffix))
                    val validExisting = target.isFile && target.length() > 0L &&
                        (segment.range == null || target.length() == segment.range.length)

                    if (!validExisting) {
                        Log.d(TAG, "SEGMENT_START seq=${segment.sequence} index=$index url=${segment.url.take(180)} range=${segment.range?.length ?: 0}")
                        val part = File(outputDir, "${target.name}.part")
                        // A .part file is never treated as a completed segment.
                        // downloadToFileWithRetry() removes it only between retry
                        // attempts and only after the request has definitively failed.
                        downloadAndDecryptSegmentWithRetry(
                            segment = segment,
                            target = part,
                            referer = referer,
                            keyCache = keyCache,
                            alreadyDecryptedByProxy = alreadyDecryptedByProxy
                        )
                        check(part.isFile && part.length() > 0L) {
                            "세그먼트 다운로드/복호화 실패: ${segment.url}"
                        }
                        atomicReplace(part, target)
                        Log.d(TAG, "SEGMENT_DONE seq=${segment.sequence} index=$index bytes=${target.length()}")
                    } else {
                        Log.d(TAG, "SEGMENT_REUSE seq=${segment.sequence} index=$index bytes=${target.length()}")
                    }

                    check(target.isFile && target.length() > 0L) {
                        "세그먼트 다운로드 실패: ${segment.url}"
                    }
                    if (segment.range != null) {
                        check(target.length() == segment.range.length) {
                            "HLS byte-range 길이가 다릅니다: ${target.name}"
                        }
                    }
                    if (!validExisting) onSegment()
                    index to target
                }
            }
        }.awaitAll().sortedBy { it.first }.map { it.second }

        var initFile: File? = null
        val mapLine = playlistText.lineSequence()
            .firstOrNull { it.trim().startsWith("#EXT-X-MAP:") }

        if (mapLine != null) {
            val attrs = parseAttributes(mapLine.substringAfter(':'))
            val uri = attrs["URI"] ?: error("EXT-X-MAP URI가 없습니다.")
            val mapUrl = resolveUrl(baseUrl, uri)
            val mapRange = attrs["BYTERANGE"]?.let(::parseByteRange)
            initFile = File(outputDir, "init.mp4")
            val validInit = initFile.isFile && initFile.length() > 0L &&
                (mapRange == null || initFile.length() == mapRange.length)
            if (!validInit) {
                val part = File(outputDir, "init.mp4.part")
                part.delete()
                downloadToFile(mapUrl, part, mapRange, referer)
                check(part.isFile && part.length() > 0L) { "HLS 초기화 세그먼트 다운로드 실패" }
                atomicReplace(part, initFile)
            }
            check(initFile.isFile && initFile.length() > 0L) { "HLS 초기화 세그먼트 다운로드 실패" }
        }

        val localPlaylist = File(outputDir, "playlist.m3u8")
        writeLocalPlaylist(
            localPlaylist,
            playlistText,
            files,
            initFile,
            stripEncryptionTag = alreadyDecryptedByProxy
        )
        Log.i(TAG, "SEGMENT_BATCH_DONE dir=${outputDir.absolutePath} count=${files.size} init=${initFile?.length() ?: 0} playlist=${localPlaylist.length()}")
        LocalPlaylist(localPlaylist, segments.size, initFile, files)
    }

    private fun segmentSuffix(url: String): String = when {
        url.substringBefore('?').endsWith(".m4s", true) -> "m4s"
        url.substringBefore('?').endsWith(".mp4", true) -> "mp4"
        else -> "ts"
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
        initFile: File?,
        stripEncryptionTag: Boolean = false
    ) {
        val lines = original.lines()
        val out = StringBuilder()
        var segmentIndex = 0

        for (raw in lines) {
            val line = raw.trim()

            when {
                stripEncryptionTag && line.startsWith("#EXT-X-KEY:") -> {
                    // FlixCloudHlsProxy has already decoded the encrypted HLS
                    // payload before the local segment is written. Keeping the
                    // EXT-X-KEY tag would make the offline player try to decrypt
                    // the already-plaintext local segments a second time.
                }

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
        var mediaSequence = 0L
        var segmentSequence = 0L
        var encryption: HlsEncryption? = null
        val previousEndByUri = mutableMapOf<String, Long>()

        for (raw in text.lines()) {
            val line = raw.trim()

            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    segmentSequence = mediaSequence
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val method = attrs["METHOD"].orEmpty().trim()
                    encryption = when {
                        method.isBlank() || method.equals("NONE", true) -> null
                        method.equals("AES-128", true) -> {
                            val keyValue = attrs["URI"]?.trim()?.trim('"')
                                ?: error("AES-128 HLS 키 URI가 없습니다.")
                            val keyUrl = resolveUrl(baseUrl, keyValue)
                            val iv = attrs["IV"]?.let(::parseHlsIv)
                            HlsEncryption(method = "AES-128", keyUrl = keyUrl, iv = iv)
                        }
                        method.equals("SAMPLE-AES", true) || method.equals("SAMPLE-AES-CTR", true) ->
                            error("지원하지 않는 HLS 암호화 방식: $method")
                        else -> error("지원하지 않는 HLS 암호화 방식: $method")
                    }
                }

                line.startsWith("#EXTINF:") -> {
                    duration = line.substringAfter(':')
                        .substringBefore(',')
                        .toDoubleOrNull() ?: 0.0
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    pendingRange = parseByteRange(line.substringAfter(':'))
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
                        range = range,
                        encryption = encryption,
                        sequence = segmentSequence++
                    )

                    duration = 0.0
                    pendingRange = null
                }
            }
        }

        return out
    }

    private fun parseByteRange(value: String): ByteRange {
        val parts = value.trim().split('@', limit = 2)
        val length = parts[0].toLongOrNull()
            ?: error("잘못된 HLS BYTERANGE: $value")
        val offset = parts.getOrNull(1)?.toLongOrNull()
        return ByteRange(length, offset)
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
            Log.i(TAG, "CONCAT_VIDEO_START parts=${video.parts.size} init=${video.initFile?.length() ?: 0}")
            concatenateMedia(video, videoSource)
            Log.i(TAG, "CONCAT_VIDEO_DONE bytes=${videoSource.length()}")
            if (audio != null && audioSource != null) {
                Log.i(TAG, "CONCAT_AUDIO_START parts=${audio.parts.size} init=${audio.initFile?.length() ?: 0}")
                concatenateMedia(audio, audioSource)
                Log.i(TAG, "CONCAT_AUDIO_DONE bytes=${audioSource.length()}")
            }

            Log.i(TAG, "MUX_SOURCES_START videoBytes=${videoSource.length()} audioBytes=${audioSource?.length() ?: 0}")
            muxSourcesToMp4(
                videoSource = videoSource,
                audioSource = audioSource,
                output = output
            )
            Log.i(TAG, "MUX_SOURCES_DONE outputBytes=${output.length()}")
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

    private fun validateHlsEncryption(text: String) {
        for (line in text.lineSequence()) {
            if (!line.trim().startsWith("#EXT-X-KEY:")) continue
            val method = parseAttributes(line.substringAfter(':'))["METHOD"].orEmpty()
            if (method.equals("SAMPLE-AES", true) || method.equals("SAMPLE-AES-CTR", true)) {
                error("지원하지 않는 HLS 암호화 방식: $method")
            }
            if (method.isNotBlank() && !method.equals("NONE", true) && !method.equals("AES-128", true)) {
                error("지원하지 않는 HLS 암호화 방식: $method")
            }
        }
    }

    private fun parseHlsIv(value: String): ByteArray {
        val hex = value.trim().removePrefix("0x").removePrefix("0X")
        require(hex.length <= 32 && hex.all { it in "0123456789abcdefABCDEF" }) {
            "잘못된 HLS IV: $value"
        }
        val padded = hex.padStart(32, '0')
        return ByteArray(16) { i -> padded.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun defaultHlsIv(sequence: Long): ByteArray =
        ByteBuffer.allocate(16).putLong(8, sequence).array()

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 키 길이가 잘못되었습니다: ${key.size}" }
        require(iv.size == 16) { "AES-128 IV 길이가 잘못되었습니다: ${iv.size}" }
        require(data.isNotEmpty() && data.size % 16 == 0) {
            "AES-128 세그먼트 길이가 16바이트 배수가 아닙니다: ${data.size}"
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(data)
    }

    private fun downloadAndDecryptSegmentWithRetry(
        segment: Segment,
        target: File,
        referer: String?,
        keyCache: java.util.concurrent.ConcurrentHashMap<String, ByteArray>,
        alreadyDecryptedByProxy: Boolean = false
    ) {
        var lastError: Throwable? = null
        repeat(SEGMENT_RETRY_COUNT) { attempt ->
            try {
                if (attempt > 0) target.delete()
                val raw = File(target.parentFile, "${target.name}.raw")
                raw.delete()
                downloadToFile(segment.url, raw, segment.range, referer)

                val encryption = segment.encryption
                if (alreadyDecryptedByProxy) {
                    // FlixCloudHlsProxy already performs the outer FlixCloud
                    // decoding and returns plaintext media bytes. The manifest
                    // still contains EXT-X-KEY, so never AES-decrypt these bytes
                    // again here.
                    atomicReplace(raw, target)
                    Log.d(TAG, "HLS_PROXY_SEGMENT_DECODED seq=${segment.sequence} bytes=${raw.length()}")
                } else if (encryption == null) {
                    atomicReplace(raw, target)
                } else {
                    val key = keyCache[encryption.keyUrl] ?: run {
                        val downloaded = downloadBytes(encryption.keyUrl, referer)
                        require(downloaded.size == 16) {
                            "AES-128 key 길이가 16바이트가 아닙니다: ${downloaded.size}, url=${encryption.keyUrl}"
                        }
                        keyCache.putIfAbsent(encryption.keyUrl, downloaded) ?: downloaded
                    }
                    val iv = encryption.iv ?: defaultHlsIv(segment.sequence)
                    val encrypted = raw.readBytes()
                    val plain = decryptAes128(encrypted, key, iv)
                    target.outputStream().use { it.write(plain) }
                    raw.delete()
                    Log.d(
                        TAG,
                        "HLS_AES128_DECRYPTED seq=${segment.sequence} bytes=${encrypted.size}->${plain.size} key=${encryption.keyUrl}"
                    )
                }
                return
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                lastError = t
                Log.w(TAG, "encrypted segment retry ${attempt + 1}/$SEGMENT_RETRY_COUNT failed: ${segment.url}", t)
                File(target.parentFile, "${target.name}.raw").delete()
                if (attempt + 1 < SEGMENT_RETRY_COUNT) {
                    try { Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1)) }
                    catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
                }
            }
        }
        throw lastError ?: IllegalStateException("segment download/decrypt failed: ${segment.url}")
    }

    private fun downloadBytes(url: String, referer: String? = null): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer?.takeIf { it.isNotBlank() } ?: REFERER)
            .header("Origin", originFor(referer))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("key HTTP ${response.code}: $url")
            return response.body?.bytes() ?: error("empty key response: $url")
        }
    }

    private fun downloadToFileWithRetry(
        url: String,
        target: File,
        range: ByteRange? = null,
        referer: String? = null
    ) {
        var lastError: Throwable? = null
        repeat(SEGMENT_RETRY_COUNT) { attempt ->
            try {
                // Never append to a partial response unless the request itself
                // explicitly represents a byte-range segment. Starting each
                // retry with a clean .part prevents truncated/corrupt data from
                // being mistaken for a completed HLS segment.
                if (attempt > 0) target.delete()
                downloadToFile(url, target, range, referer)
                return
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                lastError = t
                Log.w(
                    TAG,
                    "segment retry ${attempt + 1}/$SEGMENT_RETRY_COUNT failed: $url",
                    t
                )
                if (attempt + 1 < SEGMENT_RETRY_COUNT) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1))
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }
        throw lastError ?: IllegalStateException("segment download failed: $url")
    }

    private fun downloadToFile(
        url: String,
        target: File,
        range: ByteRange? = null,
        referer: String? = null
    ) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer?.takeIf { it.isNotBlank() } ?: REFERER)
            .header("Origin", originFor(referer))

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

        val request = builder.build()
        Log.v(TAG, "HTTP_SEGMENT_REQUEST method=${request.method} url=${url.take(180)} range=${range?.offset ?: ""}-${range?.length ?: ""}")
        client.newCall(request).execute().use { response ->
            Log.v(TAG, "HTTP_SEGMENT_RESPONSE code=${response.code} bytes=${response.body?.contentLength() ?: -1} url=${url.take(180)}")
            if (!response.isSuccessful) {
                error("segment HTTP ${response.code}: $url")
            }

            val body = response.body
                ?: error("empty response: $url")

            target.parentFile?.mkdirs()
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

    private fun getText(url: String, referer: String? = null): String {
        Log.d(TAG, "GET_PLAYLIST url=$url proxy=${FlixCloudHlsProxy.isProxyUrl(url)}")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer?.takeIf { it.isNotBlank() } ?: REFERER)
            .header("Origin", originFor(referer))
            .build()

        client.newCall(request).execute().use { response ->
            Log.d(TAG, "GET_PLAYLIST_RESPONSE url=$url status=${response.code} bytes=${response.body?.contentLength() ?: -1}")
            if (!response.isSuccessful) {
                error("playlist HTTP ${response.code}: $url")
            }

            return response.body?.string()
                ?: error("empty playlist: $url")
        }
    }

    private fun originFor(referer: String?): String {
        return runCatching {
            val uri = java.net.URI(referer?.takeIf { it.isNotBlank() } ?: REFERER)
            "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
        }.getOrDefault(ORIGIN)
    }

    private fun countMediaUris(text: String): Int =
        text.lineSequence().count { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#")
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
        private const val SEGMENT_RETRY_COUNT = 5
        private const val RETRY_BACKOFF_MS = 1500L

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "Chrome/112 Mobile Safari/537.36"

        private const val REFERER = "https://play.sub3.top/"
        private const val ORIGIN = "https://play.sub3.top"

        val defaultClient: OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
