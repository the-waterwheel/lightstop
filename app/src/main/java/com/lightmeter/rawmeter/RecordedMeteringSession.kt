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

    fun snapped(anchor: RecordedMeteringTarget, state: MeterState): RecordedMeteringSession {
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
            // A Normal capture remains immutable, but once RAW review points exist the useful
            // default presentation is Zone so those points and its gray placement rail are visible.
            mode = if (record.mode == ParameterRecordMode.ZONE || record.zonePoints.isNotEmpty()) {
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
