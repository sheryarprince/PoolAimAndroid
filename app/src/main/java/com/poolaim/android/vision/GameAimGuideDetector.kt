package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.engine.FloatPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A white guide segment rendered by the game itself, in captured-screen pixels. */
data class GameAimGuideSegment(val start: FloatPoint, val end: FloatPoint)

/**
 * Finds the game's thin neutral-white aiming strokes anywhere inside the table.
 *
 * Important: this does NOT require a classified cue ball and does NOT use the
 * cue stick or felt colour to decide whether a guide exists. The table bounds
 * are used only to limit the scan and later clip the extension.
 */
class GameAimGuideDetector(
    private val angleSteps: Int = 180,
    private val minimumLengthPx: Float = 42f
) {
    init { require(angleSteps >= 90) }

    private data class WhitePoint(val x: Float, val y: Float)
    private data class Candidate(
        val votes: Int,
        val thetaIndex: Int,
        val rhoIndex: Int,
        val segment: GameAimGuideSegment,
        val angle: Float,
        val rho: Float
    )

    fun detect(frame: CapturedRgbaFrame, table: TableBounds): List<GameAimGuideSegment> {
        val left = (table.left + 8f).toInt().coerceAtLeast(0)
        val right = (table.right - 8f).toInt().coerceAtMost(frame.width - 1)
        val top = (table.top + 8f).toInt().coerceAtLeast(0)
        val bottom = (table.bottom - 8f).toInt().coerceAtMost(frame.height - 1)
        if (right <= left || bottom <= top) return emptyList()

        // Two-pixel sampling is enough for the game's 4–8 px white guide strokes.
        val points = ArrayList<WhitePoint>()
        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                val p = frame.pixelAt(x, y)
                if (isGuideWhite(p)) points.add(WhitePoint(x.toFloat(), y.toFloat()))
                x += 2
            }
            y += 2
        }
        if (points.size < 45) return emptyList()

        val diagonal = sqrt(
            (right - left).toFloat() * (right - left) +
                (bottom - top).toFloat() * (bottom - top)
        ).toInt()
        val rhoOffset = diagonal
        val rhoCount = diagonal * 2 + 1
        val accumulator = IntArray(angleSteps * rhoCount)
        val cosTable = FloatArray(angleSteps)
        val sinTable = FloatArray(angleSteps)

        for (a in 0 until angleSteps) {
            val theta = a * PI.toFloat() / angleSteps
            val c = cos(theta)
            val s = sin(theta)
            cosTable[a] = c
            sinTable[a] = s
            val base = a * rhoCount
            for (p in points) {
                val rho = (p.x - left) * c + (p.y - top) * s
                val ri = (rho + rhoOffset).toInt()
                if (ri in 0 until rhoCount) accumulator[base + ri]++
            }
        }

        val found = ArrayList<Candidate>()
        repeat(8) {
            var bestVotes = 0
            var bestA = -1
            var bestR = -1
            for (a in 0 until angleSteps) {
                val base = a * rhoCount
                for (r in 0 until rhoCount) {
                    val votes = accumulator[base + r]
                    if (votes > bestVotes) {
                        bestVotes = votes
                        bestA = a
                        bestR = r
                    }
                }
            }
            if (bestA < 0 || bestVotes < 22) return@repeat

            val c = cosTable[bestA]
            val s = sinTable[bestA]
            val rho = (bestR - rhoOffset).toFloat()
            val projections = ArrayList<Float>()
            for (p in points) {
                val distance = abs((p.x - left) * c + (p.y - top) * s - rho)
                if (distance <= 2.8f) {
                    projections.add(-(p.x - left) * s + (p.y - top) * c)
                }
            }
            projections.sort()

            var runStart = 0
            var bestStart = -1
            var bestEnd = -1
            for (i in 1..projections.size) {
                val split = i == projections.size ||
                    projections[i] - projections[i - 1] > 8f
                if (split) {
                    if (i > runStart && (bestStart < 0 ||
                            projections[i - 1] - projections[runStart] >
                            projections[bestEnd] - projections[bestStart])) {
                        bestStart = runStart
                        bestEnd = i - 1
                    }
                    runStart = i
                }
            }

            if (bestStart >= 0) {
                val t0 = projections[bestStart]
                val t1 = projections[bestEnd]
                val length = t1 - t0
                val density = (bestEnd - bestStart + 1).toFloat() / length.coerceAtLeast(1f)
                if (length >= minimumLengthPx && density >= 0.28f) {
                    val originX = left + c * rho
                    val originY = top + s * rho
                    val dx = -s
                    val dy = c
                    val segment = GameAimGuideSegment(
                        FloatPoint(originX + dx * t0, originY + dy * t0),
                        FloatPoint(originX + dx * t1, originY + dy * t1)
                    )
                    val angle = bestA * PI.toFloat() / angleSteps
                    val duplicate = found.any {
                        val angleDiff = minOf(
                            abs(it.angle - angle),
                            PI.toFloat() - abs(it.angle - angle)
                        )
                        angleDiff < 0.10f && abs(it.rho - rho) < 12f
                    }
                    if (!duplicate) found.add(
                        Candidate(bestVotes, bestA, bestR, segment, angle, rho)
                    )
                }
            }

            // Suppress the same physical stroke and nearby accumulator peaks.
            for (da in -4..4) for (dr in -8..8) {
                val a = bestA + da
                val r = bestR + dr
                if (a in 0 until angleSteps && r in 0 until rhoCount) {
                    accumulator[a * rhoCount + r] = 0
                }
            }
        }
        return found.sortedByDescending { it.votes }
            .take(4)
            .map { it.segment }
    }

    private fun isGuideWhite(pixel: com.poolaim.android.capture.RgbaPixel?): Boolean {
        if (pixel == null || pixel.alpha < 180) return false
        val minimum = minOf(pixel.red, pixel.green, pixel.blue)
        val maximum = maxOf(pixel.red, pixel.green, pixel.blue)
        val brightness = (pixel.red + pixel.green + pixel.blue) / 3f
        return minimum >= 178 && brightness >= 205f && maximum - minimum <= 58
    }
}
