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
    fun resetMarker(id: Int, normalizedX: Float, normalizedY: Float)
    /** Replace all marker anchors after a discontinuous, non-zoom preview geometry change. */
    fun reanchor(markers: List<ZoneMarker>)
    /** Change only the UI crop/zoom projection; tracking coordinates and reference frames stay unchanged. */
    fun setDisplayZoom(zoom: Float)
    fun setVisibleViewport(left: Float, top: Float, right: Float, bottom: Float)
    /** Supply an unzoomed camera luminance frame. May be called from the camera thread. */
    fun offerFrame(frame: ZoneTrackingFrame)
}

data class ZoneTrackingFrame(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
    /** Clockwise rotation that makes the camera buffer upright in the current display. */
    val clockwiseRotationDegrees: Int,
    val capturedAtNs: Long = System.nanoTime(),
)
fun interface ZoneMarkerTrackerFactory {
    fun create(
        textureView: TextureView,
        meterState: MeterState,
        callback: (Int, Float, Float, ZoneTrackingState) -> Unit,
    ): ZoneMarkerTracker
}

/** High-level performance controls can be tuned per device without editing the algorithm. */
data class ZoneTrackingTuning(
    val trackingLongEdge: Int = 512,
    val frameIntervalMs: Long = 24L,
    val perMarkerIntervalMs: Long = 1L,
    val maxFrameIntervalMs: Long = 42L,
    val globalFeatureCount: Int = 160,
    val localFeaturesPerMarker: Int = 16,
    val featureRefreshFrames: Int = 14,
    val mappingStabilizationFrames: Int = 1,
)

class OpenCvZoneMarkerTrackerFactory(
    private val tuning: ZoneTrackingTuning = ZoneTrackingTuning(),
) : ZoneMarkerTrackerFactory {
    override fun create(
        textureView: TextureView,
        meterState: MeterState,
        callback: (Int, Float, Float, ZoneTrackingState) -> Unit,
    ): ZoneMarkerTracker = OpenCvZoneMarkerTracker(textureView, meterState, tuning, callback)
}
