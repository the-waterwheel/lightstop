package com.lightmeter.rawmeter

/**
 * Bounded attempt counter for preview-health recovery. A fault may consume the budget once; only a
 * genuinely stable preview or a user/policy reset may restore it, so a short-lived healthy sample
 * cannot let recovery loop forever.
 */
internal class PreviewHealthRecoveryBudget(private val maxAttempts: Int) {
    var attempts: Int = 0
        private set

    fun canAttempt(): Boolean = attempts < maxAttempts

    fun recordAttempt() {
        attempts += 1
    }

    fun reset() {
        attempts = 0
    }
}
