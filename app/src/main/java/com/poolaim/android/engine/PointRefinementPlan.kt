package com.poolaim.android.engine

/**
 * Geometry-only plan for the confirmed part of 0x112e98. Candidate scoring,
 * the N > 8 fine-pass bounds, and the fallback helper remain unresolved.
 */
data class PointRefinementPlan(
    val seed: IntPoint,
    val crop: IntRect,
    val halfRoiExtent: Int,
    val n: Int,
    val gridStep: Int,
    val radius7Scaled: Int,
    val radius8Scaled: Int,
    val radius1Scaled: Int,
    val finePassRequired: Boolean,
    val normalScoreThreshold: Float = 0.3f,
    val fallbackHitThreshold: Int
) {
    fun primaryGridPoints(): Sequence<IntPoint> = sequence {
        for (dy in -n..n step gridStep) {
            for (dx in -n..n step gridStep) {
                yield(IntPoint(seed.x + dx, seed.y + dy))
            }
        }
    }

    companion object {
        fun create(
            viewWidth: Int,
            viewHeight: Int,
            resultSeed: IntPoint,
            fallbackPoint: IntPoint?,
            scale: Float
        ): PointRefinementPlan? {
            require(viewWidth > 0 && viewHeight > 0)
            require(scale.isFinite() && scale > 0f)

            val seed = if (resultSeed.x == -1 || resultSeed.y == -1) {
                fallbackPoint
            } else {
                resultSeed
            } ?: return null

            val half = (viewWidth * 0.2f).toInt() / 2
            val crop = IntRect(
                left = (seed.x - half).coerceAtLeast(0),
                top = (seed.y - half).coerceAtLeast(0),
                right = (seed.x + half).coerceAtMost(viewWidth),
                bottom = (seed.y + half).coerceAtMost(viewHeight)
            )
            val n = (4f / scale).toInt()
            val step = if (n <= 8) 1 else 2

            return PointRefinementPlan(
                seed = seed,
                crop = crop,
                halfRoiExtent = half,
                n = n,
                gridStep = step,
                radius7Scaled = (7f / scale).toInt(),
                radius8Scaled = (8f / scale).toInt(),
                radius1Scaled = (1f / scale).toInt(),
                finePassRequired = n > 8,
                fallbackHitThreshold = viewWidth / 120
            )
        }
    }
}

/** Opaque score implementation for 0x112cac; intentionally not implemented. */
fun interface NativePointScoreEvaluator {
    fun score(candidate: IntPoint, radius7: Int, radius8: Int, radius1: Int): Float
}
