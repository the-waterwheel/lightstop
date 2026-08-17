package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraStreamSelectorTest {
    @Test
    fun `never selects advertised 60 fps range when ceiling is 30`() {
        val selected = CameraStreamSelector.selectFpsRangeBounds(
            listOf(15 to 30, 30 to 30, 30 to 60, 60 to 60),
            requestedCeiling = 30,
        )

        assertEquals(30 to 30, selected)
    }

    @Test
    fun `omits explicit request when no advertised range stays under target`() {
        val selected = CameraStreamSelector.selectFpsRangeBounds(
            listOf(15 to 30, 30 to 60),
            requestedCeiling = 24,
        )

        assertNull(selected)
    }

    @Test
    fun `returns no request when camera advertises no ranges`() {
        assertNull(CameraStreamSelector.selectFpsRangeBounds(emptyList(), requestedCeiling = 30))
    }
}
