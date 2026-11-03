package com.poolaim.android.engine

/**
 * Output of the still-unrecovered native RGBA-to-triplet preprocessing stage.
 *
 * The arrays and thresholds must come from a verified adapter; this type does
 * not assign RGB/BGR meaning, resize/crop policy, or invent threshold values.
 */
data class NativePreparedMaskInput(
    val category: Int,
    val primaryTriplets: PackedTripletImage,
    val thresholds: NativeMaskThresholds,
    val secondaryTriplets: PackedTripletImage? = null
)

/** Result of running the recovered downstream byte-predicate loops. */
data class NativeMaskResult(
    val mask: MutableMaskPlane,
    val category: Int,
    val width: Int,
    val height: Int
)

/**
 * Narrow integration seam between native preprocessing and the recovered
 * mask predicates. A production implementation must be backed by verified
 * original preprocessing evidence before it is enabled for captured frames.
 */
fun interface NativeMaskInputPreprocessor {
    fun prepare(frame: com.poolaim.android.capture.CapturedRgbaFrame): NativePreparedMaskInput
}

/**
 * Runs only the recovered downstream mask stage.
 *
 * This class intentionally does not provide a default preprocessor, category
 * classifier, thresholds, or buffer initialization. Those upstream semantics
 * are not established by the current RE evidence.
 */
class NativeMaskPipeline(
    private val preprocessor: NativeMaskInputPreprocessor
) {
    fun process(
        frame: com.poolaim.android.capture.CapturedRgbaFrame,
        destinationBytes: ByteArray
    ): NativeMaskResult {
        val input = preprocessor.prepare(frame)
        val width = input.primaryTriplets.width
        val height = input.primaryTriplets.height
        require(width == frame.width && height == frame.height) {
            "Prepared native triplets must match the captured frame dimensions"
        }
        require(destinationBytes.size >= width * height) {
            "Destination mask buffer is too small"
        }

        val mask = MutableMaskPlane(destinationBytes, width, height)
        NativeMaskGenerator.apply(
            classifierCategory = input.category,
            first = input.primaryTriplets,
            output = mask,
            thresholds = input.thresholds,
            secondForCategory8 = input.secondaryTriplets
        )
        return NativeMaskResult(mask, input.category, width, height)
    }
}
