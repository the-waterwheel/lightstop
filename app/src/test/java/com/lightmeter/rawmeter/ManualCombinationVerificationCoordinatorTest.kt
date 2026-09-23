package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCombinationVerificationCoordinatorTest {
    @Test
    fun `late failure cannot erase a newer running or successful probe`() {
        val coordinator = ManualCombinationVerificationCoordinator()
        val oldProbe = coordinator.begin("raw_split_v1", "tele", 1)
        val newProbe = coordinator.begin("raw_split_v1", "tele", 1)
        coordinator.failProbe(oldProbe)
        assertEquals(ManualCombinationVerificationState.PROBING, coordinator.state)
        val evidence = coordinator.complete(newProbe, "route-a", 8, 1)!!
        coordinator.failProbe(oldProbe)
        assertEquals(ManualCombinationVerificationState.AWAITING_ACCEPT, coordinator.state)
        assertEquals(evidence, coordinator.beginApply(newProbe, "raw_split_v1", "tele", "route-a", 1))
    }

    @Test
    fun `probe evidence binds plan camera route owner and completed generation`() {
        val coordinator = ManualCombinationVerificationCoordinator()
        val probeId = coordinator.begin("raw_split_v1", "tele", ownerEpoch = 7)

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
        val evidence = coordinator.complete(probeId, "route-a", 5, ownerEpoch = 2)!!

        assertNull(coordinator.beginApply(probeId, "raw_split_v1", "tele", "route-b", 2))
        assertEquals(ManualCombinationVerificationState.AWAITING_ACCEPT, coordinator.state)
        assertNull(coordinator.beginApply(probeId + 1, "raw_split_v1", "tele", "route-a", 2))

        assertEquals(evidence, coordinator.beginApply(probeId, "raw_split_v1", "tele", "route-a", 2))
        coordinator.confirm(evidence)
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
}
