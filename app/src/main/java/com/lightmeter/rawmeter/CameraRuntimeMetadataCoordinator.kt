package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.util.Log
import android.util.Size
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ActivePhysicalMetadataUpdate(
    val characteristics: CameraCharacteristics,
    val cameraInfo: CameraUiInfo,
)

/**
 * Converts high-frequency Camera2 results into UI/runtime metadata updates. This component does
 * not submit requests or replace sessions, keeping result interpretation separate from HAL
 * lifecycle decisions.
 */
internal class CameraRuntimeMetadataCoordinator(
    private val cameraManager: CameraManager,
) {
    private val activePhysicalCameraTracker = ActivePhysicalCameraTracker()
    private var actualFpsWindowStartNs = 0L
    private var actualFpsFrameCount = 0

    fun resetPhysicalCamera(
        logicalCameraId: String,
        requestedPhysicalCameraId: String?,
    ): ActiveCameraContext = activePhysicalCameraTracker.reset(
        logicalCameraId,
        requestedPhysicalCameraId,
    )

    fun observeActivePhysicalCamera(
        result: TotalCaptureResult,
        logicalCharacteristics: CameraCharacteristics?,
        currentCharacteristics: CameraCharacteristics?,
        currentInfo: CameraUiInfo,
    ): ActivePhysicalMetadataUpdate? {
        val update = activePhysicalCameraTracker.update(result)
        if (update.context.requestedPhysicalCameraId != null &&
            !update.requestedPhysicalResultPresent
        ) {
            Log.w(
                TAG,
                "Physical result missing for requested camera=" +
                    "${update.context.requestedPhysicalCameraId}; the frame will use only " +
                    "metadata present in its TotalCaptureResult",
            )
        }
        if (!update.changed) return null
        val nextCharacteristics = update.context.activePhysicalCameraId?.let { physicalId ->
            runCatching { cameraManager.getCameraCharacteristics(physicalId) }.getOrNull()
        } ?: logicalCharacteristics
        val characteristics = nextCharacteristics ?: currentCharacteristics ?: return null
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: currentInfo.focalLengthMm
        val aperture = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.firstOrNull() ?: currentInfo.aperture
        val activeArray = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
        ) ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        return ActivePhysicalMetadataUpdate(
            characteristics = characteristics,
            cameraInfo = currentInfo.copy(
                activePhysicalCameraId = update.context.activePhysicalCameraId,
                focalLengthMm = focal,
                aperture = aperture,
                focusDistanceMeters = null,
                minimumFocusDistanceDiopters = characteristics.get(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                ) ?: 0f,
                focusDistanceCalibration = characteristics.get(
                    CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION,
                ),
                focusDistanceResultAvailable = runCatching {
                    characteristics.availableCaptureResultKeys
                }.getOrDefault(emptyList()).contains(CaptureResult.LENS_FOCUS_DISTANCE),
                sensorWidthMm = physicalSize?.width ?: currentInfo.sensorWidthMm,
                sensorHeightMm = physicalSize?.height ?: currentInfo.sensorHeightMm,
                // The TextureView/YUV stream remains in the logical stream's coordinate system.
                // A physical result may describe a differently mounted sensor, but replacing the
                // stream orientation here makes the already-running preview flip aspect after the
                // first physical result arrives. RAW code reads the active physical
                // characteristics directly and therefore does not need this field overwritten.
                sensorOrientationDegrees = currentInfo.sensorOrientationDegrees,
                activeArray = activeArray ?: currentInfo.activeArray,
                previewSensorViewport = NormalizedSensorViewport.FULL,
            ),
        )
    }

    fun observeDynamicLensInfo(
        result: CaptureResult,
        currentInfo: CameraUiInfo,
    ): CameraUiInfo? {
        val focal = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: currentInfo.focalLengthMm
        val aperture = result.get(CaptureResult.LENS_APERTURE) ?: currentInfo.aperture
        val lensChanged = focal > 0f && (
            abs(focal - currentInfo.focalLengthMm) >= 0.01f ||
                abs(aperture - currentInfo.aperture) >= 0.01f
            )
        if (!lensChanged) return null
        return currentInfo.copy(
            focalLengthMm = focal.takeIf { it > 0f } ?: currentInfo.focalLengthMm,
            aperture = aperture,
        )
    }

    fun observeActualPreviewFps(
        timestampNs: Long?,
        currentInfo: CameraUiInfo,
    ): CameraUiInfo? {
        val timestamp = timestampNs ?: return null
        if (actualFpsWindowStartNs <= 0L || timestamp <= actualFpsWindowStartNs) {
            actualFpsWindowStartNs = timestamp
            actualFpsFrameCount = 1
            return null
        }
        actualFpsFrameCount += 1
        val elapsed = timestamp - actualFpsWindowStartNs
        if (elapsed < ACTUAL_FPS_WINDOW_NS) return null
        val fps = ((actualFpsFrameCount - 1) * 1_000_000_000.0 / elapsed)
            .toFloat()
            .coerceIn(0f, 240f)
        actualFpsWindowStartNs = timestamp
        actualFpsFrameCount = 1
        return currentInfo.copy(actualPreviewFps = fps)
    }

    fun observePreviewSensorViewport(
        result: CaptureResult,
        currentInfo: CameraUiInfo,
        previewSize: Size?,
        outputAspectOverride: Float?,
    ): CameraUiInfo? {
        val active = currentInfo.activeArray ?: return null
        val output = previewSize ?: currentInfo.previewSize ?: return null
        val crop = result.get(CaptureResult.SCALER_CROP_REGION)
        val zoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            result.get(CaptureResult.CONTROL_ZOOM_RATIO)
        } else {
            null
        }
        val outputWidth = outputAspectOverride?.let {
            (it * OUTPUT_ASPECT_SCALE).roundToInt()
        } ?: output.width
        val outputHeight = if (outputAspectOverride != null) {
            OUTPUT_ASPECT_SCALE
        } else {
            output.height
        }
        val viewport = PreviewOutputGeometry.sensorViewport(
            activeLeft = active.left,
            activeTop = active.top,
            activeRight = active.right,
            activeBottom = active.bottom,
            cropLeft = crop?.left,
            cropTop = crop?.top,
            cropRight = crop?.right,
            cropBottom = crop?.bottom,
            zoomRatio = zoom,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
        )
        if (viewport.isCloseTo(currentInfo.previewSensorViewport)) return null
        Log.i(TAG, "Preview sensor viewport changed ${currentInfo.previewSensorViewport}->$viewport")
        return currentInfo.copy(previewSensorViewport = viewport)
    }

    fun reset() {
        actualFpsWindowStartNs = 0L
        actualFpsFrameCount = 0
        activePhysicalCameraTracker.reset("", null)
    }

    companion object {
        private const val TAG = "lightstop"
        private const val ACTUAL_FPS_WINDOW_NS = 1_000_000_000L
        private const val OUTPUT_ASPECT_SCALE = 10_000
    }
}
