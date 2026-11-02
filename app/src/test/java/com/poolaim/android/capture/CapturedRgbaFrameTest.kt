package com.poolaim.android.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapturedRgbaFrameTest {
    @Test
    fun pixelLookupHonorsPaddedRowStrideAndPixelStride() {
        val bytes = ByteArray(24)
        bytes[0] = 1; bytes[1] = 2; bytes[2] = 3; bytes[3] = 4
        bytes[4] = 5; bytes[5] = 6; bytes[6] = 7; bytes[7] = 8
        bytes[12] = 9; bytes[13] = 10; bytes[14] = 11; bytes[15] = 12
        bytes[16] = 13; bytes[17] = 14; bytes[18] = 15; bytes[19] = 16

        val frame = CapturedRgbaFrame.fromPlaneBytes(
            source = bytes,
            width = 2,
            height = 2,
            rowStride = 12,
            pixelStride = 4
        )

        assertEquals(RgbaPixel(1, 2, 3, 4), frame.pixelAt(0, 0))
        assertEquals(RgbaPixel(13, 14, 15, 16), frame.pixelAt(1, 1))
        assertNull(frame.pixelAt(2, 1))
    }

    @Test
    fun snapshotCopiesInputBytes() {
        val source = byteArrayOf(1, 2, 3, 4)
        val frame = CapturedRgbaFrame.fromPlaneBytes(source, 1, 1, 4, 4)
        source[0] = 99
        assertEquals(1, frame.pixelAt(0, 0)!!.red)
    }
}
