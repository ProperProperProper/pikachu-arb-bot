package com.psa10arb.app.grading

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

data class HoloResult(val isHolo: Boolean, val confidence: String)

/** Ported from grading_engine.py's _detect_holo. */
object GradingHolo {

    fun detectHolo(img: Mat): HoloResult {
        val h = img.rows()
        val w = img.cols()
        val art = img.submat((h * 0.08).toInt(), (h * 0.60).toInt(), (w * 0.05).toInt(), (w * 0.95).toInt())

        val hsv = Mat()
        Imgproc.cvtColor(art, hsv, Imgproc.COLOR_BGR2HSV)
        val channels = ArrayList<Mat>()
        Core.split(hsv, channels)
        val satMat = channels[1] // 8U
        val valMat = channels[2] // 8U

        val ah = satMat.rows()
        val aw = satMat.cols()

        // sat.astype(uint8) in the Python source is a numeric no-op here since
        // the HSV S channel is already 8U — Sobel runs on it directly.
        val sx = Mat(); val sy = Mat()
        Imgproc.Sobel(satMat, sx, CvType.CV_64F, 1, 0, 3)
        Imgproc.Sobel(satMat, sy, CvType.CV_64F, 0, 1, 3)
        val sxArr = flatten64F(sx)
        val syArr = flatten64F(sy)
        var gradSum = 0.0
        for (i in sxArr.indices) gradSum += sqrt(sxArr[i] * sxArr[i] + syArr[i] * syArr[i])
        val satGrad = if (sxArr.isNotEmpty()) gradSum / sxArr.size else 0.0

        val ph = 16
        val stds = ArrayList<Double>()
        var y = 0
        while (y < ah - ph) {
            var x = 0
            while (x < aw - ph) {
                val patch = MatRowCol.flatten8U(satMat.submat(y, y + ph, x, x + ph))
                stds.add(GradingMath.std(patch))
                x += ph
            }
            y += ph
        }
        val localStd = if (stds.isNotEmpty()) stds.average() else 0.0

        val valFlat = MatRowCol.flatten8U(valMat)
        val sparklePct = if (valFlat.isNotEmpty()) {
            valFlat.count { it > 235.0 }.toDouble() / valFlat.size * 100.0
        } else 0.0

        sx.release(); sy.release(); hsv.release(); channels.forEach { it.release() }

        val score = (if (satGrad > 90.0) 1 else 0) +
            (if (localStd > 27.0) 1 else 0) +
            (if (sparklePct > 0.4) 1 else 0)
        val isHolo = score >= 2
        val conf = when (score) { 3 -> "high"; 2 -> "medium"; 1 -> "low"; else -> "none" }
        return HoloResult(isHolo, conf)
    }

    private fun flatten64F(mat: Mat): DoubleArray {
        val h = mat.rows(); val w = mat.cols()
        val buf = DoubleArray(h * w)
        mat.get(0, 0, buf)
        return buf
    }
}
