package com.lightmeter.rawmeter

import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.pow

enum class ExposurePreviewMode {
    OFF,
    ON,
}

data class ExposurePreviewSelection(
    val previewCalibratedSceneEv100: Double,
    val selectedExposureEv100: Double,
    val previewCorrectionEv: Double,
    val executionCorrectionEv: Double = 0.0,
)

data class ExposurePreviewManualExposure(
    val exposureTimeNs: Long,
    val sensitivity: Int,
    val appliedCameraEv100: Double,
    val clamped: Boolean,
)

data class ExposurePreviewCompensation(
    val requestedEv: Double,
    val appliedEv: Double,
    val steps: Int,
    val supported: Boolean,
    val clamped: Boolean,
)

/** Pure exposure-preview calculations, kept independent from Camera2 and the view layer. */
object ExposurePreviewMath {
    fun exposureEv100(
        apertureCoordinate: Double,
        shutterCoordinate: Double,
        iso: Int,
    ): Double {
        require(iso > 0) { "ISO must be positive" }
        return apertureCoordinate - shutterCoordinate - ExposureMath.log2(iso / 100.0)
    }

    /**
     * Moves a reading from its source's calibration domain to the preview/YUV domain.
     * RAW has a separate baseline and user correction; YUV and processed preview share one.
     */
    fun previewCalibratedSceneEv100(
        measuredSceneEv100: Double,
        sourceCorrectionEv: Double,
        previewCorrectionEv: Double,
    ): Double = measuredSceneEv100 - sourceCorrectionEv + previewCorrectionEv

    fun requestedCompensationEv(selection: ExposurePreviewSelection): Double =
        selection.previewCalibratedSceneEv100 - selection.selectedExposureEv100 -
            selection.executionCorrectionEv

    /** Camera exposure EV that renders the calibrated photographic selection at reference luma. */
    fun targetCameraEv100(selection: ExposurePreviewSelection): Double =
        selection.selectedExposureEv100 - selection.previewCorrectionEv +
            selection.executionCorrectionEv

    fun cameraEv100(
        aperture: Double,
        exposureTimeNs: Long,
        sensitivity: Int,
    ): Double? {
        if (!aperture.isFinite() || aperture <= 0.0 || exposureTimeNs <= 0L || sensitivity <= 0) {
            return null
        }
        val seconds = exposureTimeNs / NANOSECONDS_PER_SECOND
        return ExposureMath.log2(aperture * aperture / seconds * 100.0 / sensitivity)
            .takeIf(Double::isFinite)
    }

    /**
     * Converts the selected photographic EV into a responsive Camera2 preview exposure. Preview
     * prioritizes shutter time and raises ISO before allowing a slow frame, so the viewfinder
     * remains usable in low light instead of following the previous ISO-preferred behaviour.
     */
    fun manualExposure(
        targetCameraEv100: Double,
        cameraAperture: Double,
        preferredSensitivity: Int,
        minimumExposureTimeNs: Long,
        maximumExposureTimeNs: Long,
        minimumSensitivity: Int,
        maximumSensitivity: Int,
    ): ExposurePreviewManualExposure? {
        if (!targetCameraEv100.isFinite() || !cameraAperture.isFinite() || cameraAperture <= 0.0 ||
            minimumExposureTimeNs <= 0L || maximumExposureTimeNs < minimumExposureTimeNs ||
            minimumSensitivity <= 0 || maximumSensitivity < minimumSensitivity
        ) return null

        // t * ISO = N² * 100 / 2^EV for an EV100 exposure.
        val exposureIsoProduct = cameraAperture * cameraAperture * 100.0 /
            2.0.pow(targetCameraEv100)
        if (!exposureIsoProduct.isFinite() || exposureIsoProduct <= 0.0) return null
        val minimumSeconds = minimumExposureTimeNs / NANOSECONDS_PER_SECOND
        val maximumSeconds = maximumExposureTimeNs / NANOSECONDS_PER_SECOND
        val responsiveSeconds = minOf(maximumSeconds, PREFERRED_PREVIEW_SECONDS)
        val hardSeconds = minOf(maximumSeconds, HARD_PREVIEW_SECONDS)
        // `preferredSensitivity` remains part of the API for callers and diagnostics, but must
        // not pull the preview toward a long shutter. In bright scenes ISO bottoms out; in dark
        // scenes it rises before the shutter is allowed past the responsive target.
        var sensitivity = ceil(exposureIsoProduct / responsiveSeconds).toInt()
            .coerceIn(minimumSensitivity, maximumSensitivity)
        var seconds = exposureIsoProduct / sensitivity
        if (seconds > hardSeconds) seconds = hardSeconds
        if (seconds < minimumSeconds) {
            seconds = minimumSeconds
            sensitivity = ceil(exposureIsoProduct / seconds).toInt()
                .coerceIn(minimumSensitivity, maximumSensitivity)
        }
        val exposureTimeNs = (seconds * NANOSECONDS_PER_SECOND).toLong()
            .coerceIn(minimumExposureTimeNs, maximumExposureTimeNs)
        val appliedEv100 = ExposureMath.log2(
            cameraAperture * cameraAperture /
                (exposureTimeNs / NANOSECONDS_PER_SECOND) *
                100.0 / sensitivity,
        )
        return ExposurePreviewManualExposure(
            exposureTimeNs = exposureTimeNs,
            sensitivity = sensitivity,
            appliedCameraEv100 = appliedEv100,
            clamped = kotlin.math.abs(appliedEv100 - targetCameraEv100) > 0.02,
        )
    }

    fun quantizeCompensation(
        requestedEv: Double,
        minimumSteps: Int,
        maximumSteps: Int,
        stepEv: Double,
    ): ExposurePreviewCompensation {
        val supported = minimumSteps < maximumSteps && stepEv > 0.0 && stepEv.isFinite()
        if (!supported || !requestedEv.isFinite()) {
            return ExposurePreviewCompensation(
                requestedEv = requestedEv,
                appliedEv = 0.0,
                steps = 0,
                supported = false,
                clamped = false,
            )
        }
        val unrestrictedSteps = (requestedEv / stepEv).roundToInt()
        val steps = unrestrictedSteps.coerceIn(minimumSteps, maximumSteps)
        return ExposurePreviewCompensation(
            requestedEv = requestedEv,
            appliedEv = steps * stepEv,
            steps = steps,
            supported = true,
            clamped = steps != unrestrictedSteps,
        )
    }

    private const val NANOSECONDS_PER_SECOND = 1_000_000_000.0
    private const val PREFERRED_PREVIEW_SECONDS = 1.0 / 30.0
    private const val HARD_PREVIEW_SECONDS = 1.0 / 15.0
}

/** Independent, per-camera correction for the execution of exposure preview. */
object ExposurePreviewCalibrationMath {
    const val MIN_CORRECTION_EV = -4.0
    const val MAX_CORRECTION_EV = 4.0
    const val STEP_EV = 1.0 / 3.0

    fun snapCorrection(value: Double): Double =
        (value / STEP_EV).roundToInt() * STEP_EV

    fun clampAndSnapCorrection(value: Double): Double = snapCorrection(
        value.coerceIn(MIN_CORRECTION_EV, MAX_CORRECTION_EV),
    ).coerceIn(MIN_CORRECTION_EV, MAX_CORRECTION_EV)

    fun calibrationManualTargetEv100(neutralCameraEv100: Double, correctionEv: Double): Double =
        neutralCameraEv100 + correctionEv

    fun calibrationAeCompensationEv(correctionEv: Double): Double = -correctionEv
}
