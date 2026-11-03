package com.poolaim.android.engine

import com.poolaim.android.capture.CapturedRgbaFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeMaskPipelineTest {
    private fun frame(width: Int = 1, height: Int = 1) =
        CapturedRgbaFrame.fromPlaneBytes(
            ByteArray(width * height * 4),
            width,
            height,
            width * 4,
            4
        )

    @Test
    fun sendsPreparedTripletsThroughRecoveredPredicateLoop() {
        val pipeline = NativeMaskPipeline(
            NativeMaskInputPreprocessor {
                NativePreparedMaskInput(
                    category = 1,
                    primaryTriplets = PackedTripletImage(
                        byteArrayOf(0, 10, 200),
                        width = 1,
                        height = 1,
                        rowStrideBytes = 3
                    ),
                    thresholds = NativeMaskThresholds(
                        center = 100,
                        tolerance = 10,
                        component1UpperExclusive = 190,
                        component2LowerExclusive = 100,
                        component2UpperExclusive = 240
                    )
                )
            }
        )
        val result = pipeline.process(frame(), byteArrayOf(0))

        assertEquals(1, result.width)
        assertEquals(1, result.height)
        assertEquals(1, result.category)
        assertEquals(0xff, result.mask.unsignedAt(0, 0))
    }

    @Test
    fun rejectsPreparedDimensionsThatDoNotMatchCapturedFrame() {
        val pipeline = NativeMaskPipeline(
            NativeMaskInputPreprocessor {
                NativePreparedMaskInput(
                    category = 1,
                    primaryTriplets = PackedTripletImage(
                        byteArrayOf(0, 10, 200, 0, 10, 200),
                        width = 2,
                        height = 1,
                        rowStrideBytes = 6
                    ),
                    thresholds = NativeMaskThresholds(100, 10, 190, 100, 240)
                )
            }
        )

        assertThrows(IllegalArgumentException::class.java) {
            pipeline.process(frame(width = 1, height = 1), byteArrayOf(0, 0))
        }
    }

    @Test
    fun category8RequiresItsSecondPreparedImage() {
        val pipeline = NativeMaskPipeline(
            NativeMaskInputPreprocessor {
                NativePreparedMaskInput(
                    category = NativeMaskCategory.PROFILE_8,
                    primaryTriplets = PackedTripletImage(
                        byteArrayOf(0, 10, 200),
                        width = 1,
                        height = 1,
                        rowStrideBytes = 3
                    ),
                    thresholds = NativeMaskThresholds(100, 10, 190, 100, 240)
                )
            }
        )

        assertThrows(IllegalArgumentException::class.java) {
            pipeline.process(frame(), byteArrayOf(0))
        }
    }
}
