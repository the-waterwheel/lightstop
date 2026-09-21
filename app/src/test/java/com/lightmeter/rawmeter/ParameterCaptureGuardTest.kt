package com.lightmeter.rawmeter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterCaptureGuardTest {
    @Test
    fun `stale completion is rejected after a new capture begins`() {
        val guard = ParameterCaptureGuard()
        val first = guard.begin("a")
        val second = guard.begin("b")
        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }

    @Test
    fun `cancel invalidates the active capture`() {
        val guard = ParameterCaptureGuard()
        val token = guard.begin("a")
        guard.cancel()
        assertFalse(guard.isCurrent(token))
        assertFalse(guard.isActive)
    }

    @Test
    fun `a save cannot be started twice for the same capture`() {
        val guard = ParameterCaptureGuard()
        val token = guard.begin("a")
        assertTrue(guard.beginSave(token))
        assertFalse(guard.beginSave(token))
        guard.finishSave()
        assertTrue(guard.beginSave(token))
    }

    @Test
    fun `a stale token cannot reserve a save`() {
        val guard = ParameterCaptureGuard()
        val stale = guard.begin("a")
        guard.begin("b")
        assertFalse(guard.beginSave(stale))
    }

    @Test
    fun `complete ends the capture`() {
        val guard = ParameterCaptureGuard()
        val token = guard.begin("a")
        guard.complete()
        assertFalse(guard.isCurrent(token))
        assertFalse(guard.isActive)
    }
}
