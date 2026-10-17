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
    /** A whole-frame algorithm's actually used bright-end statistic was censored. */
    val statisticsClipped: Boolean = false,
    val meteringMode: MeteringMode = MeteringMode.SPOT,
)

/**
 * Pure state policy for RAW exposure recaptures.
 *
 * Saturated-pixel fraction is diagnostic. Local modes use channel medians and five-EV steps;
 * average metering supplies its bright-end censoring evidence and uses three-EV steps.
 * Both continue until their statistic clears or the camera cannot reduce exposure further.
 */
internal object RawExposureRetryPolicy {
    private const val AVERAGE_EXPOSURE_STEP_EV = 3
    private const val LOCAL_EXPOSURE_STEP_EV = 5
    const val SINGLE_FRAME_COUNT = 1

    /** Normalized median at which a channel's typical value is effectively at the white level. */
    const val CHANNEL_SATURATION_LEVEL = 0.985

    /** Even one clipped channel median can bias the local brightness reading. */
    const val REQUIRED_SATURATED_CHANNELS = 1

    fun saturatedChannelCount(channelMedians: DoubleArray): Int =
        if (channelMedians.size < 4) 0 else (0..3).count { channelMedians[it] >= CHANNEL_SATURATION_LEVEL }

    private fun exposureStepEv(mode: MeteringMode): Int =
        if (mode == MeteringMode.AVERAGE) AVERAGE_EXPOSURE_STEP_EV else LOCAL_EXPOSURE_STEP_EV

    fun nextStage(input: RawExposureRetryInput): Int? {
        val step = exposureStepEv(input.meteringMode)
        if (input.currentStage < 0 || input.currentStage >= Int.MAX_VALUE / step) return null
        val statisticsDistorted = if (input.meteringMode == MeteringMode.AVERAGE) input.statisticsClipped
            else input.saturatedChannelCount >= REQUIRED_SATURATED_CHANNELS
        return if (statisticsDistorted) input.currentStage + 1 else null
    }

    fun exposureReductionEv(stage: Int, meteringMode: MeteringMode = MeteringMode.SPOT): Int {
        val step = exposureStepEv(meteringMode)
        return stage.coerceIn(0, Int.MAX_VALUE / step) * step
    }
}

internal data class ManualExposurePlan(
    val exposureTimeNs: Long,
    val sensitivity: Int,
)

/** Stop at the sensor/AE boundary instead of resubmitting an identical clipped exposure forever. */
internal object RawExposureRetryProgressPolicy {
    fun canReduceManual(current: ManualExposurePlan, next: ManualExposurePlan): Boolean =
        current.exposureTimeNs > 0 && current.sensitivity > 0 &&
            next.exposureTimeNs > 0 && next.sensitivity > 0 &&
            next.exposureTimeNs.toDouble() * next.sensitivity <
            current.exposureTimeNs.toDouble() * current.sensitivity

    fun canReduceAeCompensation(currentSteps: Int?, nextSteps: Int?): Boolean =
        nextSteps != null && nextSteps < (currentSteps ?: 0)
}

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
