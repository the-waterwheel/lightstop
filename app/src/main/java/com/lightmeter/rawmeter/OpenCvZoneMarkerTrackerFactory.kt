package com.lightmeter.rawmeter

import android.util.Log
import android.view.TextureView
import org.opencv.android.OpenCVLoader

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
    ): ZoneMarkerTracker {
        val initialized = try {
            OpenCVLoader.initLocal().also { ready ->
                Log.i(TAG, "OpenCV ${OpenCVLoader.OPENCV_VERSION} initialized=$ready")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "OpenCV initialization failed", error)
            false
        }
        if (!initialized) return unavailableTracker(textureView, meterState, callback)
        return try {
            // No native OpenCV object is constructed until initLocal() has succeeded.
            OpenCvZoneMarkerTracker(textureView, meterState, tuning, callback)
        } catch (error: Throwable) {
            Log.e(TAG, "OpenCV tracker construction failed; using static markers", error)
            unavailableTracker(textureView, meterState, callback)
        }
    }

    private fun unavailableTracker(
        textureView: TextureView,
        meterState: MeterState,
        callback: (Int, Float, Float, ZoneTrackingState) -> Unit,
    ): ZoneMarkerTracker {
        textureView.post {
            meterState.transientMessage = if (meterState.menuLanguage == MenuLanguage.ENGLISH) {
                "Motion tracking is unavailable; Zone markers will remain fixed"
            } else {
                "动态跟踪不可用，区域标记将保持固定"
            }
            textureView.rootView.invalidate()
        }
        return StaticZoneMarkerTracker(callback)
    }

    private companion object {
        private const val TAG = "OpenCvTrackerFactory"
    }
}
