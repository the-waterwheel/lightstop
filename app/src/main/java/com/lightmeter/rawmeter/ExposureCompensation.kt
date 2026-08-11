package com.lightmeter.rawmeter

import kotlin.math.abs

/**
 * Selectable detent size for the exposure-compensation dial.
 *
 * Compensation continues to be stored in sixth-stop units so existing preferences remain valid
 * and switching the setting does not accumulate floating-point conversion error.
 */
enum class ExposureCompensationStep(val sixthStops: Int) {
    SIXTH(1),
    THIRD(2),
    HALF(3),
    FULL(6),
}

/** Pure dial/grid math shared by state, rendering, gestures, and unit tests. */
internal object ExposureCompensationDial {
    const val MIN_SIXTH_STOPS = -30
    const val MAX_SIXTH_STOPS = 30

    /** Snap to the nearest value representable by [step]; exact half-way values stay non-zero. */
    fun snap(sixthStops: Int, step: ExposureCompensationStep): Int {
        val unit = step.sixthStops
        val magnitude = abs(sixthStops.coerceIn(MIN_SIXTH_STOPS, MAX_SIXTH_STOPS))
        val snappedMagnitude = ((magnitude + unit / 2) / unit) * unit
        return if (sixthStops < 0) -snappedMagnitude else snappedMagnitude
    }

    /**
     * Converts physical clockwise dial detents into stored sixth-stop units.
     * The sign mirrors with handedness because the compensation scale changes sides.
     */
    fun applyDetents(
        currentSixthStops: Int,
        clockwiseDetents: Int,
        step: ExposureCompensationStep,
        leftHanded: Boolean,
    ): Int {
        val direction = if (leftHanded) -1 else 1
        return (currentSixthStops + direction * clockwiseDetents * step.sixthStops)
            .coerceIn(MIN_SIXTH_STOPS, MAX_SIXTH_STOPS)
    }

    /**
     * Offset from the scale's horizontal selection point. Positive EV is visually above it on
     * both the left- and right-handed layouts; Android dial angles increase clockwise.
     */
    fun relativeTickAngleDegrees(
        tickSixthStops: Int,
        currentSixthStops: Int,
        step: ExposureCompensationStep,
        leftHanded: Boolean,
        spacingDegrees: Float,
    ): Float {
        val detents = (tickSixthStops - currentSixthStops).toFloat() / step.sixthStops
        val visualDirection = if (leftHanded) 1f else -1f
        return visualDirection * detents * spacingDegrees
    }
}
