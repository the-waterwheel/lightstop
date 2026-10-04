package com.lightmeter.rawmeter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ZoneRawTransactionCoordinatorTest {
    @Test
    fun lateCompletionCannotClearANewerTransaction() {
        val coordinator = ZoneRawTransactionCoordinator()
        val old = coordinator.begin(plan(), null, CameraUiInfo(cameraId = "0"), null)
        val current = coordinator.begin(plan(), null, CameraUiInfo(cameraId = "0"), null)

        assertFalse(coordinator.clear(old))
        assertSame(current, coordinator.active)
    }

    private fun plan() = MeteringPlan(
        frameFormat = FrameFormat.ALL.first(),
        displayZoom = 1f,
        meteringMode = MeteringMode.CENTER_WEIGHTED,
        target = null,
        meteringAngleDegrees = AngleMeteringMath.DEFAULT_DEGREES,
        requestedSource = MeteringSource.RAW,
        screenAspect = 1f,
        sensorOrientation = 90,
        sensorFrameAspect = 1f,
        meteringRoiFraction = null,
        displayedPreviewReference = null,
    )
}
