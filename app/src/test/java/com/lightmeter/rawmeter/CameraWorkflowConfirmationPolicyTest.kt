package com.lightmeter.rawmeter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraWorkflowConfirmationPolicyTest {
    private val plan = "raw_split_v1"
    private val route = "0@2"

    @Test
    fun `system and manual probes both confirm raw`() {
        val system = CameraWorkflowConfirmationPolicy.confirm(
            plan,
            route,
            WorkflowConfirmationOrigin.SYSTEM_PROBE,
        )
        val manual = CameraWorkflowConfirmationPolicy.confirm(
            plan,
            route,
            WorkflowConfirmationOrigin.MANUAL_PROBE,
        )
        assertTrue(system.isRawConfirmed)
        assertTrue(manual.isRawConfirmed)
    }

    @Test
    fun `user selected low precision is not a raw confirmation`() {
        val selected = CameraWorkflowConfirmationPolicy.confirm(
            "stable_yuv_v1",
            route,
            WorkflowConfirmationOrigin.USER_SELECTED,
        )
        assertFalse(selected.isRawConfirmed)
    }

    @Test
    fun `same plan on a different route does not inherit confirmation`() {
        val confirmation = CameraWorkflowConfirmationPolicy.confirm(
            plan,
            route,
            WorkflowConfirmationOrigin.SYSTEM_PROBE,
        )
        assertTrue(CameraWorkflowConfirmationPolicy.isRawConfirmedFor(confirmation, plan, route))
        assertFalse(CameraWorkflowConfirmationPolicy.isRawConfirmedFor(confirmation, plan, "0@3"))
        assertFalse(CameraWorkflowConfirmationPolicy.isRawConfirmedFor(confirmation, "other", route))
        assertFalse(CameraWorkflowConfirmationPolicy.isRawConfirmedFor(confirmation, null, route))
    }

    @Test
    fun `unverified and probing are not confirmed`() {
        assertFalse(CameraWorkflowConfirmationPolicy.unverified().isRawConfirmed)
        assertFalse(CameraWorkflowConfirmationPolicy.probing(plan, route).isRawConfirmed)
    }
}
