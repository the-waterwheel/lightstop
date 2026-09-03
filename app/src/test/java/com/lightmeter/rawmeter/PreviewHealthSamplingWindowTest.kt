package com.lightmeter.rawmeter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewHealthSamplingWindowTest {
    @Test
    fun `sampling is active only inside a restarted bounded window`() {
        val window = PreviewHealthSamplingWindow(durationNs = 6_000_000_000L)

        assertFalse(window.isActive(1L))
        window.restart(10L)
        assertTrue(window.isActive(6_000_000_009L))
        assertFalse(window.isActive(6_000_000_010L))
        assertFalse(window.isActive(6_000_000_011L))
    }

    @Test
    fun `stopping a window immediately disables sampling`() {
        val window = PreviewHealthSamplingWindow(durationNs = 100L)

        window.restart(5L)
        window.stop()

        assertFalse(window.isActive(6L))
    }
}
