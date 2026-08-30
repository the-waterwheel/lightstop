package com.lightmeter.rawmeter

/**
 * Owns mutable recovery history for one CameraController lifecycle.
 *
 * [CameraRecoveryPolicy] contains the stateless decision table. This state machine adds the facts
 * that must survive a reopen: the active stream profile, per-error attempts, the global recovery
 * budget, camera-route fallback, and consecutive RAW measurement failures.
 *
 * Calls are confined to the camera handler after CameraController starts. [reset] may also be used
 * before that handler is created.
 */
internal class CameraRecoveryStateMachine(
    private val maxRecoveryAttempts: Int,
    private val rawFailuresBeforeDowngrade: Int,
) {
    var profile: CameraSessionProfile? = null
        private set

    var routeCandidateIndex: Int = 0
        private set

    private val failureAttempts =
        mutableMapOf<Triple<CameraSessionProfile, CameraFailureKind, CameraFailureStage>, Int>()
    private var totalRecoveryAttempts = 0
    private var consecutiveRawMeasurementFailures = 0

    fun resolveProfile(
        mode: MeteringPipelineMode,
        rawSupported: Boolean,
        trackingSupported: Boolean,
    ): CameraSessionProfile {
        val resolved = CameraRecoveryPolicy.normalizeForMode(
            profile ?: CameraRecoveryPolicy.initialProfile(mode, rawSupported, trackingSupported),
            mode,
            rawSupported,
            trackingSupported,
        )
        profile = resolved
        return resolved
    }

    fun nextProfile(
        mode: MeteringPipelineMode,
        rawSupported: Boolean,
        trackingSupported: Boolean,
    ): CameraSessionProfile? =
        CameraRecoveryPolicy.nextProfile(
            profile ?: CameraSessionProfile.PREVIEW_ONLY,
            mode,
            rawSupported,
            trackingSupported,
        )

    fun decideFailure(
        failure: CameraFailureKind,
        stage: CameraFailureStage,
        mode: MeteringPipelineMode,
        rawSupported: Boolean,
        trackingSupported: Boolean,
    ): CameraRecoveryDecision {
        val current = profile ?: CameraSessionProfile.PREVIEW_ONLY
        val key = Triple(current, failure, stage)
        val attempt = (failureAttempts[key] ?: 0) + 1
        failureAttempts[key] = attempt
        return CameraRecoveryPolicy.decide(
            failure,
            stage,
            current,
            mode,
            attempt,
            rawSupported,
            trackingSupported,
        )
    }

    /** Reserves one reopen attempt and records the profile that the reopen must configure. */
    fun beginRecovery(nextProfile: CameraSessionProfile): Boolean {
        if (totalRecoveryAttempts >= maxRecoveryAttempts) return false
        totalRecoveryAttempts += 1
        profile = nextProfile
        return true
    }

    /** Advances through transport candidates without changing the user-selected lens identity. */
    fun advanceCameraRoute(candidateCount: Int): Boolean {
        if (routeCandidateIndex + 1 >= candidateCount) return false
        routeCandidateIndex += 1
        profile = CameraSessionProfile.PREVIEW_ONLY
        return true
    }

    fun recordRawMeasurementSucceeded() {
        consecutiveRawMeasurementFailures = 0
    }

    /** Returns true when AUTO mode should remain on the compatible pipeline after this failure. */
    fun recordRawMeasurementFailed(mode: MeteringPipelineMode): Boolean {
        consecutiveRawMeasurementFailures += 1
        return mode == MeteringPipelineMode.AUTO &&
            consecutiveRawMeasurementFailures >= rawFailuresBeforeDowngrade
    }

    /** A sustained preview proves the camera recovered, so only transient attempt counters reset. */
    fun markPreviewStable() {
        failureAttempts.clear()
        totalRecoveryAttempts = 0
    }

    /** Applies a user-requested profile without consuming a recovery attempt or changing route. */
    fun forceProfile(profile: CameraSessionProfile) {
        this.profile = profile
        markPreviewStable()
        consecutiveRawMeasurementFailures = 0
    }

    fun reset() {
        profile = null
        routeCandidateIndex = 0
        markPreviewStable()
        consecutiveRawMeasurementFailures = 0
    }
}
