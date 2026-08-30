package com.psa10arb.app.grading

import org.opencv.core.Mat

/** Pixel-access helpers bridging OpenCV Android's Mat API to the flat
 * DoubleArray style the ported numpy logic is written against. Assumes
 * single-channel 8U Mats unless noted. */
object MatRowCol {

    fun grayRow(mat: Mat, row: Int, width: Int): DoubleArray {
        val buf = ByteArray(width)
        mat.get(row, 0, buf)
        return DoubleArray(width) { i -> (buf[i].toInt() and 0xFF).toDouble() }
    }

    fun grayCol(mat: Mat, col: Int, height: Int): DoubleArray {
        val out = DoubleArray(height)
        val buf = ByteArray(1)
        for (y in 0 until height) {
            mat.get(y, col, buf)
            out[y] = (buf[0].toInt() and 0xFF).toDouble()
        }
        return out
    }

    fun byteRow(mat: Mat, row: Int, width: Int): DoubleArray = grayRow(mat, row, width)
    fun byteCol(mat: Mat, col: Int, height: Int): DoubleArray = grayCol(mat, col, height)

    /** Flattened row-major DoubleArray of a single-channel 8U Mat region. */
    fun flatten8U(mat: Mat): DoubleArray {
        val h = mat.rows()
        val w = mat.cols()
        val buf = ByteArray(h * w)
        mat.get(0, 0, buf)
        return DoubleArray(h * w) { i -> (buf[i].toInt() and 0xFF).toDouble() }
    }

    /** Flattened row-major DoubleArray of a single-channel 32F Mat region. */
    fun flatten32F(mat: Mat): DoubleArray {
        val h = mat.rows()
        val w = mat.cols()
        val buf = FloatArray(h * w)
        mat.get(0, 0, buf)
        return DoubleArray(h * w) { i -> buf[i].toDouble() }
    }

    fun floatRow(mat: Mat, row: Int, width: Int): DoubleArray {
        val buf = FloatArray(width)
        mat.get(row, 0, buf)
        return DoubleArray(width) { i -> buf[i].toDouble() }
    }

    /** Single-pixel read from a single-channel 8U Mat, as an unsigned 0..255 Double. */
    fun at8U(mat: Mat, y: Int, x: Int): Double {
        val buf = ByteArray(1)
        mat.get(y, x, buf)
        return (buf[0].toInt() and 0xFF).toDouble()
    }
}
