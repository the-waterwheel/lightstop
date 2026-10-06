package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

class FilmNegativeSelectionAnalysisTest {
    private val evidence = FilmNegativeFrameEvidence(100_000_000, "camera:session", true,
        10_000_000, 100, listOf(1f, 1f, 1f, 1f), "fixed")
    private val base = floatArrayOf(220f / 255, 140f / 255, 90f / 255)
    private val region = FilmNegativeRegion(30f / 192, 24f / 144, 162f / 192, 120f / 144)
    private fun rgb(values: FloatArray) = (255 shl 24) or (values[0].times(255).roundToInt().coerceIn(0, 255) shl 16) or
        (values[1].times(255).roundToInt().coerceIn(0, 255) shl 8) or values[2].times(255).roundToInt().coerceIn(0, 255)
    private fun negative(level: Double, slopes: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0),
        offsets: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0), reference: FloatArray = base) =
        rgb(FloatArray(3) { c -> FilmNegativeMath.encode(FilmNegativeMath.linearize(reference[c]) * 10.0.pow(-(level * slopes[c] + offsets[c]))) })
    private fun frame(w: Int = 192, h: Int = 144, color: (Int, Int) -> Int) =
        FilmNegativeFrozenFrame(w, h, IntArray(w * h) { color(it % w, it / w) }, evidence)
    private fun bordered(slopes: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0),
        offsets: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0), reference: FloatArray = base) = frame { x, y -> when {
            x in 30..161 && y in 24..119 -> negative(.16 + (x - 30) / 131.0 * 1.0, slopes, offsets, reference)
            x in 21..170 && y in 15..128 -> rgb(reference)
            else -> -1
        } }
    private fun selected(image: FilmNegativeFrozenFrame = bordered(), settings: FilmNegativeSettings = FilmNegativeSettings()) =
        FilmNegativeSelectionAnalysis.manual(image, region, settings)!!

    @Test fun selectedPhotographFindsLocalBaseAndUsesSharedDminDmax() {
        val result = selected()
        assertEquals(FilmNegativeReferenceSource.FILM_BORDER, result.settings.referenceSource)
        assertArrayEquals(base, FloatArray(3) { result.settings.base(it) }, .001f)
        assertNotNull(result.baseRegion)
        assertTrue(result.settings.inverted && result.settings.regionTone)
        assertNull(result.settings.densityWindow)
        assertEquals(.18f, result.settings.blackDensity, .04f)
        assertEquals(1.14f, result.settings.whiteDensity, .05f)
    }

    @Test fun automaticSelectsPhotographRatherThanWholeContactSheet() {
        val image = frame { x, y -> when {
            x in 21..84 && y in 24..83 -> negative(.16 + (x - 21) / 63.0 * .9)
            x in 12..93 && y in 15..92 -> if (y < 21 && x % 18 < 6) -1 else rgb(base)
            x in 111..173 && y in 69..122 -> negative(.16 + (x - 111) / 62.0 * 1.0)
            x in 102..182 && y in 60..131 -> rgb(base)
            else -> -1
        } }
        val result = FilmNegativeSelectionAnalysis.automatic(image, FilmNegativeSettings())!!
        assertTrue((result.region.right - result.region.left) < .4f)
        assertTrue((result.region.bottom - result.region.top) < .5f)
        assertArrayEquals(base, FloatArray(3) { result.settings.base(it) }, .001f)
        assertEquals(FilmNegativeReferenceSource.FILM_BORDER, result.settings.referenceSource)
    }

    @Test fun nearbyReferenceIgnoresUnrelatedStripAndWhiteBacklight() {
        val image = frame { x, y -> when {
            x in 30..161 && y in 24..119 -> negative(.16 + (x - 30) / 131.0)
            x in 21..170 && y in 15..128 -> rgb(base)
            x < 9 -> rgb(floatArrayOf(.93f, .72f, .48f))
            else -> -1
        } }
        assertArrayEquals(base, FloatArray(3) { selected(image).settings.base(it) }, .001f)
    }

    @Test fun noBorderRequiresExplicitManualContentEstimate() {
        val image = frame { x, _ -> negative(.16 + x / 191.0) }
        val result = FilmNegativeSelectionAnalysis.manual(image, FilmNegativeRegion(0f, 0f, 1f, 1f), FilmNegativeSettings())!!
        assertNull(result.baseRegion)
        assertEquals(FilmNegativeReferenceSource.CONTENT_ESTIMATE, result.settings.referenceSource)
        assertTrue(result.settings.whiteDensity > result.settings.blackDensity + .5f)
        assertNull(FilmNegativeSelectionAnalysis.automatic(image, FilmNegativeSettings()))
    }

    @Test fun uniformOrangeClippedLightAndDarkHolderCannotProduceSelection() {
        for (color in listOf(rgb(base), -1, -16777216, negative(.6))) {
            val image = frame { _, _ -> color }
            assertNull(FilmNegativeSelectionAnalysis.automatic(image, FilmNegativeSettings()))
            assertNull(FilmNegativeSelectionAnalysis.manual(image, region, FilmNegativeSettings()))
        }
    }

    @Test fun conflictingLocalBorderColoursDoNotBecomePhysicalReference() {
        val image = frame { x, y -> when {
            x in 30..161 && y in 24..119 -> negative(.16 + (x - 30) / 131.0)
            x in 21..29 && y in 15..128 -> rgb(base)
            x in 162..170 && y in 15..128 -> rgb(floatArrayOf(210f / 255, 150f / 255, 99f / 255))
            else -> -1
        } }
        assertNull(FilmNegativeSelectionAnalysis.nearbyBase(image, region, false))
    }

    @Test fun neutralRampCorrectionReducesCrossColourAndCanBeDisabled() {
        val slopes = doubleArrayOf(.93, 1.0, 1.10)
        val offsets = doubleArrayOf(.025, 0.0, -.015)
        val result = selected(bordered(slopes, offsets), FilmNegativeSettings(colorStrength = 1f))
        assertNotNull(result.settings.colorCorrection)
        val encoded = FloatArray(3) { c -> FilmNegativeMath.encode(FilmNegativeMath.linearize(base[c]) * 10.0.pow(-(.6 * slopes[c] + offsets[c]))) }
        fun light(settings: FilmNegativeSettings) = FloatArray(3) { FilmNegativeMath.channel(encoded[it], settings.base(it), settings, it) }
        val corrected = light(result.settings); val uncorrected = light(result.settings.copy(colorStrength = 0f))
        assertTrue(corrected.maxOrNull()!! - corrected.minOrNull()!! < .04f)
        assertTrue(corrected.maxOrNull()!! - corrected.minOrNull()!! < uncorrected.maxOrNull()!! - uncorrected.minOrNull()!!)
    }

    @Test fun saturatedSingleHueDoesNotBecomeNeutralAxis() {
        val result = selected(bordered(offsets = doubleArrayOf(.4, 0.0, .05)))
        assertNull(result.settings.colorCorrection)
    }

    @Test fun monochromeUsesNeutralBaseAndHasNoColourFit() {
        val gray = floatArrayOf(.86f, .86f, .86f)
        val result = selected(bordered(reference = gray), FilmNegativeSettings(monochrome = true))
        assertEquals(FilmNegativeReferenceSource.FILM_BORDER, result.settings.referenceSource)
        assertNull(result.settings.colorCorrection)
        assertEquals(gray[0], result.settings.baseRed, .005f)
    }

    @Test fun regionAndColourLookupRemainMonotoneWithManualDensityEdits() {
        val settings = selected().settings.copy(blackDensity = .25f, whiteDensity = 1.0f,
            colorCorrection = FilmNegativeColorCorrection(1.25f, .8f, -.1f, .1f))
        val lookup = FilmNegativeMath.lookup(settings)
        for (c in 0..2) for (i in 1..255) {
            assertTrue((lookup[i * 3 + c].toInt() and 255) <= (lookup[(i - 1) * 3 + c].toInt() and 255))
        }
        val darker = FilmNegativeMath.channel(.42f, settings.baseRed, settings.copy(blackDensity = .4f))
        assertTrue(darker < FilmNegativeMath.channel(.42f, settings.baseRed, settings))
    }

    @Test fun invalidationClearsAllDerivedParametersAndRestoresOriginal() {
        val settings = selected().settings.copy(exposureEv = 2f, warmth = .7f, tint = -.5f,
            colorCorrection = FilmNegativeColorCorrection(1.1f, .9f, -.1f, .1f))
        val reset = settings.withoutReference()
        assertFalse(reset.inverted); assertFalse(reset.regionTone)
        assertNull(reset.colorCorrection); assertNull(reset.densityWindow)
        assertEquals(0f, reset.exposureEv, 0f); assertEquals(0f, reset.warmth, 0f)
        assertEquals(FilmNegativeReferenceSource.DEFAULT, reset.referenceSource)
        val mono = settings.copy(monochrome = true).withoutReference()
        assertTrue(mono.monochrome); assertEquals(mono.baseRed, mono.baseBlue, 0f)
    }

    @Test fun threeDistinctFramesRequiredAndMovingSelectionResetsAnchor() {
        val candidate = selected(); val window = FilmNegativeSelectionWindow()
        fun at(t: Long, value: FilmNegativeSelection = candidate) = value.copy(evidence = evidence.copy(timestampNs = t))
        assertNull(window.add(at(100_000_000)))
        assertNull(window.add(at(100_000_000)))
        assertNull(window.add(at(300_000_000)))
        assertNotNull(window.add(at(500_000_000)))
        assertNull(window.add(at(700_000_000)))
        val shifted = candidate.copy(region = FilmNegativeRegion(.3f, .3f, .7f, .7f))
        assertNull(window.add(at(900_000_000, shifted)))
        assertNull(window.add(at(1_100_000_000, shifted)))
        assertNotNull(window.add(at(1_300_000_000, shifted)))
    }

    @Test fun changedCaptureCurveOrCameraCannotCompleteOldWindow() {
        val candidate = selected(); val window = FilmNegativeSelectionWindow()
        assertNull(window.add(candidate))
        assertNull(window.add(candidate.copy(evidence = evidence.copy(timestampNs = 300_000_000))))
        val changed = candidate.copy(evidence = evidence.copy(timestampNs = 500_000_000, cameraKey = "other"))
        assertNull(window.add(changed))
        assertNull(window.add(changed.copy(evidence = changed.evidence.copy(timestampNs = 700_000_000))))
        assertNotNull(window.add(changed.copy(evidence = changed.evidence.copy(timestampNs = 900_000_000))))
    }

    @Test fun invalidRegionsAndMalformedFramesAreRejected() {
        val image = bordered()
        assertNull(FilmNegativeSelectionAnalysis.manual(image, FilmNegativeRegion(Float.NaN, 0f, 1f, 1f), FilmNegativeSettings()))
        assertNull(FilmNegativeSelectionAnalysis.manual(image, FilmNegativeRegion(.8f, .8f, .2f, .2f), FilmNegativeSettings()))
        assertNull(FilmNegativeSelectionAnalysis.automatic(image.copy(width = Int.MAX_VALUE), FilmNegativeSettings()))
        assertNull(FilmNegativeSelectionAnalysis.manual(image.copy(pixels = intArrayOf()), region, FilmNegativeSettings()))
    }

    @Test fun editedRotatedCropFindsItsParallelFilmRails() {
        val crop = FilmNegativeCrop(96f, 72f, 112f, 38f, (Math.PI / 4).toFloat())
        val image = frame { x, y ->
            val p = crop.local(x + .5f, y + .5f)
            when {
                abs(p.x) < crop.width / 2 && abs(p.y) < crop.height / 2 -> negative(.16 + (p.x / crop.width + .5) * 1.0)
                abs(p.x) < crop.width / 2 + 8 && abs(p.y) < crop.height / 2 + 8 -> rgb(base)
                else -> -1
            }
        }
        val region = crop.region(192, 144)
        val reference = FilmNegativeSelectionAnalysis.nearbyBase(image, region, false)
        assertNotNull(reference)
        assertArrayEquals(base, reference!!.rgb, .001f)
        val result = FilmNegativeSelectionAnalysis.manual(image, region, FilmNegativeSettings())!!
        assertEquals(FilmNegativeReferenceSource.FILM_BORDER, result.settings.referenceSource)
        assertEquals(.18f, result.settings.blackDensity, .05f)
        assertEquals(1.14f, result.settings.whiteDensity, .05f)
    }

    @Test fun rotatedManualStatisticsExcludeUnselectedBoundingBoxPixels() {
        val crop = FilmNegativeCrop(96f, 72f, 112f, 38f, .65f)
        val image = frame { x, y ->
            val p = crop.local(x + .5f, y + .5f)
            if (crop.contains(x + .5f, y + .5f)) negative(.2 + (p.x / crop.width + .5) * .8)
            else negative(2.0)
        }
        val result = FilmNegativeSelectionAnalysis.manual(image, crop.region(192, 144), FilmNegativeSettings())!!
        assertEquals(FilmNegativeReferenceSource.CONTENT_ESTIMATE, result.settings.referenceSource)
        assertEquals(.77f, result.settings.whiteDensity - result.settings.blackDensity, .06f)
    }

    @Test fun malformedManualQuadrilateralCannotBeAnalyzedAsItsBounds() {
        val region = FilmNegativeRegion(.1f, .1f, .9f, .9f, listOf(
            FilmNegativePoint(.1f, .1f), FilmNegativePoint(.9f, .9f),
            FilmNegativePoint(.9f, .1f), FilmNegativePoint(.1f, .9f)))
        assertNull(FilmNegativeSelectionAnalysis.manual(bordered(), region, FilmNegativeSettings()))
    }

    @Test fun automaticSelectionWorksWithRotatedAndMirroredContactSheet() {
        val image = bordered()
        val mirrored = image.copy(pixels = IntArray(image.pixels.size) { i -> image.pixels[(i / 192) * 192 + 191 - i % 192] })
        val rotated = image.copy(width = 144, height = 192, pixels = IntArray(image.pixels.size) { i ->
            image.pixels[(143 - i % 144) * 192 + i / 144]
        })
        for (variant in listOf(image, mirrored, rotated)) {
            val result = FilmNegativeSelectionAnalysis.automatic(variant, FilmNegativeSettings())!!
            assertArrayEquals(base, FloatArray(3) { result.settings.base(it) }, .001f)
            assertEquals(.18f, result.settings.blackDensity, .04f)
            assertEquals(1.14f, result.settings.whiteDensity, .05f)
        }
    }

    @Test fun noisyBordersKeepPhysicalSamplingTileStableAcrossFrames() {
        val template = bordered(); val window = FilmNegativeSelectionWindow()
        var result: FilmNegativeSelection? = null
        for (iteration in 0..2) {
            val image = template.copy(evidence = evidence.copy(timestampNs = evidence.timestampNs + iteration * 200_000_000L),
                pixels = IntArray(template.pixels.size) { i ->
                    val value = template.pixels[i]
                    if (value == rgb(base)) rgb(FloatArray(3) { c -> base[c] + ((i + c + iteration) % 3 - 1) / 255f }) else value
                })
            val selection = FilmNegativeSelectionAnalysis.automatic(image, FilmNegativeSettings())!!
            result = window.add(selection)
        }
        assertNotNull(result)
    }

    @Test fun orientationChangeIncludingReturnToSameAngleResetsWindow() {
        val candidate = selected(); val window = FilmNegativeSelectionWindow()
        assertNull(window.add(candidate))
        assertNull(window.add(candidate.copy(evidence = evidence.copy(timestampNs = 300_000_000))))
        val rotated = candidate.copy(orientationToken = 2, evidence = evidence.copy(timestampNs = 500_000_000))
        assertNull(window.add(rotated))
        assertNull(window.add(rotated.copy(evidence = evidence.copy(timestampNs = 700_000_000))))
        assertNotNull(window.add(rotated.copy(evidence = evidence.copy(timestampNs = 900_000_000))))
    }

    @Test fun pixelWideRebateIsNotLostBySquarePatchSampling() {
        val image = frame { x, y -> when {
            x in 30..161 && y in 24..119 -> negative(.16 + (x - 30) / 131.0)
            x in 29..162 && y in 23..120 -> rgb(base)
            else -> -1
        } }
        val result = FilmNegativeSelectionAnalysis.automatic(image, FilmNegativeSettings())!!
        assertTrue(FilmNegativeSelectionAnalysis.overlap(region, result.region) > .95f)
        assertArrayEquals(base, FloatArray(3) { result.settings.base(it) }, .001f)
        val window = FilmNegativeSelectionWindow()
        assertNull(window.add(result))
        assertNull(window.add(result.copy(evidence = evidence.copy(timestampNs = 300_000_000))))
        assertNotNull(window.add(result.copy(evidence = evidence.copy(timestampNs = 500_000_000))))
    }
}
