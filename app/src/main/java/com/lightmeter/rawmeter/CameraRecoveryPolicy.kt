package com.lightmeter.rawmeter

internal enum class CameraSessionProfile(
    val usesRaw: Boolean,
    val usesTracking: Boolean,
) {
    FULL(usesRaw = true, usesTracking = true),
    RAW_ONLY(usesRaw = true, usesTracking = false),
    COMPATIBLE(usesRaw = false, usesTracking = true),
    PREVIEW_ONLY(usesRaw = false, usesTracking = false),
}

internal enum class CameraFailureKind {
    IN_USE,
    RESOURCE_LIMIT,
    DISABLED,
    DEVICE,
    SERVICE,
    DISCONNECTED,
    UNKNOWN,
    ;

    companion object {
        fun fromDeviceError(error: Int): CameraFailureKind = when (error) {
            1 -> IN_USE
            2 -> RESOURCE_LIMIT
            3 -> DISABLED
            4 -> DEVICE
            5 -> SERVICE
            else -> UNKNOWN
        }
    }
}

internal enum class CameraFailureStage {
    OPENING,
    CONFIGURING,
    RUNNING,
}

internal enum class CameraRecoveryAction { RETRY, DOWNGRADE, STOP }

internal data class CameraRecoveryDecision(
    val action: CameraRecoveryAction,
    val profile: CameraSessionProfile?,
    val delayMs: Long = 0L,
)

internal object CameraRecoveryPolicy {
    fun initialProfile(
        mode: MeteringPipelineMode,
        rawSupported: Boolean,
        trackingSupported: Boolean,
    ): CameraSessionProfile = normalize(
        if (mode == MeteringPipelineMode.AUTO) {
            CameraSessionProfile.FULL
        } else {
            CameraSessionProfile.COMPATIBLE
        },
        rawSupported,
        trackingSupported,
    )

    fun normalize(
        profile: CameraSessionProfile,
        rawSupported: Boolean,
        trackingSupported: Boolean,
    ): CameraSessionProfile = when (profile) {
        CameraSessionProfile.FULL -> when {
            rawSupported && trackingSupported -> CameraSessionProfile.FULL
            rawSupported -> CameraSessionProfile.RAW_ONLY
            trackingSupported -> CameraSessionProfile.COMPATIBLE
            else -> CameraSessionProfile.PREVIEW_ONLY
        }
        CameraSessionProfile.RAW_ONLY -> when {
            rawSupported -> CameraSessionProfile.RAW_ONLY
            trackingSupported -> CameraSessionProfile.COMPATIBLE
            else -> CameraSessionProfile.PREVIEW_ONLY
        }
        CameraSessionProfile.COMPATIBLE -> if (trackingSupported) {
            CameraSessionProfile.COMPATIBLE
        } else {
            CameraSessionProfile.PREVIEW_ONLY
        }
        CameraSessionProfile.PREVIEW_ONLY -> CameraSessionProfile.PREVIEW_ONLY
    }

    fun nextProfile(
        current: CameraSessionProfile,
        rawSupported: Boolean,
        trackingSupported: Boolean,
    ): CameraSessionProfile? {
        val requested = when (current) {
            CameraSessionProfile.FULL -> CameraSessionProfile.RAW_ONLY
            CameraSessionProfile.RAW_ONLY -> CameraSessionProfile.COMPATIBLE
            CameraSessionProfile.COMPATIBLE -> CameraSessionProfile.PREVIEW_ONLY
            CameraSessionProfile.PREVIEW_ONLY -> null
        } ?: return null
        return normalize(requested, rawSupported, trackingSupported)
            .takeIf { it != current }
    }

    fun decide(
        failure: CameraFailureKind,
        stage: CameraFailureStage,
        current: CameraSessionProfile,
        attempt: Int,
        rawSupported: Boolean,
        trackingSupported: Boolean,
    ): CameraRecoveryDecision = when (failure) {
        CameraFailureKind.IN_USE -> if (attempt == 1) retry(current, 800L) else stop()

        CameraFailureKind.RESOURCE_LIMIT -> if (attempt == 1) {
            retry(current, 800L)
        } else {
            downgrade(current, rawSupported, trackingSupported, 500L)
        }

        CameraFailureKind.DISABLED -> stop()

        CameraFailureKind.DEVICE -> if (stage == CameraFailureStage.OPENING && attempt == 1) {
            retry(current, 500L)
        } else {
            downgrade(current, rawSupported, trackingSupported, 500L)
        }

        CameraFailureKind.SERVICE,
        CameraFailureKind.DISCONNECTED,
        CameraFailureKind.UNKNOWN,
        -> if (attempt == 1) {
            retry(current, 1_000L)
        } else {
            downgrade(current, rawSupported, trackingSupported, 1_000L)
        }
    }

    private fun retry(profile: CameraSessionProfile, delayMs: Long) =
        CameraRecoveryDecision(CameraRecoveryAction.RETRY, profile, delayMs)

    private fun downgrade(
        current: CameraSessionProfile,
        rawSupported: Boolean,
        trackingSupported: Boolean,
        delayMs: Long,
    ): CameraRecoveryDecision {
        val next = nextProfile(current, rawSupported, trackingSupported)
        return if (next == null) stop() else {
            CameraRecoveryDecision(CameraRecoveryAction.DOWNGRADE, next, delayMs)
        }
    }

    private fun stop() = CameraRecoveryDecision(CameraRecoveryAction.STOP, null)
}
