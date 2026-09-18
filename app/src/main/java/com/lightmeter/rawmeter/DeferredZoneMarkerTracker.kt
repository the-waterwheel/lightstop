package com.lightmeter.rawmeter

/**
 * Lazily creates the native OpenCV tracker on the first committed Zone entry.
 *
 * Layout may publish viewport/zoom state while the app is still in Normal mode. Those cheap values
 * are cached here, but OpenCVLoader, native Mats, the executor and sensor listener do not exist
 * until [start] is called.
 */
internal class DeferredZoneMarkerTracker(
    private val createDelegate: () -> ZoneMarkerTracker,
) : ZoneMarkerTracker {
    @Volatile
    private var delegate: ZoneMarkerTracker? = null
    private var released = false
    private var displayZoom = 1f
    private var viewport = floatArrayOf(0f, 0f, 1f, 1f)

    @Synchronized
    private fun getOrCreate(): ZoneMarkerTracker {
        check(!released) { "Zone tracker has already been released" }
        delegate?.let { return it }
        return createDelegate().also { created ->
            created.setDisplayZoom(displayZoom)
            created.setVisibleViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            delegate = created
        }
    }

    override fun start(markers: List<ZoneMarker>) = getOrCreate().start(markers)

    override fun stop() {
        delegate?.stop()
    }

    @Synchronized
    override fun release() {
        if (released) return
        released = true
        delegate?.release()
        delegate = null
    }

    override fun addMarker(id: Int, normalizedX: Float, normalizedY: Float) {
        delegate?.addMarker(id, normalizedX, normalizedY)
    }

    override fun removeMarker(id: Int) {
        delegate?.removeMarker(id)
    }

    override fun clearMarkers() {
        delegate?.clearMarkers()
    }

    override fun onMeteringStateChanged(active: Boolean) {
        delegate?.onMeteringStateChanged(active)
    }

    override fun resumeVisualTrackingAfterMetering() {
        delegate?.resumeVisualTrackingAfterMetering()
    }

    override fun resetMarker(id: Int, normalizedX: Float, normalizedY: Float) {
        delegate?.resetMarker(id, normalizedX, normalizedY)
    }

    override fun reanchor(markers: List<ZoneMarker>) {
        delegate?.reanchor(markers)
    }

    override fun setDisplayZoom(zoom: Float) {
        displayZoom = zoom.coerceAtLeast(1f)
        delegate?.setDisplayZoom(displayZoom)
    }

    override fun setVisibleViewport(left: Float, top: Float, right: Float, bottom: Float) {
        viewport = floatArrayOf(left, top, right, bottom)
        delegate?.setVisibleViewport(left, top, right, bottom)
    }

    override fun tryReserveFrame(): Boolean = delegate?.tryReserveFrame() == true

    override fun cancelFrameReservation() {
        delegate?.cancelFrameReservation()
    }

    override fun offerFrame(frame: ZoneTrackingFrame) {
        val target = delegate
        if (target == null) frame.close() else target.offerFrame(frame)
    }
}
