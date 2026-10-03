package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt

class FilmNegativeFrameAnalysisTest {
    private val evidence = FilmNegativeFrameEvidence(1, "camera", true, 10_000_000, 100,
        listOf(1f, 1f, 1f, 1f), "fixed")
    private fun frame(width: Int = 512, height: Int = 512, color: (Int, Int) -> Int) =
        FilmNegativeFrozenFrame(width, height, IntArray(width * height) { color(it % width, it / width) }, evidence)
    private fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
    private val whole = FilmNegativeRegion(0f, 0f, 1f, 1f)

    @Test fun ellipseRejectsClippedHolesAndUnevenImageContent() {
        assertNull(FilmNegativeFrameAnalysis.base(frame { _, _ -> rgb(255, 255, 255) }, whole))
        assertNull(FilmNegativeFrameAnalysis.base(frame { x, _ -> if (x < 256) rgb(220, 130, 80) else rgb(80, 40, 10) }, whole))
        assertNull(FilmNegativeFrameAnalysis.base(frame { _, _ -> rgb(220, 130, 80) }, FilmNegativeRegion(.5f, .5f, .501f, .501f)))
    }

    @Test fun clippedSelectionUsesOnlyInBoundsPixels() {
        val sample = FilmNegativeFrameAnalysis.base(frame { _, _ -> rgb(220, 130, 80) }, FilmNegativeRegion(-1f, -1f, 2f, 2f))!!
        assertArrayEquals(floatArrayOf(220f / 255, 130f / 255, 80f / 255), sample, 0.0001f)
    }

    @Test fun autoCandidateIgnoresWhiteBacklightAndFindsClearOrangeRebate() {
        val image = frame { x, _ -> when {
            x < 55 -> rgb(255, 255, 255)
            x < 130 -> rgb(220, 140, 90)
            else -> rgb(95, 70, 50)
        } }
        val candidate = FilmNegativeFrameAnalysis.findBase(image, false)!!
        assertTrue(candidate.region.left > 55f / 512)
        assertTrue(candidate.region.right < 130f / 512)
        assertEquals(220f / 255, candidate.rgb[0], 0.001f)
        assertNull(FilmNegativeFrameAnalysis.findBase(frame { _, _ -> rgb(255, 255, 255) }, false))
    }

    @Test fun imageWindowCompensatesDifferentChannelResponsesOnNeutralRamp() {
        val settings = FilmNegativeSettings()
        val image = frame { x, _ ->
            val density = .12 + x / 511.0 * 1.2
            val channels = IntArray(3) { c ->
                val d = density * doubleArrayOf(.9, 1.0, 1.12)[c] + doubleArrayOf(.05, .02, 0.0)[c]
                (FilmNegativeMath.encode(FilmNegativeMath.linearize(settings.base(c)) * 10.0.pow(-d)) * 255)
                    .roundToInt().coerceIn(0, 255)
            }
            rgb(channels[0], channels[1], channels[2])
        }
        val window = FilmNegativeFrameAnalysis.window(image, whole, settings)!!
        val adjusted = settings.copy(densityWindow = window, gamma = 1f)
        val pixel = image.pixels[256]
        val channels = floatArrayOf(((pixel shr 16) and 255) / 255f,
            ((pixel shr 8) and 255) / 255f, (pixel and 255) / 255f)
        val light = FloatArray(3) { FilmNegativeMath.channel(channels[it], settings.base(it), adjusted, it) }
        assertTrue(light.maxOrNull()!! - light.minOrNull()!! < .035f)
        assertTrue(light.all { it.isFinite() && it > .1f && it < .4f })
    }

    @Test fun flatOrMostlyClippedSelectionCannotBecomeAutoColourWindow() {
        assertNull(FilmNegativeFrameAnalysis.window(frame { _, _ -> rgb(100, 70, 40) }, whole, FilmNegativeSettings()))
        assertNull(FilmNegativeFrameAnalysis.window(frame { _, _ -> rgb(255, 255, 255) }, whole, FilmNegativeSettings()))
    }

    @Test fun manualColourControlsHavePredictableDirections() {
        val gray = floatArrayOf(.2f, .2f, .2f)
        val warm = FilmNegativeMath.displayColor(gray, FilmNegativeSettings(gamma = 1f, warmth = 1f))
        val magenta = FilmNegativeMath.displayColor(gray, FilmNegativeSettings(gamma = 1f, tint = 1f))
        assertTrue(warm[0] > warm[2])
        assertTrue(magenta[0] > magenta[1] && magenta[2] > magenta[1])
    }
}
