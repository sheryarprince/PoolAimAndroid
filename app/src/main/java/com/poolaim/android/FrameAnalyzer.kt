package com.poolaim.android

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** OpenCV table/ball proposal stage. A pool-specific trained model can refine these proposals later. */
class FrameAnalyzer {
    data class BallCandidate(val x: Float, val y: Float, val radius: Float, val confidence: Float, val isCueBall: Boolean = false)
    data class Result(
        val frameWidth: Int, val frameHeight: Int,
        val processingWidth: Int, val processingHeight: Int,
        val tableBounds: RectF?, val balls: List<BallCandidate>, val cueBall: BallCandidate?,
        val edgeDensity: Double, val processingTimeMs: Long
    )

    private val initialized = OpenCVLoader.initLocal()
    private var previousBalls: List<BallCandidate> = emptyList()
    private val trackingAlpha = 0.68f
    fun isReady() = initialized

    @Synchronized
    fun analyze(bitmap: Bitmap): Result? {
        if (!initialized || bitmap.isRecycled) return null
        val started = System.nanoTime()
        val rgba = Mat(); val rgb = Mat(); val hsv = Mat(); val gray = Mat()
        val small = Mat(); val feltMask = Mat(); val hierarchy = Mat(); val edges = Mat(); val circles = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val scale = minOf(1.0, 960.0 / max(gray.cols(), gray.rows()).toDouble())
            val outWidth = max(1, (gray.cols() * scale).roundToInt())
            val outHeight = max(1, (gray.rows() * scale).roundToInt())
            Imgproc.resize(gray, small, Size(outWidth.toDouble(), outHeight.toDouble()))
            Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(small, edges, 60.0, 140.0)

            Core.inRange(hsv, Scalar(68.0, 45.0, 80.0), Scalar(118.0, 255.0, 255.0), feltMask)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(17.0, 17.0))
            Imgproc.morphologyEx(feltMask, feltMask, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()
            val contourInput = feltMask.clone()
            Imgproc.findContours(contourInput, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            contourInput.release()

            var table: RectF? = null
            val largest = contours.maxByOrNull { Imgproc.contourArea(it) }
            if (largest != null && Imgproc.contourArea(largest) > outWidth * outHeight * 0.07) {
                val r = Imgproc.boundingRect(largest)
                val aspect = r.width.toFloat() / r.height.coerceAtLeast(1)
                if (aspect in 1.15f..2.8f) {
                    val sx = bitmap.width.toFloat() / outWidth
                    val sy = bitmap.height.toFloat() / outHeight
                    table = RectF(r.x * sx, r.y * sy, (r.x + r.width) * sx, (r.y + r.height) * sy)
                }
            }

            val tableMean = Core.mean(hsv, feltMask)
            val tableHue = tableMean.`val`[0]
            val tableSaturation = tableMean.`val`[1]
            val tableValue = tableMean.`val`[2]
            val proposals = ArrayList<BallCandidate>()
            if (table != null) {
                val sx = outWidth.toFloat() / bitmap.width
                val sy = outHeight.toFloat() / bitmap.height
                val left = (table.left * sx).roundToInt().coerceIn(0, outWidth - 1)
                val top = (table.top * sy).roundToInt().coerceIn(0, outHeight - 1)
                val right = (table.right * sx).roundToInt().coerceIn(left + 1, outWidth)
                val bottom = (table.bottom * sy).roundToInt().coerceIn(top + 1, outHeight)
                val roi = small.submat(top, bottom, left, right)
                val expectedRadius = max(4, (right - left) / 72)
                val minRadius = max(3, (expectedRadius * 0.62).roundToInt())
                val maxRadius = max(minRadius + 1, (expectedRadius * 1.42).roundToInt())
                Imgproc.HoughCircles(roi, circles, Imgproc.HOUGH_GRADIENT, 1.15, expectedRadius * 1.65, 105.0, 23.0, minRadius, maxRadius)
                val frameScaleX = bitmap.width.toFloat() / outWidth
                val frameScaleY = bitmap.height.toFloat() / outHeight
                for (i in 0 until circles.cols()) {
                    val v = circles.get(0, i) ?: continue
                    if (v.size < 3) continue
                    val cx = ((v[0] + left) * frameScaleX).toFloat()
                    val cy = ((v[1] + top) * frameScaleY).toFloat()
                    val radius = (v[2] * ((frameScaleX + frameScaleY) * 0.5f)).toFloat()
                    if (cx < table.left + radius || cx > table.right - radius || cy < table.top + radius || cy > table.bottom - radius) continue
                    val hx = (cx / bitmap.width * hsv.cols()).roundToInt().coerceIn(0, hsv.cols() - 1)
                    val hy = (cy / bitmap.height * hsv.rows()).roundToInt().coerceIn(0, hsv.rows() - 1)
                    val hr = max(2, (radius / bitmap.width * hsv.cols() * 0.42f).roundToInt())
                    val sampleMask = Mat.zeros(hsv.rows(), hsv.cols(), CvType.CV_8UC1)
                    Imgproc.circle(sampleMask, org.opencv.core.Point(hx.toDouble(), hy.toDouble()), hr, Scalar(255.0), -1)
                    val mean = Core.mean(hsv, sampleMask)
                    sampleMask.release()
                    val hue = mean.`val`[0]
                    val saturation = mean.`val`[1]
                    val value = mean.`val`[2]
                    val rawHueDelta = abs(hue - tableHue)
                    val hueDelta = min(rawHueDelta, 180.0 - rawHueDelta)
                    val colourDistance = sqrt(hueDelta * hueDelta +
                        (saturation - tableSaturation) * (saturation - tableSaturation) * 0.15 +
                        (value - tableValue) * (value - tableValue) * 0.12)
                    if (colourDistance < 28.0 || value < 18.0) continue
                    proposals.add(BallCandidate(cx, cy, radius, if (saturation < 55.0 && value > 155.0) 0.78f else 0.66f))
                }
                roi.release()
            }

            val trackedProposals = smoothCandidates(proposals)
            val whiteCandidates = trackedProposals.mapNotNull { candidate ->
                val x = (candidate.x / bitmap.width * hsv.cols()).roundToInt().coerceIn(0, hsv.cols() - 1)
                val y = (candidate.y / bitmap.height * hsv.rows()).roundToInt().coerceIn(0, hsv.rows() - 1)
                val r = max(2, (candidate.radius / bitmap.width * hsv.cols() * 0.68f).roundToInt())
                val mask = Mat.zeros(hsv.rows(), hsv.cols(), CvType.CV_8UC1)
                Imgproc.circle(mask, org.opencv.core.Point(x.toDouble(), y.toDouble()), r, Scalar(255.0), -1)
                val mean = MatOfDouble()
                val stddev = MatOfDouble()
                Core.meanStdDev(hsv, mean, stddev, mask)
                mask.release()
                val m = mean.toArray()
                val sd = stddev.toArray()
                mean.release(); stddev.release()
                val saturation = m[1]
                val value = m[2]
                val saturationSpread = sd[1]
                if (saturation < 55.0 && value > 150.0 && saturationSpread < 45.0)
                    candidate to (value - saturation * 0.6 - saturationSpread * 1.4)
                else null
            }
            val cue = whiteCandidates.maxByOrNull { it.second }?.first
            val finalBalls = trackedProposals.map { if (it == cue) it.copy(isCueBall = true) else it }
            val edgePixels = Core.countNonZero(edges)
            val totalPixels = max(1, edges.rows() * edges.cols())
            return Result(bitmap.width, bitmap.height, outWidth, outHeight, table, finalBalls, cue,
                edgePixels.toDouble() / totalPixels, (System.nanoTime() - started) / 1_000_000L)
        } catch (t: Throwable) {
            Log.e("PoolAimAnalyzer", "Frame analysis failed", t)
            return null
        } finally {
            contours.forEach { it.release() }
            rgba.release(); rgb.release(); hsv.release(); gray.release(); small.release()
            feltMask.release(); hierarchy.release(); edges.release(); circles.release()
        }
    }

    /** Greedy nearest-neighbour association with exponential smoothing. */
    private fun smoothCandidates(current: List<BallCandidate>): List<BallCandidate> {
        val unmatched = previousBalls.toMutableList()
        val smoothed = current.map { detection ->
            val index = unmatched.indices.minByOrNull { i ->
                val old = unmatched[i]
                val dx = detection.x - old.x
                val dy = detection.y - old.y
                dx * dx + dy * dy
            }
            if (index == null) detection else {
                val old = unmatched[index]
                val distance = hypot(detection.x - old.x, detection.y - old.y)
                unmatched.removeAt(index)
                if (distance <= max(detection.radius, old.radius) * 2.6f) {
                    detection.copy(
                        x = old.x * (1f - trackingAlpha) + detection.x * trackingAlpha,
                        y = old.y * (1f - trackingAlpha) + detection.y * trackingAlpha,
                        radius = old.radius * 0.35f + detection.radius * 0.65f
                    )
                } else detection
            }
        }
        previousBalls = smoothed
        return smoothed
    }


}
