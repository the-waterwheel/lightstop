package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ProcessedLumaMathTest {
    private val srgbDecoder = ProcessedLumaDecoder(ProcessedTransfer.SRGB)

    @Test
    fun fullRangeNeutralYuvMatchesSrgbGray() {
        val rgb = ProcessedLumaMath.yuvToEncodedRgb(
            yCode = 128,
            uCode = 128,
            vCode = 128,
            encoding = YuvColorEncoding(),
        )

        assertEquals(128.0 / 255.0, rgb.red, 1e-9)
        assertEquals(rgb.red, rgb.green, 1e-9)
        assertEquals(rgb.red, rgb.blue, 1e-9)
        assertEquals(srgbToLinearForTest(128.0 / 255.0), srgbDecoder.linearLuma(rgb.red, rgb.green, rgb.blue), 1e-5)
    }

    @Test
    fun chromaIsReconstructedBeforeLinearLuma() {
        // JFIF/Rec.601 code values for an almost pure sRGB red pixel.
        val rgb = ProcessedLumaMath.yuvToEncodedRgb(
            yCode = 76,
            uCode = 85,
            vCode = 255,
            encoding = YuvColorEncoding(),
        )
        val corrected = srgbDecoder.linearLuma(rgb.red, rgb.green, rgb.blue)
        val oldYOnlyApproximation = srgbToLinearForTest(76.0 / 255.0)

        assertTrue(rgb.red > 0.98)
        assertTrue(rgb.green < 0.01)
        assertTrue(rgb.blue < 0.02)
        assertEquals(0.2126, corrected, 0.005)
        assertTrue(corrected > oldYOnlyApproximation * 2.5)
        assertTrue(rgb.clipped)
    }

    @Test
    fun limitedRangeUsesDocumentedYAndChromaEndpoints() {
        val black = ProcessedLumaMath.yuvToEncodedRgb(
            16,
            128,
            128,
            YuvColorEncoding(range = YuvCodeRange.LIMITED),
        )
        val white = ProcessedLumaMath.yuvToEncodedRgb(
            235,
            128,
            128,
            YuvColorEncoding(range = YuvCodeRange.LIMITED),
        )

        assertEquals(0.0, black.red, 1e-9)
        assertEquals(1.0, white.red, 1e-9)
        assertFalse(black.clipped)
        assertTrue(white.clipped)
    }

    @Test
    fun regionMedianRejectsAHighlightOutlier() {
        val values = doubleArrayOf(0.12, 0.12, 1.0, 0.12)

        assertEquals(0.12, ProcessedLumaMath.median(values)!!, 1e-9)
    }

    @Test
    fun reportedTonemapCurveIsInvertedBackToLinearInput() {
        val curve = ProcessedToneCurve(
            doubleArrayOf(
                0.0, 0.0,
                0.25, 0.5,
                1.0, 1.0,
            ),
        )
        val decoder = ProcessedLumaDecoder(ProcessedTransfer.CURVES(curve, curve, curve))

        assertEquals(0.25, decoder.linearLuma(0.5, 0.5, 0.5), 2e-4)
    }

    @Test
    fun gammaMetadataUsesTheDocumentedInverse() {
        val decoder = ProcessedLumaDecoder(ProcessedTransfer.GAMMA(2.2))

        assertEquals(0.5.pow(2.2), decoder.linearLuma(0.5, 0.5, 0.5), 2e-5)
    }

    @Test
    fun malformedVendorCurveFallsBackToSrgb() {
        val malformed = ProcessedToneCurve(
            doubleArrayOf(
                0.0, 0.0,
                0.5, 0.8,
                1.0, 0.7,
            ),
        )
        val decoder = ProcessedLumaDecoder(
            ProcessedTransfer.CURVES(malformed, malformed, malformed),
        )

        assertEquals(srgbToLinearForTest(0.5), decoder.linearLuma(0.5, 0.5, 0.5), 2e-5)
    }

    private fun srgbToLinearForTest(value: Double): Double =
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
}
