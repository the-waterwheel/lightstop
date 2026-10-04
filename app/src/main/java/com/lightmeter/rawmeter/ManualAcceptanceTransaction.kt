package com.lightmeter.rawmeter

internal sealed interface ManualAcceptanceOutcome {
    data object Accepted : ManualAcceptanceOutcome
    data class RetryRequired(val approvalSaved: Boolean, val reason: String) : ManualAcceptanceOutcome
    data class Cancelled(val approvalSaved: Boolean) : ManualAcceptanceOutcome
}

/** Camera-thread-owned, single terminal delivery for acceptance, timeout and lifecycle races. */
internal class ManualAcceptanceTransaction(
    val evidence: ManualCombinationProbeEvidence,
    val plan: CameraCombinationPlan,
    private val completion: (ManualAcceptanceOutcome) -> Unit,
) {
    var approvalSaved = false
        private set
    private var finished = false

    fun markSaved() { check(!finished); approvalSaved = true }

    fun finish(outcome: ManualAcceptanceOutcome): Boolean {
        if (finished) return false
        finished = true
        completion(outcome)
        return true
    }
}
