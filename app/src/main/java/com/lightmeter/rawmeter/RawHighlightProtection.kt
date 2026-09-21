package com.lightmeter.rawmeter

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Inputs for deciding whether a RAW frame's usable statistic is distorted. */
internal data class RawExposureRetryInput(
    /** Channel medians at or above [RawExposureRetryPolicy.CHANNEL_SATURATION_LEVEL]. */
    val saturatedChannelCount: Int,
    val currentStage: Int,
    /** Diagnostic only; never triggers a retry by itself. */
    val clippedFraction: Double,
)

/**
 * Pure state policy for RAW exposure recaptures.
 *
 * A saturated-pixel fraction is deliberately not a quality gate: a few specular highlights do not
 * distort a robust per-channel median. A recapture is requested only when the statistic actually
 * used for metering is pinned at the white level, i.e. enough channel medians are saturated.
 */
internal object RawExposureRetryPolicy {
    const val EXPOSURE_STEP_EV = 3
    const val MAX_RECAPTURE_STAGE = 2
    const val SINGLE_FRAME_COUNT = 1

    /** Normalized median at which a channel's typical value is effectively at the white level. */
    const val CHANNEL_SATURATION_LEVEL = 0.985

    /** Two saturated channel medians (for example both green channels) indicate a distorted read. */
    const val REQUIRED_SATURATED_CHANNELS = 2

    fun nextStage(input: RawExposureRetryInput): Int? {
        if (input.currentStage >= MAX_RECAPTURE_STAGE) return null
        val statisticsDistorted =
            input.saturatedChannelCount >= REQUIRED_SATURATED_CHANNELS
        return if (statisticsDistorted) input.currentStage + 1 else null
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
