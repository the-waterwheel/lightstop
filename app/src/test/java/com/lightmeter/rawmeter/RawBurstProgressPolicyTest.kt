package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class RawBurstProgressPolicyTest {
    private fun state(
        base: Int,
        max: Int,
        expected: Int,
        completed: Int,
        valid: Int,
    ) = RawBurstState(base, max, expected, completed, valid)

    @Test
    fun `base one finishes after the first valid frame`() {
        assertEquals(
            RawBurstAction.FinishSuccess,
            RawBurstProgressPolicy.afterFrame(state(1, 2, 1, 1, 1), appendRecommended = false),
        )
    }

    @Test
    fun `invalid frame only consumes request budget and a later valid frame succeeds`() {
        assertEquals(
            RawBurstAction.Continue(2),
            RawBurstProgressPolicy.afterFrame(state(1, 2, 1, 1, 0), appendRecommended = false),
        )
        assertEquals(
            RawBurstAction.FinishSuccess,
            RawBurstProgressPolicy.afterFrame(state(1, 2, 2, 2, 1), appendRecommended = false),
        )
    }

    @Test
    fun `all invalid frames exhaust the budget and finish with error`() {
        assertEquals(
            RawBurstAction.FinishError,
            RawBurstProgressPolicy.afterFrame(state(1, 2, 2, 2, 0), appendRecommended = false),
        )
    }

    @Test
    fun `a valid first frame at high iso does not shrink the base target`() {
        // base 3 must stay 3 after one valid frame, not collapse to 2.
        assertEquals(
            RawBurstAction.Continue(3),
            RawBurstProgressPolicy.afterFrame(state(3, 4, 3, 1, 1), appendRecommended = false),
        )
    }

    @Test
    fun `three valid frames complete at high iso`() {
        assertEquals(
            RawBurstAction.FinishSuccess,
            RawBurstProgressPolicy.afterFrame(state(3, 4, 3, 3, 3), appendRecommended = false),
        )
    }

    @Test
    fun `budget exhaustion with fewer than base valid frames is an error`() {
        assertEquals(
            RawBurstAction.FinishError,
            RawBurstProgressPolicy.afterFrame(state(3, 4, 3, 4, 2), appendRecommended = false),
        )
    }

    @Test
    fun `adaptive append stays within max frames`() {
        assertEquals(
            RawBurstAction.Continue(3),
            RawBurstProgressPolicy.afterFrame(state(2, 3, 2, 2, 2), appendRecommended = true),
        )
        assertEquals(
            RawBurstAction.FinishSuccess,
            RawBurstProgressPolicy.afterFrame(state(2, 3, 3, 3, 3), appendRecommended = true),
        )
    }
}
