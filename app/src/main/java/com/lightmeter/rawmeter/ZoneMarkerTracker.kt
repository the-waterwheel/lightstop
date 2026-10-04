package com.lightmeter.rawmeter

import android.view.TextureView

/**
 * UI-independent contract for Zone marker tracking.
 *
 * The Zone UI owns marker identity and exposure data. An implementation only observes preview
 * motion and publishes normalized positions, so a future descriptor, SLAM, or ML tracker can be
 * substituted without changing [MeterLayout], [ZoneSystemView], or the metering pipeline.
 */
interface ZoneMarkerTracker {
    fun start(markers: List<ZoneMarker>)
    fun stop()
    fun release()
    fun addMarker(id: Int, normalizedX: Float = 0.5f, normalizedY: Float = 0.5f)
    fun removeMarker(id: Int)
    fun clearMarkers()
    /** Protect marker geometry while RAW capture interrupts or changes the ISP preview exposure. */
    fun onMeteringStateChanged(active: Boolean)
    /**
     * End the short gyroscope-only holdover after the live preview stream is usable again.
     * The next visual frame must establish fresh optical-flow state instead of being compared
     * with the frozen pre-RAW image.
     */
    fun resumeVisualTrackingAfterMetering()
    fun resetMarker(id: Int, normalizedX: Float, normalizedY: Float)
    /** Replace all marker anchors after a discontinuous, non-zoom preview geometry change. */
    fun reanchor(markers: List<ZoneMarker>)
    /** Change only the UI crop/zoom projection; tracking coordinates and reference frames stay unchanged. */
    fun setDisplayZoom(zoom: Float)
    fun setVisibleViewport(left: Float, top: Float, right: Float, bottom: Float)
    /**
     * Atomically reserve the worker before CameraController copies a YUV plane.
     * False means the camera frame must be closed without allocating or copying luminance bytes.
     */
    fun tryReserveFrame(): Boolean
    /** Cancel a successful reservation when the camera plane cannot be copied. */
    fun cancelFrameReservation()
    /**
     * Transfer ownership of a reserved, unzoomed luminance frame to the tracker.
     * Implementations must close the frame after processing or rejection.
     */
    fun offerFrame(frame: ZoneTrackingFrame)
}
fun interface ZoneMarkerTrackerFactory {
    fun create(
        textureView: TextureView,
        meterState: MeterState,
        callback: (Int, Float, Float, ZoneTrackingState) -> Unit,
    ): ZoneMarkerTracker
}
