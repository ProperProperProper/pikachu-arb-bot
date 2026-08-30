package com.psa10arb.app.grading

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.ArrayList

/**
 * Ported from grading_engine.py's `abs(fftshift(fft2(gray)))` step in
 * _detect_print_lines.
 *
 * Rather than building a shifted magnitude array (OpenCV has no built-in
 * fftshift), this computes the two quantities the Python code actually
 * needs — the mean magnitude of the shifted center 8-row band, and the
 * overall mean — directly from the *unshifted* DFT output:
 *
 *  - `total = mean(fft)` is shift-invariant (fftshift only permutes
 *    elements), so it's just the mean of the unshifted magnitude.
 *  - fftshift moves the DC term (unshifted row 0) to row `ah//2`. So the
 *    shifted rows [ah//2-4, ah//2+4) correspond exactly to unshifted rows
 *    {ah-4, ah-3, ah-2, ah-1, 0, 1, 2, 3} (i.e. the rows immediately
 *    surrounding the unshifted DC row, wrapping around).
 */
object GradingFft {

    fun centerBandVsTotal(gray: Mat, ah: Int): Pair<Double, Double> {
        val h = gray.rows()
        val w = gray.cols()

        val real = Mat()
        gray.convertTo(real, CvType.CV_32F)
        val imag = Mat.zeros(real.size(), CvType.CV_32F)
        val planesIn = ArrayList<Mat>()
        planesIn.add(real); planesIn.add(imag)
        val complexMat = Mat()
        Core.merge(planesIn, complexMat)

        Core.dft(complexMat, complexMat)

        val planesOut = ArrayList<Mat>()
        Core.split(complexMat, planesOut)
        val mag = Mat()
        Core.magnitude(planesOut[0], planesOut[1], mag)

        val total = Core.mean(mag).`val`[0]

        // Shifted rows [ah//2-4, ah//2+4) map back to unshifted rows
        // (offset mod ah) for offset in -4..3 — the shift-by-ah//2 term
        // cancels out of (r - shift) mod ah when r = shift + offset.
        val wrapRows = intArrayOf(-4, -3, -2, -1, 0, 1, 2, 3)
            .map { offset -> ((offset % ah) + ah) % ah }
            .toIntArray()
        var sum = 0.0
        var count = 0
        for (r in wrapRows.distinct()) {
            if (r < 0 || r >= h) continue
            val row = MatRowCol.floatRow(mag, r, w)
            sum += row.sum()
            count += row.size
        }
        val hband = if (count > 0) sum / count else 0.0

        real.release(); imag.release(); complexMat.release()
        planesOut.forEach { it.release() }
        mag.release()

        return hband to total
    }
}
