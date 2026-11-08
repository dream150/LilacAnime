package com.lilac.anime.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 앰비언트 모드(YouTube 스타일) 조명 효과.
 *
 * - mpv 가 그리는 [TextureView] 에서 아주 작은 해상도로 프레임을 주기적으로 샘플링한다.
 * - 영상 영역 안쪽 픽셀만 잘라 흐리게 만든 뒤, 영상 바깥(레터박스/필러박스)에만 그린다.
 *   색은 화면 프레임마다 목표 색을 향해 부드럽게 따라간다(시정수 [COLOR_TAU_MS]).
 * - 영상 가장자리 색을 바깥으로 그대로 이어서 번지게 하므로 영상과 조명 사이에 검은 띠가 생기지 않는다.
 *   영상 자체와 자막, 터치 입력에는 영향을 주지 않는다.
 * - 여백이 없으면(화면 비율 == 영상 비율) 샘플링을 하지 않아 비용이 0 이다.
 * - 영상 파일 자체에 구워진 검은 띠는 [setCropDetection] 으로 검출해 엔진이 잘라 내게 하면, 그 자리도 같은 방식으로 채워진다.
 */
class AmbientGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val TAG = "AmbientGlowView"
        // 재생 중 프레임 샘플링 주기. 작을수록 장면 전환에 빨리 반응하지만 TextureView 읽기 비용이 는다.
        private const val SAMPLE_INTERVAL_MS = 100L
        // 일시정지 중에는 화면이 바뀌지 않으므로 느리게 확인한다(시크 직후 갱신용).
        private const val PAUSED_SAMPLE_INTERVAL_MS = 300L
        private const val SAMPLE_LONG_EDGE = 96
        private const val GLOW_LONG_EDGE = 48
        // 표시 색이 목표 색을 따라가는 속도(시정수, ms). 작을수록 빨리 바뀐다.
        private const val COLOR_TAU_MS = 110f
        // 여백에 깔리는 조명의 밝기(0..255). 너무 밝으면 영상보다 눈에 띈다.
        private const val GLOW_ALPHA = 215
        // 영상 영역 반올림 오차로 생기는 1px 틈이 검은 줄로 보이지 않게, 조명을 영상 쪽으로 이만큼 겹친다.
        private const val OVERLAP_PX = 1
        // 영상에 구워진 검은 띠 검출용 샘플링 주기(재생 중에만).
        private const val DETECT_INTERVAL_MS = 300L
    }

    private var textureSource: TextureView? = null
    private var videoAspect = 0.0
    private var ambientEnabled = false
    private var playing = false

    private var sampleBitmap: Bitmap? = null
    private var glowBitmap: Bitmap? = null
    private var glowCanvas: Canvas? = null
    private var blurredBitmap: Bitmap? = null
    private var pixelBuffer = IntArray(0)
    private var pixelScratch = IntArray(0)
    private var hasGlow = false

    // 블러된 목표 색(targetRgb)과 화면에 보이는 색(currentRgb). RGB 순서로 픽셀당 3칸.
    private var targetRgb = FloatArray(0)
    private var currentRgb = FloatArray(0)
    private var displayBuffer = IntArray(0)
    private var animating = false
    private var snapToTarget = true
    private var lastFrameAt = 0L

    private val videoRect = Rect()
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val coverRect = RectF()
    private val excludeRect = Rect()
    private val barRect = Rect()
    private val edgeSrc = Rect()

    private val blendPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // 채도를 올리고 약간 어둡게: 영상보다 튀지 않으면서 색감은 살아 있도록.
    private val saturationFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.45f) })
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = saturationFilter
        alpha = GLOW_ALPHA
    }
    // 영상 가장자리에서 바깥으로 번지는 색. 영상 바로 옆에서는 불투명하게 시작해야 이음매가 안 보인다.
    private val edgePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = saturationFilter
    }
    private val fadePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    // ---- 영상 파일에 구워진 검은 띠 검출 ----
    private val letterboxDetector = LetterboxDetector()
    private var detecting = false
    private var detectScheduled = false
    private var detectKey: Any? = null

    /** 검은 띠 검출이 끝났을 때(띠가 없으면 isEmpty 인 값) 한 번 호출된다. 메인 스레드. */
    var onLetterboxDetected: ((LetterboxDetector.Bars) -> Unit)? = null

    private val detectRunnable = Runnable {
        detectScheduled = false
        if (!detecting) return@Runnable
        val tv = textureSource
        if (tv != null && playing && isAttachedToWindow && width > 0 && height > 0 && videoAspect > 0.01) {
            computeVideoRect()
            val bars = letterboxDetector.feed(tv, videoRect)
            if (bars != null) {
                detecting = false
                onLetterboxDetected?.invoke(bars)
                return@Runnable
            }
        }
        scheduleDetect()
    }

    private var lastSampleAt = 0L
    private var sampleFailures = 0
    private var scheduled = false

    private val sampleRunnable = Runnable {
        scheduled = false
        sampleFrame()
        scheduleSample()
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 샘플링 원본(mpv 가 그려지는 TextureView). */
    fun setVideoSource(view: TextureView?) {
        if (textureSource === view) return
        textureSource = view
        resetGlow()
        updateScheduling()
    }

    fun setVideoAspectRatio(aspectRatio: Double) {
        val normalized = aspectRatio.takeIf { it.isFinite() && it > 0.01 } ?: return
        if (abs(videoAspect - normalized) < 0.001) return
        videoAspect = normalized
        resetGlow()
        updateScheduling()
        invalidate()
    }

    fun setAmbientEnabled(enabled: Boolean) {
        if (ambientEnabled == enabled) return
        ambientEnabled = enabled
        if (!enabled) resetGlow()
        updateScheduling()
        invalidate()
    }

    fun setPlaying(isPlaying: Boolean) {
        if (playing == isPlaying) return
        playing = isPlaying
        updateScheduling()
    }

    /**
     * 영상 파일에 구워진 검은 띠 검출을 켜고 끈다. [sessionKey] 가 바뀔 때마다(새 영상) 검출을 처음부터 다시 한다.
     * 같은 키로 다시 호출해도 아무 일도 하지 않는다. 크롭이 적용되기 전의 영상에서만 검출해야 한다.
     */
    fun setCropDetection(enabled: Boolean, sessionKey: Any?) {
        if (!enabled) {
            detecting = false
            detectKey = null
            removeCallbacks(detectRunnable)
            detectScheduled = false
            return
        }
        if (sessionKey == detectKey) return
        detectKey = sessionKey
        letterboxDetector.reset()
        detecting = true
        scheduleDetect()
    }

    private fun scheduleDetect() {
        if (!detecting || detectScheduled) return
        detectScheduled = true
        postDelayed(detectRunnable, DETECT_INTERVAL_MS)
    }

    private fun resetGlow() {
        hasGlow = false
        animating = false
        snapToTarget = true
        glowBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    private fun hasBars(): Boolean {
        computeVideoRect()
        return videoRect.width() < width - 1 || videoRect.height() < height - 1
    }

    private fun computeVideoRect() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            videoRect.set(0, 0, 0, 0)
            return
        }
        val aspect = videoAspect
        if (!aspect.isFinite() || aspect <= 0.01) {
            videoRect.set(0, 0, w, h)
            return
        }
        // LibassTranslationOverlay.updateVideoViewport 와 같은 방식(keepaspect, zoom 0).
        val viewAspect = w.toDouble() / h.toDouble()
        if (viewAspect > aspect) {
            val vw = (h * aspect).toInt().coerceIn(1, w)
            val left = (w - vw) / 2
            videoRect.set(left, 0, left + vw, h)
        } else {
            val vh = (w / aspect).toInt().coerceIn(1, h)
            val top = (h - vh) / 2
            videoRect.set(0, top, w, top + vh)
        }
    }

    private fun shouldRun(): Boolean =
        ambientEnabled && isAttachedToWindow && textureSource != null && width > 0 && height > 0 && hasBars()

    private fun updateScheduling() {
        if (shouldRun()) scheduleSample() else {
            removeCallbacks(sampleRunnable)
            scheduled = false
        }
    }

    private fun scheduleSample() {
        if (scheduled || !shouldRun()) return
        scheduled = true
        postDelayed(sampleRunnable, if (playing) SAMPLE_INTERVAL_MS else PAUSED_SAMPLE_INTERVAL_MS)
    }

    private fun sampleFrame() {
        val tv = textureSource ?: return
        if (!ambientEnabled || !tv.isAvailable || tv.width <= 0 || tv.height <= 0) return
        if (!hasBars()) return
        // 너무 자주 호출되는 경우를 막는다.
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleAt < SAMPLE_INTERVAL_MS / 2) return
        lastSampleAt = now

        val viewW = width
        val viewH = height
        val scale = SAMPLE_LONG_EDGE.toFloat() / max(viewW, viewH)
        val sw = max(2, (viewW * scale).roundToInt())
        val sh = max(2, (viewH * scale).roundToInt())

        var sample = sampleBitmap
        if (sample == null || sample.isRecycled || sample.width != sw || sample.height != sh) {
            sample?.recycle()
            sample = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            sampleBitmap = sample
        }

        val captured = runCatching { tv.getBitmap(sample) }.getOrNull()
        if (captured == null) {
            if (++sampleFailures == 3) Log.w(TAG, "SAMPLE_FAILED repeatedly")
            return
        }
        sampleFailures = 0

        // 샘플 비트맵(뷰 전체) 안에서 영상 영역만 잘라낸다.
        srcRect.set(
            (videoRect.left * scale).roundToInt().coerceIn(0, sw - 1),
            (videoRect.top * scale).roundToInt().coerceIn(0, sh - 1),
            (videoRect.right * scale).roundToInt().coerceIn(1, sw),
            (videoRect.bottom * scale).roundToInt().coerceIn(1, sh)
        )
        if (srcRect.width() < 2 || srcRect.height() < 2) return

        val aspect = srcRect.width().toFloat() / srcRect.height().toFloat()
        val gw: Int
        val gh: Int
        if (aspect >= 1f) {
            gw = GLOW_LONG_EDGE
            gh = max(2, (GLOW_LONG_EDGE / aspect).roundToInt())
        } else {
            gh = GLOW_LONG_EDGE
            gw = max(2, (GLOW_LONG_EDGE * aspect).roundToInt())
        }
        var glow = glowBitmap
        if (glow == null || glow.isRecycled || glow.width != gw || glow.height != gh) {
            glow?.recycle()
            glow = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
            glowBitmap = glow
            glowCanvas = Canvas(glow)
            hasGlow = false
        }
        val canvas = glowCanvas ?: return
        dstRect.set(0, 0, gw, gh)
        // 시간 평활은 그리는 쪽(stepAnimation)에서 하므로 여기서는 새 프레임을 그대로 덮어쓴다.
        val first = !hasGlow
        canvas.drawBitmap(captured, srcRect, dstRect, blendPaint)
        hasGlow = true
        updateBlurred(glow, first)
        invalidate()
    }

    /**
     * 48px 안팎의 작은 비트맵에 박스 블러를 여러 번 적용해 "목표 색"으로 저장한다. 이 크기에서는 비용이
     * 거의 없고, View 단위 블러(RenderEffect)와 달리 영상 가장자리로 번지지 않는다.
     * 화면에 보이는 색은 [stepAnimation] 이 프레임마다 이 목표를 향해 움직인다.
     */
    private fun updateBlurred(source: Bitmap, first: Boolean) {
        val w = source.width
        val h = source.height
        var out = blurredBitmap
        if (out == null || out.isRecycled || out.width != w || out.height != h) {
            out?.recycle()
            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            blurredBitmap = out
            snapToTarget = true
        }
        val n = w * h
        if (pixelBuffer.size < n) {
            pixelBuffer = IntArray(n)
            pixelScratch = IntArray(n)
        }
        source.getPixels(pixelBuffer, 0, w, 0, 0, w, h)
        repeat(3) {
            boxBlurPass(pixelBuffer, pixelScratch, w, h, 3, horizontal = true)
            boxBlurPass(pixelScratch, pixelBuffer, w, h, 3, horizontal = false)
        }

        if (targetRgb.size != n * 3) {
            targetRgb = FloatArray(n * 3)
            currentRgb = FloatArray(n * 3)
            snapToTarget = true
        }
        var o = 0
        for (i in 0 until n) {
            val c = pixelBuffer[i]
            targetRgb[o++] = ((c shr 16) and 0xFF).toFloat()
            targetRgb[o++] = ((c shr 8) and 0xFF).toFloat()
            targetRgb[o++] = (c and 0xFF).toFloat()
        }

        if (first || snapToTarget) {
            // 첫 프레임이나 크기가 바뀐 직후에는 보간 없이 바로 맞춘다.
            System.arraycopy(targetRgb, 0, currentRgb, 0, n * 3)
            snapToTarget = false
            animating = false
            renderCurrent(out, w, h)
        } else {
            if (!animating) lastFrameAt = SystemClock.uptimeMillis()
            animating = true
        }
    }

    /** 화면에 보이는 색을 목표 색 쪽으로 한 걸음 옮긴다. 아직 더 움직여야 하면 true. */
    private fun stepAnimation(): Boolean {
        val bmp = blurredBitmap ?: return false
        if (bmp.isRecycled) return false
        val w = bmp.width
        val h = bmp.height
        val n3 = w * h * 3
        if (currentRgb.size != n3 || targetRgb.size != n3) return false

        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrameAt).coerceIn(1L, 64L)
        lastFrameAt = now
        val k = 1f - exp(-dt.toFloat() / COLOR_TAU_MS)

        var maxDiff = 0f
        for (i in 0 until n3) {
            val d = targetRgb[i] - currentRgb[i]
            currentRgb[i] += d * k
            val ad = abs(d)
            if (ad > maxDiff) maxDiff = ad
        }
        renderCurrent(bmp, w, h)
        return maxDiff > 0.6f
    }

    private fun renderCurrent(out: Bitmap, w: Int, h: Int) {
        val n = w * h
        if (displayBuffer.size < n) displayBuffer = IntArray(n)
        var o = 0
        for (i in 0 until n) {
            val r = currentRgb[o++].roundToInt().coerceIn(0, 255)
            val g = currentRgb[o++].roundToInt().coerceIn(0, 255)
            val b = currentRgb[o++].roundToInt().coerceIn(0, 255)
            displayBuffer[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(displayBuffer, 0, w, 0, 0, w, h)
    }

    private fun boxBlurPass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lineCount = if (horizontal) h else w
        val lineLen = if (horizontal) w else h
        val window = radius * 2 + 1
        for (line in 0 until lineCount) {
            for (i in 0 until lineLen) {
                var r = 0
                var g = 0
                var b = 0
                for (k in -radius..radius) {
                    val j = (i + k).coerceIn(0, lineLen - 1)
                    val idx = if (horizontal) line * w + j else j * w + line
                    val c = src[idx]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                val outIdx = if (horizontal) line * w + i else i * w + line
                dst[outIdx] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val glow = blurredBitmap ?: return
        if (!ambientEnabled || !hasGlow || glow.isRecycled) return
        if (!hasBars()) return

        if (animating) {
            animating = stepAnimation()
            if (animating) postInvalidateOnAnimation()
        }

        val viewW = width
        val viewH = height
        val barsOnSides = videoRect.left > 0 || videoRect.right < viewW
        val barsOnTopBottom = videoRect.top > 0 || videoRect.bottom < viewH

        // 영상이 놓인 자리는 비워 둔다. 단, 반올림 오차로 영상과 조명 사이에 1px 틈이 생겨 검은 줄로
        // 보이지 않도록 여백 쪽 축으로만 조명을 영상 안쪽으로 OVERLAP_PX 만큼 겹친다.
        // (겹친 부분의 색은 영상 가장자리 색과 같게 이어 그리므로 티가 나지 않는다.)
        excludeRect.set(videoRect)
        if (barsOnSides && excludeRect.width() > OVERLAP_PX * 4) {
            excludeRect.left += OVERLAP_PX
            excludeRect.right -= OVERLAP_PX
        }
        if (barsOnTopBottom && excludeRect.height() > OVERLAP_PX * 4) {
            excludeRect.top += OVERLAP_PX
            excludeRect.bottom -= OVERLAP_PX
        }

        val save = canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutRect(excludeRect)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipRect(excludeRect, Region.Op.DIFFERENCE)
        }

        // 1) 바탕: 화면 전체를 덮도록(center-crop) 확대하되, 가장자리 색이 자연스럽게 이어지게 살짝 더 키운다.
        val vw = viewW.toFloat()
        val vh = viewH.toFloat()
        val coverScale = max(vw / glow.width, vh / glow.height) * 1.12f
        val dw = glow.width * coverScale
        val dh = glow.height * coverScale
        coverRect.set(
            (vw - dw) / 2f,
            (vh - dh) / 2f,
            (vw + dw) / 2f,
            (vh + dh) / 2f
        )
        canvas.drawBitmap(glow, null, coverRect, drawPaint)

        // 2) 영상 가장자리 색을 여백 쪽으로 그대로 늘려서 그리고, 바깥으로 갈수록 투명하게 해서 바탕과 섞는다.
        //    이렇게 하면 영상 바로 옆의 색이 영상 가장자리와 이어져 검은 띠처럼 보이지 않는다.
        val gw = glow.width
        val gh = glow.height
        if (barsOnSides) {
            if (excludeRect.left > 0) {
                barRect.set(0, 0, excludeRect.left, viewH)
                edgeSrc.set(0, 0, 1, gh)
                drawEdgeBleed(canvas, glow, excludeRect.left.toFloat(), 0f, 0f, 0f)
            }
            if (excludeRect.right < viewW) {
                barRect.set(excludeRect.right, 0, viewW, viewH)
                edgeSrc.set(gw - 1, 0, gw, gh)
                drawEdgeBleed(canvas, glow, excludeRect.right.toFloat(), 0f, vw, 0f)
            }
        } else if (barsOnTopBottom) {
            if (excludeRect.top > 0) {
                barRect.set(0, 0, viewW, excludeRect.top)
                edgeSrc.set(0, 0, gw, 1)
                drawEdgeBleed(canvas, glow, 0f, excludeRect.top.toFloat(), 0f, 0f)
            }
            if (excludeRect.bottom < viewH) {
                barRect.set(0, excludeRect.bottom, viewW, viewH)
                edgeSrc.set(0, gh - 1, gw, gh)
                drawEdgeBleed(canvas, glow, 0f, excludeRect.bottom.toFloat(), 0f, vh)
            }
        }
        canvas.restoreToCount(save)
    }

    /**
     * [barRect] 영역에 [edgeSrc](블러된 비트맵의 맨 가장자리 한 줄)를 늘려 그린 뒤,
     * (fromX, fromY) 에서 불투명 -> (toX, toY) 에서 투명이 되도록 알파를 깎는다.
     */
    private fun drawEdgeBleed(
        canvas: Canvas,
        glow: Bitmap,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float
    ) {
        val layer = canvas.saveLayer(
            barRect.left.toFloat(),
            barRect.top.toFloat(),
            barRect.right.toFloat(),
            barRect.bottom.toFloat(),
            null
        )
        canvas.drawBitmap(glow, edgeSrc, barRect, edgePaint)
        fadePaint.shader = LinearGradient(
            fromX, fromY, toX, toY,
            Color.BLACK, Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(barRect, fadePaint)
        canvas.restoreToCount(layer)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetGlow()
        updateScheduling()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateScheduling()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(sampleRunnable)
        scheduled = false
        removeCallbacks(detectRunnable)
        detectScheduled = false
        detecting = false
        detectKey = null
        letterboxDetector.release()
        sampleBitmap?.recycle()
        sampleBitmap = null
        glowBitmap?.recycle()
        glowBitmap = null
        glowCanvas = null
        blurredBitmap?.recycle()
        blurredBitmap = null
        hasGlow = false
        animating = false
        snapToTarget = true
        super.onDetachedFromWindow()
    }
}
