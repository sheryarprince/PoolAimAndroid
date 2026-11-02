package com.poolaim.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGeometryTest {
    @Test
    fun fcvtasRoundsTiesAwayFromZero() {
        assertEquals(1, roundTiesAwayFromZero(0.5))
        assertEquals(-1, roundTiesAwayFromZero(-0.5))
        assertEquals(2, roundTiesAwayFromZero(1.5))
        assertEquals(-2, roundTiesAwayFromZero(-1.5))
        assertEquals(1, roundTiesAwayFromZero(1.49))
        assertEquals(-1, roundTiesAwayFromZero(-1.49))
    }

    @Test
    fun binaryMaskHonorsRowStride() {
        val bytes = ByteArray(3 * 4)
        bytes[0] = 0xff.toByte()
        bytes[1] = 0x10
        bytes[4] = 0x22
        bytes[5] = 0xff.toByte()
        val mask = BinaryMaskView(bytes, width = 2, height = 2, rowStride = 4)

        assertEquals(0xff, mask.unsignedAt(0, 0))
        assertEquals(0xff, mask.unsignedAt(1, 1))
        assertNull(mask.unsignedAt(2, 1))
    }

    @Test
    fun raySamplerStopsAfterThreeConsecutiveMissesAndMeasuresLastMaskPixel() {
        val bytes = ByteArray(12 * 3)
        for (x in 2..4) bytes[1 * 12 + x] = 0xff.toByte()
        val mask = BinaryMaskView(bytes, width = 12, height = 3)

        val result = DirectionalRaySampler().trace(
            mask,
            origin = FloatPoint(0f, 1f),
            directionX = 1f,
            directionY = 0f
        )

        assertEquals(RayStopReason.THREE_CONSECUTIVE_MISSES, result.stopReason)
        assertEquals(IntPoint(4, 1), result.lastMatchingPixel)
        assertEquals(4f, result.distanceFromOrigin, 0.0001f)
        assertEquals(3, result.matchingPixelCount)
        assertEquals(8, result.samplesExamined)
    }

    @Test
    fun raySamplerStopsAtImageEdgeAfterMatchingPixels() {
        val mask = BinaryMaskView(ByteArray(5 * 5) { 0xff.toByte() }, 5, 5)
        val result = DirectionalRaySampler().trace(
            mask,
            origin = FloatPoint(2f, 2f),
            directionX = 1f,
            directionY = 0f
        )

        assertEquals(RayStopReason.IMAGE_EDGE, result.stopReason)
        assertEquals(2f, result.distanceFromOrigin, 0f)
        assertEquals(IntPoint(4, 2), result.lastMatchingPixel)
    }

    @Test
    fun radialValidatorUsesCallerProvidedNativeDirectionTableAndRecoveredGates() {
        val directions = DirectionTable(FloatArray(180) { 1f }, FloatArray(180) { 0f })
        val bytes = ByteArray(40 * 40)
        bytes[10 * 40 + 17] = 0xff.toByte()
        val mask = BinaryMaskView(bytes, 40, 40)

        val result = RadialMaskValidator(directions).validate(mask, FloatPoint(10f, 10f))

        assertTrue(result.accepted)
        assertNull(result.reason)
        assertEquals(180, result.outerHits)
        assertEquals(0, result.innerHits)
        assertEquals(0, result.hits9x9)
        assertEquals(1, result.hits16x16)
    }

    @Test
    fun radialValidatorRejectsInsufficientOuterRing() {
        val directions = DirectionTable(FloatArray(180) { 1f }, FloatArray(180) { 0f })
        val result = RadialMaskValidator(directions).validate(
            BinaryMaskView(ByteArray(40 * 40), 40, 40),
            FloatPoint(10f, 10f)
        )
        assertFalse(result.accepted)
        assertEquals(RadialRejectReason.INSUFFICIENT_OUTER_RING, result.reason)
    }

    @Test
    fun angleEnumeratorGeneratesNineLateralCandidatesWithoutClaimingProfileSelection() {
        val mask = BinaryMaskView(ByteArray(64 * 64) { 0xff.toByte() }, 64, 64)
        val candidates = AngleCandidateEnumerator().enumerate(
            mask,
            seed = FloatPoint(30f, 30f),
            angleScheduleDegrees = listOf(0f)
        ).toList()

        assertEquals(9, candidates.size)
        assertEquals(-2f, candidates.first().lateralOffsetPixels, 0f)
        assertEquals(2f, candidates.last().lateralOffsetPixels, 0f)
        assertEquals(FloatPoint(30f, 28f), candidates.first().rayOrigin)
        assertEquals(15, candidates.first().truncatedLengthCappedAt15)
        assertTrue(candidates.all { it.ray.distanceFromOrigin > 13f })
    }

    @Test
    fun primaryCandidateEnumeratorReportsValidatedPrimaryCandidatesOnly() {
        val directions = DirectionTable(FloatArray(180) { 1f }, FloatArray(180) { 0f })
        val bytes = ByteArray(40 * 40)
        bytes[10 * 40 + 29] = 0xff.toByte()
        val mask = BinaryMaskView(bytes, 40, 40)
        val validator = RadialMaskValidator(directions)

        val first = PrimaryCandidateEnumerator(validator).enumerate(
            mask,
            basePoint = FloatPoint(10f, 10f),
            angleDegrees = 0f,
            baselineDistance = 12f
        ).first()

        assertEquals(12, first.distanceAlongRay)
        assertEquals(-1, first.side)
        assertEquals(FloatPoint(22f, 10f), first.point)
        assertTrue(first.validation.accepted)
        assertEquals(IntPoint(22, 10), first.storedPointByTruncation)
    }

    @Test
    fun pointRefinementPlanBuildsRecoveredPrimaryGrid() {
        val plan = PointRefinementPlan.create(
            viewWidth = 1200,
            viewHeight = 800,
            resultSeed = IntPoint(500, 400),
            fallbackPoint = null,
            scale = 1f
        )

        assertNotNull(plan)
        plan!!
        assertEquals(IntRect(380, 280, 620, 520), plan.crop)
        assertEquals(4, plan.n)
        assertEquals(1, plan.gridStep)
        assertEquals(7, plan.radius7Scaled)
        assertEquals(8, plan.radius8Scaled)
        assertEquals(1, plan.radius1Scaled)
        assertEquals(81, plan.primaryGridPoints().count())
        assertFalse(plan.finePassRequired)
        assertEquals(10, plan.fallbackHitThreshold)
    }

    @Test
    fun pointRefinementPlanMarksUnrecoveredFinePassInsteadOfGuessingIt() {
        val plan = PointRefinementPlan.create(
            viewWidth = 1200,
            viewHeight = 800,
            resultSeed = IntPoint(500, 400),
            fallbackPoint = null,
            scale = 0.25f
        )

        assertNotNull(plan)
        assertTrue(plan!!.finePassRequired)
        assertEquals(16, plan.n)
        assertEquals(2, plan.gridStep)
    }

    @Test
    fun pointRefinementUsesFallbackWhenEitherSeedCoordinateIsMinusOne() {
        val plan = PointRefinementPlan.create(
            viewWidth = 1000,
            viewHeight = 700,
            resultSeed = IntPoint(-1, 50),
            fallbackPoint = IntPoint(100, 120),
            scale = 1f
        )
        assertNotNull(plan)
        assertEquals(IntPoint(100, 120), plan!!.seed)
    }

    @Test
    fun coordinateTransformTruncatesTowardZeroAfterScaleAndOffset() {
        val transform = NativeCoordinateTransform(
            scale = 2f,
            outputOffset = FloatPoint(10f, -2f)
        )
        assertEquals(IntPoint(12, -4), transform.toJavaPoint(FloatPoint(5f, -5f)))
    }
}
