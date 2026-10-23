package com.lilac.anime.player

import android.graphics.Bitmap
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 고정된 반투명(흰색) 워터마크의 투명도 맵을 영상에서 스스로 학습합니다.
 *
 * 화면 = (1 - a) * 원본 + a * 흰색 이므로, 같은 위치의 픽셀을 여러 장면에서 모아 보면
 * 가장 어두운 값이 "배경이 거의 검정일 때" = a 에 가깝습니다. 프레임을 저장하지 않고
 * 픽셀/채널마다 지금까지의 최솟값 하나만 유지하므로 메모리는 프레임 1장 분량입니다.
 *
 * estimate_wm.py (PC용) 와 같은 수식입니다. 다만 여기서는 재생 화면(TextureView)을
 * 캡처하므로 원본 해상도/색 변환이 완전히 같지는 않아 PC 방식보다 약간 덜 정확합니다.
 */
class WatermarkMaskLearner {
    class Result(
        /** bbox 안의 RGBA (premultiplied: rgb = a * 흰색, a = 투명도). 흰색이라 네 값이 같다. */
        val rgba: ByteArray,
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val frameWidth: Int, val frameHeight: Int,
        val maxAlpha: Float,
        /** 로고 밖 배경 바닥값의 흩어짐으로 본 예상 오차(0~1). 낮을수록 정확. */
        val expectedError: Float,
        val frames: Int,
        /** 셰이더에 저장되는 텍스처 크기. 큰 마스크는 정수 배율로 줄여 파일을 작게 유지한다. */
        val texW: Int = x1 - x0,
        val texH: Int = y1 - y0
    )

    private var w = 0
    private var h = 0
    private var low: ByteArray? = null
    private var prevThumb: IntArray? = null

    var acceptedFrames = 0
        private set

    /** 지금까지 본 "어두운 장면"(가장자리 평균 밝기 < 45) 수. 중복으로 걸러진 프레임도 센다. */
    var darkFrames = 0
        private set

    /** 가장 최근 프레임의 가장자리 평균 밝기(0~255). 로고가 없는 가장자리만 본다. */
    var lastRingLuma = 255f
        private set

    var failureReason: String? = null
        private set

    class RingStats(val b0: FloatArray, val expectedError: Float)

    /** 장면이 충분히 다를 때만 반영합니다. 반영하면 true. */
    fun addFrame(bmp: Bitmap): Boolean {
        if (low == null) {
            w = bmp.width
            h = bmp.height
            low = ByteArray(w * h * 3) { -1 } // 255
        } else if (bmp.width != w || bmp.height != h) {
            return false
        }

        val thumb = thumbnail(bmp)
        lastRingLuma = ringLuma(thumb)
        if (lastRingLuma < 45f) darkFrames++
        val prev = prevThumb
        // 거의 똑같은 프레임(정지 화면 등)만 거른다. 어두운 장면끼리는 서로 비슷해 보여도
        // 어두운 위치가 달라 정보가 되므로 문턱을 낮게 둔다.
        if (prev != null && meanAbsDiff(prev, thumb) < 1.5) return false
        prevThumb = thumb

        val lo = low!!
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var j = 0
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (r < (lo[j].toInt() and 0xFF)) lo[j] = r.toByte()
            if (g < (lo[j + 1].toInt() and 0xFF)) lo[j + 1] = g.toByte()
            if (b < (lo[j + 2].toInt() and 0xFF)) lo[j + 2] = b.toByte()
            j += 3
        }
        acceptedFrames++
        return true
    }

    /** 학습 도중 정확도를 가늠하는 가벼운 계산(가장자리만 훑는다). */
    fun quickExpectedError(): Float {
        val lo = low ?: return 1f
        if (acceptedFrames < 3) return 1f
        return ringStats(lo, 0.06f).expectedError
    }

    private fun ringStats(lo: ByteArray, refMargin: Float): RingStats {
        val m = max(2, (min(w, h) * refMargin).toInt())
        val hist = Array(3) { IntArray(256) }
        val lumHist = IntArray(256)
        var refCount = 0
        fun acc(x: Int, y: Int) {
            val k = (y * w + x) * 3
            val r = lo[k].toInt() and 0xFF
            val g = lo[k + 1].toInt() and 0xFF
            val b = lo[k + 2].toInt() and 0xFF
            hist[0][r]++; hist[1][g]++; hist[2][b]++
            lumHist[(r + g + b) / 3]++
            refCount++
        }
        for (y in 0 until h) {
            if (y < m || y >= h - m) {
                for (x in 0 until w) acc(x, y)
            } else {
                for (x in 0 until m) acc(x, y)
                for (x in w - m until w) acc(x, y)
            }
        }
        val b0 = FloatArray(3) { c -> percentile(hist[c], refCount, 0.5).toFloat() }
        val p50 = percentile(lumHist, refCount, 0.5).toFloat()
        val p90 = percentile(lumHist, refCount, 0.9).toFloat()
        val b0Mean = (b0[0] + b0[1] + b0[2]) / 3f
        return RingStats(b0, ((p90 - p50) / max(255f - b0Mean, 1f)).coerceIn(0f, 1f))
    }

    fun finish(gate: Float = 0.02f, pad: Int = 6, refMargin: Float = 0.06f): Result? {
        val lo = low ?: run { failureReason = "캡처된 프레임이 없습니다."; return null }
        if (acceptedFrames < 6) {
            failureReason = "분석할 수 있는 장면이 부족합니다 (${acceptedFrames}개). 영상이 로딩 중이었는지 확인해 주세요."
            return null
        }

        // 1) 로고가 없는 가장자리에서 "검정 기준값" b0 와 바닥값의 흩어짐을 구한다.
        val ring = ringStats(lo, refMargin)
        val b0 = ring.b0
        val expectedError = ring.expectedError

        // 2) 투명도 a = (바닥값 - b0) / (255 - b0), 채널 평균. 흰색 로고 가정.
        val alpha = FloatArray(w * h)
        val den = FloatArray(3) { max(255f - b0[it], 1f) }
        var k = 0
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        var maxAlpha = 0f
        val bboxThresh = gate * 2f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = ((lo[k].toInt() and 0xFF) - b0[0]) / den[0]
                val g = ((lo[k + 1].toInt() and 0xFF) - b0[1]) / den[1]
                val b = ((lo[k + 2].toInt() and 0xFF) - b0[2]) / den[2]
                var a = ((r + g + b) / 3f).coerceIn(0f, 0.97f)
                if (a < gate) a = 0f
                alpha[y * w + x] = a
                if (a > bboxThresh) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    if (a > maxAlpha) maxAlpha = a
                }
                k += 3
            }
        }
        if (maxX < 0 || maxAlpha < 0.03f) {
            failureReason = "워터마크를 찾지 못했습니다 (장면 ${acceptedFrames}개, 최대 투명도 ${"%.3f".format(Locale.US, maxAlpha)}). " +
                "로고가 매우 옅거나 흰색이 아닐 수 있어요."
            return null
        }

        val x0 = max(0, minX - pad)
        val y0 = max(0, minY - pad)
        val x1 = min(w, maxX + 1 + pad)
        val y1 = min(h, maxY + 1 + pad)
        val bw0 = x1 - x0
        val bh0 = y1 - y0
        // 텍스처가 크면(수십 MB 의 hex 셰이더) mpv 가 읽거나 올리지 못할 수 있어 정수 배율로 줄인다.
        var f = 1
        while ((bw0 / f + 1).toLong() * (bh0 / f + 1) > 700_000L) f++
        val tw = (bw0 + f - 1) / f
        val th = (bh0 + f - 1) / f
        val rgba = ByteArray(tw * th * 4)
        var o = 0
        for (ty in 0 until th) {
            for (tx in 0 until tw) {
                var sum = 0f
                for (dy in 0 until f) {
                    val yy = y0 + ty * f + dy
                    if (yy >= h) continue
                    for (dx in 0 until f) {
                        val xx = x0 + tx * f + dx
                        if (xx >= w) continue
                        sum += alpha[yy * w + xx]
                    }
                }
                val a8 = (sum / (f * f) * 255f).roundToInt().coerceIn(0, 255).toByte()
                rgba[o] = a8; rgba[o + 1] = a8; rgba[o + 2] = a8; rgba[o + 3] = a8
                o += 4
            }
        }
        // 텍스처가 덮는 실제 영역(프레임 밖으로 조금 나갈 수 있음)을 x1/y1 로 돌려준다.
        val ex1 = x0 + tw * f
        val ey1 = y0 + th * f
        return Result(rgba, x0, y0, ex1, ey1, w, h, maxAlpha, expectedError, acceptedFrames, tw, th)
    }

    companion object {
        private const val HEX = "0123456789abcdef"

        /**
         * 이 mpv 빌드는 //!TEXTURE 를 "wm_tex()" 매크로가 아니라 `uniform sampler2D wm` 으로만 넘겨 준다
         * (로그: uniform sampler2D wmt; / 'wmt_tex' 미정의). 예전에 만든 마스크 파일과 PC 스크립트로 만든 파일도
         * 그대로 쓸 수 있도록 헤더 부분의 호출만 texture(wm, uv) 로 바꾼다.
         */
        private fun fixHead(head: String): String =
            head.replace("wm_tex(uv)", "texture(wm, uv)")

        /**
         * src 를 dst 로 다시 쓴다. 헤더(처음 4096바이트)는 fixHead + headTransform 을 거치고,
         * TEXTURE 본문의 줄바꿈/공백은 모두 제거해 한 줄 hex 로 만든다(예전 파일도 고쳐진다).
         */
        private fun rewriteShader(src: File, dst: File, headTransform: (String) -> String = { it }) {
            src.inputStream().buffered().use { input ->
                val headBytes = ByteArray(4096)
                var got = 0
                while (got < headBytes.size) {
                    val n = input.read(headBytes, got, headBytes.size - got)
                    if (n < 0) break
                    got += n
                }
                val head = headTransform(fixHead(String(headBytes, 0, got, Charsets.ISO_8859_1)))
                val idx = head.indexOf("//!TEXTURE wm")
                require(idx >= 0) { "마스크 파일 형식이 올바르지 않습니다." }
                // TEXTURE 헤더(//! 로 시작하는 연속된 줄)의 끝 = 본문 시작
                var pos = idx
                while (pos < head.length && head.startsWith("//!", pos)) {
                    val nl = head.indexOf('\n', pos)
                    if (nl < 0) { pos = head.length; break }
                    pos = nl + 1
                }
                dst.outputStream().buffered().use { out ->
                    out.write(head.substring(0, pos).toByteArray(Charsets.ISO_8859_1))
                    fun writeFiltered(bytes: ByteArray, len: Int) {
                        var w = 0
                        val tmp = ByteArray(len)
                        for (i in 0 until len) {
                            val c = bytes[i].toInt()
                            if (c != 10 && c != 13 && c != 32 && c != 9) tmp[w++] = bytes[i]
                        }
                        out.write(tmp, 0, w)
                    }
                    val rest = head.substring(pos).toByteArray(Charsets.ISO_8859_1)
                    writeFiltered(rest, rest.size)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        writeFiltered(buf, n)
                    }
                    out.write('\n'.code)
                }
            }
        }

        /** 로드용 복사본: wm_tex 호출 교체 + 본문 한 줄화. */
        fun copyShaderFixed(src: File, dst: File) = rewriteShader(src, dst)

        /** mpv user shader (HOOK MAIN) 로 저장. estimate_wm.py 가 만드는 파일과 같은 형식. */
        fun writeShader(result: Result, target: File) {
            val bw = result.texW
            val bh = result.texH
            val ox = result.x0.toDouble() / result.frameWidth
            val oy = result.y0.toDouble() / result.frameHeight
            val ew = (result.x1 - result.x0).toDouble() / result.frameWidth
            val eh = (result.y1 - result.y0).toDouble() / result.frameHeight
            val head = String.format(
                Locale.US,
                "//!HOOK MAIN\n//!BIND HOOKED\n//!BIND wm\n" +
                    "//!DESC remove fixed translucent watermark (inverse alpha compositing)\n\n" +
                    "// Normalized coordinates: works at any resolution with the same aspect/crop.\n" +
                    "const vec2 WM_ORIGIN = vec2(%.8f, %.8f);\n" +
                    "const vec2 WM_EXTENT = vec2(%.8f, %.8f);\n\n" +
                    "vec4 hook() {\n" +
                    "    vec4 c = HOOKED_tex(HOOKED_pos);\n" +
                    "    vec2 uv = (HOOKED_pos - WM_ORIGIN) / WM_EXTENT;\n" +
                    "    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return c;\n" +
                    "    vec4 m = texture(wm, uv);\n" +
                    "    c.rgb = clamp((c.rgb - m.rgb) / max(1.0 - m.a, 0.05), 0.0, 1.0);\n" +
                    "    return c;\n" +
                    "}\n\n" +
                    "//!TEXTURE wm\n//!SIZE %d %d\n//!FORMAT rgba8\n//!FILTER LINEAR\n//!BORDER CLAMP\n",
                ox, oy, ew, eh, bw, bh
            )
            target.bufferedWriter(Charsets.US_ASCII).use { out ->
                out.write(head)
                // mpv 는 TEXTURE 본문이 "한 줄짜리 hex" 여야 한다 (줄바꿈이 있으면 파싱 에러).
                val chunk = StringBuilder(64 * 1024)
                for (b in result.rgba) {
                    val v = b.toInt() and 0xFF
                    chunk.append(HEX[v shr 4]).append(HEX[v and 0x0F])
                    if (chunk.length >= 64 * 1024 - 2) {
                        out.write(chunk.toString())
                        chunk.setLength(0)
                    }
                }
                out.write(chunk.toString())
                out.write("\n")
            }
        }

        /**
         * 진단용: 같은 마스크로 "지우는 대신 빨간색으로 칠하는" 셰이더를 만든다.
         * 빨간 모양이 실제 워터마크와 겹치면 마스크는 맞는 것이고, 아무것도 안 보이면
         * 셰이더가 화면에 반영되지 않는 것이다. 텍스처 데이터는 그대로 복사한다.
         */
        fun writePreviewShader(
            src: File,
            dst: File,
            replacement: String = "c.rgb = mix(c.rgb, vec3(1.0, 0.0, 0.0), clamp(m.a * 8.0, 0.0, 1.0));"
        ) {
            val original = "c.rgb = clamp((c.rgb - m.rgb) / max(1.0 - m.a, 0.05), 0.0, 1.0);"
            rewriteShader(src, dst) { head ->
                require(head.contains(original)) { "마스크 셰이더 형식을 인식하지 못했습니다." }
                head.replace(original, replacement)
            }
        }

        private fun percentile(hist: IntArray, total: Int, q: Double): Int {
            if (total <= 0) return 0
            val target = (total * q).toLong()
            var sum = 0L
            for (i in hist.indices) {
                sum += hist[i]
                if (sum > target) return i
            }
            return 255
        }

        private fun thumbnail(bmp: Bitmap): IntArray {
            val small = Bitmap.createScaledBitmap(bmp, 48, 27, true)
            val out = IntArray(48 * 27)
            small.getPixels(out, 0, 48, 0, 0, 48, 27)
            if (small !== bmp) small.recycle()
            return IntArray(out.size) { i ->
                val p = out[i]
                (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
            }
        }

        /** 48x27 썸네일의 바깥 3칸 테두리 평균 밝기. 로고가 있는 중앙은 보지 않는다. */
        private fun ringLuma(t: IntArray): Float {
            var sum = 0L
            var n = 0
            for (y in 0 until 27) {
                for (x in 0 until 48) {
                    if (y < 3 || y >= 24 || x < 3 || x >= 45) {
                        sum += t[y * 48 + x]
                        n++
                    }
                }
            }
            return if (n == 0) 255f else sum.toFloat() / n
        }

        private fun meanAbsDiff(a: IntArray, b: IntArray): Double {
            var s = 0L
            for (i in a.indices) s += abs(a[i] - b[i])
            return s.toDouble() / a.size
        }
    }
}
