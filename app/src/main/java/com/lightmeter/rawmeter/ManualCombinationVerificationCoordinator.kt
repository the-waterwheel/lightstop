package com.lightmeter.rawmeter

/** The user-visible state of a manual workflow verification. */
internal enum class ManualCombinationVerificationState {
    IDLE,
    NEEDS_REVALIDATION,
    PROBING,
    AWAITING_ACCEPT,
    APPLYING,
    CONFIRMED,
    FAILED,
}

/**
 * Immutable proof that a particular manual workflow completed on the route the user selected.
 *
 * A plan id alone is intentionally not proof: a route can change while a Camera2 session is being
 * rebuilt.  The controller creates this only on its camera thread after the final resident session
 * has completed, then the UI may present the accept button.
 */
internal data class ManualCombinationProbeEvidence(
    val probeId: Long,
    val selectionCameraId: String,
    val planId: String,
    val routeIdentity: String,
    val ownerEpoch: Long,
    val completedCameraGeneration: Int,
)

/** Pure ownership and evidence gate for manual-combination confirmation. */
internal class ManualCombinationVerificationCoordinator {
    private var nextProbeId = 0L
    var state: ManualCombinationVerificationState = ManualCombinationVerificationState.IDLE
        private set
    private var active: ProbeRequest? = null
    private var acceptedEvidence: ManualCombinationProbeEvidence? = null

    @Synchronized
    fun markNeedsRevalidation(planId: String, selectionCameraId: String, ownerEpoch: Long) {
        if (state == ManualCombinationVerificationState.PROBING) return
        active = ProbeRequest(++nextProbeId, planId, selectionCameraId, ownerEpoch)
        acceptedEvidence = null
        state = ManualCombinationVerificationState.NEEDS_REVALIDATION
    }

    @Synchronized
    fun begin(planId: String, selectionCameraId: String, ownerEpoch: Long): Long {
        val request = ProbeRequest(++nextProbeId, planId, selectionCameraId, ownerEpoch)
        active = request
        acceptedEvidence = null
        state = ManualCombinationVerificationState.PROBING
        return request.probeId
    }

    @Synchronized
    fun complete(
        probeId: Long,
        routeIdentity: String?,
        completedCameraGeneration: Int,
        ownerEpoch: Long,
    ): ManualCombinationProbeEvidence? {
        val request = active ?: return null
        if (state != ManualCombinationVerificationState.PROBING || request.probeId != probeId ||
            request.ownerEpoch != ownerEpoch || routeIdentity.isNullOrBlank()
        ) {
            return null
        }
        return ManualCombinationProbeEvidence(
            probeId = request.probeId,
            selectionCameraId = request.selectionCameraId,
            planId = request.planId,
            routeIdentity = routeIdentity,
            ownerEpoch = ownerEpoch,
            completedCameraGeneration = completedCameraGeneration,
        ).also {
            acceptedEvidence = it
            state = ManualCombinationVerificationState.AWAITING_ACCEPT
        }
    }

    @Synchronized
    fun beginApply(
        probeId: Long,
        planId: String,
        selectionCameraId: String,
        routeIdentity: String?,
        ownerEpoch: Long,
    ): ManualCombinationProbeEvidence? {
        val evidence = acceptedEvidence ?: return null
        if (state != ManualCombinationVerificationState.AWAITING_ACCEPT ||
            evidence.probeId != probeId || evidence.planId != planId ||
            evidence.selectionCameraId != selectionCameraId || evidence.routeIdentity != routeIdentity ||
            evidence.ownerEpoch != ownerEpoch
        ) return null
        state = ManualCombinationVerificationState.APPLYING
        return evidence
    }

    @Synchronized
    fun confirm(evidence: ManualCombinationProbeEvidence) {
        if (acceptedEvidence == evidence && state == ManualCombinationVerificationState.APPLYING) {
            state = ManualCombinationVerificationState.CONFIRMED
        }
    }

    @Synchronized
    fun failProbe(probeId: Long) {
        // A delayed completion from an older runner must not erase the current probe's evidence.
        if (active?.probeId == probeId && state == ManualCombinationVerificationState.PROBING) fail()
    }

    @Synchronized
    fun fail() {
        acceptedEvidence = null
        state = ManualCombinationVerificationState.FAILED
    }

    @Synchronized
    fun invalidateExternal(ownerEpoch: Long) {
        if (active?.ownerEpoch != ownerEpoch) {
            active = null
            acceptedEvidence = null
            state = ManualCombinationVerificationState.FAILED
        }
    }

    @Synchronized
    fun blocksRawMetering(): Boolean = state == ManualCombinationVerificationState.NEEDS_REVALIDATION ||
        state == ManualCombinationVerificationState.PROBING ||
        state == ManualCombinationVerificationState.AWAITING_ACCEPT ||
        state == ManualCombinationVerificationState.APPLYING

    private data class ProbeRequest(
        val probeId: Long,
        val planId: String,
        val selectionCameraId: String,
        val ownerEpoch: Long,
    )
}
