package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class FilmNegativeCurveTest {
    @Test fun identityKeepsShadowsWhiteAndHighlightHeadroom() {
        val curve = FilmNegativeCurve()
        for (i in 0..400) assertEquals(i / 100.0, curve.evaluate(i / 100.0), 1e-6)
    }

    @Test fun increasingControlsStayMonotoneWithoutInterpolationOvershoot() {
        val curve = FilmNegativeCurve().add(.08f, .2f).add(.4f, .5f).add(.65f, .56f).add(.95f, .94f)
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
        val curve = FilmNegativeCurve(listOf(FilmNegativeCurvePoint(Float.NaN, .3f),
            FilmNegativeCurvePoint(.2f, Float.POSITIVE_INFINITY), FilmNegativeCurvePoint(.7f, Float.NEGATIVE_INFINITY)))
        for (i in 0..400) assertTrue(curve.evaluate(i / 100.0).isFinite())
    }

    @Test fun manualRedEditsDoNotChangeOtherChannels() {
        val settings = FilmNegativeSettings(regionTone = true, gamma = 1f)
        val changed = settings.copy(redAlignment = .1f, redCurve = FilmNegativeCurve().add(.4f, .7f).move(1, .5f, .7f))
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

    @Test fun arbitraryNewInputPointsAreSortedAndExactlyInterpolated() {
        val curve = FilmNegativeCurve().add(.8f, .9f).add(.12f, .08f).add(.43f, .6f)
        assertEquals(listOf(0f, .12f, .43f, .8f, 1f), curve.points().map { it.input })
        for (point in curve.points()) assertEquals(point.output.toDouble(), curve.evaluate(point.input.toDouble()), 1e-8)
    }

    @Test fun interiorPointMovesOnBothAxesAndKeepsOtherPoints() {
        val curve = FilmNegativeCurve().add(.2f, .3f).add(.5f, .6f).add(.8f, .9f)
        val moved = curve.move(2, .62f, .42f)
        assertEquals(FilmNegativeCurvePoint(.62f, .42f), moved.points()[2])
        for (index in listOf(0, 1, 3, 4)) assertEquals(curve.points()[index], moved.points()[index])
    }

    @Test fun horizontalDragCannotCrossOrRemoveANeighbor() {
        val curve = FilmNegativeCurve().add(.2f, .2f).add(.5f, .5f).add(.8f, .8f)
        for (input in listOf(-2f, 2f)) {
            val moved = curve.move(2, input, .4f)
            assertEquals(curve.points().size, moved.points().size)
            assertTrue(moved.points()[2].input >= .21f - 1e-6f)
            assertTrue(moved.points()[2].input <= .79f + 1e-6f)
        }
    }

    @Test fun aDensePointNeighborhoodStillAllowsSafeVerticalEditing() {
        val curve = FilmNegativeCurve().add(.49f, .2f).add(.5f, .5f).add(.51f, .8f)
        assertEquals(5, curve.points().size)
        val moved = curve.move(2, 1f, .65f)
        assertEquals(curve.points().size, moved.points().size)
        assertEquals(.65f, moved.points()[2].output, 0f)
        for (i in 0..1000) assertTrue(moved.evaluate(i / 1000.0).isFinite())
    }

    @Test fun blackAndWhiteEndpointsMoveVerticallyAndRemainAtInputBoundaries() {
        val curve = FilmNegativeCurve().move(0, .4f, .16f).move(1, .6f, .86f)
        assertEquals(FilmNegativeCurvePoint(0f, .16f), curve.points().first())
        assertEquals(FilmNegativeCurvePoint(1f, .86f), curve.points().last())
        assertEquals(.16, curve.evaluate(0.0), 1e-6)
        assertEquals(.86, curve.evaluate(1.0), 1e-6)
    }

    @Test fun resettingOnePointReturnsItToTheDiagonalWithoutRemovingOtherEdits() {
        val curve = FilmNegativeCurve().add(.2f, .4f).add(.6f, .8f)
        val reset = curve.reset(2)
        assertEquals(FilmNegativeCurvePoint(.6f, .6f), reset.points()[2])
        assertEquals(curve.points()[1], reset.points()[1])
        assertEquals(curve.points().size, reset.points().size)
    }

    @Test fun endpointsCanBeResetButCannotBeDeleted() {
        val curve = FilmNegativeCurve().add(.4f, .6f).move(0, 0f, .1f).move(2, 1f, .9f)
        assertEquals(curve, curve.remove(0)); assertEquals(curve, curve.remove(2))
        assertEquals(0f, curve.reset(0).points()[0].output, 0f)
        assertEquals(1f, curve.reset(2).points()[2].output, 0f)
    }

    @Test fun deletingTheLastInteriorPointRestoresIdentity() {
        val curve = FilmNegativeCurve().add(.41f, .77f).remove(1)
        assertEquals(FilmNegativeCurve(), curve)
        for (i in 0..400) assertEquals(i / 100.0, curve.evaluate(i / 100.0), 1e-6)
    }

    @Test fun shapePreservingCubicMatchesAKnownThreePointReference() {
        // PCHIP on (0,0), (.5,.75), (1,1): slopes are 2, .75, 0.
        val curve = FilmNegativeCurve().add(.5f, .75f)
        assertEquals(.453125, curve.evaluate(.25), 1e-9)
        assertEquals(.921875, curve.evaluate(.75), 1e-9)
        assertEquals(1.25, curve.evaluate(1.5), 1e-9)
    }

    @Test fun smoothCurveHasMatchingLeftAndRightSlopesAtAnInteriorControl() {
        val curve = FilmNegativeCurve().add(.3f, .5f).add(.7f, .8f)
        val x = .3f.toDouble(); val epsilon = 1e-6
        val left = (curve.evaluate(x) - curve.evaluate(x - epsilon)) / epsilon
        val right = (curve.evaluate(x + epsilon) - curve.evaluate(x)) / epsilon
        assertEquals(left, right, 1e-4)
    }

    @Test fun intentionalPeaksAndValleysAreAllowedWithoutSpuriousOvershoot() {
        val curve = FilmNegativeCurve().add(.2f, .85f).add(.5f, .12f).add(.8f, .75f)
        val points = curve.points()
        for (i in 0 until points.lastIndex) for (step in 0..100) {
            val a = points[i]; val b = points[i + 1]
            val y = curve.evaluate(a.input + (b.input - a.input).toDouble() * step / 100)
            assertTrue(y >= minOf(a.output, b.output) - 1e-6 && y <= maxOf(a.output, b.output) + 1e-6)
        }
        assertTrue(curve.evaluate(.2) > curve.evaluate(.5))
    }

    @Test fun almostDuplicateInputsDoNotCreateZeroLengthSegments() {
        val curve = FilmNegativeCurve().add(.5f, .7f)
        assertEquals(curve, curve.add(.5f, .2f))
        assertEquals(curve, curve.add(.505f, .2f))
        assertEquals(curve, curve.add(0f, .2f))
        for (i in 0..400) assertTrue(curve.evaluate(i / 100.0).isFinite())
    }

    @Test fun controlCountAndMalformedInputCannotGrowAnUnboundedCurve() {
        val points = (0..1000).map { FilmNegativeCurvePoint(it / 1000f, if (it % 2 == 0) 1f else 0f) }
        val curve = FilmNegativeCurve(points)
        assertTrue(curve.points().size <= FilmNegativeCurve.MAX_POINTS)
        assertEquals(curve, curve.add(Float.NaN, .1f))
        assertEquals(curve, curve.add(.6f, Float.POSITIVE_INFINITY))
        for (i in 0..400) assertTrue(curve.evaluate(i / 100.0).isFinite())
    }

    @Test fun invalidDragCoordinatesPreserveAValidExistingPoint() {
        val curve = FilmNegativeCurve().add(.5f, .7f)
        assertEquals(curve, curve.move(1, Float.NaN, Float.NEGATIVE_INFINITY))
        val limited = curve.move(1, .6f, 2f)
        assertEquals(1f, limited.points()[1].output, 0f)
    }

    @Test fun callersCannotMutateTheRendererSnapshotThroughAList() {
        val source = mutableListOf(FilmNegativeCurvePoint(.4f, .6f))
        val curve = FilmNegativeCurve(source)
        source.clear()
        val exposed = curve.points() as MutableList<FilmNegativeCurvePoint>
        exposed[1] = FilmNegativeCurvePoint(.4f, 0f)
        assertEquals(FilmNegativeCurvePoint(.4f, .6f), curve.points()[1])
        assertEquals(.6, curve.evaluate(.4f.toDouble()), 1e-6)
    }

    @Test fun normalizedCurveEqualityAndHashSupportLookupCaching() {
        val a = FilmNegativeCurve().add(.8f, .9f).add(.2f, .3f)
        val b = FilmNegativeCurve().add(.2f, .3f).add(.8f, .9f)
        assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, b.move(1, .25f, .3f))
    }

    @Test fun defaultExtraDiagonalControlsDoNotChangeTheDensityLookup() {
        val settings = FilmNegativeSettings()
        val identity = FilmNegativeCurve().add(.11f, .11f).add(.63f, .63f).add(.91f, .91f)
        assertArrayEquals(FilmNegativeMath.lookup(settings), FilmNegativeMath.lookup(settings.copy(redCurve = identity)))
    }
}
