package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeteringCalibrationPlanTest {
    private val allSources = MeteringCalibrationCapabilities(rawAvailable = true, yuvAvailable = true)

    @Test
    fun `auto calibrates raw then yuv then displayed isp`() {
        assertEquals(
            listOf(MeteringSource.RAW, MeteringSource.YUV_PREVIEW, MeteringSource.ISP_PREVIEW),
            MeteringCalibrationPlan.create(MeteringPipelineMode.AUTO, allSources),
        )
    }

    @Test
    fun `isolated calibration still records every hardware backed source`() {
        assertEquals(
            listOf(MeteringSource.RAW, MeteringSource.YUV_PREVIEW, MeteringSource.ISP_PREVIEW),
            MeteringCalibrationPlan.create(MeteringPipelineMode.ISOLATED, allSources),
        )
    }

    @Test
    fun `fast calibration still records raw for later pipeline changes`() {
        assertEquals(
            listOf(MeteringSource.RAW, MeteringSource.YUV_PREVIEW, MeteringSource.ISP_PREVIEW),
            MeteringCalibrationPlan.create(MeteringPipelineMode.FAST, allSources),
        )
    }

    @Test
    fun `unsupported hardware sources are omitted without changing the isp fallback`() {
        assertEquals(
            listOf(MeteringSource.ISP_PREVIEW),
            MeteringCalibrationPlan.create(
                MeteringPipelineMode.AUTO,
                MeteringCalibrationCapabilities(rawAvailable = false, yuvAvailable = false),
            ),
        )
    }

    @Test
    fun `a yuv fallback result skips duplicated isp stage`() {
        val run = MeteringCalibrationRun(
            referenceEv100 = 10.0,
            sources = listOf(MeteringSource.YUV_PREVIEW, MeteringSource.ISP_PREVIEW),
        )
        val ispFallback = reading(
            source = MeteringSource.ISP_PREVIEW,
            ev100 = 9.8,
            sample = sample(MeteringSource.ISP_PREVIEW, before = 9.8),
        )

        assertNull(run.accept(ispFallback))
        assertEquals(mapOf(MeteringSource.ISP_PREVIEW to 9.8), run.measurements)
        org.junit.Assert.assertTrue(run.hasFailures)
    }

    @Test
    fun `a reading without usable calibration evidence is not recorded as successful`() {
        val run = MeteringCalibrationRun(
            referenceEv100 = 10.0,
            sources = listOf(MeteringSource.RAW),
        )
        val invalid = reading(
            source = MeteringSource.RAW,
            ev100 = 9.8,
            sample = CalibrationMeasurementSample(
                source = MeteringSource.RAW,
                cameraId = "review-camera",
                signature = null,
                ev100BeforeUserCalibration = 9.8,
                appliedUserCorrectionEv = Double.NaN,
            ),
        )

        assertNull(run.accept(invalid))
        assertEquals(emptyMap<MeteringSource, Double>(), run.measurements)
        assertEquals(emptyMap<MeteringSource, CalibrationMeasurementSample>(), run.samples)
        org.junit.Assert.assertTrue(run.hasFailures)
    }

    private fun reading(
        source: MeteringSource,
        ev100: Double,
        sample: CalibrationMeasurementSample?,
    ) = MeterReading(
        sceneEv100 = ev100,
        rawLuma = 0.2,
        clippedFraction = 0.0,
        frameCount = 1,
        captureIso = 100,
        exposureTimeNs = 10_000_000,
        aperture = 2.0f,
        source = source,
        calibrationSample = sample,
    )

    private fun sample(source: MeteringSource, before: Double) = CalibrationMeasurementSample(
        source = source,
        cameraId = "review-camera",
        signature = CalibrationSignature(
            schemaVersion = CalibrationSignature.CURRENT_SCHEMA_VERSION,
            algorithmVersion = CalibrationSignature.CURRENT_ALGORITHM_VERSION,
            domain = CalibrationDomain.forSource(source),
            buildFingerprintHash = "build",
            cameraInfoVersion = null,
            selectionRouteId = "0",
            logicalCameraId = "0",
            configuredPhysicalCameraId = null,
            confirmedPhysicalCameraId = null,
            routeKind = CameraRouteKind.LOGICAL_AUTO,
            outputWidth = 100,
            outputHeight = 100,
            outputFormat = 0,
        ),
        ev100BeforeUserCalibration = before,
        appliedUserCorrectionEv = 0.0,
    )
}
