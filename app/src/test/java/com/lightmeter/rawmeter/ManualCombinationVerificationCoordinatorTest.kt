package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCombinationVerificationCoordinatorTest {
    @Test
    fun `out of order invalidation cannot erase a newer owner`() {
        val coordinator = ManualCombinationVerificationCoordinator()
        val id = coordinator.begin("raw_split_v1", "tele", 5)
        coordinator.bindRoute(id, "tele", "route-a", 8, 5)
        coordinator.invalidateExternal(4)
        assertNotNull(coordinator.complete(id, "route-a", 8, 5))
        coordinator.invalidateExternal(6)
        assertEquals(ManualCombinationVerificationState.FAILED, coordinator.state)
    }

    @Test
    fun `late failure cannot erase a newer running or successful probe`() {
        val coordinator = ManualCombinationVerificationCoordinator()
        val oldProbe = coordinator.begin("raw_split_v1", "tele", 1)
        val newProbe = coordinator.begin("raw_split_v1", "tele", 1)
        assertTrue(coordinator.bindRoute(newProbe, "tele", "route-a", 8, 1))
        coordinator.failProbe(oldProbe)
        assertEquals(ManualCombinationVerificationState.PROBING, coordinator.state)
        val evidence = coordinator.complete(newProbe, "route-a", 8, 1)!!
        coordinator.failProbe(oldProbe)
        assertEquals(ManualCombinationVerificationState.AWAITING_ACCEPT, coordinator.state)
        assertEquals(evidence, coordinator.beginApply(newProbe, "raw_split_v1", "tele", "route-a", 1, 8))
    }

    @Test
    fun `probe evidence binds plan camera route owner and completed generation`() {
        val coordinator = ManualCombinationVerificationCoordinator()
        val probeId = coordinator.begin("raw_split_v1", "tele", ownerEpoch = 7)
        assertTrue(coordinator.bindRoute(probeId, "tele", "logical|tele|FIXED_PHYSICAL|1920|1080", 23, 7))

        val evidence = coordinator.complete(
            probeId = probeId,
            routeIdentity = "logical|tele|FIXED_PHYSICAL|1920|1080",
            completedCameraGeneration = 23,
            ownerEpoch = 7,
        )

        assertNotNull(evidence)
        assertEquals("tele", evidence?.selectionCameraId)
        assertEquals(23, evidence?.completedCameraGeneration)
        assertEquals(ManualCombinationVerificationState.AWAITING_ACCEPT, coordinator.state)
        assertTrue(coordinator.blocksRawMetering())
    }

    @Test
    fun `accept rejects stale route or probe and retains the unaccepted evidence`() {
        val coordinator = ManualCombinationVerificationCoordinator()
        val probeId = coordinator.begin("raw_split_v1", "tele", ownerEpoch = 2)
        assertTrue(coordinator.bindRoute(probeId, "tele", "route-a", 5, 2))
        val evidence = coordinator.complete(probeId, "route-a", 5, ownerEpoch = 2)!!

        assertNull(coordinator.beginApply(probeId, "raw_split_v1", "tele", "route-b", 2, 5))
        assertEquals(ManualCombinationVerificationState.AWAITING_ACCEPT, coordinator.state)
        assertNull(coordinator.beginApply(probeId + 1, "raw_split_v1", "tele", "route-a", 2, 5))

        assertNull(coordinator.beginApply(probeId, "raw_split_v1", "tele", "route-a", 2, 6))
        assertEquals(evidence, coordinator.beginApply(probeId, "raw_split_v1", "tele", "route-a", 2, 5))
        assertTrue(coordinator.confirm(evidence, "tele", "route-a", 2, 5))
        assertEquals(ManualCombinationVerificationState.CONFIRMED, coordinator.state)
        assertFalse(coordinator.blocksRawMetering())
    }

    @Test
    fun `external lifecycle change invalidates a running or completed probe`() {
        val coordinator = ManualCombinationVerificationCoordinator()
        val probeId = coordinator.begin("raw_split_v1", "tele", ownerEpoch = 3)
        coordinator.invalidateExternal(ownerEpoch = 4)
        assertEquals(ManualCombinationVerificationState.FAILED, coordinator.state)
        assertNull(coordinator.complete(probeId, "route-a", 6, ownerEpoch = 3))

        val secondProbe = coordinator.begin("raw_split_v1", "tele", ownerEpoch = 4)
        assertNull(coordinator.complete(secondProbe, null, 7, ownerEpoch = 4))
        assertEquals(ManualCombinationVerificationState.PROBING, coordinator.state)
    }

    @Test
    fun `resident completion must still match the applying evidence`() {
        val coordinator = ManualCombinationVerificationCoordinator()
        val id = coordinator.begin("raw_split_v1", "tele", 2)
        assertTrue(coordinator.bindRoute(id, "tele", "route-a", 5, 2))
        val evidence = coordinator.complete(id, "route-a", 5, 2)!!
        coordinator.beginApply(id, "raw_split_v1", "tele", "route-a", 2, 5)
        assertNull(coordinator.beginApply(id, "raw_split_v1", "tele", "route-a", 2, 5))
        assertFalse(coordinator.confirm(evidence, "main", "route-a", 2, 5))
        assertFalse(coordinator.confirm(evidence, "tele", "route-b", 2, 5))
        assertFalse(coordinator.confirm(evidence, "tele", "route-a", 3, 5))
        assertFalse(coordinator.confirm(evidence, "tele", "route-a", 2, 6))
        assertTrue(coordinator.blocksRawMetering())
        assertTrue(coordinator.confirm(evidence, "tele", "route-a", 2, 5))
        assertFalse(coordinator.confirm(evidence, "tele", "route-a", 2, 5))
    }

    @Test
    fun `cancelled applying evidence cannot complete a later resident session`() {
        val coordinator = ManualCombinationVerificationCoordinator()
        val id = coordinator.begin("raw_split_v1", "tele", 2)
        assertTrue(coordinator.bindRoute(id, "tele", "route-a", 5, 2))
        val evidence = coordinator.complete(id, "route-a", 5, 2)!!
        coordinator.beginApply(id, "raw_split_v1", "tele", "route-a", 2, 5)
        coordinator.invalidateExternal(3)
        assertFalse(coordinator.confirm(evidence, "tele", "route-a", 2, 5))
        assertEquals(ManualCombinationVerificationState.FAILED, coordinator.state)
    }

    @Test
    fun `probe cannot sign the route observed only after completion`() {
        val coordinator = ManualCombinationVerificationCoordinator()
        val id = coordinator.begin("raw_split_v1", "tele", 2)
        assertNull(coordinator.complete(id, "route-a", 5, 2))
        assertFalse(coordinator.bindRoute(id, "main", "route-a", 5, 2))
        assertTrue(coordinator.bindRoute(id, "tele", "route-a", 5, 2))
        assertTrue(coordinator.bindRoute(id, "tele", "route-a", 5, 2))
        assertFalse(coordinator.bindRoute(id, "tele", "route-b", 5, 2))
        assertFalse(coordinator.bindRoute(id, "tele", "route-a", 6, 2))
        assertNull(coordinator.complete(id, "route-b", 5, 2))
        assertNull(coordinator.complete(id, "route-a", 6, 2))
        assertNotNull(coordinator.complete(id, "route-a", 5, 2))
    }
}
