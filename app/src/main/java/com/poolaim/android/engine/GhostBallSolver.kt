package com.poolaim.android.engine

import kotlin.math.*

/** Logical 8 Ball Pool table coordinates in centimetres, y increasing down-screen. */
data class PoolBallState(val id: Int, val x: Double, val y: Double)
data class PoolPocket(val id: Int, val x: Double, val y: Double, val radius: Double)
data class PoolAimSolution(
    val objectBallId: Int, val pocketId: Int,
    val cue: FloatPoint, val ghost: FloatPoint, val objectTarget: FloatPoint,
    val cueAimRadians: Double, val objectDirectionRadians: Double,
    val cutAngleDegrees: Double, val route: String, val confidence: Double
)

/**
 * Ghost-ball route solver ported from the supplied trainer's direct/bank geometry.
 * It uses the trainer's 254 x 127 cm coordinate system and its 3.81 cm ball radius.
 * This is a geometric candidate generator, not the trainer's full physics simulation.
 */
class GhostBallSolver(
    private val length: Double = 254.0,
    private val width: Double = 127.0,
    private val ballRadius: Double = 3.81,
    private val pocketMultiplier: Double = 1.23,
    private val bankFactor: Double = 0.77
) {
    private val cornerOffset = 0.3 * ballRadius * pocketMultiplier
    private val sideOffset = 0.9 * ballRadius * pocketMultiplier
    private val cornerRadius = 2.3 * ballRadius * pocketMultiplier
    private val sideRadius = 1.9 * ballRadius * pocketMultiplier
    val pockets = listOf(
        PoolPocket(0, -cornerOffset, -cornerOffset, cornerRadius),
        PoolPocket(1, length / 2, -sideOffset, sideRadius),
        PoolPocket(2, length + cornerOffset, -cornerOffset, cornerRadius),
        PoolPocket(3, -cornerOffset, width + cornerOffset, cornerRadius),
        PoolPocket(4, length / 2, width + sideOffset, sideRadius),
        PoolPocket(5, length + cornerOffset, width + cornerOffset, cornerRadius)
    )

    fun solve(cue: PoolBallState, objects: List<PoolBallState>): List<PoolAimSolution> {
        val out = ArrayList<PoolAimSolution>()
        for (ball in objects) for (pocket in pockets) {
            solveDirect(cue, ball, pocket)?.let(out::add)
            for (rail in 0..3) {
                if (railTouchesPocket(pocket.id, rail)) continue
                solveBank(cue, ball, pocket, rail)?.let(out::add)
            }
        }
        return out.sortedWith(compareBy<PoolAimSolution> { it.route != "direct" }
            .thenBy { it.cutAngleDegrees })
    }

    private fun railTouchesPocket(p: Int, r: Int): Boolean = when (p) {
        0 -> r == 0 || r == 3
        1 -> r == 0
        2 -> r == 0 || r == 1
        3 -> r == 2 || r == 3
        4 -> r == 2
        else -> r == 1 || r == 2
    }

    private fun solveDirect(cue: PoolBallState, ob: PoolBallState, p: PoolPocket): PoolAimSolution? {
        val angle = atan2(p.y - ob.y, p.x - ob.x)
        val dx = cos(angle); val dy = sin(angle)
        return makeCandidate(cue, ob, p, dx, dy, "direct", 0.0)
    }

    private fun solveBank(cue: PoolBallState, ob: PoolBallState, p: PoolPocket, rail: Int): PoolAimSolution? {
        val mirrored = when (rail) {
            0 -> p.x to (2 * ballRadius - p.y)
            1 -> (2 * (length - ballRadius) - p.x) to p.y
            2 -> p.x to (2 * (width - ballRadius) - p.y)
            else -> (2 * ballRadius - p.x) to p.y
        }
        val angle = atan2(mirrored.second - ob.y, mirrored.first - ob.x)
        val dx = cos(angle); val dy = sin(angle)
        return makeCandidate(cue, ob, p, dx, dy, "bank-$rail", bankFactor)
    }

    private fun makeCandidate(
        cue: PoolBallState, ob: PoolBallState, pocket: PoolPocket,
        dx: Double, dy: Double, route: String, bankKeep: Double
    ): PoolAimSolution? {
        val gx = ob.x - 2 * ballRadius * dx
        val gy = ob.y - 2 * ballRadius * dy
        if (gx !in ballRadius..(length - ballRadius) || gy !in ballRadius..(width - ballRadius)) return null
        val cx = gx - cue.x; val cy = gy - cue.y
        val cueDistance = hypot(cx, cy)
        if (cueDistance < 0.5) return null
        val aim = atan2(cy, cx)
        val dot = ((cx * dx + cy * dy) / cueDistance).coerceIn(-1.0, 1.0)
        if (dot <= cos(Math.toRadians(80.0))) return null
        val targetDistance = hypot(pocket.x - ob.x, pocket.y - ob.y)
        if (targetDistance < 1.0) return null
        // Check the object-ball segment against other balls; leave full collision simulation for later.
        val cut = Math.toDegrees(acos(dot))
        val confidence = (0.92 - cut / 100.0 - if (route == "direct") 0.0 else 0.12)
            .coerceIn(0.05, 0.95)
        return PoolAimSolution(ob.id, pocket.id, FloatPoint(cue.x.toFloat(), cue.y.toFloat()),
            FloatPoint(gx.toFloat(), gy.toFloat()),
            FloatPoint(pocket.x.toFloat(), pocket.y.toFloat()), aim, atan2(dy, dx), cut,
            route, confidence)
    }
}

/** Maps detected screen coordinates into the trainer's logical 2:1 table coordinate plane. */
class CalibratedTableTransform(val bounds: com.poolaim.android.vision.TableBounds) {
    init { require(bounds.width > 0f && bounds.height > 0f) }
    fun toTable(point: FloatPoint): FloatPoint = FloatPoint(
        ((point.x - bounds.left) / bounds.width * 254.0).toFloat(),
        ((point.y - bounds.top) / bounds.height * 127.0).toFloat()
    )
    fun toScreen(point: FloatPoint): FloatPoint = FloatPoint(
        bounds.left + point.x / 254f * bounds.width,
        bounds.top + point.y / 127f * bounds.height
    )
}
