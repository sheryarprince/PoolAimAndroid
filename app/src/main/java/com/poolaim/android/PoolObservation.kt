package com.poolaim.android

/**
 * Source-independent pool state expressed in normalized table coordinates (0..1).
 *
 * Detector adapters must convert their source coordinates into this space before
 * geometry or overlay code consumes the observation.
 */
data class BallObservation(
    val id: Int,
    val x: Float,
    val y: Float,
    val radius: Float,
    val classProbabilities: Map<FrameAnalyzer.BallKind, Float>,
    val confidence: Float
) {
    fun isValid(): Boolean =
        x in 0f..1f && y in 0f..1f && radius > 0f &&
            radius <= 0.5f && confidence in 0f..1f &&
            classProbabilities.values.all { it.isFinite() && it in 0f..1f }
}

data class PoolObservation(
    val sourceId: String,
    val timestampNanos: Long,
    val tableCorners: List<Pair<Float, Float>>,
    val pocketCentres: List<Pair<Float, Float>>,
    val balls: List<BallObservation>,
    val cueDirection: Pair<Float, Float>? = null,
    val confidence: Float
) {
    fun isValid(): Boolean =
        sourceId.isNotBlank() && timestampNanos > 0L &&
            tableCorners.size == 4 && pocketCentres.size == 6 &&
            (tableCorners + pocketCentres).all { (x, y) ->
                x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f
            } &&
            balls.isNotEmpty() && balls.all { it.isValid() } &&
            confidence.isFinite() && confidence in 0f..1f &&
            (cueDirection == null || (
                cueDirection.first.isFinite() && cueDirection.second.isFinite()
            ))
}

/** A live visual detector or a future documented game-state adapter. */
interface PoolDetectionSource {
    val sourceId: String
    fun latestObservation(): PoolObservation?
}

/**
 * Conservative source selector. It rejects malformed/stale observations and
 * chooses the strongest current source; it does not silently average positions
 * from coordinate systems that may not yet be aligned.
 */
class PoolObservationFusion(
    private val maxAgeNanos: Long = 250_000_000L,
    private val minimumConfidence: Float = 0.45f
) {
    init {
        require(maxAgeNanos > 0L)
        require(minimumConfidence in 0f..1f)
    }

    fun select(
        observations: List<PoolObservation>,
        nowNanos: Long = System.nanoTime()
    ): PoolObservation? {
        return observations.asSequence()
            .filter { it.isValid() }
            .filter { it.timestampNanos <= nowNanos }
            .filter { nowNanos - it.timestampNanos <= maxAgeNanos }
            .filter { it.confidence >= minimumConfidence }
            .maxWithOrNull(
                compareBy<PoolObservation> { it.confidence }
                    .thenBy { it.timestampNanos }
            )
    }
}
