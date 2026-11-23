package com.lilac.anime.player

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 하드섭 자막 시공간 인페인팅 (신경망 없음, 순수 수학 연산).
 *
 * mpv 의 GLSL 셰이더는 "지금 프레임"밖에 볼 수 없다. 그래서 두 단계로 나눈다.
 *
 *  1) [buildVf]  libavfilter(lavfi) 그래프: 자막 띠(band)만 잘라 시간차(-과거 ... +미래)를 둔 복사본을
 *                원본 프레임 아래에 세로로 이어 붙인 "키 큰 프레임"을 만든다. (재생 시각은 그대로, 미래 프레임은
 *                필터가 먼저 읽어 두므로 A/V 싱크는 어긋나지 않는다. 아직 없는 프레임 자리는 마젠타.)
 *  2) [buildShader] GLSL: 키 큰 프레임 안에서
 *       a. 모든 띠(현재+과거/미래)에서 글자 픽셀을 검출 (밝은 획이 어두운 윤곽에 막힌 구간)
 *       b. 띠 바깥의 "안전 구간"(자막 영역 밖 행/열) 투영 프로파일로 카메라 팬(평행이동)을 서브픽셀로 추정
 *       c. 구멍(글자 픽셀)마다 모든 참조 프레임에서 모션 보정한 후보를 모아, 후보가 깨끗한지 / 주변 문맥이
 *          현재 프레임과 일치하는지 / 시간이 가까운지 / 자막이 아예 없던 프레임인지로 가중 평균 + 저주파 밝기 보정
 *       d. 믿을 만한 후보가 없는 곳만 공간(방향성) 보간으로 메움
 *       e. 원본 크기로 잘라 출력 (마지막 패스에서 크기를 되돌림)
 */
object SubtitleTemporalInpaint {

    /**
     * @param patchIters 시공간 패치 매칭(PatchMatch) 반복 횟수. 0 이면 패치 채우기를 끄고 모션 보정 평균만 쓴다.
     * 짧은 간격 슬롯은 "캐릭터가 움직이는 장면"용, 긴 간격 슬롯은 "정지 장면"(자막 큐 시작 전/끝난 뒤의 깨끗한 프레임)용이다.
     */
    enum class Preset(val pastSec: FloatArray, val futureSec: FloatArray, val patchIters: Int) {
        /** 메모리/연산 최소. 패치 채우기 없음 */
        LIGHT(floatArrayOf(0.25f, 1.5f), floatArrayOf(0.25f), 0),
        /** 기본. 과거 4 (최대 2.5초) + 미래 3 (최대 1초) */
        BALANCED(floatArrayOf(0.12f, 0.4f, 1.0f, 2.5f), floatArrayOf(0.12f, 0.4f, 1.0f), 2),
        /** 과거 6 (최대 6초) + 미래 4 (최대 1.5초). 미래 프레임 버퍼가 커진다. */
        QUALITY(floatArrayOf(0.1f, 0.3f, 0.7f, 1.5f, 3.0f, 6.0f), floatArrayOf(0.1f, 0.3f, 0.7f, 1.5f), 3)
    }

    /** 키 큰 프레임 한 변의 상한 (GLES 최소 보장은 2048 이지만 거의 모든 기기가 4096 이상). */
    private const val MAX_TEX = 4096

    class Plan(
        val w: Int, val h: Int,
        val y0: Int, val hb: Int,
        /** 프레임 단위 상대 시각. 음수 = 과거, 양수 = 미래. 슬롯 i(0부터)가 셰이더의 슬롯 i+1. */
        val offsets: IntArray,
        val fps: Double,
        val rects: List<WatermarkRect>,
        val patchIters: Int = 2
    ) {
        val slots: Int get() = offsets.size
        val tallH: Int get() = h + slots * hb
        val sarScale: Double get() = tallH.toDouble() / h
        /** 이 값이 같으면 vf/셰이더를 다시 만들 필요가 없다. */
        val key: String
            get() = "$w x $h @${"%.3f".format(Locale.US, fps)} y0=$y0 hb=$hb o=${offsets.joinToString(",")} pm=$patchIters " +
                rects.joinToString(";") { "%.4f,%.4f,%.4f,%.4f".format(Locale.US, it.x, it.y, it.w, it.h) }
    }

    /**
     * 영상 크기/프레임레이트/자막 영역으로 띠와 참조 프레임 시각을 정한다.
     * 지원하지 않는 조건(홀수 크기, 너무 큼 등)이면 null → 호출하는 쪽에서 기존 공간 방식으로 되돌린다.
     */
    fun plan(w: Int, h: Int, fps: Double, rects: List<WatermarkRect>, preset: Preset = Preset.BALANCED): Plan? {
        if (w < 64 || h < 64 || w % 2 != 0 || h % 2 != 0 || rects.isEmpty()) return null
        val f = if (fps in 5.0..120.0) fps else 24.0
        val s = max(h / 1080.0, 0.5)
        val yMin = rects.minOf { it.y } * h
        val yMax = rects.maxOf { it.y + it.h } * h
        // 띠 = 자막 영역 + 위아래 여유. 여유 행은 "글자가 절대 없는 안전 구간"으로 카메라 모션 추정에 쓴다.
        val pad = max(0.4 * (yMax - yMin), 20.0 * s)
        var y0 = floor(yMin - pad).toInt().coerceAtLeast(0)
        y0 -= y0 % 2
        var y1 = ceil(yMax + pad).toInt().coerceAtMost(h)
        y1 -= y1 % 2
        val hb = y1 - y0
        if (hb < 32) return null

        val frames = ArrayList<Int>()
        for (sec in preset.pastSec) frames.add(-max(1, (sec * f).roundToInt()))
        for (sec in preset.futureSec) frames.add(max(1, (sec * f).roundToInt()))
        val offs = frames.distinct().toMutableList()
        // 키 큰 프레임이 너무 크면 가장 먼 슬롯부터 뺀다.
        while (offs.size >= 2 && h + offs.size * hb > MAX_TEX) {
            offs.remove(offs.maxByOrNull { abs(it) })
        }
        if (offs.size < 2 || h + offs.size * hb > MAX_TEX) return null
        return Plan(w, h, y0, hb, offs.toIntArray(), f, rects, preset.patchIters)
    }

    // ------------------------------------------------------------------ lavfi

    /**
     * `vf add @label:lavfi=[이 문자열]` 로 쓴다 (입출력 패드는 이름 없이 첫 입력/마지막 출력).
     *
     *  - 슬롯 i 는 "현재 프레임 + offsets[i] 프레임"의 띠. setpts 로 시간을 밀고 overlay 가 같은 pts 끼리 짝지음.
     *  - +0.25 프레임 여유: 프레임 pts 반올림 오차로 한 프레임씩 어긋나는 것을 막는다.
     *  - copy: 띠만 새 버퍼로 복사해 과거 프레임 전체를 메모리에 붙잡지 않게 한다.
     *  - 마지막 setsar 로 화면 비율(DAR)을 원래대로 유지한다.
     */
    fun buildVf(p: Plan): String {
        val dt = 1.0 / p.fps
        val n = p.slots
        val sb = StringBuilder()
        sb.append("format=yuv420p,split=2[m][bw];")
        sb.append(String.format(Locale.US, "[m]pad=%d:%d:0:0:color=0xFF00FF[c0];", p.w, p.tallH))
        sb.append(String.format(Locale.US, "[bw]crop=%d:%d:0:%d,copy,split=%d", p.w, p.hb, p.y0, n))
        for (i in 0 until n) sb.append("[b$i]")
        sb.append(';')
        for (i in 0 until n) {
            val shift = -(p.offsets[i] * dt) + 0.25 * dt
            sb.append(String.format(Locale.US, "[b%d]setpts=PTS%+.6f/TB[s%d];", i, shift, i))
        }
        for (i in 0 until n) {
            sb.append(String.format(Locale.US, "[c%d][s%d]overlay=x=0:y=%d:format=yuv420:eof_action=repeat:repeatlast=1", i, i, p.h + i * p.hb))
            if (i < n - 1) sb.append(String.format(Locale.US, "[c%d];", i + 1))
            else sb.append(String.format(Locale.US, ",setsar=sar=sar*%.8f", p.sarScale))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ GLSL

    private fun f(v: Float) = String.format(Locale.US, "%.5f", v)

    private fun rectFn(name: String, rects: List<WatermarkRect>, padX: Float, padY: Float): String {
        val body = rects.joinToString(" || ") {
            "(p.x >= ${f(it.x - padX)} && p.x <= ${f(it.x + it.w + padX)} && p.y >= ${f(it.y - padY)} && p.y <= ${f(it.y + it.h + padY)})"
        }.ifEmpty { "false" }
        return "bool $name(vec2 p) {\n    return $body;\n}\n"
    }

    /**
     * @param style SubtitleStyleLearner 가 학습한 스타일 (없으면 밝은 무채색 = 자막 채움)
     * @param strictOutline true: 획의 한쪽 끝은 반드시 어두운 윤곽에 닿아야 글자로 인정 (오검출 적음)
     * @param debug true: 지우는 대신 지워질 곳(빨강)을 표시
     */
    fun buildShader(p: Plan, style: SubtitleStyle? = null, strictOutline: Boolean = true, debug: Boolean = false): String {
        val w = p.w; val h = p.h; val hb = p.hb; val n = p.slots; val nsl = n + 1
        val wh = w / 2; val hbh = hb / 2
        val sc = max(h / 1080f, 0.5f)
        val rx = (120 * sc).roundToInt().coerceIn(24, 200)
        val ry = (10 * sc).roundToInt().coerceIn(2, 24)
        val kw = 2 * rx + 1
        val rm = ceil((style?.rayMax ?: 10f) * sc).toInt().coerceIn(4, 16)
        val rd = max(2, (2 * sc).roundToInt())
        val tmkH = nsl * hbh

        val padRect = 0.012f
        // 자막 영역의 y 범위 / x 범위 (안전 구간 판정용). 사각형 전체 합집합이 아니라 각각의 범위의 합집합.
        val yRanges = p.rects.joinToString(" || ") { "(ny >= ${f(it.y - padRect)} && ny <= ${f(it.y + it.h + padRect)})" }.ifEmpty { "false" }
        val xRanges = p.rects.joinToString(" || ") { "(nx >= ${f(it.x - padRect)} && nx <= ${f(it.x + it.w + padRect)})" }.ifEmpty { "false" }

        val rgbPick = style?.fill
        val pickDecl = if (rgbPick != null) {
            "const bool USE_PICK = true;\nconst vec3 PICK_RGB = vec3(${f(rgbPick[0])}, ${f(rgbPick[1])}, ${f(rgbPick[2])});\nconst float PICK_TOL = ${f(style!!.pickTolerance)};\n"
        } else "const bool USE_PICK = false;\nconst vec3 PICK_RGB = vec3(1.0);\nconst float PICK_TOL = 0.14;\n"

        val slotOffFn = StringBuilder("float slotSec(int s) {\n")
        for (i in 0 until n) slotOffFn.append("    if (s == ${i + 1}) return ${f(abs(p.offsets[i]) / p.fps.toFloat())};\n")
        slotOffFn.append("    return 0.0;\n}\n")

        val common = rectFn("inBandEx", p.rects, 0.02f, 0.03f) + """
const int W = $w;
const int H = $h;
const int Y0 = ${p.y0};
const int HB = $hb;
const int NS = $n;
const int NSL = $nsl;
const int WH = $wh;
const int HBH = $hbh;
const int RX = $rx;
const int RY = $ry;
const int KW = $kw;
const int RM = $rm;      // max stroke half-width (half-res texels)
const int CAPT = 2;      // outline must start within this many texels after the stroke ends
const int GR = 2;        // outline grow radius (texels)
const int RD = $rd;      // hole dilate radius (texels)
const float FW = float(W);
const float FH = float(H);
const float FHB = float(HB);
const vec2 TS = vec2(float(W), float(H + NS * HB));
const vec2 TMK_SZ = vec2(float(WH), float(NSL * HBH));
const vec2 TPR_SZ = vec2(float(W), float(2 * NSL));
const vec2 TSC_SZ = vec2(float(KW), float(2 * NSL));
const float R1 = ${f(7f * sc)};
const float R2 = ${f(14f * sc)};
const float FILL_LUM = 0.66;
const float FILL_CHROMA = 0.18;
const float EDGE_LUM = ${f(style?.edgeLum ?: 0.28f)};
const float EDGE_CHROMA = ${f(style?.edgeChroma ?: 0.20f)};
const bool NEED_OUTLINE = ${if (strictOutline) "true" else "false"};
const bool PATCH = ${if (p.patchIters > 0) "true" else "false"};
const int NPR = 2;       // patch radius (half-res texels)
const int MINTAPS = 7;   // min. known texels shared by target and source patch
const bool DEBUG_MASK = ${if (debug) "true" else "false"};
$pickDecl
float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
float chroma(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }
bool guardRow(float y) { float ny = (float(Y0) + y + 0.5) / FH; return !($yRanges); }
bool guardCol(float x) { float nx = (x + 0.5) / FW; return !($xRanges); }
$slotOffFn
"""
        // HOOKED(키 큰 프레임)에서 띠 픽셀을 읽는 도우미. index 좌표: 정수 = 텍셀 중심.
        val hookedHelpers = """
vec3 tallRGB(vec2 q) { return HOOKED_tex((q + 0.5) / TS).rgb; }
float slotY(int s) { return (s == 0) ? float(Y0) : float(H + (s - 1) * HB); }
vec3 bandRGB(int s, vec2 q) {
    q = clamp(q, vec2(0.0), vec2(FW - 1.0, FHB - 1.0));
    return tallRGB(vec2(q.x, slotY(s) + q.y));
}
"""
        // 구멍 마스크(TMH, 반해상도, 슬롯을 세로로 쌓음)를 전해상도 좌표로 읽는 도우미.
        val tmhHelpers = """
float holeLin(int s, vec2 q) {
    vec2 hq = (q - 0.5) * 0.5;
    hq = clamp(hq, vec2(0.0), vec2(float(WH - 1), float(HBH - 1)));
    return TMH_tex((hq + 0.5 + vec2(0.0, float(s * HBH))) / TMK_SZ).r;
}
float holeNN(int s, vec2 q) {
    vec2 hq = clamp(floor(q * 0.5), vec2(0.0), vec2(float(WH - 1), float(HBH - 1)));
    return TMH_tex((hq + 0.5 + vec2(0.0, float(s * HBH))) / TMK_SZ).r;
}
float zoneA(vec2 q) {
    float a = holeLin(0, q);
    a += holeLin(0, q + vec2(3.0, 0.0)) + holeLin(0, q - vec2(3.0, 0.0));
    a += holeLin(0, q + vec2(0.0, 3.0)) + holeLin(0, q - vec2(0.0, 3.0));
    return smoothstep(0.3, 0.8, a * 0.2);
}
float holeFeather(vec2 q) {
    float a = holeLin(0, q);
    a += holeLin(0, q + vec2(3.0, 0.0)) + holeLin(0, q - vec2(3.0, 0.0));
    a += holeLin(0, q + vec2(0.0, 3.0)) + holeLin(0, q - vec2(0.0, 3.0));
    return clamp(a * 0.2 * 1.25, 0.0, 1.0);
}
"""
        val hdr = { name: String, binds: String, save: String?, ww: Int, hh: Int, desc: String ->
            buildString {
                append("//!HOOK MAIN\n//!BIND HOOKED\n")
                append(binds)
                if (save != null) append("//!SAVE $save\n")
                append("//!WIDTH $ww\n//!HEIGHT $hh\n")
                append("//!DESC subtitle temporal $desc\n\n")
            }
        }

        val tmkSample = """
vec2 tmkAt(int s, ivec2 q) {
    if (q.x < 0 || q.x >= WH || q.y < 0 || q.y >= HBH) return vec2(-1.0);
    return TMK_tex((vec2(q) + 0.5 + vec2(0.0, float(s * HBH))) / TMK_SZ).rg;
}
"""
        val tmsSample = """
float tmsAt(int s, ivec2 q) {
    if (q.x < 0 || q.x >= WH || q.y < 0 || q.y >= HBH) return 0.0;
    return TMS_tex((vec2(q) + 0.5 + vec2(0.0, float(s * HBH))) / TMK_SZ).r;
}
"""
        val tmgSample = """
float tmgAt(int s, ivec2 q) {
    if (q.x < 0 || q.x >= WH || q.y < 0 || q.y >= HBH) return 0.0;
    return TMG_tex((vec2(q) + 0.5 + vec2(0.0, float(s * HBH))) / TMK_SZ).r;
}
"""
        val tmxSample = """
float tmxAt(int s, ivec2 q) {
    if (q.x < 0 || q.x >= WH || q.y < 0 || q.y >= HBH) return 0.0;
    return TMX_tex((vec2(q) + 0.5 + vec2(0.0, float(s * HBH))) / TMK_SZ).r;
}
"""
        val frag = "ivec2 ip = ivec2(floor(HOOKED_pos * TMK_SZ));\n    int s = ip.y / HBH;\n    if (s >= NSL) return vec4(0.0, 0.0, 0.0, 1.0);\n    ivec2 p = ivec2(ip.x, ip.y - s * HBH);\n"

        val sb = StringBuilder()

        // 1) classify
        sb.append(hdr("", "", "TMK", wh, tmkH, "1/11 classify"))
        sb.append(common).append(hookedHelpers).append("""
vec2 classify(vec3 c) {
    float l = lum(c);
    float ch = chroma(c);
    float fl = (l > FILL_LUM && ch < FILL_CHROMA) ? 1.0 : 0.0;
    if (USE_PICK) {
        vec3 dc = c - PICK_RGB;
        fl = (dot(dc, dc) < PICK_TOL * PICK_TOL) ? 1.0 : 0.0;
    }
    float o = (l < EDGE_LUM && ch < EDGE_CHROMA) ? 1.0 : 0.0;
    return vec2(fl, o);
}

vec4 hook() {
    $frag
    vec2 b = vec2(float(p.x * 2), float(p.y * 2));
    vec2 nrm = vec2((b.x + 1.0) / FW, (float(Y0) + b.y + 1.0) / FH);
    if (!inBandEx(nrm)) return vec4(0.0, 0.0, 0.0, 1.0);
    vec2 v = vec2(0.0);
    for (int j = 0; j < 4; j++) {
        int jy = j / 2;
        int jx = j - 2 * jy;
        v = max(v, classify(bandRGB(s, b + vec2(float(jx), float(jy)))));
    }
    return vec4(v, 0.0, 1.0);
}

""")

        // 2) strokes: bright run bounded on both sides, capped by an outline
        sb.append(hdr("", "//!BIND TMK\n", "TMS", wh, tmkH, "2/11 strokes"))
        sb.append(common).append(tmkSample).append("""
float runEnds(int s, ivec2 p, ivec2 d) {
    for (int k = 1; k <= 16; k++) {
        if (k > RM) break;
        vec2 q = tmkAt(s, p + d * k);
        if (q.x < -0.5) return 0.0;
        if (q.x < 0.5) {
            for (int m = 0; m < CAPT; m++) {
                vec2 q2 = tmkAt(s, p + d * (k + m));
                if (q2.y > 0.5) return 2.0;
            }
            return 1.0;
        }
    }
    return 0.0;
}

vec4 hook() {
    $frag
    vec2 c = tmkAt(s, p);
    if (c.x < 0.5) return vec4(0.0, 0.0, 0.0, 1.0);
    float t = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 d = (i == 0) ? ivec2(1, 0) : ((i == 1) ? ivec2(0, 1) : ((i == 2) ? ivec2(1, 1) : ivec2(1, -1)));
        float e1 = runEnds(s, p, d);
        if (e1 < 0.5) continue;
        float e2 = runEnds(s, p, -d);
        if (e2 > 0.5 && (!NEED_OUTLINE || max(e1, e2) > 1.5)) { t = 1.0; break; }
    }
    return vec4(t, 0.0, 0.0, 1.0);
}

""")

        // 3) grow onto outline pixels next to strokes
        sb.append(hdr("", "//!BIND TMK\n//!BIND TMS\n", "TMG", wh, tmkH, "3/11 grow"))
        sb.append(common).append(tmkSample).append(tmsSample).append("""
vec4 hook() {
    $frag
    if (tmsAt(s, p) > 0.5) return vec4(1.0, 0.0, 0.0, 1.0);
    if (tmkAt(s, p).y < 0.5) return vec4(0.0, 0.0, 0.0, 1.0);
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) continue;
            for (int r = 1; r <= GR; r++) {
                if (tmsAt(s, p + ivec2(dx, dy) * r) > 0.5) return vec4(1.0, 0.0, 0.0, 1.0);
            }
        }
    }
    return vec4(0.0, 0.0, 0.0, 1.0);
}

""")

        // 4) dilate x
        sb.append(hdr("", "//!BIND TMG\n", "TMX", wh, tmkH, "4/11 dilate x"))
        sb.append(common).append(tmgSample).append("""
vec4 hook() {
    $frag
    float m = 0.0;
    for (int dx = -RD; dx <= RD; dx++) m = max(m, tmgAt(s, p + ivec2(dx, 0)));
    return vec4(m, 0.0, 0.0, 1.0);
}

""")

        // 5) dilate y -> hole mask
        sb.append(hdr("", "//!BIND TMX\n", "TMH", wh, tmkH, "5/11 dilate y (hole)"))
        sb.append(common).append(tmxSample).append("""
vec4 hook() {
    $frag
    float m = 0.0;
    for (int dy = -RD; dy <= RD; dy++) m = max(m, tmxAt(s, p + ivec2(0, dy)));
    return vec4(m, 0.0, 0.0, 1.0);
}

""")

        // 6) projection profiles from guard rows / columns
        sb.append(hdr("", "", "TPR", w, 2 * nsl, "6/11 profiles"))
        sb.append(common).append(hookedHelpers).append("""
vec4 hook() {
    ivec2 ip = ivec2(floor(HOOKED_pos * TPR_SZ));
    float sum = 0.0;
    float cnt = 0.0;
    if (ip.y < NSL) {
        int s = ip.y;
        for (int y = 0; y < HB; y++) {
            if (!guardRow(float(y))) continue;
            sum += lum(bandRGB(s, vec2(float(ip.x), float(y))));
            cnt += 1.0;
        }
        return vec4(cnt > 0.0 ? sum / cnt : 0.0, cnt >= 6.0 ? 1.0 : 0.0, 0.0, 1.0);
    }
    int s2 = ip.y - NSL;
    if (ip.x >= HB) return vec4(0.0, 0.0, 0.0, 1.0);
    for (int x = 0; x < W; x += 2) {
        if (!guardCol(float(x))) continue;
        sum += lum(bandRGB(s2, vec2(float(x), float(ip.x))));
        cnt += 1.0;
    }
    return vec4(cnt > 0.0 ? sum / cnt : 0.0, cnt >= 16.0 ? 1.0 : 0.0, 0.0, 1.0);
}

""")

        // 7) shift scores
        sb.append(hdr("", "//!BIND TPR\n", "TSC", kw, 2 * nsl, "7/11 shift scores"))
        sb.append(common).append("""
vec2 tprAt(int x, int r) { return TPR_tex((vec2(float(x), float(r)) + 0.5) / TPR_SZ).rg; }

vec4 hook() {
    ivec2 ip = ivec2(floor(HOOKED_pos * TSC_SZ));
    int row = ip.y;
    int k = ip.x - RX;
    if (row == 0 || row == NSL) return vec4(9.0, 0.0, 0.0, 1.0);
    float acc = 0.0;
    float cnt = 0.0;
    if (row < NSL) {
        for (int x = 0; x < W; x++) {
            int xs = x + k;
            if (xs < 0 || xs >= W) continue;
            vec2 a = tprAt(x, 0);
            vec2 b = tprAt(xs, row);
            if (a.y > 0.5 && b.y > 0.5) { acc += abs(a.x - b.x); cnt += 1.0; }
        }
        float sc = (cnt > 0.15 * FW) ? acc / cnt + 0.0002 * abs(float(k)) : 9.0;
        return vec4(sc, 0.0, 0.0, 1.0);
    }
    if (abs(k) > RY) return vec4(9.0, 0.0, 0.0, 1.0);
    for (int y = 0; y < HB; y++) {
        int ys = y + k;
        if (ys < 0 || ys >= HB) continue;
        vec2 a = tprAt(y, NSL);
        vec2 b = tprAt(ys, row);
        if (a.y > 0.5 && b.y > 0.5) { acc += abs(a.x - b.x); cnt += 1.0; }
    }
    float sc2 = (cnt > 0.15 * FHB) ? acc / cnt + 0.0004 * abs(float(k)) : 9.0;
    return vec4(sc2, 0.0, 0.0, 1.0);
}

""")

        // 8) per-slot motion + confidence + clean fraction
        sb.append(hdr("", "//!BIND TSC\n//!BIND TMH\n", "TMV", nsl, 1, "8/11 motion"))
        sb.append(common).append(hookedHelpers).append(tmhHelpers).append("""
float scAt(int row, int k) { return TSC_tex((vec2(float(k), float(row)) + 0.5) / TSC_SZ).r; }

bool slotInvalid(int s) {
    vec3 c = vec3(0.0);
    for (int i = 0; i < 5; i++) {
        c += bandRGB(s, vec2((float(i) + 0.5) / 5.0 * FW, FHB * 0.5));
    }
    c *= 0.2;
    return (c.r > 0.7 && c.b > 0.7 && c.g < 0.35);
}

// best shift over row `row` (k index range [k0, k1]); zero-shift preferred unless clearly better; sub-texel parabola
vec2 bestShift(int row, int k0, int k1) {
    float b = 9.0;
    int kb = RX;
    for (int k = k0; k <= k1; k++) {
        float v = scAt(row, k);
        if (v < b) { b = v; kb = k; }
    }
    float s0 = scAt(row, RX);
    if (kb != RX && b > 0.7 * s0 - 0.001) { kb = RX; b = s0; }
    float fr = 0.0;
    if (b < 8.0 && kb > k0 && kb < k1) {
        float a = scAt(row, kb - 1);
        float c = scAt(row, kb + 1);
        if (a < 8.0 && c < 8.0) {
            float den = a - 2.0 * b + c;
            if (den > 1e-6) fr = clamp(0.5 * (a - c) / den, -0.5, 0.5);
        }
    }
    return vec2(b < 8.0 ? float(kb - RX) + fr : 0.0, b);
}

vec4 hook() {
    int s = int(floor(HOOKED_pos.x * float(NSL)));
    if (s <= 0) return vec4(0.0, 0.0, 1.0, 1.0);
    if (slotInvalid(s)) return vec4(0.0, 0.0, 0.0, -1.0);
    vec2 bx = bestShift(s, 0, KW - 1);
    vec2 by = bestShift(NSL + s, RX - RY, RX + RY);
    float conf;
    if (bx.y >= 8.0) {
        conf = 0.4;                      // not enough guard rows: assume a static background
    } else {
        float resid = bx.y;
        if (by.y < 8.0) resid = max(resid, by.y);
        conf = 1.0 - smoothstep(0.02, 0.08, resid);
    }
    float hole = 0.0;
    for (int j = 0; j < 6; j++) {
        for (int i = 0; i < 12; i++) {
            vec2 q = vec2((float(i) + 0.5) / 12.0 * FW, (float(j) + 0.5) / 6.0 * FHB);
            hole += holeNN(s, q);
        }
    }
    float cleanFrac = 1.0 - hole / 72.0;
    return vec4(bx.x, by.y < 8.0 ? by.x : 0.0, conf, cleanFrac);
}

""")


        // 9) half-res colour stack (+ "known" flag) for patch matching
        sb.append(hdr("", "//!BIND TMH\n//!BIND TMV\n", "TLR", wh, tmkH, "9 half-res colours"))
        sb.append(common).append(hookedHelpers).append("""
vec4 hook() {
    $frag
    vec4 mv = TMV_tex((vec2(float(s), 0.0) + 0.5) / vec2(float(NSL), 1.0));
    if (s > 0 && mv.w < -0.5) return vec4(0.0);
    float hole = TMH_tex((vec2(p) + 0.5 + vec2(0.0, float(s * HBH))) / TMK_SZ).r;
    vec2 b = vec2(float(p.x * 2), float(p.y * 2));
    vec3 c = bandRGB(s, b) + bandRGB(s, b + vec2(1.0, 0.0)) + bandRGB(s, b + vec2(0.0, 1.0)) + bandRGB(s, b + vec2(1.0, 1.0));
    return vec4(c * 0.25, hole < 0.5 ? 1.0 : 0.0);
}

""")
        if (p.patchIters > 0) {
            val nnCommon = """
vec4 tlrAt(int x, int y) { return TLR_tex((vec2(float(x), float(y)) + 0.5) / TMK_SZ); }
float tmhAt(ivec2 t) { return TMH_tex((vec2(t) + 0.5) / TMK_SZ).r; }
float hash21(vec2 q) {
    vec3 p3 = fract(vec3(q.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
// mean squared colour difference between the known texels around target t (slot 0) and source q (any slot)
float patchCost(ivec2 t, ivec2 q, out float delta) {
    delta = 0.0;
    if (q.x < 0 || q.x >= WH || q.y < 0 || q.y >= NSL * HBH) return 9.0;
    int sl = q.y / HBH;
    int qly = q.y - sl * HBH;
    if (tlrAt(q.x, q.y).a < 0.5) return 9.0;
    float sum = 0.0;
    float cnt = 0.0;
    float dl = 0.0;
    for (int dy = -NPR; dy <= NPR; dy++) {
        int ty = t.y + dy;
        int sy = qly + dy;
        if (ty < 0 || ty >= HBH || sy < 0 || sy >= HBH) continue;
        for (int dx = -NPR; dx <= NPR; dx++) {
            int tx = t.x + dx;
            int sx = q.x + dx;
            if (tx < 0 || tx >= WH || sx < 0 || sx >= WH) continue;
            vec4 a = tlrAt(tx, ty);
            if (a.a < 0.5) continue;
            vec4 b = tlrAt(sx, sl * HBH + sy);
            if (b.a < 0.5) continue;
            vec3 d = a.rgb - b.rgb;
            sum += dot(d, d) / 3.0;
            dl += lum(d);
            cnt += 1.0;
        }
    }
    if (cnt < float(MINTAPS)) return 9.0;
    delta = dl / cnt;
    return sum / cnt + ((sl == 0) ? 0.0006 : 0.0);
}
"""
            val nnFrag = "ivec2 t = ivec2(floor(HOOKED_pos * vec2(float(WH), float(HBH))));\n    if (tmhAt(t) < 0.5) return vec4(-1.0, -1.0, 9.0, 0.0);\n"
            // 10) init: motion-compensated (global) candidates from every slot
            sb.append(hdr("", "//!BIND TMH\n//!BIND TMV\n//!BIND TLR\n", "TN0", wh, hbh, "10 patch init"))
            sb.append(common).append(nnCommon).append("""
vec4 hook() {
    $nnFrag
    float best = 9.0;
    vec2 bq = vec2(-1.0);
    float bd = 0.0;
    for (int s = 1; s < NSL; s++) {
        vec4 mv = TMV_tex((vec2(float(s), 0.0) + 0.5) / vec2(float(NSL), 1.0));
        if (mv.w < -0.5) continue;
        for (int k = 0; k < 2; k++) {
            ivec2 sh = (k == 0) ? ivec2(int(floor(mv.x * 0.5 + 0.5)), int(floor(mv.y * 0.5 + 0.5))) : ivec2(0);
            if (k == 1 && sh == ivec2(0) && abs(mv.x) < 0.5 && abs(mv.y) < 0.5) continue;
            ivec2 q = ivec2(t.x + sh.x, s * HBH + t.y + sh.y);
            int ly = q.y - s * HBH;
            if (ly < 0 || ly >= HBH) continue;
            float d;
            float c = patchCost(t, q, d);
            if (c < best) { best = c; bq = vec2(q); bd = d; }
        }
    }
    return vec4(bq, best, bd);
}

""")
            // 11..) PatchMatch iterations (Jacobi style): propagate neighbours' matches, random search, random slot jump
            for (k in 1..p.patchIters) {
                val prev = "TN${k - 1}"
                sb.append(hdr("", "//!BIND TMH\n//!BIND TLR\n//!BIND $prev\n", "TN$k", wh, hbh, "11 patch iter $k"))
                sb.append(common).append(nnCommon).append("""
vec4 nnAt(ivec2 t) {
    if (t.x < 0 || t.x >= WH || t.y < 0 || t.y >= HBH) return vec4(-1.0, -1.0, 9.0, 0.0);
    return ${prev}_tex((vec2(t) + 0.5) / vec2(float(WH), float(HBH)));
}

vec4 hook() {
    $nnFrag
    vec4 cur = nnAt(t);
    float best = cur.z;
    vec2 bq = cur.xy;
    float bd = cur.w;
    // propagation
    for (int i = 0; i < 4; i++) {
        ivec2 n = (i == 0) ? ivec2(-1, 0) : ((i == 1) ? ivec2(1, 0) : ((i == 2) ? ivec2(0, -1) : ivec2(0, 1)));
        vec4 nb = nnAt(t + n);
        if (nb.z >= 8.0) continue;
        ivec2 q = ivec2(nb.xy) - n;
        float d;
        float c = patchCost(t, q, d);
        if (c < best) { best = c; bq = vec2(q); bd = d; }
    }
    // random search around the current best (or around the same spot if none yet)
    vec2 base = (cur.z < 8.0) ? bq : vec2(float(t.x), float(t.y));
    float rad = 24.0;
    for (int k = 0; k < 3; k++) {
        vec2 h = vec2(hash21(vec2(t) + float($k * 7 + k * 13)), hash21(vec2(t.yx) + float($k * 11 + k * 5) + 3.1));
        ivec2 q = ivec2(floor(base + (h * 2.0 - 1.0) * rad + 0.5));
        float d;
        float c = patchCost(t, q, d);
        if (c < best) { best = c; bq = vec2(q); bd = d; }
        rad *= 0.35;
    }
    // jump to a random slot at about the same place
    {
        float h1 = hash21(vec2(t) * 1.7 + float($k * 29));
        float h2 = hash21(vec2(t.yx) * 2.3 + float($k * 17));
        int sl = int(floor(h1 * float(NSL)));
        sl = min(sl, NSL - 1);
        ivec2 q = ivec2(t.x + int(floor((h2 * 2.0 - 1.0) * 6.0 + 0.5)), sl * HBH + t.y);
        float d;
        float c = patchCost(t, q, d);
        if (c < best) { best = c; bq = vec2(q); bd = d; }
    }
    return vec4(bq, best, bd);
}

""")
            }
        }

        // temporal gather
        sb.append(hdr("", "//!BIND TMH\n//!BIND TMV\n", "TFL", w, hb, "9/11 temporal fill"))
        sb.append(common).append(hookedHelpers).append(tmhHelpers).append("""
vec4 mvAt(int s) { return TMV_tex((vec2(float(s), 0.0) + 0.5) / vec2(float(NSL), 1.0)); }

vec4 hook() {
    vec2 p = floor(HOOKED_pos * vec2(FW, FHB));
    float a = holeFeather(p);
    if (a <= 0.002) return vec4(0.0);
    vec3 cm = bandRGB(0, p);
    vec3 acc = vec3(0.0);
    float ws = 0.0;
    float wmax = 0.0;
    for (int s = 1; s < NSL; s++) {
        vec4 mv = mvAt(s);
        if (mv.z < 0.05) continue;
        vec2 q = p + mv.xy;
        if (q.x < 0.0 || q.x > FW - 1.0 || q.y < 0.0 || q.y > FHB - 1.0) continue;
        if (holeLin(s, q) > 0.02) continue;
        vec3 cs = bandRGB(s, q);
        float mse = 0.0;
        float cn = 0.0;
        vec3 dsum = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float ang = 0.7853982 * float(i);
            float rr = (i - 2 * (i / 2) == 0) ? R1 : R2;
            vec2 pt = p + vec2(cos(ang), sin(ang)) * rr;
            if (pt.x < 0.0 || pt.x > FW - 1.0 || pt.y < 0.0 || pt.y > FHB - 1.0) continue;
            if (holeLin(0, pt) > 0.02) continue;
            vec2 qt = pt + mv.xy;
            if (qt.x < 0.0 || qt.x > FW - 1.0 || qt.y < 0.0 || qt.y > FHB - 1.0) continue;
            if (holeLin(s, qt) > 0.02) continue;
            vec3 d = bandRGB(0, pt) - bandRGB(s, qt);
            mse += dot(d, d) / 3.0;
            dsum += d;
            cn += 1.0;
        }
        float pen = 0.6;
        vec3 fill = cs;
        if (cn >= 2.0) {
            pen = exp(-(mse / cn) / 0.003);
            fill = clamp(cs + clamp(dsum / cn, vec3(-0.08), vec3(0.08)) * 0.8, 0.0, 1.0);
        }
        float w = mv.z * pen * (1.0 / (1.0 + slotSec(s) * 2.0)) * (0.5 + 0.5 * mv.w);
        acc += fill * w * w;
        ws += w * w;
        wmax = max(wmax, w);
    }
    float qual = smoothstep(0.06, 0.25, wmax);
    vec3 f = (ws > 1e-8) ? acc / ws : cm;
    return vec4(f, qual);
}

""")

        // final estimate: temporal average + patch match + flat-colour spatial fill, then matting
        val tnName = "TN${p.patchIters}"
        val patchSnippet = if (p.patchIters > 0) """
    if (PATCH) {
        ivec2 th = ivec2(floor(p * 0.5));
        vec4 nn = ${tnName}_tex((vec2(th) + 0.5) / vec2(float(WH), float(HBH)));
        if (nn.z < 8.0) {
            int sl = int(floor((nn.y + 0.5) / float(HBH)));
            float ly = nn.y - float(sl * HBH);
            vec2 src = vec2(2.0 * nn.x + (p.x - 2.0 * float(th.x)), 2.0 * ly + (p.y - 2.0 * float(th.y)));
            nc = clamp(bandRGB(sl, src) + vec3(clamp(nn.w, -0.06, 0.06)) * 0.8, 0.0, 1.0);
            wN = exp(-nn.z / 0.004);
        }
    }
""" else ""
        val tspBinds = "//!BIND TMH\n//!BIND TMG\n//!BIND TFL\n" + (if (p.patchIters > 0) "//!BIND $tnName\n" else "")
        sb.append(hdr("", tspBinds, "TSP", w, hb, "12 combine + matting"))
        sb.append(common).append(hookedHelpers).append(tmhHelpers).append("""
float coreA(vec2 q) {
    vec2 hq = clamp((q - 0.5) * 0.5, vec2(0.0), vec2(float(WH - 1), float(HBH - 1)));
    float a = TMG_tex((hq + 0.5) / TMK_SZ).r;
    vec2 o = vec2(1.0, 0.0);
    a += TMG_tex((clamp(hq + o, vec2(0.0), vec2(float(WH - 1), float(HBH - 1))) + 0.5) / TMK_SZ).r;
    a += TMG_tex((clamp(hq - o, vec2(0.0), vec2(float(WH - 1), float(HBH - 1))) + 0.5) / TMK_SZ).r;
    a += TMG_tex((clamp(hq + o.yx, vec2(0.0), vec2(float(WH - 1), float(HBH - 1))) + 0.5) / TMK_SZ).r;
    a += TMG_tex((clamp(hq - o.yx, vec2(0.0), vec2(float(WH - 1), float(HBH - 1))) + 0.5) / TMK_SZ).r;
    return smoothstep(0.25, 0.75, a * 0.2);
}

vec2 clampB(vec2 q) { return clamp(q, vec2(0.0), vec2(FW - 1.0, FHB - 1.0)); }

vec3 probe(vec2 p, vec2 dir, float stepPx, out float dist, out vec2 qm) {
    dist = -1.0;
    qm = p;
    for (int i = 1; i <= 28; i++) {
        float t = (i <= 12) ? stepPx * float(i) : stepPx * (12.0 + 2.0 * float(i - 12));
        vec2 q = p + dir * t;
        if (q.x < 0.0 || q.x > FW - 1.0 || q.y < 0.0 || q.y > FHB - 1.0) return vec3(0.0);
        if (holeNN(0, q) < 0.5) {
            dist = t;
            vec2 q1 = clampB(p + dir * (t + 1.5 * stepPx));
            vec2 q2 = clampB(p + dir * (t + 3.5 * stepPx));
            qm = clampB(p + dir * (t + 2.5 * stepPx));
            return 0.5 * (bandRGB(0, q1) + bandRGB(0, q2));
        }
    }
    return vec3(0.0);
}

float profDiff(vec2 qa, vec2 qb, vec2 nn, float stepPx) {
    float acc = 0.0;
    float cnt = 0.0;
    for (int j = -2; j <= 2; j++) {
        vec2 o = nn * (1.5 * stepPx * float(j));
        vec2 a = qa + o;
        vec2 b = qb + o;
        if (holeNN(0, a) < 0.5 && holeNN(0, b) < 0.5) {
            vec3 df = bandRGB(0, a) - bandRGB(0, b);
            acc += dot(df, df);
            cnt += 1.0;
        }
    }
    return (cnt > 0.0) ? acc / cnt : 9.0;
}

// Flat-colour fill: every ray hits the real background just outside the hole. Instead of averaging those
// colours (which smears two different regions into mud) pick the DOMINANT colour cluster (mean-shift, weighted by
// 1/d^1.5) so cel-shaded areas stay flat. Where an edge crosses the hole and some ray pair agrees, carry the line through.
vec3 spatialFill(vec2 p, float sc) {
    float st = 2.0 * sc;
    vec3 cs[20];
    float dw[20];
    int n = 0;
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    float bestDiff = 9.0;
    float worstDiff = 0.0;
    for (int i = 0; i < 10; i++) {
        float deg;
        if (i == 0) deg = 90.0; else if (i == 1) deg = 75.0; else if (i == 2) deg = 105.0;
        else if (i == 3) deg = 60.0; else if (i == 4) deg = 120.0; else if (i == 5) deg = 0.0;
        else if (i == 6) deg = 30.0; else if (i == 7) deg = 150.0; else if (i == 8) deg = 45.0;
        else deg = 135.0;
        float ang = deg * 0.01745329;
        vec2 dir = vec2(cos(ang), sin(ang));
        float d1; float d2; vec2 qa; vec2 qb;
        vec3 c1 = probe(p, dir, st, d1, qa);
        vec3 c2 = probe(p, -dir, st, d2, qb);
        if (d1 > 0.0) { cs[n] = c1; dw[n] = 1.0 / (d1 * sqrt(d1)); n += 1; }
        if (d2 > 0.0) { cs[n] = c2; dw[n] = 1.0 / (d2 * sqrt(d2)); n += 1; }
        if (d1 > 0.0 && d2 > 0.0) {
            float dd = profDiff(qa, qb, vec2(-dir.y, dir.x), st);
            bestDiff = min(bestDiff, dd);
            worstDiff = max(worstDiff, dd);
            float k = 0.012 + dd;
            float w = 1.0 / ((d1 + d2) * k * sqrt(k));
            acc += ((c1 * d2 + c2 * d1) / (d1 + d2)) * w;
            wsum += w;
        }
    }
    if (n == 0) return bandRGB(0, p);
    float bestDens = -1.0;
    int bi = 0;
    for (int i = 0; i < 20; i++) {
        if (i >= n) break;
        float dens = 0.0;
        for (int j = 0; j < 20; j++) {
            if (j >= n) break;
            vec3 df = cs[i] - cs[j];
            dens += dw[j] * exp(-dot(df, df) / 0.0072);
        }
        if (dens > bestDens) { bestDens = dens; bi = i; }
    }
    vec3 m = vec3(0.0);
    float mw = 0.0;
    for (int j = 0; j < 20; j++) {
        if (j >= n) break;
        vec3 df = cs[j] - cs[bi];
        float k = dw[j] * exp(-dot(df, df) / 0.0072);
        m += cs[j] * k;
        mw += k;
    }
    vec3 fv = m / max(mw, 1e-6);
    if (wsum > 0.0) {
        float edge = smoothstep(0.015, 0.08, worstDiff);
        float reliable = 1.0 - smoothstep(0.015, 0.09, bestDiff);
        fv = mix(fv, acc / wsum, edge * reliable);
    }
    return fv;
}

vec4 hook() {
    vec2 p = floor(HOOKED_pos * vec2(FW, FHB));
    float az = zoneA(p);
    if (az <= 0.002) return vec4(0.0);
    vec3 c = bandRGB(0, p);
    vec4 tf = TFL_tex((p + 0.5) / vec2(FW, FHB));
    float wT = tf.a;
    vec3 nc = tf.rgb;
    float wN = 0.0;
$patchSnippet
    float wBest = max(wT, wN);
    vec3 sp = (wT >= wN) ? tf.rgb : nc;
    float wS = 0.05 + (1.0 - wBest);
    if (wBest < 0.85) sp = spatialFill(p, max(float(H) / 1080.0, 0.5));
    float aT = wT * wT * wT;
    float aN = wN * wN * wN;
    float aS = wS * wS * wS;
    vec3 bgEst = (tf.rgb * aT + nc * aN + sp * aS) / max(aT + aN + aS, 1e-6);

    // Matting: in the halo around the glyphs the pixel is  c = alpha*outline + (1-alpha)*background.
    // Un-mix it so the real background texture survives there; only the solid glyph core is replaced by the estimate.
    vec3 outl = vec3(0.03);
    vec3 od = outl - bgEst;
    float den = dot(od, od);
    vec3 x = bgEst;
    if (den > 0.01) {
        float al = clamp(dot(c - bgEst, od) / den, 0.0, 1.0);
        if (al < 0.7) {
            vec3 bun = (c - al * outl) / max(1.0 - al, 0.3);
            if (length(bun - bgEst) < 0.2) x = mix(bgEst, clamp(bun, 0.0, 1.0), smoothstep(0.01, 0.04, den));
        }
    }
    x = mix(x, bgEst, coreA(p));
    return vec4(clamp(x, 0.0, 1.0), az);
}

""")

        // crop back to the original frame and composite
        sb.append(hdr("", "//!BIND TSP\n", null, w, h, "11/11 composite"))
        sb.append(common).append(hookedHelpers).append("""
vec4 hook() {
    vec2 p = floor(HOOKED_pos * vec2(FW, FH));
    vec4 c = HOOKED_tex((p + 0.5) / TS);
    if (p.y < float(Y0) || p.y >= float(Y0 + HB)) return c;
    vec4 t = TSP_tex((vec2(p.x, p.y - float(Y0)) + 0.5) / vec2(FW, FHB));
    if (t.a <= 0.0) return c;
    if (DEBUG_MASK) return vec4(mix(c.rgb, vec3(1.0, 0.0, 0.0), 0.55 * t.a), c.a);
    return vec4(mix(c.rgb, t.rgb, t.a), c.a);
}
""")
        return sb.toString()
    }
}
