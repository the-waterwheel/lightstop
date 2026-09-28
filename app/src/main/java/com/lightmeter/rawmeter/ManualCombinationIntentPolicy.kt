package com.lightmeter.rawmeter

/** Resolves user intent without substituting an automatic candidate for a missing manual plan. */
internal data class ManualCombinationIntent(
    val selection: CombinationSelection?,
    val planId: String?,
    val requiresChoice: Boolean,
    val environmentExpired: Boolean,
)

internal object ManualCombinationIntentPolicy {
    fun resolve(
        read: ManualCombinationSelectionRead,
        availablePlanIds: Set<String>,
        explicitProbePlanId: String?,
    ): ManualCombinationIntent {
        if (explicitProbePlanId != null) {
            return ManualCombinationIntent(null, explicitProbePlanId, false, false)
        }
        val selection = when (read) {
            is ManualCombinationSelectionRead.Valid -> read.selection
            is ManualCombinationSelectionRead.NeedsRevalidation -> read.selection
            else -> null
        }
        val planId = selection?.planId?.takeIf { it in availablePlanIds }
        return ManualCombinationIntent(
            selection, planId,
            read !is ManualCombinationSelectionRead.Missing && planId == null,
            planId != null && read is ManualCombinationSelectionRead.NeedsRevalidation,
        )
    }
}
