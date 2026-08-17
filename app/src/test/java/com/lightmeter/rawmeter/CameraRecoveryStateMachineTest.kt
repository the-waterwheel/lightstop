package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRecoveryStateMachineTest {
    @Test
    fun `tracks failure attempts and recovery budget`() {
        val state = stateMachine(maxAttempts = 2)
        state.resolveProfile(MeteringPipelineMode.AUTO, rawSupported = true, trackingSupported = true)

        val first = state.decideFailure(
            CameraFailureKind.DEVICE,
            CameraFailureStage.OPENING,
            MeteringPipelineMode.AUTO,
            rawSupported = true,
            trackingSupported = true,
        )
        val second = state.decideFailure(
            CameraFailureKind.DEVICE,
            CameraFailureStage.OPENING,
            MeteringPipelineMode.AUTO,
            rawSupported = true,
            trackingSupported = true,
        )

        assertEquals(CameraRecoveryAction.RETRY, first.action)
        assertEquals(CameraRecoveryAction.DOWNGRADE, second.action)
        assertTrue(state.beginRecovery(CameraSessionProfile.RAW_ONLY))
        assertTrue(state.beginRecovery(CameraSessionProfile.COMPATIBLE))
        assertFalse(state.beginRecovery(CameraSessionProfile.PREVIEW_ONLY))
    }

    @Test
    fun `logical fallback happens once and reset restores initial route`() {
        val state = stateMachine()

        assertFalse(state.enableLogicalCameraFallback(hasPhysicalSelection = false))
        assertTrue(state.enableLogicalCameraFallback(hasPhysicalSelection = true))
        assertFalse(state.enableLogicalCameraFallback(hasPhysicalSelection = true))
        assertTrue(state.usesLogicalCameraFallback)
        assertEquals(CameraSessionProfile.PREVIEW_ONLY, state.profile)

        state.reset()

        assertFalse(state.usesLogicalCameraFallback)
        assertNull(state.profile)
    }

    @Test
    fun `two consecutive raw failures request compatible pipeline until raw succeeds`() {
        val state = stateMachine(rawFailures = 2)

        assertFalse(state.recordRawMeasurementFailed(MeteringPipelineMode.AUTO))
        assertTrue(state.recordRawMeasurementFailed(MeteringPipelineMode.AUTO))
        state.recordRawMeasurementSucceeded()
        assertFalse(state.recordRawMeasurementFailed(MeteringPipelineMode.AUTO))
        assertFalse(state.recordRawMeasurementFailed(MeteringPipelineMode.ISOLATED))
        assertFalse(state.recordRawMeasurementFailed(MeteringPipelineMode.FAST))
    }

    private fun stateMachine(maxAttempts: Int = 6, rawFailures: Int = 2) =
        CameraRecoveryStateMachine(maxAttempts, rawFailures)
}
