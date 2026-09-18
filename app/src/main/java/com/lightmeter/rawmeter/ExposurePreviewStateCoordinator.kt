package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.util.Log

/**
 * Owns the ordinary exposure-preview selection and the Camera2 exposure state derived from it.
 * Auxiliary preview calibration remains a separate workflow and can temporarily replace the
 * applied state through [applyCalibrationComparison].
 */
internal class ExposurePreviewStateCoordinator(
    private val onRequestChanged: () -> Unit,
    private val onUnavailable: () -> Unit,
) {
    @Volatile
    private var requestedSelection: ExposurePreviewSelection? = null

    var manualExposure: ExposurePreviewManualExposure? = null
        private set

    var compensationSteps: Int = 0
        private set

    private var unsupportedReported = false

    val isNeutral: Boolean
        get() = manualExposure == null && compensationSteps == 0

    fun updateSelection(selection: ExposurePreviewSelection?) {
        requestedSelection = selection?.takeIf {
            it.previewCalibratedSceneEv100.isFinite() &&
                it.selectedExposureEv100.isFinite() &&
                it.previewCorrectionEv.isFinite() &&
                it.executionCorrectionEv.isFinite()
        }
    }

    fun clearRequestedSelection() {
        requestedSelection = null
    }

    fun apply(
        characteristics: CameraCharacteristics?,
        cameraInfo: CameraUiInfo,
        latestResult: CaptureResult?,
    ) {
        val selection = requestedSelection
        if (selection != null && characteristics == null) return
        val range = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = characteristics?.get(
            CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP,
        )?.toDouble()
        val requestedCompensation = selection?.let(ExposurePreviewMath::requestedCompensationEv)
        val requestedManualExposure = selection?.let { requested ->
            val exposureRange = characteristics?.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
            )
            val sensitivityRange = characteristics?.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
            )
            val maximumFrameDuration = characteristics?.get(
                CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION,
            )
            val maximumExposureTime = if (exposureRange != null) {
                minOf(exposureRange.upper, maximumFrameDuration ?: exposureRange.upper)
            } else {
                null
            }
            if (!cameraInfo.manualSensorAvailable || exposureRange == null ||
                sensitivityRange == null || maximumExposureTime == null
            ) {
                null
            } else {
                ExposurePreviewMath.manualExposure(
                    targetCameraEv100 = ExposurePreviewMath.targetCameraEv100(requested),
                    cameraAperture = cameraInfo.aperture.toDouble(),
                    preferredSensitivity = latestResult
                        ?.get(CaptureResult.SENSOR_SENSITIVITY) ?: sensitivityRange.lower,
                    minimumExposureTimeNs = exposureRange.lower,
                    maximumExposureTimeNs = maximumExposureTime,
                    minimumSensitivity = sensitivityRange.lower,
                    maximumSensitivity = sensitivityRange.upper,
                )
            }
        }
        val compensation = if (requestedCompensation == null || range == null || step == null) {
            ExposurePreviewCompensation(
                requestedEv = requestedCompensation ?: 0.0,
                appliedEv = 0.0,
                steps = 0,
                supported = requestedCompensation == null,
                clamped = false,
            )
        } else {
            ExposurePreviewMath.quantizeCompensation(
                requestedEv = requestedCompensation,
                minimumSteps = range.lower,
                maximumSteps = range.upper,
                stepEv = step,
            )
        }
        val supported = selection == null || requestedManualExposure != null || compensation.supported
        if (selection == null) {
            unsupportedReported = false
        } else if (!supported && !unsupportedReported) {
            unsupportedReported = true
            onUnavailable()
        }
        val requestedSteps = if (requestedManualExposure == null) compensation.steps else 0
        if (manualExposure == requestedManualExposure && compensationSteps == requestedSteps) return
        manualExposure = requestedManualExposure
        compensationSteps = requestedSteps
        onRequestChanged()
        Log.i(
            TAG,
            "Exposure preview: selected=${selection?.selectedExposureEv100}EV100 " +
                "targetCamera=${selection?.let(ExposurePreviewMath::targetCameraEv100)}EV100 " +
                "manual=$requestedManualExposure requested=${compensation.requestedEv}EV " +
                "applied=${compensation.appliedEv}EV steps=${compensation.steps} " +
                "supported=$supported " +
                "clamped=${requestedManualExposure?.clamped ?: compensation.clamped}",
        )
    }

    /** Applies the auxiliary calibration comparison without changing the ordinary selection. */
    fun applyCalibrationComparison(
        manualExposure: ExposurePreviewManualExposure?,
        compensationSteps: Int,
    ) {
        this.manualExposure = manualExposure
        this.compensationSteps = compensationSteps
        onRequestChanged()
    }

    /** Clears preview correction but lets the controller submit it with a fresh baseline tag. */
    fun neutralizeForMetering(): Boolean {
        requestedSelection = null
        if (isNeutral) return false
        manualExposure = null
        compensationSteps = 0
        return true
    }

    fun reset() {
        requestedSelection = null
        manualExposure = null
        compensationSteps = 0
        unsupportedReported = false
    }

    companion object {
        private const val TAG = "lightstop"
    }
}
