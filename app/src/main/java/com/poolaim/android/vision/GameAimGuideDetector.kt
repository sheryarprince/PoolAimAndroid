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

/** A detected in-game guide ray, anchored to the actual reticle centre. */
data class GameAimGuideSegment(val start: FloatPoint, val end: FloatPoint)

class GameAimGuideDetector {
    private data class Ring(val center: FloatPoint, val radius: Int, val score: Int)
    private data class Ray(
        val angle: Float,
        val startDistance: Int,
        val endDistance: Int,
        val hits: Int
    ) {
        val length: Int get() = endDistance - startDistance + 1
    }
    private data class Candidate(val ring: Ring, val rays: List<Ray>, val quality: Float)

    fun detect(
        frame: CapturedRgbaFrame,
        table: TableBounds,
        excludedAreas: List<TableBounds> = emptyList(),
        balls: List<DetectedBall> = emptyList()
    ): List<GameAimGuideSegment> {
        val left = (table.left + 8f).toInt().coerceAtLeast(0)
        val right = (table.right - 8f).toInt().coerceAtMost(frame.width - 1)
        val top = (table.top + 8f).toInt().coerceAtLeast(0)
        val bottom = (table.bottom - 8f).toInt().coerceAtMost(frame.height - 1)
        if (right <= left || bottom <= top) return emptyList()

        fun excluded(x: Int, y: Int): Boolean {
            if (excludedAreas.any { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }) return true
            // Remove detected ball interiors and rims from reticle/ray evidence.
            return balls.any { ball ->
                val dx = x - ball.center.x
                val dy = y - ball.center.y
                dx * dx + dy * dy <= (ball.radius * 1.18f) * (ball.radius * 1.18f)
            }
        }

        // Reticle candidates are selected by ring evidence, then validated by
        // two independent guide rays. A circular ball edge alone is not enough.
        val coarse = ArrayList<Ring>()
        val radii = intArrayOf(12, 15, 18, 21)
        var y = top + 24
        while (y < bottom - 24) {
            var x = left + 24
            while (x < right - 24) {
                if (!excluded(x, y)) {
                    for (radius in radii) {
                        val score = ringScore(frame, x, y, radius, ::excluded)
                        if (score >= 22 && ringCoverage(frame, x, y, radius, ::excluded) >= 6) {
                            val candidate = Ring(FloatPoint(x.toFloat(), y.toFloat()), radius, score)
                            if (coarse.none { distance(it.center, candidate.center) < 18f }) {
                                coarse.add(candidate)
                            } else {
                                val index = coarse.indexOfFirst { distance(it.center, candidate.center) < 18f }
                                if (index >= 0 && score > coarse[index].score) coarse[index] = candidate
                            }
                        }
                    }
                }
                x += 6
            }
            y += 6
        }
        if (coarse.isEmpty()) return emptyList()

        // The guide reticle belongs to the cue-ball aiming system. A ring-like
        // highlight elsewhere on the felt must never win merely because it has
        // more white pixels. Require a confidently detected cue ball and keep
        // candidate reticles in the small, physically plausible zone around it.
        val cueBalls = balls.filter {
            it.ballId == BallIdentity.CUE && it.confidence >= 0.20f &&
                it.radius in 5f..40f
        }
        if (cueBalls.isEmpty()) return emptyList()
        // Multiple nearby cue hypotheses can occur in crowded or partially
        // occluded frames. Anchor against any credible cue candidate rather
        // than rejecting the entire frame because the detector returned two.
        val anchored = coarse.filter { candidate ->
            cueBalls.any { cue ->
                val d = distance(candidate.center, cue.center)
                d in (cue.radius * 1.65f)..(cue.radius * 5.25f)
            }
        }
        if (anchored.isEmpty()) return emptyList()

        val maxRay = (minOf(table.width, table.height) * 0.46f).toInt().coerceAtLeast(80)
        var best: Candidate? = null
        for (coarseRing in anchored.sortedByDescending { it.score }.take(24)) {
            var refined = coarseRing
            for (oy in -6..6 step 2) for (ox in -6..6 step 2) {
                val cx = coarseRing.center.x.toInt() + ox
                val cy = coarseRing.center.y.toInt() + oy
                if (cx !in left + 22 until right - 22 || cy !in top + 22 until bottom - 22 ||
                    excluded(cx, cy)) continue
                for (radius in 11..22) {
                    val score = ringScore(frame, cx, cy, radius, ::excluded)
                    if (score > refined.score) {
                        refined = Ring(FloatPoint(cx.toFloat(), cy.toFloat()), radius, score)
                    }
                }
            }

            // The cue ball can physically cover the first part of one guide
            // stroke. Permit that one known occlusion, but only along the
            // reticle-to-cue-ball distance; do not bridge arbitrary gaps.
            val cueAnchor = cueBalls.minBy { distance(refined.center, it.center) }
            val cueGapAllowance = (
                distance(refined.center, cueAnchor.center) + cueAnchor.radius * 1.5f
            ).toInt()
            val maxStartDistance = refined.radius + 6 + cueGapAllowance
            val rays = findRays(
                frame, refined, left, top, right, bottom, maxRay,
                maxStartDistance, ::excluded
            )
            if (rays.size < 2) continue
            val strongest = rays.take(2)
            var separation = abs(strongest[0].angle - strongest[1].angle)
            if (separation > PI.toFloat()) separation = 2f * PI.toFloat() - separation
            if (separation < 0.40f || separation > 2.75f) continue
            // Reward two long, well-supported rays; reject candidates that
            // merely sit near a single cue-stick edge or a ball highlight.
            val cueDistanceRadii = distance(refined.center, cueAnchor.center) /
                cueAnchor.radius.coerceAtLeast(1f)
            // Prefer the reticle physically attached to the cue ball and two
            // long continuous guide strokes; raw ring whiteness alone must
            // not select a decorative circle elsewhere on the table.
            val quality = refined.score * 0.8f +
                minOf(strongest[0].length, strongest[1].length) * 0.50f +
                strongest.sumOf { it.hits.toDouble() }.toFloat() * 0.10f -
                abs(cueDistanceRadii - 2.8f) * 25f
            if (best == null || quality > best!!.quality) {
                best = Candidate(refined, strongest, quality)
            }
        }

        val chosen = best ?: return emptyList()
        return chosen.rays.map { ray ->
            val dx = cos(ray.angle)
            val dy = sin(ray.angle)
            // Start at the reticle centre so the added segment joins the
            // original game guide without a visible gap.
            GameAimGuideSegment(
                chosen.ring.center,
                FloatPoint(
                    chosen.ring.center.x + dx * ray.endDistance,
                    chosen.ring.center.y + dy * ray.endDistance
                )
            )
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
        maxStartDistance: Int,
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
            for (distance in ring.radius + 2..maxLength) {
                val fx = ring.center.x + dx * distance
                val fy = ring.center.y + dy * distance
                val x = fx.roundToInt()
                val y = fy.roundToInt()
                var hit = false
                // Sample a narrow perpendicular strip to tolerate anti-aliasing
                // and 2–4 px guide thickness without merging nearby objects.
                for (offset in -2..2) {
                    val sx = (fx + px * offset).roundToInt()
                    val sy = (fy + py * offset).roundToInt()
                    if (sx in left..right && sy in top..bottom &&
                        !excluded(sx, sy) && isGuideWhite(frame.pixelAt(sx, sy))) {
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
                    if (gap > 2) {
                        val length = lastHit - runStart + 1
                        if (runStart <= maxStartDistance && length >= 32 && hits >= 25) {
                            val candidate = Ray(angle, runStart, lastHit, hits)
                            if (bestRay == null || candidate.length > bestRay!!.length) bestRay = candidate
                        }
                        runStart = -1
                        lastHit = -1
                        hits = 0
                        gap = 0
                    }
                }
            }
            if (runStart >= 0) {
                val length = lastHit - runStart + 1
                if (runStart <= maxStartDistance && length >= 32 && hits >= 25) {
                    val candidate = Ray(angle, runStart, lastHit, hits)
                    if (bestRay == null || candidate.length > bestRay!!.length) bestRay = candidate
                }
            }
            if (bestRay != null) rays.add(bestRay!!)
        }

        // Cluster nearby angle samples of the same one-sided stroke. Unlike
        // the old modulo-PI merge, opposite directions remain distinct rays.
        val selected = ArrayList<Ray>()
        for (ray in rays.sortedWith(compareByDescending<Ray> { it.length }.thenByDescending { it.hits })) {
            val duplicate = selected.any { existing ->
                var delta = abs(existing.angle - ray.angle)
                if (delta > PI.toFloat()) delta = 2f * PI.toFloat() - delta
                delta < 0.16f
            }
            if (!duplicate) selected.add(ray)
            if (selected.size == 2) break
        }
        return selected
    }

    /** Require a near-complete circular reticle, not scattered white pixels. */
    private fun ringCoverage(
        frame: CapturedRgbaFrame,
        cx: Int,
        cy: Int,
        radius: Int,
        excluded: (Int, Int) -> Boolean
    ): Int {
        var coveredSectors = 0
        for (sector in 0 until 8) {
            var hits = 0
            for (sample in 0 until 4) {
                val angle = 2.0 * PI * (sector * 4 + sample) / 32.0
                var found = false
                for (dr in -1..1) {
                    val x = cx + ((radius + dr) * cos(angle)).roundToInt()
                    val y = cy + ((radius + dr) * sin(angle)).roundToInt()
                    if (!excluded(x, y) && isGuideWhite(frame.pixelAt(x, y))) {
                        found = true
                        break
                    }
                }
                if (found) hits++
            }
            if (hits >= 2) coveredSectors++
        }
        return coveredSectors
    }

    private fun ringScore(
        frame: CapturedRgbaFrame,
        cx: Int,
        cy: Int,
        radius: Int,
        excluded: (Int, Int) -> Boolean
    ): Int {
        var score = 0
        for (i in 0 until 36) {
            val angle = 2.0 * PI * i / 36
            var hit = false
            for (dr in -1..1) {
                val x = cx + ((radius + dr) * cos(angle)).roundToInt()
                val y = cy + ((radius + dr) * sin(angle)).roundToInt()
                if (!excluded(x, y) && isGuideWhite(frame.pixelAt(x, y))) {
                    hit = true
                    break
                }
            }
            if (hit) score++
        }
        return score
    }

    private fun isGuideWhite(pixel: RgbaPixel?): Boolean {
        if (pixel == null || pixel.alpha < 180) return false
        val minimum = minOf(pixel.red, pixel.green, pixel.blue)
        val maximum = maxOf(pixel.red, pixel.green, pixel.blue)
        val brightness = (pixel.red + pixel.green + pixel.blue) / 3f
        return minimum >= 178 && brightness >= 205f && maximum - minimum <= 58
    }

    private fun distance(a: FloatPoint, b: FloatPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
