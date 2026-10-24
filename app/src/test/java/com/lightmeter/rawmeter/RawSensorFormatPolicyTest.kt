package com.lightmeter.rawmeter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSensorFormatPolicyTest {
    @Test
    fun acceptsAllFourBayerArrangements() {
        (0..3).forEach { cfa -> assertTrue(RawSensorFormatPolicy.isBayerCfa(cfa)) }
    }

    @Test
    fun rejectsRgbMonoNirAndUnknownArrangements() {
        listOf(null, 4, 5, 6, 99).forEach { cfa ->
            assertFalse(RawSensorFormatPolicy.isBayerCfa(cfa))
        }
    }

    @Test
    fun rawOutputAndBayerLayoutAreSufficientRegardlessOfCapabilityFlagsOrHardwareLevel() {
        (0..3).forEach { cfa -> assertTrue(RawSensorFormatPolicy.supportsBayerMetering(true, cfa)) }
        assertFalse(RawSensorFormatPolicy.supportsBayerMetering(true, 4))
        assertFalse(RawSensorFormatPolicy.supportsBayerMetering(false, 0))
        assertFalse(RawSensorFormatPolicy.supportsBayerMetering(true, null))
    }
}
