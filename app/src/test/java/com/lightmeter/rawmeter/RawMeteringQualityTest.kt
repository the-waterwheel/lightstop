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
            frameLumas = listOf(0.0, 0.3),
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
}
