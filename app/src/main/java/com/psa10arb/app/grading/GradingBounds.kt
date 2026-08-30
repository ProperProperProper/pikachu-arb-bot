package com.psa10arb.app.grading

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val MIN_CARD_AREA_FRACTION = 0.05
private const val CONTOUR_APPROX_EPSILON = 0.02
private val FILL_COLOR = Scalar(238.0, 238.0, 238.0) // eBay-photo mode fill (light grey)

data class Bounds(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

/**
 * Ported from grading_engine.py's card-bounds / fine-alignment / perspective
 * quad-detection helpers. Only the eBay-photo (non-dark-mode) branches are
 * ported — grade_ebay_photo always forces _BG_DARK_MODE=False, and the
 * scanner dark-paper branches are unreachable from this app.
 */
object GradingBounds {

    fun findCardBounds(bgr: Mat): Bounds {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        val binary = Mat()
        Imgproc.threshold(blur, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 25.0))
        val closed = Mat()
        Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel)
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        gray.release(); blur.release(); binary.release(); closed.release(); hierarchy.release()

        if (contours.isEmpty()) return Bounds(0, 0, bgr.cols(), bgr.rows())
        val c = contours.maxByOrNull { Imgproc.contourArea(it) }!!
        val rect = Imgproc.boundingRect(c)
        contours.forEach { it.release() }
        return Bounds(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height)
    }

    fun fineAlign(img: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(blur, edges, 30.0, 100.0)

        val h = edges.rows(); val w = edges.cols()
        val bh = max(1, (h * 0.18).toInt())
        val bw = max(1, (w * 0.18).toInt())
        val mask = Mat.zeros(edges.size(), edges.type())
        Imgproc.rectangle(mask, Point(0.0, 0.0), Point((w - 1).toDouble(), (bh - 1).toDouble()), Scalar(255.0), -1)
        Imgproc.rectangle(mask, Point(0.0, (h - bh).toDouble()), Point((w - 1).toDouble(), (h - 1).toDouble()), Scalar(255.0), -1)
        Imgproc.rectangle(mask, Point(0.0, 0.0), Point((bw - 1).toDouble(), (h - 1).toDouble()), Scalar(255.0), -1)
        Imgproc.rectangle(mask, Point((w - bw).toDouble(), 0.0), Point((w - 1).toDouble(), (h - 1).toDouble()), Scalar(255.0), -1)
        val maskedEdges = Mat()
        Core.bitwise_and(edges, mask, maskedEdges)

        val linesMat = Mat()
        Imgproc.HoughLinesP(maskedEdges, linesMat, 1.0, Math.PI / 180, 40, 60.0, 15.0)
        val lines = GradingSurface.houghLinesAsList(linesMat)
        gray.release(); blur.release(); edges.release(); mask.release(); maskedEdges.release(); linesMat.release()

        if (lines.size < 2) return img
        val angles = ArrayList<Double>()
        for (ln in lines) {
            val dx = ln[2] - ln[0]; val dy = ln[3] - ln[1]
            if (dx == 0.0) continue
            val a = Math.toDegrees(atan2(dy, dx))
            if (abs(a) <= 8.0) angles.add(a)
        }
        if (angles.isEmpty()) return img
        val angle = GradingMath.median(angles.toDoubleArray())
        if (abs(angle) < 0.2) return img

        val rotMat = Imgproc.getRotationMatrix2D(Point(w / 2.0, h / 2.0), angle, 1.0)
        val out = Mat()
        Imgproc.warpAffine(img, out, rotMat, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_LANCZOS4, Core.BORDER_CONSTANT, FILL_COLOR)
        rotMat.release()
        return out
    }

    private fun orderCorners(pts: Array<Point>): Array<Point> {
        val sums = pts.map { it.x + it.y }
        val diffs = pts.map { it.y - it.x }
        val tl = pts[sums.indices.minByOrNull { sums[it] }!!]
        val tr = pts[diffs.indices.minByOrNull { diffs[it] }!!]
        val br = pts[sums.indices.maxByOrNull { sums[it] }!!]
        val bl = pts[diffs.indices.maxByOrNull { diffs[it] }!!]
        return arrayOf(tl, tr, br, bl)
    }

    fun findBestQuad(bgr: Mat): Array<Point>? {
        val h = bgr.rows(); val w = bgr.cols()
        val maxDim = 2000.0
        val scale = min(min(maxDim / w, maxDim / h), 1.0)
        val work = if (scale < 1.0) {
            val resized = Mat()
            Imgproc.resize(bgr, resized, Size(w * scale, h * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            resized
        } else bgr

        val ww = work.cols(); val wh = work.rows()
        val minAreaW = ww * wh * MIN_CARD_AREA_FRACTION

        val gray = Mat()
        Imgproc.cvtColor(work, gray, Imgproc.COLOR_BGR2GRAY)
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)

        var bestCnt: MatOfPoint? = null
        var bestCntArea = -1.0

        for ((lo, hi) in listOf(30.0 to 120.0, 15.0 to 60.0, 8.0 to 30.0)) {
            val edges = Mat()
            Imgproc.Canny(blur, edges, lo, hi)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.dilate(edges, edges, kernel)
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            edges.release(); hierarchy.release()

            val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }
            for (cnt in sorted.take(10)) {
                if (Imgproc.contourArea(cnt) < minAreaW) break
                val cnt2f = MatOfPoint2f(*cnt.toArray())
                val peri = Imgproc.arcLength(cnt2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(cnt2f, approx, CONTOUR_APPROX_EPSILON * peri, true)
                val approxPts = approx.toArray()
                cnt2f.release(); approx.release()
                if (approxPts.size == 4) {
                    val ordered = orderCorners(approxPts)
                    gray.release(); blur.release()
                    if (work !== bgr) work.release()
                    contours.forEach { it.release() }
                    return if (scale < 1.0) ordered.map { Point(it.x / scale, it.y / scale) }.toTypedArray() else ordered
                }
            }
            if (sorted.isNotEmpty() && Imgproc.contourArea(sorted[0]) >= minAreaW) {
                val area = Imgproc.contourArea(sorted[0])
                if (area > bestCntArea) {
                    bestCnt?.release()
                    bestCnt = sorted[0]
                    bestCntArea = area
                    sorted.drop(1).forEach { it.release() }
                } else {
                    sorted.forEach { it.release() }
                }
            } else {
                contours.forEach { it.release() }
            }
        }

        gray.release(); blur.release()
        if (work !== bgr) work.release()

        val result = if (bestCnt != null) {
            val cnt2f = MatOfPoint2f(*bestCnt.toArray())
            val rotRect = Imgproc.minAreaRect(cnt2f)
            val boxPts = Mat()
            Imgproc.boxPoints(rotRect, boxPts)
            val pts = Array(4) { i -> Point(boxPts.get(i, 0)[0], boxPts.get(i, 1)[0]) }
            cnt2f.release(); boxPts.release(); bestCnt.release()
            val ordered = orderCorners(pts)
            if (scale < 1.0) ordered.map { Point(it.x / scale, it.y / scale) }.toTypedArray() else ordered
        } else null

        return result
    }

    private fun clamp45(a: Double): Double = when {
        a > 45 -> a - 90
        a < -45 -> a + 90
        else -> a
    }

    fun rotateAndCrop(bgr: Mat, corners: Array<Point>): Mat {
        val h = bgr.rows(); val w = bgr.cols()
        val tl = corners[0]; val tr = corners[1]; val br = corners[2]; val bl = corners[3]

        val angleTop = clamp45(Math.toDegrees(atan2(tr.y - tl.y, tr.x - tl.x)))
        val angleBot = clamp45(Math.toDegrees(atan2(br.y - bl.y, br.x - bl.x)))
        val angle = (angleTop + angleBot) / 2.0
        val rad = Math.toRadians(angle)
        val ca = abs(cos(rad)); val sa = abs(sin(rad))
        val newW = (w * ca + h * sa).toInt() + 4
        val newH = (w * sa + h * ca).toInt() + 4
        val offX = (newW - w) / 2.0
        val offY = (newH - h) / 2.0

        val rotMat = Imgproc.getRotationMatrix2D(Point(w / 2.0, h / 2.0), angle, 1.0)
        rotMat.put(0, 2, rotMat.get(0, 2)[0] + offX)
        rotMat.put(1, 2, rotMat.get(1, 2)[0] + offY)

        val rotated = Mat()
        Imgproc.warpAffine(bgr, rotated, rotMat, Size(newW.toDouble(), newH.toDouble()), Imgproc.INTER_LANCZOS4, Core.BORDER_CONSTANT, FILL_COLOR)

        val m00 = rotMat.get(0, 0)[0]; val m01 = rotMat.get(0, 1)[0]; val m02 = rotMat.get(0, 2)[0]
        val m10 = rotMat.get(1, 0)[0]; val m11 = rotMat.get(1, 1)[0]; val m12 = rotMat.get(1, 2)[0]
        rotMat.release()

        val rotPts = arrayOf(tl, tr, br, bl).map { p ->
            Point(m00 * p.x + m01 * p.y + m02, m10 * p.x + m11 * p.y + m12)
        }
        val rx1 = rotPts.minOf { it.x }; val ry1 = rotPts.minOf { it.y }
        val rx2 = rotPts.maxOf { it.x }; val ry2 = rotPts.maxOf { it.y }
        // Trim INWARD from the quad's tight bounding box, rather than padding
        // outward like the original scanner-mode algorithm did. Rotating a
        // photo necessarily exposes solid-fill-color wedges at the corners
        // of the rotated canvas (there's no source pixel data there); the
        // original's outward pad risked pulling those wedges into the crop,
        // right where corner-whitening/crease detection sample from. Since
        // this app only ever grades eBay photos (never the scanner-mode
        // path those numbers were tuned for), trimming in is the safer
        // choice here — a few lost edge pixels beats corner/crease readings
        // being contaminated by synthetic fill color.
        val insetX = max(10, ((rx2 - rx1) * 0.04).toInt())
        val insetY = max(10, ((ry2 - ry1) * 0.04).toInt())
        val x1 = max(0, rx1.toInt() + insetX)
        val y1 = max(0, ry1.toInt() + insetY)
        val x2 = min(newW, rx2.toInt() - insetX)
        val y2 = min(newH, ry2.toInt() - insetY)

        if (x2 <= x1 || y2 <= y1) return rotated
        val crop = Mat(rotated, Rect(x1, y1, x2 - x1, y2 - y1))
        rotated.release()

        return if (crop.cols() > crop.rows()) {
            val out = Mat()
            Core.rotate(crop, out, Core.ROTATE_90_CLOCKWISE)
            crop.release()
            out
        } else crop
    }
}
