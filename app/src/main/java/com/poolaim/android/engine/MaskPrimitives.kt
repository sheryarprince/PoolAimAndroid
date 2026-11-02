package com.poolaim.android.engine

import kotlin.math.ceil
import kotlin.math.floor

/**
 * One-byte-per-pixel binary mask view.
 *
 * The original mask-generation/thresholding stage is not yet recovered. This
 * class deliberately accepts an already-created mask and does not invent a
 * colour threshold.
 */
class BinaryMaskView(
    private val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int = width
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(rowStride >= width) { "rowStride must be at least width" }
        require(bytes.size >= rowStride * height) { "mask buffer is shorter than rowStride * height" }
    }

    /** Returns an unsigned byte, or null when the coordinate is outside the mask. */
    fun unsignedAt(x: Int, y: Int): Int? {
        if (x !in 0 until width || y !in 0 until height) return null
        return bytes[y * rowStride + x].toInt() and 0xff
    }

    fun isWhiteMaskPixel(x: Int, y: Int): Boolean = unsignedAt(x, y) == 0xff
}

data class FloatPoint(val x: Float, val y: Float)
data class IntPoint(val x: Int, val y: Int)
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right >= left)
        require(bottom >= top)
    }
}

/**
 * The native engine reads two 180-entry direction arrays. Their exact runtime
 * initialization has not yet been recovered, so callers must provide the
 * values; this class never synthesizes them with guessed trigonometry.
 */
class DirectionTable(xValues: FloatArray, yValues: FloatArray) {
    private val xs = xValues.copyOf()
    private val ys = yValues.copyOf()

    init {
        require(xs.size == 180 && ys.size == 180) {
            "The recovered radial test expects 180 direction entries"
        }
    }

    fun x(index: Int): Float = xs[index]
    fun y(index: Int): Float = ys[index]
}

/** ARM FCVTAS-compatible rounding: nearest integer, ties away from zero. */
fun roundTiesAwayFromZero(value: Double): Int {
    require(value.isFinite()) { "coordinate must be finite" }
    val rounded = if (value >= 0.0) floor(value + 0.5) else ceil(value - 0.5)
    require(rounded >= Int.MIN_VALUE.toDouble() && rounded <= Int.MAX_VALUE.toDouble()) {
        "rounded coordinate is outside Int range"
    }
    return rounded.toInt()
}

/** ARM-like truncation toward zero for finite values in the Int range. */
fun truncateToInt(value: Float): Int {
    require(value.isFinite()) { "value must be finite" }
    require(value.toDouble() >= Int.MIN_VALUE.toDouble() && value.toDouble() <= Int.MAX_VALUE.toDouble()) {
        "value is outside Int range"
    }
    return value.toInt()
}
