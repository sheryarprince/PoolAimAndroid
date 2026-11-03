package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.engine.FloatPoint
import kotlin.math.max
import kotlin.math.min

data class TableBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}
data class DetectedBall(val center: FloatPoint, val radius: Float, val confidence: Float, val isCueCandidate: Boolean = false)
data class PoolScene(
    val table: TableBounds?, val balls: List<DetectedBall>, val confidence: Float,
    val frameWidth: Int, val frameHeight: Int, val diagnostic: String
)

/** Conservative first-stage detector: green cloth envelope + circular non-cloth candidates.
 * Coordinates are captured-screen pixels. It does not classify ball numbers or infer pockets.
 */
class PoolScreenDetector(private val sampleStep: Int = 2) {
    init { require(sampleStep in 1..6) }

    fun detect(frame: CapturedRgbaFrame): PoolScene {
        val w = (frame.width + sampleStep - 1) / sampleStep
        val h = (frame.height + sampleStep - 1) / sampleStep
        if (w < 40 || h < 40) return empty(frame, "Frame too small")
        val green = BooleanArray(w * h)
        var count = 0
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (gy in 0 until h) for (gx in 0 until w) {
            val p = frame.pixelAt(gx * sampleStep, gy * sampleStep) ?: continue
            if (p.green > 48 && p.green > p.red * 1.12 &&
                p.green > p.blue * 1.07 && p.green - p.red > 14) {
                green[gy * w + gx] = true
                count++
                minX = min(minX, gx); minY = min(minY, gy)
                maxX = max(maxX, gx); maxY = max(maxY, gy)
            }
        }
        if (count < w * h * 0.025 || maxX <= minX || maxY <= minY)
            return empty(frame, "Green table not confidently found")
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val aspect = bw.toFloat() / bh
        val fill = count.toFloat() / (bw * bh)
        if (aspect !in 1.45f..2.35f || fill < 0.22f)
            return empty(frame, "Green region is not a plausible pool-table surface")
        val table = TableBounds(minX * sampleStep.toFloat(), minY * sampleStep.toFloat(),
            min(frame.width.toFloat(), (maxX + 1) * sampleStep.toFloat()),
            min(frame.height.toFloat(), (maxY + 1) * sampleStep.toFloat()))
        val balls = findCircularComponents(frame, green, w, h, minX, minY, maxX, maxY, table)
        val confidence = (0.45f + 0.35f * fill.coerceIn(0f, 1f) +
            0.20f * (1f - kotlin.math.abs(aspect - 2f) / 0.55f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        return PoolScene(table, balls, confidence, frame.width, frame.height,
            "Cloth envelope found; " + balls.size + " circular candidates (unclassified)")
    }

    private fun findCircularComponents(
        frame: CapturedRgbaFrame, green: BooleanArray, w: Int, h: Int,
        left: Int, top: Int, right: Int, bottom: Int, table: TableBounds
    ): List<DetectedBall> {
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val result = ArrayList<DetectedBall>()
        val minDiameter = table.width * 0.018f / sampleStep
        val maxDiameter = table.width * 0.070f / sampleStep
        for (y in top + 2 until bottom - 1) for (x in left + 2 until right - 1) {
            val start = y * w + x
            if (seen[start] || green[start]) continue
            val pix = frame.pixelAt(x * sampleStep, y * sampleStep) ?: continue
            if (pix.alpha < 100 || max(pix.red, max(pix.green, pix.blue)) < 75) {
                seen[start] = true; continue
            }
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            var minCx = x; var maxCx = x; var minCy = y; var maxCy = y
            var sumX = 0L; var sumY = 0L; var n = 0
            while (head < tail) {
                val idx = queue[head++]
                val cx = idx % w; val cy = idx / w
                minCx = min(minCx, cx); maxCx = max(maxCx, cx)
                minCy = min(minCy, cy); maxCy = max(maxCy, cy)
                sumX += cx; sumY += cy; n++
                val ns = intArrayOf(idx - 1, idx + 1, idx - w, idx + w)
                for (ni in ns) {
                    if (ni < 0 || ni >= w * h || seen[ni] || green[ni]) continue
                    val nx = ni % w; val ny = ni / w
                    if (nx !in left + 1 until right || ny !in top + 1 until bottom) continue
                    val q = frame.pixelAt(nx * sampleStep, ny * sampleStep) ?: continue
                    if (q.alpha < 100 || max(q.red, max(q.green, q.blue)) < 75) continue
                    seen[ni] = true
                    if (tail < queue.size) queue[tail++] = ni
                }
            }
            val cw = maxCx - minCx + 1
            val ch = maxCy - minCy + 1
            val diameter = (cw + ch) * 0.5f
            val ratio = cw.toFloat() / ch.coerceAtLeast(1)
            if (n < 5 || diameter !in minDiameter..maxDiameter || ratio !in 0.68f..1.45f) continue
            val circularity = (min(cw, ch).toFloat() / max(cw, ch)).coerceIn(0f, 1f)
            val sizeScore = (1f - kotlin.math.abs(diameter - (minDiameter + maxDiameter) / 2f) /
                ((maxDiameter - minDiameter) / 2f)).coerceIn(0f, 1f)
            val centerX = ((sumX.toFloat() / n + 0.5f) * sampleStep).toInt().coerceIn(0, frame.width - 1)
            val centerY = ((sumY.toFloat() / n + 0.5f) * sampleStep).toInt().coerceIn(0, frame.height - 1)
            val centerPixel = frame.pixelAt(centerX, centerY)
            val whiteCue = centerPixel != null && centerPixel.red > 190 &&
                centerPixel.green > 190 && centerPixel.blue > 175 &&
                kotlin.math.abs(centerPixel.red - centerPixel.green) < 55 &&
                kotlin.math.abs(centerPixel.green - centerPixel.blue) < 65
            result.add(DetectedBall(FloatPoint(centerX.toFloat(), centerY.toFloat()),
                diameter * sampleStep * 0.5f,
                (0.55f * circularity + 0.45f * sizeScore).coerceIn(0f, 1f), whiteCue))
        }
        return result.sortedByDescending { it.confidence }.take(16)
    }

    private fun empty(frame: CapturedRgbaFrame, reason: String) =
        PoolScene(null, emptyList(), 0f, frame.width, frame.height, reason)
}
