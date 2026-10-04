package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ZoneMeterSessionTest {
    @Test
    fun cancellingRemeasureKeepsExistingMarkerAndReading() {
        val session = measuredSession()
        val marker = session.markers.single()
        val originalEv = marker.ev100

        assertSame(marker, session.beginRemeasure(marker.id, 100))
        assertNull(session.cancelPending())

        assertSame(marker, session.markers.single())
        assertEquals(originalEv, marker.ev100)
        assertNull(session.pendingMarkerId)
    }

    @Test
    fun completingRemeasureUpdatesExistingMarkerWithoutChangingIdentity() {
        val session = measuredSession()
        val marker = session.markers.single()

        assertSame(marker, session.beginRemeasure(marker.id, 100))
        assertSame(marker, session.completePending(reading(12.25), 100, ExposureLockMode.APERTURE))

        assertSame(marker, session.markers.single())
        assertEquals(12.25, marker.ev100!!, 0.0001)
        assertNull(session.pendingMarkerId)
    }

    @Test
    fun lostMarkerCannotStartRemeasurement() {
        val session = measuredSession()
        val marker = session.markers.single()
        marker.trackingState = ZoneTrackingState.LOST

        assertNull(session.beginRemeasure(marker.id, 100))
        assertNull(session.pendingMarkerId)
    }

    @Test
    fun batchRemeasurementUpdatesSeveralMarkersWithoutChangingTheirIdentity() {
        val session = measuredSession()
        val first = session.markers.single()
        val second = session.beginMarker(100, 0.7f, 0.4f)!!
        session.completePending(reading(11.0), 100, ExposureLockMode.APERTURE)

        val updated = session.completeRemeasurements(
            listOf(
                ZoneMeteringResult(first.id, reading(12.0)),
                ZoneMeteringResult(second.id, reading(13.0)),
            ),
            100,
            ExposureLockMode.APERTURE,
        )

        assertEquals(listOf(first, second), updated)
        assertSame(first, session.markers[0])
        assertSame(second, session.markers[1])
        assertEquals(12.0, first.ev100!!, 0.0001)
        assertEquals(13.0, second.ev100!!, 0.0001)
        assertNull(session.pendingMarkerId)
    }

    private fun measuredSession(): ZoneMeterSession = ZoneMeterSession().also { session ->
        session.beginMarker(100, 0.3f, 0.6f)
        session.completePending(reading(10.0), 100, ExposureLockMode.APERTURE)
    }

    private fun reading(ev100: Double) = MeterReading(
        sceneEv100 = ev100,
        rawLuma = 0.2,
        clippedFraction = 0.0,
        frameCount = 1,
        captureIso = 100,
        exposureTimeNs = 8_000_000L,
        aperture = 5.6f,
    )
}
