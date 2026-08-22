package com.lightmeter.rawmeter

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Pure state policy for RAW highlight-protection recaptures. */
internal object RawHighlightProtectionPolicy {
    const val EXPOSURE_STEP_EV = 3
    const val MAX_RECAPTURE_STAGE = 2
    const val SINGLE_FRAME_COUNT = 1

    /**
     * Ignore a very small number of saturated samples, which can be caused by hot pixels.
     * A one-percent clipped region is still low enough to protect a small specular highlight.
     */
    const val CLIPPED_FRACTION_THRESHOLD = 0.01

    fun nextStage(clippedFraction: Double, currentStage: Int): Int? = when {
        !clippedFraction.isFinite() -> null
        clippedFraction <= CLIPPED_FRACTION_THRESHOLD -> null
        currentStage >= MAX_RECAPTURE_STAGE -> null
        else -> currentStage + 1
    }

    fun exposureReductionEv(stage: Int): Int =
        stage.coerceIn(0, MAX_RECAPTURE_STAGE) * EXPOSURE_STEP_EV
}

internal data class ManualExposurePlan(
    val exposureTimeNs: Long,
    val sensitivity: Int,
)

/** Converts an EV reduction into a bounded manual sensor exposure. */
internal object ExposureReductionPlanner {
    fun plan(
        baseExposureTimeNs: Long,
        baseSensitivity: Int,
        reductionEv: Int,
        minimumExposureTimeNs: Long,
        maximumExposureTimeNs: Long,
        minimumSensitivity: Int,
        maximumSensitivity: Int,
    ): ManualExposurePlan {
        val minTime = minimumExposureTimeNs.coerceAtLeast(1L)
        val maxTime = maximumExposureTimeNs.coerceAtLeast(minTime)
        val minIso = minimumSensitivity.coerceAtLeast(1)
        val maxIso = maximumSensitivity.coerceAtLeast(minIso)
        val baseTime = baseExposureTimeNs.coerceIn(minTime, maxTime)
        val baseIso = baseSensitivity.coerceIn(minIso, maxIso)
        val factor = 2.0.pow(reductionEv.coerceAtLeast(0))

        // Shorten shutter time first. If the sensor minimum is reached, use ISO for the
        // remainder so exposureTime * ISO remains as close as possible to the target.
        val targetExposureProduct = baseTime.toDouble() * baseIso.toDouble() / factor
        val plannedTime = (baseTime.toDouble() / factor)
            .roundToLong()
            .coerceIn(minTime, maxTime)
        val plannedIso = (targetExposureProduct / plannedTime.toDouble())
            .roundToInt()
            .coerceIn(minIso, maxIso)
        return ManualExposurePlan(plannedTime, plannedIso)
    }
}
