package com.lightmeter.rawmeter

/**
 * Safe Zone-mode fallback used when the optional OpenCV runtime cannot be loaded.
 *
 * Metering remains available, but marker positions stay fixed and are reported as uncertain so
 * the UI never implies that motion tracking is active.
 */
internal class StaticZoneMarkerTracker(
    private val callback: (Int, Float, Float, ZoneTrackingState) -> Unit,
) : ZoneMarkerTracker {
    private val markers = linkedMapOf<Int, Pair<Float, Float>>()

    override fun start(markers: List<ZoneMarker>) {
        this.markers.clear()
        markers.forEach { marker -> update(marker.id, marker.normalizedX, marker.normalizedY) }
    }

    override fun stop() = Unit

    override fun release() {
        markers.clear()
    }

    override fun addMarker(id: Int, normalizedX: Float, normalizedY: Float) {
        update(id, normalizedX, normalizedY)
    }

    override fun removeMarker(id: Int) {
        markers.remove(id)
    }

    override fun clearMarkers() {
        markers.clear()
    }

    override fun onMeteringStateChanged(active: Boolean) = Unit

    override fun resumeVisualTrackingAfterMetering() = Unit

    override fun resetMarker(id: Int, normalizedX: Float, normalizedY: Float) {
        update(id, normalizedX, normalizedY)
    }

    override fun reanchor(markers: List<ZoneMarker>) = start(markers)

    override fun setDisplayZoom(zoom: Float) = Unit

    override fun setVisibleViewport(left: Float, top: Float, right: Float, bottom: Float) = Unit

    override fun tryReserveFrame(): Boolean = false

    override fun cancelFrameReservation() = Unit

    override fun offerFrame(frame: ZoneTrackingFrame) {
        frame.close()
    }

    private fun update(id: Int, x: Float, y: Float) {
        val normalizedX = x.coerceIn(0f, 1f)
        val normalizedY = y.coerceIn(0f, 1f)
        markers[id] = normalizedX to normalizedY
        callback(id, normalizedX, normalizedY, ZoneTrackingState.UNCERTAIN)
    }
}
