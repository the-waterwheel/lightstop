package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingQualityPolicyTest {
    private fun reading(
        source: MeteringSource = MeteringSource.RAW,
        luma: Double = 0.5,
        clipped: Double = 0.0,
    ) = MeterReading(
        sceneEv100 = 10.0,
        rawLuma = luma,
        clippedFraction = clipped,
        frameCount = 1,
        captureIso = 100,
        exposureTimeNs = 1_000_000L,
        aperture = 2.0f,
        source = source,
    )

    @Test
    fun `no reading has no label and no warning`() {
        val quality = ReadingQualityPolicy.evaluate(null)
        assertNull(quality.sourceLabel)
        assertFalse(quality.lowConfidence)
    }

    @Test
    fun `clean raw reading is trusted`() {
        val quality = ReadingQualityPolicy.evaluate(reading())
        assertEquals("RAW", quality.sourceLabel)
        assertFalse(quality.lowConfidence)
    }

    @Test
    fun `processed sources are always labelled low confidence`() {
        assertTrue(
            ReadingQualityPolicy.evaluate(reading(source = MeteringSource.YUV_PREVIEW)).lowConfidence,
        )
        assertTrue(
            ReadingQualityPolicy.evaluate(reading(source = MeteringSource.ISP_PREVIEW)).lowConfidence,
        )
    }

    @Test
    fun `clipping and weak signal lower confidence`() {
        assertTrue(
            ReadingQualityPolicy.evaluate(
                reading(clipped = ReadingQualityPolicy.CLIPPED_FRACTION_LIMIT + 0.01),
            ).lowConfidence,
        )
        assertTrue(
            ReadingQualityPolicy.evaluate(reading(luma = 0.0001)).lowConfidence,
        )
    }
}
