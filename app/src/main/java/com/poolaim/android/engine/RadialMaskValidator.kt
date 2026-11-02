package com.poolaim.android.engine

enum class RadialRejectReason {
    OUTSIDE_IMAGE,
    INSUFFICIENT_OUTER_RING,
    TOO_MANY_INNER_RING_HITS,
    TOO_MANY_9X9_HITS,
    TOO_MANY_16X16_HITS
}

data class RadialValidation(
    val accepted: Boolean,
    val reason: RadialRejectReason?,
    val outerHits: Int,
    val innerHits: Int,
    val hits9x9: Int,
    val hits16x16: Int
)

/**
 * Reproduction of the confirmed 0x10c5e0 radial and local-density gates.
 *
 * The caller supplies the native 180-entry direction table. The exact native
 * initialization of those arrays has not yet been verified.
 */
class RadialMaskValidator(
    private val directions: DirectionTable,
    private val outerThreshold: Float = 0.85f
) {
    init {
        require(outerThreshold in 0f..1f)
    }

    fun validate(mask: BinaryMaskView, point: FloatPoint): RadialValidation {
        val empty = RadialValidation(false, RadialRejectReason.OUTSIDE_IMAGE, 0, 0, 0, 0)
        if (!point.x.isFinite() || !point.y.isFinite()) return empty

        // The recovered caller uses radii 6 and 8; the helper checks the
        // 6..8 outer band and 5..6 inner band.
        val margin = 8f
        if (point.x < 0f || point.y < 0f ||
            point.x >= mask.width || point.y >= mask.height ||
            point.x - margin < 1f || point.y - margin < 1f ||
            point.x + margin >= mask.width - 1f ||
            point.y + margin >= mask.height - 1f
        ) return empty

        var outerHits = 0
        for (index in 0 until 180) {
            var hit = false
            for (radius in 6..8) {
                val x = roundTiesAwayFromZero(point.x.toDouble() + radius * directions.x(index).toDouble())
                val y = roundTiesAwayFromZero(point.y.toDouble() + radius * directions.y(index).toDouble())
                if (mask.isWhiteMaskPixel(x, y)) {
                    hit = true
                    break
                }
            }
            if (hit) outerHits++
        }
        if (outerHits.toFloat() < outerThreshold * 180f) {
            return RadialValidation(false, RadialRejectReason.INSUFFICIENT_OUTER_RING, outerHits, 0, 0, 0)
        }

        var innerHits = 0
        for (index in 0 until 180) {
            var hit = false
            for (radius in 5..6) {
                val x = roundTiesAwayFromZero(point.x.toDouble() + radius * directions.x(index).toDouble())
                val y = roundTiesAwayFromZero(point.y.toDouble() + radius * directions.y(index).toDouble())
                if (mask.isWhiteMaskPixel(x, y)) {
                    hit = true
                    break
                }
            }
            if (hit) innerHits++
        }
        if (innerHits >= 0x78) {
            return RadialValidation(false, RadialRejectReason.TOO_MANY_INNER_RING_HITS, outerHits, innerHits, 0, 0)
        }

        val centerX = roundTiesAwayFromZero(point.x.toDouble())
        val centerY = roundTiesAwayFromZero(point.y.toDouble())
        var hits9 = 0
        for (dy in -4..4) {
            for (dx in -4..4) {
                if (mask.isWhiteMaskPixel(centerX + dx, centerY + dy)) hits9++
            }
        }
        if (hits9 >= 0x32) {
            return RadialValidation(false, RadialRejectReason.TOO_MANY_9X9_HITS, outerHits, innerHits, hits9, 0)
        }

        var hits16 = 0
        for (dy in -8..7) {
            for (dx in -8..7) {
                if (mask.isWhiteMaskPixel(centerX + dx, centerY + dy)) hits16++
            }
        }
        if (hits16 >= 0x96) {
            return RadialValidation(false, RadialRejectReason.TOO_MANY_16X16_HITS, outerHits, innerHits, hits9, hits16)
        }

        return RadialValidation(true, null, outerHits, innerHits, hits9, hits16)
    }
}
