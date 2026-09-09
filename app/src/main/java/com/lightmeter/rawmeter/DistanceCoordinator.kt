package com.lightmeter.rawmeter

import android.hardware.camera2.CaptureResult

/**
 * Owns distance-provider state and emits only meaningful transitions.  New providers can be
 * registered here later without letting CameraController become the distance business layer.
 */
internal class DistanceCoordinator(
    private val onStateChanged: (DistanceMeasurementState) -> Unit,
) {
    private val focusProvider = Camera2FocusDistanceProvider()
    private var state = DistanceMeasurementState()

    fun startFocusDistance(context: DistanceContext, capability: FocusDistanceCapability) {
        publish(focusProvider.start(context, capability))
    }

    fun stop() = publish(focusProvider.stop())

    fun invalidate(reason: String) = publish(focusProvider.invalidate(reason))

    fun onCaptureResult(context: DistanceContext, result: CaptureResult, fallbackTimestampNs: Long?) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: fallbackTimestampNs ?: return
        focusProvider.onFrame(
            context = context,
            timestampNs = timestamp,
            afState = result.get(CaptureResult.CONTROL_AF_STATE),
            lensState = result.get(CaptureResult.LENS_STATE),
            diopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
        )?.let(::publish)
    }

    private fun publish(next: DistanceMeasurementState) {
        if (next == state) return
        state = next
        onStateChanged(next)
    }
}
