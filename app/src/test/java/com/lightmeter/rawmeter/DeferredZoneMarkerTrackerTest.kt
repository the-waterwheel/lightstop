package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredZoneMarkerTrackerTest {
    @Test
    fun layoutUpdatesDoNotCreateOpenCvDelegate() {
        var creations = 0
        val fake = FakeZoneMarkerTracker()
        val deferred = DeferredZoneMarkerTracker {
            creations += 1
            fake
        }

        deferred.setDisplayZoom(2f)
        deferred.setVisibleViewport(0.1f, 0.2f, 0.9f, 0.8f)

        assertEquals(0, creations)
        assertFalse(deferred.tryReserveFrame())

        deferred.start(emptyList())
        assertEquals(1, creations)
        assertEquals(2f, fake.zoom)
        assertEquals(listOf(0.1f, 0.2f, 0.9f, 0.8f), fake.viewport)
        assertTrue(fake.started)

        deferred.stop()
        deferred.start(emptyList())
        assertEquals(1, creations)
    }

    private class FakeZoneMarkerTracker : ZoneMarkerTracker {
        var started = false
        var zoom = 1f
        var viewport = emptyList<Float>()

        override fun start(markers: List<ZoneMarker>) {
            started = true
        }

        override fun stop() {
            started = false
        }

        override fun release() = Unit
        override fun addMarker(id: Int, normalizedX: Float, normalizedY: Float) = Unit
        override fun removeMarker(id: Int) = Unit
        override fun clearMarkers() = Unit
        override fun onMeteringStateChanged(active: Boolean) = Unit
        override fun resetMarker(id: Int, normalizedX: Float, normalizedY: Float) = Unit
        override fun reanchor(markers: List<ZoneMarker>) = Unit

        override fun setDisplayZoom(zoom: Float) {
            this.zoom = zoom
        }

        override fun setVisibleViewport(left: Float, top: Float, right: Float, bottom: Float) {
            viewport = listOf(left, top, right, bottom)
        }

        override fun tryReserveFrame(): Boolean = started
        override fun cancelFrameReservation() = Unit
        override fun offerFrame(frame: ZoneTrackingFrame) = frame.close()
    }
}
