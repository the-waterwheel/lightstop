package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log2

class FlashExposureMathTest {
    @Test
    fun effectiveGuideNumberIncludesIsoPowerAndLoss() {
        val configuration = FlashConfiguration(
            guideNumber = 100.0,
            iso = 400,
            powerDenominator = 2,
            lossStops = 1.0,
            distanceMeters = 10.0,
        )

        assertEquals(100.0, FlashExposureMath.effectiveGuideNumber(configuration, 400), 1e-9)
    }

    @Test
    fun lockedShutterAddsAmbientAndFlashBeforeConvertingToStops() {
        val result = FlashExposureMath.adjustment(
            configuration = FlashConfiguration(guideNumber = 100.0, distanceMeters = 10.0),
            autofocusDistanceMeters = null,
            meteringIso = 100,
            ambientEv100 = log2(100.0),
            lockMode = ExposureLockMode.SHUTTER,
            lockedApertureStop = 0.0,
            lockedShutterLogSeconds = 0.0,
        )

        assertEquals(1.0, result.compensationStops, 1e-9)
        assertEquals(FlashAdjustmentStatus.APPLIED, result.status)
    }

    @Test
    fun lockedApertureLeavesOnlyUnfilledExposureForAmbient() {
        val result = FlashExposureMath.adjustment(
            configuration = FlashConfiguration(guideNumber = 50.0, distanceMeters = 10.0),
            autofocusDistanceMeters = null,
            meteringIso = 100,
            ambientEv100 = 10.0,
            lockMode = ExposureLockMode.APERTURE,
            lockedApertureStop = 2.0 * log2(10.0),
            lockedShutterLogSeconds = 0.0,
        )

        assertEquals(-log2(0.75), result.compensationStops, 1e-9)
        assertEquals(FlashAdjustmentStatus.APPLIED, result.status)
    }

    @Test
    fun autoDistanceRequiresCameraFocusMetadata() {
        val result = FlashExposureMath.adjustment(
            configuration = FlashConfiguration(distanceMeters = null),
            autofocusDistanceMeters = null,
            meteringIso = 100,
            ambientEv100 = 10.0,
            lockMode = ExposureLockMode.SHUTTER,
            lockedApertureStop = 0.0,
            lockedShutterLogSeconds = 0.0,
        )

        assertEquals(FlashAdjustmentStatus.DISTANCE_UNAVAILABLE, result.status)
        assertEquals(0.0, result.compensationStops, 0.0)
    }

    @Test
    fun flashThatAlreadyFillsLockedApertureIsFlagged() {
        val result = FlashExposureMath.adjustment(
            configuration = FlashConfiguration(guideNumber = 100.0, distanceMeters = 10.0),
            autofocusDistanceMeters = null,
            meteringIso = 100,
            ambientEv100 = 10.0,
            lockMode = ExposureLockMode.APERTURE,
            lockedApertureStop = 2.0 * log2(10.0),
            lockedShutterLogSeconds = 0.0,
        )

        assertEquals(FlashAdjustmentStatus.FLASH_DOMINATES, result.status)
        assertEquals(0.0, result.compensationStops, 0.0)
    }

    @Test
    fun infinityFocusContributesNoFlashInsteadOfBlockingTheMeter() {
        val result = FlashExposureMath.adjustment(
            configuration = FlashConfiguration(distanceMeters = null),
            autofocusDistanceMeters = Double.POSITIVE_INFINITY,
            meteringIso = 100,
            ambientEv100 = 10.0,
            lockMode = ExposureLockMode.SHUTTER,
            lockedApertureStop = 0.0,
            lockedShutterLogSeconds = 0.0,
        )

        assertEquals(FlashAdjustmentStatus.APPLIED, result.status)
        assertEquals(0.0, result.compensationStops, 0.0)
    }

    @Test
    fun distanceDetentsAreFineNearbyAndCoarserFarAway() {
        val values = FlashDistanceScale.meters.filterNotNull()

        assertTrue(values.zipWithNext().all { (left, right) -> right > left })
        assertTrue(values[1] - values[0] < values.last() - values[values.lastIndex - 1])
    }

    @Test
    fun exposureCompensationScalesAmbientAndFlashToTheSameTargetDose() {
        fun result(compensation: Double, ambient: Double) = FlashExposureMath.adjustment(
            configuration = FlashConfiguration(guideNumber = 100.0, distanceMeters = 10.0),
            autofocusDistanceMeters = null,
            meteringIso = 100,
            ambientEv100 = ambient,
            exposureCompensationEv = compensation,
            lockMode = ExposureLockMode.SHUTTER,
            lockedApertureStop = 0.0,
            lockedShutterLogSeconds = 0.0,
        )

        assertEquals(
            result(0.0, log2(100.0)).compensationStops,
            result(1.0, log2(100.0) - 1.0).compensationStops,
            1e-9,
        )
    }
}
