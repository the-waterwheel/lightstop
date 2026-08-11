package com.lightmeter.rawmeter

import android.view.TextureView

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

/** The only construction boundary that knows the concrete OpenCV implementation. */
class OpenCvZoneMarkerTrackerFactory(
    private val tuning: ZoneTrackingTuning = ZoneTrackingTuning(),
) : ZoneMarkerTrackerFactory {
    override fun create(
        textureView: TextureView,
        meterState: MeterState,
        callback: (Int, Float, Float, ZoneTrackingState) -> Unit,
    ): ZoneMarkerTracker = OpenCvZoneMarkerTracker(textureView, meterState, tuning, callback)
}
