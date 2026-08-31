package com.lilac.anime.data.matcher

import java.util.Locale
import kotlin.math.min

data class HangulVector(val cho: Int, val jung: Int, val jong: Int)

/** Shared title matcher for Korean/Latin/Japanese anime titles. */
object HangulSimilarityMatcher {
    fun filterNoise(input: String): String = input
        .lowercase(Locale.ROOT)
        .replace(Regex("[^가-힣a-zA-Z0-9\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun decomposeHangul(c: Char): HangulVector? {
        val code = c.code
        if (code !in 0xAC00..0xD7A3) return null
        val offset = code - 0xAC00
        return HangulVector(offset / (28 * 21), (offset / 28) % 21, offset % 28)
    }

    fun substitutionCost(a: Char, b: Char): Double {
        if (a == b) return 0.0
        val va = decomposeHangul(a)
        val vb = decomposeHangul(b)
        if (va != null && vb != null) {
            var cost = 0.0
            if (va.cho != vb.cho) cost += 0.4
            if (va.jung != vb.jung) cost += 0.3
            if (va.jong != vb.jong) cost += 0.3
            return cost
        }
        return 1.0
    }

    fun weightedEditDistance(first: String, second: String): Double {
        val a = filterNoise(first).replace(" ", "")
        val b = filterNoise(second).replace(" ", "")
        if (a.isEmpty()) return b.length.toDouble()
        if (b.isEmpty()) return a.length.toDouble()
        var previous = DoubleArray(b.length + 1) { it.toDouble() }
        var current = DoubleArray(b.length + 1)
        for (i in a.indices) {
            current[0] = (i + 1).toDouble()
            for (j in b.indices) {
                current[j + 1] = min(min(previous[j + 1] + 1.0, current[j] + 1.0), previous[j] + substitutionCost(a[i], b[j]))
            }
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }

    fun similarity(first: String, second: String): Double {
        val a = filterNoise(first).replace(" ", "")
        val b = filterNoise(second).replace(" ", "")
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.90
        return (1.0 - weightedEditDistance(a, b) / maxOf(a.length, b.length).toDouble()).coerceIn(0.0, 1.0)
    }

    /** Compatibility score for existing AniSkip thresholds (0..10000). */
    fun score(first: String, second: String): Int = (similarity(first, second) * 10000.0).toInt()
}
