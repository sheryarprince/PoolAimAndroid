package com.poolaim.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeScoringAndMaskTest {
    private val thresholds = NativeMaskThresholds(
        center = 100,
        tolerance = 10,
        component1UpperExclusive = 200,
        component2LowerExclusive = 20,
        component2UpperExclusive = 200
    )

    @Test
    fun categoryOtherUsesStrictPredicatesAndPreservesUnmatchedBytesWithPitches() {
        val input = ByteArray(8) { 0 }
        input[0] = 120
        input[1] = 100
        input[2] = 50
        input[3] = 100
        input[4] = 100
        input[5] = 50
        val output = byteArrayOf(0x44, 0x55, 0x66, 0x77)
        NativeMaskGenerator.apply(
            classifierCategory = 7,
            first = PackedTripletImage(input, width = 2, height = 1, rowStrideBytes = 8),
            output = MutableMaskPlane(output, width = 2, height = 1, rowStrideBytes = 4),
            thresholds = thresholds
        )

        assertEquals(0xff, output[0].toInt() and 0xff)
        assertEquals(0x55, output[1].toInt() and 0xff)
    }

    @Test
    fun category15WritesZeroForHardBandAndWhiteForOtherConfiguredMatches() {
        val input = byteArrayOf(
            142.toByte(), 100, 50,
            40, 100, 50
        )
        val output = byteArrayOf(0x33, 0x44)
        NativeMaskGenerator.apply(
            classifierCategory = 15,
            first = PackedTripletImage(input, 2, 1, 6),
            output = MutableMaskPlane(output, 2, 1),
            thresholds = thresholds
        )

        assertEquals(0, output[0].toInt() and 0xff)
        assertEquals(0xff, output[1].toInt() and 0xff)
    }

    @Test
    fun category8IncludesSecondSourcePredicateAndUsesUnsignedRange() {
        val input = byteArrayOf(
            6, 150.toByte(), 50,
            6, 190.toByte(), 50
        )
        val second = byteArrayOf(
            130.toByte(), 0, 128.toByte(),
            128.toByte(), 0, 128.toByte()
        )
        val output = byteArrayOf(0x20, 0x21)
        NativeMaskGenerator.apply(
            classifierCategory = 8,
            first = PackedTripletImage(input, 2, 1, 6),
            output = MutableMaskPlane(output, 2, 1),
            thresholds = thresholds,
            secondForCategory8 = PackedTripletImage(second, 2, 1, 6)
        )

        assertEquals(0xff, output[0].toInt() and 0xff) // c1 150 and second-source branch
        assertEquals(0x21, output[1].toInt() and 0xff) // upper bound is exclusive at 190
    }

    @Test
    fun radialScoreCountsEachOfNineAnglesAndWeightsWhiteAndBlackBands() {
        val bytes = ByteArray(40 * 40)
        val center = IntPoint(20, 20)
        for (radius in 7..8) {
            bytes[center.y * 40 + center.x + radius] = 0xff.toByte()
            bytes[(center.y + radius) * 40 + center.x] = 0xff.toByte()
            bytes[center.y * 40 + center.x - radius] = 0xff.toByte()
            bytes[(center.y - radius) * 40 + center.x] = 0xff.toByte()
        }
        val scorer = NativeRadialMaskScorer(NativeTrigPairProvider { NativeTrigPair(1.0, 0.0) })
        val result = scorer.score(BinaryMaskView(bytes, 40, 40), center, 7, 8, 1)

        assertEquals(54, result.numerator)
        assertEquals(72, result.denominator)
        assertEquals(18, result.innerBandWhiteAngleSamples)
        assertEquals(18, result.outerSlackBlackAngleSamples)
        assertEquals(0.75f, result.score, 0f)
    }

    @Test
    fun radialScoreFailsClosedWhenCallerCropOmitsSample() {
        val scorer = NativeRadialMaskScorer(NativeTrigPairProvider { NativeTrigPair(1.0, 0.0) })
        var thrown = false
        try {
            scorer.score(BinaryMaskView(ByteArray(4), 2, 2), IntPoint(0, 0), 1, 1, 1)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun templateMethodOneFormulaAndStrictProfileSelection() {
        val image = FloatSampleImage(floatArrayOf(1f, 2f), width = 2, height = 1)
        val template = FloatSampleImage(floatArrayOf(1f), width = 1, height = 1)
        assertEquals(0.0, NativeTemplateScore.sqDiffNormedAt(image, template, 0, 0), 0.0)
        assertEquals(0.5, NativeTemplateScore.sqDiffNormedAt(image, template, 1, 0), 0.0)
        val surface = NativeTemplateScore.scoreSurface(image, template)
        assertEquals(2, surface.width)
        assertEquals(1, surface.height)
        assertEquals(0.5f, surface.scoreAt(1, 0), 0f)

        val selected = NativeProfileSelector.select(
            first = ProfileScoreCandidate(0.5f, IntPoint(3, 4)),
            second = ProfileScoreCandidate(0.25f, IntPoint(10, 20)),
            third = ProfileScoreCandidate(0.1f, IntPoint(0, 0)),
            threshold = 0.5f,
            coordinateOffset = 7
        )
        assertEquals(ProfileSelection(9, IntPoint(17, 27), 0.25f), selected)
        assertNull(
            NativeProfileSelector.select(
                ProfileScoreCandidate(0.5f, IntPoint(0, 0)),
                ProfileScoreCandidate(0.5f, IntPoint(0, 0)),
                ProfileScoreCandidate(0.5f, IntPoint(0, 0)),
                threshold = 0.5f,
                coordinateOffset = 0
            )
        )
    }

    @Test
    fun zeroEnergyTemplateScoreIsReportedAsUnresolvedInput() {
        val image = FloatSampleImage(floatArrayOf(0f), 1, 1)
        val template = FloatSampleImage(floatArrayOf(0f), 1, 1)
        var thrown = false
        try {
            NativeTemplateScore.sqDiffNormedAt(image, template, 0, 0)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}

