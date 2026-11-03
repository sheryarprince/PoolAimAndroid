package com.poolaim.android.engine

import kotlin.math.cos
import kotlin.math.sin

data class PrimaryCandidateAttempt(
    val distanceAlongRay: Int,
    val side: Int,
    val point: FloatPoint,
    val storedPointByTruncation: IntPoint,
    val validation: RadialValidation,
    val perpendicularRetryOffset: Int? = null
)

/**
 * Implements the verified primary attempt ordering for 0x10cb68. `enumerate`
 * yields primary candidates; `searchFirstAccepted` also applies the recovered
 * perpendicular retry schedule and stops at the first validator acceptance.
 * Later profile stages still determine the Java result.
 */
class PrimaryCandidateEnumerator(
    private val validator: CandidatePointValidator
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

    /**
     * Replays the confirmed first-valid search order from 0x10cb68. A failed
     * primary point is tested at perpendicular integer offsets -3,-2,-1,1,2,3;
     * offset zero is omitted because it is the already-tested primary point.
     * This returns a geometric candidate only; later profile stages still
     * determine the Java result.
     */
    fun searchFirstAccepted(
        mask: BinaryMaskView,
        basePoint: FloatPoint,
        angleDegrees: Float,
        baselineDistance: Float
    ): PrimaryCandidateAttempt? {
        require(angleDegrees.isFinite())
        val radians = Math.toRadians(angleDegrees.toDouble())
        val perpendicularX = -sin(radians).toFloat()
        val perpendicularY = cos(radians).toFloat()

        for (primary in enumerate(mask, basePoint, angleDegrees, baselineDistance)) {
            if (primary.validation.accepted) return primary

            for (offset in intArrayOf(-3, -2, -1, 1, 2, 3)) {
                val point = FloatPoint(
                    primary.point.x + offset * perpendicularX,
                    primary.point.y + offset * perpendicularY
                )
                val validation = validator.validate(mask, point)
                if (validation.accepted) {
                    return primary.copy(
                        point = point,
                        storedPointByTruncation = IntPoint(
                            truncateToInt(point.x),
                            truncateToInt(point.y)
                        ),
                        validation = validation,
                        perpendicularRetryOffset = offset
                    )
                }
            }
        }
        return null
    }
}

