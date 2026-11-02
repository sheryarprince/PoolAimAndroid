package com.poolaim.android.engine

import kotlin.math.cos
import kotlin.math.sin

data class PrimaryCandidateAttempt(
    val distanceAlongRay: Int,
    val side: Int,
    val point: FloatPoint,
    val storedPointByTruncation: IntPoint,
    val validation: RadialValidation
)

/**
 * Enumerates and validates only the confirmed primary pass of 0x10cb68.
 *
 * The secondary perpendicular retry schedule is not fully specified in the
 * current REA evidence. This class therefore reports observations and never
 * claims to reproduce the final native selected point.
 */
class PrimaryCandidateEnumerator(
    private val validator: RadialMaskValidator
) {
    fun enumerate(
        mask: BinaryMaskView,
        basePoint: FloatPoint,
        angleDegrees: Float,
        baselineDistance: Float
    ): Sequence<PrimaryCandidateAttempt> {
        require(angleDegrees.isFinite())
        require(baselineDistance.isFinite())
        val radians = Math.toRadians(angleDegrees.toDouble())
        val directionX = cos(radians).toFloat()
        val directionY = sin(radians).toFloat()

        return sequence {
            for (j in 0..59) {
                for (side in intArrayOf(-1, 1)) {
                    val t = truncateToInt(baselineDistance + side * j.toFloat())
                    if (t < 12) continue

                    val candidate = FloatPoint(
                        basePoint.x + t * directionX,
                        basePoint.y + t * directionY
                    )
                    if (candidate.x < 0f || candidate.y < 0f ||
                        candidate.x > mask.width - 1f || candidate.y > mask.height - 1f
                    ) continue

                    yield(
                        PrimaryCandidateAttempt(
                            distanceAlongRay = t,
                            side = side,
                            point = candidate,
                            storedPointByTruncation = IntPoint(
                                truncateToInt(candidate.x),
                                truncateToInt(candidate.y)
                            ),
                            validation = validator.validate(mask, candidate)
                        )
                    )
                }
            }
        }
    }
}
