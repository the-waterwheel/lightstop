package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class FilmNegativeCameraSessionTest {
    @Test fun previewChoiceDoesNotReplaceMeteringRouteAfterExit() {
        val meterRoute = "rear-main"
        val session = FilmNegativeCameraSession()
        session.open(meterRoute)
        assertTrue(session.select("rear-wide", listOf(meterRoute, "rear-wide")))
        assertEquals("rear-wide", session.expected(meterRoute))
        session.close()
        assertEquals(meterRoute, session.expected(meterRoute))
        session.open(meterRoute)
        assertEquals(meterRoute, session.expected(meterRoute))
    }
    @Test fun unknownOrClosedRouteCannotOverrideCurrentSelection() {
        val session = FilmNegativeCameraSession()
        assertFalse(session.select("wide", listOf("main", "wide")))
        session.open("main")
        assertFalse(session.select("unknown", listOf("main", "wide")))
        assertFalse(session.select("main", listOf("main", "wide")))
        assertEquals("main", session.expected("main"))
    }
    @Test fun unavailablePreviewRouteFallsBackWithoutChangingMeteringChoice() {
        val session = FilmNegativeCameraSession()
        session.open("main")
        session.select("wide", listOf("main", "wide"))
        assertEquals("wide", session.reconcile("main", listOf("main", "wide")))
        assertEquals("main", session.reconcile("main", listOf("main")))
        session.close()
        assertEquals("main", session.expected("main"))
    }
    @Test fun blurSpreadsAnImpulseWithoutEditingTheRetainedSource() {
        val source = IntArray(25) { 0xff000000.toInt() }
        source[12] = -1
        val result = FilmNegativeBlur.blur(source, 5, 5)
        assertEquals(-1, source[12])
        assertTrue((result[12] and 255) in 1..254)
        assertTrue((result[11] and 255) > 0)
        assertTrue(result.all { it ushr 24 == 255 })
    }
    @Test fun blurKeepsUniformColoursAndSinglePixelEdges() {
        val uniform = IntArray(12) { 0xffad718c.toInt() }
        assertArrayEquals(uniform, FilmNegativeBlur.blur(uniform, 3, 4))
        assertArrayEquals(intArrayOf(-1), FilmNegativeBlur.blur(intArrayOf(-1), 1, 1))
    }
}
