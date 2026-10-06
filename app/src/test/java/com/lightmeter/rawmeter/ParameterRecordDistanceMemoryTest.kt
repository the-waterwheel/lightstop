package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class ParameterRecordDistanceMemoryTest {
    @Test fun recordingKeepsMeteredDistanceEvenWhenPreviewFocusMovesOrExpires() {
        val memory = ParameterRecordDistanceMemory()
        memory.observe(observation(2.43))
        memory.freezeAtMetering()
        memory.observe(observation(7.0))
        memory.observe(DistanceMeasurementState(status = DistanceMeasurementStatus.STALE))
        assertEquals(2.43, memory.forRecording(null)!!.meters!!, 0.0)
        memory.freezeAtMetering()
        assertEquals(7.0, memory.forRecording(null)!!.meters!!, 0.0)
    }

    @Test fun retainedDistanceSurvivesUnavailableStatesWithoutMakingFlashAvailable() {
        val memory = ParameterRecordDistanceMemory()
        memory.observe(observation(3.0))
        memory.observe(DistanceMeasurementState(status = DistanceMeasurementStatus.UNSUPPORTED))
        assertEquals(3.0, memory.forRecording(null)!!.meters!!, 0.0)
        val stale = observation(3.0).copy(status = DistanceMeasurementStatus.STALE,
            estimate = observation(3.0).estimate!!.copy(isFresh = false))
        memory.observe(stale)
        assertEquals(3.0, memory.forRecording(null)!!.meters!!, 0.0)
        assertFalse(memory.forRecording(null)!!.isFreshAtCapture)
        assertNull(stale.effectiveMetersForFlash)
    }

    @Test fun recentSavedDistanceIsFallbackUntilANewObservationArrives() {
        val saved = ParameterRecordCaptureSnapshot.distance(observation(5.0))!!
            .copy(status = DistanceMeasurementStatus.STALE, isFreshAtCapture = false)
        val memory = ParameterRecordDistanceMemory()
        assertEquals(saved, memory.forRecording(saved))
        memory.observe(observation(1.5))
        assertEquals(1.5, memory.forRecording(saved)!!.meters!!, 0.0)
    }

    @Test fun missingAndInvalidDistancesAreNotRecorded() {
        val memory = ParameterRecordDistanceMemory()
        assertNull(memory.forRecording(null))
        for (meters in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            memory.observe(observation(meters))
            memory.freezeAtMetering()
            val saved = ParameterRecordCaptureSnapshot.distance(observation(meters))
            assertNull(memory.forRecording(saved))
        }
    }

    private fun observation(meters: Double) = DistanceMeasurementState(
        DistanceEstimate(meters, null, null, 0.7, DistanceQuality.MEDIUM,
            DistanceSource.FOCUS_APPROXIMATE, 1L, "0@2", NormalizedPoint.CENTER, 5, true),
        DistanceMeasurementStatus.AVAILABLE,
    )
}
