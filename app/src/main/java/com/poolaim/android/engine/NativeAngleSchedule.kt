package com.poolaim.android.engine

import kotlin.math.min

/**
 * Angle schedules recovered from ARM64 raw-image RVA 0x10d848.
 *
 * The sweep uses 0.5 degree increments, with 0.1 degree increments in narrow
 * regions around 0, 90, 180, 270, and 360 degrees. The second pass scans a
 * 2 degree window around the stored best angle in 0.1 degree steps. Integer
 * tenths are used to avoid cumulative floating-point drift at branch limits.
 * This object enumerates angles only; it does not select a best angle or
 * implement the opaque profile scoring in 0x10d19c/0x10d848.
 */
object NativeAngleSchedule {
    /** Angles evaluated by the initial sweep; 360 degrees is the exclusive bound. */
    fun initialScan(): Sequence<Float> = sequence {
        var tenths = 0
        while (tenths < 3600) {
            yield(tenths / 10f)
            val increment = when {
                tenths < 10 -> 1
                tenths <= 885 -> 5
                tenths < 910 -> 1
                tenths <= 1785 -> 5
                tenths < 1815 -> 1
                tenths <= 2685 -> 5
                tenths < 2710 -> 1
                tenths <= 3585 -> 5
                else -> 1
            }
            tenths += increment
        }
    }

    /**
     * Local refinement angles around a native best angle. The native branch
     * skips refinement when the stored best angle is below 1 degree.
     */
    fun localRefinement(bestAngleDegrees: Float): Sequence<Float> = sequence {
        require(bestAngleDegrees.isFinite())
        if (bestAngleDegrees < 1f) return@sequence

        val start = bestAngleDegrees - 1f
        val endExclusive = min(bestAngleDegrees + 1f, 360f)
        var stepIndex = 0
        while (true) {
            val angle = start + stepIndex * 0.1f
            if (angle >= endExclusive) break
            yield(angle)
            stepIndex += 1
        }
    }
}
