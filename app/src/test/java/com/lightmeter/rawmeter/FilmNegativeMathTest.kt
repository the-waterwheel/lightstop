package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt

class FilmNegativeMathTest {
    @Test fun denserNegativeBecomesBrighterAndClearBaseBecomesBlack() {
        val settings = FilmNegativeSettings(gamma = 1f)
        assertEquals(0f, FilmNegativeMath.channel(0.6f, 0.6f, settings), 1e-6f)
        assertTrue(FilmNegativeMath.channel(0.1f, 0.6f, settings) >
            FilmNegativeMath.channel(0.3f, 0.6f, settings))
    }

    @Test fun equalRelativeTransmissionIsNeutralDespiteOrangeBase() {
        val settings = FilmNegativeSettings(gamma = 1f)
        val base = floatArrayOf(0.9f, 0.6f, 0.35f)
        val outputs = base.map { encoded ->
            val linearBase = ((encoded + 0.055) / 1.055).pow(2.4)
            val transmitted = linearBase * 0.1
            val input = if (transmitted <= 0.0031308) transmitted * 12.92
            else 1.055 * transmitted.pow(1.0 / 2.4) - 0.055
            FilmNegativeMath.channel(input.toFloat(), encoded, settings)
        }
        val black = 10.0.pow(-settings.whiteDensity.toDouble() / settings.filmGamma)
        val expected = ((10.0.pow((1.0 - settings.whiteDensity) / settings.filmGamma) - black) / (1 - black)).toFloat()
        outputs.forEach { assertEquals(expected, it, 1e-5f) }
    }

    @Test fun lookupIsBoundedAndMonotonicAcrossEachChannel() {
        val settings = FilmNegativeSettings(blackDensity = -0.2f, whiteDensity = 1.8f,
            gamma = 0.7f, exposureEv = 1.2f)
        val lookup = FilmNegativeMath.lookup(settings)
        assertEquals(768, lookup.size)
        for (channel in 0..2) for (value in 1..255) {
            assertTrue((lookup[value * 3 + channel].toInt() and 255) <=
                (lookup[(value - 1) * 3 + channel].toInt() and 255))
        }
    }

    @Test fun zeroInputAndCollapsedWindowRemainFinite() {
        val settings = FilmNegativeSettings(blackDensity = 1f, whiteDensity = 1f)
        for (value in listOf(0f, 0.5f, 1f)) {
            val output = FilmNegativeMath.channel(value, 0f, settings)
            assertTrue(output.isFinite() && output in 0f..16f)
        }
    }

    @Test fun manualSamplingRejectsClippedLightAndUsesRobustMedian() {
        val bytes = ByteArray(20 * 4)
        for (pixel in 0 until 20) {
            val rgb = if (pixel < 19) intArrayOf(210, 140, 80) else intArrayOf(249, 248, 247)
            for (channel in 0..2) bytes[pixel * 4 + channel] = rgb[channel].toByte()
            bytes[pixel * 4 + 3] = 255.toByte()
        }
        val sample = FilmNegativeMath.sampleBase(bytes)
        assertNotNull(sample)
        assertEquals(210f / 255f, sample!![0], 1e-6f)
        assertEquals(80f / 255f, sample[2], 1e-6f)
        assertNull(FilmNegativeMath.sampleBase(ByteArray(64 * 4) { 255.toByte() }))
        assertNull(FilmNegativeMath.sampleBase(ByteArray(64 * 4)))
    }

    @Test fun oneEvDoublesLinearLightBeforeShoulder() {
        val settings = FilmNegativeSettings(gamma = 1f)
        val neutral = floatArrayOf(0.1f, 0.1f, 0.1f)
        val original = FilmNegativeMath.displayColor(neutral, settings)
        val brighter = FilmNegativeMath.displayColor(neutral, settings.copy(exposureEv = 1f))
        assertEquals(2.0, FilmNegativeMath.linearize(brighter[0]) / FilmNegativeMath.linearize(original[0]), 1e-5)
    }

    @Test fun filmWhiteHasHeadroomAndExposureDoesNotRebuildTheCurve() {
        val settings = FilmNegativeSettings(whiteDensity = 1f)
        val atWhite = FilmNegativeMath.encode(FilmNegativeMath.linearize(0.9f) * 0.1)
        assertEquals(1f, FilmNegativeMath.channel(atWhite, 0.9f, settings), 1e-5f)
        assertTrue(FilmNegativeMath.channel(atWhite / 2, 0.9f, settings) > 1f)
        org.junit.Assert.assertArrayEquals(FilmNegativeMath.lookup(settings),
            FilmNegativeMath.lookup(settings.copy(exposureEv = 2f, saturation = 0f, gamma = 2f)))
    }

    @Test fun shoulderIsMonotonicNeutralAndKeepsGamutBoundedAtExtremeSettings() {
        var previous = 0f
        for (i in 0..100) {
            val value = i / 10f
            val rgb = FilmNegativeMath.displayColor(floatArrayOf(value, value, value), FilmNegativeSettings(gamma = 1f))
            assertTrue(rgb[0] >= previous)
            assertEquals(rgb[0], rgb[1], 1e-6f)
            assertEquals(rgb[1], rgb[2], 1e-6f)
            previous = rgb[0]
        }
        for (gamma in listOf(0.3f, 3f)) for (ev in listOf(-3f, 3f)) for (sat in listOf(0f, 2f)) {
            val rgb = FilmNegativeMath.displayColor(floatArrayOf(16f, 0.1f, 0f),
                FilmNegativeSettings(gamma = gamma, exposureEv = ev, saturation = sat))
            assertTrue(rgb.all { it.isFinite() && it in 0f..1f })
        }
    }

    @Test fun rejectMostlyClippedAndSpatiallyUnevenBase() {
        val mostlyClipped = ByteArray(16 * 16 * 4) { 255.toByte() }
        repeat(16) { p -> repeat(3) { c -> mostlyClipped[p * 4 + c] = 100 } }
        assertNull(FilmNegativeMath.sampleBase(mostlyClipped))
        val uneven = ByteArray(16 * 16 * 4) { index ->
            if (index % 4 == 3) 255.toByte() else if ((index / 4) % 16 < 8) 80 else 100
        }
        assertNull(FilmNegativeMath.sampleBase(uneven))
        val uniform = ByteArray(16 * 16 * 4) { index ->
            if (index % 4 == 3) 255.toByte() else (100 + index / 4 % 3).toByte()
        }
        assertNotNull(FilmNegativeMath.sampleBase(uniform))
    }

    @Test fun lookupPackingPreservesDeepShadowsInsteadOfRoundingThemToBlack() {
        for (light in listOf(0.0001f, 0.001f, 0.01f, 0.1f, 1f, 8f, 16f)) {
            val quantized = (FilmNegativeMath.packLight(light) * 255).roundToInt() / 255f
            val restored = FilmNegativeMath.unpackLight(quantized)
            assertTrue(restored > 0f)
            assertEquals(light, restored, maxOf(0.00005f, light * 0.15f))
        }
    }
}
