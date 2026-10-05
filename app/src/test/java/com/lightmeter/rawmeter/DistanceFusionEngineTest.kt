package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class DistanceFusionEngineTest {
    private val now = 10_000_000_000L
    private fun estimate(meters: Double, source: DistanceSource = DistanceSource.FOCUS_CALIBRATED,
        confidence: Double = 0.8, error: Double = 0.06) = DistanceEstimate(
        meters, meters*(1-error), meters*(1+error), confidence, DistanceQuality.MEDIUM,
        source, now, "0", NormalizedPoint.CENTER, 8, true, receivedAtNs = now,
    )

    @Test fun conflictUsesDistanceRatioInExposureStopsNotMetres() {
        assertEquals(2.0, DistanceFusionEngine.flashDifferenceEv(1.0, 2.0), 1e-9)
        assertEquals(DistanceFusionEngine.flashDifferenceEv(1.0, 1.2),
            DistanceFusionEngine.flashDifferenceEv(5.0, 6.0), 1e-9)
        assertTrue(DistanceFusionEngine.flashDifferenceEv(1.0, 2.0) >
            DistanceFusionEngine.flashDifferenceEv(9.0, 10.0))
    }

    @Test fun reliableNearFocusReceivesExtraWeightButRangeDoesNotOverrideQuality() {
        assertTrue(DistanceFusionEngine.weight(estimate(4.0)) > DistanceFusionEngine.weight(estimate(6.0)))
        assertTrue(DistanceFusionEngine.weight(estimate(4.0, confidence=0.2, error=0.3)) <
            DistanceFusionEngine.weight(estimate(4.0, DistanceSource.MOTION_PARALLAX)))
    }

    @Test fun agreeingSourcesFuseInInverseDistanceWithoutArtificialPrecision() {
        val result = DistanceFusionEngine.fuse(estimate(2.0),
            estimate(2.1, DistanceSource.MOTION_PARALLAX), now)!!.estimate!!
        assertEquals(DistanceSource.FUSED, result.source)
        assertTrue(result.meters in 2.0..2.1)
        assertTrue(result.lowerMeters!! < 2.0)
        assertTrue(result.upperMeters!! > 2.1)
        assertTrue(result.confidence <= 0.8)
    }

    @Test fun unresolvedExposureConflictCannotDriveFlash() {
        val state = DistanceFusionEngine.fuse(estimate(2.0),
            estimate(4.0, DistanceSource.MOTION_PARALLAX), now)!!
        assertEquals(DistanceMeasurementStatus.STALE, state.status)
        assertNull(state.effectiveMetersForFlash)
    }

    @Test fun strongMotionWinsConflictAgainstWeakFocusEvenInsideFiveMetres() {
        val state = DistanceFusionEngine.fuse(estimate(2.0, confidence=0.25, error=0.25),
            estimate(3.0, DistanceSource.MOTION_PARALLAX), now)!!
        assertEquals(DistanceSource.MOTION_PARALLAX, state.estimate!!.source)
        assertEquals(3.0, state.estimate!!.meters, 0.0)
    }

    @Test fun poorMotionCannotOutvoteReliableFocus() {
        val state = DistanceFusionEngine.fuse(estimate(2.0),
            estimate(5.0, DistanceSource.MOTION_PARALLAX, 0.2, 0.3), now)!!
        assertEquals(DistanceSource.FOCUS_CALIBRATED, state.estimate!!.source)
    }

    @Test fun staleForeignOrTimeMisalignedObservationsNeverFuse() {
        val a = estimate(2.0)
        val b = estimate(2.1, DistanceSource.MOTION_PARALLAX)
        for (invalid in listOf(b.copy(isFresh=false), b.copy(cameraIdentity="2"),
            b.copy(target=NormalizedPoint(0.4f, 0.5f)), b.copy(timestampNs=now-900_000_000L))) {
            assertNotEquals(DistanceSource.FUSED, DistanceFusionEngine.fuse(a, invalid, now)!!.estimate!!.source)
        }
        assertNull(DistanceFusionEngine.fuse(a, b, now+DISTANCE_TTL_NS+1))
    }

    @Test fun fusionDoesNotRenewOldInputLifetime() {
        val state = DistanceFusionEngine.fuse(estimate(2.0).copy(receivedAtNs=now-1_900_000_000L),
            estimate(2.05, DistanceSource.MOTION_PARALLAX), now)!!
        assertFalse(state.estimate!!.isCurrent(now+200_000_000L))
    }

    @Test fun malformedIntervalsAndLowQualityCannotDriveExposure() {
        val good = estimate(2.0).copy(receivedAtNs=0L)
        for (bad in listOf(good.copy(lowerMeters=null), good.copy(upperMeters=1.0),
            good.copy(confidence=Double.NaN), good.copy(meters=Double.NaN),
            good.copy(quality=DistanceQuality.LOW), good.copy(lowerMeters=0.1))) {
            assertFalse(DistanceFusionEngine.usableForFlash(bad))
        }
        assertNull(DistanceMeasurementState(good, DistanceMeasurementStatus.SAMPLING).effectiveMetersForFlash)
        assertEquals(2.0, DistanceMeasurementState(good, DistanceMeasurementStatus.AVAILABLE).effectiveMetersForFlash!!, 0.0)
    }

    @Test fun focusPreferenceChangesSmoothlyAcrossFiveMetres() {
        assertEquals(DistanceFusionEngine.weight(estimate(4.999)),
            DistanceFusionEngine.weight(estimate(5.001)), 0.02)
    }

    @Test fun invalidExposureComparisonCannotSilentlyBecomeAgreement() {
        val result = DistanceFusionEngine.fuse(estimate(2.0),
            estimate(2.1, DistanceSource.MOTION_PARALLAX), now) { _, _ -> Double.NaN }!!
        assertEquals(DistanceMeasurementStatus.STALE, result.status)
    }

    private fun heuristic() = estimate(2.0, DistanceSource.FOCUS_ESTIMATED, confidence = 0.25)
        .copy(lowerMeters = null, upperMeters = null, quality = DistanceQuality.LOW)

    @Test fun heuristicSurvivesRefreshWithoutPretendingToHaveMetricUncertainty() {
        val guess = heuristic()
        val state = DistanceFusionEngine.fuse(guess, null, now + 250_000_000L)!!
        assertEquals(DistanceMeasurementStatus.AVAILABLE, state.status)
        assertEquals(guess, state.estimate)
        assertEquals(0.0, DistanceFusionEngine.weight(guess), 0.0)
        assertTrue(DistanceFusionEngine.usableForEstimatedFlash(guess))
        assertNull(DistanceFusionEngine.fuse(guess, null, now + DISTANCE_TTL_NS + 1))
    }

    @Test fun realMetricObservationAlwaysReplacesUnverifiedHeuristicWithoutFusingIt() {
        val motion = estimate(4.0, DistanceSource.MOTION_PARALLAX)
        val state = DistanceFusionEngine.fuse(heuristic(), motion, now)!!
        assertEquals(motion, state.estimate)
        assertEquals(DistanceSource.MOTION_PARALLAX, state.estimate!!.source)
    }

    @Test fun approximateMetricDistanceUsesNumericErrorRatherThanLowLabelAlone() {
        val approximate = estimate(2.0, DistanceSource.FOCUS_APPROXIMATE, 0.30, 0.20)
            .copy(quality = DistanceQuality.LOW)
        assertTrue(DistanceFusionEngine.usableForFlash(approximate))
        assertFalse(DistanceFusionEngine.usableForFlash(approximate.copy(lowerMeters = 0.5)))
        assertFalse(DistanceFusionEngine.usableForFlash(approximate.copy(confidence = 0.01)))
    }

    @Test fun lowPrecisionFocusRemainsUsableAsAnExplicitApproximation() {
        val coarse = estimate(10.0, confidence = 0.10, error = 0.4).copy(quality = DistanceQuality.LOW)
        assertFalse(DistanceFusionEngine.usableForFlash(coarse))
        assertTrue(DistanceFusionEngine.usableForEstimatedFlash(coarse))
        assertTrue(coarse.isApproximate)
        assertTrue(DistanceFusionEngine.usableForEstimatedFlash(coarse.copy(upperMeters = null)))
        for (bad in listOf(coarse.copy(lowerMeters = null), coarse.copy(lowerMeters = -1.0),
            coarse.copy(upperMeters = 1.0), coarse.copy(sampleCount = 1),
            coarse.copy(confidence = Double.NaN))) {
            assertFalse(DistanceFusionEngine.usableForEstimatedFlash(bad))
        }
        assertFalse(DistanceFusionEngine.usableForEstimatedFlash(coarse.copy(source = DistanceSource.MOTION_PARALLAX)))
    }
}
