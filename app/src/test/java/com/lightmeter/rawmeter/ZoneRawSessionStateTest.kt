package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneRawSessionStateTest {
    @Test
    fun successfulTransactionRequiresRawThenRestoreThenResidentSession() {
        val state = ZoneRawSessionState()

        assertEquals(ZoneRawSessionPhase.SWITCHING_TO_RAW, state.phase)
        assertFalse(state.markResidentSessionConfigured())
        assertTrue(state.markRawSessionConfigured())
        assertEquals(ZoneRawSessionPhase.METERING, state.phase)
        assertTrue(state.beginRestore())
        assertEquals(ZoneRawSessionPhase.RESTORING, state.phase)
        assertTrue(state.markResidentSessionConfigured())
        assertEquals(ZoneRawSessionPhase.COMPLETE, state.phase)
        assertFalse(state.markRawSessionConfigured())
    }

    @Test
    fun rawConfigurationFailureCanRestoreWithoutEnteringMetering() {
        val state = ZoneRawSessionState()

        assertTrue(state.beginRestore())
        assertEquals(ZoneRawSessionPhase.RESTORING, state.phase)
        assertTrue(state.markResidentSessionConfigured())
    }
}
