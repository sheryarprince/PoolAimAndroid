package com.poolaim.android

import android.content.Context
import android.graphics.PointF

/**
 * Stores the six pocket centres in normalized screen coordinates.
 * Normalized values survive display-resolution changes while remaining
 * tied to the landscape orientation in which calibration was performed.
 */
class PocketCalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("pool_aim_calibration", Context.MODE_PRIVATE)

    fun load(): List<PointF>? {
        if (!prefs.getBoolean("complete", false)) return null
        val points = (0 until 6).map { i ->
            val x = prefs.getFloat("x_$i", Float.NaN)
            val y = prefs.getFloat("y_$i", Float.NaN)
            if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
            PointF(x, y)
        }
        return points
    }

    fun save(points: List<PointF>) {
        require(points.size == 6)
        prefs.edit().clear().apply {
            points.forEachIndexed { i, point ->
                putFloat("x_$i", point.x.coerceIn(0f, 1f))
                putFloat("y_$i", point.y.coerceIn(0f, 1f))
            }
            putBoolean("complete", true)
        }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
