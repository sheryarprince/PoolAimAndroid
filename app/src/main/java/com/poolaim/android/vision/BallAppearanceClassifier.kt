package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.engine.FloatPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BallClassification(
    val ballId: Int?,
    val kind: BallKind,
    val confidence: Float,
    val whiteFraction: Float,
    val darkFraction: Float,
    val baseColorId: Int?
)

/**
 * Full-disc surface classifier. A deterministic area-distributed sample pattern
 * measures the actual white/black/coloured surface ratios. The tiny centre
 * number disc is omitted because it exists on object balls and would otherwise
 * bias the stripe/solid decision.
 *
 * This class never makes up a numeric identity: low-evidence candidates remain
 * UNKNOWN with a null ID for tracking or manual correction.
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
        val extent = radius * 0.98f

        // Golden-angle points are approximately uniform by area. Sampling is
        // deterministic, reproducible on-device, and skips the centre number
        // disc where solids and stripes both have white printed numerals.
        val count = 96
        val goldenAngle = 2.39996323
        for (i in 0 until count) {
            val rho = sqrt((i + 0.5) / count.toDouble()).toFloat() * 0.98f
            if (rho < 0.28f) continue
            val angle = i * goldenAngle
            val nx = (kotlin.math.cos(angle) * rho).toFloat()
            val ny = (kotlin.math.sin(angle) * rho).toFloat()
            val x = (center.x + nx * extent).toInt()
            val y = (center.y + ny * extent).toInt()
            val p = frame.pixelAt(x, y) ?: continue
            if (p.alpha < 100) continue
            sampled++

            val hsv = toHsv(p.red, p.green, p.blue)
            if (hsv.s < 0.30f && hsv.v > 0.68f) white++
            if (hsv.v < 0.24f) dark++

            if (hsv.s >= 0.25f && hsv.v >= 0.14f) {
                val color = colorId(hsv)
                if (color in 1..7) {
                    votes[color]++
                    valueSums[color] += hsv.v
                    chromatic++
                }
            }
        }

        if (sampled < 32) return unknown()
        val whiteFraction = white.toFloat() / sampled
        val darkFraction = dark.toFloat() / sampled

        // Cue ball is nearly all white/ivory. The central printed number spot
        // is excluded, so a stripe cannot become a false cue-ball candidate.
        if (whiteFraction >= 0.76f && darkFraction < 0.10f) {
            val confidence = (0.76f + (whiteFraction - 0.76f) * 0.90f).coerceIn(0.76f, 0.995f)
            return BallClassification(BallIdentity.CUE, BallKind.CUE, confidence,
                whiteFraction, darkFraction, null)
        }

        // The black eight ball has a dark body over most of its visible disc.
        if (darkFraction >= 0.62f && darkFraction > whiteFraction * 1.5f) {
            val confidence = (0.70f + (darkFraction - 0.62f) * 0.75f).coerceIn(0.70f, 0.99f)
            return BallClassification(BallIdentity.EIGHT, BallKind.EIGHT, confidence,
                whiteFraction, darkFraction, null)
        }

        if (chromatic < 8) {
            return BallClassification(null, BallKind.UNKNOWN, 0f, whiteFraction, darkFraction, null)
        }
        var bestColor = 0
        var bestVotes = 0
        for (id in 1..7) {
            if (votes[id] > bestVotes) {
                bestVotes = votes[id]
                bestColor = id
            }
        }
        if (bestColor == 0) {
            return BallClassification(null, BallKind.UNKNOWN, 0f, whiteFraction, darkFraction, null)
        }

        val voteRatio = bestVotes.toFloat() / chromatic
        val meanValue = valueSums[bestColor] / bestVotes.coerceAtLeast(1)

        // Red (3) and maroon (7) share a hue family in the trainer palette;
        // their sampled body brightness separates the entries.
        if (bestColor == 3 && meanValue < 0.53f) bestColor = 7
        if (bestColor == 7 && meanValue > 0.68f) bestColor = 3

        // Stripes expose a substantial white surface outside the coloured
        // middle band. Solids have only minor highlight/print-white coverage.
        val stripe = whiteFraction >= 0.27f && whiteFraction < 0.76f
        val id = if (stripe) bestColor + 8 else bestColor
        val kind = BallIdentity.kindOf(id)
        val appearanceEvidence = if (stripe) {
            ((whiteFraction - 0.25f) / 0.35f).coerceIn(0f, 1f)
        } else {
            ((0.27f - whiteFraction) / 0.27f).coerceIn(0f, 1f)
        }
        val confidence = (
            0.48f + voteRatio * 0.34f + appearanceEvidence * 0.18f
        ).coerceIn(0f, 0.98f)

        return BallClassification(id, kind, confidence, whiteFraction, darkFraction, bestColor)
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
}
