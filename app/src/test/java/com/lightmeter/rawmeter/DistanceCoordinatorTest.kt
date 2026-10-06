package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class DistanceCoordinatorTest {
    private val context = DistanceContext(1, "0", true)
    private val unsupportedFocus = FocusDistanceCapability(0f, null, false, true)
    private fun estimate(now: Long) = DistanceEstimate(2.0, 1.8, 2.2, 0.8, DistanceQuality.MEDIUM,
        DistanceSource.MOTION_PARALLAX, now, "0", NormalizedPoint.CENTER, 10, true, receivedAtNs=now)

    @Test fun independentMotionWorksWithoutFocusAndExpiresWithoutCameraFrames() {
        var now = 1_000_000_000L
        var state = DistanceMeasurementState()
        val coordinator = DistanceCoordinator({ state = it }, { now })
        coordinator.startFocusDistance(context, unsupportedFocus)
        coordinator.onMotionEstimate(context, estimate(now))
        assertEquals(DistanceMeasurementStatus.AVAILABLE, state.status)
        now += DISTANCE_TTL_NS+1
        coordinator.refresh()
        assertEquals(DistanceMeasurementStatus.STALE, state.status)
        assertFalse(state.estimate!!.isFresh)
    }

    @Test fun oldSessionWorkerResultsCannotRestoreInvalidatedDistance() {
        val now = 1_000_000_000L
        var state = DistanceMeasurementState()
        val coordinator = DistanceCoordinator({ state = it }, { now })
        coordinator.startFocusDistance(context, unsupportedFocus)
        coordinator.onMotionEstimate(context, estimate(now))
        coordinator.invalidate("Camera changed")
        coordinator.onMotionEstimate(context, estimate(now))
        assertNull(state.estimate)
        assertNull(state.motionObservation)
        coordinator.startFocusDistance(context.copy(generation=2), unsupportedFocus)
        coordinator.onMotionEstimate(context, estimate(now))
        assertEquals(DistanceMeasurementStatus.UNSUPPORTED, state.status)
        coordinator.stop()
        coordinator.onMotionEstimate(context.copy(generation=2), estimate(now))
        assertEquals(DistanceMeasurementStatus.IDLE, state.status)
    }

    @Test fun missingAfDoesNotReportUnavailableWhileAnIndependentMotionSourceCanRun() {
        var state = DistanceMeasurementState()
        val coordinator = DistanceCoordinator({ state = it }, { 1_000_000_000L })
        coordinator.startFocusDistance(context, unsupportedFocus, motionSupported = true)
        assertEquals(DistanceMeasurementStatus.WAITING_FOR_FOCUS, state.status)
        coordinator.onMotionEstimate(context, estimate(1_000_000_000L))
        assertEquals(DistanceMeasurementStatus.AVAILABLE, state.status)
    }
}
