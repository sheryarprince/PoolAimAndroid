package com.poolaim.android.engine

import kotlin.math.min

/**
 * Angle schedules recovered from ARM64 raw-image RVA 0x10d848.
 *
 * The first pass uses 0.5 degree increments, with 0.1 degree increments in
 * narrow regions around 0, 90, 180, 270, and 360 degrees. The second pass
 * scans a 2 degree window around a stored best angle in 0.1 degree increments.
 * This object only reproduces angle enumeration. It does not select a best
 * angle or implement the opaque profile scoring in 0x10d19c/0x10d848.
 */
object NativeAngleSchedule {
    /** Angles evaluated by the initial native sweep; 360 degrees is the stop bound. */
    fun initialScan(): Sequence<Float> = sequence {
        var angle = 0f
        while (angle < 360f) {
            yield(angle)
            angle = nextInitialAngle(angle)
        }
    }

    /**
     * Local refinement angles around a native best angle. The native branch
     * skips refinement when the stored best angle is below 1 degree.
     */
    fun localRefinement(bestAngleDegrees: Float): Sequence<Float> = sequence {
        require(bestAngleDegrees.isFinite())
        if (bestAngleDegrees < 1f) return@sequence

        var angle = bestAngleDegrees - 1f
        val endExclusive = min(bestAngleDegrees + 1f, 360f)
        while (angle < endExclusive) {
            yield(angle)
            // The local pass adds the float32 0.1 constant, matching FADD S.
            angle += 0.1f
        }
    }

    private fun nextInitialAngle(angle: Float): Float = when {
        angle < 1f -> (angle.toDouble() + 0.1).toFloat()
        angle <= 88.5f -> angle + 0.5f
        angle < 91f -> (angle.toDouble() + 0.1).toFloat()
        angle <= 178.5f -> angle + 0.5f
        angle < 181.5f -> (angle.toDouble() + 0.1).toFloat()
        angle <= 268.5f -> angle + 0.5f
        angle < 271f -> (angle.toDouble() + 0.1).toFloat()
        angle <= 358.5f -> angle + 0.5f
        else -> (angle.toDouble() + 0.1).toFloat()
    }
}

