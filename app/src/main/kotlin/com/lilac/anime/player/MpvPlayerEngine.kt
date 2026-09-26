package com.lilac.anime.player

import com.lilac.anime.*
import com.lilac.anime.cast.*
import com.lilac.anime.core.model.*
import com.lilac.anime.core.update.*
import com.lilac.anime.data.*
import com.lilac.anime.data.matcher.*
import com.lilac.anime.data.offline.*
import com.lilac.anime.data.subtitle.*
import com.lilac.anime.network.*
import com.lilac.anime.ui.*
import com.lilac.anime.ui.detail.*
import com.lilac.anime.ui.home.*
import com.lilac.anime.ui.navigation.*
import com.lilac.anime.ui.search.*
import com.lilac.anime.ui.settings.*
import com.lilac.anime.ui.theme.*
import com.lilac.anime.viewmodel.*

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat.MPV_FORMAT_DOUBLE
import dev.jdtech.mpv.MPVLib.MpvFormat.MPV_FORMAT_FLAG
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.roundToInt
import com.lilac.anime.data.subtitle.SubtitleAssetUtil

/**
 * Android libmpv wrapper used by LilacAnime.
 * Playback is handled by libmpv; the existing ViewModel/repository/subtitle
 * discovery logic remains outside this class.
 */
class MpvPlayerEngine(private val context: Context) {
    companion object {
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4
        private const val TAG = "LilacMpv"
    }
    private val fontsDir = File(context.filesDir, "mpv_fonts").apply { mkdirs() }
    private val mpv: MPVLib = requireNotNull(MPVLib.create(context.applicationContext)) {
        "libmpv initialization failed"
    }

    @Volatile var isPlaying: Boolean = false
        private set
    @Volatile var currentPosition: Long = 0L
        private set
    @Volatile var duration: Long = 0L
        private set
    @Volatile var subtitleText: String = ""
        private set
    @Volatile var playbackState: Int = STATE_IDLE
        private set
    @Volatile var activeLoadGeneration: Long = 0L
        private set

    /** Counts genuine libmpv END_FILE events. */
    @Volatile
    var endFileEventGeneration: Long = 0L
        private set

    @Volatile
    private var suppressEndFileUntilStartFile = false

    private val _endFileEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val endFileEvents: SharedFlow<Long> = _endFileEvents

    /**
     * Completion signal for the currently loaded media. Autoplay uses the same
     * genuine MPV_EVENT_END_FILE completion path as normal playback; the
     * generation prevents duplicate completion events.
     */
    private val _playbackEndedEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val playbackEndedEvents: SharedFlow<Long> = _playbackEndedEvents

    @Volatile
    private var completionSignalledGeneration: Long = -1L

    /**
     * Prepare for a manual episode switch without sending mpv's `stop`.
     *
     * `stop` emits END_FILE and can race the real END_FILE used for autoplay.
     * The next `loadfile ... replace` will tear down the old media itself.
     */
    fun stopForEpisodeSwitch() {
        pendingSubtitlePath = null
        currentSubtitlePath = null
        currentSubtitleIsAss = false
        playWhenLoaded = false
        runCatching { mpv.setPropertyBoolean("pause", true) }
        playbackState = STATE_IDLE
        isPlaying = false
        currentPosition = 0L
        duration = 0L
    }

    @Volatile var loadedLoadGeneration: Long = 0L
        private set

    private var pendingSubtitlePath: String? = null
    private var currentSubtitlePath: String? = null
    private var currentSubtitleIsAss: Boolean = false
    private var playWhenLoaded: Boolean = false
    private var loadGeneration: Long = 0L

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = updateProperty(property)
        override fun eventProperty(property: String, value: Long) = updateProperty(property)
        override fun eventProperty(property: String, value: Double) = updateProperty(property)
        override fun eventProperty(property: String, value: Boolean) = updateProperty(property)
        override fun eventProperty(property: String, value: String) = updateProperty(property)

        override fun event(eventId: Int) {
            when (eventId) {
                MpvEvent.MPV_EVENT_START_FILE -> {
                    // A new load has actually reached mpv. Any END_FILE emitted
                    // while replacing the previous media belongs to the replacement,
                    // not to autoplay for the newly selected episode.
                    suppressEndFileUntilStartFile = false
                    playbackState = STATE_BUFFERING
                    isPlaying = false
                }
                MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    loadedLoadGeneration = loadGeneration
                    playbackState = STATE_READY
                    // Start only after libmpv has actually opened the new media.
                    // Calling pause=false immediately after loadfile can be lost while
                    // replacing a remote HLS item, which made next/previous appear to
                    // switch correctly but remain paused.
                    // is added. Adding it immediately after loadfile is race-prone
                    // (especially for remote HLS + remote VTT/SRT/ASS), so defer it
                    // until FILE_LOADED.
                    pendingSubtitlePath?.let { path ->
                        pendingSubtitlePath = null
                        addSubtitleTrack(path)
                    }
                    if (playWhenLoaded) {
                        playWhenLoaded = false
                        // Some remote HLS replacements report FILE_LOADED while
                        // mpv is still settling the demuxer. Issue both the pause
                        // property change and an explicit play command so the new
                        // episode cannot remain in a paused READY state.
                        runCatching { mpv.setPropertyBoolean("pause", false) }
                        runCatching { mpv.command(arrayOf("play")) }
                        isPlaying = true
                    }
                }
                MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    playbackState = STATE_READY
                }
                MpvEvent.MPV_EVENT_END_FILE -> {
                    if (suppressEndFileUntilStartFile) {
                        Log.d(TAG, "END_FILE_SUPPRESSED_FOR_MEDIA_REPLACEMENT")
                    } else {
                        endFileEventGeneration++
                        Log.d(
                            TAG,
                            "END_FILE generation=$endFileEventGeneration loadGeneration=$loadGeneration"
                        )
                        playbackState = STATE_ENDED
                        isPlaying = false
                        _endFileEvents.tryEmit(endFileEventGeneration)
                        signalPlaybackEnded(loadGeneration)
                    }
                }
            }
        }
    }

    init {
        mpv.setOptionString("config", "no")
        mpv.setOptionString("load-auto-profiles", "no")
        mpv.setOptionString("terminal", "no")
        mpv.setOptionString("msg-level", "all=info")
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("hwdec", "mediacodec")

        // Android TV performance profile. TV devices vary widely, but avoiding
        // software-heavy video processing and keeping a small demuxer cache
        // gives libmpv more predictable frame delivery on remote HLS playback.
        val isTv = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        if (isTv) {
            mpv.setOptionString("video-sync", "audio")
            mpv.setOptionString("interpolation", "no")
            mpv.setOptionString("deband", "no")
            mpv.setOptionString("sigmoid-upscaling", "no")
            mpv.setOptionString("scale", "bilinear")
            mpv.setOptionString("cscale", "bilinear")
            mpv.setOptionString("dscale", "bilinear")
            mpv.setOptionString("framedrop", "vo")
            mpv.setOptionString("cache", "yes")
            mpv.setOptionString("demuxer-max-bytes", "32MiB")
            mpv.setOptionString("demuxer-max-back-bytes", "8MiB")
            mpv.setOptionString("cache-secs", "20")
        }
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("idle", "once")
        mpv.setOptionString("sub-auto", "no")
        mpv.setOptionString("sub-fonts-dir", fontsDir.absolutePath)
        mpv.setOptionString("sub-use-margins", "yes")
        mpv.setOptionString("sub-ass-override", "no")
        // Must be set before mpv.init(): demuxer-lavf-o is consumed when
        // libavformat opens an HLS demuxer. This also makes the fix apply to
        // LinkKF HLS without changing Re:ANIME/other source URLs.
        configureHlsSegmentCompatibility()

        mpv.init()
        mpv.addObserver(observer)

        observe("time-pos", MPV_FORMAT_DOUBLE)
        observe("duration", MPV_FORMAT_DOUBLE)
        observe("pause", MPV_FORMAT_FLAG)
        // 일부 HLS/로컬 MP4에서 END_FILE이 늦거나 누락되는 경우를 위한
        // 자동재생 완료 신호 fallback.
        observe("eof-reached", MPV_FORMAT_FLAG)
        observe("sub-text", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    }

    private fun observe(name: String, format: Int) {
        runCatching { mpv.observeProperty(name, format) }
            .onFailure { Log.w(TAG, "observeProperty failed: $name", it) }
    }

    private fun updateProperty(property: String) {
        // During an episode/server switch libmpv can still deliver delayed
        // property notifications after the old media has already been torn
        // down. Querying time-pos/duration/eof-reached at that moment makes
        // native mpv print "property unavailable" and can race the next load.
        if (playbackState == STATE_IDLE) return

        runCatching {
            when (property) {
                "time-pos" -> {
                    val value = mpv.getPropertyDouble("time-pos") ?: return@runCatching
                    currentPosition = (value * 1000.0).toLong().coerceAtLeast(0L)
                }
                "duration" -> {
                    val value = mpv.getPropertyDouble("duration") ?: return@runCatching
                    duration = (value * 1000.0).toLong().coerceAtLeast(0L)
                }
                "pause" -> {
                    isPlaying = mpv.getPropertyBoolean("pause") != true
                }
                "eof-reached" -> {
                    if (mpv.getPropertyBoolean("eof-reached") == true) {
                        signalPlaybackEnded(loadedLoadGeneration)
                    }
                }
                "sub-text" -> subtitleText = mpv.getPropertyString("sub-text") ?: ""
            }
        }.onFailure {
            // A property can disappear while mpv is replacing the media.
            // This is expected during teardown, so never propagate it.
            Log.d(TAG, "MPV_PROPERTY_UNAVAILABLE property=$property")
        }
    }

    fun attachSurface(surface: Surface) {
        mpv.attachSurface(surface)
        mpv.setOptionString("force-window", "yes")
    }

    fun detachSurface() {
        runCatching {
            mpv.setOptionString("force-window", "no")
            mpv.detachSurface()
        }
    }

    /**
     * LinkKF's current HLS manifest uses MPEG-TS segments whose URLs end in
     * ".jpg". The bytes are actually MPEG-TS, but FFmpeg/libavformat's HLS
     * demuxer rejects the segment before probing it unless that extension is
     * allowed.
     *
     * FFmpeg versions bundled with libmpv differ: newer builds expose
     * allowed_segment_extensions separately; older builds used
     * allowed_extensions for the same HLS extension check.
     */
    private fun configureHlsSegmentCompatibility() {
        val modern = mpv.setOptionString(
            "demuxer-lavf-o",
            "allowed_segment_extensions=ALL"
        )
        if (modern >= 0) {
            Log.d(TAG, "HLS_SEGMENT_COMPAT enabled modern allowed_segment_extensions=ALL")
            return
        }

        val legacy = mpv.setOptionString(
            "demuxer-lavf-o",
            "allowed_extensions=ALL"
        )
        if (legacy >= 0) {
            Log.d(TAG, "HLS_SEGMENT_COMPAT enabled legacy allowed_extensions=ALL")
        } else {
            Log.e(
                TAG,
                "HLS_SEGMENT_COMPAT failed modernResult=$modern legacyResult=$legacy"
            )
        }
    }

    fun configureNetworkHeaders(headers: String, referer: String? = null) {
        // mpv expects HTTP header fields as a comma-separated list.
        // A newline-delimited value can be ignored by the native HTTP client,
        // which is especially visible with protected VTT files.
        if (headers.isNotBlank()) {
            val lines = headers
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
            val normalized = lines.joinToString(",")
            val userAgent = lines
                .firstOrNull { it.startsWith("User-Agent:", true) }
                ?.substringAfter(":")
                ?.trim()
            // Send the captured browser UA both through the explicit mpv option and
            // the header list. Some libmpv builds handle one path more reliably.
            runCatching {
                mpv.setOptionString("http-header-fields", normalized)
            }.onFailure {
                Log.w(TAG, "HTTP_HEADERS_OPTION_FAILED", it)
            }
            runCatching {
                mpv.setOptionString("user-agent", userAgent.orEmpty())
            }.onFailure {
                Log.w(TAG, "HTTP_USER_AGENT_OPTION_FAILED", it)
            }
        } else {
            runCatching { mpv.setOptionString("http-header-fields", "") }
            runCatching { mpv.setOptionString("user-agent", "") }
        }
        runCatching {
            mpv.setOptionString("referrer", referer.orEmpty())
        }.onFailure {
            Log.w(TAG, "HTTP_REFERRER_OPTION_FAILED", it)
        }
    }

    fun setSubtitleFontsDir(path: String) {
        // sub-fonts-dir is a runtime mpv property. Using setPropertyString here
        // makes changes effective for tracks loaded after the directory is updated.
        mpv.setPropertyString("sub-fonts-dir", path)
    }

    fun setSubtitleFontFamily(family: String) {
        mpv.setPropertyString("sub-font", family)
    }

    /** Restore mpv/libass font selection to the subtitle's own/default font. */
    fun resetSubtitleFontFamily() {
        runCatching { mpv.setPropertyString("sub-font", "") }
    }

    /** Replace only the currently selected external subtitle; video playback is untouched. */
    fun replaceSubtitleTrack(path: String?) {
        val value = path?.takeIf { it.isNotBlank() } ?: return
        val sub = if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            value
        } else {
            File(value.removePrefix("file://")).absolutePath
        }
        // `sub-remove` can legitimately fail when no external subtitle track is
        // currently loaded. Do NOT put it in the same runCatching as `sub-add`:
        // otherwise a harmless remove failure prevents the VTT from ever being
        // added. This was especially easy to hit on the first Linkkf VTT attach.
        runCatching {
            mpv.command(arrayOf("sub-remove"))
        }.onFailure {
            Log.d(TAG, "SUB_REMOVE_IGNORED path=$sub reason=${it.message}")
        }

        runCatching {
            Log.d(TAG, "SUB_ADD path=$sub exists=${File(sub.removePrefix("file://")).isFile} url=${sub.startsWith("http://", true) || sub.startsWith("https://", true)}")
            mpv.command(arrayOf("sub-add", sub, "select"))
            mpv.setPropertyBoolean("sub-visibility", true)
            mpv.setPropertyString("sub-auto", "no")
            currentSubtitlePath = sub
            currentSubtitleIsAss = sub.isAssLike()
            Log.d(TAG, "SUB_ADD_OK path=$sub")
        }.onFailure {
            Log.e(TAG, "SUB_ADD_FAILED path=$sub", it)
        }
    }

    fun reloadCurrentSubtitle() {
        runCatching { mpv.command(arrayOf("sub-reload")) }
            .onFailure { Log.w(TAG, "SUB_RELOAD_FAILED", it) }
    }

    fun setSubtitleDelay(offsetMs: Long) {
        runCatching { mpv.setPropertyDouble("sub-delay", offsetMs / 1000.0) }
    }

    fun setSubtitleVisible(visible: Boolean) {
        runCatching { mpv.setPropertyBoolean("sub-visibility", visible) }
    }

    fun setSpeed(speed: Float) {
        mpv.setPropertyDouble("speed", speed.toDouble())
    }

    fun getSpeed(): Float = (mpv.getPropertyDouble("speed") ?: 1.0).toFloat()

    fun play() {
        runCatching { mpv.setPropertyBoolean("pause", false) }
        runCatching { mpv.command(arrayOf("play")) }
        isPlaying = true
        playbackState = STATE_READY
    }

    /**
     * Re-assert playback after a remote HLS episode replacement. This is safe
     * only for the currently loaded generation and is used by PlayerScreen as
     * a small watchdog during the first moments after FILE_LOADED.
     */
    fun forcePlayIfReady(expectedGeneration: Long): Boolean {
        if (loadedLoadGeneration != expectedGeneration ||
            playbackState != STATE_READY) return false
        val paused = mpv.getPropertyBoolean("pause") == true
        if (paused || !isPlaying) {
            play()
        }
        return true
    }

    fun pause() {
        mpv.setPropertyBoolean("pause", true)
        isPlaying = false
    }

    fun seekBy(seconds: Double) {
        mpv.command(arrayOf("seek", seconds.toString(), "relative+exact"))
    }

    fun seekTo(positionMs: Long) {
        mpv.command(
            arrayOf(
                "seek",
                (positionMs.coerceAtLeast(0L) / 1000.0).toString(),
                "absolute+exact"
            )
        )
    }

    fun stop() {
        pendingSubtitlePath = null
        currentSubtitlePath = null
        currentSubtitleIsAss = false
        playWhenLoaded = false
        runCatching { mpv.command(arrayOf("stop")) }
        isPlaying = false
        playbackState = STATE_IDLE
        currentPosition = 0L
        duration = 0L
        subtitleText = ""
    }

    fun clearMediaItems() = stop()

    /**
     * ASS/SSA normally uses libass script styling, which can be expensive on
     * lower-powered TV hardware. "strip" keeps subtitle text but removes
     * ASS override tags/effects; "no" preserves the original ASS effects.
     */
    fun setAssEffectsEnabled(enabled: Boolean) {
        mpv.setOptionString("sub-ass-override", if (enabled) "no" else "strip")
        if (currentSubtitleIsAss && currentSubtitlePath != null) {
            runCatching { mpv.command(arrayOf("sub-reload")) }
        }
    }

    fun applySubtitleStyle(
        textColor: Int,
        borderColor: Int,
        sizePercent: Float,
        bold: Boolean,
        outlineWidth: Float,
        bottomPaddingFraction: Float,
        pip: Boolean,
        ass: Boolean = currentSubtitleIsAss
    ) {
        // ASS/SSA must keep the script's own Style.Fontname and FontSize.
        // Applying mpv's generic sub-font-size/sub-bold/sub-border-size to ASS
        // changes its visual scale and makes the same ASS look different when a
        // custom font is selected. Generic styling is therefore VTT/SRT-only.
        if (ass) return

        setMpvColor("sub-color", textColor)
        setMpvColor("sub-border-color", borderColor)
        // The old Compose/Media3 VTT renderer used 18sp as its 100% baseline.
        val scale = if (pip) 0.48f else 1f
        val fontSize = (36f * (sizePercent / 100f) * scale).coerceIn(10f, 72f)
        mpv.setPropertyDouble("sub-font-size", fontSize.toDouble())
        mpv.setPropertyBoolean("sub-bold", bold)
        mpv.setPropertyDouble("sub-border-size", outlineWidth.coerceIn(0f, 8f).toDouble())
        val position = ((1f - bottomPaddingFraction.coerceIn(0.03f, 0.45f)) * 100f)
            .roundToInt().coerceIn(55, 97)
        mpv.setPropertyDouble("sub-pos", position.toDouble())
    }

    private fun setMpvColor(name: String, color: Int) {
        val rgb = String.format(
            Locale.US,
            "#%02X%02X%02X",
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color)
        )
        runCatching { mpv.setPropertyString(name, rgb) }
    }

    private fun signalPlaybackEnded(generation: Long) {
        if (generation <= 0L || loadedLoadGeneration != generation) return
        if (completionSignalledGeneration == generation) return
        completionSignalledGeneration = generation
        Log.d(TAG, "PLAYBACK_ENDED generation=$generation")
        _playbackEndedEvents.tryEmit(generation)
    }

    fun load(
        url: String,
        subtitlePath: String?,
        syncOffsetMs: Long,
        customFontPath: String?,
        autoPlay: Boolean = true
    ) {
        loadGeneration++
        activeLoadGeneration = loadGeneration
        completionSignalledGeneration = -1L
        // loadfile ... replace is atomic from mpv's point of view and avoids the
        // extra stop -> END_FILE -> load race that made episode navigation pause.
        pendingSubtitlePath = null
        currentSubtitlePath = null
        currentSubtitleIsAss = subtitlePath?.let {
            val clean = it.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
            clean.endsWith(".ass") || clean.endsWith(".ssa")
        } == true
        playWhenLoaded = autoPlay
        isPlaying = false
        playbackState = STATE_BUFFERING
        // Replacing an already loaded item may emit END_FILE for the old item.
        // Suppress only that pre-START_FILE event; START_FILE clears the guard,
        // so a real EOF from the new episode still reaches autoplay.
        suppressEndFileUntilStartFile = true
        currentPosition = 0L
        duration = 0L
        subtitleText = ""

        // ASS/SSA의 경우 caller가 customFontPath를 null로 전달한다.
        // 먼저 전역 sub-font를 초기화해 이전 VTT 설정이 ASS에 남지 않도록 한다.
        resetSubtitleFontFamily()
        val font = customFontPath?.let(::File)?.takeIf { it.isFile }
        if (font != null) {
            fontsDir.mkdirs()
            val target = File(fontsDir, font.name)
            runCatching { font.copyTo(target, overwrite = true) }
            setSubtitleFontsDir(fontsDir.absolutePath)
            SubtitleAssetUtil.fontFamilyName(font)?.let { setSubtitleFontFamily(it) }
        }

        mpv.setOptionString("sub-ass-override", "no")
        mpv.setPropertyDouble("sub-delay", syncOffsetMs / 1000.0)
        // ASS keeps its script-defined font family, FontSize, outline and weight.
        // Do not set sub-font-size here: that generic mpv setting is what can make
        // an ASS subtitle change size when a different font is selected.
        if (currentSubtitleIsAss) {
            mpv.setOptionString("sub-ass-override", "no")
        } else {
            mpv.setOptionString("sub-ass-override", "yes")
        }
        mpv.setPropertyBoolean("sub-visibility", false)
        pendingSubtitlePath = subtitlePath?.takeIf { it.isNotBlank() }
        Log.d(TAG, "LOAD generation=$loadGeneration url=$url subtitle=$subtitlePath")
        mpv.command(arrayOf("loadfile", url, "replace"))
    }

    private fun String.isAssLike(): Boolean {
        val clean = substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return clean.endsWith(".ass") || clean.endsWith(".ssa")
    }

    private fun addSubtitleTrack(subtitlePath: String) {
        val sub = if (
            subtitlePath.startsWith("http://", true) ||
            subtitlePath.startsWith("https://", true)
        ) {
            subtitlePath
        } else {
            File(subtitlePath.removePrefix("file://")).absolutePath
        }

        if (sub.isBlank()) return

        runCatching {
            Log.d(TAG, "SUB_ADD path=$sub")
            mpv.command(arrayOf("sub-add", sub, "select"))
            currentSubtitlePath = sub
            // All external subtitle formats are rendered by mpv/libass. For ASS/SSA
            // libass preserves script positioning/styles; VTT/SRT use mpv's native
            // text renderer and therefore do not need the old Compose TextView.
            mpv.setPropertyBoolean("sub-visibility", true)
        }.onFailure {
            Log.e(TAG, "SUB_ADD_FAILED path=$sub", it)
        }
    }

    fun release() {
        runCatching {
            mpv.removeObserver(observer)
            mpv.destroy()
        }
    }

}

/**
 * Surface used by Compose's AndroidView. Gestures are handled here instead of
 * by Media3/PlayerView, so the behavior remains stable after the player swap.
 */
class MpvPlayerSurfaceView(
    context: Context,
    private val engine: MpvPlayerEngine
) : TextureView(context), TextureView.SurfaceTextureListener {

    var seekSeconds: Long = 10L
    var gesturesLocked: Boolean = false
    var onSingleTap: (() -> Unit)? = null
    var onUnlockTap: (() -> Unit)? = null
    var onDoubleTap: ((Int) -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    private var attached = false
    private var longPressActive = false
    private var speedBeforeLongPress = 1.0f

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // TextureView가 Compose overlay보다 먼저 터치를 소비할 수 있으므로,
                // 잠금 상태에서는 화면 왼쪽 중앙의 잠금 버튼을 native 쪽에서도 직접 처리한다.
                if (gesturesLocked) {
                    val density = resources.displayMetrics.density
                    val hitWidth = 80f * density
                    val centerY = height / 2f
                    val hitHeight = 70f * density
                    if (e.x <= hitWidth && kotlin.math.abs(e.y - centerY) <= hitHeight) {
                        onUnlockTap?.invoke()
                        return true
                    }
                }
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (gesturesLocked) return true

                if (e.x < width / 2f) {
                    engine.seekBy(-seekSeconds.toDouble())
                    onDoubleTap?.invoke(-1)
                } else {
                    engine.seekBy(seekSeconds.toDouble())
                    onDoubleTap?.invoke(1)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (gesturesLocked || longPressActive || !engine.isPlaying) return

                longPressActive = true
                speedBeforeLongPress = engine.getSpeed().coerceAtLeast(0.1f)
                engine.setSpeed(2.0f)
                onLongPress?.invoke()
            }
        }
    )

    init {
        surfaceTextureListener = this
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val delta = seekSeconds.coerceAtLeast(0L).toDouble()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (!gesturesLocked) engine.seekBy(-delta)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (!gesturesLocked) engine.seekBy(delta)
                    return true
                }
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (!gesturesLocked) {
                        if (engine.isPlaying) engine.pause() else engine.play()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            if (longPressActive) {
                longPressActive = false
                engine.setSpeed(speedBeforeLongPress)
            }
        }

        return handled || super.onTouchEvent(event)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        engine.attachSurface(Surface(surface))
        attached = true
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (attached) engine.detachSurface()
        attached = false
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
