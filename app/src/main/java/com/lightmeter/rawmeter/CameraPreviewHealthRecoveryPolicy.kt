package com.lightmeter.rawmeter

/** What a soft preview-health warning is allowed to do. */
internal enum class PreviewHealthRecoveryDecision {
    /** Keep the workflow and only lower the preview frame rate. */
    RETRY_STANDARD_RATE,

    /** A confirmed RAW workflow: warn only, never change precision or route. */
    CONFIRMED_WARNING_ONLY,

    /** Unconfirmed: the normal combination/route recovery search may run. */
    CONTINUE,
}

/**
 * Pure gate for preview-health recovery. A soft picture-quality heuristic must never be able to
 * replace a workflow the user or a real probe confirmed.
 */
internal object CameraPreviewHealthRecoveryPolicy {
    fun decide(
        confirmedRawWorkflow: Boolean,
        retryAtStandardRate: Boolean,
    ): PreviewHealthRecoveryDecision = when {
        retryAtStandardRate -> PreviewHealthRecoveryDecision.RETRY_STANDARD_RATE
        confirmedRawWorkflow -> PreviewHealthRecoveryDecision.CONFIRMED_WARNING_ONLY
        else -> PreviewHealthRecoveryDecision.CONTINUE
    }
}
