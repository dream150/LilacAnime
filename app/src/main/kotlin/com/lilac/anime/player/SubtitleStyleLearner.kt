package com.lilac.anime.player

import android.graphics.Bitmap
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 영상에 박힌 자막의 "스타일"(채움 색, 윤곽선 색/두께, 획 굵기).
 * SubtitleEraseShaderHq.build(style = ...) 로 넘기면 셰이더의 분류 기준이 이 값으로 바뀐다.
 * 길이 값은 모두 "1080p 기준 픽셀"이다 (셰이더 안에서 해상도에 맞게 배율이 곱해진다).
 */
class SubtitleStyle(
    /** 채움 색 (0..1 RGB). 셰이더의 PICK_RGB. */
    val fill: FloatArray,
    /** 채움 색 허용 거리 (RGB 유클리드). 셰이더의 PICK_TOL. */
    val pickTolerance: Float,
    /** 이보다 어둡고 무채색이면 윤곽선 후보 (0..1). */
    val edgeLum: Float,
    /** 윤곽선 후보의 채도(max-min) 상한 (0..1). */
    val edgeChroma: Float,
    /** 획 반폭 상한 (걸음 수, 1걸음 = 1080p 기준 2px). 셰이더의 RAY_STEPS. */
    val rayMax: Float,
    /** 획 속에서 윤곽선/채움 쪽으로 번지는 거리 (1080p 기준 px). 셰이더의 GROW_PX. */
    val growPx: Float
) {
    fun serialize(): String = String.format(
        Locale.US, "v1;%.4f,%.4f,%.4f;%.4f;%.4f;%.4f;%.2f;%.2f",
        fill[0], fill[1], fill[2], pickTolerance, edgeLum, edgeChroma, rayMax, growPx
    )

    companion object {
        fun parse(s: String?): SubtitleStyle? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                val p = s.trim().split(";")
                require(p.size == 7 && p[0] == "v1")
                val c = p[1].split(",").map { it.toFloat() }
                require(c.size == 3)
                SubtitleStyle(
                    floatArrayOf(c[0], c[1], c[2]),
                    p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6].toFloat()
                )
            }.getOrNull()
        }
    }
}

/**
 * 여러 장면에서 캡처한 프레임을 보고 하드섭 자막의 스타일을 스스로 학습한다.
 *
 * 핵심 아이디어: 자막 글자의 획은 "밝은 구간이 좁고(획 폭), 양옆이 어두운 윤곽선으로 막혀 있고,
 * 구간 안의 색이 균일"하다. 이런 구간(run)을 가로/세로로 훑어 모으면, 배경 그림은 장면마다 제각각이지만
 * 자막은 항상 같은 색이므로 색 히스토그램의 최빈값이 곧 자막 채움 색이 된다.
 * 그 색과 일치하는 구간만 다시 모아 획 굵기/윤곽선 두께/윤곽선 색을 통계로 낸다.
 *
 * 프레임은 저장하지 않고 구간 정보(작은 객체)만 쌓는다. 모든 계산은 CPU 에서 하며,
 * addFrame 은 무거우니 호출하는 쪽에서 백그라운드 디스패처에서 부를 것.
 */
class SubtitleStyleLearner {
    class Report(
        val style: SubtitleStyle,
        /** 자막 색과 일치하는 구간이 충분히 보인 프레임 수. */
        val subtitleFrames: Int,
        val usedRuns: Int,
        /** 전체 구간 중 최빈 색에 속한 비율(0..1). 낮으면 배경 그림이 섞였다는 뜻. */
        val share: Float
    ) {
        /** 0..1. 자막 장면 수와 최빈 색 비율로 본 신뢰도. */
        val confidence: Float = (min(1f, subtitleFrames / 16f) * min(1f, share / 0.5f)).coerceIn(0f, 1f)
    }

    private class Run(
        val frame: Int,
        val r: Float, val g: Float, val b: Float,
        val width: Float,      // 1080p 기준 px
        val outThick: Float,   // 1080p 기준 px
        val outLum: Float,
        val outChroma: Float
    )

    private class Analysis(
        val center: FloatArray,
        val members: List<Run>,
        val subtitleFrames: Int,
        val spread: Float
    )

    private val runs = ArrayList<Run>()
    private var frameIndex = 0
    private var frameRuns = 0

    var failureReason: String? = null
        private set

    /** 지금까지 넣은 프레임 수. */
    val capturedFrames: Int get() = frameIndex

    /** rects: 자막 영역(정규화 좌표 0..1). 영역 밖은 보지 않는다. */
    fun addFrame(bmp: Bitmap, rects: List<WatermarkRect>) {
        frameIndex++
        frameRuns = 0
        val bw = bmp.width
        val bh = bmp.height
        if (bw < 64 || bh < 64) return
        val norm = 1080f / bh
        for (rc in rects) {
            val rx = (rc.x * bw).toInt().coerceIn(0, bw - 1)
            val ry = (rc.y * bh).toInt().coerceIn(0, bh - 1)
            val rw = (rc.w * bw).toInt().coerceAtMost(bw - rx)
            val rh = (rc.h * bh).toInt().coerceAtMost(bh - ry)
            if (rw < 8 || rh < 8) continue
            val px = IntArray(rw * rh)
            bmp.getPixels(px, 0, rw, rx, ry, rw, rh)
            val lum = IntArray(px.size)
            val chr = IntArray(px.size)
            for (i in px.indices) {
                val p = px[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                lum[i] = (r * 299 + g * 587 + b * 114) / 1000
                chr[i] = max(r, max(g, b)) - min(r, min(g, b))
            }
            for (y in 0 until rh) scanLine(px, lum, chr, y * rw, 1, rw, norm)
            for (x in 0 until rw) scanLine(px, lum, chr, x, rw, rh, norm)
        }
    }

    private fun isDark(lum: IntArray, chr: IntArray, idx: Int): Boolean =
        lum[idx] <= DARK_MAX && chr[idx] <= DARK_CHROMA_MAX

    /** 한 줄(가로 또는 세로)을 훑어 "양옆이 어두운 윤곽선으로 막힌, 균일한 밝은 구간"을 모은다. */
    private fun scanLine(
        px: IntArray, lum: IntArray, chr: IntArray,
        start: Int, stride: Int, n: Int, norm: Float
    ) {
        val minLen = max(2, (2f / norm).roundToInt())
        val maxLen = max(minLen + 2, (24f / norm).roundToInt())
        val gap = max(2, (3f / norm).roundToInt())
        val maxOut = max(3, (14f / norm).roundToInt())
        var i = 0
        while (i < n) {
            if (frameRuns >= MAX_RUNS_PER_FRAME) return
            if (lum[start + i * stride] < FILL_MIN) {
                i++
                continue
            }
            var j = i
            while (j + 1 < n && lum[start + (j + 1) * stride] >= FILL_MIN) j++
            val len = j - i + 1
            if (len in minLen..maxLen) {
                // 양쪽 가장자리 근처(안티앨리어싱 몇 px 이내)에 어두운 윤곽선이 있어야 한다.
                var l = -1
                var k = 1
                while (k <= gap && i - k >= 0) {
                    if (isDark(lum, chr, start + (i - k) * stride)) { l = i - k; break }
                    k++
                }
                var rr = -1
                if (l >= 0) {
                    k = 1
                    while (k <= gap && j + k < n) {
                        if (isDark(lum, chr, start + (j + k) * stride)) { rr = j + k; break }
                        k++
                    }
                }
                if (l >= 0 && rr >= 0) {
                    var sr = 0
                    var sg = 0
                    var sb = 0
                    var sl = 0L
                    var sl2 = 0L
                    for (t in i..j) {
                        val idx = start + t * stride
                        val p = px[idx]
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                        val lv = lum[idx]
                        sl += lv
                        sl2 += (lv * lv).toLong()
                    }
                    val mean = sl.toFloat() / len
                    val variance = sl2.toFloat() / len - mean * mean
                    // 구간 안의 밝기가 균일해야 한다 (그림의 그라데이션/하이라이트 제외). 아주 짧은 구간은 건너뛴다.
                    if (len < 3 || variance <= UNIFORM_STD * UNIFORM_STD) {
                        var tl = 0
                        var q = l
                        while (q >= 0 && tl < maxOut && isDark(lum, chr, start + q * stride)) { tl++; q-- }
                        var tr = 0
                        q = rr
                        while (q < n && tr < maxOut && isDark(lum, chr, start + q * stride)) { tr++; q++ }
                        val lIdx = start + l * stride
                        runs.add(
                            Run(
                                frameIndex,
                                sr / (255f * len), sg / (255f * len), sb / (255f * len),
                                len * norm,
                                (tl + tr) * 0.5f * norm,
                                lum[lIdx] / 255f,
                                chr[lIdx] / 255f
                            )
                        )
                        frameRuns++
                    }
                }
            }
            i = j + 1
        }
    }

    private fun bin(v: Float): Int = (v * 16f).toInt().coerceIn(0, 15)

    private fun dist2(r: Run, cr: Float, cg: Float, cb: Float): Float {
        val dr = r.r - cr
        val dg = r.g - cg
        val db = r.b - cb
        return dr * dr + dg * dg + db * db
    }

    /** 구간들의 색 최빈값을 찾고, 그 색에 속한 구간과 자막이 보인 프레임 수를 센다. */
    private fun analyze(): Analysis? {
        if (runs.size < MIN_RUNS) return null
        val hist = IntArray(16 * 16 * 16)
        for (r in runs) hist[bin(r.r) * 256 + bin(r.g) * 16 + bin(r.b)]++
        var best = -1
        var bestSum = 0
        for (ri in 0..15) for (gi in 0..15) for (bi in 0..15) {
            var s = 0
            for (dr in -1..1) for (dg in -1..1) for (db in -1..1) {
                val a = ri + dr
                val b = gi + dg
                val c = bi + db
                if (a in 0..15 && b in 0..15 && c in 0..15) s += hist[a * 256 + b * 16 + c]
            }
            if (s > bestSum) {
                bestSum = s
                best = ri * 256 + gi * 16 + bi
            }
        }
        if (best < 0) return null
        var cr = ((best / 256) + 0.5f) / 16f
        var cg = (((best / 16) % 16) + 0.5f) / 16f
        var cb = ((best % 16) + 0.5f) / 16f
        repeat(2) {
            var n = 0
            var sr = 0f
            var sg = 0f
            var sb = 0f
            for (r in runs) {
                if (dist2(r, cr, cg, cb) <= RADIUS2) {
                    n++
                    sr += r.r
                    sg += r.g
                    sb += r.b
                }
            }
            if (n > 0) {
                cr = sr / n
                cg = sg / n
                cb = sb / n
            }
        }
        var members = runs.filter { dist2(it, cr, cg, cb) <= RADIUS2 }
        if (members.size < MIN_MEMBERS) return null
        // 자막이 나온 프레임(같은 색 구간이 프레임당 수십~수백 개)에서 나온 구간만 쓴다.
        // 배경 그림의 비슷한 색(하이라이트, 선화)은 프레임당 몇 개뿐이라 여기서 걸러진다.
        val perFrame = IntArray(frameIndex + 1)
        for (m in members) perFrame[m.frame]++
        members = members.filter { perFrame[it.frame] >= MIN_RUNS_PER_FRAME }
        if (members.size < MIN_MEMBERS) return null
        // 색 중심과 흩어짐을 자막 프레임 기준으로 다시 계산
        var sr = 0f
        var sg = 0f
        var sb = 0f
        for (m in members) {
            sr += m.r
            sg += m.g
            sb += m.b
        }
        cr = sr / members.size
        cg = sg / members.size
        cb = sb / members.size
        var sq = 0f
        for (m in members) sq += dist2(m, cr, cg, cb)
        val frames = members.map { it.frame }.distinct().size
        return Analysis(floatArrayOf(cr, cg, cb), members, frames, sqrt(sq / members.size))
    }

    /** 학습 도중 진행 상황: (자막이 보인 프레임 수, 이만큼이면 충분한가). */
    fun progress(): Pair<Int, Boolean> {
        val a = analyze() ?: return 0 to false
        return a.subtitleFrames to (a.subtitleFrames >= 16 && a.members.size >= 1500)
    }

    fun finish(): Report? {
        val a = analyze()
        if (a == null) {
            failureReason = "윤곽선이 있는 자막 글자를 찾지 못했습니다. 자막 영역이 맞는지, 자막이 나오는 장면이 충분한지 확인해 주세요."
            return null
        }
        if (a.subtitleFrames < 5) {
            failureReason = "자막이 나온 장면이 부족합니다 (${a.subtitleFrames}개). 대사가 많은 에피소드에서 다시 시도해 주세요."
            return null
        }
        val m = a.members
        fun pct(sorted: List<Float>, q: Float): Float = sorted[((sorted.size - 1) * q).toInt()]
        val widths = m.map { it.width }.sorted()
        val outs = m.map { it.outThick }.sorted()
        val oLum = m.map { it.outLum }.sorted()
        val oChr = m.map { it.outChroma }.sorted()
        val wp90 = pct(widths, 0.9f)
        val outMed = pct(outs, 0.5f)
        val style = SubtitleStyle(
            fill = floatArrayOf(a.center[0], a.center[1], a.center[2]),
            pickTolerance = (2.5f * a.spread + 0.06f).coerceIn(0.10f, 0.22f),
            edgeLum = (pct(oLum, 0.9f) + 0.10f).coerceIn(0.20f, 0.42f),
            edgeChroma = (pct(oChr, 0.9f) + 0.08f).coerceIn(0.14f, 0.35f),
            // 획 폭의 90% 지점에 여유 1.6배. 1걸음 = 1080p 기준 2px 이므로 폭/2.
            rayMax = ceil(wp90 * 1.6f / 2f).coerceIn(5f, 16f),
            growPx = (outMed * 1.5f + 3f).coerceIn(5f, 12f)
        )
        return Report(style, a.subtitleFrames, m.size, m.size.toFloat() / runs.size)
    }

    companion object {
        private const val FILL_MIN = 150          // 이 밝기(0..255) 이상이면 채움 후보
        private const val DARK_MAX = 80           // 이 밝기 이하(+무채색)면 윤곽선 후보
        private const val DARK_CHROMA_MAX = 100
        private const val UNIFORM_STD = 22f       // 구간 안 밝기 표준편차 상한
        private const val RADIUS2 = 0.14f * 0.14f // 최빈 색과의 거리^2 (0..1 RGB)
        private const val MIN_RUNS = 300
        private const val MIN_MEMBERS = 200
        private const val MIN_RUNS_PER_FRAME = 80
        private const val MAX_RUNS_PER_FRAME = 4000
    }
}
