package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class RawMeteringPolicyTest {
    @Test
    fun `raw samples increase at 500 and 1200 iso boundaries`() {
        assertEquals(2, RawMeteringPolicy.frameCount(null))
        assertEquals(1, RawMeteringPolicy.frameCount(100))
        assertEquals(1, RawMeteringPolicy.frameCount(499))
        assertEquals(2, RawMeteringPolicy.frameCount(500))
        assertEquals(2, RawMeteringPolicy.frameCount(1199))
        assertEquals(3, RawMeteringPolicy.frameCount(1200))
        assertEquals(3, RawMeteringPolicy.frameCount(6400))
        assertEquals(1, CompatibleMeteringPolicy.FRAME_COUNT)
    }
}
