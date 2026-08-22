package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawHighlightProtectionTest {
    @Test
    fun `clipping triggers at most two three-EV recaptures`() {
        val clipped = RawHighlightProtectionPolicy.CLIPPED_FRACTION_THRESHOLD + 0.001

        assertEquals(1, RawHighlightProtectionPolicy.nextStage(clipped, 0))
        assertEquals(2, RawHighlightProtectionPolicy.nextStage(clipped, 1))
        assertNull(RawHighlightProtectionPolicy.nextStage(clipped, 2))
        assertEquals(3, RawHighlightProtectionPolicy.exposureReductionEv(1))
        assertEquals(6, RawHighlightProtectionPolicy.exposureReductionEv(2))
        assertEquals(1, RawHighlightProtectionPolicy.SINGLE_FRAME_COUNT)
    }

    @Test
    fun `noise-level or invalid clipping does not recapture`() {
        assertNull(RawHighlightProtectionPolicy.nextStage(0.0, 0))
        assertNull(
            RawHighlightProtectionPolicy.nextStage(
                RawHighlightProtectionPolicy.CLIPPED_FRACTION_THRESHOLD,
                0,
            ),
        )
        assertNull(RawHighlightProtectionPolicy.nextStage(Double.NaN, 0))
    }

    @Test
    fun `planner shortens shutter while preserving ISO`() {
        val plan = ExposureReductionPlanner.plan(
            baseExposureTimeNs = 10_000_000L,
            baseSensitivity = 400,
            reductionEv = 3,
            minimumExposureTimeNs = 100_000L,
            maximumExposureTimeNs = 1_000_000_000L,
            minimumSensitivity = 50,
            maximumSensitivity = 6_400,
        )

        assertEquals(1_250_000L, plan.exposureTimeNs)
        assertEquals(400, plan.sensitivity)
    }

    @Test
    fun `planner uses ISO after reaching minimum shutter time`() {
        val plan = ExposureReductionPlanner.plan(
            baseExposureTimeNs = 10_000_000L,
            baseSensitivity = 800,
            reductionEv = 6,
            minimumExposureTimeNs = 1_000_000L,
            maximumExposureTimeNs = 1_000_000_000L,
            minimumSensitivity = 50,
            maximumSensitivity = 6_400,
        )

        assertEquals(1_000_000L, plan.exposureTimeNs)
        assertEquals(125, plan.sensitivity)
    }

    @Test
    fun `planner remains inside sensor limits when full reduction is impossible`() {
        val plan = ExposureReductionPlanner.plan(
            baseExposureTimeNs = 1_000_000L,
            baseSensitivity = 100,
            reductionEv = 6,
            minimumExposureTimeNs = 1_000_000L,
            maximumExposureTimeNs = 1_000_000_000L,
            minimumSensitivity = 100,
            maximumSensitivity = 3_200,
        )

        assertEquals(1_000_000L, plan.exposureTimeNs)
        assertEquals(100, plan.sensitivity)
    }
}
