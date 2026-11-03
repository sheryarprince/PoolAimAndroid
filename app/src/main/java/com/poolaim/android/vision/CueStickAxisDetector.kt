package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.engine.FloatPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Finds the visible cue shaft around the detected cue ball.
 *
 * It scores narrow radial strips in the playable table area. A wood-coloured
 * shaft is preferred, but parallel edge contrast also contributes so common
 * painted cue skins can still be detected. The returned vector is an axis:
 * its sign is irrelevant because both table-boundary intersections are used.
 */
class CueStickAxisDetector(
    private val angleSteps: Int = 180
) {
    init { require(angleSteps >= 90) }

    fun detect(
        frame: CapturedRgbaFrame,
        cueCenter: FloatPoint,
        ballRadius: Float,
        table: TableBounds
    ): FloatPoint? {
        if (ballRadius <= 2f) return null
        val maxLength = minOf(
            table.width, table.height
        ).coerceAtLeast(ballRadius * 8f) * 0.72f
        val start = ballRadius * 1.8f
        val step = max(3f, ballRadius * 0.42f)
        val end = minOf(maxLength, ballRadius * 30f)
        if (end <= start + step * 5f) return null

        var bestScore = 0f
        var best = FloatPoint(0f, 0f)
        for (i in 0 until angleSteps) {
            val angle = PI * i / angleSteps
            val ux = cos(angle).toFloat()
            val uy = sin(angle).toFloat()
            val px = -uy
            val py = ux
            var woodHits = 0
            var edgeHits = 0
            var samples = 0
            var run = 0
            var longestRun = 0
            var d = start
            while (d <= end) {
                val x = cueCenter.x + ux * d
                val y = cueCenter.y + uy * d
                if (x < table.left || x > table.right || y < table.top || y > table.bottom) break
                val center = frame.pixelAt(x.toInt(), y.toInt())
                val sideA = frame.pixelAt(
                    (x + px * ballRadius * 0.52f).toInt(),
                    (y + py * ballRadius * 0.52f).toInt()
                )
                val sideB = frame.pixelAt(
                    (x - px * ballRadius * 0.52f).toInt(),
                    (y - py * ballRadius * 0.52f).toInt()
                )
                if (center != null && sideA != null && sideB != null) {
                    samples++
                    val wood = isWoodCue(center.red, center.green, center.blue)
                    val sidesNotWood = !isWoodCue(sideA.red, sideA.green, sideA.blue) &&
                        !isWoodCue(sideB.red, sideB.green, sideB.blue)
                    if (wood) {
                        woodHits++
                        run++
                        longestRun = max(longestRun, run)
                    } else {
                        run = 0
                    }
                    if (wood && sidesNotWood) edgeHits++
                }
                d += step
            }
            if (samples < 8) continue
            val woodRatio = woodHits.toFloat() / samples
            val edgeRatio = edgeHits.toFloat() / samples
            val continuity = (longestRun.toFloat() / samples).coerceIn(0f, 1f)
            // Reject short isolated warm pixels; prefer a sustained shaft.
            val score = woodRatio * 0.45f + edgeRatio * 0.30f + continuity * 0.25f
            if (score > bestScore) {
                bestScore = score
                best = FloatPoint(ux, uy)
            }
        }
        return if (bestScore >= 0.20f) best else null
    }

    private fun isWoodCue(r: Int, g: Int, b: Int): Boolean {
        val brightness = (r + g + b) / 3
        return brightness in 48..238 &&
            r > g * 1.015f &&
            g > b * 1.015f &&
            r - b > 12
    }
}
