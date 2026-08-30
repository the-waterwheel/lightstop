package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteringCalibrationCoordinatorTest {
    @Test
    fun `lens switch cancels without returning a mixed-source result`() {
        val coordinator = MeteringCalibrationCoordinator()
        coordinator.start(
            10.0,
            listOf(MeteringSource.RAW, MeteringSource.ISP_PREVIEW),
            identity("0@main", "main"),
        )

        val transition = coordinator.onReading(
            reading(MeteringSource.RAW),
            identity("0@tele", "tele"),
        )

        assertEquals(MeteringCalibrationTransition.LensChanged, transition)
        assertTrue(!coordinator.isActive)
    }

    @Test
    fun `failed raw continues and preserves a later isp result`() {
        val coordinator = MeteringCalibrationCoordinator()
        coordinator.start(
            10.0,
            listOf(MeteringSource.RAW, MeteringSource.ISP_PREVIEW),
            identity("0", "main"),
        )

        assertEquals(
            MeteringCalibrationTransition.Next(MeteringCalibrationStep(MeteringSource.ISP_PREVIEW, 2, 2)),
            coordinator.onStageError("raw failed"),
        )
        val completion = coordinator.onReading(
            reading(MeteringSource.ISP_PREVIEW),
            identity("0", "main"),
        )
            as MeteringCalibrationTransition.Complete

        assertEquals(mapOf(MeteringSource.ISP_PREVIEW to 9.5), completion.result.measurements)
        assertTrue(completion.result.hasFailures)
    }

    @Test
    fun `temporary missing active physical id after reopen does not cancel calibration`() {
        val coordinator = MeteringCalibrationCoordinator()
        coordinator.start(
            10.0,
            listOf(MeteringSource.RAW, MeteringSource.ISP_PREVIEW),
            identity("0", "2"),
        )

        assertEquals(
            MeteringCalibrationTransition.Next(MeteringCalibrationStep(MeteringSource.ISP_PREVIEW, 2, 2)),
            coordinator.onReading(reading(MeteringSource.RAW), identity("0", null)),
        )
        val completion = coordinator.onReading(
            reading(MeteringSource.ISP_PREVIEW),
            identity("0", "2"),
        )

        assertTrue(completion is MeteringCalibrationTransition.Complete)
    }

    @Test
    fun `two different concrete physical ids still cancel calibration`() {
        val coordinator = MeteringCalibrationCoordinator()
        coordinator.start(
            10.0,
            listOf(MeteringSource.RAW, MeteringSource.ISP_PREVIEW),
            identity("0", "2"),
        )

        assertEquals(
            MeteringCalibrationTransition.LensChanged,
            coordinator.onReading(reading(MeteringSource.RAW), identity("0", "3")),
        )
    }

    private fun identity(route: String, physical: String?) = CalibrationCaptureIdentity(
        routeId = route,
        activePhysicalCameraId = physical,
    )

    private fun reading(source: MeteringSource) = MeterReading(
        sceneEv100 = 9.5,
        rawLuma = 0.18,
        clippedFraction = 0.0,
        frameCount = 1,
        captureIso = 100,
        exposureTimeNs = 10_000_000,
        aperture = 2.0f,
        source = source,
    )
}
