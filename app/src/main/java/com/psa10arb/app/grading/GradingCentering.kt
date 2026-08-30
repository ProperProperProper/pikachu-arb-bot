package com.psa10arb.app.grading

import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

private val GRADE_CEN_THR = linkedMapOf(10 to 55.0, 9 to 60.0, 8 to 65.0, 7 to 70.0)

data class CenteringResult(
    val grade: Int,
    val lr: String,
    val tb: String,
    val borderL: Double,
    val borderR: Double,
    val borderT: Double,
    val borderB: Double,
)

/**
 * Ported from grading_engine.py's _cen_border_px / _measure_centering.
 * Only the non-dark-mode (eBay photo) branch is ported — grade_ebay_photo
 * always forces _BG_DARK_MODE=False, so the scanner dark-paper branch is
 * dead code for this app's use (only ever grades downloaded eBay photos).
 */
object GradingCentering {

    private fun cenBorderPx(grayLine: DoubleArray, satLine: DoubleArray, reverse: Boolean): Double {
        val WIN = 5
        val LO = 228.0
        val HI = 242.0
        val MAX_STD = 8.0
        val SHADOW_THR = 70.0
        val SHADOW_ZONE = 60

        val gray = if (reverse) grayLine.reversedArray() else grayLine
        val sat = if (reverse) satLine.reversedArray() else satLine
        val n = gray.size
        var cardEdge = 0

        run {
            var i = 0
            while (i <= n - WIN) {
                val win = gray.copyOfRange(i, i + WIN)
                val m = win.average()
                val s = GradingMath.std(win)
                if (!(m in LO..HI && s < MAX_STD)) {
                    cardEdge = i
                    break
                }
                i++
            }
        }
        val limit = min(cardEdge + SHADOW_ZONE, n)
        while (cardEdge < limit && gray[cardEdge] < SHADOW_THR) cardEdge++

        val SAMPLE = 8
        val sampleEnd = min(cardEdge + SAMPLE, n)
        if (sampleEnd <= cardEdge) return 0.0
        val borderGray = gray.copyOfRange(cardEdge, sampleEnd).average()
        val borderSat = sat.copyOfRange(cardEdge, sampleEnd).average()

        val CHG_GRAY = 25.0
        val CHG_SAT = 40.0
        val CONSEC = 5
        var runLen = 0
        for (i in cardEdge until n) {
            val diffG = kotlin.math.abs(gray[i] - borderGray)
            val diffS = kotlin.math.abs(sat[i] - borderSat)
            if (diffG > CHG_GRAY || diffS > CHG_SAT) {
                runLen++
                if (runLen >= CONSEC) return max(0, i - CONSEC - cardEdge + 1).toDouble()
            } else {
                runLen = 0
            }
        }
        return 0.0
    }

    fun measureCentering(img: Mat): CenteringResult {
        val h = img.rows()
        val w = img.cols()
        val gray = Mat()
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        val hsv = Mat()
        Imgproc.cvtColor(img, hsv, Imgproc.COLOR_BGR2HSV)
        val hsvChannels = ArrayList<Mat>()
        org.opencv.core.Core.split(hsv, hsvChannels)
        val satMat = hsvChannels[1] // S channel

        val CEN_SAMPLES = 10
        val lv = DoubleArray(CEN_SAMPLES)
        val rv = DoubleArray(CEN_SAMPLES)
        val tv = DoubleArray(CEN_SAMPLES)
        val bv = DoubleArray(CEN_SAMPLES)

        val rows = GradingMath.linspaceInt(h * 0.15, h * 0.85, CEN_SAMPLES)
        for ((idx, row) in rows.withIndex()) {
            val grayRow = MatRowCol.grayRow(gray, row, w)
            val satRow = MatRowCol.byteRow(satMat, row, w)
            lv[idx] = cenBorderPx(grayRow, satRow, false)
            rv[idx] = cenBorderPx(grayRow, satRow, true)
        }
        val cols = GradingMath.linspaceInt(w * 0.15, w * 0.85, CEN_SAMPLES)
        for ((idx, col) in cols.withIndex()) {
            val grayCol = MatRowCol.grayCol(gray, col, h)
            val satCol = MatRowCol.byteCol(satMat, col, h)
            tv[idx] = cenBorderPx(grayCol, satCol, false)
            bv[idx] = cenBorderPx(grayCol, satCol, true)
        }

        val L = max(GradingMath.median(lv), 1.0)
        val R = max(GradingMath.median(rv), 1.0)
        val T = max(GradingMath.median(tv), 1.0)
        val B = max(GradingMath.median(bv), 1.0)
        val lp = L / (L + R) * 100
        val rp = R / (L + R) * 100
        val tp = T / (T + B) * 100
        val bp = B / (T + B) * 100
        val worseLr = max(lp, rp)
        val worseTb = max(tp, bp)

        var cg = 1
        for (g in GRADE_CEN_THR.keys.sorted()) {
            val thr = GRADE_CEN_THR.getValue(g)
            if (worseLr <= thr && worseTb <= thr) cg = g else break
        }

        gray.release(); hsv.release(); hsvChannels.forEach { it.release() }

        return CenteringResult(
            grade = max(cg, 1),
            lr = "${"%.0f".format(lp)}/${"%.0f".format(rp)}",
            tb = "${"%.0f".format(tp)}/${"%.0f".format(bp)}",
            borderL = L, borderR = R, borderT = T, borderB = B,
        )
    }
}
