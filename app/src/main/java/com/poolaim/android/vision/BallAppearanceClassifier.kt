package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.engine.FloatPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class BallClassification(
    val ballId: Int?,
    val kind: BallKind,
    val confidence: Float,
    val whiteFraction: Float,
    val darkFraction: Float,
    val baseColorId: Int?
)

/**
 * Full-disc classifier. It samples the ball interior (not one centre pixel),
 * measures white/black surface proportions, then votes over coloured pixels.
 * A stripe is distinguished from its matching solid by its larger white-area
 * fraction. The numbered white spot and specular highlights are deliberately
 * treated as a minority for solid balls.
 */
class BallAppearanceClassifier {
    private data class Hsv(val h: Float, val s: Float, val v: Float)

    fun classify(frame: CapturedRgbaFrame, center: FloatPoint, radius: Float): BallClassification {
        if (!center.x.isFinite() || !center.y.isFinite() || !radius.isFinite() || radius < 2f) {
            return unknown()
        }

        var sampled = 0
        var white = 0
        var dark = 0
        var chromatic = 0
        val votes = IntArray(8)
        val valueSums = FloatArray(8)
        val hueSums = FloatArray(8)
        val extent = radius * 0.76f
        val grid =  nineSamples
        for (gy in grid) for (gx in grid) {
            val nx = gx / 4f
            val ny = gy / 4f
            if (nx * nx + ny * ny > 0.76f * 0.76f) continue
            val x = (center.x + nx * extent).toInt()
            val y = (center.y + ny * extent).toInt()
            val p = frame.pixelAt(x, y) ?: continue
            if (p.alpha < 100) continue
            sampled++
            val hsv = toHsv(p.red, p.green, p.blue)
            if (hsv.s < 0.27f && hsv.v > 0.68f) white++
            if (hsv.v < 0.23f) dark++
            if (hsv.s >= 0.25f && hsv.v >= 0.14f) {
                val id = colorId(hsv)
                if (id in 1..7) {
                    votes[id]++
                    valueSums[id] += hsv.v
                    hueSums[id] += hsv.h
                    chromatic++
                }
            }
        }

        if (sampled < 18) return unknown()
        val whiteFraction = white.toFloat() / sampled
        val darkFraction = dark.toFloat() / sampled

        // The cue ball is almost entirely ivory/white, unlike the number spot
        // on a striped or solid object ball.
        if (whiteFraction >= 0.70f && darkFraction < 0.12f) {
            return BallClassification(
                BallIdentity.CUE, BallKind.CUE,
                (0.72f + (whiteFraction - 0.70f) * 0.85f).coerceIn(0.72f, 0.99f),
                whiteFraction, darkFraction, null
            )
        }

        // The eight ball has a dark body; the small numbered spot does not
        // dominate the disc-area sample.
        if (darkFraction >= 0.48f && darkFraction > whiteFraction * 1.15f) {
            return BallClassification(
                BallIdentity.EIGHT, BallKind.EIGHT,
                (0.62f + (darkFraction - 0.48f) * 0.8f).coerceIn(0.62f, 0.98f),
                whiteFraction, darkFraction, null
            )
        }

        if (chromatic < 4) return BallClassification(null, BallKind.UNKNOWN, 0f, whiteFraction, darkFraction, null)
        var bestId = 0
        var bestVotes = 0
        for (id in 1..7) {
            if (votes[id] > bestVotes) {
                bestVotes = votes[id]
                bestId = id
            }
        }
        if (bestId == 0) return BallClassification(null, BallKind.UNKNOWN, 0f, whiteFraction, darkFraction, null)

        val voteRatio = bestVotes.toFloat() / chromatic
        val meanValue = valueSums[bestId] / bestVotes.coerceAtLeast(1)
        // Trainer's red (3) and maroon (7) share the red hue family. Their
        // body brightness separates the two palette entries.
        if (bestId == 3 && meanValue < 0.53f) bestId = 7
        if (bestId == 7 && meanValue > 0.68f) bestId = 3

        val isStripe = whiteFraction >= 0.36f && whiteFraction < 0.70f
        val id = if (isStripe) bestId + 8 else bestId
        val kind = BallIdentity.kindOf(id)
        val stripeEvidence = if (isStripe) (whiteFraction - 0.30f) / 0.35f
            else (0.46f - whiteFraction) / 0.40f
        val confidence = (0.45f + voteRatio * 0.35f + stripeEvidence.coerceIn(0f, 1f) * 0.20f)
            .coerceIn(0f, 0.97f)

        return BallClassification(id, kind, confidence, whiteFraction, darkFraction, bestId)
    }

    private fun colorId(c: Hsv): Int {
        val h = c.h
        return when {
            h >= 350f || h < 11f -> if (c.v < 0.56f) 7 else 3
            h in 11f..34f -> 5
            h in 34f..66f -> 1
            h in 70f..175f -> 6
            h in 190f..250f -> 2
            h in 255f..315f -> 4
            else -> 0
        }
    }

    private fun toHsv(r0: Int, g0: Int, b0: Int): Hsv {
        val r = r0 / 255f
        val g = g0 / 255f
        val b = b0 / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val d = hi - lo
        var h = when {
            d < 0.0001f -> 0f
            hi == r -> 60f * (((g - b) / d) % 6f)
            hi == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0f) h += 360f
        return Hsv(h, if (hi <= 0f) 0f else d / hi, hi)
    }

    private fun unknown() = BallClassification(null, BallKind.UNKNOWN, 0f, 0f, 0f, null)

    private companion object {
        val nineSamples = intArrayOf(-4, -3, -2, -1, 0, 1, 2, 3, 4)
    }
}
