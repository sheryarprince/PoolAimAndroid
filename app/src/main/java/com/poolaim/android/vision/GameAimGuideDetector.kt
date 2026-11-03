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

/** One actual game-rendered guide ray, anchored at the game's circular aiming reticle. */
data class GameAimGuideSegment(val start: FloatPoint, val end: FloatPoint)

class GameAimGuideDetector {
    private data class Reticle(val center: FloatPoint, val radius: Int, val score: Int)
    private data class Ray(val angle: Float, val length: Int, val hits: Int)

    fun detect(
        frame: CapturedRgbaFrame,
        table: TableBounds,
        excludedAreas: List<TableBounds> = emptyList()
    ): List<GameAimGuideSegment> {
        val left = (table.left + 8f).toInt().coerceAtLeast(0)
        val right = (table.right - 8f).toInt().coerceAtMost(frame.width - 1)
        val top = (table.top + 8f).toInt().coerceAtLeast(0)
        val bottom = (table.bottom - 8f).toInt().coerceAtMost(frame.height - 1)
        if (right <= left || bottom <= top) return emptyList()

        fun excluded(x: Int, y: Int) = excludedAreas.any {
            x >= it.left && x <= it.right && y >= it.top && y <= it.bottom
        }

        // First locate the small white circular aiming reticle. A ball's white
        // patch is filled, not a continuous annulus, so it cannot pass this test.
        var best: Reticle? = null
        val radii = listOf(13, 16, 19)
        val angularSamples = 24
        var cy = top + 24
        while (cy < bottom - 24) {
            var cx = left + 24
            while (cx < right - 24) {
                if (!excluded(cx, cy)) {
                    for (radius in radii) {
                        var score = 0
                        for (i in 0 until angularSamples) {
                            val a = 2.0 * PI * i / angularSamples
                            var hit = false
                            for (dr in -1..1) {
                                val x = cx + ((radius + dr) * cos(a)).roundToInt()
                                val y = cy + ((radius + dr) * sin(a)).roundToInt()
                                if (!excluded(x, y) && isGuideWhite(frame.pixelAt(x, y))) {
                                    hit = true
                                    break
                                }
                            }
                            if (hit) score++
                        }
                        if (best == null || score > best!!.score) {
                            best = Reticle(FloatPoint(cx.toFloat(), cy.toFloat()), radius, score)
                        }
                    }
                }
                cx += 8
            }
            cy += 8
        }
        val coarse = best ?: return emptyList()
        // Refine the centre locally so the ray origins do not drift with coarse sampling.
        var reticle = coarse
        for (ry in -8..8 step 2) for (rx in -8..8 step 2) {
            val cx = coarse.center.x.toInt() + rx
            val cy = coarse.center.y.toInt() + ry
            if (cx !in left + 20 until right - 20 || cy !in top + 20 until bottom - 20 ||
                excluded(cx, cy)) continue
            for (radius in 12..21) {
                var score = 0
                for (i in 0 until 32) {
                    val a = 2.0 * PI * i / 32
                    var hit = false
                    for (dr in -1..1) {
                        val x = cx + ((radius + dr) * cos(a)).roundToInt()
                        val y = cy + ((radius + dr) * sin(a)).roundToInt()
                        if (!excluded(x, y) && isGuideWhite(frame.pixelAt(x, y))) {
                            hit = true
                            break
                        }
                    }
                    if (hit) score++
                }
                if (score > reticle.score) {
                    reticle = Reticle(FloatPoint(cx.toFloat(), cy.toFloat()), radius, score)
                }
            }
        }
        // Require a near-complete ring; do not guess a centre from arbitrary lines.
        if (reticle.score < 21) return emptyList()

        // Search radially outward from the reticle. This is deliberately local:
        // white balls, labels, table trim and unrelated UI cannot become guide axes.
        val rays = ArrayList<Ray>()
        val maxLength = (minOf(table.width, table.height) * 0.48f).toInt().coerceAtLeast(60)
        for (degree in 0 until 360) {
            val angle = degree * PI.toFloat() / 180f
            val dx = cos(angle)
            val dy = sin(angle)
            val px = -dy
            val py = dx
            var run = 0
            var bestRun = 0
            var bestEnd = 0
            var runHits = 0
            var bestHits = 0
            var gap = 0
            for (distance in reticle.radius + 3..maxLength) {
                val x = reticle.center.x + dx * distance
                val y = reticle.center.y + dy * distance
                var hit = false
                for (offset in -2..2) {
                    val sx = (x + px * offset).roundToInt()
                    val sy = (y + py * offset).roundToInt()
                    if (sx in left..right && sy in top..bottom &&
                        !excluded(sx, sy) && isGuideWhite(frame.pixelAt(sx, sy))) {
                        hit = true
                        break
                    }
                }
                if (hit) {
                    run++
                    runHits++
                    gap = 0
                    if (run > bestRun) {
                        bestRun = run
                        bestEnd = distance
                        bestHits = runHits
                    }
                } else {
                    gap++
                    if (gap > 2) {
                        run = 0
                        runHits = 0
                    }
                }
            }
            if (bestRun >= 25 && bestHits >= 22) {
                rays.add(Ray(angle, bestEnd, bestHits))
            }
        }

        // Merge neighbouring angle samples of the same physical stroke, then
        // keep only the two strongest distinct guide directions.
        val selected = ArrayList<Ray>()
        for (ray in rays.sortedWith(compareByDescending<Ray> { it.length }.thenByDescending { it.hits })) {
            val duplicate = selected.any { existing ->
                val raw = abs(existing.angle - ray.angle)
                val normalized = raw % PI.toFloat()
                val difference = minOf(normalized, PI.toFloat() - normalized)
                difference < 0.22f
            }
            if (!duplicate) selected.add(ray)
            if (selected.size == 2) break
        }

        return selected.map { ray ->
            GameAimGuideSegment(
                reticle.center,
                FloatPoint(
                    reticle.center.x + cos(ray.angle) * ray.length,
                    reticle.center.y + sin(ray.angle) * ray.length
                )
            )
        }
    }

    private fun isGuideWhite(pixel: RgbaPixel?): Boolean {
        if (pixel == null || pixel.alpha < 180) return false
        val minimum = minOf(pixel.red, pixel.green, pixel.blue)
        val maximum = maxOf(pixel.red, pixel.green, pixel.blue)
        val brightness = (pixel.red + pixel.green + pixel.blue) / 3f
        return minimum >= 178 && brightness >= 205f && maximum - minimum <= 58
    }
}
