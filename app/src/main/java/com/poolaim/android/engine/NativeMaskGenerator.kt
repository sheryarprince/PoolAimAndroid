package com.poolaim.android.engine

import kotlin.math.abs

/** Three interleaved unsigned components as consumed by the native mask loops. */
class PackedTripletImage(
    private val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int
) {
    init {
        require(width > 0 && height > 0)
        require(rowStrideBytes >= width * 3)
        require(bytes.size >= (height - 1) * rowStrideBytes + width * 3)
    }

    fun componentAt(x: Int, y: Int, component: Int): Int {
        require(x in 0 until width && y in 0 until height && component in 0..2)
        return bytes[y * rowStrideBytes + x * 3 + component].toInt() and 0xff
    }
}

/** Mutable one-byte-per-pixel destination. Construction preserves caller bytes. */
class MutableMaskPlane(
    private val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int = width
) {
    init {
        require(width > 0 && height > 0)
        require(rowStrideBytes >= width)
        require(bytes.size >= (height - 1) * rowStrideBytes + width)
    }

    fun unsignedAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        return bytes[y * rowStrideBytes + x].toInt() and 0xff
    }

    internal fun write(x: Int, y: Int, value: Int) {
        require(x in 0 until width && y in 0 until height && value in 0..0xff)
        bytes[y * rowStrideBytes + x] = value.toByte()
    }

    /** Explicit caller operation; native predicate loops do not clear unmatched pixels. */
    fun clear(value: Int = 0) {
        require(value in 0..0xff)
        for (y in 0 until height) for (x in 0 until width) write(x, y, value)
    }

    fun copyBytes(): ByteArray = bytes.copyOf()
}

data class NativeMaskThresholds(
    val center: Int,
    val tolerance: Int,
    val component1UpperExclusive: Int,
    val component2LowerExclusive: Int,
    val component2UpperExclusive: Int
) {
    init {
        require(tolerance >= 0)
    }
}

/** Numeric classifier results observed at the 0x113fd4 routing branch. */
object NativeMaskCategory {
    const val PROFILE_8 = 8
    const val PROFILE_15 = 15
}

/**
 * Ports the byte writes in 0x11167c, 0x1117f0 and 0x111988.
 * This is downstream of an unrecovered image-to-triplet conversion. It does
 * not assign RGB/BGR semantics and does not initialize unmatched output bytes.
 */
object NativeMaskGenerator {
    private const val CATEGORY8_COMPONENT1_UPPER_EXCLUSIVE = 190

    fun apply(
        classifierCategory: Int,
        first: PackedTripletImage,
        output: MutableMaskPlane,
        thresholds: NativeMaskThresholds,
        secondForCategory8: PackedTripletImage? = null
    ) {
        require(first.width == output.width && first.height == output.height)
        if (classifierCategory == NativeMaskCategory.PROFILE_8) {
            requireNotNull(secondForCategory8) { "Category 8 requires its second native component image" }
            require(secondForCategory8.width == output.width && secondForCategory8.height == output.height)
        }

        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                val c0 = first.componentAt(x, y, 0)
                val c1 = first.componentAt(x, y, 1)
                val c2 = first.componentAt(x, y, 2)
                when (classifierCategory) {
                    NativeMaskCategory.PROFILE_15 -> applyCategory15(c0, c1, c2, x, y, output, thresholds)
                    NativeMaskCategory.PROFILE_8 -> applyCategory8(
                        c0, c1, c2,
                        secondForCategory8!!.componentAt(x, y, 0),
                        secondForCategory8.componentAt(x, y, 2),
                        x, y, output, thresholds
                    )
                    else -> if (basePredicate(c0, c1, c2, thresholds)) output.write(x, y, 0xff)
                }
            }
        }
    }

    private fun basePredicate(c0: Int, c1: Int, c2: Int, t: NativeMaskThresholds): Boolean =
        abs(c0 - t.center) > t.tolerance &&
            c1 < t.component1UpperExclusive &&
            c2 > t.component2LowerExclusive && c2 < t.component2UpperExclusive

    private fun applyCategory15(
        c0: Int, c1: Int, c2: Int, x: Int, y: Int,
        output: MutableMaskPlane, t: NativeMaskThresholds
    ) {
        if (c1 < t.component1UpperExclusive &&
            c2 > t.component2LowerExclusive && c2 < t.component2UpperExclusive
        ) {
            if (abs(c0 - 142) < 7) output.write(x, y, 0)
            else if (c0 < 50 || abs(c0 - t.center) > t.tolerance) output.write(x, y, 0xff)
        }
    }

    private fun applyCategory8(
        c0: Int, c1: Int, c2: Int, d0: Int, d2: Int,
        x: Int, y: Int, output: MutableMaskPlane, t: NativeMaskThresholds
    ) {
        val category8Upper = minOf(t.component1UpperExclusive, CATEGORY8_COMPONENT1_UPPER_EXCLUSIVE)
        if (abs(c0 - t.center) > t.tolerance &&
            c1 < category8Upper &&
            c2 > t.component2LowerExclusive && c2 < t.component2UpperExclusive
        ) output.write(x, y, 0xff)

        // Native unsigned range is 101 <= c1 < 190 for this branch.
        if (c0 < 7 && c1 in 101 until category8Upper &&
            c2 > t.component2LowerExclusive && c2 < t.component2UpperExclusive
        ) output.write(x, y, 0xff)
        if (d0 > 128 && abs(d0 - d2) < 5) output.write(x, y, 0xff)
    }
}
