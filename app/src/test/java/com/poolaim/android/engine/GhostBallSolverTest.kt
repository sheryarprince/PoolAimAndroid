package com.poolaim.android.engine

import com.poolaim.android.vision.BallKind
import com.poolaim.android.vision.DetectedBall
import com.poolaim.android.vision.PoolScene
import com.poolaim.android.vision.TableBounds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostBallSolverTest {
    private fun ball(id: Int, x: Float, y: Float) = DetectedBall(
        FloatPoint(x, y), 3.8f, 0.95f, id, when (id) {
            0 -> BallKind.CUE
            8 -> BallKind.EIGHT
            in 9..15 -> BallKind.STRIPE
            else -> BallKind.SOLID
        }, 0.95f
    )

    @Test fun directSolutionsAreReturnedWhenBothPathsAreClear() {
        val scene = PoolScene(
            TableBounds(0f, 0f, 254f, 127f),
            listOf(ball(0, 45f, 63f), ball(3, 135f, 63f)),
            0.95f, 254, 127, "test scene"
        )
        val solutions = GhostBallSolver().solve(scene)
        assertTrue(solutions.isNotEmpty())
        assertTrue(solutions.any { it.type == ShotType.DIRECT && it.cuePathClear && it.objectPathClear })
    }

    @Test fun fullWidthBallWallBlocksAllCuePaths() {
        val balls = ArrayList<DetectedBall>()
        balls.add(ball(0, 28f, 63f))
        balls.add(ball(1, 185f, 63f))
        var id = 2
        var y = 4f
        while (id <= 14) {
            balls.add(ball(id, 88f, y))
            y += 9.9f
            id++
        }
        val scene = PoolScene(
            TableBounds(0f, 0f, 254f, 127f), balls,
            0.95f, 254, 127, "blocked scene"
        )
        val solutions = GhostBallSolver().solve(scene)
        assertFalse(solutions.any { it.objectBallId == 1 && it.cuePathClear })
    }
}
