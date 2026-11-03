package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.engine.FloatPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Finds the live cue-shaft axis around the detected cue ball.
 *
 * Does not assume a wooden/brown cue. It searches for a long, narrow,
 * continuous contrast ridge against the table felt, so metallic, blue, white,
 * gold and patterned cue skins can be detected as well.
 */
class CueStickAxisDetector(
    private val angleSteps: Int = 360
) {
    init { require(angleSteps >= 90) }

    fun detect(
        frame: CapturedRgbaFrame,
        cueCenter: FloatPoint,
        ballRadius: Float,
        table: TableBounds
    ): FloatPoint? {
        if (ballRadius <= 2f) return null
        val maxLength = minOf(table.width, table.height) * 0.72f
        val start = ballRadius * 1.65f
        val step = max(2f, ballRadius * 0.30f)
        val end = minOf(maxLength, ballRadius * 34f)
        if (end <= start + step * 8f) return null

        var bestScore = 0f
        var best = FloatPoint(0f, 0f)
        for (i in 0 until angleSteps) {
            val angle = 2.0 * PI * i / angleSteps
            val ux = cos(angle).toFloat()
            val uy = sin(angle).toFloat()
            val px = -uy
            val py = ux
            var contrastHits = 0
            var samples = 0
            var run = 0
            var longestRun = 0
            var d = start
            while (d <= end) {
                val x = cueCenter.x + ux * d
                val y = cueCenter.y + uy * d
                if (x < table.left + 1f || x > table.right - 1f ||
                    y < table.top + 1f || y > table.bottom - 1f) break

                val center = frame.pixelAt(x.toInt(), y.toInt())
                val sideA = frame.pixelAt(
                    (x + px * ballRadius * 0.36f).toInt(),
                    (y + py * ballRadius * 0.36f).toInt()
                )
                val sideB = frame.pixelAt(
                    (x - px * ballRadius * 0.36f).toInt(),
                    (y - py * ballRadius * 0.36f).toInt()
                )
                if (center != null && sideA != null && sideB != null) {
                    samples++
                    val sideR = (sideA.red + sideB.red) * 0.5f
                    val sideG = (sideA.green + sideB.green) * 0.5f
                    val sideBlue = (sideA.blue + sideB.blue) * 0.5f
                    val centerLuma = luma(center.red, center.green, center.blue)
                    val sideLuma = luma(sideR, sideG, sideBlue)
                    val luminanceContrast = abs(centerLuma - sideLuma)
                    val colorContrast = (
                        abs(center.red - sideR) +
                            abs(center.green - sideG) +
                            abs(center.blue - sideBlue)
                        ) / 3f
                    val sideAgreement = abs(
                        luma(sideA.red, sideA.green, sideA.blue) -
                            luma(sideB.red, sideB.green, sideB.blue)
                    )
                    // A shaft is a narrow ridge: its centre differs from both
                    // flanking felt samples, which should usually agree.
                    val hit = (luminanceContrast >= 17f || colorContrast >= 23f) &&
                        sideAgreement < max(34f, luminanceContrast * 1.8f)
                    if (hit) {
                        contrastHits++
                        run++
                        longestRun = max(longestRun, run)
                    } else {
                        run = 0
                    }
                }
                d += step
            }
            if (samples < 10) continue
            val hitRatio = contrastHits.toFloat() / samples
            val continuity = longestRun.toFloat() / samples
            val score = hitRatio * 0.42f + continuity * 0.58f
            if (score > bestScore) {
                bestScore = score
                best = FloatPoint(ux, uy)
            }
        }
        // Require a reasonably long continuous shaft, not a single ball edge
        // or a short table marking.
        return if (bestScore >= 0.22f) best else null
    }

    private fun luma(r: Float, g: Float, b: Float): Float =
        0.299f * r + 0.587f * g + 0.114f * b

    private fun luma(r: Int, g: Int, b: Int): Float =
        luma(r.toFloat(), g.toFloat(), b.toFloat())
}
