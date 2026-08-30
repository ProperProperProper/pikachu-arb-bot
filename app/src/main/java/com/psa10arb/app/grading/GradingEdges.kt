package com.psa10arb.app.grading

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

private val GRADE_EDGE_THR = linkedMapOf(10 to 0, 9 to 1, 8 to 3, 7 to 5)

data class EdgesResult(val grade: Int, val defects: Int, val zoneFlags: Map<String, Boolean>)

/** Ported from grading_engine.py's _analyze_edges. */
object GradingEdges {

    fun analyzeEdges(img: Mat): EdgesResult {
        val h = img.rows()
        val w = img.cols()
        val sp = max(5, (min(h, w) * 0.015).toInt())

        val gray = Mat()
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        val hsv = Mat()
        Imgproc.cvtColor(img, hsv, Imgproc.COLOR_BGR2HSV)
        val hsvChannels = ArrayList<Mat>()
        Core.split(hsv, hsvChannels)
        val satMat = hsvChannels[1]

        val halfW = w / 2
        val halfH = h / 2

        data class Zone(val name: String, val rowStart: Int, val rowEnd: Int, val colStart: Int, val colEnd: Int)
        val zones = listOf(
            Zone("top-L", 0, sp, 0, halfW),
            Zone("top-R", 0, sp, halfW, w),
            Zone("bot-L", h - sp, h, 0, halfW),
            Zone("bot-R", h - sp, h, halfW, w),
            Zone("left-T", 0, halfH, 0, sp),
            Zone("left-B", halfH, h, 0, sp),
            Zone("right-T", 0, halfH, w - sp, w),
            Zone("right-B", halfH, h, w - sp, w),
        )

        val zoneFlags = LinkedHashMap<String, Boolean>()
        val sidesHit = HashSet<String>()

        for (z in zones) {
            if (z.rowEnd <= z.rowStart || z.colEnd <= z.colStart) {
                zoneFlags[z.name] = false
                continue
            }
            val flatG = MatRowCol.flatten8U(gray.submat(z.rowStart, z.rowEnd, z.colStart, z.colEnd))
            val flatS = MatRowCol.flatten8U(satMat.submat(z.rowStart, z.rowEnd, z.colStart, z.colEnd))
            if (flatG.isEmpty()) {
                zoneFlags[z.name] = false
                continue
            }
            val bg = GradingMath.median(flatG)
            var anomBad = 0
            for (i in flatG.indices) {
                val anom = flatG[i] > bg + 45.0 || flatG[i] < bg - 45.0
                if (anom && flatS[i] < 60.0) anomBad++
            }
            val bad = anomBad.toDouble() / flatG.size > 0.10
            zoneFlags[z.name] = bad
            if (bad) sidesHit.add(z.name.substringBefore("-"))
        }

        gray.release(); hsv.release(); hsvChannels.forEach { it.release() }

        val defects = sidesHit.size
        var eg = 1
        for (g in GRADE_EDGE_THR.keys.sorted()) {
            val thr = GRADE_EDGE_THR.getValue(g)
            if (defects <= thr) eg = g else break
        }
        return EdgesResult(max(eg, 1), defects, zoneFlags)
    }
}
