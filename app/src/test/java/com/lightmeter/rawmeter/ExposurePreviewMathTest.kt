package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposurePreviewMathTest {
    @Test
    fun selectedPhotographicParametersProduceEv100() {
        val ev100 = ExposurePreviewMath.exposureEv100(
            apertureCoordinate = ExposureMath.apertureStop(8.0),
            shutterCoordinate = ExposureMath.log2(1.0 / 125.0),
            iso = 100,
        )

        assertEquals(ExposureMath.log2(8.0 * 8.0 * 125.0), ev100, 0.0001)
    }

    @Test
    fun rawReadingIsMovedOntoPreviewCalibration() {
        val previewEv = ExposurePreviewMath.previewCalibratedSceneEv100(
            measuredSceneEv100 = 11.5,
            sourceCorrectionEv = 0.7,
            previewCorrectionEv = -0.2,
        )

        assertEquals(10.6, previewEv, 0.0001)
    }

    @Test
    fun brighterSelectedExposureRequestsPositiveAeCompensation() {
        val requested = ExposurePreviewMath.requestedCompensationEv(
            ExposurePreviewSelection(
                previewCalibratedSceneEv100 = 12.0,
                selectedExposureEv100 = 11.0,
                previewCorrectionEv = 0.0,
            ),
        )

        assertEquals(1.0, requested, 0.0001)
    }

    @Test
    fun selectedEvIsConvertedToPreviewCameraCalibrationDomain() {
        val target = ExposurePreviewMath.targetCameraEv100(
            ExposurePreviewSelection(
                previewCalibratedSceneEv100 = 12.0,
                selectedExposureEv100 = 11.0,
                previewCorrectionEv = 0.4,
            ),
        )

        assertEquals(10.6, target, 0.0001)
    }

    @Test
    fun manualPreviewExposureUsesTargetEvAndRespectsSensorRanges() {
        val exposure = ExposurePreviewMath.manualExposure(
            targetCameraEv100 = 10.0,
            cameraAperture = 2.0,
            preferredSensitivity = 100,
            minimumExposureTimeNs = 100_000L,
            maximumExposureTimeNs = 1_000_000_000L,
            minimumSensitivity = 50,
            maximumSensitivity = 3_200,
        )!!

        assertEquals(3_906_250L, exposure.exposureTimeNs)
        assertEquals(100, exposure.sensitivity)
        assertEquals(10.0, exposure.appliedCameraEv100, 0.0001)
        assertFalse(exposure.clamped)
    }

    @Test
    fun manualPreviewExposureMovesIsoBeforeClamping() {
        val exposure = ExposurePreviewMath.manualExposure(
            targetCameraEv100 = -2.0,
            cameraAperture = 2.0,
            preferredSensitivity = 100,
            minimumExposureTimeNs = 100_000L,
            maximumExposureTimeNs = 1_000_000_000L,
            minimumSensitivity = 50,
            maximumSensitivity = 800,
        )!!

        assertEquals(800, exposure.sensitivity)
        assertEquals(1_000_000_000L, exposure.exposureTimeNs)
        assertTrue(exposure.clamped)
    }

    @Test
    fun cameraStepsAreRoundedAndClampedToAdvertisedRange() {
        val withinRange = ExposurePreviewMath.quantizeCompensation(
            requestedEv = 0.8,
            minimumSteps = -6,
            maximumSteps = 6,
            stepEv = 1.0 / 3.0,
        )
        assertEquals(2, withinRange.steps)
        assertEquals(2.0 / 3.0, withinRange.appliedEv, 0.0001)
        assertFalse(withinRange.clamped)

        val clamped = ExposurePreviewMath.quantizeCompensation(
            requestedEv = 4.0,
            minimumSteps = -6,
            maximumSteps = 6,
            stepEv = 1.0 / 3.0,
        )
        assertEquals(6, clamped.steps)
        assertEquals(2.0, clamped.appliedEv, 0.0001)
        assertTrue(clamped.clamped)
    }

    @Test
    fun zeroRangeMeansExposurePreviewIsUnsupported() {
        val result = ExposurePreviewMath.quantizeCompensation(
            requestedEv = 1.0,
            minimumSteps = 0,
            maximumSteps = 0,
            stepEv = 1.0 / 3.0,
        )

        assertFalse(result.supported)
        assertEquals(0, result.steps)
    }
}
