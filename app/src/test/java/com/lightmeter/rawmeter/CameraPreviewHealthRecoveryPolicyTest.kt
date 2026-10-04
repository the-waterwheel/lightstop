package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPreviewHealthRecoveryPolicyTest {
    @Test
    fun `high rate retry takes precedence and keeps the workflow`() {
        assertEquals(
            PreviewHealthRecoveryDecision.RETRY_STANDARD_RATE,
            CameraPreviewHealthRecoveryPolicy.decide(
                confirmedRawWorkflow = true,
                retryAtStandardRate = true,
            ),
        )
    }

    @Test
    fun `confirmed raw only warns`() {
        assertEquals(
            PreviewHealthRecoveryDecision.CONFIRMED_WARNING_ONLY,
            CameraPreviewHealthRecoveryPolicy.decide(
                confirmedRawWorkflow = true,
                retryAtStandardRate = false,
            ),
        )
    }

    @Test
    fun `unconfirmed keeps the normal recovery search`() {
        assertEquals(
            PreviewHealthRecoveryDecision.CONTINUE,
            CameraPreviewHealthRecoveryPolicy.decide(
                confirmedRawWorkflow = false,
                retryAtStandardRate = false,
            ),
        )
    }
}
