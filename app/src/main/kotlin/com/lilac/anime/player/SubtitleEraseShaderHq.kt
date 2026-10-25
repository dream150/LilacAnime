package com.lilac.anime.player

import java.util.Locale

/**
 * 영상에 박힌 자막 지우기 - 고품질 셰이더 생성기.
 * MpvPlayerEngine.configureSubtitleErase 에서 build() 결과를 sub_erase.glsl 로 저장해 mpv 에 넘긴다.
 * (원래 buildSubtitleEraseShader 와 같은 입력/출력: 영역 목록 -> GLSL 문자열)
 */
object SubtitleEraseShaderHq {
    /**
     * 고품질 버전 (8패스, 모두 영역 밖은 즉시 반환).
     *  1) SUBC  픽셀 분류 (반해상도, 2x2 부블록의 max 로 1px 얇은 윤곽선도 보존)
     *  2) SUBM  폭이 좁은 흰색 획 판정
     *  3) SUBG  글자 속에서 8px 안쪽으로 번지되 "윤곽/채움으로 분류된 픽셀"에만 번짐
     *  4) SUBD  안티앨리어싱 테두리용 팽창
     *  5) SUBE  페더링용 여유 영역 (= 채워야 할 구멍)
     *  6)       SUBF  SUBE 바깥의 진짜 배경을 8쌍(16방향)으로 찾아 채움.
     *           기본: 모든 경계 색을 1/d^2 로 부드럽게 섞음 (방향마다 지터를 줘서 방사형 줄무늬 제거)
     *           경계선이 구멍을 가로지를 때만 방향성 선형 보간으로 선을 이어줌
     *  7)       SUBS  채운 결과를 구멍 안에서만 살짝 블러 (픽셀별 지터 노이즈 제거 -> 사진 같은 질감 방지)
     *  8)       SUBD 를 부드럽게 만든 알파로 원본과 섞음
     */
    /**
     * @param pickedRgb 스포이드로 찍은 자막 색 (0..1 RGB). null 이면 "밝은 무채색(흰색 계열)" 전부를 자막 후보로 본다.
     * @param pickTolerance 찍은 색과의 허용 거리 (RGB 유클리드 거리, 0.10~0.20 정도)
     * @param style SubtitleStyleLearner 가 학습한 자막 스타일 (채움 색/윤곽선/획 굵기). null 이면 기본값.
     *              pickedRgb 를 직접 주면 그 색이 학습된 색보다 우선한다.
     */
    fun build(
        rects: List<WatermarkRect>,
        pickedRgb: FloatArray? = null,
        pickTolerance: Float = 0.14f,
        style: SubtitleStyle? = null,
        /** true: 획의 한쪽 끝이라도 어두운 윤곽선에 닿아 있어야 글자로 인정 (오검출은 줄지만 덜 지워질 수 있음) */
        strictOutline: Boolean = false,
        /** true: 획을 따라 흰색이 너무 길게 이어지는 선(하이라이트, 테두리)은 글자로 보지 않음. 긴 밑줄/큰 글자가 안 지워지면 끄기 */
        limitLength: Boolean = false,
        /** true: 지우는 대신 빨강=지워질 곳, 노랑=글자로 인식한 획 으로 표시 (진단용) */
        debug: Boolean = false
    ): String {
        val band = bandFn("inBand", rects, 0f, 0f) +
            // 1번 패스(분류)만 쓰는 넓힌 영역: 영역 경계에서 흰 물체가 잘려 "좁은 획"으로 오인되는 것을 막는다
            bandFn("inBandEx", rects, 0.02f, 0.04f)
        val manualPick = pickedRgb != null && pickedRgb.size >= 3
        val rgbPick: FloatArray? = if (manualPick) pickedRgb else style?.fill
        val tolPick = if (manualPick) pickTolerance else (style?.pickTolerance ?: pickTolerance)
        val edgeLumS = String.format(Locale.US, "%.4f", style?.edgeLum ?: 0.28f)
        val edgeChromaS = String.format(Locale.US, "%.4f", style?.edgeChroma ?: 0.20f)
        val rayS = String.format(Locale.US, "%.2f", style?.rayMax ?: 10f)
        val growS = String.format(Locale.US, "%.2f", style?.growPx ?: 8f)
        // 학습된 스타일이 있으면 "윤곽선으로 막힌 글자 획"만 자막으로 본다 (윤곽선 없는 밝은 선/물체 오검출 방지)
        val needOutlineS = if (strictOutline) "true" else "false"
        val debugS = if (debug) "true" else "false"
        val limitS = if (limitLength) "true" else "false"
        val pickDecl = if (rgbPick != null) {
            String.format(
                Locale.US,
                "const bool USE_PICK = true;\nconst vec3 PICK_RGB = vec3(%.4f, %.4f, %.4f);\nconst float PICK_TOL = %.4f;\n",
                rgbPick[0], rgbPick[1], rgbPick[2], tolPick
            )
        } else {
            "const bool USE_PICK = false;\nconst vec3 PICK_RGB = vec3(1.0);\nconst float PICK_TOL = 0.14;\n"
        }
        val common = band + pickDecl + """
// ---- tuning values ----
const float FILL_LUM = 0.66;     // brighter + neutral than this = text interior candidate
const float FILL_CHROMA = 0.18;  // chroma (max-min) limit; lower = stricter exclusion of colored artwork
const float EDGE_LUM = $edgeLumS;     // darker + neutral than this = outline candidate (learned)
const float EDGE_CHROMA = $edgeChromaS;  // learned
const float FILL_BREAK = 0.03;   // best-pair color diff (squared) above this = no reliable direction -> use smooth base fill
const float EDGE_LO = 0.015;     // worst-pair color diff (squared): below LO = flat background (base fill only)
const float EDGE_HI = 0.08;      //   above HI = an edge definitely crosses the hole (directional fill allowed)
const float TEX_GAIN = 1.0;      // how much of the surrounding fine texture is copied into the fill (0 = flat fill)
const float TEX_CLAMP = 0.04;    // max brightness detail copied per pixel, so edges are never copied as texture
const float GRAIN = 0.002;       // tiny dither (anti-banding only). Anime fills are flat, so keep it near 0; 0.0 = off
const bool DEBUG_MASK = $debugS;  // preview: red = will be replaced, yellow = detected text strokes
const bool LIMIT_LEN = $limitS;  // reject strokes that stay white for a long way along their own direction (limitLength)
const bool NEED_OUTLINE = $needOutlineS;  // stroke must be capped by outline pixels (strictOutline)
const float LEN_STEPS = 22.0;   // max half-length of a text stroke along its own direction = 2*s*LEN_STEPS px (longer = line art, not text)
const float GROW_PX = $growS;     // how far (1080p px) pass 3 grows from a stroke onto outline/fill pixels (learned)
const float RAY_STEPS = $rayS;   // max half-width of a text stroke = 2*s*RAY_STEPS px (raise for huge titles, lower to spare big white areas)

float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
float chroma(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }
"""
        return """//!HOOK MAIN
//!BIND HOOKED
//!SAVE SUBC
//!WIDTH HOOKED.w 2 /
//!HEIGHT HOOKED.h 2 /
//!DESC subtitle erase HQ 1/8: classify

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
    if (!inBandEx(p)) return vec4(0.0);
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
//!DESC subtitle erase HQ 2/8: narrow white strokes

$common
// Walk along dir while pixels stay "white". Returns
//   0 : the white run does not end within maxSteps (big white area / long line) or we leave the frame
//   1 : the run ends
//   2 : the run ends AND is capped by an outline (dark) pixel right after it
float runEnds(vec2 p, vec2 dir, float s, float maxSteps) {
    for (int k = 1; k <= 24; k++) {
        if (float(k) > maxSteps) break;
        vec2 q = p + dir * (2.0 * s * float(k)) * HOOKED_pt;
        if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) return 0.0;
        if (SUBC_tex(q).r < 0.4) {
            for (int m = 0; m < 3; m++) {
                vec2 q2 = q + dir * (2.0 * s * float(m)) * HOOKED_pt;
                if (SUBC_tex(q2).g > 0.4) return 2.0;
            }
            return 1.0;
        }
    }
    return 0.0;
}

vec4 hook() {
    vec2 p = HOOKED_pos;
    if (!inBand(p)) return vec4(0.0);
    if (SUBC_tex(p).r < 0.5) return vec4(0.0, 0.0, 0.0, 1.0);
    float s = max(HOOKED_size.y / 1080.0, 0.5);
    float m = 0.0;
    // A pixel belongs to a text stroke if
    //  1) along one of 4 axes the white run is narrow (bounded on both sides),
    //  2) (strict outline) at least one end of that run is capped by an outline pixel, and
    //  3) along the perpendicular axis the white run is also short: text strokes are short,
    //     long bright lines (roof highlights, frame borders, horizons) are not text.
    for (int i = 0; i < 4; i++) {
        float a = 0.7853982 * float(i);
        vec2 d = vec2(cos(a), sin(a));
        float e1 = runEnds(p, d, s, RAY_STEPS);
        float e2 = runEnds(p, -d, s, RAY_STEPS);
        if (e1 > 0.5 && e2 > 0.5 && (!NEED_OUTLINE || max(e1, e2) > 1.5)) {
            vec2 n = vec2(-d.y, d.x);
            if (!LIMIT_LEN || (runEnds(p, n, s, LEN_STEPS) > 0.5 && runEnds(p, -n, s, LEN_STEPS) > 0.5)) {
                m = 1.0;
                break;
            }
        }
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
//!DESC subtitle erase HQ 3/8: grow onto outline

$common
vec4 hook() {
    vec2 p = HOOKED_pos;
    if (!inBand(p)) return vec4(0.0);
    vec2 cl = SUBC_tex(p).rg;
    float m = SUBM_tex(p).r;
    // grow only onto pixels classified as outline/fill; background is left alone
    if (m < 0.5 && (cl.r > 0.4 || cl.g > 0.4)) {
        float s = max(HOOKED_size.y / 1080.0, 0.5);
        float g = GROW_PX * 0.25 * s;
        for (int i = 0; i < 8; i++) {
            float a = 0.7853982 * float(i);
            vec2 d = vec2(cos(a), sin(a)) * HOOKED_pt;
            m = max(m, SUBM_tex(p + d * g).r);
            m = max(m, SUBM_tex(p + d * (2.0 * g)).r);
            m = max(m, SUBM_tex(p + d * (3.0 * g)).r);
            m = max(m, SUBM_tex(p + d * (4.0 * g)).r);
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
//!DESC subtitle erase HQ 4/8: halo dilate

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
//!DESC subtitle erase HQ 5/8: feather margin

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
//!DESC subtitle erase HQ 6/8: structure-aware fill

$common

// find the first pixel outside SUBE (not text) along dir and return the background color just beyond it
// first 12 steps are fine, then the stride doubles so large holes are reached; dist = -1 if not found
// qm = point 2.5 steps beyond the boundary (center of the profile used to compare both sides)
vec3 probe(vec2 p, vec2 dir, float stepPx, out float dist, out vec2 qm) {
    dist = -1.0;
    qm = p;
    vec2 pt = HOOKED_pt;
    for (int i = 1; i <= 28; i++) {
        float t = (i <= 12) ? stepPx * float(i) : stepPx * (12.0 + 2.0 * float(i - 12));
        vec2 q = p + dir * t * pt;
        if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) return vec3(0.0);
        if (SUBE_tex(q).r < 0.5) {
            dist = t;
            vec2 q1 = clamp(p + dir * (t + 1.5 * stepPx) * pt, vec2(0.0), vec2(1.0));
            vec2 q2 = clamp(p + dir * (t + 3.5 * stepPx) * pt, vec2(0.0), vec2(1.0));
            qm = clamp(p + dir * (t + 2.5 * stepPx) * pt, vec2(0.0), vec2(1.0));
            return 0.5 * (HOOKED_tex(q1).rgb + HOOKED_tex(q2).rgb);
        }
    }
    return vec3(0.0);
}

// How different are the two ends, looking at a small strip (5 samples across the ray) instead of one color.
// An edge that runs along the ray looks the same at both ends (small value); an edge that crosses does not.
float profDiff(vec2 qa, vec2 qb, vec2 n, float stepPx) {
    float acc = 0.0;
    float cnt = 0.0;
    for (int j = -2; j <= 2; j++) {
        vec2 o = n * (1.5 * stepPx * float(j)) * HOOKED_pt;
        vec2 a = qa + o;
        vec2 b = qb + o;
        if (SUBE_tex(a).r < 0.5 && SUBE_tex(b).r < 0.5) {
            vec3 df = HOOKED_tex(a).rgb - HOOKED_tex(b).rgb;
            acc += dot(df, df);
            cnt += 1.0;
        }
    }
    return (cnt > 0.0) ? acc / cnt : 9.0;
}

// One pair (+dir, -dir).
//  - nearAcc/nearW : boundary colors of every direction, weighted d^-1.5 (smooth blend of all surroundings)
//  - acc/wsum      : linear interpolation across the hole, weighted by how similar the two ENDS (strips) are.
//                    This carries edges / thin lines / color borders straight through the hole.
//  - bestDiff / worstDiff : smallest / largest strip difference (squared) over all pairs
void pairFill(vec2 p, vec2 dir, float stepPx, inout vec3 acc, inout float wsum,
              inout float bestDiff, inout float worstDiff, inout vec3 nearAcc, inout float nearW) {
    float d1;
    float d2;
    vec2 qa;
    vec2 qb;
    vec3 c1 = probe(p, dir, stepPx, d1, qa);
    vec3 c2 = probe(p, -dir, stepPx, d2, qb);
    if (d1 > 0.0) {
        float w1 = 1.0 / (d1 * sqrt(d1));
        nearAcc += c1 * w1;
        nearW += w1;
    }
    if (d2 > 0.0) {
        float w2 = 1.0 / (d2 * sqrt(d2));
        nearAcc += c2 * w2;
        nearW += w2;
    }
    if (d1 > 0.0 && d2 > 0.0) {
        float dd = profDiff(qa, qb, vec2(-dir.y, dir.x), stepPx);
        bestDiff = min(bestDiff, dd);
        worstDiff = max(worstDiff, dd);
        float k = 0.012 + dd;
        float w = 1.0 / ((d1 + d2) * k * sqrt(k));
        acc += ((c1 * d2 + c2 * d1) / (d1 + d2)) * w;
        wsum += w;
    }
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
    float worstDiff = 0.0;
    vec3 nearAcc = vec3(0.0);
    float nearW = 0.0;
    // 14 pairs. Holes are wide, flat bands, so the edges that cross them are mostly near-vertical:
    // 9 pairs cover 60..120 deg in 7.5 deg steps, 5 more cover the rest.
    for (int i = 0; i < 14; i++) {
        float deg;
        if (i < 9) deg = 60.0 + 7.5 * float(i);
        else if (i == 9) deg = 0.0;
        else if (i == 10) deg = 20.0;
        else if (i == 11) deg = 40.0;
        else if (i == 12) deg = 140.0;
        else deg = 160.0;
        float a = deg * 0.01745329;
        pairFill(p, vec2(cos(a), sin(a)), st, acc, wsum, bestDiff, worstDiff, nearAcc, nearW);
    }
    if (nearW <= 0.0) return c;
    vec3 f = nearAcc / nearW;
    if (wsum > 0.0) {
        vec3 fDir = acc / wsum;
        // directional result only when an edge crosses the hole AND some direction is consistent
        float edge = smoothstep(EDGE_LO, EDGE_HI, worstDiff);
        float reliable = 1.0 - smoothstep(FILL_BREAK * 0.5, FILL_BREAK * 3.0, bestDiff);
        f = mix(f, fDir, edge * reliable);
    }
    return vec4(clamp(f, 0.0, 1.0), c.a);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND SUBE
//!BIND SUBF
//!SAVE SUBS
//!DESC subtitle erase HQ 7/8: smooth + surrounding texture

$common
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 blur5(vec2 q, float s) {
    vec2 o = HOOKED_pt * (2.0 * s);
    return 0.2 * (HOOKED_tex(q).rgb
                + HOOKED_tex(q + vec2(o.x, 0.0)).rgb + HOOKED_tex(q - vec2(o.x, 0.0)).rgb
                + HOOKED_tex(q + vec2(0.0, o.y)).rgb + HOOKED_tex(q - vec2(0.0, o.y)).rgb);
}

// Only pixels inside the hole are touched; real pixels are used as inputs, so the fill stays continuous.
vec4 hook() {
    vec2 p = HOOKED_pos;
    vec4 c = HOOKED_tex(p);
    if (!inBand(p)) return c;
    if (SUBE_tex(p).r < 0.5) return c;
    float s = max(HOOKED_size.y / 1080.0, 0.5);

    // 1) edge-preserving smoothing: neighbors count only if their color is close, so thin lines and
    //    hard color edges that were carried through the hole stay sharp.
    vec3 c0 = SUBF_tex(p).rgb;
    vec3 sum = c0 * 2.0;
    float w = 2.0;
    for (int i = 0; i < 8; i++) {
        float a = 0.7853982 * float(i);
        vec2 d = vec2(cos(a), sin(a)) * HOOKED_pt;
        for (int r = 1; r <= 2; r++) {
            vec3 q = SUBF_tex(p + d * (1.5 * s * float(r))).rgb;
            vec3 df = q - c0;
            float k = max(0.0, 1.0 - dot(df, df) / 0.012);
            sum += q * k;
            w += k;
        }
    }
    vec3 f = sum / w;

    // 2) surrounding texture: find the nearest hole boundary, mirror this pixel across it, and copy the
    //    fine detail (pixel minus its local mean) found there.  At the boundary the mirror point is the
    //    boundary pixel itself, so texture continues seamlessly into the fill.
    float bestT = 1e9;
    vec2 bestDir = vec2(0.0);
    for (int i = 0; i < 8; i++) {
        float a = 0.7853982 * float(i);
        vec2 dir = vec2(cos(a), sin(a));
        for (int k = 1; k <= 24; k++) {
            float t = 2.0 * s * float(k);
            if (t >= bestT) break;
            vec2 q = p + dir * t * HOOKED_pt;
            if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) break;
            if (SUBE_tex(q).r < 0.5) {
                bestT = t;
                bestDir = dir;
                break;
            }
        }
    }
    if (bestT < 1e8) {
        vec2 q = p + bestDir * (2.0 * bestT + 4.0 * s) * HOOKED_pt;
        if (q.x > 0.0 && q.x < 1.0 && q.y > 0.0 && q.y < 1.0 && SUBE_tex(q).r < 0.5) {
            float res = lum(HOOKED_tex(q).rgb) - lum(blur5(q, s));
            f += vec3(clamp(res, -TEX_CLAMP, TEX_CLAMP)) * TEX_GAIN;
        }
    }

    // anti-banding: dither of about one 8-bit step
    f += (hash12(floor(p * HOOKED_size)) - 0.5) * GRAIN;
    return vec4(clamp(f, 0.0, 1.0), c.a);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND SUBD
//!BIND SUBS
//!BIND SUBM
//!DESC subtitle erase HQ 8/8: feathered blend

$common
vec4 hook() {
    vec2 p = HOOKED_pos;
    vec4 c = HOOKED_tex(p);
    if (!inBand(p)) return c;
    if (DEBUG_MASK) {
        vec3 col = mix(c.rgb, vec3(0.0, 0.4, 1.0), 0.10);          // blue tint = selected region
        col = mix(col, vec3(1.0, 0.0, 0.0), 0.55 * SUBD_tex(p).r);  // red = will be replaced
        col = mix(col, vec3(1.0, 1.0, 0.0), 0.85 * SUBM_tex(p).r);  // yellow = detected text strokes
        return vec4(col, c.a);
    }
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
    vec3 f = SUBS_tex(p).rgb;
    return vec4(mix(c.rgb, f, a), c.a);
}
"""
    }

    /** 영역 목록을 GLSL bool 함수로 만든다. padX/padY 만큼 사각형을 넓힌다 (정규화 좌표). */
    private fun bandFn(name: String, rects: List<WatermarkRect>, padX: Float, padY: Float): String {
        val body = rects.joinToString(" || ") {
            String.format(
                Locale.US, "(p.x >= %.5f && p.x <= %.5f && p.y >= %.5f && p.y <= %.5f)",
                it.x - padX, it.x + it.w + padX, it.y - padY, it.y + it.h + padY
            )
        }.ifEmpty { "false" }
        return "bool $name(vec2 p) {\n    return $body;\n}\n"
    }
}
