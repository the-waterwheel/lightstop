package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureCompensationTest {
    @Test
    fun `changing step snaps to nearest compatible value without losing sign`() {
        assertEquals(2, ExposureCompensationDial.snap(1, ExposureCompensationStep.THIRD))
        assertEquals(-2, ExposureCompensationDial.snap(-1, ExposureCompensationStep.THIRD))
        assertEquals(6, ExposureCompensationDial.snap(5, ExposureCompensationStep.HALF))
        assertEquals(-6, ExposureCompensationDial.snap(-5, ExposureCompensationStep.FULL))
    }

    @Test
    fun `one detent uses the selected fraction`() {
        assertEquals(1, ExposureCompensationDial.applyDetents(0, 1, ExposureCompensationStep.SIXTH, false))
        assertEquals(2, ExposureCompensationDial.applyDetents(0, 1, ExposureCompensationStep.THIRD, false))
        assertEquals(3, ExposureCompensationDial.applyDetents(0, 1, ExposureCompensationStep.HALF, false))
        assertEquals(6, ExposureCompensationDial.applyDetents(0, 1, ExposureCompensationStep.FULL, false))
    }

    @Test
    fun `dial rotation mirrors with handedness`() {
        assertEquals(2, ExposureCompensationDial.applyDetents(0, 1, ExposureCompensationStep.THIRD, false))
        assertEquals(-2, ExposureCompensationDial.applyDetents(0, 1, ExposureCompensationStep.THIRD, true))
    }

    @Test
    fun `positive ticks are above and negative ticks below on both sides`() {
        val rightPositive = ExposureCompensationDial.relativeTickAngleDegrees(1, 0, ExposureCompensationStep.SIXTH, false, 8f)
        val rightNegative = ExposureCompensationDial.relativeTickAngleDegrees(-1, 0, ExposureCompensationStep.SIXTH, false, 8f)
        val leftPositive = ExposureCompensationDial.relativeTickAngleDegrees(1, 0, ExposureCompensationStep.SIXTH, true, 8f)
        val leftNegative = ExposureCompensationDial.relativeTickAngleDegrees(-1, 0, ExposureCompensationStep.SIXTH, true, 8f)

        assertTrue(rightPositive < 0f)
        assertTrue(rightNegative > 0f)
        // Around the left-side 180-degree base, positive offsets point upward on Android canvas.
        assertTrue(leftPositive > 0f)
        assertTrue(leftNegative < 0f)
    }
}
