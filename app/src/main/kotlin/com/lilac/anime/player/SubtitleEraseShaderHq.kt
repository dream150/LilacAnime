package com.lilac.anime.player

import java.util.Locale

/**
 * 영상에 박힌 자막 지우기 - 고품질 셰이더 생성기.
 * MpvPlayerEngine.configureSubtitleErase 에서 build() 결과를 sub_erase.glsl 로 저장해 mpv 에 넘긴다.
 * (원래 buildSubtitleEraseShader 와 같은 입력/출력: 영역 목록 -> GLSL 문자열)
 */
object SubtitleEraseShaderHq {
    /**
     * 고품질 버전 (7패스, 모두 영역 밖은 즉시 반환).
     *  1) SUBC  픽셀 분류 (반해상도, 2x2 부블록의 max 로 1px 얇은 윤곽선도 보존)
     *           - 채움: 밝고 무채색 / 윤곽: 어둡고 무채색.  색이 있는 픽셀(캐릭터 그림)은 둘 다 아님
     *  2) SUBM  폭이 좁은 흰색 획 판정: 흰색 픽셀에서 4개 축으로 걸어서 흰색 구간이 양쪽 모두
     *           RAY_STEPS 안에 끝나는 축이 있으면 글자 획. 윤곽선 유무와 상관없고,
     *           폭이 넓은 흰색 면(옷, 구름 등)은 어느 축에서도 안 끝나서 제외됨
     *  3) SUBG  글자 속에서 8px 안쪽으로 번지되 "윤곽/채움으로 분류된 픽셀"에만 번짐 (배경은 침범 안 함)
     *  4) SUBD  안티앨리어싱 테두리용으로 조금 더 팽창
     *  5) SUBE  페더링용 여유 영역
     *  6) SUBF  SUBE 바깥의 진짜 배경을 4쌍(수직/수평/대각2)으로 찾아 방향성 선형 보간
     *  7)       SUBD 를 부드럽게 만든 알파로 원본과 섞음
     */
    /**
     * @param pickedRgb 스포이드로 찍은 자막 색 (0..1 RGB). null 이면 "밝은 무채색(흰색 계열)" 전부를 자막 후보로 본다.
     * @param pickTolerance 찍은 색과의 허용 거리 (RGB 유클리드 거리, 0.10~0.20 정도)
     */
    fun build(rects: List<WatermarkRect>, pickedRgb: FloatArray? = null, pickTolerance: Float = 0.14f): String {
        val band = StringBuilder("bool inBand(vec2 p) {\n    return ")
        band.append(
            rects.joinToString(" || ") {
                String.format(
                    Locale.US, "(p.x >= %.5f && p.x <= %.5f && p.y >= %.5f && p.y <= %.5f)",
                    it.x, it.x + it.w, it.y, it.y + it.h
                )
            }
        ).append(";\n}\n")
        val pickDecl = if (pickedRgb != null && pickedRgb.size >= 3) {
            String.format(
                Locale.US,
                "const bool USE_PICK = true;\nconst vec3 PICK_RGB = vec3(%.4f, %.4f, %.4f);\nconst float PICK_TOL = %.4f;\n",
                pickedRgb[0], pickedRgb[1], pickedRgb[2], pickTolerance
            )
        } else {
            "const bool USE_PICK = false;\nconst vec3 PICK_RGB = vec3(1.0);\nconst float PICK_TOL = 0.14;\n"
        }
        val common = band.toString() + pickDecl + """
// ---- tuning values ----
const float FILL_LUM = 0.66;     // brighter + neutral than this = text interior candidate
const float FILL_CHROMA = 0.18;  // chroma (max-min) limit; lower = stricter exclusion of colored artwork
const float EDGE_LUM = 0.28;     // darker + neutral than this = outline candidate
const float EDGE_CHROMA = 0.20;
const float FILL_BREAK = 0.03;    // when both sides differ by about this (color diff squared), treat as an edge and move toward crisp fill
const float GRAIN = 0.006;       // dither noise (anti-banding); set 0.0 if it looks noisy
const float RAY_STEPS = 10.0;   // max half-width of a text stroke = 2*s*RAY_STEPS px (raise for huge titles, lower to spare big white areas)

float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
float chroma(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }
"""
        return """//!HOOK MAIN
//!BIND HOOKED
//!SAVE SUBC
//!WIDTH HOOKED.w 2 /
//!HEIGHT HOOKED.h 2 /
//!DESC subtitle erase HQ 1/7: classify

$common
vec2 classify(vec2 q) {
    vec3 c = HOOKED_tex(q).rgb;
    float l = lum(c);
    float ch = chroma(c);
    float f = (l > FILL_LUM && ch < FILL_CHROMA) ? 1.0 : 0.0;
    if (USE_PICK) {
        vec3 dc = c - PICK_RGB;
        f = (dot(dc, dc) < PICK_TOL * PICK_TOL) ? 1.0 : 0.0;
    }
    float o = (l < EDGE_LUM && ch < EDGE_CHROMA) ? 1.0 : 0.0;
    return vec2(f, o);
}

vec4 hook() {
    vec2 p = HOOKED_pos;
    if (!inBand(p)) return vec4(0.0);
    vec2 h = 0.5 * HOOKED_pt;
    vec2 v = classify(p + vec2(-h.x, -h.y));
    v = max(v, classify(p + vec2(h.x, -h.y)));
    v = max(v, classify(p + vec2(-h.x, h.y)));
    v = max(v, classify(p + vec2(h.x, h.y)));
    return vec4(v, 0.0, 1.0);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND SUBC
//!SAVE SUBM
//!WIDTH HOOKED.w 2 /
//!HEIGHT HOOKED.h 2 /
//!DESC subtitle erase HQ 2/7: narrow white strokes

$common
// Walk along dir while pixels stay "white". Return 1 if the white run ENDS within
// RAY_STEPS steps (i.e. the stroke is narrow in this direction), 0 if it is still white
// at the end or we leave the frame (i.e. a big white area).
float runEnds(vec2 p, vec2 dir, float s) {
    for (int k = 1; k <= 24; k++) {
        if (float(k) > RAY_STEPS) break;
        vec2 q = p + dir * (2.0 * s * float(k)) * HOOKED_pt;
        if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) return 0.0;
        if (SUBC_tex(q).r < 0.4) return 1.0;
    }
    return 0.0;
}

vec4 hook() {
    vec2 p = HOOKED_pos;
    if (!inBand(p)) return vec4(0.0);
    if (SUBC_tex(p).r < 0.5) return vec4(0.0, 0.0, 0.0, 1.0);
    float s = max(HOOKED_size.y / 1080.0, 0.5);
    float m = 0.0;
    // A pixel belongs to a text stroke if along at least one axis (4 axes) the white run
    // is bounded on both sides. Large white regions are not bounded on any axis.
    for (int i = 0; i < 4; i++) {
        float a = 0.7853982 * float(i);
        vec2 d = vec2(cos(a), sin(a));
        if (runEnds(p, d, s) > 0.5 && runEnds(p, -d, s) > 0.5) { m = 1.0; break; }
    }
    return vec4(m, m, m, 1.0);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND SUBC
//!BIND SUBM
//!SAVE SUBG
//!WIDTH HOOKED.w 2 /
//!HEIGHT HOOKED.h 2 /
//!DESC subtitle erase HQ 3/7: grow onto outline

$common
vec4 hook() {
    vec2 p = HOOKED_pos;
    if (!inBand(p)) return vec4(0.0);
    vec2 cl = SUBC_tex(p).rg;
    float m = SUBM_tex(p).r;
    // grow only onto pixels classified as outline/fill; background is left alone
    if (m < 0.5 && (cl.r > 0.4 || cl.g > 0.4)) {
        float s = max(HOOKED_size.y / 1080.0, 0.5);
        for (int i = 0; i < 8; i++) {
            float a = 0.7853982 * float(i);
            vec2 d = vec2(cos(a), sin(a)) * HOOKED_pt;
            m = max(m, SUBM_tex(p + d * (2.0 * s)).r);
            m = max(m, SUBM_tex(p + d * (4.0 * s)).r);
            m = max(m, SUBM_tex(p + d * (6.0 * s)).r);
            m = max(m, SUBM_tex(p + d * (8.0 * s)).r);
        }
    }
    return vec4(m, m, m, 1.0);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND SUBG
//!SAVE SUBD
//!WIDTH HOOKED.w 2 /
//!HEIGHT HOOKED.h 2 /
//!DESC subtitle erase HQ 4/7: halo dilate

$common
vec4 hook() {
    vec2 p = HOOKED_pos;
    if (!inBand(p)) return vec4(0.0);
    float s = max(HOOKED_size.y / 1080.0, 0.5);
    float m = SUBG_tex(p).r;
    for (int i = 0; i < 8; i++) {
        float a = 0.7853982 * float(i);
        vec2 d = vec2(cos(a), sin(a)) * HOOKED_pt;
        m = max(m, SUBG_tex(p + d * (2.0 * s)).r);
        m = max(m, SUBG_tex(p + d * (4.0 * s)).r);
    }
    return vec4(m, m, m, 1.0);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND SUBD
//!SAVE SUBE
//!WIDTH HOOKED.w 2 /
//!HEIGHT HOOKED.h 2 /
//!DESC subtitle erase HQ 5/7: feather margin

$common
vec4 hook() {
    vec2 p = HOOKED_pos;
    if (!inBand(p)) return vec4(0.0);
    float s = max(HOOKED_size.y / 1080.0, 0.5);
    float m = SUBD_tex(p).r;
    for (int i = 0; i < 8; i++) {
        float a = 0.7853982 * float(i);
        vec2 d = vec2(cos(a), sin(a)) * HOOKED_pt;
        m = max(m, SUBD_tex(p + d * (2.0 * s)).r);
        m = max(m, SUBD_tex(p + d * (4.0 * s)).r);
    }
    return vec4(m, m, m, 1.0);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND SUBE
//!SAVE SUBF
//!DESC subtitle erase HQ 6/7: directional fill

$common

// find the first pixel outside SUBE (not text) along dir and return the background color just beyond it
// first 12 steps are fine, then the stride doubles so large holes are reached; dist = -1 if not found
vec3 probe(vec2 p, vec2 dir, float stepPx, out float dist) {
    dist = -1.0;
    vec2 pt = HOOKED_pt;
    for (int i = 1; i <= 28; i++) {
        float t = (i <= 12) ? stepPx * float(i) : stepPx * (12.0 + 2.0 * float(i - 12));
        vec2 q = p + dir * t * pt;
        if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) return vec3(0.0);
        if (SUBE_tex(q).r < 0.5) {
            dist = t;
            vec2 q1 = clamp(p + dir * (t + 1.5 * stepPx) * pt, vec2(0.0), vec2(1.0));
            vec2 q2 = clamp(p + dir * (t + 3.5 * stepPx) * pt, vec2(0.0), vec2(1.0));
            return 0.5 * (HOOKED_tex(q1).rgb + HOOKED_tex(q2).rgb);
        }
    }
    return vec3(0.0);
}

// Handle one pair (+dir, -dir). All choices are weighted sums so nothing switches abruptly (no banding).
//  - acc/wsum : linear interpolation, weighted higher when both end colors are similar
//  - nearAcc/nearW : boundary colors of all directions summed with distance^-4 (biased to the nearest, no hard cells)
//  - bestDiff : color diff squared of the most consistent pair (is an edge passing through?)
void pairFill(vec2 p, vec2 dir, float stepPx, inout vec3 acc, inout float wsum,
              inout float bestDiff, inout vec3 nearAcc, inout float nearW) {
    float d1;
    float d2;
    vec3 c1 = probe(p, dir, stepPx, d1);
    vec3 c2 = probe(p, -dir, stepPx, d2);
    if (d1 > 0.0) {
        float w1 = 1.0 / (d1 * d1 * d1 * d1);
        nearAcc += c1 * w1;
        nearW += w1;
    }
    if (d2 > 0.0) {
        float w2 = 1.0 / (d2 * d2 * d2 * d2);
        nearAcc += c2 * w2;
        nearW += w2;
    }
    if (d1 > 0.0 && d2 > 0.0) {
        vec3 df = c1 - c2;
        float dd = dot(df, df);
        bestDiff = min(bestDiff, dd);
        float k = 0.004 + dd;
        float w = 1.0 / ((d1 + d2) * k * sqrt(k));
        acc += ((c1 * d2 + c2 * d1) / (d1 + d2)) * w;
        wsum += w;
    }
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec4 hook() {
    vec2 p = HOOKED_pos;
    vec4 c = HOOKED_tex(p);
    if (!inBand(p)) return c;
    if (SUBE_tex(p).r < 0.5) return c;
    float s = max(HOOKED_size.y / 1080.0, 0.5);
    float st = 2.0 * s;
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    float bestDiff = 9.0;
    vec3 nearAcc = vec3(0.0);
    float nearW = 0.0;
    // 8 pairs = 16 directions (22.5 deg apart) so slanted lines can be continued
    pairFill(p, vec2(0.0, 1.0), st, acc, wsum, bestDiff, nearAcc, nearW);
    pairFill(p, vec2(1.0, 0.0), st, acc, wsum, bestDiff, nearAcc, nearW);
    pairFill(p, vec2(0.7071068, 0.7071068), st, acc, wsum, bestDiff, nearAcc, nearW);
    pairFill(p, vec2(0.7071068, -0.7071068), st, acc, wsum, bestDiff, nearAcc, nearW);
    pairFill(p, vec2(0.8944272, 0.4472136), st, acc, wsum, bestDiff, nearAcc, nearW);
    pairFill(p, vec2(0.8944272, -0.4472136), st, acc, wsum, bestDiff, nearAcc, nearW);
    pairFill(p, vec2(0.4472136, 0.8944272), st, acc, wsum, bestDiff, nearAcc, nearW);
    pairFill(p, vec2(0.4472136, -0.8944272), st, acc, wsum, bestDiff, nearAcc, nearW);
    if (nearW <= 0.0) return c;
    vec3 fNear = nearAcc / nearW;
    vec3 fSmooth = (wsum > 0.0) ? acc / wsum : fNear;
    // the more an edge passes (large bestDiff), the more we move to the crisp result; no hard if
    float t = smoothstep(FILL_BREAK * 0.5, FILL_BREAK * 3.0, bestDiff);
    vec3 f = mix(fSmooth, fNear, t);
    // anti-banding: dither of about one 8-bit step
    float n = hash12(floor(p * HOOKED_size)) - 0.5;
    f += n * GRAIN;
    return vec4(clamp(f, 0.0, 1.0), c.a);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND SUBD
//!BIND SUBF
//!DESC subtitle erase HQ 7/7: feathered blend

$common
vec4 hook() {
    vec2 p = HOOKED_pos;
    vec4 c = HOOKED_tex(p);
    if (!inBand(p)) return c;
    float s = max(HOOKED_size.y / 1080.0, 0.5);
    float a = 1.0;
    if (SUBD_tex(p).r < 0.5) {
        float sum = 0.0;
        for (int i = 0; i < 8; i++) {
            float ang = 0.7853982 * float(i);
            vec2 d = vec2(cos(ang), sin(ang)) * HOOKED_pt;
            sum += SUBD_tex(p + d * (2.0 * s)).r;
            sum += SUBD_tex(p + d * (4.0 * s)).r;
        }
        a = clamp(sum / 16.0 * 1.6, 0.0, 1.0);
    }
    if (a <= 0.0) return c;
    vec3 f = SUBF_tex(p).rgb;
    return vec4(mix(c.rgb, f, a), c.a);
}
"""
    }
}
