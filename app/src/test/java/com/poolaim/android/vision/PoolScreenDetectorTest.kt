package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolScreenDetectorTest {
    @Test fun findsBlueTableAndClassifiesCueAndObjectBalls() {
        val width = 700
        val height = 400
        val bytes = ByteArray(width * height * 4)
        fun put(x: Int, y: Int, r: Int, g: Int, b: Int) {
            val i = (y * width + x) * 4
            bytes[i] = r.toByte(); bytes[i + 1] = g.toByte()
            bytes[i + 2] = b.toByte(); bytes[i + 3] = 255.toByte()
        }
        for (y in 50 until 350) for (x in 50 until 650) put(x, y, 20, 130, 180)
        for (y in 190..210) for (x in 190..210) {
            val dx = x - 200; val dy = y - 200
            if (dx * dx + dy * dy <= 100) put(x, y, 246, 243, 234)
        }
        for (y in 190..210) for (x in 490..510) {
            val dx = x - 500; val dy = y - 200
            if (dx * dx + dy * dy <= 100) put(x, y, 210, 56, 42)
        }
        val frame = CapturedRgbaFrame.fromPlaneBytes(bytes, width, height, width * 4, 4)
        val scene = PoolScreenDetector(2).detect(frame)
        assertNotNull(scene.table)
        assertTrue(scene.confidence > 0.5f)
        assertTrue(scene.balls.isNotEmpty())
        assertTrue(scene.balls.any { it.ballId == BallIdentity.CUE })
        assertTrue(scene.balls.any { it.ballId == 3 })
    }


    @Test fun findsNeutralGreyTableAndCueBall() {
        val width = 700
        val height = 400
        val bytes = ByteArray(width * height * 4)
        fun put(x: Int, y: Int, r: Int, g: Int, b: Int) {
            val i = (y * width + x) * 4
            bytes[i] = r.toByte(); bytes[i + 1] = g.toByte()
            bytes[i + 2] = b.toByte(); bytes[i + 3] = 255.toByte()
        }
        for (y in 50 until 350) for (x in 50 until 650) put(x, y, 86, 86, 86)
        for (y in 190..210) for (x in 190..210) {
            val dx = x - 200; val dy = y - 200
            if (dx * dx + dy * dy <= 100) put(x, y, 246, 243, 234)
        }
        for (y in 190..210) for (x in 490..510) {
            val dx = x - 500; val dy = y - 200
            if (dx * dx + dy * dy <= 100) put(x, y, 210, 56, 42)
        }
        val frame = CapturedRgbaFrame.fromPlaneBytes(bytes, width, height, width * 4, 4)
        val scene = PoolScreenDetector(2).detect(frame)
        assertNotNull(scene.table)
        assertTrue(scene.balls.isNotEmpty())
        assertTrue(scene.balls.any { it.ballId == BallIdentity.CUE })
    }

    @Test fun detectsCueShaftWithoutAssumingWoodColour() {
        val width = 500
        val height = 400
        val bytes = ByteArray(width * height * 4)
        fun put(x: Int, y: Int, r: Int, g: Int, b: Int) {
            val i = (y * width + x) * 4
            bytes[i] = r.toByte(); bytes[i + 1] = g.toByte()
            bytes[i + 2] = b.toByte(); bytes[i + 3] = 255.toByte()
        }
        for (y in 20 until 380) for (x in 20 until 480) put(x, y, 86, 86, 86)
        // Bright cyan cue shaft extending upward from the cue-ball centre.
        for (y in 45 until 185) for (x in 247..253) put(x, y, 90, 220, 245)
        for (y in 190..210) for (x in 240..260) {
            val dx = x - 250; val dy = y - 200
            if (dx * dx + dy * dy <= 100) put(x, y, 246, 243, 234)
        }
        val frame = CapturedRgbaFrame.fromPlaneBytes(bytes, width, height, width * 4, 4)
        val axis = CueStickAxisDetector().detect(
            frame, com.poolaim.android.engine.FloatPoint(250f, 200f), 10f,
            TableBounds(20f, 20f, 480f, 380f)
        )
        assertNotNull(axis)
        assertTrue(kotlin.math.abs(axis!!.y) > 0.90f)
    }

    @Test fun rejectsNonPoolScreen() {
        val width = 160
        val height = 100
        val bytes = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            bytes[i * 4] = 20.toByte()
            bytes[i * 4 + 1] = 30.toByte()
            bytes[i * 4 + 2] = 55.toByte()
            bytes[i * 4 + 3] = 255.toByte()
        }
        val scene = PoolScreenDetector().detect(
            CapturedRgbaFrame.fromPlaneBytes(bytes, width, height, width * 4, 4)
        )
        assertEquals(null, scene.table)
        assertTrue(scene.balls.isEmpty())
    }
}
