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
    fun resetMarker(id: Int, normalizedX: Float, normalizedY: Float)
    fun setVisibleViewport(left: Float, top: Float, right: Float, bottom: Float)
}
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
