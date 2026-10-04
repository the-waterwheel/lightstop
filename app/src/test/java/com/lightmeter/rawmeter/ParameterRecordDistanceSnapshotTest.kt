package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterRecordDistanceSnapshotTest {
    @Test
    fun availableAutoDistanceIsFrozenWithItsSourceAndQuality() {
        val snapshot = ParameterRecordCaptureSnapshot.distance(
            DistanceMeasurementState(
                estimate = DistanceEstimate(
                    meters = 2.43,
                    lowerMeters = 2.20,
                    upperMeters = 2.71,
                    confidence = 0.82,
                    quality = DistanceQuality.MEDIUM,
                    source = DistanceSource.FOCUS_APPROXIMATE,
                    timestampNs = 99L,
                    cameraIdentity = "0",
                    target = NormalizedPoint.CENTER,
                    sampleCount = 8,
                    isFresh = true,
                ),
                status = DistanceMeasurementStatus.AVAILABLE,
            ),
        )

        requireNotNull(snapshot)
        assertEquals(2.43, snapshot.meters!!, 0.0)
        assertEquals(DistanceSource.FOCUS_APPROXIMATE, snapshot.source)
        assertEquals(DistanceQuality.MEDIUM, snapshot.quality)
        assertTrue(snapshot.isFreshAtCapture)
        assertNull(snapshot.ageMsAtCapture)
    }

    @Test
    fun staleDistanceIsDiagnosticOnlyAndDoesNotPretendToBeFresh() {
        val snapshot = ParameterRecordCaptureSnapshot.distance(
            DistanceMeasurementState(
                estimate = DistanceEstimate(
                    meters = 3.0,
                    lowerMeters = null,
                    upperMeters = null,
                    confidence = 0.2,
                    quality = DistanceQuality.LOW,
                    source = DistanceSource.FOCUS_APPROXIMATE,
                    timestampNs = 101L,
                    cameraIdentity = "0",
                    target = NormalizedPoint.CENTER,
                    sampleCount = 5,
                    isFresh = false,
                ),
                status = DistanceMeasurementStatus.STALE,
            ),
        )

        requireNotNull(snapshot)
        assertEquals(DistanceMeasurementStatus.STALE, snapshot.status)
        assertFalse(snapshot.isFreshAtCapture)
    }

    @Test
    fun idleDistanceStateDoesNotAddARecordField() {
        assertNull(ParameterRecordCaptureSnapshot.distance(DistanceMeasurementState()))
    }
}
