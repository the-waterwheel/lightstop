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
                selection = selection(origin = CombinationSelectionOrigin.MANUAL_VERIFIED),
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

    @Test
    fun `a confirmation only matches the route it was verified on`() {
        val selection = CombinationSelection(
            planId = "raw_split_v1",
            origin = CombinationSelectionOrigin.SYSTEM_PROBE,
            routeIdentity = "path-a",
        )
        assertTrue(CombinationRouteMatchPolicy.matches(selection, "path-a"))
        assertFalse(CombinationRouteMatchPolicy.matches(selection, "path-b"))
        assertFalse(CombinationRouteMatchPolicy.matches(selection.copy(routeIdentity = null), "path-a"))
        assertFalse(CombinationRouteMatchPolicy.matches(null, "path-a"))
    }

    @Test
    fun `system raw caches require a probe credential and a matching route`() {
        val verified = CombinationSelection(
            "raw_split_v1", CombinationSelectionOrigin.SYSTEM_PROBE, "path-a",
        )
        assertFalse(CombinationRouteMatchPolicy.needsSystemRevalidation(null, "path-a"))
        assertFalse(CombinationRouteMatchPolicy.needsSystemRevalidation(verified, "path-a"))
        assertTrue(CombinationRouteMatchPolicy.needsSystemRevalidation(verified, "path-b"))
        assertTrue(CombinationRouteMatchPolicy.needsSystemRevalidation(verified.copy(routeIdentity = null), "path-a"))
        val legacy = verified.copy(origin = CombinationSelectionOrigin.LEGACY_AUTO)
        assertTrue(CombinationRouteMatchPolicy.needsSystemRevalidation(legacy, "path-a"))
        assertTrue(CombinationRouteMatchPolicy.needsSystemRevalidation(legacy.copy(routeIdentity = null), "path-a"))
    }

    @Test
    fun `legacy manual raw selection needs revalidation`() {
        assertTrue(
            ManualCombinationRevalidationPolicy.needsRevalidation(
                selection = selection(origin = CombinationSelectionOrigin.MANUAL),
                isRawPlan = true,
                currentRouteId = "path-a",
            ),
        )
        assertFalse(
            ManualCombinationRevalidationPolicy.needsRevalidation(
                selection = selection(origin = CombinationSelectionOrigin.MANUAL_VERIFIED)
                    .copy(routeIdentity = "path-a"),
                isRawPlan = true,
                currentRouteId = "path-a",
            ),
        )
        assertFalse(
            ManualCombinationRevalidationPolicy.needsRevalidation(
                selection = selection(origin = CombinationSelectionOrigin.MANUAL),
                isRawPlan = false,
                currentRouteId = "path-a",
            ),
        )
    }

    @Test
    fun `manual raw verification requires a trusted origin and matching resolved route`() {
        val verified = selection(origin = CombinationSelectionOrigin.MANUAL_VERIFIED)
            .copy(routeIdentity = "path-a")
        assertTrue(ManualCombinationRevalidationPolicy.needsRevalidation(verified, true, "path-b"))
        assertTrue(ManualCombinationRevalidationPolicy.needsRevalidation(verified, true, null))
        assertTrue(ManualCombinationRevalidationPolicy.needsRevalidation(verified.copy(routeIdentity = null), true, "path-a"))
        assertTrue(ManualCombinationRevalidationPolicy.needsRevalidation(verified.copy(origin = CombinationSelectionOrigin.LEGACY_AUTO), true, "path-a"))
        assertFalse(ManualCombinationRevalidationPolicy.needsRevalidation(null, true, "path-a"))
        assertFalse(ManualCombinationRevalidationPolicy.needsRevalidation(verified, false, "path-b"))
    }
}
