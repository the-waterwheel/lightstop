package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.view.Surface
import kotlin.math.max

/**
 * Owns repeating preview request construction and its short-lived YUV/RAW capture state.
 * Session profile selection and all recovery decisions remain in CameraController.
 */
internal class CameraPreviewRequestCoordinator(
    private val cameraHandler: () -> Handler?,
    private val cameraDevice: () -> CameraDevice?,
    private val captureSession: () -> CameraCaptureSession?,
    private val previewSurface: () -> Surface?,
    private val trackingSurface: () -> Surface?,
    private val trackingFramesEnabled: () -> Boolean,
    private val combinationProbeActive: () -> Boolean,
    private val cameraGeneration: () -> Int,
    private val neutralBaselineGeneration: () -> Long?,
    private val previewFpsRange: () -> Range<Int>?,
    private val characteristics: () -> CameraCharacteristics?,
    private val manualExposure: () -> ExposurePreviewManualExposure?,
    private val exposureCompensationSteps: () -> Int,
    private val sessionProfile: () -> CameraSessionProfile?,
    private val configureAutoFocus: (CaptureRequest.Builder) -> Unit,
    private val captureCallback: () -> CameraCaptureSession.CaptureCallback,
    private val onRequestFailure: (generation: Int) -> Unit,
) {
    private var compatibleYuvRequestActive = false
    private var pausedForRawCapture = false

    var requestSequence: Long = 0L
        private set

    fun submit(
        device: CameraDevice,
        session: CameraCaptureSession,
        preview: Surface,
    ) {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        val requestTag = PreviewRequestTag(
            cameraGeneration = cameraGeneration(),
            requestSequence = ++requestSequence,
            neutralBaselineGeneration = neutralBaselineGeneration(),
        )
        builder.setTag(requestTag)
        builder.addTarget(preview)
        val yuvSurface = trackingSurface()
        val includeYuv = yuvSurface != null && (
            trackingFramesEnabled() || compatibleYuvRequestActive || combinationProbeActive()
            )
        if (includeYuv) builder.addTarget(yuvSurface)
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        val appliedManualExposure = manualExposure()
        val fpsRange = previewFpsRange()
        if (appliedManualExposure != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                appliedManualExposure.exposureTimeNs,
            )
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, appliedManualExposure.sensitivity)
            val maximumFrameDuration = characteristics()?.get(
                CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION,
            ) ?: appliedManualExposure.exposureTimeNs
            val targetFrameDurationNs = fpsRange?.upper
                ?.takeIf { it > 0 }
                ?.let { 1_000_000_000L / it }
                ?: LOW_PREVIEW_TARGET_FRAME_DURATION_NS
            builder.set(
                CaptureRequest.SENSOR_FRAME_DURATION,
                max(targetFrameDurationNs, appliedManualExposure.exposureTimeNs)
                    .coerceAtMost(maximumFrameDuration),
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                exposureCompensationSteps(),
            )
            fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }
        configureAutoFocus(builder)
        session.setRepeatingRequest(builder.build(), captureCallback(), cameraHandler())
        Log.i(
            TAG,
            "Preview request submitted: fps=$fpsRange yuv=$includeYuv " +
                "manualExposure=$appliedManualExposure " +
                "exposureCompensationSteps=${exposureCompensationSteps()} " +
                "profile=${sessionProfile()} tag=$requestTag",
        )
    }

    fun update() {
        if (pausedForRawCapture) return
        val device = cameraDevice() ?: return
        val session = captureSession() ?: return
        val preview = previewSurface() ?: return
        try {
            submit(device, session, preview)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to update preview request", error)
            onRequestFailure(cameraGeneration())
        }
    }

    /** Freezes the last displayed frame and drains repeating preview/YUV before RAW captures. */
    fun pauseForRawCapture() {
        if (pausedForRawCapture) return
        val session = captureSession() ?: return
        try {
            session.stopRepeating()
            pausedForRawCapture = true
        } catch (error: Exception) {
            Log.w(TAG, "Unable to pause preview before RAW capture", error)
        }
    }

    fun resumeAfterRawCapture() {
        if (!pausedForRawCapture) return
        pausedForRawCapture = false
        update()
    }

    fun beginCompatibleYuvRequest() {
        compatibleYuvRequestActive = true
        update()
    }

    fun finishCompatibleYuvRequest() {
        val handler = cameraHandler()
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post(::finishCompatibleYuvRequest)
            return
        }
        if (!compatibleYuvRequestActive) return
        compatibleYuvRequestActive = false
        update()
    }

    /** Clears state after a session close without submitting another request. */
    fun resetCaptureState() {
        compatibleYuvRequestActive = false
        pausedForRawCapture = false
    }

    companion object {
        private const val TAG = "lightstop"
        private const val LOW_PREVIEW_TARGET_FRAME_DURATION_NS = 33_333_333L
    }
}
