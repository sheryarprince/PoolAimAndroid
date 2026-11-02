package com.poolaim.android.engine

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

enum class RayStopReason {
    THREE_CONSECUTIVE_MISSES,
    IMAGE_EDGE,
    ITERATION_GUARD
}

data class RaySample(
    val distanceFromOrigin: Float,
    val lastMatchingPixel: IntPoint?,
    val matchingPixelCount: Int,
    val samplesExamined: Int,
    val stopReason: RayStopReason
)

/**
 * Reproduction of the confirmed 0x10d048 byte-mask ray walk.
 *
 * It measures the distance to the last 0xff sample. It is not a cushion
 * reflection, bank-shot, or pocket-trajectory solver.
 */
class DirectionalRaySampler {
    fun traceDegrees(mask: BinaryMaskView, origin: FloatPoint, angleDegrees: Float): RaySample {
        require(angleDegrees.isFinite())
        val radians = Math.toRadians(angleDegrees.toDouble())
        return trace(mask, origin, cos(radians).toFloat(), sin(radians).toFloat())
    }

    fun trace(
        mask: BinaryMaskView,
        origin: FloatPoint,
        directionX: Float,
        directionY: Float
    ): RaySample {
        require(origin.x.isFinite() && origin.y.isFinite())
        require(directionX.isFinite() && directionY.isFinite())
        require(directionX != 0f || directionY != 0f) { "ray direction cannot be zero" }
        val directionLength = hypot(directionX.toDouble(), directionY.toDouble())
        require(kotlin.math.abs(directionLength - 1.0) < 0.001) {
            "The native ray walk expects a unit direction vector"
        }

        var last: IntPoint? = null
        var matches = 0
        var misses = 0
        var examined = 0
        // A unit ray must leave a finite image within width + height steps.
        val maxIterations = mask.width + mask.height + 4

        for (i in 0 until maxIterations) {
            val px = roundTiesAwayFromZero(origin.x.toDouble() + i.toDouble() * directionX.toDouble())
            val py = roundTiesAwayFromZero(origin.y.toDouble() + i.toDouble() * directionY.toDouble())
            val value = mask.unsignedAt(px, py)
            examined++

            if (value == null) {
                return result(origin, last, matches, examined, RayStopReason.IMAGE_EDGE)
            }

            if (value == 0xff) {
                last = IntPoint(px, py)
                matches++
                misses = 0
            } else {
                misses++
                if (misses == 3) {
                    return result(origin, last, matches, examined, RayStopReason.THREE_CONSECUTIVE_MISSES)
                }
            }
        }

        return result(origin, last, matches, examined, RayStopReason.ITERATION_GUARD)
    }

    private fun result(
        origin: FloatPoint,
        last: IntPoint?,
        matches: Int,
        examined: Int,
        reason: RayStopReason
    ): RaySample {
        val distance = if (last == null) {
            0f
        } else {
            sqrt(
                (origin.x.toDouble() - last.x.toDouble()) * (origin.x.toDouble() - last.x.toDouble()) +
                    (origin.y.toDouble() - last.y.toDouble()) * (origin.y.toDouble() - last.y.toDouble())
            ).toFloat()
        }
        return RaySample(distance, last, matches, examined, reason)
    }
}
