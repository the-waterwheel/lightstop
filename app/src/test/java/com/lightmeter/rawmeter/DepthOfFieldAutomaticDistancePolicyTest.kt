package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepthOfFieldAutomaticDistancePolicyTest {
    @Test
    fun acceptsOnlyFreshAvailableCenterEstimate() {
        val estimate = estimate()

        assertEquals(
            estimate,
            DepthOfFieldAutomaticDistancePolicy.usableEstimate(
                DistanceMeasurementState(estimate, DistanceMeasurementStatus.AVAILABLE),
            ),
        )
        assertNull(
            DepthOfFieldAutomaticDistancePolicy.usableEstimate(
                DistanceMeasurementState(estimate, DistanceMeasurementStatus.SAMPLING),
            ),
        )
        assertNull(
            DepthOfFieldAutomaticDistancePolicy.usableEstimate(
                DistanceMeasurementState(
                    estimate.copy(isFresh = false),
                    DistanceMeasurementStatus.AVAILABLE,
                ),
            ),
        )
        assertNull(
            DepthOfFieldAutomaticDistancePolicy.usableEstimate(
                DistanceMeasurementState(
                    estimate.copy(target = NormalizedPoint(0.4f, 0.5f)),
                    DistanceMeasurementStatus.AVAILABLE,
                ),
            ),
        )
    }

    private fun estimate() = DistanceEstimate(
        meters = 2.5,
        lowerMeters = 2.3,
        upperMeters = 2.7,
        confidence = 0.8,
        quality = DistanceQuality.HIGH,
        source = DistanceSource.FOCUS_CALIBRATED,
        timestampNs = 123L,
        cameraIdentity = "0",
        target = NormalizedPoint.CENTER,
        sampleCount = 5,
        isFresh = true,
    )
}
