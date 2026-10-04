package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Handler

/**
 * Owns the short-lived 0-EV comparison used by auxiliary exposure-preview calibration.
 * CameraController only decides when the workflow may start and submits the resulting request.
 */
internal class ExposurePreviewCalibrationCoordinator(
    private val mainHandler: Handler,
    private val cameraHandler: () -> Handler?,
    private val cameraGeneration: () -> Int,
    private val previewRequestSequence: () -> Long,
    private val latestResult: () -> CaptureResult?,
    private val characteristics: () -> CameraCharacteristics?,
    private val cameraInfo: () -> CameraUiInfo,
    private val calibrationIdentity: () -> CalibrationCaptureIdentity,
    private val calibrationCameraId: () -> String,
    private val applyPreview: (ExposurePreviewManualExposure?, Int) -> Unit,
) {
    private data class Baseline(
        val cameraEv100: Double,
        val sensitivity: Int,
        val aperture: Double,
    )

    private data class Operation(
        val cameraGeneration: Int,
        val requestSequence: Long,
        val callback: (Boolean) -> Unit,
    )

    private var operation: Operation? = null
    private var timeout: Runnable? = null
    private var stableFrames = 0
    private var baseline: Baseline? = null
    private var expectedIdentity: CalibrationCaptureIdentity? = null
    private var activeCallback: ((Boolean) -> Unit)? = null

    var isActive: Boolean = false
        private set

    @Volatile
    var cameraId: String? = null
        private set

    /** Must run on the camera thread. */
    fun begin(onReady: (Boolean) -> Unit) {
        cancelPreparation()
        isActive = true
        cameraId = calibrationCameraId()
        expectedIdentity = calibrationIdentity()
        activeCallback = onReady
        baseline = null
        stableFrames = 0
        applyPreview(null, 0)
        val pending = Operation(
            cameraGeneration = cameraGeneration(),
            requestSequence = previewRequestSequence(),
            callback = onReady,
        )
        operation = pending
        val timeoutAction = Runnable {
            if (operation === pending) {
                finishPreparation(pending, baselineFrom(latestResult()))
            }
        }
        timeout = timeoutAction
        cameraHandler()?.postDelayed(timeoutAction, PREPARATION_TIMEOUT_MS)
    }

    /** Must run on the camera thread. Null shows the AE 0-EV reference. */
    fun updateComparison(correctionEv: Double?) {
        if (!isActive) return
        if (correctionEv == null) {
            applyPreview(null, 0)
            return
        }
        val correction = ExposurePreviewCalibrationMath.clampAndSnapCorrection(correctionEv)
        val manualExposure = baseline?.let { manualExposure(it, correction) }
        val compensation = compensation(correction)
        if (manualExposure == null && !compensation.supported) return
        applyPreview(manualExposure, if (manualExposure == null) compensation.steps else 0)
    }

    /** Must run on the camera thread. */
    fun finish() {
        cancelPreparation()
        isActive = false
        baseline = null
        expectedIdentity = null
        activeCallback = null
        cameraId = null
        applyPreview(null, 0)
    }

    /** Must run on the camera thread. */
    fun cancelForCameraClose() {
        cancelPreparation()
        val callback = activeCallback
        isActive = false
        baseline = null
        expectedIdentity = null
        activeCallback = null
        cameraId = null
        if (callback != null) mainHandler.post { callback(false) }
    }

    /** Must run on the camera callback thread. */
    fun onCaptureResult(request: CaptureRequest, result: CaptureResult) {
        if (!isActive) return
        val currentIdentity = calibrationIdentity()
        val expected = expectedIdentity
        if (expected != null && expected.conflictsWith(currentIdentity)) {
            failForLensChange()
            return
        }
        expectedIdentity = expected?.withReportedPhysicalFrom(currentIdentity) ?: currentIdentity
        cameraId = calibrationCameraId()
        val pending = operation ?: return
        val tag = request.tag as? PreviewRequestTag ?: return
        if (tag.cameraGeneration != pending.cameraGeneration ||
            tag.requestSequence < pending.requestSequence
        ) {
            return
        }
        val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
            ?: request.get(CaptureRequest.CONTROL_AE_MODE)
        val compensationSteps = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
            ?: request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)
            ?: 0
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val neutralAndStable = aeMode != CameraMetadata.CONTROL_AE_MODE_OFF &&
            compensationSteps == 0 && isStableAeState(aeState)
        stableFrames = if (neutralAndStable) stableFrames + 1 else 0
        if (stableFrames < PreviewBaselinePolicy.REQUIRED_STABLE_FRAMES) return
        finishPreparation(pending, baselineFrom(result))
    }

    private fun finishPreparation(pending: Operation, capturedBaseline: Baseline?) {
        if (operation !== pending) return
        timeout?.let { cameraHandler()?.removeCallbacks(it) }
        timeout = null
        operation = null
        stableFrames = 0
        baseline = capturedBaseline
        val ready = capturedBaseline?.let { manualExposure(it, correctionEv = 0.0) } != null ||
            compensation(correctionEv = 0.0).supported
        if (!ready) {
            isActive = false
            expectedIdentity = null
            activeCallback = null
            cameraId = null
        }
        mainHandler.post { pending.callback(ready) }
    }

    private fun cancelPreparation() {
        timeout?.let { cameraHandler()?.removeCallbacks(it) }
        timeout = null
        operation = null
        stableFrames = 0
    }

    private fun failForLensChange() {
        cancelPreparation()
        val callback = activeCallback
        isActive = false
        baseline = null
        expectedIdentity = null
        activeCallback = null
        cameraId = null
        applyPreview(null, 0)
        if (callback != null) mainHandler.post { callback(false) }
    }

    private fun baselineFrom(result: CaptureResult?): Baseline? {
        result ?: return null
        val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val aperture = (result.get(CaptureResult.LENS_APERTURE)
            ?: cameraInfo().aperture.takeIf { it > 0f })?.toDouble() ?: return null
        val cameraEv100 = ExposurePreviewMath.cameraEv100(
            aperture = aperture,
            exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null,
            sensitivity = sensitivity,
        ) ?: return null
        return Baseline(cameraEv100, sensitivity, aperture)
    }

    private fun manualExposure(baseline: Baseline, correctionEv: Double): ExposurePreviewManualExposure? {
        val info = cameraInfo()
        if (!info.manualSensorAvailable) return null
        val chars = characteristics() ?: return null
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: return null
        val sensitivityRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: return null
        val maximumFrameDuration = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
            ?: exposureRange.upper
        return ExposurePreviewMath.manualExposure(
            targetCameraEv100 = ExposurePreviewCalibrationMath.calibrationManualTargetEv100(
                baseline.cameraEv100,
                correctionEv,
            ),
            cameraAperture = baseline.aperture,
            preferredSensitivity = baseline.sensitivity,
            minimumExposureTimeNs = exposureRange.lower,
            maximumExposureTimeNs = minOf(exposureRange.upper, maximumFrameDuration),
            minimumSensitivity = sensitivityRange.lower,
            maximumSensitivity = sensitivityRange.upper,
        )
    }

    private fun compensation(correctionEv: Double): ExposurePreviewCompensation {
        val chars = characteristics()
        val range = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble()
        val requested = ExposurePreviewCalibrationMath.calibrationAeCompensationEv(correctionEv)
        if (range == null || step == null) {
            return ExposurePreviewCompensation(
                requestedEv = requested,
                appliedEv = 0.0,
                steps = 0,
                supported = false,
                clamped = false,
            )
        }
        return ExposurePreviewMath.quantizeCompensation(
            requestedEv = requested,
            minimumSteps = range.lower,
            maximumSteps = range.upper,
            stepEv = step,
        )
    }

    private fun isStableAeState(state: Int?): Boolean = state == null ||
        state == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
        state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
        state == CaptureResult.CONTROL_AE_STATE_LOCKED

    companion object {
        private const val PREPARATION_TIMEOUT_MS = 1_800L
    }
}
