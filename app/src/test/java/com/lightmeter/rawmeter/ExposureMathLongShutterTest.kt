package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureMathLongShutterTest {
    @Test
    fun `long shutter values continue past last printed tick`() {
        val target = ExposureMath.maxMarkedShutterLogSeconds + 1.1
        val snapped = ExposureMath.nearestShutterLogSeconds(target, ExposureStep.THIRD)

        assertTrue(snapped > ExposureMath.maxMarkedShutterLogSeconds)
        assertTrue(ExposureMath.shutterValueForCoordinate(snapped, ExposureStep.THIRD) > 30.0)
        assertTrue(ExposureMath.shutterTicks(ExposureStep.THIRD).none { it.coordinate > ExposureMath.maxMarkedShutterLogSeconds })
    }

    @Test
    fun `long shutter snapping follows selected stop interval`() {
        val target = ExposureMath.maxMarkedShutterLogSeconds + 1.4

        assertEquals(6.0, ExposureMath.nearestShutterLogSeconds(target, ExposureStep.FULL), 0.0001)
        assertEquals(6.5, ExposureMath.nearestShutterLogSeconds(target, ExposureStep.HALF), 0.0001)
        assertEquals(19.0 / 3.0, ExposureMath.nearestShutterLogSeconds(target, ExposureStep.THIRD), 0.0001)
    }
}
