package com.poolaim.android

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * First-stage, on-device frame preprocessing.
 *
 * This deliberately does NOT claim to detect pool balls yet. The custom
 * ball detector will be plugged in after we have representative labelled
 * gameplay frames. OpenCV handles deterministic image preprocessing while
 * the trained detector will classify cue/object balls.
 */
class FrameAnalyzer {
    data class Result(
        val frameWidth: Int,
        val frameHeight: Int,
        val processingWidth: Int,
        val processingHeight: Int,
        val edgeDensity: Double,
        val processingTimeMs: Long
    )

    private val initialized: Boolean = OpenCVLoader.initLocal()

    fun isReady(): Boolean = initialized

    fun analyze(bitmap: Bitmap): Result? {
        if (!initialized || bitmap.isRecycled) return null

        val started = System.nanoTime()
        val rgba = Mat()
        val gray = Mat()
        val small = Mat()
        val edges = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

            // Bound CPU/memory use while retaining enough pixels for ball detection.
            val scale = minOf(1.0, 960.0 / max(gray.cols(), gray.rows()).toDouble())
            val outWidth = max(1, (gray.cols() * scale).toInt())
            val outHeight = max(1, (gray.rows() * scale).toInt())
            Imgproc.resize(gray, small, Size(outWidth.toDouble(), outHeight.toDouble()))
            Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(small, edges, 60.0, 140.0)

            val edgePixels = org.opencv.core.Core.countNonZero(edges)
            val totalPixels = max(1, edges.rows() * edges.cols())
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L

            return Result(
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                processingWidth = outWidth,
                processingHeight = outHeight,
                edgeDensity = edgePixels.toDouble() / totalPixels,
                processingTimeMs = elapsedMs
            )
        } catch (t: Throwable) {
            Log.e("PoolAimAnalyzer", "Frame preprocessing failed", t)
            return null
        } finally {
            rgba.release()
            gray.release()
            small.release()
            edges.release()
        }
    }
}
