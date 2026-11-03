package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.engine.FloatPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Detects the game's own thin white aiming guides around the cue ball.
 * This deliberately does not inspect cue-stick colour, shaft texture, or felt colour.
 * A guide is accepted only when a near-white, narrow line continues radially
 * outwards from just beyond the cue-ball edge for a meaningful distance.
 */
class GameAimGuideDetector(
    private val angleSteps: Int = 360,
    private val minimumRunPx: Float = 22f
) {
    init { require(angleSteps >= 180) }

    fun detect(
        frame: CapturedRgbaFrame,
        cueCenter: FloatPoint,
        cueRadius: Float,
        table: TableBounds
    ): List<FloatPoint> {
        if (cueRadius <= 2f) return emptyList()
        val candidates = ArrayList<Pair<Float, FloatPoint>>()
        val step = 1.5f
        val start = cueRadius * 1.08f
        val inset = cueRadius * 0.55f

        for (i in 0 until angleSteps) {
            val angle = 2.0 * PI * i / angleSteps
            val ux = cos(angle).toFloat()
            val uy = sin(angle).toFloat()
            val px = -uy
            val py = ux
            val maxDistance = distanceToTableEdge(cueCenter, ux, uy, table) - inset
            if (maxDistance <= start + minimumRunPx) continue

            var run = 0f
            var longestRun = 0f
            var totalWhite = 0
            var totalSamples = 0
            var d = start
            while (d <= maxDistance) {
                val x = cueCenter.x + ux * d
                val y = cueCenter.y + uy * d
                // Sample across a narrow 3-pixel strip to tolerate anti-aliasing.
                val center = frame.pixelAt(x.toInt(), y.toInt())
                val sideA = frame.pixelAt((x + px * 1.1f).toInt(), (y + py * 1.1f).toInt())
                val sideB = frame.pixelAt((x - px * 1.1f).toInt(), (y - py * 1.1f).toInt())
                val hit = isGuideWhite(center) ||
                    (isGuideWhite(sideA) && isGuideWhite(sideB))
                totalSamples++
                if (hit) {
                    totalWhite++
                    run += step
                    longestRun = max(longestRun, run)
                } else {
                    // A single anti-aliased or occluded pixel should not split a guide.
                    run = max(0f, run - step * 1.5f)
                }
                d += step
            }
            if (longestRun >= max(minimumRunPx, cueRadius * 1.65f)) {
                val density = totalWhite.toFloat() / totalSamples.coerceAtLeast(1)
                val score = longestRun * (0.7f + density.coerceIn(0f, 1f) * 0.3f)
                candidates.add(score to FloatPoint(ux, uy))
            }
        }

        // Adjacent scan angles represent the same physical line. Keep the
        // strongest direction in each 7-degree neighbourhood.
        val selected = ArrayList<Pair<Float, FloatPoint>>()
        for (candidate in candidates.sortedByDescending { it.first }) {
            val duplicate = selected.any { existing ->
                val dot = existing.second.x * candidate.second.x +
                    existing.second.y * candidate.second.y
                val angle = kotlin.math.acos(dot.coerceIn(-1f, 1f)) * 180f / PI.toFloat()
                angle < 7f
            }
            if (!duplicate) selected.add(candidate)
            if (selected.size >= 3) break
        }
        return selected.map { it.second }
    }

    private fun isGuideWhite(pixel: com.poolaim.android.capture.RgbaPixel?): Boolean {
        if (pixel == null) return false
        val minimum = minOf(pixel.red, pixel.green, pixel.blue)
        val maximum = maxOf(pixel.red, pixel.green, pixel.blue)
        val brightness = (pixel.red + pixel.green + pixel.blue) / 3f
        return minimum >= 178 && brightness >= 194f && maximum - minimum <= 62
    }

    private fun distanceToTableEdge(
        origin: FloatPoint,
        dx: Float,
        dy: Float,
        table: TableBounds
    ): Float {
        var distance = Float.POSITIVE_INFINITY
        if (dx > 0.0001f) distance = minOf(distance, (table.right - origin.x) / dx)
        if (dx < -0.0001f) distance = minOf(distance, (table.left - origin.x) / dx)
        if (dy > 0.0001f) distance = minOf(distance, (table.bottom - origin.y) / dy)
        if (dy < -0.0001f) distance = minOf(distance, (table.top - origin.y) / dy)
        return distance
    }
}
