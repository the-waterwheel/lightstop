package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFrameRateControllerTest {
    @Test
    fun `normal light keeps the requested smooth fixed range`() {
        assertEquals(
            60 to 60,
            CameraFrameRatePolicy.selectRangeBounds(
                ranges = listOf(15 to 30, 30 to 30, 30 to 60, 60 to 60),
                requestedCeiling = 60,
                lowLight = false,
            ),
        )
    }

    @Test
    fun `low light prefers an advertised variable range capped at thirty`() {
        assertEquals(
            15 to 30,
            CameraFrameRatePolicy.selectRangeBounds(
                ranges = listOf(10 to 30, 15 to 30, 30 to 30, 30 to 60, 60 to 60),
                requestedCeiling = 60,
                lowLight = true,
            ),
        )
    }

    @Test
    fun `low light uses an advertised fixed fifteen range when variable is unavailable`() {
        assertEquals(
            15 to 15,
            CameraFrameRatePolicy.selectRangeBounds(
                ranges = listOf(15 to 15, 24 to 24, 30 to 30),
                requestedCeiling = 30,
                lowLight = true,
            ),
        )
    }

    @Test
    fun `never invents or exceeds an advertised user ceiling`() {
        assertNull(
            CameraFrameRatePolicy.selectRangeBounds(
                ranges = listOf(30 to 60, 60 to 60),
                requestedCeiling = 24,
                lowLight = true,
            ),
        )
    }

    @Test
    fun `exposure metadata produces camera EV at ISO one hundred`() {
        val ev = PreviewExposureValue.ev100(
            exposureTimeNs = 3_906_250L,
            sensitivity = 100,
            aperture = 2f,
        )

        assertEquals(10.0, requireNotNull(ev), 0.0001)
    }

    @Test
    fun `dark and bright transitions require separate hysteresis windows`() {
        val monitor = PreviewLowLightMonitor(
            enterThresholdEv100 = 5.0,
            exitThresholdEv100 = 6.25,
            enterDurationNs = 750_000_000L,
            exitDurationNs = 2_000_000_000L,
        )

        assertFalse(monitor.observe(4.0, 1_000_000_000L, false))
        assertFalse(monitor.observe(4.0, 1_700_000_000L, false))
        assertTrue(monitor.observe(4.0, 1_800_000_000L, false))
        assertTrue(monitor.isLowLight)

        assertFalse(monitor.observe(7.0, 2_000_000_000L, false))
        assertFalse(monitor.observe(7.0, 3_900_000_000L, false))
        assertTrue(monitor.observe(7.0, 4_100_000_000L, false))
        assertFalse(monitor.isLowLight)
    }

    @Test
    fun `flash-required AE can enter low light but still observes hysteresis`() {
        val monitor = PreviewLowLightMonitor(enterDurationNs = 500_000_000L)

        assertFalse(monitor.observe(7.0, 1_000_000_000L, true))
        assertTrue(monitor.observe(7.0, 1_600_000_000L, true))
        assertTrue(monitor.isLowLight)
    }

    @Test
    fun `controller resets compatibility ceiling for a new camera`() {
        val controller = PreviewFrameRateController(PreviewFrameRateMode.HIGH)

        assertTrue(controller.useNextCompatibilityCeiling())
        assertEquals(30, controller.requestCeiling)
        controller.resetForCamera()

        assertEquals(60, controller.requestCeiling)
        assertFalse(controller.isLowLight)
    }
}
