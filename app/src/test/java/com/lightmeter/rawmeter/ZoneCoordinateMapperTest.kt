package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opencv.core.Point

class ZoneCoordinateMapperTest {
    private val fullViewport = ZoneVisibleViewport(0f, 0f, 1f, 1f)

    @Test
    fun `display-oriented YUV coordinates do not rotate with display metadata`() {
        val portrait = ZoneCoordinateMapper.basePreviewToTexture(
            0.2f,
            0.7f,
            1000,
            500,
            fullViewport,
            displayOriented = true,
            displayRotationDegrees = 0,
        )
        val landscape = ZoneCoordinateMapper.basePreviewToTexture(
            0.2f,
            0.7f,
            1000,
            500,
            fullViewport,
            displayOriented = true,
            displayRotationDegrees = 90,
        )

        assertEquals(portrait.x, landscape.x, 0.001)
        assertEquals(portrait.y, landscape.y, 0.001)
    }

    @Test
    fun `display-oriented analysis point maps back to the same marker anchor`() {
        val point = ZoneCoordinateMapper.basePreviewToTexture(
            0.23f,
            0.71f,
            640,
            480,
            fullViewport,
            displayOriented = true,
            displayRotationDegrees = 270,
        )
        val mapped = ZoneCoordinateMapper.textureToBasePreview(
            point,
            640,
            480,
            fullViewport,
            displayOriented = true,
            displayRotationDegrees = 270,
        )

        assertEquals(0.23f, mapped.first, 0.0001f)
        assertEquals(0.71f, mapped.second, 0.0001f)
    }

    @Test
    fun `portrait to landscape counters the app coordinate rotation`() {
        val mapped = ZoneCoordinateMapper.remapForLayoutOrientation(
            x = 0.2f,
            y = 0.7f,
            fromLandscape = false,
            toLandscape = true,
        )

        assertEquals(0.7f, mapped.first, 0.0001f)
        assertEquals(0.8f, mapped.second, 0.0001f)
    }

    @Test
    fun `layout orientation remap round trips exactly`() {
        val landscape = ZoneCoordinateMapper.remapForLayoutOrientation(
            x = 0.23f,
            y = 0.71f,
            fromLandscape = false,
            toLandscape = true,
        )
        val portrait = ZoneCoordinateMapper.remapForLayoutOrientation(
            x = landscape.first,
            y = landscape.second,
            fromLandscape = true,
            toLandscape = false,
        )

        assertEquals(0.23f, portrait.first, 0.0001f)
        assertEquals(0.71f, portrait.second, 0.0001f)
    }

    @Test
    fun `reference point survives analysis source coordinate changes`() {
        val source = Point(120.0, 560.0)
        val displayOriented = ZoneCoordinateMapper.remapAnalysisPoint(
            source,
            fromWidth = 400,
            fromHeight = 800,
            fromDisplayOriented = false,
            fromDisplayRotationDegrees = 90,
            toWidth = 800,
            toHeight = 400,
            toDisplayOriented = true,
            toDisplayRotationDegrees = 0,
        )
        val roundTrip = ZoneCoordinateMapper.remapAnalysisPoint(
            displayOriented,
            fromWidth = 800,
            fromHeight = 400,
            fromDisplayOriented = true,
            fromDisplayRotationDegrees = 0,
            toWidth = 400,
            toHeight = 800,
            toDisplayOriented = false,
            toDisplayRotationDegrees = 90,
        )

        assertEquals(source.x, roundTrip.x, 0.001)
        assertEquals(source.y, roundTrip.y, 0.001)
    }
}
