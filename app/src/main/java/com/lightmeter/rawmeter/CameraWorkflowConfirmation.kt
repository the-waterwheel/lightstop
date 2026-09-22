package com.lightmeter.rawmeter

/** Where a workflow confirmation came from. */
internal enum class WorkflowConfirmationOrigin {
    /** This build's real system probe accepted the workflow. */
    SYSTEM_PROBE,

    /** The user ran a manual probe and accepted the verified result. */
    MANUAL_PROBE,

    /** The user explicitly chose this workflow (possibly a lower-precision one). */
    USER_SELECTED,
}

/** Lifecycle of a workflow confirmation for one route. */
internal enum class WorkflowConfirmationState {
    UNVERIFIED,
    PROBING,
    CONFIRMED,
    RECOVERING_SAME_WORKFLOW,
    TEMPORARILY_UNAVAILABLE,
    NEEDS_REVALIDATION,
}

/**
 * Immutable workflow confirmation shared by the system and manual paths.
 *
 * The confirmation is scoped to a plan id *and* a route id, so the same plan id reached through a
 * different lens or transport route never inherits an earlier confirmation. A session generation is
 * deliberately not part of it: a normal reopen or Normal/Zone switch must not revoke it.
 */
internal data class CameraWorkflowConfirmation(
    val planId: String? = null,
    val routeId: String = "",
    val origin: WorkflowConfirmationOrigin? = null,
    val state: WorkflowConfirmationState = WorkflowConfirmationState.UNVERIFIED,
) {
    val isRawConfirmed: Boolean
        get() = state == WorkflowConfirmationState.CONFIRMED &&
            (origin == WorkflowConfirmationOrigin.SYSTEM_PROBE ||
                origin == WorkflowConfirmationOrigin.MANUAL_PROBE)
}

internal object CameraWorkflowConfirmationPolicy {
    fun unverified(): CameraWorkflowConfirmation = CameraWorkflowConfirmation()

    fun probing(planId: String, routeId: String): CameraWorkflowConfirmation =
        CameraWorkflowConfirmation(
            planId = planId,
            routeId = routeId,
            origin = null,
            state = WorkflowConfirmationState.PROBING,
        )

    fun confirm(
        planId: String,
        routeId: String,
        origin: WorkflowConfirmationOrigin,
    ): CameraWorkflowConfirmation = CameraWorkflowConfirmation(
        planId = planId,
        routeId = routeId,
        origin = origin,
        state = WorkflowConfirmationState.CONFIRMED,
    )

    /** True only when the confirmed RAW workflow still matches the active plan and route. */
    fun isRawConfirmedFor(
        confirmation: CameraWorkflowConfirmation,
        planId: String?,
        routeId: String,
    ): Boolean = confirmation.isRawConfirmed &&
        planId != null &&
        confirmation.planId == planId &&
        confirmation.routeId == routeId
}
