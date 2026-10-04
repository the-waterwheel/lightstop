package com.lightmeter.rawmeter

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Handler
import android.os.SystemClock
import android.util.Log

/** Owns the short neutral-AE settling window that precedes a light measurement. */
internal class MeteringPreviewBaselineCoordinator(
    private val cameraHandler: () -> Handler?,
) {
    private var stableFrames = 0
    private var generation = 0L
    private var operation: PreviewBaselineOperation? = null
    private var timeout: Runnable? = null

    val isActive: Boolean
        get() = operation != null

    val activeGeneration: Long?
        get() = operation?.generation

    fun start(cameraGeneration: Int, continuation: () -> Unit) {
        stableFrames = 0
        val pending = PreviewBaselineOperation(
            cameraGeneration = cameraGeneration,
            generation = ++generation,
            startedAtElapsedMs = SystemClock.elapsedRealtime(),
            continuation = continuation,
        )
        operation = pending
        val handler = cameraHandler() ?: run {
            finish(pending, "no camera handler")
            return
        }
        val timeoutAction = Runnable {
            if (operation == pending) {
                Log.w(TAG, "Timed out waiting for neutral preview AE; continuing measurement")
                finish(pending, "timeout")
            }
        }
        timeout = timeoutAction
        handler.postDelayed(timeoutAction, TIMEOUT_MS)
    }

    fun onCaptureResult(request: CaptureRequest, result: CaptureResult) {
        val pending = operation ?: return
        val requestTag = request.tag as? PreviewRequestTag
        if (!PreviewBaselinePolicy.acceptsResult(pending, requestTag)) return
        val appliedSteps = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
        val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val neutralRequestReached = (appliedSteps == null || appliedSteps == 0) &&
            aeMode != CaptureResult.CONTROL_AE_MODE_OFF
        val aeStable = aeState == null ||
            aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
        stableFrames = if (neutralRequestReached && aeStable) stableFrames + 1 else 0
        if (PreviewBaselinePolicy.hasEnoughStableFrames(stableFrames)) {
            finish(pending, "stable tagged AE")
        }
    }

    fun cancel() {
        timeout?.let { cameraHandler()?.removeCallbacks(it) }
        timeout = null
        stableFrames = 0
        operation = null
    }

    private fun finish(pending: PreviewBaselineOperation, reason: String) {
        if (operation != pending) return
        timeout?.let { cameraHandler()?.removeCallbacks(it) }
        timeout = null
        stableFrames = 0
        operation = null
        Log.i(
            TAG,
            "Neutral preview baseline finished: reason=$reason generation=${pending.generation} " +
                "waitMs=${SystemClock.elapsedRealtime() - pending.startedAtElapsedMs}",
        )
        pending.continuation()
    }

    companion object {
        private const val TAG = "lightstop"
        private const val TIMEOUT_MS = 1_200L
    }
}
