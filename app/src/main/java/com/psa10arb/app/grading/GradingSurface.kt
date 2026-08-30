package com.psa10arb.app.grading

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Ported from grading_engine.py's _detect_print_lines / _detect_creases /
 * _detect_warp / _detect_stain. */
object GradingSurface {

    fun houghLinesAsList(lines: Mat): List<DoubleArray> =
        (0 until lines.rows()).map { i -> lines.get(i, 0) }

    // ── print lines ──────────────────────────────────────────────────────
    fun detectPrintLines(img: Mat): Boolean {
        val h = img.rows(); val w = img.cols()
        val art = img.submat((h * 0.10).toInt(), (h * 0.60).toInt(), (w * 0.05).toInt(), (w * 0.95).toInt())
        val ah = art.rows(); val aw = art.cols()

        val gray = Mat()
        Imgproc.cvtColor(art, gray, Imgproc.COLOR_BGR2GRAY)
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(blurred, edges, 30.0, 90.0)

        val linesMat = Mat()
        Imgproc.HoughLinesP(edges, linesMat, 1.0, Math.PI / 180, 40, 80.0, 8.0)
        val hlineYs = ArrayList<Double>()
        for (ln in houghLinesAsList(linesMat)) {
            val (x1, y1, x2, y2) = ln
            val dx = x2 - x1; val dy = y2 - y1
            if (dx != 0.0 && abs(Math.toDegrees(atan2(dy, dx))) <= 3.0) {
                hlineYs.add((y1 + y2) / 2.0)
            }
        }
        blurred.release(); edges.release(); linesMat.release()
        if (hlineYs.size < 3) { gray.release(); return false }

        // abs(fftshift(fft2(gray))) — see GradingFft for the derivation that
        // lets us compute the shifted center-band / overall means directly
        // from the unshifted magnitude spectrum without materializing a
        // fftshift'd array.
        val result = GradingFft.centerBandVsTotal(gray, ah)
        gray.release()
        return result.first > result.second * 1.6
    }

    private operator fun DoubleArray.component1() = this[0]
    private operator fun DoubleArray.component2() = this[1]
    private operator fun DoubleArray.component3() = this[2]
    private operator fun DoubleArray.component4() = this[3]

    // ── creases ──────────────────────────────────────────────────────────
    fun detectCreases(img: Mat): Boolean {
        val h = img.rows(); val w = img.cols()
        // Wider inset than the original scanner-tuned 5%/4% — a straight
        // real-card-vs-fill-color boundary near the true edge reads as a
        // strong, high-contrast line to Hough detection and was causing
        // false crease positives on eBay photos (see rotateAndCrop's note).
        val art = img.submat((h * 0.12).toInt(), (h * 0.88).toInt(), (w * 0.10).toInt(), (w * 0.90).toInt())
        val ah = art.rows(); val aw = art.cols()

        val gray = Mat()
        Imgproc.cvtColor(art, gray, Imgproc.COLOR_BGR2GRAY)
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(blur, edges, 20.0, 60.0)

        val minLen = max((min(ah, aw) * 0.25).toInt(), 40)
        val linesMat = Mat()
        Imgproc.HoughLinesP(edges, linesMat, 1.0, Math.PI / 180, 30, minLen.toDouble(), 15.0)
        val lines = houghLinesAsList(linesMat)
        blur.release(); edges.release(); linesMat.release()

        if (lines.isEmpty()) { gray.release(); return false }

        for (ln in lines) {
            val x1 = ln[0]; val y1 = ln[1]; val x2 = ln[2]; val y2 = ln[3]
            val dx = x2 - x1; val dy = y2 - y1
            val length = sqrt(dx * dx + dy * dy)
            if (length < minLen) continue
            val nx = -dy / length
            val ny = dx / length
            val diffs = ArrayList<Double>()
            for (t in GradingMath.linspaceDouble(0.15, 0.85, 20)) {
                val cx = x1 + t * dx
                val cy = y1 + t * dy
                for (d in intArrayOf(3, 6)) {
                    val p1x = (cx + nx * d).coerceIn(0.0, (aw - 1).toDouble()).toInt()
                    val p1y = (cy + ny * d).coerceIn(0.0, (ah - 1).toDouble()).toInt()
                    val p2x = (cx - nx * d).coerceIn(0.0, (aw - 1).toDouble()).toInt()
                    val p2y = (cy - ny * d).coerceIn(0.0, (ah - 1).toDouble()).toInt()
                    diffs.add(abs(MatRowCol.at8U(gray, p1y, p1x) - MatRowCol.at8U(gray, p2y, p2x)))
                }
            }
            if (diffs.isNotEmpty() && diffs.average() > 16.0) { gray.release(); return true }
        }
        gray.release()
        return false
    }

    // ── warp ─────────────────────────────────────────────────────────────
    fun detectWarp(img: Mat): Boolean {
        val gray = Mat()
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        val h = gray.rows(); val w = gray.cols()
        val kw = max(w / 8 * 2 + 1, 21)
        val kh = max(h / 8 * 2 + 1, 21)
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(kw.toDouble(), kh.toDouble()), 0.0)
        val gx = Mat(); val gy = Mat()
        Imgproc.Sobel(blurred, gx, CvType.CV_64F, 1, 0, 5)
        Imgproc.Sobel(blurred, gy, CvType.CV_64F, 0, 1, 5)

        val gxArr = flatten64F(gx); val gyArr = flatten64F(gy)
        val meanGx = gxArr.average(); val meanGy = gyArr.average()
        var magSum = 0.0
        for (i in gxArr.indices) magSum += sqrt(gxArr[i] * gxArr[i] + gyArr[i] * gyArr[i])
        val meanMag = max(if (gxArr.isNotEmpty()) magSum / gxArr.size else 0.0, 1.0)
        val coherence = sqrt(meanGx * meanGx + meanGy * meanGy) / meanMag

        val left = MatRowCol.flatten8U(blurredAs8U(blurred).submat(0, h, 0, w / 3)).average()
        val right = MatRowCol.flatten8U(blurredAs8U(blurred).submat(0, h, 2 * w / 3, w)).average()
        val top = MatRowCol.flatten8U(blurredAs8U(blurred).submat(0, h / 3, 0, w)).average()
        val bot = MatRowCol.flatten8U(blurredAs8U(blurred).submat(2 * h / 3, h, 0, w)).average()
        val thirds = max(abs(left - right), abs(top - bot))

        gray.release(); blurred.release(); gx.release(); gy.release()
        return coherence > 0.35 || thirds > 14.0
    }

    // GaussianBlur on a CV_8U source stays CV_8U — Python's cv2.GaussianBlur
    // on a uint8 array likewise returns uint8, so `blurred` here already IS
    // 8U; this wrapper just documents/asserts that instead of reconverting.
    private fun blurredAs8U(blurred: Mat): Mat = blurred

    private fun flatten64F(mat: Mat): DoubleArray {
        val h = mat.rows(); val w = mat.cols()
        val buf = DoubleArray(h * w)
        mat.get(0, 0, buf)
        return buf
    }

    // ── stain ────────────────────────────────────────────────────────────
    fun detectStain(img: Mat): Boolean {
        val h = img.rows(); val w = img.cols()
        val art = img.submat((h * 0.12).toInt(), (h * 0.88).toInt(), (w * 0.06).toInt(), (w * 0.94).toInt())
        val ah = art.rows(); val aw = art.cols()

        val lab = Mat()
        Imgproc.cvtColor(art, lab, Imgproc.COLOR_BGR2Lab)
        val channels = ArrayList<Mat>()
        Core.split(lab, channels)
        val lArr = MatRowCol.flatten8U(channels[0])
        val aArr = MatRowCol.flatten8U(channels[1])
        val bArr = MatRowCol.flatten8U(channels[2])
        channels.forEach { it.release() }; lab.release()

        val p10 = GradingMath.percentile(lArr, 10.0)
        val p90 = GradingMath.percentile(lArr, 90.0)
        val core = lArr.filter { it in p10..p90 }
        val lMu = if (core.isNotEmpty()) core.average() else 0.0
        val lSd = max(GradingMath.std(core.toDoubleArray()), 1.0)

        val n = lArr.size
        val chroma = DoubleArray(n) { i ->
            // Lab a/b channels are stored offset by 128 in OpenCV's 8U Lab —
            // Python's cv2 does the same (Lab a/b range -127..127 mapped to
            // 0..255), so this matches cv2.split(...) on an 8U Lab Mat as-is.
            val a = aArr[i] - 128.0
            val b = bArr[i] - 128.0
            sqrt(a * a + b * b)
        }
        val cP90 = max(GradingMath.percentile(chroma, 90.0), 1.0)

        var stainMaskBytes = ByteArray(n)
        for (i in 0 until n) {
            val lAnom = abs(lArr[i] - lMu) > 3.5 * lSd
            val haze = chroma[i] < cP90 * 0.08 && abs(lArr[i] - lMu) > 2.0 * lSd
            stainMaskBytes[i] = if (lAnom || haze) 1.toByte() else 0.toByte()
        }

        val anomalyMat = Mat(ah, aw, CvType.CV_8UC1)
        anomalyMat.put(0, 0, ByteArray(n) { i -> if (stainMaskBytes[i].toInt() != 0) (255).toByte() else 0.toByte() })

        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val nLbl = Imgproc.connectedComponentsWithStats(anomalyMat, labels, stats, centroids, 8)
        val minPx = max(6, (ah * aw * 0.0003).toInt())
        var stainPx = 0L
        for (i in 1 until nLbl) {
            val area = stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt()
            if (area >= minPx) stainPx += area
        }
        val pct = stainPx.toDouble() / max(ah * aw, 1)

        anomalyMat.release(); labels.release(); stats.release(); centroids.release()
        return pct > 0.008
    }
}
