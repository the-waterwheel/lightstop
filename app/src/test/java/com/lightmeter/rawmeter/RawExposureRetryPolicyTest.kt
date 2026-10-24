package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawExposureRetryPolicyTest {
    private fun input(
        saturatedChannelCount: Int,
        currentStage: Int,
        clippedFraction: Double = 0.0,
        mode: MeteringMode = MeteringMode.SPOT,
    ) = RawExposureRetryInput(
        saturatedChannelCount = saturatedChannelCount,
        currentStage = currentStage,
        clippedFraction = clippedFraction,
        meteringMode = mode,
    )

    @Test
    fun `all local modes keep taking five EV steps until the metering point clears`() {
        for (mode in listOf(MeteringMode.SPOT, MeteringMode.CENTER_WEIGHTED, MeteringMode.ANGLE)) {
            for (stage in 0 until 20) {
                val next = RawExposureRetryPolicy.nextStage(input(1, stage, mode = mode))!!
                assertEquals(stage + 1, next)
                assertEquals((stage + 1) * 5, RawExposureRetryPolicy.exposureReductionEv(next, mode))
                assertNull(RawExposureRetryPolicy.nextStage(input(0, stage, mode = mode)))
            }
        }
        assertEquals(1, RawExposureRetryPolicy.SINGLE_FRAME_COUNT)
    }

    @Test
    fun `a small clipped fraction alone never triggers a recapture`() {
        assertNull(RawExposureRetryPolicy.nextStage(input(0, 0, clippedFraction = 0.02)))
        assertNull(RawExposureRetryPolicy.nextStage(input(0, 0, clippedFraction = 0.5)))
    }

    @Test
    fun `valid statistics are accepted regardless of clipped fraction`() {
        assertNull(RawExposureRetryPolicy.nextStage(input(0, 0, clippedFraction = 0.0)))
        assertNull(
            RawExposureRetryPolicy.nextStage(
                input(0, 0, clippedFraction = RawExposureRetryPolicy.CHANNEL_SATURATION_LEVEL),
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

    @Test
    fun `average recaptures keep three EV steps and stop at the actual manual control floor`() {
        fun plan(stage: Int) = ExposureReductionPlanner.plan(
            baseExposureTimeNs = 64_000_000L,
            baseSensitivity = 800,
            reductionEv = RawExposureRetryPolicy.exposureReductionEv(stage, MeteringMode.AVERAGE),
            minimumExposureTimeNs = 1_000_000L,
            maximumExposureTimeNs = 1_000_000_000L,
            minimumSensitivity = 50,
            maximumSensitivity = 6_400,
        )
        assertEquals(ManualExposurePlan(64_000_000L, 800), plan(0))
        assertEquals(ManualExposurePlan(8_000_000L, 800), plan(1))
        assertEquals(ManualExposurePlan(1_000_000L, 800), plan(2))
        assertEquals(ManualExposurePlan(1_000_000L, 100), plan(3))
        assertEquals(ManualExposurePlan(1_000_000L, 50), plan(4))
        for (stage in 1..4) assertTrue(RawExposureRetryProgressPolicy.canReduceManual(plan(stage - 1), plan(stage)))
        assertFalse(RawExposureRetryProgressPolicy.canReduceManual(plan(4), plan(5)))
    }

    @Test
    fun `manual recapture checks exposure product when shutter and ISO move in opposite directions`() {
        val current = ManualExposurePlan(1_000_000L, 800)
        assertTrue(RawExposureRetryProgressPolicy.canReduceManual(current, ManualExposurePlan(2_000_000L, 100)))
        assertFalse(RawExposureRetryProgressPolicy.canReduceManual(current, ManualExposurePlan(2_000_000L, 400)))
        assertFalse(RawExposureRetryProgressPolicy.canReduceManual(current, ManualExposurePlan(500_000L, 3200)))
    }

    @Test
    fun `AE recapture stops when compensation is clamped or unavailable`() {
        assertTrue(RawExposureRetryProgressPolicy.canReduceAeCompensation(null, -9))
        assertTrue(RawExposureRetryProgressPolicy.canReduceAeCompensation(-9, -18))
        assertFalse(RawExposureRetryProgressPolicy.canReduceAeCompensation(-18, -18))
        assertFalse(RawExposureRetryProgressPolicy.canReduceAeCompensation(null, 0))
        assertFalse(RawExposureRetryProgressPolicy.canReduceAeCompensation(-18, null))
    }

    @Test
    fun `continuous retry stage arithmetic cannot overflow`() {
        for (mode in MeteringMode.entries) {
            assertNull(RawExposureRetryPolicy.nextStage(RawExposureRetryInput(1, Int.MAX_VALUE, .02, true, mode)))
            assertNull(RawExposureRetryPolicy.nextStage(RawExposureRetryInput(1, -1, .02, true, mode)))
            assertTrue(RawExposureRetryPolicy.exposureReductionEv(Int.MAX_VALUE, mode) > 0)
        }
    }

    @Test
    fun `one clipped color channel triggers a local retry before it can bias luminance`() {
        val medians = doubleArrayOf(0.99, 0.3, 0.3, 0.1, 0.0, 1024.0)
        val count = RawExposureRetryPolicy.saturatedChannelCount(medians)
        assertEquals(1, count)
        assertEquals(1, RawExposureRetryPolicy.nextStage(input(count, 0)))
        assertEquals(0, RawExposureRetryPolicy.saturatedChannelCount(doubleArrayOf(.7, .7, .7, .7)))
    }

    @Test
    fun `local five EV recapture reduces the exposure product by thirty two each time`() {
        fun plan(stage: Int) = ExposureReductionPlanner.plan(
            baseExposureTimeNs = 1_024_000_000L,
            baseSensitivity = 1600,
            reductionEv = RawExposureRetryPolicy.exposureReductionEv(stage),
            minimumExposureTimeNs = 1_000L,
            maximumExposureTimeNs = 2_000_000_000L,
            minimumSensitivity = 50,
            maximumSensitivity = 6_400,
        )
        for (stage in 1..3) {
            val previous = plan(stage - 1)
            val next = plan(stage)
            assertEquals(32.0, previous.exposureTimeNs.toDouble() * previous.sensitivity /
                (next.exposureTimeNs.toDouble() * next.sensitivity), 1e-10)
            assertTrue(RawExposureRetryProgressPolicy.canReduceManual(previous, next))
        }
    }

    @Test
    fun `average uses its bright end statistic independently of local channel saturation`() {
        assertNull(RawExposureRetryPolicy.nextStage(RawExposureRetryInput(4, 0, .5, false, MeteringMode.AVERAGE)))
        assertEquals(1, RawExposureRetryPolicy.nextStage(RawExposureRetryInput(0, 0, .02, true, MeteringMode.AVERAGE)))
        assertNull(RawExposureRetryPolicy.nextStage(RawExposureRetryInput(0, 0, .02, true, MeteringMode.SPOT)))
    }
}
