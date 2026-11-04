package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.capture.RgbaPixel
import com.poolaim.android.engine.FloatPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** A detected in-game guide ray, extended from the game reticle to the felt boundary. */
data class GameAimGuideSegment(val start: FloatPoint, val end: FloatPoint)

class GameAimGuideDetector {
    private data class Ring(val center: FloatPoint, val radius: Int, val score: Int)
    private data class Ray(val angle: Float, val length: Int, val hits: Int)
    private data class Candidate(val ring: Ring, val rays: List<Ray>, val quality: Float)

    fun detect(
        frame: CapturedRgbaFrame,
        table: TableBounds,
        excludedAreas: List<TableBounds> = emptyList(),
        balls: List<DetectedBall> = emptyList()
    ): List<GameAimGuideSegment> {
        val left = (table.left + 5f).toInt().coerceAtLeast(0)
        val right = (table.right - 5f).toInt().coerceAtMost(frame.width - 1)
        val top = (table.top + 5f).toInt().coerceAtLeast(0)
        val bottom = (table.bottom - 5f).toInt().coerceAtMost(frame.height - 1)
        if (right <= left || bottom <= top) return emptyList()

        fun excluded(x: Int, y: Int): Boolean {
            if (excludedAreas.any { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }) return true
            return balls.any { ball ->
                val dx = x - ball.center.x
                val dy = y - ball.center.y
                dx * dx + dy * dy <= (ball.radius * 1.10f) * (ball.radius * 1.10f)
            }
        }

        // Stage 1: find reticle-like rings from image evidence. Cue-ball identity
        // is deliberately NOT a prerequisite: it is often hidden by the cue,
        // a hand animation, or another ball in real gameplay.
        val coarse = ArrayList<Ring>()
        val radii = intArrayOf(11, 14, 17, 20, 23, 26)
        var y = top + 28
        while (y < bottom - 28) {
            var x = left + 28
            while (x < right - 28) {
                if (!excluded(x, y)) {
                    for (radius in radii) {
                        val score = ringScore(frame, x, y, radius, ::excluded)
                        if (score >= 17 && ringCoverage(frame, x, y, radius, ::excluded) >= 5) {
                            val candidate = Ring(FloatPoint(x.toFloat(), y.toFloat()), radius, score)
                            val index = coarse.indexOfFirst { distance(it.center, candidate.center) < 15f }
                            if (index < 0) coarse.add(candidate)
                            else if (score > coarse[index].score) coarse[index] = candidate
                        }
                    }
                }
                x += 5
            }
            y += 5
        }
        if (coarse.isEmpty()) return emptyList()

        val maxRay = sqrt(table.width * table.width + table.height * table.height).toInt()
        var best: Candidate? = null
        // Stage 2: a ring is accepted only when two separate, continuous,
        // neutral-white strokes begin at its perimeter. This rejects ball rims,
        // score graphics, and isolated highlights.
        for (coarseRing in coarse.sortedByDescending { it.score }.take(36)) {
            var refined = coarseRing
            for (oy in -4..4 step 2) for (ox in -4..4 step 2) {
                val cx = coarseRing.center.x.toInt() + ox
                val cy = coarseRing.center.y.toInt() + oy
                if (cx !in left + 24 until right - 24 || cy !in top + 24 until bottom - 24 ||
                    excluded(cx, cy)) continue
                for (radius in 10..27) {
                    val score = ringScore(frame, cx, cy, radius, ::excluded)
                    if (score > refined.score) refined = Ring(FloatPoint(cx.toFloat(), cy.toFloat()), radius, score)
                }
            }
            val rays = findRays(frame, refined, left, top, right, bottom, maxRay, ::excluded)
            if (rays.size < 2) continue
            val a = rays[0].angle
            val b = rays[1].angle
            var separation = abs(a - b)
            if (separation > PI.toFloat()) separation = 2f * PI.toFloat() - separation
            if (separation < 0.28f || separation > 2.85f) continue

            val quality = refined.score * 0.55f +
                minOf(rays[0].length, rays[1].length) * 0.65f +
                (rays[0].hits + rays[1].hits) * 0.18f
            if (best == null || quality > best!!.quality) best = Candidate(refined, rays, quality)
        }

        val chosen = best ?: return emptyList()
        return chosen.rays.mapNotNull { ray ->
            val dx = cos(ray.angle)
            val dy = sin(ray.angle)
            val end = rayToFeltBoundary(chosen.ring.center, dx, dy, left.toFloat(), top.toFloat(),
                right.toFloat(), bottom.toFloat()) ?: return@mapNotNull null
            GameAimGuideSegment(chosen.ring.center, end)
        }
    }

    private fun findRays(
        frame: CapturedRgbaFrame,
        ring: Ring,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        maxLength: Int,
        excluded: (Int, Int) -> Boolean
    ): List<Ray> {
        val rays = ArrayList<Ray>()
        for (degree in 0 until 360) {
            val angle = degree * PI.toFloat() / 180f
            val dx = cos(angle)
            val dy = sin(angle)
            val px = -dy
            val py = dx
            var runStart = -1
            var lastHit = -1
            var hits = 0
            var gap = 0
            var bestRay: Ray? = null
            for (distance in ring.radius + 1..maxLength) {
                val fx = ring.center.x + dx * distance
                val fy = ring.center.y + dy * distance
                val x = fx.roundToInt()
                val y = fy.roundToInt()
                if (x !in left..right || y !in top..bottom) break
                var hit = false
                for (offset in -3..3) {
                    val sx = (fx + px * offset).roundToInt()
                    val sy = (fy + py * offset).roundToInt()
                    if (sx in left..right && sy in top..bottom && !excluded(sx, sy) &&
                        isGuideWhite(frame.pixelAt(sx, sy))) {
                        hit = true
                        break
                    }
                }
                if (hit) {
                    if (runStart < 0) runStart = distance
                    lastHit = distance
                    hits++
                    gap = 0
                } else if (runStart >= 0) {
                    gap++
                    if (gap > 4) {
                        val length = lastHit - runStart + 1
                        if (runStart <= ring.radius + 9 && length >= 24 && hits >= 16) {
                            val candidate = Ray(angle, length, hits)
                            if (bestRay == null || candidate.length > bestRay!!.length) bestRay = candidate
                        }
                        runStart = -1; lastHit = -1; hits = 0; gap = 0
                    }
                }
            }
            if (runStart >= 0) {
                val length = lastHit - runStart + 1
                if (runStart <= ring.radius + 9 && length >= 24 && hits >= 16) {
                    val candidate = Ray(angle, length, hits)
                    if (bestRay == null || candidate.length > bestRay!!.length) bestRay = candidate
                }
            }
            if (bestRay != null) rays.add(bestRay!!)
        }

        // Collapse adjacent angle samples of the same physical stroke while
        // preserving opposite rays as distinct directions.
        val selected = ArrayList<Ray>()
        for (ray in rays.sortedWith(compareByDescending<Ray> { it.length }.thenByDescending { it.hits })) {
            val duplicate = selected.any { existing ->
                var delta = abs(existing.angle - ray.angle)
                if (delta > PI.toFloat()) delta = 2f * PI.toFloat() - delta
                delta < 0.13f
            }
            if (!duplicate) selected.add(ray)
            if (selected.size == 2) break
        }
        return selected
    }

    private fun ringCoverage(
        frame: CapturedRgbaFrame, cx: Int, cy: Int, radius: Int,
        excluded: (Int, Int) -> Boolean
    ): Int {
        var sectors = 0
        for (sector in 0 until 8) {
            var hits = 0
            for (sample in 0 until 4) {
                val angle = 2.0 * PI * (sector * 4 + sample) / 32.0
                var found = false
                for (dr in -1..1) {
                    val x = cx + ((radius + dr) * cos(angle)).roundToInt()
                    val y = cy + ((radius + dr) * sin(angle)).roundToInt()
                    if (!excluded(x, y) && isGuideWhite(frame.pixelAt(x, y))) { found = true; break }
                }
                if (found) hits++
            }
            if (hits >= 2) sectors++
        }
        return sectors
    }

    private fun ringScore(
        frame: CapturedRgbaFrame, cx: Int, cy: Int, radius: Int,
        excluded: (Int, Int) -> Boolean
    ): Int {
        var score = 0
        for (i in 0 until 36) {
            val angle = 2.0 * PI * i / 36
            var hit = false
            for (dr in -1..1) {
                val x = cx + ((radius + dr) * cos(angle)).roundToInt()
                val y = cy + ((radius + dr) * sin(angle)).roundToInt()
                if (!excluded(x, y) && isGuideWhite(frame.pixelAt(x, y))) { hit = true; break }
            }
            if (hit) score++
        }
        return score
    }

    /** Adaptive-tolerant neutral-white test for anti-aliased white strokes with dark outlines. */
    private fun isGuideWhite(pixel: RgbaPixel?): Boolean {
        if (pixel == null || pixel.alpha < 150) return false
        val minimum = minOf(pixel.red, pixel.green, pixel.blue)
        val maximum = maxOf(pixel.red, pixel.green, pixel.blue)
        val brightness = (pixel.red + pixel.green + pixel.blue) / 3f
        return minimum >= 145 && brightness >= 182f && maximum - minimum <= 88
    }

    private fun rayToFeltBoundary(
        origin: FloatPoint, dx: Float, dy: Float,
        left: Float, top: Float, right: Float, bottom: Float
    ): FloatPoint? {
        val candidates = ArrayList<Float>(2)
        if (dx > 0.0001f) candidates.add((right - origin.x) / dx)
        if (dx < -0.0001f) candidates.add((left - origin.x) / dx)
        if (dy > 0.0001f) candidates.add((bottom - origin.y) / dy)
        if (dy < -0.0001f) candidates.add((top - origin.y) / dy)
        val t = candidates.filter { it > 0f }.minOrNull() ?: return null
        return FloatPoint((origin.x + dx * t).coerceIn(left, right),
            (origin.y + dy * t).coerceIn(top, bottom))
    }

    private fun distance(a: FloatPoint, b: FloatPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
