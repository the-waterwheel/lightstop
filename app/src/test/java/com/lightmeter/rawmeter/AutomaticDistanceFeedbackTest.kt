package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class AutomaticDistanceFeedbackTest {
    private fun state(status: DistanceMeasurementStatus) = DistanceMeasurementState(status = status)

    @Test fun backgroundUnsupportedStatesNeverShowUserNotices() {
        assertFalse(AutomaticDistanceFeedback().consumeUnavailable(state(DistanceMeasurementStatus.UNSUPPORTED)))
    }

    @Test fun explicitSelectionWaitsForOutcomeAndOnlyReportsUnavailableOnce() {
        val feedback = AutomaticDistanceFeedback()
        feedback.selected(true)
        for (status in listOf(DistanceMeasurementStatus.WAITING_FOR_FOCUS,
            DistanceMeasurementStatus.SAMPLING, DistanceMeasurementStatus.STALE)) {
            assertFalse(feedback.consumeUnavailable(state(status)))
        }
        assertTrue(feedback.consumeUnavailable(state(DistanceMeasurementStatus.UNSUPPORTED)))
        assertFalse(feedback.consumeUnavailable(state(DistanceMeasurementStatus.UNSUPPORTED)))
        feedback.selected(true)
        assertTrue(feedback.consumeUnavailable(state(DistanceMeasurementStatus.UNSUPPORTED)))
    }

    @Test fun ManualSelectionOrSuccessfulEstimateCancelsPendingUnavailableNotice() {
        val feedback = AutomaticDistanceFeedback()
        feedback.selected(true)
        feedback.selected(false)
        assertFalse(feedback.consumeUnavailable(state(DistanceMeasurementStatus.UNSUPPORTED)))
        feedback.selected(true)
        assertFalse(feedback.consumeUnavailable(state(DistanceMeasurementStatus.AVAILABLE)))
        assertFalse(feedback.consumeUnavailable(state(DistanceMeasurementStatus.UNSUPPORTED)))
    }
}
