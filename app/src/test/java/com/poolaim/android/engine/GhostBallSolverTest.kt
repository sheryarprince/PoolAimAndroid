package com.poolaim.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostBallSolverTest {
    @Test fun createsDirectGhostBallCandidateForAlignedShot() {
        val solver = GhostBallSolver()
        val cue = PoolBallState(0, 60.0, 45.0)
        val objectBall = PoolBallState(1, 127.0, 45.0)
        val solutions = solver.solve(cue, listOf(objectBall))
        val direct = solutions.firstOrNull { it.objectBallId == 1 && it.pocketId == 1 && it.route == "direct" }
        assertTrue("Expected direct top-side pocket candidate", direct != null)
        assertEquals(119.38f, direct!!.ghost.x, 0.2f)
        assertEquals(45f, direct.ghost.y, 0.2f)
    }

    @Test fun calibratedTransformMapsTableCornersToLogicalDimensions() {
        val bounds = com.poolaim.android.vision.TableBounds(100f, 50f, 900f, 450f)
        val transform = CalibratedTableTransform(bounds)
        val topLeft = transform.toTable(FloatPoint(100f, 50f))
        val bottomRight = transform.toTable(FloatPoint(900f, 450f))
        assertEquals(0f, topLeft.x, 0.001f)
        assertEquals(0f, topLeft.y, 0.001f)
        assertEquals(254f, bottomRight.x, 0.001f)
        assertEquals(127f, bottomRight.y, 0.001f)
    }
}
