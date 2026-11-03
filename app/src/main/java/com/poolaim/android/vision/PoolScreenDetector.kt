package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.engine.FloatPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class TableBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

data class DetectedBall(
    val center: FloatPoint,
    val radius: Float,
    val confidence: Float,
    val ballId: Int? = null,
    val kind: BallKind = BallKind.UNKNOWN,
    val classificationConfidence: Float = 0f,
    val whiteFraction: Float = 0f,
    val darkFraction: Float = 0f
)

data class PoolScene(
    val table: TableBounds?,
    val balls: List<DetectedBall>,
    val confidence: Float,
    val frameWidth: Int,
    val frameHeight: Int,
    val diagnostic: String
)

/**
 * Detects the felt envelope (blue or green) and circle boundaries by radial
 * edge contrast. Unlike the first prototype, this is not tied to green tables
 * and does not use a single centre pixel to identify the cue ball.
 *
 * Coordinates are captured-screen pixels. Numbered identities are supplied
 * by BallAppearanceClassifier and remain null when visual evidence is weak.
 */
class PoolScreenDetector(
    private val sampleStep: Int = 2,
    private val classifier: BallAppearanceClassifier = BallAppearanceClassifier()
) {
    init { require(sampleStep in 1..4) }

    private data class ComponentBounds(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val count: Int
    )
    private data class CircleCandidate(
        val center: FloatPoint,
        val radius: Float,
        val edgeScore: Float,
        val insideScore: Float
    )

    fun detect(frame: CapturedRgbaFrame): PoolScene {
        val w = (frame.width + sampleStep - 1) / sampleStep
        val h = (frame.height + sampleStep - 1) / sampleStep
        if (w < 80 || h < 60) return empty(frame, "Frame too small for table recognition")

        val cloth = BooleanArray(w * h)
        var clothCount = 0
        for (gy in 0 until h) {
            val y = gy * sampleStep
            for (gx in 0 until w) {
                val p = frame.pixelAt(gx * sampleStep, y) ?: continue
                if (isCloth(p.red, p.green, p.blue)) {
                    cloth[gy * w + gx] = true
                    clothCount++
                }
            }
        }
        if (clothCount < w * h * 0.055f) {
            return empty(frame, "No blue/green pool-table felt found")
        }

        val bounds = largestComponent(cloth, w, h)
            ?: return empty(frame, "Felt regions are too fragmented")
        val bw = bounds.right - bounds.left + 1
        val bh = bounds.bottom - bounds.top + 1
        val aspect = bw.toFloat() / bh.coerceAtLeast(1)
        val fill = bounds.count.toFloat() / (bw * bh).coerceAtLeast(1)
        if (bw < w * 0.35f || bh < h * 0.25f || aspect !in 1.35f..2.45f || fill < 0.26f) {
            return empty(frame, "Largest felt region is not a plausible pool table")
        }

        val table = TableBounds(
            (bounds.left * sampleStep).toFloat(),
            (bounds.top * sampleStep).toFloat(),
            min(frame.width, (bounds.right + 1) * sampleStep).toFloat(),
            min(frame.height, (bounds.bottom + 1) * sampleStep).toFloat()
        )
        val circles = findCircles(frame, table)
        val balls = circles.map { c ->
            val classified = classifier.classify(frame, c.center, c.radius)
            DetectedBall(
                center = c.center,
                radius = c.radius,
                confidence = (0.55f * c.edgeScore + 0.45f * c.insideScore).coerceIn(0f, 1f),
                ballId = classified.ballId,
                kind = classified.kind,
                classificationConfidence = classified.confidence,
                whiteFraction = classified.whiteFraction,
                darkFraction = classified.darkFraction
            )
        }
        val tableConfidence = (
            0.40f + 0.30f * fill.coerceIn(0f, 1f) +
                0.30f * (1f - abs(aspect - 2f) / 0.65f).coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)
        val identified = balls.count { it.ballId != null }
        val cueCount = balls.count { it.ballId == BallIdentity.CUE }
        val message = "Table found; ${balls.size} ball candidates, ${identified} identified, ${cueCount} cue ball(s)"
        return PoolScene(table, balls, tableConfidence, frame.width, frame.height, message)
    }

    private fun largestComponent(mask: BooleanArray, width: Int, height: Int): ComponentBounds? {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var best: ComponentBounds? = null
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var left = start % width
            var right = left
            var top = start / width
            var bottom = top
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                left = min(left, x); right = max(right, x)
                top = min(top, y); bottom = max(bottom, y)
                val neighbors = intArrayOf(
                    if (x > 0) index - 1 else -1,
                    if (x + 1 < width) index + 1 else -1,
                    if (y > 0) index - width else -1,
                    if (y + 1 < height) index + width else -1
                )
                for (next in neighbors) {
                    if (next >= 0 && mask[next] && !visited[next]) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }
            if (best == null || tail > best.count) {
                best = ComponentBounds(left, top, right, bottom, tail)
            }
        }
        return best
    }

    private fun findCircles(frame: CapturedRgbaFrame, table: TableBounds): List<CircleCandidate> {
        val radius = table.width * 0.016f
        if (radius < 5f) return emptyList()
        val candidates = ArrayList<CircleCandidate>()
        val left = (table.left + radius * 1.45f).toInt()
        val right = (table.right - radius * 1.45f).toInt()
        val top = (table.top + radius * 1.45f).toInt()
        val bottom = (table.bottom - radius * 1.45f).toInt()

        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                val score = circleScore(frame, x.toFloat(), y.toFloat(), radius)
                if (score.first > 0.28f && score.second > 0.48f) {
                    // A local 3x3 refinement reduces the quantisation error from
                    // the coarse grid without increasing the full-frame cost.
                    var bestX = x
                    var bestY = y
                    var bestScore = score
                    for (dy in -2..2 step 2) for (dx in -2..2 step 2) {
                        val refined = circleScore(frame, (x + dx).toFloat(), (y + dy).toFloat(), radius)
                        if (refined.first * refined.second > bestScore.first * bestScore.second) {
                            bestX = x + dx
                            bestY = y + dy
                            bestScore = refined
                        }
                    }
                    candidates.add(
                        CircleCandidate(
                            FloatPoint(bestX.toFloat(), bestY.toFloat()),
                            radius,
                            bestScore.first,
                            bestScore.second
                        )
                    )
                }
                x += 4
            }
            y += 4
        }

        // Greedy non-maximum suppression. Touching pool balls are about 2R
        // apart, so the exclusion distance is deliberately below that value.
        val selected = ArrayList<CircleCandidate>()
        for (candidate in candidates.sortedByDescending { it.edgeScore * it.insideScore }) {
            if (selected.none {
                    distance(it.center, candidate.center) < radius * 1.18f
                }) {
                selected.add(candidate)
                if (selected.size == 16) break
            }
        }
        return selected
    }

    /** Returns edge contrast and the proportion of inner samples that are not felt. */
    private fun circleScore(
        frame: CapturedRgbaFrame,
        cx: Float,
        cy: Float,
        radius: Float
    ): Pair<Float, Float> {
        var contrast = 0f
        var strongEdges = 0
        var innerCount = 0
        var nonCloth = 0
        for (i in 0 until 16) {
            val angle = i * (Math.PI * 2.0 / 16.0)
            val dx = kotlin.math.cos(angle).toFloat()
            val dy = kotlin.math.sin(angle).toFloat()
            val inner = frame.pixelAt(
                (cx + dx * radius * 0.78f).toInt(),
                (cy + dy * radius * 0.78f).toInt()
            ) ?: continue
            val outer = frame.pixelAt(
                (cx + dx * radius * 1.24f).toInt(),
                (cy + dy * radius * 1.24f).toInt()
            ) ?: continue
            val difference = (
                abs(inner.red - outer.red) +
                    abs(inner.green - outer.green) +
                    abs(inner.blue - outer.blue)
                ) / 3f
            contrast += difference
            if (difference > 28f) strongEdges++

            val inside = frame.pixelAt(
                (cx + dx * radius * 0.35f).toInt(),
                (cy + dy * radius * 0.35f).toInt()
            )
            if (inside != null) {
                innerCount++
                if (!isCloth(inside.red, inside.green, inside.blue)) nonCloth++
            }
        }
        if (innerCount == 0) return 0f to 0f
        val meanContrast = contrast / 16f
        val edgeScore = (meanContrast / 88f).coerceIn(0f, 1f) *
            (strongEdges / 16f).coerceIn(0f, 1f)
        val insideScore = nonCloth.toFloat() / innerCount
        return edgeScore to insideScore
    }

    private fun isCloth(r: Int, g: Int, b: Int): Boolean {
        val blueFelt = b > 105 && g > 68 && b > g * 1.08f &&
            g > r * 1.24f && b - r > 48
        val greenFelt = g > 48 && g > r * 1.12f &&
            g > b * 1.04f && g - r > 12
        return blueFelt || greenFelt
    }

    private fun distance(a: FloatPoint, b: FloatPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun empty(frame: CapturedRgbaFrame, reason: String) =
        PoolScene(null, emptyList(), 0f, frame.width, frame.height, reason)
}
