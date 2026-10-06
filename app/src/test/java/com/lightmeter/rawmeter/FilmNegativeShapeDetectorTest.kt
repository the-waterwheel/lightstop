package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FilmNegativeShapeDetectorTest {
    private val evidence = FilmNegativeFrameEvidence(100_000_000, "camera:session", true,
        10_000_000, 100, listOf(1f, 1f, 1f, 1f), "fixed")
    private val base = floatArrayOf(220f / 255, 140f / 255, 90f / 255)
    private fun color(values: FloatArray): Int = (255 shl 24) or
        (values[0].times(255).roundToInt().coerceIn(0, 255) shl 16) or
        (values[1].times(255).roundToInt().coerceIn(0, 255) shl 8) or
        values[2].times(255).roundToInt().coerceIn(0, 255)
    private data class Scene(val frame: FilmNegativeFrozenFrame, val truth: FilmNegativeMask)
    private fun scene(angle: Double = 23.0, aspect: Double = 1.5, curve: Double = 0.0,
        perspective: Double = 0.0, noise: Int = 0, dx: Int = 0, border: Double = 6.0,
        monochrome: Boolean = false, paleDetail: Boolean = false): Scene {
        val w = 224; val h = 224; val theta = Math.toRadians(angle)
        val halfW = if (aspect >= 1) 68.0 else 68.0 * aspect
        val halfH = if (aspect >= 1) 68.0 / aspect else 68.0
        val reference = if (monochrome) floatArrayOf(.86f, .86f, .86f) else base
        val truth = BooleanArray(w * h)
        val pixels = IntArray(w * h) { i ->
            val x = i % w - w / 2.0 - dx; val y = i / w - h / 2.0
            val u = (x * cos(theta) + y * sin(theta))
            val v = (-x * sin(theta) + y * cos(theta)) - curve * ((u / halfW).pow(2) - .5)
            val warpedU = u / (1 + perspective * v / halfH)
            val photo = abs(warpedU) < halfW && abs(v) < halfH
            truth[i] = photo
            when {
                photo -> {
                    val d = if (paleDetail && abs(warpedU) < 10 && abs(v) < 8) .015
                        else .16 + (warpedU + halfW) / (2 * halfW)
                    color(FloatArray(3) { c -> FilmNegativeMath.encode(FilmNegativeMath.linearize(reference[c]) * 10.0.pow(-d)) })
                }
                abs(warpedU) < halfW + border && abs(v) < halfH + border ->
                    color(FloatArray(3) { c -> reference[c] + if (noise > 0) ((i * 17 + c * 7) % (noise * 2 + 1) - noise) / 255f else 0f })
                else -> -1
            }
        }
        return Scene(FilmNegativeFrozenFrame(w, h, pixels, evidence), FilmNegativeMask(w, h, truth))
    }

    @Test fun formatsRotationsCurvatureAndPerspectiveRetainPhotographAndTone() {
        // 135, square 120, 6x9, half-frame, 4x5 and panoramic shapes. No stock-specific priors.
        for (aspect in listOf(1.5, 1.0, 2.0 / 3, .75, 1.25, 3.0)) {
            for (angle in listOf(0.0, 17.0, 45.0, 73.0)) {
                val scene = scene(angle, aspect, curve = 7.0, perspective = .12, noise = 1)
                val result = FilmNegativeSelectionAnalysis.automatic(scene.frame, FilmNegativeSettings())
                assertNotNull("aspect=$aspect angle=$angle", result)
                assertTrue("mask aspect=$aspect angle=$angle", result!!.mask!!.overlap(scene.truth) > .92f)
                assertArrayEquals(base, FloatArray(3) { result.settings.base(it) }, .01f)
                assertEquals("low density excludes the clear border", .18f, result.settings.blackDensity, .06f)
                assertEquals(1.14f, result.settings.whiteDensity, .07f)
            }
        }
    }

    @Test fun paleEnclosedImageDetailIsRetainedInsteadOfCutOutAsFilmBase() {
        val scene = scene(curve = 10.0, paleDetail = true)
        val result = FilmNegativeSelectionAnalysis.automatic(scene.frame, FilmNegativeSettings())!!
        assertTrue(result.mask!!.contains(.5f, .5f))
        assertTrue(result.mask.overlap(scene.truth) > .95f)
    }

    @Test fun monochromeCurvedFilmUsesNeutralBorder() {
        val scene = scene(37.0, 1.0, 10.0, monochrome = true)
        val result = FilmNegativeSelectionAnalysis.automatic(scene.frame, FilmNegativeSettings())!!
        assertTrue("one-tap action must not require a black-and-white choice", result.settings.monochrome)
        assertNull(result.settings.colorCorrection)
        assertEquals(.86f, result.settings.baseGreen, .01f)
        assertTrue(result.mask!!.overlap(scene.truth) > .95f)
    }

    @Test fun automaticColourFilmDoesNotRequireChangingPreviousMonochromeMode() {
        val result = FilmNegativeSelectionAnalysis.automatic(scene().frame, FilmNegativeSettings(monochrome = true))!!
        assertFalse(result.settings.monochrome)
        assertTrue(result.settings.inverted)
        assertEquals(FilmNegativeReferenceSource.FILM_BORDER, result.settings.referenceSource)
    }

    @Test fun confirmingAutomaticBoundingBoxKeepsContourBasedStatistics() {
        val scene = scene(45.0, curve = 12.0)
        val automatic = FilmNegativeSelectionAnalysis.automatic(scene.frame, FilmNegativeSettings())!!
        val manual = FilmNegativeSelectionAnalysis.manual(scene.frame, automatic.region, FilmNegativeSettings())!!
        assertEquals(automatic.settings, manual.settings)
        assertTrue(manual.mask!!.overlap(scene.truth) > .95f)
    }

    @Test fun aSmallHandheldTranslationCanCompleteThreeFrameWindow() {
        val window = FilmNegativeSelectionWindow()
        for (i in 0..2) {
            val scene = scene(dx = i)
            val frame = scene.frame.copy(evidence = evidence.copy(timestampNs = 100_000_000L + i * 200_000_000L))
            val selected = FilmNegativeSelectionAnalysis.automatic(frame, FilmNegativeSettings())!!
            val accepted = window.add(selected)
            if (i < 2) assertNull(accepted) else assertNotNull(accepted)
        }
    }

    @Test fun detectionGapCannotJoinTwoOldFramesWithOneNewFrame() {
        val selected = FilmNegativeSelectionAnalysis.automatic(scene().frame, FilmNegativeSettings())!!
        val window = FilmNegativeSelectionWindow()
        assertNull(window.add(selected))
        assertNull(window.add(selected.copy(evidence = evidence.copy(timestampNs = 300_000_000))))
        window.clear()
        assertNull(window.add(selected.copy(evidence = evidence.copy(timestampNs = 500_000_000))))
    }

    @Test fun oneFrozenFrameIsSufficientButUnconfirmedCaptureCannotBeCalibrated() {
        val frame = scene(45.0, curve = 7.0).frame
        assertNotNull(FilmNegativeSelectionAnalysis.automatic(frame, FilmNegativeSettings()))
        for (changed in listOf(evidence.copy(locked = false), evidence.copy(exposureNs = 0),
            evidence.copy(colorGains = listOf(Float.NaN, 1f, 1f, 1f)))) {
            assertNull(FilmNegativeSelectionAnalysis.automatic(frame.copy(evidence = changed), FilmNegativeSettings()))
        }
    }

    @Test fun oneSidedOrangeGradientAndUnclippedWhiteBacklightDoNotCountAsFilm() {
        val w = 224; val h = 224
        val frame = FilmNegativeFrozenFrame(w, h, IntArray(w * h) { i ->
            val t = (i % w) / (w - 1f)
            color(FloatArray(3) { c -> base[c] * (.35f + t * .65f) })
        }, evidence)
        assertNull(FilmNegativeSelectionAnalysis.automatic(frame, FilmNegativeSettings()))
        val white = frame.copy(pixels = IntArray(w * h) { color(floatArrayOf(.94f, .94f, .94f)) })
        assertNull(FilmNegativeSelectionAnalysis.automatic(white, FilmNegativeSettings(monochrome = true)))
    }

    @Test fun fullyCroppedFilmCannotInventClearBorder() {
        val scene = scene(border = 0.0)
        assertNull(FilmNegativeSelectionAnalysis.automatic(scene.frame, FilmNegativeSettings()))
    }

    @Test fun neutralUnclippedLightTableDoesNotTurnColourFilmIntoMonochrome() {
        val scene = scene()
        val frame = scene.frame.copy(pixels = scene.frame.pixels.map { if (it == -1) color(floatArrayOf(.94f, .94f, .94f)) else it }.toIntArray())
        val selected = FilmNegativeSelectionAnalysis.automatic(frame, FilmNegativeSettings())!!
        assertFalse(selected.settings.monochrome)
        assertArrayEquals(base, FloatArray(3) { selected.settings.base(it) }, .01f)
        assertTrue(selected.mask!!.overlap(scene.truth) > .95f)
    }

    @Test fun thinDiagonalBorderIsEnoughWithoutSprocketHoles() {
        val scene = scene(45.0, curve = 4.0, border = 1.5)
        val result = FilmNegativeSelectionAnalysis.automatic(scene.frame, FilmNegativeSettings())!!
        assertTrue(result.mask!!.overlap(scene.truth) > .95f)
        assertArrayEquals(base, FloatArray(3) { result.settings.base(it) }, .01f)
    }

    @Test fun largeAndSmallOriginalsProduceSameAnalysisGeometryAndTone() {
        val small = scene().frame
        val large = small.copy(width = small.width * 4, height = small.height * 4,
            pixels = IntArray(small.pixels.size * 16) { i ->
                small.pixels[(i / (small.width * 4) / 4) * small.width + (i % (small.width * 4) / 4)]
            })
        val a = FilmNegativeSelectionAnalysis.automatic(small, FilmNegativeSettings())!!
        val b = FilmNegativeSelectionAnalysis.automatic(large, FilmNegativeSettings())!!
        assertTrue(a.mask!!.overlap(b.mask!!) > .96f)
        assertEquals(a.settings.blackDensity, b.settings.blackDensity, .025f)
        assertEquals(a.settings.whiteDensity, b.settings.whiteDensity, .025f)
    }

    @Test fun shapeChangeCannotHideInsideUnchangedBoundingBox() {
        val selected = FilmNegativeSelectionAnalysis.automatic(scene().frame, FilmNegativeSettings())!!
        val mask = selected.mask!!
        val altered = FilmNegativeMask(mask.width, mask.height,
            BooleanArray(mask.width * mask.height) { i -> mask.at(i % mask.width, i / mask.width) && i % mask.width < mask.width / 2 })
        val window = FilmNegativeSelectionWindow()
        assertNull(window.add(selected))
        assertNull(window.add(selected.copy(evidence = evidence.copy(timestampNs = 300_000_000))))
        assertNull(window.add(selected.copy(mask = altered, evidence = evidence.copy(timestampNs = 500_000_000))))
    }

    @Test fun missingBorderMaskCoordinatesAreOutsideNotFirstPixel() {
        val mask = FilmNegativeMask(2, 2, booleanArrayOf(true, true, true, true))
        assertFalse(mask.contains(-.001f, .5f))
        assertFalse(mask.contains(.5f, -.001f))
        assertFalse(mask.contains(1f, .5f))
    }
}
