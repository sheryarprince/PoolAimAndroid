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
    enum class BallKind { CUE, EIGHT, STRIPE, SOLID, UNKNOWN }
    data class BallCandidate(val x: Float, val y: Float, val radius: Float, val confidence: Float, val isCueBall: Boolean = false, val kind: BallKind = BallKind.UNKNOWN, val appearanceConfidence: Float = 0f)
    data class Result(
        val frameWidth: Int, val frameHeight: Int,
        val processingWidth: Int, val processingHeight: Int,
        val tableBounds: RectF?, val balls: List<BallCandidate>, val cueBall: BallCandidate?,
        /** Unit direction of the visually detected cue-stick/aim segment in frame coordinates. */
        val cueAxis: Pair<Float, Float>?,
        val edgeDensity: Double, val processingTimeMs: Long
    )

    private val initialized = OpenCVLoader.initLocal()
    private var previousBalls: List<BallCandidate> = emptyList()
    private var previousCue: BallCandidate? = null
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

            // Pool-table cloth is not always green. Build a broad chromatic cloth
            // proposal mask, then use contour geometry to reject unrelated regions.
            val greenBlue = Mat()
            val redLow = Mat()
            val redHigh = Mat()
            Core.inRange(hsv, Scalar(28.0, 38.0, 38.0), Scalar(142.0, 255.0, 255.0), greenBlue)
            Core.inRange(hsv, Scalar(0.0, 42.0, 35.0), Scalar(12.0, 255.0, 255.0), redLow)
            Core.inRange(hsv, Scalar(168.0, 42.0, 35.0), Scalar(180.0, 255.0, 255.0), redHigh)
            Core.bitwise_or(greenBlue, redLow, feltMask)
            Core.bitwise_or(feltMask, redHigh, feltMask)
            greenBlue.release(); redLow.release(); redHigh.release()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(17.0, 17.0))
            Imgproc.morphologyEx(feltMask, feltMask, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()
            val contourInput = feltMask.clone()
            Imgproc.findContours(contourInput, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            contourInput.release()

            var table: RectF? = null
            // Select the strongest geometrically plausible cloth region.
            val minTableArea = bitmap.width.toDouble() * bitmap.height * 0.07
            val tableContour = contours.asSequence()
                .map { contour -> contour to Imgproc.contourArea(contour) }
                .filter { (_, area) -> area >= minTableArea }
                .mapNotNull { (contour, area) ->
                    val rect = Imgproc.boundingRect(contour)
                    val aspect = rect.width.toFloat() / rect.height.coerceAtLeast(1)
                    if (aspect !in 1.15f..2.8f) null else {
                        val rectangularity = area / (rect.width.toDouble() * rect.height.coerceAtLeast(1))
                        Triple(rect, area * (0.65 + 0.35 * rectangularity), rectangularity)
                    }
                }
                .maxByOrNull { it.second }
            // The mask and its contours are in original frame coordinates.
            if (tableContour != null && tableContour.third > 0.35) {
                val r = tableContour.first
                table = RectF(r.x.toFloat(), r.y.toFloat(),
                    (r.x + r.width).toFloat(), (r.y + r.height).toFloat())
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
                Imgproc.HoughCircles(roi, circles, Imgproc.HOUGH_GRADIENT, 1.1, expectedRadius * 1.45, 95.0, 18.5, minRadius, maxRadius)
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
            val classified = trackedProposals.map { classifyAppearance(it, hsv, bitmap.width, bitmap.height) }
            // A modest temporal prior prevents a bright stripe from stealing cue identity.
            val whiteCandidates = classified.filter { it.kind == BallKind.CUE }.map { candidate ->
                val prior = previousCue?.let {
                    val distance = hypot(candidate.x - it.x, candidate.y - it.y)
                    (distance / max(1f, candidate.radius * 8f)).coerceAtMost(1f) * 18f
                } ?: 0f
                candidate to (candidate.appearanceConfidence * 100f - prior)
            }
            val cue = whiteCandidates.maxByOrNull { it.second }?.first
            previousCue = cue
            val finalBalls = classified.map { candidate ->
                if (cue != null && candidate.x == cue.x && candidate.y == cue.y)
                    candidate.copy(isCueBall = true, kind = BallKind.CUE)
                else candidate.copy(isCueBall = false)
            }
            // The aiming direction must come from pixels next to the cue ball, not from
            // object-ball/pocket geometry. Hough segments are restricted to the table ROI.
            val cueAxis = if (table != null && cue != null)
                detectCueAxis(edges, table, cue, bitmap.width, bitmap.height, outWidth, outHeight)
            else null
            val edgePixels = Core.countNonZero(edges)
            val totalPixels = max(1, edges.rows() * edges.cols())
            return Result(bitmap.width, bitmap.height, outWidth, outHeight, table, finalBalls, cue, cueAxis,
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


    /**
     * Finds a real image segment that begins close to the cue-ball perimeter.
     * The returned vector points from the cue ball toward the visible stick/guide.
     * It deliberately does not inspect object balls or pocket locations.
     */
    private fun detectCueAxis(
        edges: Mat,
        table: RectF,
        cue: BallCandidate,
        frameWidth: Int,
        frameHeight: Int,
        processingWidth: Int,
        processingHeight: Int
    ): Pair<Float, Float>? {
        val sx = processingWidth.toFloat() / frameWidth
        val sy = processingHeight.toFloat() / frameHeight
        val left = (table.left * sx).roundToInt().coerceIn(0, edges.cols() - 1)
        val top = (table.top * sy).roundToInt().coerceIn(0, edges.rows() - 1)
        val right = (table.right * sx).roundToInt().coerceIn(left + 1, edges.cols())
        val bottom = (table.bottom * sy).roundToInt().coerceIn(top + 1, edges.rows())
        val roi = edges.submat(top, bottom, left, right)
        val lines = Mat()
        try {
            val radius = cue.radius * ((sx + sy) * 0.5f)
            val cx = cue.x * sx
            val cy = cue.y * sy
            Imgproc.HoughLinesP(
                roi, lines, 1.0, Math.PI / 180.0,
                max(12, (radius * 1.15f).roundToInt()),
                max(12.0, radius * 2.4),
                max(4.0, radius * 0.65)
            )
            var best: Pair<Float, Float>? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in 0 until lines.rows()) {
                val v = lines.get(i, 0) ?: continue
                if (v.size < 4) continue
                val x1 = v[0] + left
                val y1 = v[1] + top
                val x2 = v[2] + left
                val y2 = v[3] + top
                val dx = x2 - x1
                val dy = y2 - y1
                val length = hypot(dx, dy)
                if (length < radius * 2.2) continue
                val ux = dx / length
                val uy = dy / length
                val t = (((cx - x1) * ux + (cy - y1) * uy) / length).coerceIn(0.0, 1.0)
                val nearX = x1 + ux * length * t
                val nearY = y1 + uy * length * t
                val distance = hypot(cx - nearX, cy - nearY)
                if (distance > radius * 2.6) continue
                val farX: Double
                val farY: Double
                val d1 = hypot(cx - x1, cy - y1)
                val d2 = hypot(cx - x2, cy - y2)
                if (d1 >= d2) { farX = x1; farY = y1 } else { farX = x2; farY = y2 }
                var vx = farX - cx
                var vy = farY - cy
                val norm = hypot(vx, vy)
                if (norm < radius * 1.5) continue
                vx /= norm; vy /= norm
                val score = length - distance * 2.4 + (radius * 0.25)
                if (score > bestScore) {
                    bestScore = score
                    best = Pair(vx.toFloat(), vy.toFloat())
                }
            }
            return best
        } finally {
            lines.release()
            roi.release()
        }
    }

    /** Inner-disk and outer-ring appearance features using a tiny per-ball ROI. */
    private fun classifyAppearance(ball: BallCandidate, hsv: Mat, frameWidth: Int, frameHeight: Int): BallCandidate {
        val x = (ball.x / frameWidth * hsv.cols()).roundToInt().coerceIn(0, hsv.cols() - 1)
        val y = (ball.y / frameHeight * hsv.rows()).roundToInt().coerceIn(0, hsv.rows() - 1)
        val radius = max(3, (ball.radius / frameWidth * hsv.cols()).roundToInt())
        val left = (x - radius).coerceAtLeast(0)
        val top = (y - radius).coerceAtLeast(0)
        val right = (x + radius + 1).coerceAtMost(hsv.cols())
        val bottom = (y + radius + 1).coerceAtMost(hsv.rows())
        val roi = hsv.submat(top, bottom, left, right)
        val inner = Mat.zeros(roi.rows(), roi.cols(), CvType.CV_8UC1)
        val ring = Mat.zeros(roi.rows(), roi.cols(), CvType.CV_8UC1)
        val full = Mat.zeros(roi.rows(), roi.cols(), CvType.CV_8UC1)
        val localRadius = min(radius, min(roi.cols(), roi.rows()) / 2 - 1).coerceAtLeast(2)
        val center = org.opencv.core.Point((x - left).toDouble(), (y - top).toDouble())
        Imgproc.circle(inner, center, max(2, (localRadius * 0.36f).roundToInt()), Scalar(255.0), -1)
        Imgproc.circle(ring, center, max(3, (localRadius * 0.88f).roundToInt()), Scalar(255.0), -1)
        Imgproc.circle(ring, center, max(2, (localRadius * 0.48f).roundToInt()), Scalar(0.0), -1)
        Imgproc.circle(full, center, max(3, (localRadius * 0.78f).roundToInt()), Scalar(255.0), -1)
        try {
            val innerScalar = Core.mean(roi, inner)
            val ringScalar = Core.mean(roi, ring)
            val mean = MatOfDouble()
            val deviation = MatOfDouble()
            Core.meanStdDev(roi, mean, deviation, full)
            val sd = deviation.toArray()
            mean.release()
            deviation.release()
            val sat = innerScalar.`val`[1]
            val value = innerScalar.`val`[2]
            val ringSat = ringScalar.`val`[1]
            val satSpread = sd[1]
            val valueSpread = sd[2]
            val contrast = kotlin.math.abs(sat - ringSat)
            val classified = when {
                value < 72.0 -> BallKind.EIGHT to ((72.0 - value) / 72.0).toFloat().coerceIn(0.25f, 0.95f)
                sat < 45.0 && value > 145.0 && satSpread < 34.0 && valueSpread < 62.0 ->
                    BallKind.CUE to ((1.0 - sat / 90.0) * (value / 255.0) * (1.0 - satSpread / 90.0)).toFloat().coerceIn(0.2f, 0.99f)
                contrast > 25.0 && (ringSat < sat * 0.62 || sat < ringSat * 0.62) ->
                    BallKind.STRIPE to (contrast / 90.0).toFloat().coerceIn(0.25f, 0.9f)
                sat > 45.0 -> BallKind.SOLID to (sat / 180.0).toFloat().coerceIn(0.2f, 0.85f)
                else -> BallKind.UNKNOWN to 0.25f
            }
            return ball.copy(kind = classified.first, appearanceConfidence = classified.second)
        } finally {
            inner.release()
            ring.release()
            full.release()
            roi.release()
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
