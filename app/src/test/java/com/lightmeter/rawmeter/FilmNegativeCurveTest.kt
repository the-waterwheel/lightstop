package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class FilmNegativeCurveTest {
    @Test fun identityKeepsShadowsWhiteAndHighlightHeadroom() {
        val curve = FilmNegativeCurve()
        for (i in 0..400) assertEquals(i / 100.0, curve.evaluate(i / 100.0), 1e-6)
    }

    @Test fun crossingAnAnchorCannotReverseTheCurveOrLoseBlack() {
        val curve = FilmNegativeCurve().move(1, 1f).move(2, 0f).move(3, -1f)
        var previous = 0.0
        for (i in 0..400) {
            val value = curve.evaluate(i / 100.0)
            assertTrue(value.isFinite() && value >= previous)
            previous = value
        }
        assertEquals(0.0, curve.evaluate(0.0), 0.0)
        assertEquals(1.0, curve.evaluate(1.0), 1e-6)
        assertTrue(curve.evaluate(1.5) > 1.0)
    }

    @Test fun nonFiniteCurveParametersFallBackToOrderedFiniteAnchors() {
        val curve = FilmNegativeCurve(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (i in 0..400) assertTrue(curve.evaluate(i / 100.0).isFinite())
    }

    @Test fun manualRedEditsDoNotChangeOtherChannels() {
        val settings = FilmNegativeSettings(regionTone = true, gamma = 1f)
        val changed = settings.copy(redAlignment = .1f, redCurve = FilmNegativeCurve().move(2, .7f))
        for (c in 0..2) {
            val original = FilmNegativeMath.channel(.3f, .9f, settings, c)
            val edited = FilmNegativeMath.channel(.3f, .9f, changed, c)
            if (c == 0) assertTrue(edited > original) else assertEquals(original, edited, 1e-6f)
        }
    }

    @Test fun contrastKeepsNeutralMidgrayAndDoesNotChangeDensityLookup() {
        val settings = FilmNegativeSettings(gamma = 1f)
        for (contrast in listOf(.5f, 1f, 2f)) {
            val edited = settings.copy(contrast = contrast)
            val middle = FilmNegativeMath.displayColor(floatArrayOf(.18f, .18f, .18f), edited)
            assertEquals(.18, FilmNegativeMath.linearize(middle[0]), 1e-6)
            assertEquals(middle[0], middle[1], 1e-6f)
            assertArrayEquals(FilmNegativeMath.lookup(settings), FilmNegativeMath.lookup(edited))
            val shadow = FilmNegativeMath.displayColor(floatArrayOf(.05f, .05f, .05f), edited)
            val highlight = FilmNegativeMath.displayColor(floatArrayOf(.5f, .5f, .5f), edited)
            assertTrue(shadow[0] < middle[0] && highlight[0] > middle[0])
        }
    }
}
