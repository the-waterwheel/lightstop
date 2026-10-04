package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivePhysicalCameraSelectorTest {
    @Test
    fun requestedPhysicalCameraTakesPrecedenceOverAReportedLogicalSwitch() {
        assertEquals("2", ActivePhysicalCameraSelector.select("2", "3"))
    }

    @Test
    fun automaticLogicalCameraUsesTheReportedActivePhysicalId() {
        assertEquals("3", ActivePhysicalCameraSelector.select(null, "3"))
    }

    @Test
    fun automaticLogicalCameraStaysUnknownWhenThePlatformDoesNotReportAnId() {
        assertNull(ActivePhysicalCameraSelector.select(null, null))
    }
}
