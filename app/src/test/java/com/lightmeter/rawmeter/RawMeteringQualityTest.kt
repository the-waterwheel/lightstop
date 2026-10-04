package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawMeteringQualityTest {
    @Test
    fun `stable frames with enough samples are accepted`() {
        val assessment = RawMeteringQualityPolicy.assess(
            frameLumas = listOf(1.0, 1.01, 0.99),
            saturatedChannelCount = 0,
            sampleCount = 1024,
        )
        assertEquals(RawMeteringQualityAction.ACCEPT, assessment.action)
        assertEquals(false, assessment.systematicBias)
    }

    @Test
    fun `systematic saturation asks for a new exposure`() {
        val assessment = RawMeteringQualityPolicy.assess(
            frameLumas = listOf(1.0, 1.0),
            saturatedChannelCount = RawExposureRetryPolicy.REQUIRED_SATURATED_CHANNELS,
            sampleCount = 1024,
        )
        assertEquals(RawMeteringQualityAction.CHANGE_EXPOSURE, assessment.action)
        assertEquals(true, assessment.systematicBias)
    }

    @Test
    fun `noisy frames ask for more frames at the same exposure`() {
        val assessment = RawMeteringQualityPolicy.assess(
            frameLumas = listOf(0.1, 0.3),
            saturatedChannelCount = 0,
            sampleCount = 1024,
        )
        assertEquals(RawMeteringQualityAction.APPEND_SAME_EXPOSURE, assessment.action)
    }

    @Test
    fun `too few samples are rejected`() {
        val assessment = RawMeteringQualityPolicy.assess(
            frameLumas = listOf(1.0),
            saturatedChannelCount = 0,
            sampleCount = RawMeteringQualityPolicy.MIN_SAMPLES - 1,
        )
        assertEquals(RawMeteringQualityAction.REJECT, assessment.action)
        assertNull(assessment.randomNoiseStops)
    }

    @Test
    fun `noisy frames append one more same-exposure frame within the cap`() {
        val noisy = listOf(0.1, 0.3)
        assertEquals(
            true,
            RawMeteringQualityPolicy.shouldAppendSameExposure(
                framesCaptured = 2,
                maxFrames = 3,
                noiseStops = RawMeteringQualityPolicy.frameNoiseStops(noisy),
            ),
        )
        assertEquals(
            false,
            RawMeteringQualityPolicy.shouldAppendSameExposure(
                framesCaptured = 3,
                maxFrames = 3,
                noiseStops = 0.5,
            ),
        )
    }

    @Test
    fun `stable frames never append`() {
        assertEquals(
            false,
            RawMeteringQualityPolicy.shouldAppendSameExposure(
                framesCaptured = 1,
                maxFrames = 2,
                noiseStops = RawMeteringQualityPolicy.frameNoiseStops(listOf(1.0, 1.01)),
            ),
        )
        assertEquals(
            false,
            RawMeteringQualityPolicy.shouldAppendSameExposure(
                framesCaptured = 1,
                maxFrames = 2,
                noiseStops = null,
            ),
        )
    }

    @Test
    fun `noise is measured in EV and rejects invalid luminance`() {
        assertEquals(1.0, RawMeteringQualityPolicy.frameNoiseStops(listOf(1.0, 2.0))!!, 1e-9)
        assertEquals(1.0, RawMeteringQualityPolicy.frameNoiseStops(listOf(10.0, 20.0))!!, 1e-9)
        assertNull(RawMeteringQualityPolicy.frameNoiseStops(listOf(0.0, -1.0, Double.NaN)))
    }
}
