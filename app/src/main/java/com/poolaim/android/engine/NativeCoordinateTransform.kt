package com.poolaim.android.engine

/**
 * JNI adapter transform observed in the ARM64 payload:
 * Java = trunc(nativeCoordinate / scale + outputOffset).
 */
class NativeCoordinateTransform(
    val scale: Float,
    val outputOffset: FloatPoint
) {
    init {
        require(scale.isFinite() && scale != 0f)
        require(outputOffset.x.isFinite() && outputOffset.y.isFinite())
    }

    fun toJavaPoint(nativePoint: FloatPoint): IntPoint {
        return IntPoint(
            truncateToInt(nativePoint.x / scale + outputOffset.x),
            truncateToInt(nativePoint.y / scale + outputOffset.y)
        )
    }
}
