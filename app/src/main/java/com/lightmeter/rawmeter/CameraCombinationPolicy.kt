package com.lightmeter.rawmeter

/** How the active camera stream workflow is chosen. */
enum class MeteringCombinationSelectionMode {
    SYSTEM,
    MANUAL,
}

/** User-facing precision classes. A YUV plan is also AUTO's fallback when RAW plans fail. */
internal enum class CameraCombinationClass {
    HIGH_ACCURACY_RAW,
    STABLE_YUV,
    COMPATIBILITY_ISP,
}

/**
 * A combination is a complete workflow, not just one Camera2 session.
 *
 * This matters for the split RAW strategy: ordinary metering keeps preview + RAW, Zone keeps
 * preview + YUV, and only the short Zone capture swaps to RAW_ISOLATED.
 */
internal data class CameraCombinationPlan(
    val id: String,
    val combinationClass: CameraCombinationClass,
    val precisionRank: Int,
    val stabilityRank: Int,
    val normalResidentProfile: CameraSessionProfile,
    val zoneResidentProfile: CameraSessionProfile,
    val normalMeteringProfile: CameraSessionProfile,
    val zoneMeteringProfile: CameraSessionProfile,
) {
    val requiredProfiles: Set<CameraSessionProfile>
        get() = setOf(
            normalResidentProfile,
            zoneResidentProfile,
            normalMeteringProfile,
            zoneMeteringProfile,
        )
}

internal enum class CameraCombinationMatrixSupport {
    /** Listed by the framework mandatory matrix for every stage in this workflow. */
    GUARANTEED,

    /** Not guaranteed or the API is unavailable; actual session creation must still be tried. */
    UNKNOWN,

    /** Impossible from advertised formats or output-count limits, or explicitly rejected by HAL. */
    REJECTED,
}

internal data class CameraCombinationCapabilities(
    val rawAvailable: Boolean,
    val yuvAvailable: Boolean,
    val maxRawOutputs: Int = if (rawAvailable) 1 else 0,
    val maxProcessedOutputs: Int = if (yuvAvailable) 2 else 1,
)

internal data class CameraCombinationCandidate(
    val plan: CameraCombinationPlan,
    val matrixSupport: CameraCombinationMatrixSupport,
)

/** Pure candidate ordering and first-pass filtering. */
internal object CameraCombinationPolicy {
    val rawSplit = CameraCombinationPlan(
        id = "raw_split_v1",
        combinationClass = CameraCombinationClass.HIGH_ACCURACY_RAW,
        precisionRank = 300,
        stabilityRank = 300,
        normalResidentProfile = CameraSessionProfile.RAW_ONLY,
        zoneResidentProfile = CameraSessionProfile.COMPATIBLE,
        normalMeteringProfile = CameraSessionProfile.RAW_ONLY,
        zoneMeteringProfile = CameraSessionProfile.RAW_ISOLATED,
    )

    /** RAW precision with no resident RAW stream; slower, but useful on constrained HALs. */
    val rawFullyIsolated = CameraCombinationPlan(
        id = "raw_fully_isolated_v1",
        combinationClass = CameraCombinationClass.HIGH_ACCURACY_RAW,
        precisionRank = 300,
        stabilityRank = 290,
        normalResidentProfile = CameraSessionProfile.COMPATIBLE,
        zoneResidentProfile = CameraSessionProfile.COMPATIBLE,
        normalMeteringProfile = CameraSessionProfile.RAW_ISOLATED,
        zoneMeteringProfile = CameraSessionProfile.RAW_ISOLATED,
    )

    /** Kept as a searchable option, but ranked below split sessions because it pressures HALs. */
    val rawFull = CameraCombinationPlan(
        id = "raw_full_v1",
        combinationClass = CameraCombinationClass.HIGH_ACCURACY_RAW,
        precisionRank = 300,
        stabilityRank = 200,
        normalResidentProfile = CameraSessionProfile.FULL,
        zoneResidentProfile = CameraSessionProfile.FULL,
        normalMeteringProfile = CameraSessionProfile.FULL,
        zoneMeteringProfile = CameraSessionProfile.FULL,
    )

    val stableYuv = CameraCombinationPlan(
        id = "stable_yuv_v1",
        combinationClass = CameraCombinationClass.STABLE_YUV,
        precisionRank = 200,
        stabilityRank = 300,
        normalResidentProfile = CameraSessionProfile.COMPATIBLE,
        zoneResidentProfile = CameraSessionProfile.COMPATIBLE,
        normalMeteringProfile = CameraSessionProfile.COMPATIBLE,
        zoneMeteringProfile = CameraSessionProfile.COMPATIBLE,
    )

    val compatibilityIsp = CameraCombinationPlan(
        id = "compatibility_isp_v1",
        combinationClass = CameraCombinationClass.COMPATIBILITY_ISP,
        precisionRank = 100,
        stabilityRank = 300,
        normalResidentProfile = CameraSessionProfile.PREVIEW_ONLY,
        zoneResidentProfile = CameraSessionProfile.PREVIEW_ONLY,
        normalMeteringProfile = CameraSessionProfile.PREVIEW_ONLY,
        zoneMeteringProfile = CameraSessionProfile.PREVIEW_ONLY,
    )

    private val allPlans = listOf(
        rawSplit,
        rawFullyIsolated,
        rawFull,
        stableYuv,
        compatibilityIsp,
    )

    fun orderedCandidates(
        mode: MeteringPipelineMode,
        capabilities: CameraCombinationCapabilities,
        mandatoryGuaranteedProfiles: Set<CameraSessionProfile> = emptySet(),
        halRejectedProfiles: Set<CameraSessionProfile> = emptySet(),
    ): List<CameraCombinationCandidate> = allPlans.asSequence()
        .filter { plan -> planAllowedByMode(plan, mode) }
        .map { plan ->
            CameraCombinationCandidate(
                plan = plan,
                matrixSupport = classify(
                    plan = plan,
                    capabilities = capabilities,
                    mandatoryGuaranteedProfiles = mandatoryGuaranteedProfiles,
                    halRejectedProfiles = halRejectedProfiles,
                ),
            )
        }
        // Mandatory tables are minimum guarantees, not an exhaustive allow-list. UNKNOWN must be
        // retained so OEM-specific combinations still reach the real configure/health probe.
        .filter { it.matrixSupport != CameraCombinationMatrixSupport.REJECTED }
        .sortedWith(
            compareByDescending<CameraCombinationCandidate> { it.plan.precisionRank }
                .thenByDescending { it.plan.stabilityRank }
                .thenByDescending {
                    it.matrixSupport == CameraCombinationMatrixSupport.GUARANTEED
                },
        )
        .toList()

    fun classify(
        plan: CameraCombinationPlan,
        capabilities: CameraCombinationCapabilities,
        mandatoryGuaranteedProfiles: Set<CameraSessionProfile>,
        halRejectedProfiles: Set<CameraSessionProfile> = emptySet(),
    ): CameraCombinationMatrixSupport {
        if (plan.requiredProfiles.any { it in halRejectedProfiles }) {
            return CameraCombinationMatrixSupport.REJECTED
        }
        if (plan.requiredProfiles.any { profile ->
                profile.usesRaw && (!capabilities.rawAvailable || capabilities.maxRawOutputs < 1)
            }
        ) {
            return CameraCombinationMatrixSupport.REJECTED
        }
        if (plan.requiredProfiles.any { profile ->
                profile.usesTracking && (!capabilities.yuvAvailable ||
                    processedOutputCount(profile) > capabilities.maxProcessedOutputs)
            }
        ) {
            return CameraCombinationMatrixSupport.REJECTED
        }
        if (plan.requiredProfiles.any {
                processedOutputCount(it) > capabilities.maxProcessedOutputs
            }
        ) {
            return CameraCombinationMatrixSupport.REJECTED
        }
        return if (mandatoryGuaranteedProfiles.containsAll(plan.requiredProfiles)) {
            CameraCombinationMatrixSupport.GUARANTEED
        } else {
            CameraCombinationMatrixSupport.UNKNOWN
        }
    }

    private fun planAllowedByMode(
        plan: CameraCombinationPlan,
        mode: MeteringPipelineMode,
    ): Boolean = when (mode) {
        MeteringPipelineMode.AUTO -> true
        MeteringPipelineMode.ISOLATED ->
            plan.combinationClass != CameraCombinationClass.HIGH_ACCURACY_RAW
        MeteringPipelineMode.FAST ->
            plan.combinationClass == CameraCombinationClass.COMPATIBILITY_ISP
    }

    private fun processedOutputCount(profile: CameraSessionProfile): Int =
        (if (profile.usesPreview) 1 else 0) + (if (profile.usesTracking) 1 else 0)
}
