package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCombinationPolicyTest {
    @Test
    fun autoKeepsUnknownOemCombinationsAndOrdersByPrecisionThenPressure() {
        val candidates = CameraCombinationPolicy.orderedCandidates(
            mode = MeteringPipelineMode.AUTO,
            capabilities = CameraCombinationCapabilities(
                rawAvailable = true,
                yuvAvailable = true,
                maxRawOutputs = 1,
                maxProcessedOutputs = 2,
            ),
        )

        assertEquals(
            listOf(
                "raw_split_v1",
                "raw_fully_isolated_v1",
                "raw_full_v1",
                "stable_yuv_v1",
                "compatibility_isp_v1",
            ),
            candidates.map { it.plan.id },
        )
        assertTrue(candidates.all {
            it.matrixSupport == CameraCombinationMatrixSupport.UNKNOWN
        })
    }

    @Test
    fun autoFallsBackToYuvWhenRawIsNotAdvertised() {
        val candidates = CameraCombinationPolicy.orderedCandidates(
            mode = MeteringPipelineMode.AUTO,
            capabilities = CameraCombinationCapabilities(
                rawAvailable = false,
                yuvAvailable = true,
            ),
        )

        assertEquals(
            listOf("stable_yuv_v1", "compatibility_isp_v1"),
            candidates.map { it.plan.id },
        )
    }

    @Test
    fun stableNeverCreatesRawAndCompatibilityNeverCreatesYuv() {
        val capabilities = CameraCombinationCapabilities(
            rawAvailable = true,
            yuvAvailable = true,
        )
        val stable = CameraCombinationPolicy.orderedCandidates(
            MeteringPipelineMode.ISOLATED,
            capabilities,
        )
        val compatibility = CameraCombinationPolicy.orderedCandidates(
            MeteringPipelineMode.FAST,
            capabilities,
        )

        assertEquals(
            listOf("stable_yuv_v1", "compatibility_isp_v1"),
            stable.map { it.plan.id },
        )
        assertTrue(stable.all { candidate ->
            candidate.plan.requiredProfiles.none(CameraSessionProfile::usesRaw)
        })
        assertEquals(listOf("compatibility_isp_v1"), compatibility.map { it.plan.id })
        assertFalse(compatibility.single().plan.requiredProfiles.any { it.usesTracking })
    }

    @Test
    fun mandatoryMatrixIsGuaranteeNotAllowList() {
        val capabilities = CameraCombinationCapabilities(
            rawAvailable = true,
            yuvAvailable = true,
        )
        val guaranteed = CameraCombinationPolicy.classify(
            plan = CameraCombinationPolicy.rawSplit,
            capabilities = capabilities,
            mandatoryGuaranteedProfiles = CameraCombinationPolicy.rawSplit.requiredProfiles,
        )
        val unknown = CameraCombinationPolicy.classify(
            plan = CameraCombinationPolicy.rawSplit,
            capabilities = capabilities,
            mandatoryGuaranteedProfiles = emptySet(),
        )

        assertEquals(CameraCombinationMatrixSupport.GUARANTEED, guaranteed)
        assertEquals(CameraCombinationMatrixSupport.UNKNOWN, unknown)
    }

    @Test
    fun outputLimitsAndDefinitiveHalRejectionsRemoveCandidate() {
        val insufficientProcessedOutputs = CameraCombinationPolicy.classify(
            plan = CameraCombinationPolicy.rawFull,
            capabilities = CameraCombinationCapabilities(
                rawAvailable = true,
                yuvAvailable = true,
                maxRawOutputs = 1,
                maxProcessedOutputs = 1,
            ),
            mandatoryGuaranteedProfiles = emptySet(),
        )
        val rejectedStage = CameraCombinationPolicy.classify(
            plan = CameraCombinationPolicy.rawSplit,
            capabilities = CameraCombinationCapabilities(
                rawAvailable = true,
                yuvAvailable = true,
            ),
            mandatoryGuaranteedProfiles = emptySet(),
            halRejectedProfiles = setOf(CameraSessionProfile.RAW_ISOLATED),
        )

        assertEquals(CameraCombinationMatrixSupport.REJECTED, insufficientProcessedOutputs)
        assertEquals(CameraCombinationMatrixSupport.REJECTED, rejectedStage)
    }
}
