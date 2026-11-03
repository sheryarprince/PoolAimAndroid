package com.poolaim.android.engine

import com.poolaim.android.vision.BallIdentity
import com.poolaim.android.vision.DetectedBall
import com.poolaim.android.vision.PoolScene
import com.poolaim.android.vision.TableBounds
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class ShotType { DIRECT, ONE_CUSHION }

data class ShotSolution(
    val objectBallId: Int,
    val pocketIndex: Int,
    val type: ShotType,
    val bankRailIndex: Int?,
    val cue: FloatPoint,
    val ghost: FloatPoint,
    val objectTarget: FloatPoint,
    val pocket: FloatPoint,
    val aimAngleDegrees: Float,
    val score: Float,
    val cuePathClear: Boolean,
    val objectPathClear: Boolean,
    /** Exact first cushion-contact point for a one-cushion object-ball route. */
    val bankPoint: FloatPoint? = null
)

data class CollisionValidation(
    val cuePathClear: Boolean,
    val objectPathClear: Boolean,
    val minimumClearance: Float
) {
    val accepted: Boolean get() = cuePathClear && objectPathClear
}

/** Maps screen pixels into the trainer's 254 x 127 logical table. */
class CalibratedTableTransform(private val table: TableBounds) {
    init { require(table.width > 0f && table.height > 0f) }

    fun toLogical(point: FloatPoint): FloatPoint = FloatPoint(
        (point.x - table.left) * 254f / table.width,
        (point.y - table.top) * 127f / table.height
    )

    fun toScreen(point: FloatPoint): FloatPoint = FloatPoint(
        table.left + point.x * table.width / 254f,
        table.top + point.y * table.height / 127f
    )

    fun radiusToLogical(screenRadius: Float): Float =
        screenRadius * 254f / table.width
}

/**
 * Direct and one-cushion ghost-ball candidate search using the trainer's
 * cue-ball ID (0) and object-ball IDs (1..15). Every candidate is checked
 * against every other recognized ball along both the cue path and the
 * object-ball path. It does not claim to reproduce spin, power or the full
 * time-stepped Python physics engine.
 */
class GhostBallSolver {
    private data class BallPoint(val id: Int, val p: FloatPoint)
    private data class Route(
        val pocketIndex: Int,
        val pocket: FloatPoint,
        val type: ShotType,
        val bankRail: Int?,
        val objectPath: List<Pair<FloatPoint, FloatPoint>>,
        val objectDirection: FloatPoint
    )

    private val length = 254f
    private val width = 127f

    fun solve(scene: PoolScene, maxSolutions: Int = 6): List<ShotSolution> {
        val table = scene.table ?: return emptyList()
        val transform = CalibratedTableTransform(table)
        val logical = scene.balls
            .filter { it.ballId != null && BallIdentity.isValid(it.ballId) }
            .groupBy { it.ballId!! }
            .mapNotNull { (id, candidates) ->
                val best = candidates.maxByOrNull { it.classificationConfidence } ?: return@mapNotNull null
                BallPoint(id, transform.toLogical(best.center))
            }
        val cue = logical.firstOrNull { it.id == BallIdentity.CUE } ?: return emptyList()
        val objects = logical.filter { it.id in 1..15 }
        if (objects.isEmpty()) return emptyList()

        val estimatedRadius = scene.balls
            .filter { it.ballId != null }
            .map { transform.radiusToLogical(it.radius) }
            .filter { it.isFinite() && it > 1f }
            .average().toFloat().takeIf { it.isFinite() && it > 0f } ?: 3.8f
        val diameter = estimatedRadius * 2f
        val pockets = listOf(
            FloatPoint(0f, 0f), FloatPoint(length / 2f, 0f), FloatPoint(length, 0f),
            FloatPoint(0f, width), FloatPoint(length / 2f, width), FloatPoint(length, width)
        )
        val output = ArrayList<ShotSolution>()

        for (target in objects) {
            for ((pocketIndex, pocket) in pockets.withIndex()) {
                val direct = makeDirectRoute(target.p, pocket, pocketIndex)
                if (direct != null) {
                    addIfClear(output, cue, target, direct, logical, estimatedRadius, diameter)
                }
                for (rail in 0..3) {
                    val bank = makeBankRoute(target.p, pocket, pocketIndex, rail, estimatedRadius)
                    if (bank != null) {
                        addIfClear(output, cue, target, bank, logical, estimatedRadius, diameter)
                    }
                }
            }
        }

        return output.sortedByDescending { it.score }.take(maxSolutions.coerceAtLeast(1))
    }

    private fun makeDirectRoute(
        objectPoint: FloatPoint,
        pocket: FloatPoint,
        pocketIndex: Int
    ): Route? {
        val direction = unit(pocket.x - objectPoint.x, pocket.y - objectPoint.y) ?: return null
        return Route(
            pocketIndex, pocket, ShotType.DIRECT, null,
            listOf(objectPoint to pocket), direction
        )
    }

    private fun makeBankRoute(
        objectPoint: FloatPoint,
        pocket: FloatPoint,
        pocketIndex: Int,
        rail: Int,
        radius: Float
    ): Route? {
        // Do not reflect through a rail that directly contains this pocket.
        val railTouchesPocket = when (pocketIndex) {
            0 -> rail == 0 || rail == 3
            1 -> rail == 0
            2 -> rail == 0 || rail == 1
            3 -> rail == 2 || rail == 3
            4 -> rail == 2
            5 -> rail == 1 || rail == 2
            else -> false
        }
        if (railTouchesPocket) return null

        val reflected = when (rail) {
            0 -> FloatPoint(pocket.x, 2f * radius - pocket.y)
            1 -> FloatPoint(2f * (length - radius) - pocket.x, pocket.y)
            2 -> FloatPoint(pocket.x, 2f * (width - radius) - pocket.y)
            else -> FloatPoint(2f * radius - pocket.x, pocket.y)
        }
        val dx = reflected.x - objectPoint.x
        val dy = reflected.y - objectPoint.y
        val t = when (rail) {
            0 -> (radius - objectPoint.y) / dy
            1 -> (length - radius - objectPoint.x) / dx
            2 -> (width - radius - objectPoint.y) / dy
            else -> (radius - objectPoint.x) / dx
        }
        if (!t.isFinite() || t <= 0.03f || t >= 0.97f) return null
        val bankPoint = FloatPoint(objectPoint.x + dx * t, objectPoint.y + dy * t)
        if (bankPoint.x !in radius..(length - radius) || bankPoint.y !in radius..(width - radius)) return null
        val direction = unit(dx, dy) ?: return null
        return Route(
            pocketIndex, pocket, ShotType.ONE_CUSHION, rail,
            listOf(objectPoint to bankPoint, bankPoint to pocket), direction
        )
    }

    private fun addIfClear(
        output: MutableList<ShotSolution>,
        cue: BallPoint,
        target: BallPoint,
        route: Route,
        allBalls: List<BallPoint>,
        radius: Float,
        diameter: Float
    ) {
        val ghost = FloatPoint(
            target.p.x - route.objectDirection.x * diameter,
            target.p.y - route.objectDirection.y * diameter
        )
        if (ghost.x !in radius..(length - radius) || ghost.y !in radius..(width - radius)) return
        val cueDirection = unit(ghost.x - cue.p.x, ghost.y - cue.p.y) ?: return
        val cuePathClearance = minimumClearance(
            cue.p, ghost, allBalls.filter { it.id != cue.id && it.id != target.id }, diameter
        )
        if (cuePathClearance < 0f) return

        val objectsToCheck = allBalls.filter { it.id != cue.id && it.id != target.id }
        var objectClearance = Float.POSITIVE_INFINITY
        for ((start, end) in route.objectPath) {
            val clearance = minimumClearance(start, end, objectsToCheck, diameter)
            if (clearance < 0f) return
            objectClearance = min(objectClearance, clearance)
        }
        val cutAlignment = (route.objectDirection.x * cueDirection.x +
            route.objectDirection.y * cueDirection.y).coerceIn(-1f, 1f)
        val cutPenalty = (1f - cutAlignment) * 0.5f
        val objectDistance = route.objectPath.sumOf { distance(it.first, it.second).toDouble() }.toFloat()
        val cueDistance = distance(cue.p, ghost)
        val bankPenalty = if (route.type == ShotType.ONE_CUSHION) 0.14f else 0f
        val score = (1f - cutPenalty * 0.42f -
            (cueDistance + objectDistance) / 950f - bankPenalty).coerceIn(0.02f, 1f)

        output.add(
            ShotSolution(
                objectBallId = target.id,
                pocketIndex = route.pocketIndex,
                type = route.type,
                bankRailIndex = route.bankRail,
                cue = cue.p,
                ghost = ghost,
                objectTarget = target.p,
                pocket = route.pocket,
                aimAngleDegrees = Math.toDegrees(
                    atan2((ghost.y - cue.p.y).toDouble(), (ghost.x - cue.p.x).toDouble())
                ).toFloat(),
                score = score,
                cuePathClear = true,
                objectPathClear = true,
                bankPoint = if (route.type == ShotType.ONE_CUSHION) route.objectPath.firstOrNull()?.second else null
            )
        )
    }

    /** Negative means a collision with another ball; nonnegative is free clearance. */
    private fun minimumClearance(
        start: FloatPoint,
        end: FloatPoint,
        obstacles: List<BallPoint>,
        diameter: Float
    ): Float {
        if (obstacles.isEmpty()) return Float.POSITIVE_INFINITY
        var minimum = Float.POSITIVE_INFINITY
        for (obstacle in obstacles) {
            val d = pointSegmentDistance(obstacle.p, start, end)
            val clearance = d - diameter * 0.98f
            minimum = min(minimum, clearance)
            if (clearance < 0f) return clearance
        }
        return minimum
    }

    private fun pointSegmentDistance(p: FloatPoint, a: FloatPoint, b: FloatPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-8f) return distance(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return distance(p, FloatPoint(a.x + t * dx, a.y + t * dy))
    }

    private fun unit(dx: Float, dy: Float): FloatPoint? {
        val magnitude = hypot(dx, dy)
        if (!magnitude.isFinite() || magnitude < 1e-4f) return null
        return FloatPoint(dx / magnitude, dy / magnitude)
    }

    private fun distance(a: FloatPoint, b: FloatPoint): Float =
        hypot(a.x - b.x, a.y - b.y)
}
