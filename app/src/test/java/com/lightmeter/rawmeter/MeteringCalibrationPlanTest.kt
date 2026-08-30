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
        val ispFallback = MeterReading(
            sceneEv100 = 9.8,
            rawLuma = 0.2,
            clippedFraction = 0.0,
            frameCount = 1,
            captureIso = 100,
            exposureTimeNs = 10_000_000,
            aperture = 2.0f,
            source = MeteringSource.ISP_PREVIEW,
        )

        assertNull(run.accept(ispFallback))
        assertEquals(mapOf(MeteringSource.ISP_PREVIEW to 9.8), run.measurements)
        org.junit.Assert.assertTrue(run.hasFailures)
    }
}
