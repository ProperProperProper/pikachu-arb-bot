package com.psa10arb.app.grading

import kotlin.math.floor
import kotlin.math.sqrt

/** Small numpy-equivalent helpers used throughout the grading port. */
object GradingMath {

    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sortedArray()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** Population std (ddof=0), matching numpy.std default. */
    fun std(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val m = values.average()
        val variance = values.sumOf { (it - m) * (it - m) } / values.size
        return sqrt(variance)
    }

    /** numpy.percentile with default 'linear' interpolation. */
    fun percentile(values: DoubleArray, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sortedArray()
        val n = sorted.size
        if (n == 1) return sorted[0]
        val idx = (p / 100.0) * (n - 1)
        val lo = floor(idx).toInt()
        val hi = lo + 1
        if (hi >= n) return sorted[n - 1]
        val frac = idx - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    fun mean(values: DoubleArray): Double = if (values.isEmpty()) 0.0 else values.average()

    /** Evenly spaced ints from start to stop inclusive, count values — mirrors np.linspace(..., dtype=int). */
    fun linspaceInt(start: Double, stop: Double, count: Int): IntArray {
        if (count == 1) return intArrayOf(start.toInt())
        val step = (stop - start) / (count - 1)
        return IntArray(count) { i -> (start + step * i).toInt() }
    }

    fun linspaceDouble(start: Double, stop: Double, count: Int): DoubleArray {
        if (count == 1) return doubleArrayOf(start)
        val step = (stop - start) / (count - 1)
        return DoubleArray(count) { i -> start + step * i }
    }
}
