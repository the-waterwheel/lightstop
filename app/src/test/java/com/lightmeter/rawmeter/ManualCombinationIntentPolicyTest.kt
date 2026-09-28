package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class ManualCombinationIntentPolicyTest {
    private val saved = CombinationSelection("old_raw", CombinationSelectionOrigin.MANUAL_VERIFIED, "route-a")

    @Test fun `expired missing plan never becomes an automatic candidate`() {
        val result = ManualCombinationIntentPolicy.resolve(
            ManualCombinationSelectionRead.NeedsRevalidation(saved), setOf("new_raw"), null,
        )
        assertEquals(saved, result.selection)
        assertNull(result.planId)
        assertTrue(result.requiresChoice)
        assertFalse(result.environmentExpired)
    }

    @Test fun `existing expired plan can be reverified without changing intent`() {
        val result = ManualCombinationIntentPolicy.resolve(
            ManualCombinationSelectionRead.NeedsRevalidation(saved), setOf("old_raw", "new_raw"), null,
        )
        assertEquals("old_raw", result.planId)
        assertTrue(result.environmentExpired)
        assertFalse(result.requiresChoice)
    }

    @Test fun `explicit new probe is not interrupted by old records`() {
        for (read in listOf(
            ManualCombinationSelectionRead.NeedsRevalidation(saved),
            ManualCombinationSelectionRead.UnsupportedRecord("newer schema"),
        )) {
            val result = ManualCombinationIntentPolicy.resolve(read, setOf("new_raw"), "new_raw")
            assertEquals("new_raw", result.planId)
            assertNull(result.selection)
            assertFalse(result.requiresChoice)
            assertFalse(result.environmentExpired)
        }
    }

    @Test fun `missing unsupported empty and valid records remain distinct`() {
        assertFalse(ManualCombinationIntentPolicy.resolve(ManualCombinationSelectionRead.Missing, emptySet(), null).requiresChoice)
        assertTrue(ManualCombinationIntentPolicy.resolve(ManualCombinationSelectionRead.UnsupportedRecord("future"), emptySet(), null).requiresChoice)
        assertTrue(ManualCombinationIntentPolicy.resolve(ManualCombinationSelectionRead.Valid(saved), emptySet(), null).requiresChoice)
        assertEquals("old_raw", ManualCombinationIntentPolicy.resolve(ManualCombinationSelectionRead.Valid(saved), setOf("old_raw"), null).planId)
    }

    @Test fun `same lens does not make an old notification current`() {
        val request = ManualCombinationRevalidationRequest("tele", "old_raw", 5, "expired", true)
        assertTrue(request.belongsTo("tele", 5))
        assertFalse(request.belongsTo("tele", 6))
        assertFalse(request.belongsTo("main", 5))
    }
}
