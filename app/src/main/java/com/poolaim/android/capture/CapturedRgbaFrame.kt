package com.poolaim.android.capture

import android.graphics.PixelFormat
import android.media.Image

data class RgbaPixel(val red: Int, val green: Int, val blue: Int, val alpha: Int)

/**
 * Immutable, row-stride-aware snapshot of a MediaProjection RGBA_8888 frame.
 * This is raw capture only: it does not create the unresolved native binary mask.
 */
class CapturedRgbaFrame private constructor(
    private val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int
) {
    fun pixelAt(x: Int, y: Int): RgbaPixel? {
        if (x !in 0 until width || y !in 0 until height) return null
        val index = y * rowStride + x * pixelStride
        if (index < 0 || index + 3 >= bytes.size) return null
        return RgbaPixel(
            red = bytes[index].toInt() and 0xff,
            green = bytes[index + 1].toInt() and 0xff,
            blue = bytes[index + 2].toInt() and 0xff,
            alpha = bytes[index + 3].toInt() and 0xff
        )
    }

    companion object {
        fun fromPlaneBytes(
            source: ByteArray,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int
        ): CapturedRgbaFrame {
            require(width > 0 && height > 0)
            require(pixelStride >= 4)
            require(rowStride >= width * pixelStride)
            val requiredBytes = (height - 1) * rowStride + (width - 1) * pixelStride + 4
            require(source.size >= requiredBytes)
            return CapturedRgbaFrame(source.copyOf(), width, height, rowStride, pixelStride)
        }

        fun copyFrom(image: Image): CapturedRgbaFrame {
            require(image.format == PixelFormat.RGBA_8888) { "Expected RGBA_8888 ImageReader output" }
            val plane = image.planes.firstOrNull() ?: error("Image has no planes")
            val duplicate = plane.buffer.duplicate()
            val snapshot = ByteArray(duplicate.remaining())
            duplicate.get(snapshot)
            return fromPlaneBytes(
                snapshot,
                image.width,
                image.height,
                plane.rowStride,
                plane.pixelStride
            )
        }
    }
}
