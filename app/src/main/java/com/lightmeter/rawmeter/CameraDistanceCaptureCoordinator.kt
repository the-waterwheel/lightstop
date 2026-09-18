package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Handler
import android.util.Log
import android.view.Surface

/**
 * Owns the Camera2 side of focus-distance measurement: AF request configuration, one-shot AF
 * triggering and capture-result delivery to the distance provider.
 */
internal class CameraDistanceCaptureCoordinator(
    private val cameraHandler: () -> Handler?,
    private val cameraDevice: () -> CameraDevice?,
    private val captureSession: () -> CameraCaptureSession?,
    private val previewSurface: () -> Surface?,
    private val characteristics: () -> CameraCharacteristics?,
    private val cameraInfo: () -> CameraUiInfo,
    private val cameraGeneration: () -> Int,
    private val previewCaptureCallback: () -> CameraCaptureSession.CaptureCallback,
    onStateChanged: (DistanceMeasurementState) -> Unit,
) {
    private val distanceCoordinator = DistanceCoordinator(onStateChanged)

    fun configureAutoFocus(builder: CaptureRequest.Builder) {
        val modes = characteristics()?.get(
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
        ) ?: intArrayOf()
        when {
            modes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                )
            modes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            else ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        }
    }

    /** Must run on the camera handler so the trigger and following capture plan stay ordered. */
    fun beginAutomaticSampling() {
        val context = currentContext() ?: run {
            distanceCoordinator.invalidate("Active physical camera is unknown")
            return
        }
        val info = cameraInfo()
        distanceCoordinator.startFocusDistance(
            context,
            FocusDistanceCapability(
                minimumDiopters = info.minimumFocusDistanceDiopters,
                calibration = info.focusDistanceCalibration,
                resultKeyAvailable = info.focusDistanceResultAvailable,
                physicalIdentityKnown = info.physicalCameraIdentityKnown,
            ),
        )
        triggerAutoFocus()
    }

    fun stop() = distanceCoordinator.stop()

    fun invalidate(reason: String) = distanceCoordinator.invalidate(reason)

    fun onCaptureResult(result: CaptureResult, fallbackTimestampNs: Long?) {
        val context = currentContext() ?: return
        distanceCoordinator.onCaptureResult(context, result, fallbackTimestampNs)
    }

    private fun triggerAutoFocus(): Boolean {
        val device = cameraDevice() ?: return false
        val session = captureSession() ?: return false
        val preview = previewSurface() ?: return false
        return runCatching {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                configureAutoFocus(this)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }
            session.capture(builder.build(), previewCaptureCallback(), cameraHandler())
            true
        }.getOrElse {
            Log.w(TAG, "Unable to trigger autofocus for automatic distance", it)
            false
        }
    }

    private fun currentContext(): DistanceContext? {
        val info = cameraInfo()
        if (!info.physicalCameraIdentityKnown) return null
        val identity = info.activePhysicalCameraId ?: info.physicalCameraId
            ?: info.runtimeCameraId.takeIf { it.isNotBlank() } ?: return null
        return DistanceContext(
            generation = cameraGeneration(),
            cameraIdentity = identity,
            physicalIdentityKnown = true,
        )
    }

    companion object {
        private const val TAG = "lightstop"
    }
}
