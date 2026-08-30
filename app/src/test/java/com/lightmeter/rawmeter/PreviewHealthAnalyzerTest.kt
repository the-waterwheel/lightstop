package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewHealthAnalyzerTest {
    @Test
    fun normalGradientRemainsHealthy() {
        val monitor = PreviewHealthMonitor()

        val decisions = (0 until 10).map { frame ->
            val metrics = requireNotNull(PreviewHealthAnalyzer.analyzeArgb(64, 64, gradient(frame * 5)))
            monitor.observe(metrics, frame.toLong() + 1L)
        }

        assertTrue(decisions.all { it.state != PreviewHealthState.FAILED })
        assertEquals(PreviewHealthState.HEALTHY, decisions.last().state)
    }

    @Test
    fun flatSaturatedGreenOutputFailsAfterAConservativeWindow() {
        val metrics = requireNotNull(PreviewHealthAnalyzer.analyzeArgb(64, 64, solid(0xff00ff00.toInt())))
        val monitor = PreviewHealthMonitor()

        repeat(12) { monitor.observe(metrics, it.toLong() + 1L) }

        val decision = monitor.observe(metrics, 20L)
        assertEquals(PreviewHealthState.FAILED, decision.state)
        assertEquals(PreviewHealthReason.GREEN_DOMINANT, decision.reason)
    }

    @Test
    fun variedGreenSceneNeverAutomaticallyFails() {
        val monitor = PreviewHealthMonitor()

        val decisions = (0 until 18).map { frame ->
            val metrics = requireNotNull(
                PreviewHealthAnalyzer.analyzeArgb(64, 64, variedGreen(frame)),
            )
            monitor.observe(metrics, frame.toLong() + 1L)
        }

        assertTrue(decisions.none { it.state == PreviewHealthState.FAILED })
    }

    @Test
    fun repeatedBlackAndWhiteRowsFailAfterAContinuousWindow() {
        val metrics = requireNotNull(PreviewHealthAnalyzer.analyzeArgb(64, 64, horizontalStripes()))
        val monitor = PreviewHealthMonitor()

        repeat(8) { monitor.observe(metrics, it.toLong() + 1L) }

        val decision = monitor.observe(metrics, 20L)
        assertEquals(PreviewHealthState.FAILED, decision.state)
        assertEquals(PreviewHealthReason.HORIZONTAL_STRIPES, decision.reason)
    }

    @Test
    fun allBlackOutputIsOnlySuspect() {
        val metrics = requireNotNull(PreviewHealthAnalyzer.analyzeArgb(64, 64, solid(0xff000000.toInt())))
        val monitor = PreviewHealthMonitor()

        repeat(10) { monitor.observe(metrics, it.toLong() + 1L) }

        assertEquals(PreviewHealthState.SUSPECT, monitor.observe(metrics, 12L).state)
    }

    private fun gradient(offset: Int): IntArray = IntArray(64 * 64) { index ->
        val value = (((index % 64 + offset) % 64) * 255 / 63)
        0xff000000.toInt() or (value shl 16) or (value shl 8) or value
    }

    private fun solid(color: Int): IntArray = IntArray(64 * 64) { color }

    private fun horizontalStripes(): IntArray = IntArray(64 * 64) { index ->
        if ((index / 64) % 2 == 0) 0xffffffff.toInt() else 0xff000000.toInt()
    }

    private fun variedGreen(frame: Int): IntArray = IntArray(64 * 64) { index ->
        val x = index % 64
        val y = index / 64
        val green = (90 + (x * 3 + y * 2 + frame * 5) % 150).coerceAtMost(255)
        val red = 20 + (x + frame) % 70
        val blue = 15 + (y * 2 + frame) % 65
        0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
    }
}
