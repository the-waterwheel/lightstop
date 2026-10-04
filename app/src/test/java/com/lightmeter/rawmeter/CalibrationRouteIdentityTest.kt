package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationRouteIdentityTest {
    @Test
    fun `selected route remains stable while logical active physical metadata resets`() {
        assertEquals("0", CalibrationRouteIdentity.resolve("0", "0"))
        assertEquals("0@2", CalibrationRouteIdentity.resolve("0@2", "0@2"))
    }

    @Test
    fun `requested route is used before camera info is ready`() {
        assertEquals("0@3", CalibrationRouteIdentity.resolve("", "0@3"))
        assertEquals("0", CalibrationRouteIdentity.resolve("", null))
    }

    @Test
    fun `temporary unknown physical lens does not conflict but a different known lens does`() {
        val main = CalibrationCaptureIdentity("0", "2")

        assertEquals(false, main.conflictsWith(CalibrationCaptureIdentity("0", null)))
        assertEquals(true, main.conflictsWith(CalibrationCaptureIdentity("0", "3")))
    }
}
