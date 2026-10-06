package com.lightmeter.rawmeter

import android.hardware.camera2.CaptureResult

/**
 * Owns distance-provider state and emits only meaningful transitions.  New providers can be
 * registered here later without letting CameraController become the distance business layer.
 */
internal class DistanceCoordinator(
    private val onStateChanged: (DistanceMeasurementState) -> Unit,
    private val clock: () -> Long = System::nanoTime,
) {
    private val focusProvider = Camera2FocusDistanceProvider(clock = clock)
    private var state = DistanceMeasurementState()
    private var focus: DistanceEstimate? = null
    private var motion: DistanceEstimate? = null
    private var context: DistanceContext? = null
    private var motionSupported = false

    fun startFocusDistance(context: DistanceContext, capability: FocusDistanceCapability, motionSupported: Boolean = false) {
        if (this.context != context) { focus = null; motion = null }
        this.context = context
        this.motionSupported = motionSupported
        acceptFocus(focusProvider.start(context, capability))
    }

    fun stop() {
        context = null; focus = null; motion = null
        motionSupported = false
        publish(focusProvider.stop())
    }

    fun invalidate(reason: String) {
        context = null; focus = null; motion = null
        motionSupported = false
        publish(focusProvider.invalidate(reason))
    }

    fun onMotionEstimate(context: DistanceContext, estimate: DistanceEstimate) {
        if (this.context != context || estimate.cameraIdentity != context.cameraIdentity ||
            estimate.target != context.target || !estimate.isCurrent(clock())) return
        motion = estimate
        refresh()
    }

    fun refresh() {
        DistanceFusionEngine.fuse(focus, motion, clock())?.let(::publish) ?: run {
            if (state.estimate != null) publish(DistanceMeasurementState(
                state.estimate?.copy(isFresh = false), DistanceMeasurementStatus.STALE,
                "Distance estimate expired",
            ))
        }
    }

    private fun acceptFocus(next: DistanceMeasurementState) {
        focus = next.estimate
        val fallback = if (next.status == DistanceMeasurementStatus.UNSUPPORTED && motionSupported)
            next.copy(status = DistanceMeasurementStatus.WAITING_FOR_FOCUS,
                diagnosticReason = "Waiting for motion distance") else next
        publish(DistanceFusionEngine.fuse(focus, motion, clock()) ?: fallback)
    }

    fun onCaptureResult(context: DistanceContext, result: CaptureResult, fallbackTimestampNs: Long?, motionConfidence: Double = 1.0) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: fallbackTimestampNs ?: return
        focusProvider.onFrame(
            context = context,
            timestampNs = timestamp,
            afState = result.get(CaptureResult.CONTROL_AF_STATE),
            lensState = result.get(CaptureResult.LENS_STATE),
            diopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
            motionConfidence = motionConfidence,
        )?.let(::acceptFocus)
    }

    private fun publish(next: DistanceMeasurementState) {
        val complete = next.copy(focusObservation = focus, motionObservation = motion)
        if (complete == state) return
        state = complete
        onStateChanged(complete)
    }
}
