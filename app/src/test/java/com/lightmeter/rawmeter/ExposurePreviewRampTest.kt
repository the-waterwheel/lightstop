package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ExposurePreviewRampTest {
    @Test fun brighteningAndDarkeningReachTheirTargetsWithBoundedMonotonicSteps() {
        for ((from, target) in listOf(8.0 to 12.0, 12.0 to 8.0, -2.0 to 14.0)) {
            val ramp = ExposurePreviewRamp()
            var timestamp = 1_000_000_000L
            ramp.start(from, target, timestamp)
            var previous = from
            var intermediateFrames = 0
            repeat(180) {
                timestamp += 33_333_334L
                val ev = ramp.advance(timestamp)
                assertTrue(abs(ev - previous) <= .201)
                assertTrue(if (target > from) ev >= previous else ev <= previous)
                if (ev != from && ev != target) intermediateFrames++
                previous = ev
            }
            assertTrue(intermediateFrames > 8)
            assertEquals(target, ramp.value, .00001)
            assertFalse(ramp.running)
        }
    }

    @Test fun frozenPreviewAndDuplicateFramesNeverSkipTheTransition() {
        val ramp = ExposurePreviewRamp()
        ramp.start(8.0, 12.0, 1_000_000_000L)
        val value = ramp.advance(1_033_333_334L)
        assertEquals(value, ramp.advance(1_033_333_334L), 0.0)
        assertEquals(value, ramp.advance(1_000_000_000L), 0.0)
        assertTrue(ramp.advance(31_000_000_000L) - value <= .301)
        assertTrue(ramp.value < 9.0)
    }

    @Test fun retargetStartsAtTheCurrentValueAndCanReverseWithoutAnExposureJump() {
        val ramp = ExposurePreviewRamp()
        ramp.start(8.0, 12.0, 1_000_000_000L)
        val current = ramp.advance(1_050_000_000L)
        ramp.start(current, 6.0, 1_050_000_000L)
        assertEquals(current, ramp.value, 0.0)
        assertTrue(ramp.advance(1_083_333_334L) < current)
        ramp.cancel()
        val cancelled = ramp.value
        assertEquals(cancelled, ramp.advance(5_000_000_000L), 0.0)
    }

    @Test fun manualInterpolationUsesStopsAndPreservesTheShutterIsoProduct() {
        val from = exposure(10_000_000L, 100)
        val target = exposure(40_000_000L, 400)
        val midpoint = ExposurePreviewRamp.manualAt(from, target, (from.appliedCameraEv100 + target.appliedCameraEv100) / 2, 2.0)
        assertEquals(20_000_000L, midpoint.exposureTimeNs)
        assertEquals(200, midpoint.sensitivity)
        assertEquals(2.0, from.appliedCameraEv100 - midpoint.appliedCameraEv100, .0001)
        assertEquals(from, ExposurePreviewRamp.manualAt(from, target, from.appliedCameraEv100, 2.0))
        assertEquals(target, ExposurePreviewRamp.manualAt(from, target, target.appliedCameraEv100, 2.0))
    }

    @Test fun aeValuesOutsideTheManualIsoRangeAreConvertedWithoutChangingBrightness() {
        val source = exposure(20_000_000L, 50)
        val seed = ExposurePreviewRamp.legalSeed(source, 2.0, 100_000L, 1_000_000_000L, 100, 3200)
        assertEquals(100, seed.sensitivity)
        assertEquals(10_000_000L, seed.exposureTimeNs)
        assertEquals(source.appliedCameraEv100, seed.appliedCameraEv100, .00001)
    }

    private fun exposure(time: Long, iso: Int) = ExposurePreviewManualExposure(time, iso,
        ExposurePreviewMath.cameraEv100(2.0, time, iso)!!, false)
}
