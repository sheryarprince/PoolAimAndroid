package com.poolaim.android.engine

import kotlin.math.sqrt

data class NativeTrigPair(val first: Double, val second: Double)

/** The external native trig-pair symbol is unresolved; callers supply its result. */
fun interface NativeTrigPairProvider {
    fun atRadians(radians: Double): NativeTrigPair
}

data class RadialScoreBreakdown(
    val score: Float,
    val numerator: Int,
    val denominator: Int,
    val innerBandWhiteAngleSamples: Int,
    val outerSlackBlackAngleSamples: Int
)

/**
 * Instruction-derived port of ARM64 raw-image RVA 0x112cac.
 * It deliberately requires a trig-pair provider and an already prepared mask.
 */
class NativeRadialMaskScorer(private val trig: NativeTrigPairProvider) {
    fun score(
        mask: BinaryMaskView,
        center: IntPoint,
        innerRadius: Int,
        outerRadius: Int,
        slackRadius: Int
    ): RadialScoreBreakdown {
        require(innerRadius > 0 && outerRadius >= innerRadius && slackRadius > 0)
        val firstRadius = innerRadius - slackRadius
        val lastRadius = outerRadius + slackRadius
        require(firstRadius >= 0)

        var numerator = 0
        var whiteBandAngleSamples = 0
        var blackSlackAngleSamples = 0
        var denominator = 0
        for (radius in firstRadius..lastRadius) {
            for (step in 0..8) {
                val angleRadians = step * 10.0 * PI / 180.0
                val pair = trig.atRadians(angleRadians)
                val dx = (radius * pair.first).toInt() // FCVTZS: truncation toward zero
                val dy = (radius * pair.second).toInt()
                val value0 = sample(mask, center.x + dx, center.y + dy)
                val value1 = sample(mask, center.x + dy, center.y + dx)
                val value2 = sample(mask, center.x - dx, center.y - dy)
                val value3 = sample(mask, center.x - dy, center.y - dx)
                if (radius in innerRadius..outerRadius &&
                    value0 == 0xff && value1 == 0xff && value2 == 0xff && value3 == 0xff
                ) {
                    numerator += 2
                    whiteBandAngleSamples++
                } else if (radius !in innerRadius..outerRadius &&
                    value0 == 0 && value1 == 0 && value2 == 0 && value3 == 0
                ) {
                    numerator += 1
                    blackSlackAngleSamples++
                }
            }
            denominator += 18
        }
        return RadialScoreBreakdown(
            score = numerator.toFloat() / denominator.toFloat(),
            numerator = numerator,
            denominator = denominator,
            innerBandWhiteAngleSamples = whiteBandAngleSamples,
            outerSlackBlackAngleSamples = blackSlackAngleSamples
        )
    }

    private fun sample(mask: BinaryMaskView, x: Int, y: Int): Int =
        mask.unsignedAt(x, y)
            ?: throw IllegalArgumentException("0x112cac caller crop must contain every radial sample")

    private companion object {
        const val PI = 3.141592653589793
    }
}

/** Interleaved scalar samples; use a separate instance per source/template. */
class FloatSampleImage(
    private val samples: FloatArray,
    val width: Int,
    val height: Int,
    val channels: Int = 1,
    val rowStrideSamples: Int = width * channels
) {
    init {
        require(width > 0 && height > 0 && channels > 0)
        require(rowStrideSamples >= width * channels)
        require(samples.size >= (height - 1) * rowStrideSamples + width * channels)
    }

    internal fun sample(x: Int, y: Int, channel: Int): Float =
        samples[y * rowStrideSamples + x * channels + channel]
}

/** One location in a raw TM_SQDIFF_NORMED formula surface. */
object NativeTemplateScore {
    /**
     * Evaluates the documented method-1 formula at one valid top-left offset.
     * Degenerate zero-energy inputs are rejected because the recovered binary's
     * exact OpenCV version/kernel behavior for that case is not established.
     */
    fun sqDiffNormedAt(image: FloatSampleImage, template: FloatSampleImage, x: Int, y: Int): Double {
        require(image.channels == template.channels)
        require(template.width <= image.width && template.height <= image.height)
        require(x in 0..(image.width - template.width) && y in 0..(image.height - template.height))
        var squaredDifference = 0.0
        var templateEnergy = 0.0
        var imageEnergy = 0.0
        for (ty in 0 until template.height) {
            for (tx in 0 until template.width) {
                for (channel in 0 until template.channels) {
                    val t = template.sample(tx, ty, channel).toDouble()
                    val v = image.sample(x + tx, y + ty, channel).toDouble()
                    require(t.isFinite() && v.isFinite())
                    val delta = t - v
                    squaredDifference += delta * delta
                    templateEnergy += t * t
                    imageEnergy += v * v
                }
            }
        }
        val denominator = sqrt(templateEnergy * imageEnergy)
        require(denominator > 0.0 && denominator.isFinite()) {
            "Zero/invalid energy follows unresolved OpenCV edge behavior"
        }
        return squaredDifference / denominator
    }

    /** Builds the method-1 score plane; location tie-breaking remains a caller decision. */
    fun scoreSurface(image: FloatSampleImage, template: FloatSampleImage): TemplateScoreSurface {
        require(template.width <= image.width && template.height <= image.height)
        val resultWidth = image.width - template.width + 1
        val resultHeight = image.height - template.height + 1
        val result = FloatArray(resultWidth * resultHeight)
        for (y in 0 until resultHeight) {
            for (x in 0 until resultWidth) {
                result[y * resultWidth + x] = sqDiffNormedAt(image, template, x, y).toFloat()
            }
        }
        return TemplateScoreSurface(resultWidth, resultHeight, result)
    }
}

data class TemplateScoreSurface(val width: Int, val height: Int, val values: FloatArray) {
    init {
        require(width > 0 && height > 0 && values.size == width * height)
    }

    fun scoreAt(x: Int, y: Int): Float {
        require(x in 0 until width && y in 0 until height)
        return values[y * width + x]
    }
}

data class ProfileScoreCandidate(val score: Float, val minimumLocation: IntPoint)
data class ProfileSelection(val category: Int, val point: IntPoint, val score: Float)

/** Exact observed 0x110c38 strict threshold order and category literals. */
object NativeProfileSelector {
    fun select(
        first: ProfileScoreCandidate,
        second: ProfileScoreCandidate,
        third: ProfileScoreCandidate,
        threshold: Float,
        coordinateOffset: Int
    ): ProfileSelection? {
        val candidates = listOf(8 to first, 9 to second, 7 to third)
        val selected = candidates.firstOrNull { (_, candidate) -> candidate.score < threshold } ?: return null
        val (category, candidate) = selected
        return ProfileSelection(
            category,
            IntPoint(candidate.minimumLocation.x + coordinateOffset, candidate.minimumLocation.y + coordinateOffset),
            candidate.score
        )
    }
}

