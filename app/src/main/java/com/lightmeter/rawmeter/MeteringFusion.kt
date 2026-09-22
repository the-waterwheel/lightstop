package com.lightmeter.rawmeter

import kotlin.math.abs

/**
 * Robustly fuses per-frame measurements into the value shown to the user.
 *
 * Keeping fusion independent of Camera2 makes the adaptive RAW-frame policy easy to test without a
 * device. Median EV/luma reject a transient frame while clipped fraction remains an average.
 *
 * The calibration sample must describe the *same* statistic the user sees. For RAW the
 * before-user-calibration EV is therefore fused with the same median rule as the displayed EV, so
 * `displayEv == sampleBeforeEv + appliedCorrection` holds. A representative frame is only used for
 * scalar metadata (ISO, exposure, aperture), never to replace the fused calibration EV.
 */
internal object MeteringFusion {
    /** Per-frame applied corrections must agree within this tolerance to be one calibration state. */
    private const val APPLIED_CORRECTION_TOLERANCE_EV = 1e-3

    fun fuse(stats: List<MeteringFrameStat>, source: MeteringSource): MeterReading? {
        if (stats.isEmpty()) return null
        val representative = stats[stats.size / 2]
        return MeterReading(
            sceneEv100 = median(stats.map(MeteringFrameStat::ev100).sorted()),
            rawLuma = median(stats.map(MeteringFrameStat::luma).sorted()),
            clippedFraction = stats.map(MeteringFrameStat::clipped).average(),
            frameCount = stats.size,
            captureIso = representative.captureIso,
            exposureTimeNs = representative.exposureTimeNs,
            aperture = representative.aperture,
            source = source,
            calibrationSample = calibrationSample(stats, source),
        )
    }

    private fun calibrationSample(
        stats: List<MeteringFrameStat>,
        source: MeteringSource,
    ): CalibrationMeasurementSample {
        if (source == MeteringSource.RAW) {
            // A single calibration state must apply to every frame; otherwise the fused
            // before-calibration EV cannot be attributed to one correction.
            val appliedCorrections = stats.map(MeteringFrameStat::appliedUserCorrectionEv)
            val applied = appliedCorrections.first()
            val consistent = appliedCorrections.all {
                abs(it - applied) <= APPLIED_CORRECTION_TOLERANCE_EV
            }
            return CalibrationMeasurementSample(
                source = source,
                cameraId = "",
                signature = null,
                ev100BeforeUserCalibration = median(
                    stats.map(MeteringFrameStat::ev100BeforeUserCalibration).sorted(),
                ),
                appliedUserCorrectionEv = if (consistent) applied else Double.NaN,
            )
        }
        // Processed streams are metered from a single frame; keep its own tuple together so the
        // response anchor's luminance and before-calibration EV describe the same frame.
        val representative = stats[stats.size / 2]
        return CalibrationMeasurementSample(
            source = source,
            cameraId = "",
            signature = null,
            ev100BeforeUserCalibration = representative.ev100BeforeUserCalibration,
            appliedUserCorrectionEv = representative.appliedUserCorrectionEv,
            inputLuma = representative.luma.takeIf { it.isFinite() && it > 0.0 },
        )
    }

    private fun median(sorted: List<Double>): Double {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) * 0.5
        } else {
            sorted[middle]
        }
    }
}
