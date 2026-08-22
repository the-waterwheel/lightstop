package com.lightmeter.rawmeter

import kotlin.math.atan
import kotlin.math.min
import kotlin.math.tan

/** Optical field-of-view calculations shared by the angle dial and RAW analyzer. */
internal object AngleMeteringMath {
    // The final 40° and 50° stops follow the requested 30–50° ten-degree interval.
    val selectableDegrees = intArrayOf(
        1, 2, 3, 4, 5,
        7, 9, 11, 13, 15,
        20, 25, 30,
        40, 50,
    )

    const val DEFAULT_DEGREES = 1

    fun maximumSupportedDegrees(
        focalLengthMm: Double,
        sensorWidthMm: Double,
        sensorHeightMm: Double,
        sensorFrameAspect: Double,
        zoom: Double,
    ): Double? {
        val shortSide = effectiveCropShortSideMm(
            sensorWidthMm,
            sensorHeightMm,
            sensorFrameAspect,
            zoom,
        ) ?: return null
        if (!focalLengthMm.isFinite() || focalLengthMm <= 0.0) return null
        return Math.toDegrees(2.0 * atan(shortSide / (2.0 * focalLengthMm)))
    }

    fun roiFraction(
        angleDegrees: Int,
        focalLengthMm: Double,
        sensorWidthMm: Double,
        sensorHeightMm: Double,
        sensorFrameAspect: Double,
        zoom: Double,
    ): Float? {
        val shortSide = effectiveCropShortSideMm(
            sensorWidthMm,
            sensorHeightMm,
            sensorFrameAspect,
            zoom,
        ) ?: return null
        if (!focalLengthMm.isFinite() || focalLengthMm <= 0.0) return null
        val angle = angleDegrees.coerceIn(selectableDegrees.first(), selectableDegrees.last())
        val projectedDiameter = 2.0 * focalLengthMm * tan(Math.toRadians(angle / 2.0))
        return (projectedDiameter / shortSide).toFloat().coerceIn(0f, 1f)
    }

    fun maximumSupportedIndex(maximumDegrees: Double?): Int {
        if (maximumDegrees == null || !maximumDegrees.isFinite()) return selectableDegrees.lastIndex
        return selectableDegrees.indexOfLast { it <= maximumDegrees + SUPPORT_TOLERANCE_DEGREES }
            .coerceAtLeast(0)
    }

    fun nearestSelectableDegrees(value: Int): Int =
        selectableDegrees.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_DEGREES

    private fun effectiveCropShortSideMm(
        sensorWidthMm: Double,
        sensorHeightMm: Double,
        sensorFrameAspect: Double,
        zoom: Double,
    ): Double? {
        if (!sensorWidthMm.isFinite() || !sensorHeightMm.isFinite() ||
            sensorWidthMm <= 0.0 || sensorHeightMm <= 0.0 ||
            !sensorFrameAspect.isFinite() || sensorFrameAspect <= 0.0 ||
            !zoom.isFinite() || zoom <= 0.0
        ) return null
        var cropWidth = sensorWidthMm
        var cropHeight = sensorHeightMm
        if (cropWidth / cropHeight > sensorFrameAspect) {
            cropWidth = cropHeight * sensorFrameAspect
        } else {
            cropHeight = cropWidth / sensorFrameAspect
        }
        return min(cropWidth, cropHeight) / zoom
    }

    private const val SUPPORT_TOLERANCE_DEGREES = 0.05
}

/** Compatibility-mode restriction kept independent from the settings UI. */
internal object MeteringAreaPolicy {
    fun canSelect(mode: MeteringMode, pipeline: MeteringPipelineMode): Boolean =
        mode != MeteringMode.ANGLE || pipeline != MeteringPipelineMode.FAST

    fun resolveForPipeline(
        current: MeteringMode,
        pipeline: MeteringPipelineMode,
    ): MeteringMode = if (canSelect(current, pipeline)) {
        current
    } else {
        MeteringMode.CENTER_WEIGHTED
    }
}
