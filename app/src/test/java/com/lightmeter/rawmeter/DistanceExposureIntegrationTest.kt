package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class DistanceExposureIntegrationTest {
    private fun estimate(meters: Double, source: DistanceSource) = DistanceEstimate(
        meters, meters*0.97, meters*1.03, 0.8, DistanceQuality.MEDIUM,
        source, 1_000L, "0", NormalizedPoint.CENTER, 10, true,
    )

    private fun state() = DistanceMeasurementState(
        status = DistanceMeasurementStatus.STALE,
        focusObservation = estimate(2.0, DistanceSource.FOCUS_CALIBRATED),
        motionObservation = estimate(2.3, DistanceSource.MOTION_PARALLAX),
    )

    private fun calculate(state: DistanceMeasurementState, ambient: Double, guide: Double = 10.0,
        mode: ExposureLockMode = ExposureLockMode.SHUTTER, manual: Double? = null,
        apertureStop: Double = 8.0) = FlashExposureMath.adjustment(
        FlashConfiguration(guideNumber=guide, distanceMeters=manual), null, 100, ambient,
        lockMode=mode, lockedApertureStop=apertureStop, lockedShutterLogSeconds=0.0,
        distanceMeasurementState=state,
    )

    @Test fun ambientDominatedExposureCanAcceptDisagreementThatMattersForFlashOnly() {
        assertTrue(DistanceFusionEngine.flashDifferenceEv(2.0, 2.3) > DistanceFusionEngine.CONFLICT_EV)
        val result = calculate(state(), ambient=14.0)
        assertEquals(FlashAdjustmentStatus.APPLIED, result.status)
        assertTrue(result.effectiveDistanceMeters!! in 2.0..2.3)
    }

    @Test fun changingOnlyAmbientReevaluatesSameObservationsAndRejectsFlashDominatedConflict() {
        val observations = state()
        assertEquals(FlashAdjustmentStatus.APPLIED, calculate(observations, 14.0).status)
        assertEquals(FlashAdjustmentStatus.DISTANCE_UNAVAILABLE, calculate(observations, -10.0).status)
        assertEquals(FlashAdjustmentStatus.APPLIED, calculate(observations, 14.0).status)
    }

    @Test fun lockedApertureChecksRemainingAmbientNotOnlyFlashDoseRatio() {
        val flashOnly = DistanceFusionEngine.flashDifferenceEv(2.0, 2.1)
        val actual = FlashExposureMath.distanceExposureDifference(2.0, 2.1, 3.9, 100, 10.0,
            ExposureLockMode.APERTURE, 2.0, 0.0)
        assertTrue(flashOnly < DistanceFusionEngine.CONFLICT_EV)
        assertTrue(actual > 1.0)
    }

    @Test fun crossingFlashDominanceBoundaryIsAnUnboundedConflict() {
        assertEquals(Double.POSITIVE_INFINITY,
            FlashExposureMath.distanceExposureDifference(1.9, 2.1, 4.0, 100, 10.0,
                ExposureLockMode.APERTURE, 2.0, 0.0), 0.0)
    }

    @Test fun uncertaintyIntervalMustNotCrossLockedApertureSingularity() {
        val observation = estimate(2.0, DistanceSource.FOCUS_CALIBRATED).copy(lowerMeters=1.8, upperMeters=2.2)
        val state = DistanceMeasurementState(observation, DistanceMeasurementStatus.AVAILABLE)
        assertTrue(DistanceFusionEngine.usableForFlash(observation))
        assertEquals(FlashAdjustmentStatus.DISTANCE_UNAVAILABLE,
            calculate(state, 10.0, 3.9, ExposureLockMode.APERTURE, apertureStop=2.0).status)
    }

    @Test fun manualDistanceBypassesAutomaticConflictWithoutChangingExistingFormula() {
        val result = calculate(state(), -10.0, manual=3.0)
        assertEquals(FlashAdjustmentStatus.APPLIED, result.status)
        assertEquals(3.0, result.effectiveDistanceMeters!!, 0.0)
    }

    @Test fun flashCannotReviveExpiredObservationsOrUseMissingUncertainty() {
        val old = state().copy(
            focusObservation=state().focusObservation!!.copy(receivedAtNs=1L),
            motionObservation=state().motionObservation!!.copy(receivedAtNs=1L),
        )
        assertEquals(FlashAdjustmentStatus.DISTANCE_UNAVAILABLE, calculate(old, 14.0).status)
        val incomplete = DistanceMeasurementState(estimate(2.0, DistanceSource.FOCUS_CALIBRATED)
            .copy(lowerMeters=null), DistanceMeasurementStatus.AVAILABLE)
        assertEquals(FlashAdjustmentStatus.DISTANCE_UNAVAILABLE, calculate(incomplete, 14.0).status)
    }

    @Test fun explicitBestEffortSourceCanDriveEstimatedFlashWithoutInventingAnErrorInterval() {
        val guess = estimate(2.0, DistanceSource.FOCUS_ESTIMATED).copy(
            lowerMeters = null, upperMeters = null, confidence = 0.25, quality = DistanceQuality.LOW)
        val state = DistanceMeasurementState(guess, DistanceMeasurementStatus.AVAILABLE,
            focusObservation = guess)
        val result = calculate(state, 10.0)
        assertEquals(FlashAdjustmentStatus.APPLIED, result.status)
        assertEquals(2.0, result.effectiveDistanceMeters!!, 0.0)
        assertTrue(result.isEstimatedDistance)
        assertEquals(FlashAdjustmentStatus.DISTANCE_UNAVAILABLE,
            calculate(state.copy(estimate = guess.copy(receivedAtNs = 1L),
                focusObservation = guess.copy(receivedAtNs = 1L)), 10.0).status)
    }

    @Test fun legacyFocusWithLowConfidenceStillProducesMarkedExposureEstimate() {
        val focus = estimate(10.0, DistanceSource.FOCUS_CALIBRATED)
            .copy(confidence = 0.10, quality = DistanceQuality.LOW, lowerMeters = 6.0, upperMeters = null)
        val result = calculate(DistanceMeasurementState(focus, DistanceMeasurementStatus.AVAILABLE,
            focusObservation = focus), 10.0)
        assertEquals(FlashAdjustmentStatus.APPLIED, result.status)
        assertEquals(10.0, result.effectiveDistanceMeters!!, 0.0)
        assertTrue(result.isEstimatedDistance)
    }
}
