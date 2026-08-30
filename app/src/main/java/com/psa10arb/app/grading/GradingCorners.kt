package com.psa10arb.app.grading

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val COR_CROP_PX = 60

data class CornersResult(
    val grade: Int,
    val worst: Double,
    val worstPos: String,
    val whites: Map<String, Double>,
)

/**
 * Ported from grading_engine.py's _corner_whitening / _analyze_corners.
 *
 * _corner_sharpness (Harris corner response) is intentionally NOT ported:
 * the Python source itself only ever uses it to populate an "(info only)"
 * inspection-report row — it never feeds `cor_g` or `probable_grade` — and
 * this app doesn't render the 30-point inspection report, so it's dead
 * weight here.
 */
object GradingCorners {

    private fun cornerWhitening(crop: Mat): Double {
        val gray = Mat()
        Imgproc.cvtColor(crop, gray, Imgproc.COLOR_BGR2GRAY)
        val gh = gray.rows()
        val gw = gray.cols()

        val tipPx = max(8, min(14, min(gh / 4, gw / 4)))
        val refEnd = max(tipPx + 4, min(40, min(gh / 2, gw / 2)))

        val tipAll = MatRowCol.flatten8U(gray.submat(0, tipPx, 0, tipPx))
        val refA = MatRowCol.flatten8U(gray.submat(0, refEnd, tipPx, refEnd))
        val refB = MatRowCol.flatten8U(gray.submat(tipPx, refEnd, 0, tipPx))
        val ref = refA + refB

        val tip = tipAll.filter { it < 232.0 }
        val bgFrac = tipAll.count { it >= 232.0 }.toDouble() / max(tipAll.size, 1)

        gray.release()

        if (tip.size < max(4, tipPx)) {
            return roundTo1(min(30.0, bgFrac * 30.0))
        }
        if (ref.isEmpty()) return 0.0
        val excess = max(0.0, tip.average() - ref.average())
        return roundTo1(min(100.0, excess / 30.0 * 100.0))
    }

    private fun gradeFromWhitening(excess: Double): Int {
        val thresholds = listOf(5.0 to 10, 18.0 to 9, 35.0 to 8, 55.0 to 7)
        for ((limit, grade) in thresholds) if (excess <= limit) return grade
        return 1
    }

    private fun gradeFromWhiteningSpread(whites: Map<String, Double>): Int {
        val vals = whites.values
        val spread = (vals.maxOrNull() ?: 0.0) - (vals.minOrNull() ?: 0.0)
        val thresholds = listOf(5.0 to 10, 15.0 to 9, 30.0 to 8, 50.0 to 7)
        for ((limit, grade) in thresholds) if (spread <= limit) return grade
        return 1
    }

    fun analyzeCorners(img: Mat, isHolo: Boolean): CornersResult {
        val h = img.rows()
        val w = img.cols()
        val s = COR_CROP_PX
        // Second line of defense against fill-color contamination at the
        // true image corners (see GradingBounds.rotateAndCrop's inward-trim
        // comment) — start each 60x60 sample a few px in from the edge.
        val edgeInset = 6

        val rawTL = img.submat(edgeInset, edgeInset + s, edgeInset, edgeInset + s)
        val rawTR = img.submat(edgeInset, edgeInset + s, w - s - edgeInset, w - edgeInset)
        val rawBR = img.submat(h - s - edgeInset, h - edgeInset, w - s - edgeInset, w - edgeInset)
        val rawBL = img.submat(h - s - edgeInset, h - edgeInset, edgeInset, edgeInset + s)

        val trFlipped = Mat(); Core.flip(rawTR, trFlipped, 1)
        val brRotated = Mat(); Core.rotate(rawBR, brRotated, Core.ROTATE_180)
        val blFlipped = Mat(); Core.flip(rawBL, blFlipped, 0)

        val whites = linkedMapOf(
            "TL" to cornerWhitening(rawTL),
            "TR" to cornerWhitening(trFlipped),
            "BR" to cornerWhitening(brRotated),
            "BL" to cornerWhitening(blFlipped),
        )
        trFlipped.release(); brRotated.release(); blFlipped.release()

        if (whites.isEmpty()) return CornersResult(10, 0.0, "", emptyMap())
        val pos = whites.entries.maxByOrNull { it.value }!!.key
        val worst = whites.getValue(pos)
        val cg = if (isHolo) gradeFromWhiteningSpread(whites) else gradeFromWhitening(worst)
        return CornersResult(cg, worst, pos, whites)
    }

    private fun roundTo1(v: Double): Double = (v * 10).roundToInt() / 10.0
}
