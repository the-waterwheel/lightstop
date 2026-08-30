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
    fun `camera routes advance within bounds and reset restores the first candidate`() {
        val state = stateMachine()

        assertFalse(state.advanceCameraRoute(candidateCount = 1))
        assertTrue(state.advanceCameraRoute(candidateCount = 3))
        assertTrue(state.advanceCameraRoute(candidateCount = 3))
        assertFalse(state.advanceCameraRoute(candidateCount = 3))
        assertEquals(2, state.routeCandidateIndex)
        assertEquals(CameraSessionProfile.PREVIEW_ONLY, state.profile)

        state.reset()

        assertEquals(0, state.routeCandidateIndex)
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

    @Test
    fun `manual safe profile preserves selected route and resets recovery budget`() {
        val state = stateMachine(maxAttempts = 1)
        state.resolveProfile(MeteringPipelineMode.AUTO, rawSupported = true, trackingSupported = true)
        assertTrue(state.advanceCameraRoute(candidateCount = 2))
        assertTrue(state.beginRecovery(CameraSessionProfile.COMPATIBLE))

        state.forceProfile(CameraSessionProfile.PREVIEW_ONLY)

        assertEquals(1, state.routeCandidateIndex)
        assertEquals(CameraSessionProfile.PREVIEW_ONLY, state.profile)
        assertTrue(state.beginRecovery(CameraSessionProfile.PREVIEW_ONLY))
    }

    private fun stateMachine(maxAttempts: Int = 6, rawFailures: Int = 2) =
        CameraRecoveryStateMachine(maxAttempts, rawFailures)
}
