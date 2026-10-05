package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Handler
import android.media.Image
import android.util.Log
import android.view.Surface

/**
 * Owns the Camera2 side of focus-distance measurement: AF request configuration, one-shot AF
 * triggering and capture-result delivery to the distance provider.
 */
internal class CameraDistanceCaptureCoordinator(
    context: Context,
    private val cameraHandler: () -> Handler?,
    private val cameraDevice: () -> CameraDevice?,
    private val captureSession: () -> CameraCaptureSession?,
    private val previewSurface: () -> Surface?,
    private val characteristics: () -> CameraCharacteristics?,
    private val requestCharacteristics: () -> CameraCharacteristics?,
    private val cameraInfo: () -> CameraUiInfo,
    private val cameraGeneration: () -> Int,
    private val previewCaptureCallback: () -> CameraCaptureSession.CaptureCallback,
    private val motionGeometryAllowed: () -> Boolean,
    private val onSamplingChanged: () -> Unit,
    onStateChanged: (DistanceMeasurementState) -> Unit,
) {
    private val distanceCoordinator = DistanceCoordinator(onStateChanged)
    private val motionProvider = MotionParallaxDistanceProvider(context, distanceCoordinator::onMotionEstimate)
    private var activeContext: DistanceContext? = null
    private var lastStartNs = 0L
    private var focusedFrames = 0
    private val expiryCheck = object : Runnable {
        override fun run() {
            if (activeContext == null) return
            distanceCoordinator.refresh()
            cameraHandler()?.postDelayed(this, 250L)
        }
    }
    val wantsFrames: Boolean get() = activeContext != null && motionProvider.running

    fun configureAutoFocus(builder: CaptureRequest.Builder) {
        val modes = requestCharacteristics()?.get(
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
        // Keep the measured target identical in the trigger and resident repeating requests.
        if (activeContext != null) configureCenterAutoFocusRegion(builder)
    }

    /** Must run on the camera handler so the trigger and following capture plan stay ordered. */
    fun beginAutomaticSampling() {
        val context = currentContext() ?: run {
            invalidate("Active physical camera is unknown")
            return
        }
        val info = cameraInfo()
        if (activeContext == context && System.nanoTime() - lastStartNs < 1_500_000_000L) {
            distanceCoordinator.refresh()
            return
        }
        activeContext = context
        lastStartNs = System.nanoTime()
        val handler = cameraHandler()
        if (handler != null && motionGeometryAllowed() && characteristics()?.let(MotionDistanceGeometry::canAttempt) == true)
            motionProvider.start(context, handler) else motionProvider.stop()
        distanceCoordinator.startFocusDistance(
            context,
            FocusDistanceCapability(
                minimumDiopters = info.minimumFocusDistanceDiopters,
                calibration = info.focusDistanceCalibration,
                resultKeyAvailable = info.focusDistanceResultAvailable,
                physicalIdentityKnown = info.physicalCameraIdentityKnown,
                targetConfidence = if ((requestCharacteristics()?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0)
                    1.0 else 0.45,
            ),
            motionSupported = motionProvider.running,
        )
        if (handler != null) {
            handler.removeCallbacks(expiryCheck)
            handler.postDelayed(expiryCheck, 250L)
        }
        onSamplingChanged()
        triggerAutoFocus()
    }

    fun stop() {
        releaseAutoFocus()
        activeContext = null
        cameraHandler()?.removeCallbacks(expiryCheck)
        motionProvider.stop()
        distanceCoordinator.stop()
        onSamplingChanged()
    }

    fun invalidate(reason: String) {
        releaseAutoFocus()
        activeContext = null
        cameraHandler()?.removeCallbacks(expiryCheck)
        motionProvider.stop()
        distanceCoordinator.invalidate(reason)
    }

    fun onCaptureResult(result: CaptureResult, fallbackTimestampNs: Long?) {
        val context = currentContext() ?: run {
            if (activeContext != null) invalidate("Active physical camera is unknown")
            return
        }
        if (activeContext != null && activeContext != context) {
            invalidate("Camera route or session changed")
            return
        }
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: fallbackTimestampNs
        val realtime = characteristics()?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val motionConfidence = if (realtime && timestamp != null) motionProvider.focusReliability(timestamp) else 1.0
        distanceCoordinator.onCaptureResult(context, result, fallbackTimestampNs, motionConfidence)
        if (timestamp != null && context == activeContext && motionGeometryAllowed()) {
            characteristics()?.let { motionProvider.onCaptureResult(timestamp, result, it) }
        }
        val focused = result.get(CaptureResult.CONTROL_AF_STATE) == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED
        focusedFrames = if (focused) focusedFrames + 1 else 0
        if (focusedFrames >= 10) releaseAutoFocus()
    }

    fun onImage(image: Image) {
        if (motionGeometryAllowed()) runCatching { motionProvider.onImage(image) }.onFailure {
            // Optional ranging must never interrupt the owner of the YUV/Zone image.
            motionProvider.stop()
            Log.w(TAG, "Motion distance frame unavailable", it)
        }
    }

    private fun releaseAutoFocus() {
        val release = pendingAutoFocusRelease ?: return
        cancelPendingAutoFocusRelease()
        release.run()
    }

    private fun triggerAutoFocus(): Boolean {
        cancelPendingAutoFocusRelease()
        focusedFrames = 0
        val device = cameraDevice() ?: return false
        val session = captureSession() ?: return false
        val preview = previewSurface() ?: return false
        val handler = cameraHandler() ?: return false
        val modes = requestCharacteristics()?.get(
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
        ) ?: intArrayOf()
        val canTrigger = modes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ||
            modes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)
        if (!canTrigger) return false
        val generation = cameraGeneration()
        return runCatching {
            // CANCEL -> START releases a previous lock and begins exactly one scan. The resident
            // preview request never carries START. Completion/timeout explicitly sends CANCEL.
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
            pendingAutoFocusRelease = null
            runCatching {
                submitAutoFocusRequest(
                    device = device,
                    session = session,
                    preview = preview,
                    handler = handler,
                    trigger = CameraMetadata.CONTROL_AF_TRIGGER_CANCEL,
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
        // CaptureRequest coordinates belong to the opened camera, which can be logical even
        // when the focus metadata and output buffers belong to a fixed physical camera.
        val cameraCharacteristics = requestCharacteristics() ?: return
        val maximumRegions = cameraCharacteristics.get(
            CameraCharacteristics.CONTROL_MAX_REGIONS_AF,
        ) ?: 0
        val activeArray = cameraCharacteristics.get(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
        ) ?: return
        if (maximumRegions <= 0 || activeArray.width() <= 0 || activeArray.height() <= 0) return
        val viewport = cameraInfo().previewSensorViewport
        val regionWidth = (activeArray.width() * (viewport.right - viewport.left) / 8).toInt().coerceAtLeast(1)
        val regionHeight = (activeArray.height() * (viewport.bottom - viewport.top) / 8).toInt().coerceAtLeast(1)
        val left = (activeArray.left + activeArray.width() * (viewport.left + viewport.right) / 2).toInt() - regionWidth / 2
        val top = (activeArray.top + activeArray.height() * (viewport.top + viewport.bottom) / 2).toInt() - regionHeight / 2
        val centerRegion = Rect(left, top, left + regionWidth, top + regionHeight)
        builder.set(
            CaptureRequest.CONTROL_AF_REGIONS,
            arrayOf(MeteringRectangle(centerRegion, MeteringRectangle.METERING_WEIGHT_MAX)),
        )
    }

    private fun currentContext(): DistanceContext? {
        val info = cameraInfo()
        val identity = info.activePhysicalCameraId ?: info.physicalCameraId
            ?: info.runtimeCameraId.takeIf { it.isNotBlank() } ?: return null
        return DistanceContext(
            generation = cameraGeneration(),
            cameraIdentity = identity,
            physicalIdentityKnown = info.physicalCameraIdentityKnown,
        )
    }

    companion object {
        private const val TAG = "lightstop"
        private const val AF_RELEASE_DELAY_MS = 1_500L
    }
}
