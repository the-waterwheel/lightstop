package com.lightmeter.rawmeter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneRapidMotionTest {
    @Test
    fun calmFrameDoesNotEnterRapidMotionRecovery() {
        assertFalse(
            isRapidZoneMotion(
                prediction(dx = 5f, dy = 4f, screenX = 0.004f, screenY = 0.003f),
                width = 512,
                height = 384,
            ),
        )
    }

    @Test
    fun largePredictedFrameTranslationEntersRapidMotionRecovery() {
        assertTrue(
            isRapidZoneMotion(
                prediction(dx = 14f),
                width = 512,
                height = 384,
            ),
        )
    }

    @Test
    fun highAngularVelocityEntersRapidMotionRecoveryBeforeCalibration() {
        assertTrue(
            isRapidZoneMotion(
                prediction(screenX = 0.013f),
                width = 512,
                height = 384,
            ),
        )
    }

    private fun prediction(
        dx: Float = 0f,
        dy: Float = 0f,
        roll: Float = 0f,
        screenX: Float = 0f,
        screenY: Float = 0f,
    ) = MotionPrediction(
        dx = dx,
        dy = dy,
        rollRadians = roll,
        screenXRotation = screenX,
        screenYRotation = screenY,
        displayHorizontalScale = 0.75f,
        displayVerticalScale = 1.0f,
        angularHorizontalScale = 0.75f,
        angularVerticalScale = 1.0f,
        displayOriented = true,
        xTranslationTrusted = false,
        yTranslationTrusted = false,
    )
}
