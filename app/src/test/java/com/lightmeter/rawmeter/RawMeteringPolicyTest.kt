package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class RawMeteringPolicyTest {
    @Test
    fun `high iso uses additional raw samples while compatible path remains single frame`() {
        assertEquals(3, RawMeteringPolicy.frameCount(null))
        assertEquals(3, RawMeteringPolicy.frameCount(800))
        assertEquals(5, RawMeteringPolicy.frameCount(801))
        assertEquals(1, CompatibleMeteringPolicy.FRAME_COUNT)
    }
}
