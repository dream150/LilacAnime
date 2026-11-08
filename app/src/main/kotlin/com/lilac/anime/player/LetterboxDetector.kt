package com.lilac.anime.player

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.TextureView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 영상 파일 자체에 구워진 검은 띠(레터박스/필러박스)를 검출한다.
 *
 * - mpv 가 그리는 TextureView 를 작은 해상도로 읽어, 영상 영역 안에서 "거의 검은" 행/열이
 *   가장자리에서부터 얼마나 이어지는지 센다.
 * - 어두운 장면(페이드, 밤 장면)을 띠로 오인하지 않도록 프레임을 여러 장 모아 가장 작은 값만 쓴다.
 *   (모든 프레임에서 공통으로 검었던 구간만 띠로 인정)
 * - 결과는 영상 영역 대비 비율(0..1)이다. 소스 픽셀로 바꾸는 건 호출하는 쪽에서 한다.
 *
 * 반드시 crop 이 적용되지 않은 상태의 영상에서 돌려야 한다.
 */
class LetterboxDetector(
    private val longEdge: Int = 320,
    // 이 밝기(0..255) 이하면 "검은 픽셀". 리미티드 레인지 검정(16)에 압축 노이즈 여유를 더한 값.
    private val lumaThreshold: Int = 40,
    // 한 행/열에서 밝은 픽셀이 이 비율 이하면 검은 줄로 본다. 띠 안에 구워진 자막이 있어도 걸리게 하는 여유.
    private val brightFraction: Float = 0.02f,
    // 이보다 얇은 띠는 무시한다.
    private val minBarFraction: Float = 0.015f,
    // 이만큼의 유효 프레임을 모은 뒤에 결과를 낸다.
    private val requiredSamples: Int = 12,
    // 전체 밝은 픽셀이 이 비율보다 적은 프레임(페이드/암전)은 버린다.
    private val minLitFraction: Float = 0.03f,
) {
    data class Bars(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val isEmpty: Boolean get() = left <= 0f && top <= 0f && right <= 0f && bottom <= 0f
    }

    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private var accepted = 0
    private var minLeft = Float.MAX_VALUE
    private var minTop = Float.MAX_VALUE
    private var minRight = Float.MAX_VALUE
    private var minBottom = Float.MAX_VALUE

    fun reset() {
        accepted = 0
        minLeft = Float.MAX_VALUE
        minTop = Float.MAX_VALUE
        minRight = Float.MAX_VALUE
        minBottom = Float.MAX_VALUE
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
        pixels = IntArray(0)
    }

    /**
     * 프레임 한 장을 넣는다. 충분히 모이면 결과(띠가 없으면 isEmpty 인 Bars)를, 아직이면 null 을 돌려준다.
     * [videoRect] 는 뷰 좌표계에서 영상이 그려지는 영역(AmbientGlowView.computeVideoRect 와 같은 값).
     */
    fun feed(tv: TextureView, videoRect: Rect): Bars? {
        val vw = tv.width
        val vh = tv.height
        if (vw <= 0 || vh <= 0 || !tv.isAvailable) return null
        if (videoRect.width() < 16 || videoRect.height() < 16) return null

        val scale = longEdge.toFloat() / max(vw, vh)
        val bw = max(16, (vw * scale).roundToInt())
        val bh = max(16, (vh * scale).roundToInt())
        var bmp = bitmap
        if (bmp == null || bmp.isRecycled || bmp.width != bw || bmp.height != bh) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            pixels = IntArray(bw * bh)
            reset()
        }
        val got = runCatching { tv.getBitmap(bmp) }.getOrNull() ?: return null
        got.getPixels(pixels, 0, bw, 0, 0, bw, bh)

        val x0 = (videoRect.left * scale).roundToInt().coerceIn(0, bw - 1)
        val y0 = (videoRect.top * scale).roundToInt().coerceIn(0, bh - 1)
        val x1 = (videoRect.right * scale).roundToInt().coerceIn(x0 + 1, bw)
        val y1 = (videoRect.bottom * scale).roundToInt().coerceIn(y0 + 1, bh)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < 16 || rh < 16) return null

        val rowLit = IntArray(rh)
        val colLit = IntArray(rw)
        var totalLit = 0
        for (y in 0 until rh) {
            val base = (y0 + y) * bw + x0
            for (x in 0 until rw) {
                val c = pixels[base + x]
                val luma = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                if (luma > lumaThreshold) {
                    rowLit[y]++
                    colLit[x]++
                    totalLit++
                }
            }
        }
        // 거의 암전인 프레임은 띠인지 어두운 장면인지 알 수 없으므로 버린다.
        if (totalLit < rw * rh * minLitFraction) return null

        val rowLimit = (rw * brightFraction).toInt()
        val colLimit = (rh * brightFraction).toInt()
        var top = 0
        while (top < rh && rowLit[top] <= rowLimit) top++
        var bottom = 0
        while (bottom < rh && rowLit[rh - 1 - bottom] <= rowLimit) bottom++
        var left = 0
        while (left < rw && colLit[left] <= colLimit) left++
        var right = 0
        while (right < rw && colLit[rw - 1 - right] <= colLimit) right++
        if (top >= rh || left >= rw) return null

        minTop = minOf(minTop, top.toFloat() / rh)
        minBottom = minOf(minBottom, bottom.toFloat() / rh)
        minLeft = minOf(minLeft, left.toFloat() / rw)
        minRight = minOf(minRight, right.toFloat() / rw)
        accepted++
        if (accepted < requiredSamples) return null

        // 검출 해상도 1칸만큼 더 잘라 낸다. 띠 경계의 회색 줄(압축 번짐)이 앰비언트와 맞닿아 보이는 걸 막는다.
        fun finish(v: Float, extent: Int): Float =
            if (v < minBarFraction) 0f else (v + 1f / extent).coerceAtMost(0.4f)

        val result = Bars(
            left = finish(minLeft, rw),
            top = finish(minTop, rh),
            right = finish(minRight, rw),
            bottom = finish(minBottom, rh),
        )
        reset()
        return result
    }
}
