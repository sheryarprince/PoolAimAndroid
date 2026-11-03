package com.poolaim.android.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BallIdentityTest {
    @Test fun keepsOriginalTrainerIdsAndFamilies() {
        assertEquals(BallKind.CUE, BallIdentity.kindOf(0))
        for (id in 1..7) assertEquals(BallKind.SOLID, BallIdentity.kindOf(id))
        assertEquals(BallKind.EIGHT, BallIdentity.kindOf(8))
        for (id in 9..15) assertEquals(BallKind.STRIPE, BallIdentity.kindOf(id))
        assertEquals(BallKind.UNKNOWN, BallIdentity.kindOf(null))
        assertTrue((0..15).all { BallIdentity.isValid(it) })
        assertFalse(BallIdentity.isValid(16))
    }

    @Test fun stripeBaseColorsMatchTheirSolidCounterparts() {
        for (id in 1..7) assertEquals(id, BallIdentity.baseColorId(id + 8))
    }
}
