package com.lightmeter.rawmeter

/**
 * Pure upgrade rule for legacy manual RAW selections.
 *
 * Older builds persisted a successful manual RAW acceptance as [CombinationSelectionOrigin.MANUAL],
 * which carries no verification credential. Such a selection is kept but must not inherit the RAW
 * confirmation lock until it is re-verified; verification success must be written as
 * [CombinationSelectionOrigin.MANUAL_VERIFIED].
 */
internal object ManualCombinationRevalidationPolicy {
    fun needsRevalidation(
        selection: CombinationSelection?,
        isRawPlan: Boolean,
        currentRouteId: String?,
    ): Boolean = isRawPlan && selection != null &&
        (selection.origin != CombinationSelectionOrigin.MANUAL_VERIFIED ||
            currentRouteId.isNullOrBlank() || selection.routeIdentity != currentRouteId)
}
