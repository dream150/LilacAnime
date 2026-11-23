package com.lilac.anime.player

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 시공간(spatio-temporal) 자막 지우기 - 신경망 없이 수식만 쓴다.
 *
 * mpv 의 GLSL 셰이더는 프레임 사이에 상태를 남기지 못한다(이전 프레임을 읽을 수 없다).
 * 그래서 "시간축"은 libavfilter 로 만든다:
 *
 *   mpv vf(lavfi, CPU)                         mpv glsl-shaders(GPU)
 *   ┌──────────────────────────────┐          ┌─────────────────────────────────┐
 *   │ 원본 프레임                    │          │ 1) 자막 획 검출 (SubtitleEraseHq │
 *   │ + 자막 띠를 0.7/2/4.5/9 초 전   │  ─────▶  │    의 1~5패스를 그대로 재사용)    │
 *   │   프레임으로 잘라 아래에 붙임    │          │ 2) 공간 채움 (구조 보존 보간)     │
 *   │   (1/2 해상도, 6fps 지연선)     │          │ 3) 과거 프레임 후보를 링 일치도로  │
 *   └──────────────────────────────┘          │    가중해 구멍에 섞음              │
 *                                              │ 4) 원본 크기로 잘라 내보냄         │
 *                                              └─────────────────────────────────┘
 *
 * 자막은 몇 초 동안 같은 자리에 박혀 있으므로 "바로 전 프레임"에는 쓸모가 없다.
 * 대신 자막이 뜨기 전의 프레임(= 지연선 중 하나)에는 가려지지 않은 배경이 있다.
 * 배경이 정지해 있으면 그 프레임의 같은 픽셀이 정답이고, 움직였으면 구멍 둘레(링)가 어긋나서
 * 가중치가 0 으로 떨어지므로 공간 채움이 그대로 쓰인다. (장면 전환, 큰 움직임, 지연선이 아직 안 찬 시점 포함)
 */
class SubtitleStLayout private constructor(
    val frameW: Int,
    val frameH: Int,
    /** 지연선에 담는 영역(원본 픽셀, 4의 배수). 지울 영역 + 링 반경만큼의 여유. */
    val bandX: Int,
    val bandY: Int,
    val bandW: Int,
    val bandH: Int,
    val stripW: Int,
    val stripH: Int,
    val perRow: Int,
    val rows: Int
) {
    val padH: Int get() = rows * stripH
    val packedH: Int get() = frameH + padH
    val key: String get() = "${frameW}x$frameH:$bandX,$bandY,$bandW,$bandH"

    fun stripX(k: Int): Int = (k % perRow) * stripW
    fun stripY(k: Int): Int = frameH + (k / perRow) * stripH

    /**
     * mpv `vf add @label:lavfi=graph=%N%<이 문자열>` 로 넣는다.
     * 출력 프레임 = 원본(위) + 과거 띠 REFS 장(아래, 초록색 패드 위에 얹음).
     * 지연선이 아직 차지 않은 구간은 초록색이 그대로 남고, 셰이더가 그걸 "무효"로 읽는다.
     * setsar 는 쌓은 프레임이 원래 화면 비율(DAR)로 표시되게 맞춘다. (셰이더가 마지막에 원본 크기로 자른다)
     */
    fun vfGraph(displayAspect: Double): String {
        val sb = StringBuilder()
        sb.append("[in]split=").append(REFS + 1).append("[m]")
        for (k in 0 until REFS) sb.append("[s").append(k).append(']')
        sb.append(';')
        for (k in 0 until REFS) {
            sb.append("[s").append(k).append("]crop=").append(bandW).append(':').append(bandH)
                .append(':').append(bandX).append(':').append(bandY)
                .append(",fps=").append(HIST_FPS)
                .append(",scale=").append(stripW).append(':').append(stripH).append(":flags=bilinear")
                .append(",setpts=PTS+").append(String.format(Locale.US, "%.3f", OFFSETS_SEC[k])).append("/TB")
                .append("[h").append(k).append("];")
        }
        sb.append("[m]pad=").append(frameW).append(':').append(packedH).append(":0:0:color=0x00FF00[c0];")
        for (k in 0 until REFS) {
            sb.append("[c").append(k).append("][h").append(k).append("]overlay=")
                .append(stripX(k)).append(':').append(stripY(k))
                .append(":eof_action=repeat[c").append(k + 1).append("];")
        }
        sb.append("[c").append(REFS).append("]setsar=sar=")
            .append(String.format(Locale.US, "%.8f", displayAspect))
            .append('*').append(packedH).append('/').append(frameW)
            .append("[out]")
        return sb.toString()
    }

    companion object {
        const val REFS = 4

        /** 지연선 오프셋(초). 자막 한 줄이 보통 1~5초 떠 있으므로 가장 긴 값이 그보다 커야 뜨기 전 프레임을 만난다. */
        val OFFSETS_SEC = doubleArrayOf(0.7, 2.0, 4.5, 9.0)
        const val HIST_FPS = 6

        /** 링 일치도 반경(1080p 기준 px). 셰이더의 RING_PX 와 같아야 한다. */
        const val RING_PX_1080 = 80.0

        /** 프레임 크기와 지울 영역으로 레이아웃을 정한다. 쓸 수 없는 크기면 null (HQ 공간 채움만 사용). */
        fun plan(w: Int, h: Int, rects: List<WatermarkRect>): SubtitleStLayout? {
            if (rects.isEmpty() || w < 256 || h < 144 || w % 2 != 0 || h % 2 != 0) return null
            val s = h / 1080.0
            val margin = ceil(RING_PX_1080 * s).toInt() + 8
            val minX = rects.minOf { it.x }
            val maxX = rects.maxOf { it.x + it.w }
            val minY = rects.minOf { it.y }
            val maxY = rects.maxOf { it.y + it.h }

            val wMax = w / 4 * 4
            val hMax = h / 4 * 4
            val x0 = ((floor(minX * w).toInt() - margin).coerceAtLeast(0)) / 4 * 4
            val y0 = ((floor(minY * h).toInt() - margin).coerceAtLeast(0)) / 4 * 4
            val x1 = min(((ceil(maxX * w).toInt() + margin + 3) / 4) * 4, wMax)
            val y1 = min(((ceil(maxY * h).toInt() + margin + 3) / 4) * 4, hMax)
            val bw = x1 - x0
            val bh = y1 - y0
            if (bw < 16 || bh < 16) return null
            // 너무 넓은 영역(멀리 떨어진 여러 영역 등)은 지연선 비용이 커서 포기한다.
            if (bh > h / 2 || bw.toLong() * bh > w.toLong() * h / 2) return null

            val sw = bw / 2
            val sh = bh / 2
            val perRow = max(1, w / sw)
            val rows = (REFS + perRow - 1) / perRow
            val packedH = h + rows * sh
            if (packedH > 4096) return null
            return SubtitleStLayout(w, h, x0, y0, bw, bh, sw, sh, perRow, rows)
        }
    }
}

object SubtitleEraseShaderSt {
    private class Pass(val header: MutableList<String>, var body: String) {
        fun render(): String = header.joinToString("\n") + "\n" + body
    }

    private fun Pass.mapHeader(fn: (String) -> String) {
        for (i in header.indices) header[i] = fn(header[i])
    }

    private fun parse(pass: String): Pass {
        val lines = pass.split('\n')
        var i = 0
        while (i < lines.size && lines[i].startsWith("//!")) i++
        return Pass(lines.subList(0, i).toMutableList(), lines.subList(i, lines.size).joinToString("\n"))
    }

    private fun f(v: Double, digits: Int = 6): String = String.format(Locale.US, "%.${digits}f", v)

    private fun replaceAllChecked(text: String, old: String, new: String, what: String): String {
        check(text.contains(old)) { "SubtitleEraseShaderSt: HQ 셰이더에서 '$what' 를 찾지 못했습니다 (HQ 셰이더가 바뀌었는지 확인)" }
        return text.replace(old, new)
    }

    /**
     * @return mpv 에 줄 GLSL. [SubtitleStLayout.vfGraph] 로 만든 필터가 먼저 붙어 있어야만 맞게 동작한다.
     * 파라미터는 [SubtitleEraseShaderHq.build] 와 같다.
     */
    fun build(
        layout: SubtitleStLayout,
        rects: List<WatermarkRect>,
        pickedRgb: FloatArray? = null,
        pickTolerance: Float = 0.14f,
        style: SubtitleStyle? = null,
        strictOutline: Boolean = false,
        limitLength: Boolean = false,
        debug: Boolean = false
    ): String {
        val hq = SubtitleEraseShaderHq.build(rects, pickedRgb, pickTolerance, style, strictOutline, limitLength, debug)
        val raw = hq.split(Regex("(?m)^(?=//!HOOK MAIN)")).filter { it.isNotBlank() }
        check(raw.size == 8) { "SubtitleEraseShaderSt: HQ 셰이더 패스 수가 8개가 아닙니다 (${raw.size})" }

        val ph = layout.padH
        val stc = stCommon(layout)

        // HQ 패스는 "원본 크기 프레임"을 가정한다. 우리 입력은 아래에 띠가 붙은 프레임이므로
        // 원본 영역만 읽도록 텍스처 접근/크기 매크로를 바꾼다. (HOOKED_pos 는 패스 출력 기준이라 그대로 맞다)
        val passes = raw.map { p ->
            val t = p.replace("HOOKED_tex(", "mainTex(")
                .replace("HOOKED_pt", "MAIN_PT")
                .replace("HOOKED_size", "MAIN_SIZE")
            parse(t)
        }

        // HQ 공통부(밴드 함수 + 상수 + lum/chroma). 이펙트 패스에서도 그대로 쓴다.
        val common = passes[4].body.substringBefore("vec4 hook()")

        // 1~5: 분류 / 획 / 번짐 / 팽창 / 여유 영역 (반해상도, 높이만 패딩 만큼 빼서 원본 영역에 맞춘다)
        for (i in 0..4) {
            passes[i].mapHeader {
                if (it == "//!HEIGHT HOOKED.h 2 /") "//!HEIGHT HOOKED.h $ph - 2 /" else it
            }
        }
        // 6: 공간 채움(SUBF) - 원본 크기로 출력
        passes[5].header.add("//!WIDTH HOOKED.w")
        passes[5].header.add("//!HEIGHT HOOKED.h $ph -")
        // 7: 평활 + 주변 질감 - 입력을 시간 합성 결과(SUBT)로 바꾸고, 시간 신뢰도가 높은 곳은 질감 복사를 줄인다
        passes[6].mapHeader { if (it == "//!BIND SUBF") "//!BIND SUBT" else it }
        passes[6].header.add("//!WIDTH HOOKED.w")
        passes[6].header.add("//!HEIGHT HOOKED.h $ph -")
        passes[6].body = replaceAllChecked(passes[6].body, "SUBF_tex(", "SUBT_tex(", "SUBF_tex")
        passes[6].body = replaceAllChecked(
            passes[6].body, "* TEX_GAIN;", "* TEX_GAIN * (1.0 - SUBT_tex(p).a);", "TEX_GAIN"
        )
        // 8: 최종 혼합 - 원본 크기(띠를 잘라낸 크기)로 출력
        passes[7].header.add("//!WIDTH HOOKED.w")
        passes[7].header.add("//!HEIGHT HOOKED.h $ph -")

        for (p in passes) p.body = stc + "\n" + p.body

        val validFn = "bool refValid(int k) {\n    vec4 v = SUBV_tex(vec2(0.5, 0.5));\n    return v[k] > 0.5;\n}\n"
        val temporalConsts = """
const float RING_PX = ${f(SubtitleStLayout.RING_PX_1080, 1)};   // ring radius (1080p px) used to compare the surroundings
const float RING_R0 = 24.0;      // ring weight falls to 1/2 at this distance (1080p px)
const float RING_MINW = 1.0;     // total ring weight needed before a reference can be trusted
const float SIG_RING = 0.0025;   // squared color error at which a reference's ring match weight is e^-1
const float SIG_PLAUS = 0.06;    // squared distance to the spatial fill at which plausibility is e^-1
const float TEMPORAL_MIX = 0.95; // max share of the temporal result in the final fill
"""

        val subv = """//!HOOK MAIN
//!BIND HOOKED
//!SAVE SUBV
//!WIDTH 1
//!HEIGHT 1
//!DESC subtitle erase ST: reference validity

$stc
vec4 hook() {
    vec4 v = vec4(0.0);
    for (int k = 0; k < 4; k++) {
        v[k] = stripLooksValid(k) ? 1.0 : 0.0;
    }
    return v;
}
"""

        val subx = """//!HOOK MAIN
//!BIND HOOKED
//!BIND SUBE
//!BIND SUBV
//!SAVE SUBX
//!WIDTH HOOKED.w 2 /
//!HEIGHT HOOKED.h $ph - 2 /
//!DESC subtitle erase ST 1/3: per-reference difference outside the hole

$stc
$common
$validFn
vec4 hook() {
    vec2 p = HOOKED_pos;
    if (!inHist(p)) return vec4(0.0);
    if (SUBE_tex(p).r > 0.5) return vec4(0.0);
    vec3 c = mainTex(p).rgb;
    vec4 d = vec4(1.0);
    for (int k = 0; k < 4; k++) {
        if (refValid(k)) {
            vec3 df = c - refTex(k, p);
            d[k] = min(dot(df, df), 1.0);
        }
    }
    return d;
}
"""

        val subr = """//!HOOK MAIN
//!BIND HOOKED
//!BIND SUBE
//!BIND SUBX
//!SAVE SUBR
//!WIDTH HOOKED.w 2 /
//!HEIGHT HOOKED.h $ph - 2 /
//!DESC subtitle erase ST 2/3: ring consistency around the hole

$stc
$common
$temporalConsts
// For every hole pixel: how well does each reference frame agree with the current frame on the real
// (non-hole) pixels around it?  Fixed spiral taps (no jitter), weighted 1/(1+(r/R0)^2), so the field is smooth.
vec4 hook() {
    vec2 p = HOOKED_pos;
    if (!inHist(p)) return vec4(9.0);
    if (SUBE_tex(p).r < 0.5) return vec4(9.0);
    float s = MAIN_SIZE.y / 1080.0;
    vec4 acc = vec4(0.0);
    float ws = 0.0;
    for (int i = 0; i < 40; i++) {
        float fr = (float(i) + 0.5) / 40.0;
        float rad = RING_PX * s * sqrt(fr);
        float ang = 2.3999632 * float(i);
        vec2 q = p + vec2(cos(ang), sin(ang)) * rad * MAIN_PT;
        if (!inHist(q)) continue;
        if (SUBE_tex(q).r > 0.5) continue;
        float rr = rad / (RING_R0 * s);
        float w = 1.0 / (1.0 + rr * rr);
        acc += SUBX_tex(q) * w;
        ws += w;
    }
    if (ws < RING_MINW) return vec4(9.0);
    return acc / ws;
}
"""

        val subt = """//!HOOK MAIN
//!BIND HOOKED
//!BIND SUBE
//!BIND SUBG
//!BIND SUBF
//!BIND SUBR
//!BIND SUBV
//!SAVE SUBT
//!WIDTH HOOKED.w
//!HEIGHT HOOKED.h $ph -
//!DESC subtitle erase ST 3/3: temporal blend (alpha = temporal confidence)

$stc
$common
$temporalConsts
$validFn
// Candidate k = the same pixel in the frame D_k seconds ago.  Its weight is the product of
//   ring     : the surroundings of the hole agree with the current frame (static background)
//   plaus    : the candidate is close to the spatial fill (rejects text that was already there)
//   not text : a bright neutral pixel on a detected stroke is probably the same subtitle again
// Confidence = 1 - prod(1 - w_k): any one good candidate is enough, all bad ones leave the spatial fill alone.
vec4 hook() {
    vec2 p = HOOKED_pos;
    vec3 F = SUBF_tex(p).rgb;
    if (!inHist(p)) return vec4(F, 0.0);
    if (SUBE_tex(p).r < 0.5) return vec4(F, 0.0);
    vec4 G = SUBR_tex(p);
    float onText = SUBG_tex(p).r;
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    float miss = 1.0;
    for (int k = 0; k < 4; k++) {
        if (!refValid(k)) continue;
        vec3 r = refTex(k, p);
        float ring = exp(-G[k] / SIG_RING);
        vec3 df = r - F;
        float plaus = exp(-dot(df, df) / SIG_PLAUS);
        float tl = smoothstep(FILL_LUM - 0.06, FILL_LUM + 0.04, lum(r))
                 * (1.0 - smoothstep(FILL_CHROMA * 0.7, FILL_CHROMA * 1.3, chroma(r)));
        float w = ring * plaus * (1.0 - 0.9 * tl * onText);
        acc += r * w;
        wsum += w;
        miss *= (1.0 - w);
    }
    float conf = (1.0 - miss) * TEMPORAL_MIX;
    vec3 T = (wsum > 0.0001) ? acc / wsum : F;
    return vec4(clamp(mix(F, T, conf), 0.0, 1.0), conf);
}
"""

        val ordered = ArrayList<String>()
        ordered += subv
        for (i in 0..4) ordered += passes[i].render()
        ordered += passes[5].render()
        ordered += subx
        ordered += subr
        ordered += subt
        ordered += passes[6].render()
        ordered += passes[7].render()
        return ordered.joinToString("\n")
    }

    /** 모든 패스가 공유하는 레이아웃 상수와 도우미. (원본 영역 좌표 <-> 쌓은 프레임 좌표) */
    private fun stCommon(L: SubtitleStLayout): String {
        val sb = StringBuilder()
        sb.append("const vec2 MAIN_SIZE = vec2(${f(L.frameW.toDouble(), 1)}, ${f(L.frameH.toDouble(), 1)});\n")
        sb.append("const vec2 MAIN_PT = vec2(${f(1.0 / L.frameW, 10)}, ${f(1.0 / L.frameH, 10)});\n")
        sb.append("const float MAIN_RH = ${f(L.frameH.toDouble() / L.packedH, 10)};\n")
        sb.append("const vec2 PK_SIZE = vec2(${f(L.frameW.toDouble(), 1)}, ${f(L.packedH.toDouble(), 1)});\n")
        sb.append("const vec2 HB_ORIGIN = vec2(${f(L.bandX.toDouble(), 1)}, ${f(L.bandY.toDouble(), 1)});\n")
        sb.append("const vec2 HB_SIZE = vec2(${f(L.bandW.toDouble(), 1)}, ${f(L.bandH.toDouble(), 1)});\n")
        sb.append("const vec2 STRIP_SIZE = vec2(${f(L.stripW.toDouble(), 1)}, ${f(L.stripH.toDouble(), 1)});\n")
        sb.append("vec4 mainTex(vec2 m) { return HOOKED_tex(vec2(m.x, m.y * MAIN_RH)); }\n")
        sb.append(
            "bool inHist(vec2 m) {\n" +
                "    vec2 q = m * MAIN_SIZE - HB_ORIGIN;\n" +
                "    return q.x >= 0.0 && q.y >= 0.0 && q.x <= HB_SIZE.x && q.y <= HB_SIZE.y;\n" +
                "}\n"
        )
        sb.append("vec2 stripOrigin(int k) {\n")
        for (k in 0 until SubtitleStLayout.REFS - 1) {
            sb.append("    if (k == $k) return vec2(${f(L.stripX(k).toDouble(), 1)}, ${f(L.stripY(k).toDouble(), 1)});\n")
        }
        val last = SubtitleStLayout.REFS - 1
        sb.append("    return vec2(${f(L.stripX(last).toDouble(), 1)}, ${f(L.stripY(last).toDouble(), 1)});\n}\n")
        sb.append(
            // 원본 좌표 m 이 가리키는 지연선 띠 k 안의 픽셀 위치 (띠 가장자리 밖으로 번지지 않게 0.5px 안쪽으로 가둔다)
            "vec2 refPx(int k, vec2 m) {\n" +
                "    vec2 u = clamp((m * MAIN_SIZE - HB_ORIGIN) / HB_SIZE, 0.0, 1.0);\n" +
                "    vec2 o = stripOrigin(k);\n" +
                "    return clamp(o + u * STRIP_SIZE, o + 0.5, o + STRIP_SIZE - 0.5);\n" +
                "}\n" +
                "vec3 refTex(int k, vec2 m) { return HOOKED_tex(refPx(k, m) / PK_SIZE).rgb; }\n" +
                // 지연선이 아직 안 찼으면 띠 자리에 초록색 패드가 그대로 보인다. 네 모서리가 모두 초록이면 무효.
                "bool stripLooksValid(int k) {\n" +
                "    vec2 o = stripOrigin(k);\n" +
                "    int bad = 0;\n" +
                "    for (int i = 0; i < 4; i++) {\n" +
                "        vec2 q = o + STRIP_SIZE * vec2((i % 2 == 0) ? 0.2 : 0.8, (i < 2) ? 0.2 : 0.8);\n" +
                "        vec3 c = HOOKED_tex(q / PK_SIZE).rgb;\n" +
                "        if (c.g > 0.55 && c.r < 0.25 && c.b < 0.25) bad++;\n" +
                "    }\n" +
                "    return bad < 4;\n" +
                "}\n"
        )
        return sb.toString()
    }
}
