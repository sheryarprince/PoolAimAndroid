package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PoolScreenDetectorTest {
    @Test fun findsGreenTableAndCircularBallCandidateInSyntheticFrame() {
        val width = 320
        val height = 200
        val bytes = ByteArray(width * height * 4)
        fun put(x: Int, y: Int, r: Int, g: Int, b: Int) {
            val i = (y * width + x) * 4
            bytes[i] = r.toByte(); bytes[i + 1] = g.toByte()
            bytes[i + 2] = b.toByte(); bytes[i + 3] = 255.toByte()
        }
        for (y in 30 until 170) for (x in 30 until 290) put(x, y, 20, 120, 65)
        for (y in 91..109) for (x in 141..159) {
            if (sqrt(((x - 150) * (x - 150) + (y - 100) * (y - 100)).toDouble()) <= 6.0)
                put(x, y, 235, 235, 230)
        }
        val frame = CapturedRgbaFrame.fromPlaneBytes(bytes, width, height, width * 4, 4)
        val scene = PoolScreenDetector(2).detect(frame)
        assertNotNull(scene.table)
        assertTrue(scene.confidence > 0.5f)
        assertTrue(scene.balls.isNotEmpty())
        assertEquals(150f, scene.balls.first().center.x, 3f)
        assertEquals(100f, scene.balls.first().center.y, 3f)
    }

    @Test fun rejectsFrameWithoutGreenTable() {
        val width = 120
        val height = 80
        val bytes = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            bytes[i * 4] = 80.toByte()
            bytes[i * 4 + 1] = 80.toByte()
            bytes[i * 4 + 2] = 80.toByte()
            bytes[i * 4 + 3] = 255.toByte()
        }
        val scene = PoolScreenDetector().detect(
            CapturedRgbaFrame.fromPlaneBytes(bytes, width, height, width * 4, 4)
        )
        assertEquals(null, scene.table)
        assertTrue(scene.balls.isEmpty())
    }
}
