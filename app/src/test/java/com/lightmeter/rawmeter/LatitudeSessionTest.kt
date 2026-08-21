package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatitudeSessionTest {
    @Test
    fun workbookValuesBeyondScaleClampOnlyTheirMarkerPositions() {
        val range = FilmLatitudeRange(shadowEv = -5.5, highlightEv = 7.5)

        assertEquals(0.0, range.lowerZone, 0.0)
        assertEquals(10.0, range.upperZone, 0.0)
        assertEquals(-5.5, range.shadowEv, 0.0)
        assertEquals(7.5, range.highlightEv, 0.0)
        assertTrue(range.containsZone(0.0))
        assertTrue(range.containsZone(10.0))
    }

    @Test
    fun persistedFloatRoundingDoesNotMisclassifyBoundaryPoint() {
        val range = FilmLatitudeRange(shadowEv = -3.0999999046325684, highlightEv = 2.7)

        assertTrue(range.containsZone(1.9))
    }

    @Test
    fun shadowMarkerCannotPassHighlightMarker() {
        val session = LatitudeSession()
        val profile = profile(FilmLatitudeRange(-3.0, 2.0))
        session.select(profile, profile.originalRange)

        assertTrue(session.setBoundary(LatitudeBoundary.SHADOW, 9.0))

        assertEquals(2.0, session.range.shadowEv, 0.0)
        assertEquals(2.0, session.range.highlightEv, 0.0)
    }

    @Test
    fun highlightMarkerCannotPassShadowMarker() {
        val session = LatitudeSession()
        val profile = profile(FilmLatitudeRange(-1.0, 4.0))
        session.select(profile, profile.originalRange)

        assertTrue(session.setBoundary(LatitudeBoundary.HIGHLIGHT, 1.0))

        assertEquals(-1.0, session.range.shadowEv, 0.0)
        assertEquals(-1.0, session.range.highlightEv, 0.0)
    }

    @Test
    fun dragSnapsDisplayValueToOneTenthStop() {
        val session = LatitudeSession()

        assertTrue(session.setBoundary(LatitudeBoundary.SHADOW, 2.34))
        assertEquals(-2.7, session.range.shadowEv, 0.0001)
        assertFalse(session.setBoundary(LatitudeBoundary.SHADOW, 2.31))
    }

    @Test
    fun resetClearsSelectionAndRestoresScaleEnds() {
        val session = LatitudeSession()
        val profile = profile(FilmLatitudeRange(-3.0, 4.0))
        session.select(profile, profile.originalRange)

        session.reset()

        assertEquals(null, session.selectedFilmId)
        assertEquals(FilmLatitudeRange.FULL_SCALE, session.range)
    }

    private fun profile(range: FilmLatitudeRange) = FilmLatitudeProfile(
        id = "test-film",
        manufacturer = "Test",
        model = "Film",
        iso = 100,
        type = "negative",
        discontinued = false,
        originalRange = range,
    )
}
