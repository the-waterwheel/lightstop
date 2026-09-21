package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawExposureRetryPolicyTest {
    private fun input(
        saturatedChannelCount: Int,
        currentStage: Int,
        clippedFraction: Double = 0.0,
    ) = RawExposureRetryInput(
        saturatedChannelCount = saturatedChannelCount,
        currentStage = currentStage,
        clippedFraction = clippedFraction,
    )

    @Test
    fun `distorted statistics trigger at most two three-EV recaptures`() {
        val saturated = RawExposureRetryPolicy.REQUIRED_SATURATED_CHANNELS

        assertEquals(1, RawExposureRetryPolicy.nextStage(input(saturated, 0)))
        assertEquals(2, RawExposureRetryPolicy.nextStage(input(saturated, 1)))
        assertNull(RawExposureRetryPolicy.nextStage(input(saturated, 2)))
        assertEquals(3, RawExposureRetryPolicy.exposureReductionEv(1))
        assertEquals(6, RawExposureRetryPolicy.exposureReductionEv(2))
        assertEquals(1, RawExposureRetryPolicy.SINGLE_FRAME_COUNT)
    }

    @Test
    fun `a small clipped fraction alone never triggers a recapture`() {
        assertNull(RawExposureRetryPolicy.nextStage(input(0, 0, clippedFraction = 0.02)))
        assertNull(RawExposureRetryPolicy.nextStage(input(1, 0, clippedFraction = 0.5)))
    }

    @Test
    fun `valid statistics are accepted regardless of clipped fraction`() {
        assertNull(RawExposureRetryPolicy.nextStage(input(0, 0, clippedFraction = 0.0)))
        assertNull(
            RawExposureRetryPolicy.nextStage(
                input(1, 0, clippedFraction = RawExposureRetryPolicy.CHANNEL_SATURATION_LEVEL),
            ),
        )
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
