package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class FilmNegativeBaseDetectorTest {
    private val base = rgb(220, 140, 90)
    private fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
    private fun image(w: Int = 192, h: Int = 144, color: (Int, Int) -> Int) =
        IntArray(w * h) { color(it % w, it / w) }
    private fun photo(x: Int, y: Int): Int {
        val shade = (x / 12 + y / 12) % 4 * 8
        return rgb(85 + shade, 62 + shade, 40 + shade)
    }
    private fun find(w: Int = 192, h: Int = 144, monochrome: Boolean = false,
        color: (Int, Int) -> Int) = FilmNegativeBaseDetector.find(w, h, image(w, h, color), monochrome)

    @Test fun detectsVerticalRebateInsideClippedBareLightAndDarkHolder() {
        val candidate = find { x, y -> when {
            x < 9 -> rgb(255, 255, 255)
            x < 24 -> base
            x >= 180 -> rgb(0, 0, 0)
            else -> photo(x, y)
        } }!!
        assertArrayEquals(floatArrayOf(220f / 255, 140f / 255, 90f / 255), candidate.rgb, 0.001f)
        assertTrue(candidate.region.left >= 9f / 192 && candidate.region.right <= 24f / 192)
        assertTrue(candidate.confidence >= 0.75f)
    }

    @Test fun detectsHorizontalRebateWithSprocketHolesInOuterRail() {
        val candidate = find { x, y -> when {
            y < 18 && y < 8 && x % 24 < 10 -> rgb(255, 255, 255)
            y < 18 -> base
            else -> photo(x, y)
        } }!!
        assertTrue(candidate.region.top >= 8f / 144 && candidate.region.bottom <= 18f / 144)
        assertEquals(140f / 255, candidate.rgb[1], 0.001f)
    }

    @Test fun sameDetectorWorksAfterRotationAndMirroring() {
        val original = image { x, y -> if (x < 18) base else photo(x, y) }
        val mirrored = IntArray(original.size) { i -> original[(i / 192) * 192 + 191 - i % 192] }
        val rotated = IntArray(original.size) { i ->
            val x = i % 144; val y = i / 144
            original[(143 - x) * 192 + y]
        }
        val reference = FilmNegativeBaseDetector.find(192, 144, original, false)!!.rgb
        assertArrayEquals(reference, FilmNegativeBaseDetector.find(192, 144, mirrored, false)!!.rgb, 0.001f)
        assertArrayEquals(reference, FilmNegativeBaseDetector.find(144, 192, rotated, false)!!.rgb, 0.001f)
    }

    @Test fun acceptsLowNoiseAndSparseDustWithoutChoosingTheDust() {
        val candidate = find { x, y ->
            if (x < 21) {
                if (x % 13 == 0 && y % 17 == 0) rgb(8, 7, 6)
                else { val noise = (x + y) % 5 - 2; rgb(220 + noise, 140 + noise, 90 + noise) }
            } else photo(x, y)
        }!!
        assertArrayEquals(floatArrayOf(220f / 255, 140f / 255, 90f / 255), candidate.rgb, 3f / 255)
    }

    @Test fun samplesLargeFramesWithoutChangingReferenceOrAspectRatio() {
        val candidate = find(1280, 960) { x, y -> if (x < 128) base else photo(x / 7, y / 7) }!!
        assertEquals(220f / 255, candidate.rgb[0], 0.001f)
        assertTrue(candidate.region.right <= 0.1f)
    }

    @Test fun monochromeRebateUsesNeutralColorCriteria() {
        val candidate = find(monochrome = true) { x, y ->
            if (y < 18) rgb(215, 213, 211) else rgb(70 + x % 4, 70 + x % 4, 70 + x % 4)
        }!!
        assertEquals(215f / 255, candidate.rgb[0], 0.001f)
        assertNull(find { _, y -> if (y < 18) rgb(215, 213, 211) else rgb(90, 90, 90) })
        assertNull(find(monochrome = true) { x, y -> if (x < 18) base else photo(x, y) })
    }

    @Test fun rejectsClippedAndUnclippedWhiteBacklight() {
        assertNull(find { _, _ -> rgb(255, 255, 255) })
        assertNull(find { x, y -> if (x < 18) rgb(225, 222, 220) else photo(x, y) })
        assertNull(find { x, y -> if (x < 18) rgb(255, 140, 90) else photo(x, y) })
    }

    @Test fun rejectsTinyOrangeObjectsAndCentralBands() {
        assertNull(find { x, y -> if (x in 2..24 && y in 35..68) base else photo(x, y) })
        assertNull(find { x, y -> if (y in 62..79) base else photo(x, y) })
    }

    @Test fun noBorderAndUniformOrangeFieldsCannotEstablishFilmBase() {
        assertNull(find { _, _ -> base })
        assertNull(find { x, y -> photo(x, y) })
        assertNull(find { x, _ -> if (x < 18) base else rgb(0, 0, 0) })
    }

    @Test fun darkOrangeFrameCannotMasqueradeAsAHighlyTransmissiveRebate() {
        assertNull(find { x, _ -> if (x < 18) rgb(100, 70, 40) else rgb(210, 180, 155) })
    }

    @Test fun competingBorderColorsAreRejectedAsAmbiguous() {
        assertNull(find { x, y -> when {
            x < 18 -> rgb(225, 150, 90)
            x >= 174 -> rgb(210, 155, 100)
            else -> photo(x, y)
        } })
    }

    @Test fun connectedLightingGradientMustAlsoPassGlobalUniformity() {
        assertNull(find { x, y -> if (x < 18) {
            val gradient = y * 40 / 143
            rgb(205 + gradient, 125 + gradient, 75 + gradient)
        } else photo(x, y) })
    }

    @Test fun malformedAndVerySmallFramesAreRejected() {
        assertNull(FilmNegativeBaseDetector.find(0, 100, intArrayOf(), false))
        assertNull(FilmNegativeBaseDetector.find(Int.MAX_VALUE, Int.MAX_VALUE, intArrayOf(), false))
        assertNull(FilmNegativeBaseDetector.find(192, 144, IntArray(10), false))
        assertNull(find(12, 12) { _, _ -> base })
    }
}
