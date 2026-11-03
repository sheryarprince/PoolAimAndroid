package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.engine.FloatPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class BallAppearanceClassifierTest {
    private fun frameForBall(kind: String, base: Int = 1): CapturedRgbaFrame {
        val width = 100
        val height = 100
        val bytes = ByteArray(width * height * 4)
        val palette = BallIdentity.rgbForBaseColor(base) ?: intArrayOf(242, 193, 46)
        for (y in 0 until height) for (x in 0 until width) {
            val dx = x - 50
            val dy = y - 50
            val inside = dx * dx + dy * dy <= 15 * 15
            var rgb = intArrayOf(20, 130, 180)
            if (inside) {
                rgb = when (kind) {
                    "cue" -> intArrayOf(246, 243, 234)
                    "eight" -> intArrayOf(20, 20, 20)
                    "stripe" -> if (kotlin.math.abs(dy) <= 8) palette else intArrayOf(246, 243, 234)
                    else -> palette
                }
                // All numbered trainer balls have a small white number disc.
                if (kind != "cue" && dx * dx + dy * dy <= 3 * 3) rgb = intArrayOf(248, 246, 239)
            }
            val index = (y * width + x) * 4
            bytes[index] = rgb[0].toByte()
            bytes[index + 1] = rgb[1].toByte()
            bytes[index + 2] = rgb[2].toByte()
            bytes[index + 3] = 255.toByte()
        }
        return CapturedRgbaFrame.fromPlaneBytes(bytes, width, height, width * 4, 4)
    }

    @Test fun cueBallUsesWholeDiscRatherThanOneCentrePixel() {
        val result = BallAppearanceClassifier().classify(
            frameForBall("cue"), FloatPoint(50f, 50f), 15f
        )
        assertEquals(BallIdentity.CUE, result.ballId)
        assertEquals(BallKind.CUE, result.kind)
        assertTrue(result.confidence >= 0.65f)
    }

    @Test fun solidAndStripeWithSameBaseColorReceiveDifferentTrainerIds() {
        val classifier = BallAppearanceClassifier()
        val solid = classifier.classify(frameForBall("solid", 1), FloatPoint(50f, 50f), 15f)
        val stripe = classifier.classify(frameForBall("stripe", 1), FloatPoint(50f, 50f), 15f)
        assertEquals(1, solid.ballId)
        assertEquals(BallKind.SOLID, solid.kind)
        assertEquals(9, stripe.ballId)
        assertEquals(BallKind.STRIPE, stripe.kind)
    }

    @Test fun eightBallIsRecognizedFromDarkAreaFraction() {
        val result = BallAppearanceClassifier().classify(
            frameForBall("eight"), FloatPoint(50f, 50f), 15f
        )
        assertEquals(BallIdentity.EIGHT, result.ballId)
        assertEquals(BallKind.EIGHT, result.kind)
    }

    @Test fun trainerPaletteRoundTripsAllBaseColors() {
        val classifier = BallAppearanceClassifier()
        for (base in 1..7) {
            val solid = classifier.classify(frameForBall("solid", base), FloatPoint(50f, 50f), 15f)
            assertEquals("solid $base", base, solid.ballId)
            val stripe = classifier.classify(frameForBall("stripe", base), FloatPoint(50f, 50f), 15f)
            assertEquals("stripe ${base + 8}", base + 8, stripe.ballId)
        }
    }
}
