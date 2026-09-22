package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewHealthRecoveryBudgetTest {
    @Test
    fun `budget is consumed once and not restored by an unconfirmed healthy sample`() {
        val budget = PreviewHealthRecoveryBudget(1)
        assertTrue(budget.canAttempt())
        budget.recordAttempt()
        assertFalse(budget.canAttempt())
        assertEquals(1, budget.attempts)
    }

    @Test
    fun `a stable preview resets the budget`() {
        val budget = PreviewHealthRecoveryBudget(2)
        budget.recordAttempt()
        budget.recordAttempt()
        assertFalse(budget.canAttempt())
        budget.reset()
        assertTrue(budget.canAttempt())
        assertEquals(0, budget.attempts)
    }
}
