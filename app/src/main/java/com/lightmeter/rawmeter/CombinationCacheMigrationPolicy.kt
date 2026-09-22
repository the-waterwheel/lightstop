package com.lightmeter.rawmeter

/**
 * Pure migration rule for a persisted system combination cache.
 *
 * An older build could write a low-precision automatic result after a runtime downgrade. While AUTO
 * still has a RAW candidate, such a legacy cache must not be treated as already validated; it is
 * re-probed and the outcome is persisted with an explicit origin.
 */
internal object CombinationCacheMigrationPolicy {
    fun needsRawRevalidation(
        selection: CombinationSelection?,
        mode: MeteringPipelineMode,
        cachedPlanIsRaw: Boolean,
        hasRawCandidate: Boolean,
    ): Boolean = selection != null &&
        selection.origin == CombinationSelectionOrigin.LEGACY_AUTO &&
        mode == MeteringPipelineMode.AUTO &&
        hasRawCandidate &&
        !cachedPlanIsRaw
}
