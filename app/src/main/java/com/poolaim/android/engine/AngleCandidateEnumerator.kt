package com.poolaim.android.engine

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class AngleRayCandidate(
    val angleDegrees: Float,
    val lateralOffsetPixels: Float,
    val rayOrigin: FloatPoint,
    val ray: RaySample,
    val truncatedLengthCappedAt15: Int
)

/**
 * Enumerates only the confirmed geometric candidates from 0x10d848.
 *
 * Exact angle intervals/branch schedule and profile scoring remain outside
 * this class. Pass the angle sequence transcribed from the native CFG; no
 * default 0.5/0.1-degree schedule is fabricated here.
 */
class AngleCandidateEnumerator(
    private val raySampler: DirectionalRaySampler = DirectionalRaySampler(),
    private val minimumRayExtentExclusive: Float = 13f
) {
    fun lateralOrigins(seed: FloatPoint, angleDegrees: Float): Sequence<Pair<Float, FloatPoint>> {
        require(angleDegrees.isFinite())
        val radians = Math.toRadians(angleDegrees.toDouble())
        val sinTheta = sin(radians).toFloat()
        val cosTheta = cos(radians).toFloat()
        return (-4..4).asSequence().map { step ->
            val k = step * 0.5f
            val q = FloatPoint(seed.x - k * sinTheta, seed.y + k * cosTheta)
            k to q
        }
    }

    fun enumerate(
        mask: BinaryMaskView,
        seed: FloatPoint,
        angleScheduleDegrees: Iterable<Float>
    ): Sequence<AngleRayCandidate> = sequence {
        for (angle in angleScheduleDegrees) {
            require(angle.isFinite())
            val radians = Math.toRadians(angle.toDouble())
            val dx = cos(radians).toFloat()
            val dy = sin(radians).toFloat()

            for ((offset, origin) in lateralOrigins(seed, angle)) {
                val ray = raySampler.trace(mask, origin, dx, dy)
                if (ray.distanceFromOrigin <= minimumRayExtentExclusive) continue

                yield(
                    AngleRayCandidate(
                        angleDegrees = angle,
                        lateralOffsetPixels = offset,
                        rayOrigin = origin,
                        ray = ray,
                        truncatedLengthCappedAt15 = min(ray.distanceFromOrigin.toInt(), 15)
                    )
                )
            }
        }
    }
}

/**
 * Opaque native profile scoring is intentionally an interface only.
 * No implementation is supplied until 0x1108d0/0x10d19c semantics are known.
 */
interface NativeAngleProfileScorer {
    fun evaluate(candidate: AngleRayCandidate): NativeAngleProfileDecision
}

data class NativeAngleProfileDecision(
    val accepted: Boolean,
    val nativeMeasurement: Float? = null
)
