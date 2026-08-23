package com.lightmeter.rawmeter

import kotlin.math.roundToInt

enum class ExposurePreviewMode {
    OFF,
    ON,
}

data class ExposurePreviewSelection(
    val previewCalibratedSceneEv100: Double,
    val selectedExposureEv100: Double,
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
        selection.previewCalibratedSceneEv100 - selection.selectedExposureEv100

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
}
