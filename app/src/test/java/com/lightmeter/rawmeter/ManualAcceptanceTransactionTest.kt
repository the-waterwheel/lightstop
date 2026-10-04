package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class ManualAcceptanceTransactionTest {
    private val evidence = ManualCombinationProbeEvidence(1, "tele", "raw_split_v1", "route-a", 2, 3)

    @Test fun `timeout error close and late success deliver one terminal result`() {
        val outcomes = mutableListOf<ManualAcceptanceOutcome>()
        val tx = ManualAcceptanceTransaction(evidence, CameraCombinationPolicy.rawSplit, outcomes::add)
        tx.markSaved()
        assertTrue(tx.finish(ManualAcceptanceOutcome.RetryRequired(tx.approvalSaved, "timeout")))
        assertFalse(tx.finish(ManualAcceptanceOutcome.Cancelled(true)))
        assertFalse(tx.finish(ManualAcceptanceOutcome.Accepted))
        assertEquals(listOf(ManualAcceptanceOutcome.RetryRequired(true, "timeout")), outcomes)
    }

    @Test fun `unsaved cancellation cannot claim persisted approval`() {
        val outcomes = mutableListOf<ManualAcceptanceOutcome>()
        val tx = ManualAcceptanceTransaction(evidence, CameraCombinationPolicy.rawSplit, outcomes::add)
        assertFalse(tx.approvalSaved)
        tx.finish(ManualAcceptanceOutcome.Cancelled(tx.approvalSaved))
        assertEquals(ManualAcceptanceOutcome.Cancelled(false), outcomes.single())
    }
}
