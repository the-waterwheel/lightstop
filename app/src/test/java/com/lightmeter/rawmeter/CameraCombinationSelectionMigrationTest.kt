package com.lightmeter.rawmeter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCombinationSelectionMigrationTest {
    private fun selection(
        planId: String = "stable_yuv_v1",
        origin: CombinationSelectionOrigin = CombinationSelectionOrigin.LEGACY_AUTO,
    ) = CombinationSelection(planId, origin)

    @Test
    fun `legacy low precision auto cache with a raw candidate needs revalidation`() {
        assertTrue(
            CombinationCacheMigrationPolicy.needsRawRevalidation(
                selection = selection(),
                mode = MeteringPipelineMode.AUTO,
                cachedPlanIsRaw = false,
                hasRawCandidate = true,
            ),
        )
    }

    @Test
    fun `already raw cache does not need revalidation`() {
        assertFalse(
            CombinationCacheMigrationPolicy.needsRawRevalidation(
                selection = selection(planId = "raw_split_v1"),
                mode = MeteringPipelineMode.AUTO,
                cachedPlanIsRaw = true,
                hasRawCandidate = true,
            ),
        )
    }

    @Test
    fun `this build's probe result is trusted`() {
        assertFalse(
            CombinationCacheMigrationPolicy.needsRawRevalidation(
                selection = selection(origin = CombinationSelectionOrigin.SYSTEM_PROBE),
                mode = MeteringPipelineMode.AUTO,
                cachedPlanIsRaw = false,
                hasRawCandidate = true,
            ),
        )
    }

    @Test
    fun `manual and non-auto selections are respected`() {
        assertFalse(
            CombinationCacheMigrationPolicy.needsRawRevalidation(
                selection = selection(origin = CombinationSelectionOrigin.MANUAL),
                mode = MeteringPipelineMode.AUTO,
                cachedPlanIsRaw = false,
                hasRawCandidate = true,
            ),
        )
        assertFalse(
            CombinationCacheMigrationPolicy.needsRawRevalidation(
                selection = selection(),
                mode = MeteringPipelineMode.ISOLATED,
                cachedPlanIsRaw = false,
                hasRawCandidate = true,
            ),
        )
    }

    @Test
    fun `no raw candidate or no cache does nothing`() {
        assertFalse(
            CombinationCacheMigrationPolicy.needsRawRevalidation(
                selection = selection(),
                mode = MeteringPipelineMode.AUTO,
                cachedPlanIsRaw = false,
                hasRawCandidate = false,
            ),
        )
        assertFalse(
            CombinationCacheMigrationPolicy.needsRawRevalidation(
                selection = null,
                mode = MeteringPipelineMode.AUTO,
                cachedPlanIsRaw = false,
                hasRawCandidate = true,
            ),
        )
    }
}
