package com.lightmeter.rawmeter

import kotlin.math.max
import kotlin.math.min

/**
 * Temporary exposure pairing used while reviewing a capture.
 *
 * A record is evidence of the settings used for the photograph and must never be rewritten by a
 * review gesture. This separate value can therefore be dragged, snapped, and switched between
 * Normal and Zone without changing [ParameterRecordEntry].
 */
internal data class RecordedMeteringSession(
    val mode: ParameterRecordMode,
    val apertureCoordinate: Double,
    val shutterCoordinate: Double,
) {
    fun shifted(stops: Double, state: MeterState): RecordedMeteringSession {
        if (!stops.isFinite() || kotlin.math.abs(stops) < 0.000_001) return this
        val apertureTicks = ExposureMath.apertureTicks(state.apertureStep)
        val shutterTicks = ExposureMath.shutterTicks(state.shutterStep)
        val minimumShift = max(
            (apertureTicks.minOfOrNull { it.coordinate } ?: apertureCoordinate) - apertureCoordinate,
            (shutterTicks.minOfOrNull { it.coordinate } ?: shutterCoordinate) - shutterCoordinate,
        )
        val maximumShift = min(
            (apertureTicks.maxOfOrNull { it.coordinate } ?: apertureCoordinate) - apertureCoordinate,
            ExposureMath.maxShutterLogSeconds - shutterCoordinate,
        )
        val accepted = stops.coerceIn(minimumShift, maximumShift)
        return copy(
            apertureCoordinate = apertureCoordinate + accepted,
            shutterCoordinate = shutterCoordinate + accepted,
        )
    }

    /** Moves point placement through exposure EV while keeping the selected parameter locked. */
    fun exposureShifted(
        stops: Double,
        lockMode: ExposureLockMode,
        apertureStep: ExposureStep,
        shutterStep: ExposureStep,
    ): RecordedMeteringSession {
        if (!stops.isFinite() || kotlin.math.abs(stops) < 0.000_001) return this
        return if (lockMode == ExposureLockMode.APERTURE) {
            val shutterTicks = ExposureMath.shutterTicks(shutterStep)
            val minimum = shutterTicks.minOfOrNull { it.coordinate } ?: shutterCoordinate
            val maximum = max(
                shutterTicks.maxOfOrNull { it.coordinate } ?: shutterCoordinate,
                ExposureMath.maxShutterLogSeconds,
            )
            copy(shutterCoordinate = (shutterCoordinate - stops).coerceIn(minimum, maximum))
        } else {
            val apertureTicks = ExposureMath.apertureTicks(apertureStep)
            val minimum = apertureTicks.minOfOrNull { it.coordinate } ?: apertureCoordinate
            val maximum = apertureTicks.maxOfOrNull { it.coordinate } ?: apertureCoordinate
            copy(apertureCoordinate = (apertureCoordinate + stops).coerceIn(minimum, maximum))
        }
    }

    fun exposureShifted(stops: Double, state: MeterState): RecordedMeteringSession =
        exposureShifted(stops, state.exposureLockMode, state.apertureStep, state.shutterStep)

    fun snapped(anchor: RecordedMeteringTarget, state: MeterState): RecordedMeteringSession {
        if (anchor == RecordedMeteringTarget.ZONE_RAIL) {
            val exposureShift = if (state.exposureLockMode == ExposureLockMode.APERTURE) {
                shutterCoordinate - ExposureMath.nearestShutterLogSeconds(
                    shutterCoordinate,
                    state.shutterStep,
                )
            } else {
                ExposureMath.nearestApertureStop(apertureCoordinate, state.apertureStep) -
                    apertureCoordinate
            }
            return exposureShifted(exposureShift, state)
        }
        val delta = when (anchor) {
            RecordedMeteringTarget.SHUTTER ->
                ExposureMath.nearestShutterLogSeconds(shutterCoordinate, state.shutterStep) - shutterCoordinate
            else ->
                ExposureMath.nearestApertureStop(apertureCoordinate, state.apertureStep) - apertureCoordinate
        }
        return shifted(delta, state)
    }

    companion object {
        fun from(record: ParameterRecordEntry): RecordedMeteringSession = RecordedMeteringSession(
            // Replaying Zone placement requires the compact RAW grid. Old records can retain
            // saved point evidence without offering an interaction that cannot recalculate it.
            mode = if (RecordedHistoryCapability.canRecalculateZone(record) &&
                (record.mode == ParameterRecordMode.ZONE || record.zonePoints.isNotEmpty())
            ) {
                ParameterRecordMode.ZONE
            } else {
                ParameterRecordMode.NORMAL
            },
            apertureCoordinate = record.apertureCoordinate,
            shutterCoordinate = record.shutterCoordinate,
        )
    }
}

internal enum class RecordedMeteringTarget {
    NORMAL_MODE,
    ZONE_MODE,
    APERTURE,
    SHUTTER,
    ZONE_RAIL,
    NONE,
}
