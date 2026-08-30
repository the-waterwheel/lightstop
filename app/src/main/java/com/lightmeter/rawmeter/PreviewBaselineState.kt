package com.lightmeter.rawmeter

/** Tag carried by every repeating preview request so stale Camera2 results cannot unlock metering. */
internal data class PreviewRequestTag(
    val cameraGeneration: Int,
    val requestSequence: Long,
    val neutralBaselineGeneration: Long?,
)

/** One in-flight transition from manual/compensated preview back to neutral AE. */
internal data class PreviewBaselineOperation(
    val cameraGeneration: Int,
    val generation: Long,
    val startedAtElapsedMs: Long,
    val continuation: () -> Unit,
)

internal object PreviewBaselinePolicy {
    fun acceptsResult(
        operation: PreviewBaselineOperation,
        requestTag: PreviewRequestTag?,
    ): Boolean = requestTag?.cameraGeneration == operation.cameraGeneration &&
        requestTag.neutralBaselineGeneration == operation.generation

    fun hasEnoughStableFrames(stableFrameCount: Int): Boolean =
        stableFrameCount >= REQUIRED_STABLE_FRAMES

    const val REQUIRED_STABLE_FRAMES = 2
}
