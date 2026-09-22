package com.lightmeter.rawmeter

import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
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

    fun stop() {
        cancelPendingAutoFocusRelease()
        distanceCoordinator.stop()
    }

    fun invalidate(reason: String) {
        cancelPendingAutoFocusRelease()
        distanceCoordinator.invalidate(reason)
    }

    fun onCaptureResult(result: CaptureResult, fallbackTimestampNs: Long?) {
        val context = currentContext() ?: return
        distanceCoordinator.onCaptureResult(context, result, fallbackTimestampNs)
    }

    private fun triggerAutoFocus(): Boolean {
        cancelPendingAutoFocusRelease()
        val device = cameraDevice() ?: return false
        val session = captureSession() ?: return false
        val preview = previewSurface() ?: return false
        val handler = cameraHandler() ?: return false
        val modes = characteristics()?.get(
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
        ) ?: intArrayOf()
        val canTrigger = modes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ||
            modes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)
        if (!canTrigger) return false
        val generation = cameraGeneration()
        return runCatching {
            // CANCEL -> START releases a previous lock and begins exactly one scan. The resident
            // preview request never carries START, so continuous AF is not left locked.
            submitAutoFocusRequest(
                device = device,
                session = session,
                preview = preview,
                handler = handler,
                trigger = CameraMetadata.CONTROL_AF_TRIGGER_CANCEL,
                callback = noOpCaptureCallback,
            )
            submitAutoFocusRequest(
                device = device,
                session = session,
                preview = preview,
                handler = handler,
                trigger = CameraMetadata.CONTROL_AF_TRIGGER_START,
                callback = previewCaptureCallback(),
            )
            scheduleAutoFocusRelease(device, session, preview, handler, generation)
            true
        }.getOrElse {
            Log.w(TAG, "Unable to trigger autofocus for automatic distance", it)
            false
        }
    }

    private fun submitAutoFocusRequest(
        device: CameraDevice,
        session: CameraCaptureSession,
        preview: Surface,
        handler: Handler,
        trigger: Int,
        callback: CameraCaptureSession.CaptureCallback,
    ) {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            configureAutoFocus(this)
            configureCenterAutoFocusRegion(this)
            set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
        }
        session.capture(builder.build(), callback, handler)
    }

    /** Releases the one-shot AF lock without touching a newer session. */
    private fun scheduleAutoFocusRelease(
        device: CameraDevice,
        session: CameraCaptureSession,
        preview: Surface,
        handler: Handler,
        generation: Int,
    ) {
        val task = Runnable {
            if (generation != cameraGeneration() || cameraDevice() !== device ||
                captureSession() !== session || previewSurface() !== preview
            ) return@Runnable
            runCatching {
                submitAutoFocusRequest(
                    device = device,
                    session = session,
                    preview = preview,
                    handler = handler,
                    trigger = CameraMetadata.CONTROL_AF_TRIGGER_IDLE,
                    callback = noOpCaptureCallback,
                )
            }.onFailure { Log.w(TAG, "Unable to release the autofocus trigger", it) }
        }
        pendingAutoFocusRelease = task
        handler.postDelayed(task, AF_RELEASE_DELAY_MS)
    }

    private val noOpCaptureCallback = object : CameraCaptureSession.CaptureCallback() {}
    private var pendingAutoFocusRelease: Runnable? = null

    private fun cancelPendingAutoFocusRelease() {
        val task = pendingAutoFocusRelease ?: return
        cameraHandler()?.removeCallbacks(task)
        pendingAutoFocusRelease = null
    }

    private fun configureCenterAutoFocusRegion(builder: CaptureRequest.Builder) {
        val cameraCharacteristics = characteristics() ?: return
        val maximumRegions = cameraCharacteristics.get(
            CameraCharacteristics.CONTROL_MAX_REGIONS_AF,
        ) ?: 0
        val activeArray = cameraCharacteristics.get(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
        ) ?: return
        if (maximumRegions <= 0 || activeArray.width() <= 0 || activeArray.height() <= 0) return
        val regionWidth = (activeArray.width() / 8).coerceAtLeast(1)
        val regionHeight = (activeArray.height() / 8).coerceAtLeast(1)
        val left = activeArray.centerX() - regionWidth / 2
        val top = activeArray.centerY() - regionHeight / 2
        val centerRegion = Rect(left, top, left + regionWidth, top + regionHeight)
        builder.set(
            CaptureRequest.CONTROL_AF_REGIONS,
            arrayOf(MeteringRectangle(centerRegion, MeteringRectangle.METERING_WEIGHT_MAX)),
        )
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
        private const val AF_RELEASE_DELAY_MS = 600L
    }
}
