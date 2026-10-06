package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class DistanceImuHistoryTest {
    private fun sample(ns: Long, acceleration: Double = 0.0, rotation: Double = 0.0) =
        DistanceImuSample(ns, DistanceVector(acceleration, 0.0, 0.0), DistanceVector(0.0, rotation, 0.0))

    @Test fun interpolatesExactImageEndpointsAndDoesNotExtrapolate() {
        val history = DistanceImuHistory()
        history.add(sample(100_000_000L, 0.0))
        history.add(sample(120_000_000L, 2.0))
        val range = history.between(105_000_000L, 115_000_000L)
        assertEquals(2, range.size)
        assertEquals(0.5, range.first().acceleration.x, 1e-9)
        assertEquals(1.5, range.last().acceleration.x, 1e-9)
        assertTrue(history.between(90_000_000L, 115_000_000L).isEmpty())
        assertTrue(history.between(105_000_000L, 130_000_000L).isEmpty())
    }

    @Test fun sensorGapsCannotPretendToBeStationaryOrInterpolatedMotion() {
        val history = DistanceImuHistory()
        (0..8).forEach { history.add(sample(100_000_000L + it*10_000_000L)) }
        history.add(sample(270_000_000L))
        assertFalse(history.quietAt(270_000_000L))
        assertTrue(history.between(200_000_000L, 260_000_000L).isEmpty())
    }

    @Test fun boundedHistoryRejectsDuplicatesAndClearDropsPreviousSession() {
        val history = DistanceImuHistory()
        (1..500).forEach { history.add(sample(it*10_000_000L)) }
        history.add(sample(4_999_000_000L, 100.0))
        history.add(sample(5_000_000_000L, 100.0))
        assertTrue(history.between(10_000_000L, 50_000_000L).isEmpty())
        assertTrue(history.quietAt(5_000_000_000L))
        history.clear()
        assertFalse(history.quietAt(5_000_000_000L))
    }

    @Test fun motionPenalizesFocusAndMissingOrOldSensorsNeverInventEvidence() {
        val history = DistanceImuHistory()
        assertEquals(1.0, history.focusReliability(100L), 0.0)
        history.add(sample(1_000_000_000L, rotation=0.3))
        assertTrue(history.focusReliability(1_010_000_000L) < 0.3)
        assertEquals(1.0, history.focusReliability(2_000_000_000L), 0.0)
    }
}
