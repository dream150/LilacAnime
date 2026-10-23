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
import android.os.SystemClock
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat.MPV_FORMAT_DOUBLE
import dev.jdtech.mpv.MPVLib.MpvFormat.MPV_FORMAT_FLAG
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
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
        // 진단용: true로 바꾸면 delogo 대신 선택 영역에 빨간 반투명 박스를 그립니다.
        // 박스가 보이면 vf 파이프라인은 정상이고, 안 보이면 필터가 렌더링에 반영되지 않는 겁니다.
        private const val WATERMARK_DEBUG_BOX = false
    }
    private val fontsDir = File(context.filesDir, "mpv_fonts").apply { mkdirs() }
    private val upscalingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mpv: MPVLib = requireNotNull(MPVLib.create(context.applicationContext)) {
        "libmpv initialization failed"
    }

    @Volatile var isPlaying: Boolean = false
        private set
    @Volatile var currentPosition: Long = 0L
        private set
    @Volatile var duration: Long = 0L
        private set
    @Volatile var videoWidth: Int = 0
        private set
    @Volatile var videoHeight: Int = 0
        private set
    /** Actual display/output dimensions reported by mpv (after pixel aspect correction). */
    @Volatile var videoDisplayWidth: Int = 0
        private set
    @Volatile var videoDisplayHeight: Int = 0
        private set
    @Volatile var videoDisplayAspect: Double = 0.0
        private set
    @Volatile private var aiUpscalingRequested: Boolean = false
    @Volatile private var aiUpscalingBackendPreference: String = "gpu"
    @Volatile private var aiUpscalingAnime4KMode: String = "A"
    @Volatile private var aiUpscalingScaleMultiplier: Int = 2
    @Volatile private var lastUpscalingProfile: String = "default"
    @Volatile private var upscalingGeneration: Long = 0L
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
    private val _subtitleEvents = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val subtitleEvents: SharedFlow<String> = _subtitleEvents
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
        // An episode switch may leave one delayed END_FILE event from the old
        // HLS item in libmpv's queue. Suppress that event until the replacement
        // emits START_FILE, otherwise the PlayerScreen's autoplay collector can
        // interpret the stale event as another natural episode completion.
        suppressEndFileUntilStartFile = true
        runCatching { mpv.setPropertyBoolean("pause", true) }
        playbackState = STATE_IDLE
        isPlaying = false
        currentPosition = 0L
        duration = 0L
        videoWidth = 0
        videoHeight = 0
        videoDisplayWidth = 0
        videoDisplayHeight = 0
        videoDisplayAspect = 0.0
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
        override fun eventProperty(property: String, value: Long) {
            when (property) {
                "video-params/w" -> videoWidth = value.toInt().coerceAtLeast(0)
                "video-params/h" -> videoHeight = value.toInt().coerceAtLeast(0)
                "video-params/dw" -> videoDisplayWidth = value.toInt().coerceAtLeast(0)
                "video-params/dh" -> videoDisplayHeight = value.toInt().coerceAtLeast(0)
            }
            if (property == "video-params/w" || property == "video-params/h") {
                applyUpscalingProfile()
                applyWatermarkFilter()
            }
            updateProperty(property)
        }
        override fun eventProperty(property: String, value: Double) {
            if (property == "video-params/dar" && value.isFinite() && value > 0.01) {
                videoDisplayAspect = value
            }
            if (value.isFinite()) {
                when (property) {
                    "video-params/w" -> videoWidth = value.toInt().coerceAtLeast(0)
                    "video-params/h" -> videoHeight = value.toInt().coerceAtLeast(0)
                    "video-params/dw" -> videoDisplayWidth = value.toInt().coerceAtLeast(0)
                    "video-params/dh" -> videoDisplayHeight = value.toInt().coerceAtLeast(0)
                }
            }
            if (property == "video-params/w" || property == "video-params/h") {
                applyWatermarkFilter()
            }
            updateProperty(property)
        }
        override fun eventProperty(property: String, value: Boolean) = updateProperty(property)
        override fun eventProperty(property: String, value: String) {
            if (property == "sub-text") {
                subtitleText = value
                _subtitleEvents.tryEmit(value)
            } else {
                updateProperty(property)
            }
        }

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
        mpv.setOptionString("msg-level", "all=info,vo=debug,gpu=debug")
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
        mpv.setOptionString("keepaspect", "yes")
        mpv.setOptionString("video-zoom", "0")
        mpv.setOptionString("panscan", "0")
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("idle", "once")
        mpv.setOptionString("sub-auto", "no")
        mpv.setOptionString("sub-fonts-dir", fontsDir.absolutePath)
        mpv.setOptionString("sub-use-margins", "no")
        mpv.setOptionString("sub-ass-override", "no")
        // Must be set before mpv.init(): demuxer-lavf-o is consumed when
        // libavformat opens an HLS demuxer. This also makes the fix apply to
        // LinkKF HLS without changing Re:ANIME/other source URLs.
        configureHlsSegmentCompatibility()

        mpv.init()
        mpv.addObserver(observer)

        observe("time-pos", MPV_FORMAT_DOUBLE)
        observe("duration", MPV_FORMAT_DOUBLE)
        observe("video-params/dar", MPV_FORMAT_DOUBLE)
        observe("video-params/w", MPV_FORMAT_DOUBLE)
        observe("video-params/h", MPV_FORMAT_DOUBLE)
        observe("video-params/dw", MPV_FORMAT_DOUBLE)
        observe("video-params/dh", MPV_FORMAT_DOUBLE)
        observe("pause", MPV_FORMAT_FLAG)
        // 일부 HLS/로컬 MP4에서 END_FILE이 늦거나 누락되는 경우를 위한
        // 자동재생 완료 신호 fallback.
        observe("eof-reached", MPV_FORMAT_FLAG)
        observe("sub-text", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    }

    fun configureAiUpscaling(
        enabled: Boolean,
        backendPreference: String = "gpu",
        scaleMultiplier: Int = 2,
        anime4kMode: String = "A"
    ) {
        aiUpscalingRequested = enabled
        aiUpscalingBackendPreference = backendPreference.takeIf { it in setOf("gpu", "anime4k", "animejanai") } ?: "gpu"
        aiUpscalingScaleMultiplier = scaleMultiplier.takeIf { it in setOf(2, 3, 4) } ?: 2
        aiUpscalingAnime4KMode = anime4kMode.takeIf { it in setOf("A", "B", "C", "A+A", "B+B", "C+A") } ?: "A"
        upscalingGeneration++
        lastUpscalingProfile = ""
        applyUpscalingProfile()
    }

    private fun applyUpscalingProfile() {
        val shouldUseAnime4K = aiUpscalingRequested && aiUpscalingBackendPreference == "anime4k"
        val profile = if (aiUpscalingRequested) "upscale-${aiUpscalingBackendPreference}-${aiUpscalingScaleMultiplier}x-${aiUpscalingAnime4KMode}" else "default"
        if (profile == lastUpscalingProfile) return
        lastUpscalingProfile = profile
        val generation = upscalingGeneration

        if (shouldUseAnime4K) {
            upscalingScope.launch {
                try {
                    val selectedMode = aiUpscalingAnime4KMode
                    val shaders = com.lilac.anime.player.upscaling.Anime4KShaderManager.ensureShaders(context.applicationContext, selectedMode)
                    if (generation != upscalingGeneration || !aiUpscalingRequested || aiUpscalingBackendPreference != "anime4k" || selectedMode != aiUpscalingAnime4KMode) {
                        Log.i(TAG, "UPSCALING_PROFILE backend=anime4k status=SUPERSEDED generation=$generation currentGeneration=$upscalingGeneration")
                        return@launch
                    }

                    val hwdecResult = runCatching { mpv.setPropertyString("hwdec", "mediacodec-copy") }
                    if (hwdecResult.isFailure) {
                        Log.e(TAG, "ANIME4K_HWDEC_CONFIG_FAILED requested=mediacodec-copy", hwdecResult.exceptionOrNull())
                    }

                    val paths = shaders.joinToString(":") { it.absolutePath }
                    anime4kShaderPaths = shaders.map { it.absolutePath }
                    applyShaderList()
                    runCatching { mpv.setPropertyString("scale", "ewa_lanczossharp") }
                    runCatching { mpv.setPropertyString("cscale", "spline36") }
                    runCatching { mpv.setPropertyString("dscale", "mitchell") }

                    val configured = runCatching { mpv.getPropertyString("glsl-shaders") }.getOrNull().orEmpty()
                    val listedCount = shaders.count { configured.contains(it.absolutePath) }
                    val currentWidth = videoWidth
                    val currentHeight = videoHeight
                    val status = when {
                        hwdecResult.isFailure -> "SHADERS_SET_HWDEC_UNCONFIRMED"
                        listedCount == 0 -> "SHADERS_SET_LIST_UNCONFIRMED"
                        listedCount < shaders.size -> "SHADERS_PARTIALLY_LISTED"
                        else -> "SHADERS_CONFIGURED"
                    }
                    Log.i(TAG, "UPSCALING_PROFILE backend=anime4k status=$status source=${currentWidth}x${currentHeight} mode=$selectedMode files=${shaders.size} listed=$listedCount hwdecCopy=${hwdecResult.isSuccess} paths=$paths")
                    Log.i(TAG, "ANIME4K_VERIFY mode=$selectedMode renderer=gpu shaderListReadback=${configured.isNotBlank()} listed=$listedCount expected=${shaders.size}; inspect mpv vo/gpu compile logs for runtime confirmation")
                } catch (t: Throwable) {
                    if (generation == upscalingGeneration) {
                        Log.e(TAG, "UPSCALING_PROFILE backend=anime4k status=SHADER_SETUP_FAILED source=${videoWidth}x${videoHeight}", t)
                        anime4kShaderPaths = emptyList()
                        applyShaderList()
                        applyHwdecPolicy()
                    } else {
                        Log.i(TAG, "UPSCALING_PROFILE backend=anime4k status=STALE_SETUP_ERROR generation=$generation currentGeneration=$upscalingGeneration", t)
                    }
                }
            }
            return
        }

        anime4kShaderPaths = emptyList()
        applyShaderList()
        applyHwdecPolicy()

        if (aiUpscalingRequested) {
            val results = listOf(
                runCatching { mpv.setPropertyString("scale", "ewa_lanczossharp") }.isSuccess,
                runCatching { mpv.setPropertyString("cscale", "ewa_lanczossharp") }.isSuccess,
                runCatching { mpv.setPropertyString("dscale", "mitchell") }.isSuccess,
                runCatching { mpv.setPropertyString("scale-antiring", "0.7") }.isSuccess
            )
            val status = when (aiUpscalingBackendPreference) {
                "animejanai" -> "ANDROID_RUNTIME_NOT_PORTED_GPU_FALLBACK"
                else -> "GPU_SCALER_ACTIVE"
            }
            Log.i(TAG, "UPSCALING_PROFILE requested=true status=$status source=${videoWidth}x${videoHeight} backend=$aiUpscalingBackendPreference targetScale=${aiUpscalingScaleMultiplier}x optionsApplied=${results.count { it }}/4")
        } else {
            val results = listOf(
                runCatching { mpv.setPropertyString("scale", "bilinear") }.isSuccess,
                runCatching { mpv.setPropertyString("cscale", "bilinear") }.isSuccess,
                runCatching { mpv.setPropertyString("dscale", "bilinear") }.isSuccess,
                runCatching { mpv.setPropertyString("scale-antiring", "0") }.isSuccess
            )
            Log.i(TAG, "UPSCALING_PROFILE requested=false status=DISABLED source=${videoWidth}x${videoHeight} optionsApplied=${results.count { it }}/4")
        }
    }

    // ---- Watermark removal (libavfilter delogo through mpv's vf chain) ----
    private val watermarkFilterLabel = "lilacwm"
    private var watermarkRequested = false
    private var watermarkRects: List<WatermarkRect> = emptyList()
    private var appliedWatermarkKey = ""

    /** PC에서 estimate_wm.py 로 만든 역합성 셰이더. 앱 내부 저장소에 보관합니다. */
    private val watermarkShaderFile: File
        get() = File(File(context.filesDir, "watermark").apply { mkdirs() }, "wm_remove.glsl")

    @Volatile private var anime4kShaderPaths: List<String> = emptyList()
    @Volatile private var watermarkPreview = false
    @Volatile private var watermarkTest = false
    private var lastWatermarkReloadAt = 0L
    private var appliedShaderKey = ""

    fun hasWatermarkShader(): Boolean = watermarkShaderFile.let { it.isFile && it.length() > 0 }

    /** 셰이더(GPU, 역합성) 모드: 마스크 파일이 있으면 delogo 대신 이쪽을 씁니다. */
    private fun watermarkShaderMode(): Boolean = watermarkRequested && hasWatermarkShader() && !learningWatermark

    /** delogo(CPU) 모드: 마스크 파일이 없고 영역이 지정된 경우에만. */
    private fun watermarkActive(): Boolean =
        watermarkRequested && !hasWatermarkShader() && watermarkRects.isNotEmpty() && !learningWatermark

    private class LoadedShader(var key: String = "", var path: String = "")
    private val wmSlot = LoadedShader()
    private val subSlot = LoadedShader()
    private var shaderLoadCounter = 0

    /**
     * mpv 는 glsl-shaders 값(경로 문자열)이 이전과 같으면 파일 내용이 바뀌었어도 다시 읽지 않습니다.
     * 그래서 내용이 바뀔 때마다 새 이름의 복사본을 만들어 그 경로를 넘깁니다.
     * textured=true 는 마스크 텍스처가 있는 파일(본문 한 줄화 + 헤더 보정), false 는 그대로 복사.
     */
    private fun loadablePath(src: File, slot: LoadedShader = wmSlot, textured: Boolean = true): String {
        val key = "${src.absolutePath}:${src.lastModified()}:${src.length()}"
        if (key == slot.key && File(slot.path).isFile) return slot.path
        val dst = File(src.parentFile, "wm_load_${++shaderLoadCounter}.glsl")
        if (textured) WatermarkMaskLearner.copyShaderFixed(src, dst) else src.copyTo(dst, overwrite = true)
        runCatching { if (slot.path.isNotEmpty()) File(slot.path).delete() }
        slot.key = key
        slot.path = dst.absolutePath
        Log.i(TAG, "WATERMARK_SHADER_PATH src=${src.name} -> ${dst.name} size=${dst.length()}")
        return slot.path
    }

    // ---- 영상에 박힌 자막 지우기 (글자 픽셀 검출 + 주변으로 메우기, GPU 셰이더) ----
    @Volatile private var subtitleEraseRequested = false
    @Volatile private var subtitleErasePaused = false
    private var subtitleEraseRegions: List<WatermarkRect> = emptyList()
    private var subtitleEraseKey = ""
    private val subtitleEraseFile: File
        get() = File(File(context.filesDir, "watermark").apply { mkdirs() }, "sub_erase.glsl")

    private fun subtitleEraseMode(): Boolean =
        subtitleEraseRequested && !subtitleErasePaused && subtitleEraseRegions.isNotEmpty() && !learningWatermark

    /** 영역 선택용 프레임을 원본 그대로 잡기 위해 잠시 끄고 켭니다. */
    fun setSubtitleErasePaused(paused: Boolean) {
        subtitleErasePaused = paused
        applyHwdecPolicy()
        applyShaderList()
    }

    fun configureSubtitleErase(enabled: Boolean, regions: String) {
        subtitleEraseRequested = enabled
        subtitleEraseRegions = WatermarkRegions.parse(regions)
        val key = "$enabled:${WatermarkRegions.serialize(subtitleEraseRegions)}"
        if (key == subtitleEraseKey) return
        subtitleEraseKey = key
        if (subtitleEraseRegions.isNotEmpty()) {
            runCatching { subtitleEraseFile.writeText(buildSubtitleEraseShader(subtitleEraseRegions), Charsets.US_ASCII) }
                .onFailure { Log.e(TAG, "SUBTITLE_ERASE write failed", it) }
        }
        applyHwdecPolicy()
        applyShaderList()
    }

    /**
     * 3패스: (1) 글자 후보 검출(밝은 획 + 어두운 외곽선) (2) 팽창 (3) 팽창된 영역을 주변의
     * "글자가 아닌" 픽셀 평균으로 메움. 검출/팽창은 절반 해상도에서 해서 비용을 줄인다.
     * 영역 밖은 모든 패스에서 즉시 반환한다.
     */
    private fun buildSubtitleEraseShader(rects: List<WatermarkRect>): String {
        val band = StringBuilder("bool inBand(vec2 p) {\n    return ")
        band.append(
            rects.joinToString(" || ") {
                String.format(
                    Locale.US, "(p.x >= %.5f && p.x <= %.5f && p.y >= %.5f && p.y <= %.5f)",
                    it.x, it.x + it.w, it.y, it.y + it.h
                )
            }
        ).append(";\n}\n")
        val common = band.toString() + """
float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
"""
        return """//!HOOK MAIN
//!BIND HOOKED
//!SAVE SUBM
//!WIDTH HOOKED.w 2 /
//!HEIGHT HOOKED.h 2 /
//!DESC subtitle erase 1/3: text mask

$common
bool isBright(vec2 q) { return lum(HOOKED_tex(q).rgb) > 0.72; }
bool isDark(vec2 q) { return lum(HOOKED_tex(q).rgb) < 0.24; }

vec4 hook() {
    vec2 p = HOOKED_pos;
    if (!inBand(p)) return vec4(0.0);
    float s = max(HOOKED_size.y / 1080.0, 0.5);
    float l = lum(HOOKED_tex(p).rgb);
    bool bright = l > 0.72;
    bool dark = l < 0.24;
    if (!bright && !dark) return vec4(0.0);
    bool nearBright = false;
    bool nearDark = false;
    for (int i = 0; i < 8; i++) {
        float a = 0.7853982 * float(i);
        vec2 d = vec2(cos(a), sin(a)) * HOOKED_pt;
        vec2 q1 = p + d * (3.0 * s);
        vec2 q2 = p + d * (6.0 * s);
        if (bright) { nearDark = nearDark || isDark(q1) || isDark(q2); }
        if (dark) { nearBright = nearBright || isBright(q1) || isBright(q2); }
    }
    float m = ((bright && nearDark) || (dark && nearBright)) ? 1.0 : 0.0;
    return vec4(m, m, m, 1.0);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND SUBM
//!SAVE SUBD
//!WIDTH HOOKED.w 2 /
//!HEIGHT HOOKED.h 2 /
//!DESC subtitle erase 2/3: dilate

$common
vec4 hook() {
    vec2 p = HOOKED_pos;
    if (!inBand(p)) return vec4(0.0);
    float s = max(HOOKED_size.y / 1080.0, 0.5);
    float m = SUBM_tex(p).r;
    for (int i = 0; i < 8; i++) {
        float a = 0.7853982 * float(i);
        vec2 d = vec2(cos(a), sin(a)) * HOOKED_pt;
        m = max(m, SUBM_tex(p + d * (2.0 * s)).r);
        m = max(m, SUBM_tex(p + d * (4.0 * s)).r);
    }
    return vec4(m, m, m, 1.0);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND SUBD
//!DESC subtitle erase 3/3: fill

$common
vec4 hook() {
    vec2 p = HOOKED_pos;
    vec4 c = HOOKED_tex(p);
    if (!inBand(p)) return c;
    if (SUBD_tex(p).r < 0.5) return c;
    float s = max(HOOKED_size.y / 1080.0, 0.5);
    vec3 acc = vec3(0.0);
    float cnt = 0.0;
    for (int ring = 0; ring < 7; ring++) {
        float r = (5.0 + 5.0 * float(ring)) * s;
        for (int k = 0; k < 12; k++) {
            float a = 0.5235988 * float(k) + 0.45 * float(ring);
            vec2 q = clamp(p + vec2(cos(a), sin(a)) * r * HOOKED_pt, vec2(0.0), vec2(1.0));
            if (SUBD_tex(q).r < 0.5) {
                acc += HOOKED_tex(q).rgb;
                cnt += 1.0;
            }
        }
        if (cnt >= 8.0) break;
    }
    if (cnt < 1.0) return c;
    return vec4(acc / cnt, c.a);
}
"""
    }

    /** glsl-shaders 목록의 단일 진입점. 워터마크 셰이더를 항상 맨 앞에 두고 Anime4K 를 뒤에 붙입니다. */
    private fun applyShaderList() {
        val list = buildList {
            if (watermarkTest && watermarkRequested && !learningWatermark) {
                add(loadablePath(File(watermarkShaderFile.parentFile, "wm_test.glsl")))
            } else if (watermarkShaderMode()) {
                val preview = File(watermarkShaderFile.parentFile, "wm_preview.glsl")
                add(loadablePath(if (watermarkPreview && preview.isFile) preview else watermarkShaderFile))
            }
            if (subtitleEraseMode() && subtitleEraseFile.isFile) {
                add(loadablePath(subtitleEraseFile, subSlot, textured = false))
            }
            // 학습 중에는 워터마크가 그대로 찍힌 프레임이 필요하므로 모든 셰이더를 끈다.
            if (!learningWatermark) addAll(anime4kShaderPaths)
        }
        val result = runCatching {
            if (list.isEmpty()) {
                mpv.command(arrayOf("change-list", "glsl-shaders", "clr", ""))
            } else {
                mpv.command(arrayOf("change-list", "glsl-shaders", "set", list.joinToString(":")))
            }
        }
        if (watermarkShaderMode() || (watermarkTest && watermarkRequested) || subtitleEraseMode()) {
            val readback = runCatching { mpv.getPropertyString("glsl-shaders") }.getOrNull().orEmpty()
            Log.i(
                TAG,
                "WATERMARK_SHADER applied=${result.isSuccess} listed=${readback.contains(watermarkShaderFile.name)} " +
                    "anime4k=${anime4kShaderPaths.size} size=${watermarkShaderFile.length()}"
            )
            if (anime4kShaderPaths.isNotEmpty()) {
                Log.w(TAG, "WATERMARK_SHADER anime4k is active too: its LUMA passes run before MAIN, so removal may be inexact")
            }
            verifyWatermarkShader()
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "SHADER_LIST_FAILED", it) }
    }

    /** 셰이더가 실제 렌더 패스에 들어갔는지, 디코더가 copy 모드인지 mpv에서 직접 읽어 로그로 남깁니다. */
    private fun verifyWatermarkShader() {
        upscalingScope.launch {
            kotlinx.coroutines.delay(1500L)
            fun prop(name: String) = runCatching { mpv.getPropertyString(name) }.getOrNull() ?: "<unreadable>"
            val passes = ArrayList<String>()
            for (i in 0 until 40) {
                val d = runCatching { mpv.getPropertyString("vo-passes/fresh/$i/desc") }.getOrNull() ?: break
                passes.add(d)
            }
            val hwdecCurrent = prop("hwdec-current")
            Log.i(
                TAG,
                "WATERMARK_SHADER_VERIFY found=${passes.any { it.contains("watermark", true) }} passes=${passes.size} " +
                    "hwdec=${prop("hwdec")} hwdec-current=$hwdecCurrent preview=$watermarkPreview test=$watermarkTest " +
                    "vo=${prop("current-vo")} ctx=${prop("current-gpu-context")} glsl-shaders=${prop("glsl-shaders")} list=$passes"
            )
            val now = System.currentTimeMillis()
            if ((watermarkShaderMode() || watermarkTest || subtitleEraseMode()) && !hwdecCurrent.contains("copy") && hwdecCurrent != "no" &&
                now - lastWatermarkReloadAt > 20_000L
            ) {
                lastWatermarkReloadAt = now
                Log.w(TAG, "WATERMARK_SHADER_VERIFY decoder is $hwdecCurrent, reloading video for copy mode")
                runCatching { mpv.command(arrayOf("video-reload")) }
                    .onFailure { Log.e(TAG, "WATERMARK_SHADER_VERIFY video-reload failed", it) }
            }
        }
    }

    /**
     * 진단용 1단계: 텍스처 없이 화면 전체를 파랗게 물들이는 최소 셰이더.
     * 파랗게 보이면 셰이더 경로는 살아 있고, 마스크 셰이더(텍스처 사용)만 파싱/컴파일에서 실패하는 것이다.
     * 파랗게 안 보이면 셰이더 훅 자체가 렌더링에 닿지 않는 것(디코더/VO 문제)이다.
     */
    fun setWatermarkTest(stage: Int): Result<Unit> = runCatching {
        val file = File(watermarkShaderFile.parentFile, "wm_test.glsl")
        if (stage > 0) require(watermarkRequested) { "워터마크 제거가 꺼져 있습니다." }
        if (stage == 1) {
            file.writeText(
                "//!HOOK MAIN\n//!BIND HOOKED\n//!DESC watermark pipeline test (blue tint)\n\n" +
                    "vec4 hook() {\n" +
                    "    vec4 c = HOOKED_tex(HOOKED_pos);\n" +
                    "    c.rgb = mix(c.rgb, vec3(0.0, 0.0, 1.0), 0.5);\n" +
                    "    return c;\n" +
                    "}\n",
                Charsets.US_ASCII
            )
        } else if (stage == 2) {
            // 2x2 텍스처만 쓰는 최소 셰이더: 텍스처 기능 자체가 이 기기/mpv 에서 되는지 본다.
            file.writeText(
                "//!HOOK MAIN\n//!BIND HOOKED\n//!BIND wmt\n//!DESC watermark mini texture test (green tint)\n\n" +
                    "vec4 hook() {\n" +
                    "    vec4 c = HOOKED_tex(HOOKED_pos);\n" +
                    "    vec4 m = texture(wmt, HOOKED_pos);\n" +
                    "    c.rgb = mix(c.rgb, vec3(0.0, 1.0, 0.0), 0.5 * m.a);\n" +
                    "    return c;\n" +
                    "}\n\n" +
                    "//!TEXTURE wmt\n//!SIZE 2 2\n//!FORMAT rgba8\n//!FILTER LINEAR\n//!BORDER CLAMP\n" +
                    "ffffffffffffffffffffffffffffffff\n",
                Charsets.US_ASCII
            )
        } else if (stage == 3) {
            // 실제 마스크 파일(텍스처 포함)을 쓰되, 마스크 영역 사각형 전체를 초록으로 칠한다.
            require(hasWatermarkShader()) { "마스크가 없습니다." }
            WatermarkMaskLearner.writePreviewShader(
                watermarkShaderFile, file,
                "c.rgb = mix(c.rgb, vec3(0.0, 1.0, 0.0), 0.4 + 0.6 * m.a);"
            )
            val head = watermarkShaderFile.inputStream().use { ins ->
                val buf = ByteArray(900)
                val n = ins.read(buf).coerceAtLeast(0)
                buf.copyOf(n)
            }
            val text = String(head, Charsets.ISO_8859_1)
            val idx = text.indexOf("//!TEXTURE wm")
            val size = Regex("//!SIZE (\\d+) (\\d+)").find(text)
            val expectedHex = size?.let { it.groupValues[1].toLong() * it.groupValues[2].toLong() * 8 }
            Log.i(TAG, "WATERMARK_FILE length=${watermarkShaderFile.length()} textureHeaderAt=$idx expectedHexChars=$expectedHex " +
                "headerLen=$idx head=" + text.take(if (idx > 0) idx + 90 else 600).replace("\n", "\\n"))
        }
        wmSlot.key = ""
        watermarkTest = stage > 0
        applyHwdecPolicy()
        applyShaderList()
    }

    /** 학습된 마스크를 지우는 대신 빨간색으로 칠해 보여 줍니다(진단용). */
    fun setWatermarkPreview(on: Boolean): Result<Unit> = runCatching {
        if (on) {
            require(hasWatermarkShader()) { "마스크가 없습니다." }
            require(watermarkRequested) { "워터마크 제거가 꺼져 있습니다." }
            WatermarkMaskLearner.writePreviewShader(
                watermarkShaderFile,
                File(watermarkShaderFile.parentFile, "wm_preview.glsl")
            )
        }
        wmSlot.key = ""
        watermarkPreview = on
        applyShaderList()
    }

    /** 선택한 .glsl 마스크를 앱 저장소로 가져옵니다. 형식이 맞지 않으면 실패를 돌려줍니다. */
    fun importWatermarkShader(input: java.io.InputStream): Result<Unit> = runCatching {
        val tmp = File(watermarkShaderFile.parentFile, "wm_remove.glsl.tmp")
        var total = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                require(total <= 64L * 1024 * 1024) { "파일이 너무 큽니다." }
                out.write(buf, 0, n)
            }
        }
        val headBytes = ByteArray(4096)
        val headLen = tmp.inputStream().use { it.read(headBytes).coerceAtLeast(0) }
        val head = String(headBytes, 0, headLen, Charsets.ISO_8859_1)
        require(head.contains("//!HOOK") && head.contains("//!TEXTURE wm")) {
            "estimate_wm.py 로 만든 마스크 파일이 아닙니다."
        }
        require(tmp.renameTo(watermarkShaderFile) || (watermarkShaderFile.delete() && tmp.renameTo(watermarkShaderFile))) {
            "파일을 저장하지 못했습니다."
        }
        appliedShaderKey = ""
    }.onFailure { File(watermarkShaderFile.parentFile, "wm_remove.glsl.tmp").delete() }

    // ---- 앱 안에서 마스크 자동 학습 ----
    @Volatile private var learningWatermark = false

    class WatermarkLearnReport(val frames: Int, val darkFrames: Int, val maxAlpha: Float, val expectedError: Float)

    private suspend fun waitForSeekSettled() {
        kotlinx.coroutines.delay(250L)
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val seeking = runCatching { mpv.getPropertyBoolean("seeking") }.getOrNull() == true
            val buffering = runCatching { mpv.getPropertyBoolean("paused-for-cache") }.getOrNull() == true
            if (!seeking && !buffering) break
            kotlinx.coroutines.delay(150L)
        }
        kotlinx.coroutines.delay(300L) // 새 프레임이 화면에 그려질 시간
    }

    /**
     * 영상을 여러 장면으로 건너뛰며 프레임을 캡처해 워터마크 투명도 맵을 학습하고,
     * 역합성 셰이더로 저장합니다. 끝나면(취소/실패 포함) 재생 위치와 상태를 되돌립니다.
     * 학습 중에는 워터마크/업스케일 셰이더를 꺼서 원본 그대로의 프레임을 얻습니다.
     */
    suspend fun learnWatermarkMask(
        maxCaptures: Int = 72,
        onProgress: (frames: Int, darkFrames: Int) -> Unit
    ): Result<WatermarkLearnReport> {
        val durMs = duration
        if (durMs < 120_000L) {
            return Result.failure(IllegalStateException("영상 길이를 알 수 없거나 너무 짧아 학습할 수 없습니다."))
        }
        val startPos = currentPosition
        val wasPlaying = isPlaying
        val learner = WatermarkMaskLearner()
        watermarkPreview = false
        watermarkTest = false
        var captures = 0
        val darkness = ArrayList<Pair<Long, Float>>() // 캡처 위치(ms) -> 가장자리 밝기

        // 한 곳을 캡처한다. 정확도가 충분해졌으면 true (그만 찍어도 된다는 뜻).
        suspend fun sampleAt(posMs: Long): Boolean {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val sec = posMs.coerceAtLeast(0L) / 1000.0
            runCatching { mpv.command(arrayOf("seek", sec.toString(), "absolute+keyframes")) }
            waitForSeekSettled()
            val bmp = runCatching { captureVideoBitmap() }.getOrNull()
            captures++
            if (bmp != null) {
                learner.addFrame(bmp)
                bmp.recycle()
                darkness.add(posMs to learner.lastRingLuma)
            }
            onProgress(learner.acceptedFrames, learner.darkFrames)
            return learner.acceptedFrames >= 16 && captures % 4 == 0 && learner.quickExpectedError() <= 0.012f
        }

        return try {
            learningWatermark = true
            pause()
            removeWatermarkFilter()
            appliedWatermarkKey = ""
            applyShaderList()

            var enough = false
            // 1단계: 에피소드 맨 앞(검은 화면/페이드 인이 흔함) + 전체를 고르게 훑기.
            // 황금비 수열이라 어디서 멈춰도 구간이 고르게 퍼져 있다.
            for (p in longArrayOf(400L, 1200L, 2600L)) {
                if (enough || captures >= maxCaptures) break
                enough = sampleAt(p)
            }
            val coarse = minOf(maxCaptures, 44)
            var i = 0
            while (!enough && captures < coarse) {
                val frac = 0.03 + 0.94 * (((i + 1) * 0.6180339887498949) % 1.0)
                enough = sampleAt((durMs * frac).toLong())
                i++
            }
            // 2단계: 아직 부족하면, 가장 어두웠던 장면 주변을 더 찍는다. 어두운 장면은 몇 초씩
            // 이어지고, 샷마다 어두운 위치가 달라서 바닥값을 더 낮춰 준다.
            if (!enough) {
                val lo = (durMs * 0.03).toLong()
                val hi = (durMs * 0.97).toLong()
                val darkest = darkness.filter { it.second < 70f }.sortedBy { it.second }.take(6)
                loop@ for ((pos, _) in darkest) {
                    for (off in longArrayOf(-4000L, -1500L, 1500L, 4000L)) {
                        if (captures >= maxCaptures) break@loop
                        enough = sampleAt((pos + off).coerceIn(lo, hi))
                        if (enough) break@loop
                    }
                }
            }

            val result = learner.finish()
                ?: return Result.failure(IllegalStateException(learner.failureReason ?: "워터마크를 찾지 못했습니다."))
            val tmp = File(watermarkShaderFile.parentFile, "wm_remove.glsl.tmp")
            WatermarkMaskLearner.writeShader(result, tmp)
            if (!tmp.renameTo(watermarkShaderFile)) {
                watermarkShaderFile.delete()
                check(tmp.renameTo(watermarkShaderFile)) { "마스크 파일을 저장하지 못했습니다." }
            }
            Log.i(
                TAG,
                "WATERMARK_LEARN captures=$captures frames=${result.frames} dark=${learner.darkFrames} " +
                    "box=${result.x0},${result.y0},${result.x1},${result.y1} maxAlpha=${result.maxAlpha} " +
                    "expectedError=${result.expectedError} frame=${result.frameWidth}x${result.frameHeight} " +
                    "file=${watermarkShaderFile.length()} darkestRing=${darkness.minOfOrNull { it.second }}"
            )
            Result.success(WatermarkLearnReport(result.frames, learner.darkFrames, result.maxAlpha, result.expectedError))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "WATERMARK_LEARN_FAILED", t)
            Result.failure(t)
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                learningWatermark = false
                appliedShaderKey = ""
                runCatching { seekTo(startPos) }
                kotlinx.coroutines.delay(200L)
                applyHwdecPolicy()
                applyShaderList()
                applyWatermarkFilter()
                if (wasPlaying) play()
            }
        }
    }

    fun deleteWatermarkShader() {
        watermarkPreview = false
        watermarkTest = false
        watermarkShaderFile.delete()
        appliedShaderKey = ""
        applyHwdecPolicy()
        applyShaderList()
        applyWatermarkFilter()
    }

    /**
     * delogo is a CPU filter, so it needs frames in system memory. Anime4K needs the
     * same copy-back mode, so keep it whenever either feature is active.
     */
    private fun applyHwdecPolicy() {
        val needsCopy = watermarkActive() || watermarkShaderMode() || subtitleEraseMode() || (watermarkTest && watermarkRequested) ||
            (aiUpscalingRequested && aiUpscalingBackendPreference == "anime4k")
        runCatching { mpv.setPropertyString("hwdec", if (needsCopy) "mediacodec-copy" else "mediacodec") }
    }

    fun configureWatermarkRemoval(enabled: Boolean, regions: String) {
        watermarkRequested = enabled
        watermarkRects = WatermarkRegions.parse(regions)
        appliedWatermarkKey = ""
        applyHwdecPolicy()
        // 같은 마스크로 이미 적용돼 있으면 큰 셰이더를 다시 읽지 않습니다.
        val shaderKey = "${watermarkShaderMode()}:${watermarkShaderFile.lastModified()}:${watermarkShaderFile.length()}:${anime4kShaderPaths.size}"
        if (shaderKey != appliedShaderKey) {
            appliedShaderKey = shaderKey
            applyShaderList()
        }
        applyWatermarkFilter()
    }

    private var watermarkFilterInstalled = false

    private fun removeWatermarkFilter() {
        if (!watermarkFilterInstalled) return
        watermarkFilterInstalled = false
        runCatching { mpv.command(arrayOf("vf", "remove", "@$watermarkFilterLabel")) }
    }

    private fun applyWatermarkFilter() {
        if (videoWidth <= 0) {
            runCatching { mpv.getPropertyDouble("video-params/w") }.getOrNull()
                ?.takeIf { it.isFinite() && it > 0 }?.let { videoWidth = it.toInt() }
        }
        if (videoHeight <= 0) {
            runCatching { mpv.getPropertyDouble("video-params/h") }.getOrNull()
                ?.takeIf { it.isFinite() && it > 0 }?.let { videoHeight = it.toInt() }
        }
        val width = videoWidth
        val height = videoHeight
        if (!watermarkActive()) {
            if (appliedWatermarkKey != "off") {
                removeWatermarkFilter()
                appliedWatermarkKey = "off"
            }
            return
        }
        // Regions are normalized, so wait until mpv reports the real frame size.
        if (width <= 2 || height <= 2) return

        val filters = watermarkRects.mapNotNull { r ->
            // delogo requires the area to stay at least 1px inside the frame.
            val x = (r.x * width).roundToInt().coerceIn(1, width - 2)
            val y = (r.y * height).roundToInt().coerceIn(1, height - 2)
            val w = (r.w * width).roundToInt().coerceAtMost(width - 1 - x)
            val h = (r.h * height).roundToInt().coerceAtMost(height - 1 - y)
            if (w < 2 || h < 2) null
            else if (WATERMARK_DEBUG_BOX) "drawbox=x=$x:y=$y:w=$w:h=$h:color=red@0.5:t=fill"
            else "delogo=x=$x:y=$y:w=$w:h=$h"
        }
        if (filters.isEmpty()) return

        val graph = filters.joinToString(",")
        val key = "${width}x$height:$graph"
        if (key == appliedWatermarkKey) return
        removeWatermarkFilter()
        val result = runCatching {
            mpv.command(arrayOf("vf", "add", "@$watermarkFilterLabel:lavfi=[$graph]"))
        }
        if (result.isSuccess) {
            watermarkFilterInstalled = true
            appliedWatermarkKey = key
            Log.i(TAG, "WATERMARK_FILTER applied source=${width}x$height filter=$graph")
            verifyWatermarkFilter()
        } else {
            Log.e(TAG, "WATERMARK_FILTER failed source=${width}x$height filter=$graph", result.exceptionOrNull())
        }
    }

    /** vf add 가 예외 없이 끝나도 실제로 체인에 들어갔는지는 별개라서, mpv에서 직접 읽어 확인합니다. */
    private fun verifyWatermarkFilter() {
        upscalingScope.launch {
            kotlinx.coroutines.delay(600L)
            fun prop(name: String) = runCatching { mpv.getPropertyString(name) }.getOrNull() ?: "<unreadable>"
            val vf = prop("vf")
            val hwdec = prop("hwdec")
            val hwdecCurrent = prop("hwdec-current")
            Log.i(
                TAG,
                "WATERMARK_VERIFY vf=$vf hwdec=$hwdec hwdec-current=$hwdecCurrent " +
                    "pixfmt=${prop("video-params/pixelformat")} outPixfmt=${prop("video-out-params/pixelformat")}"
            )
            if (!vf.contains(watermarkFilterLabel)) {
                Log.e(TAG, "WATERMARK_VERIFY filter NOT present in vf chain")
            }
            // 런타임 hwdec 변경이 디코더에 반영되지 않았다면 copy-back 모드로 비디오 트랙을 다시 엽니다.
            if (watermarkActive() && !hwdecCurrent.contains("copy") && hwdecCurrent != "no") {
                Log.w(TAG, "WATERMARK_VERIFY decoder still zero-copy ($hwdecCurrent); reloading video")
                runCatching { mpv.command(arrayOf("video-reload")) }
                    .onFailure { Log.e(TAG, "WATERMARK_VERIFY video-reload failed", it) }
            }
        }
    }

    private var surfaceViewRef: java.lang.ref.WeakReference<TextureView>? = null

    fun registerSurfaceView(view: TextureView) {
        surfaceViewRef = java.lang.ref.WeakReference(view)
    }

    /**
     * Captures the current video picture straight from the player's TextureView.
     * mpv's own screenshot command cannot be used here: the bundled libavcodec has
     * no image encoder. Subtitles are hidden while capturing and letterbox bars are
     * cropped away, so the result maps 1:1 onto the video frame.
     */
    suspend fun captureVideoBitmap(): android.graphics.Bitmap {
        val view = surfaceViewRef?.get()
            ?: throw IllegalStateException("재생 화면을 찾지 못했습니다.")
        val subtitlesWereVisible = runCatching { mpv.getPropertyBoolean("sub-visibility") }.getOrNull() != false
        runCatching { mpv.setPropertyBoolean("sub-visibility", false) }
        try {
            kotlinx.coroutines.delay(350L)
            return kotlinx.coroutines.withContext(Dispatchers.Main) {
                val full = view.bitmap
                    ?: throw IllegalStateException("영상 프레임을 가져오지 못했습니다.")
                val vw = full.width
                val vh = full.height
                if (vw <= 0 || vh <= 0) throw IllegalStateException("영상 프레임이 비어 있습니다.")
                val aspect = when {
                    videoDisplayAspect > 0.01 -> videoDisplayAspect
                    videoWidth > 0 && videoHeight > 0 -> videoWidth.toDouble() / videoHeight
                    else -> vw.toDouble() / vh
                }
                val viewAspect = vw.toDouble() / vh
                val cw: Int
                val ch: Int
                if (viewAspect > aspect) {
                    ch = vh
                    cw = (vh * aspect).roundToInt().coerceIn(1, vw)
                } else {
                    cw = vw
                    ch = (vw / aspect).roundToInt().coerceIn(1, vh)
                }
                android.graphics.Bitmap.createBitmap(full, (vw - cw) / 2, (vh - ch) / 2, cw, ch)
            }
        } finally {
            if (subtitlesWereVisible) runCatching { mpv.setPropertyBoolean("sub-visibility", true) }
        }
    }

    /**
     * Drops the filter and forces mpv to redraw the current frame, so a screenshot
     * taken right afterwards shows the original picture for region selection.
     */
    fun prepareCleanFrameCapture() {
        removeWatermarkFilter()
        appliedWatermarkKey = ""
        runCatching { mpv.command(arrayOf("seek", "0", "relative+exact")) }
    }

    private fun observe(name: String, format: Int) {
        runCatching { mpv.observeProperty(name, format) }
            .onFailure { Log.w(TAG, "observeProperty failed: $name", it) }
    }

    private fun updateProperty(property: String) {
        if (property == "sub-text") return
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
                "sub-text" -> {
                    val value = mpv.getPropertyString("sub-text") ?: ""
                    subtitleText = value
                    _subtitleEvents.tryEmit(value)
                }
            }
        }.onFailure {
            // A property can disappear while mpv is replacing the media.
            // This is expected during teardown, so never propagate it.
            Log.d(TAG, "MPV_PROPERTY_UNAVAILABLE property=$property")
        }
    }

    fun attachSurface(surface: Surface) {
        mpv.attachSurface(surface)
        mpv.setOptionString("keepaspect", "yes")
        mpv.setOptionString("video-zoom", "0")
        mpv.setOptionString("panscan", "0")
        mpv.setOptionString("sub-use-margins", "no")
        mpv.setOptionString("force-window", "yes")
    }

    /**
     * Rebinds the currently displayed Surface after the Android TextureView
     * changes size (fold/unfold, rotation, PiP resize, etc.). libmpv keeps the
     * same media/time position; only the rendering target is refreshed.
     */
    fun refreshSurface(surface: Surface) {
        runCatching {
            mpv.attachSurface(surface)
            mpv.setOptionString("keepaspect", "yes")
            mpv.setOptionString("video-zoom", "0")
            mpv.setOptionString("panscan", "0")
            mpv.setOptionString("force-window", "yes")
        }.onFailure {
            Log.w(TAG, "Failed to refresh mpv surface", it)
        }
    }

    fun detachSurface() {
        runCatching {
            mpv.setOptionString("keepaspect", "yes")
            mpv.setOptionString("video-zoom", "0")
            mpv.setOptionString("panscan", "0")
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
        // Parse both the current newline-delimited format and older JSON header maps.
        // libmpv's http-header-fields syntax is `Name=value,...`, not HTTP's
        // `Name: value` wire syntax. Passing colon-delimited fields can silently
        // drop Referer/Origin on protected HLS playlists and their segments.
        val rawLines = if (headers.trimStart().startsWith("{")) {
            runCatching {
                val json = org.json.JSONObject(headers)
                val keys = json.keys()
                buildList {
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = json.optString(key)
                        if (value.isNotBlank()) add("$key: $value")
                    }
                }
            }.getOrDefault(headers.lineSequence().toList())
        } else headers.lineSequence().toList()
        val parsed = rawLines.mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }.filter { (name, value) -> name.isNotBlank() && value.isNotBlank() }
        val userAgent = parsed.firstOrNull { it.first.equals("User-Agent", true) }?.second
        val headerReferer = parsed.firstOrNull { it.first.equals("Referer", true) }?.second
        val effectiveReferer = referer?.takeIf { it.isNotBlank() } ?: headerReferer.orEmpty()
        // mpv has dedicated options for User-Agent and Referer. Keep them out of
        // http-header-fields to avoid duplicate/conflicting values.
        val normalized = parsed
            .filterNot { (name, _) -> name.equals("User-Agent", true) || name.equals("Referer", true) }
            .joinToString(",") { (name, value) -> "$name=$value" }
        val fieldsSet = runCatching { mpv.setPropertyString("http-header-fields", normalized) }.isSuccess
        if (!fieldsSet) runCatching { mpv.setOptionString("http-header-fields", normalized) }
        val uaSet = runCatching { mpv.setPropertyString("user-agent", userAgent.orEmpty()) }.isSuccess
        if (!uaSet) runCatching { mpv.setOptionString("user-agent", userAgent.orEmpty()) }
        val refererSet = runCatching { mpv.setPropertyString("referrer", effectiveReferer) }.isSuccess
        if (!refererSet) runCatching { mpv.setOptionString("referrer", effectiveReferer) }
        Log.i(TAG, "HTTP_CONTEXT_CONFIGURED headerCount=${parsed.size} extraHeaderCount=${normalized.split(',').count { it.isNotBlank() }} userAgent=${!userAgent.isNullOrBlank()} referer=${effectiveReferer.isNotBlank()} fieldsProperty=$fieldsSet uaProperty=$uaSet refererProperty=$refererSet")
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

    /** Current external subtitle path used by the libass translation overlay. */
    val currentSubtitleFilePath: String?
        get() = currentSubtitlePath?.removePrefix("file://")?.let { File(it).absolutePath }

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

    fun getCurrentSubtitleText(): String = runCatching {
        mpv.getPropertyString("sub-text").orEmpty()
    }.getOrDefault(subtitleText)

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

    /** Re-arm the shared libmpv instance when a new PlayerScreen is entered.
     *  The engine is process-wide, so leaving a screen must not leave force-window/
     *  pause/end-file state from the previous screen behind.
     */
    fun prepareForNewPlayer() {
        pendingSubtitlePath = null
        currentSubtitlePath = null
        currentSubtitleIsAss = false
        playWhenLoaded = false
        suppressEndFileUntilStartFile = false
        runCatching { mpv.setPropertyBoolean("pause", true) }
        runCatching { mpv.setOptionString("force-window", "yes") }
        runCatching { mpv.setOptionString("keepaspect", "yes") }
        playbackState = STATE_IDLE
        isPlaying = false
        currentPosition = 0L
        duration = 0L
        videoWidth = 0
        videoHeight = 0
        videoDisplayWidth = 0
        videoDisplayHeight = 0
        videoDisplayAspect = 0.0
        subtitleText = ""
    }

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

    /** Save the currently decoded video frame without subtitle or player overlays. */
    fun screenshotToFile(outputPath: String) {
        require(outputPath.isNotBlank()) { "스크린샷 경로가 비어 있습니다." }
        File(outputPath).parentFile?.mkdirs()
        mpv.command(arrayOf("screenshot-to-file", outputPath, "video"))
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
        // SRT has no ASS-style font/size/color metadata, so mpv/libass uses the
        // generic subtitle settings below for SRT. This is intentional: it makes
        // SRT follow the player's font, size, color, bold and outline controls.
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
        videoWidth = 0
        videoHeight = 0
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

        // Keep subtitles inside the actual video frame. When this is enabled,
        // mpv may place subtitle pixels into letterbox/pillarbox margins.
        mpv.setOptionString("sub-use-margins", "no")
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
    private var currentSurface: Surface? = null
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
        engine.registerSurfaceView(this)
        // TextureView can keep the same SurfaceTexture while its buffer size
        // changes during fold/unfold and PiP transitions. Keep the buffer in
        // lockstep with the actual view size before handing it to libmpv.
        surface.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        currentSurface?.release()
        currentSurface = Surface(surface)
        engine.attachSurface(currentSurface!!)
        attached = true
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        surface.setDefaultBufferSize(safeWidth, safeHeight)

        // Do not reload/seek the media. Reattaching the same Surface causes
        // libmpv to rebuild its video output for the new Android viewport.
        currentSurface?.let { engine.refreshSurface(it) }

        // Reset any TextureView transform left behind by a previous window
        // size. mpv itself owns aspect-ratio correction (keepaspect=yes).
        setTransform(android.graphics.Matrix())
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (attached) engine.detachSurface()
        attached = false
        currentSurface?.release()
        currentSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
