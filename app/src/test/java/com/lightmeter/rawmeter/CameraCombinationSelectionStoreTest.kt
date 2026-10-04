package com.lightmeter.rawmeter

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production serializer without an Android process or touching device preferences. */
class CameraCombinationSelectionStoreTest {
    private class MemoryPreferences {
        val values = mutableMapOf<String, Any?>()
        var writes = 0
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString", "getInt" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putString", "putInt" -> { changes[args!![0] as String] = args[1]; proxy }
                    "remove" -> { changes[args!![0] as String] = null; proxy }
                    "apply" -> {
                        changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        writes++
                        null
                    }
                    else -> error("Unexpected Editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }

    @Test fun `verified selection round trips then expires without erasing intent`() {
        val memory = MemoryPreferences()
        var environment = "os-a|1"
        val store = CameraCombinationSelectionStore(memory.preferences) { environment }
        store.saveVerified("tele", "raw_split_v1", "route-a")
        val expected = CombinationSelection("raw_split_v1", CombinationSelectionOrigin.MANUAL_VERIFIED, "route-a")
        assertEquals(ManualCombinationSelectionRead.Valid(expected), store.readManualSelection("tele"))
        assertEquals(1, memory.writes)
        environment = "os-b|1"
        assertEquals(ManualCombinationSelectionRead.NeedsRevalidation(expected), store.readManualSelection("tele"))
        assertEquals(1, memory.writes)
        store.saveVerified("tele", "raw_split_v1", "route-b")
        assertEquals(ManualCombinationSelectionRead.Valid(expected.copy(routeIdentity = "route-b")), store.readManualSelection("tele"))
    }

    @Test fun `future schema is not parsed as usable or overwritten on read`() {
        val memory = MemoryPreferences()
        val store = CameraCombinationSelectionStore(memory.preferences) { "os-a|1" }
        store.saveVerified("tele", "raw_split_v1", "route-a")
        memory.values[memory.values.keys.single { it.endsWith("_schema") }] = 99
        val before = memory.values.toMap()
        assertTrue(store.readManualSelection("tele") is ManualCombinationSelectionRead.UnsupportedRecord)
        assertEquals(before, memory.values)
        assertEquals(1, memory.writes)
    }

    @Test fun `legacy missing route and origin survive a read for revalidation`() {
        val memory = MemoryPreferences()
        val store = CameraCombinationSelectionStore(memory.preferences) { "os-a|1" }
        assertEquals(ManualCombinationSelectionRead.Missing, store.readManualSelection("tele"))
        store.saveVerified("tele", "raw_split_v1", "route-a")
        memory.values.keys.filter { it.endsWith("_route") || it.endsWith("_origin") }.forEach(memory.values::remove)
        val read = store.readManualSelection("tele") as ManualCombinationSelectionRead.Valid
        assertNull(read.selection.routeIdentity)
        assertTrue(ManualCombinationRevalidationPolicy.needsRevalidation(read.selection, true, "route-a"))
        assertEquals(1, memory.writes)
    }

    @Test fun `legacy intent probe acceptance restore failure and fresh retry preserve approval`() {
        val memory = MemoryPreferences()
        val store = CameraCombinationSelectionStore(memory.preferences) { "os-a|1" }
        store.save("tele", "raw_split_v1", "route-a")
        val intent = ManualCombinationIntentPolicy.resolve(store.readManualSelection("tele"), setOf("raw_split_v1"), null)
        val coordinator = ManualCombinationVerificationCoordinator()
        coordinator.markNeedsRevalidation(intent.planId!!, "tele", 2)
        assertTrue(coordinator.blocksRawMetering())
        val probe = coordinator.begin(intent.planId, "tele", 2)
        coordinator.bindRoute(probe, "tele", "route-a", 5, 2)
        val evidence = coordinator.complete(probe, "route-a", 5, 2)!!
        assertEquals(1, memory.writes) // Hardware success alone never saves human approval.
        coordinator.beginApply(probe, intent.planId, "tele", "route-a", 2, 5)
        val results = mutableListOf<ManualAcceptanceOutcome>()
        val acceptance = ManualAcceptanceTransaction(evidence, CameraCombinationPolicy.rawSplit, results::add)
        store.saveVerified(evidence.selectionCameraId, evidence.planId, evidence.routeIdentity)
        acceptance.markSaved()
        coordinator.fail()
        acceptance.finish(ManualAcceptanceOutcome.RetryRequired(acceptance.approvalSaved, "preview failed"))
        assertEquals(CombinationSelectionOrigin.MANUAL_VERIFIED, store.selectedPlan("tele")!!.origin)
        assertNull(coordinator.beginApply(probe, intent.planId, "tele", "route-a", 2, 5))
        val retry = coordinator.begin(intent.planId, "tele", 3)
        assertNotEquals(probe, retry)
        coordinator.bindRoute(retry, "tele", "route-a", 7, 3)
        val fresh = coordinator.complete(retry, "route-a", 7, 3)!!
        coordinator.failProbe(probe)
        assertNotNull(coordinator.beginApply(retry, intent.planId, "tele", "route-a", 3, 7))
        assertTrue(coordinator.confirm(fresh, "tele", "route-a", 3, 7))
        assertFalse(acceptance.finish(ManualAcceptanceOutcome.Accepted))
        assertEquals(1, results.size)
        assertEquals(2, memory.writes)
    }

    @Test fun `cancelled probe cannot upgrade or erase the legacy record`() {
        val memory = MemoryPreferences()
        val store = CameraCombinationSelectionStore(memory.preferences) { "os-a|1" }
        store.save("tele", "raw_split_v1", "route-a")
        val before = memory.values.toMap()
        val coordinator = ManualCombinationVerificationCoordinator()
        val probe = coordinator.begin("raw_split_v1", "tele", 2)
        coordinator.bindRoute(probe, "tele", "route-a", 5, 2)
        coordinator.invalidateExternal(3)
        assertNull(coordinator.complete(probe, "route-a", 5, 2))
        assertNull(coordinator.beginApply(probe, "raw_split_v1", "tele", "route-a", 2, 5))
        assertEquals(before, memory.values)
        assertEquals(1, memory.writes)
    }
}
