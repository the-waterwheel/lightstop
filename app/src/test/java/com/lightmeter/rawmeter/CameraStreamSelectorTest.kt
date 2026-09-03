package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraStreamSelectorTest {
    @Test
    fun `prefers common four thirds preview for logical and physical consistency`() {
        val selected = CameraStreamSelector.choosePreviewDimensions(
            sizes = listOf(1920 to 1080, 1440 to 1080, 1280 to 720),
            sensorAspect = 16.0 / 9.0,
        )

        assertEquals(1440 to 1080, selected)
    }

    @Test
    fun `falls back to sensor aspect when four thirds is unavailable`() {
        val selected = CameraStreamSelector.choosePreviewDimensions(
            sizes = listOf(1920 to 1080, 1280 to 720, 720 to 720),
            sensorAspect = 16.0 / 9.0,
        )

        assertEquals(1920 to 1080, selected)
    }

    @Test
    fun `frame rate capability does not override the common preview shape`() {
        val selected = CameraStreamSelector.choosePreviewDimensions(
            sizes = listOf(1920 to 1080, 1440 to 1080),
            sensorAspect = 16.0 / 9.0,
        )

        assertEquals(1440 to 1080, selected)
    }

    @Test
    fun `tracking stream must match preview aspect`() {
        val selected = CameraStreamSelector.chooseTrackingDimensions(
            sizes = listOf(640 to 480, 640 to 360, 320 to 240),
            preview = 1440 to 1080,
        )

        assertEquals(640 to 480, selected)
        assertNull(
            CameraStreamSelector.chooseTrackingDimensions(
                sizes = listOf(640 to 360, 1280 to 720),
                preview = 1440 to 1080,
            ),
        )
    }

    @Test
    fun `recognizes four thirds sizes regardless of axis order`() {
        val selected = CameraStreamSelector.choosePreviewDimensions(
            sizes = listOf(1920 to 1080, 1080 to 1440),
            sensorAspect = 9.0 / 16.0,
        )

        assertEquals(1080 to 1440, selected)
    }

    @Test
    fun `never selects advertised 60 fps range when ceiling is 30`() {
        val selected = CameraStreamSelector.selectFpsRangeBounds(
            listOf(15 to 30, 30 to 30, 30 to 60, 60 to 60),
            requestedCeiling = 30,
        )

        assertEquals(30 to 30, selected)
    }

    @Test
    fun `high preference selects an advertised 60 fps range`() {
        val selected = CameraStreamSelector.selectFpsRangeBounds(
            listOf(15 to 30, 30 to 30, 30 to 60, 60 to 60),
            requestedCeiling = 60,
        )

        assertEquals(60 to 60, selected)
    }

    @Test
    fun `dark scene selects flexible fifteen to thirty range`() {
        val selected = CameraStreamSelector.selectFpsRangeBounds(
            listOf(15 to 30, 30 to 30, 30 to 60, 60 to 60),
            requestedCeiling = 60,
            lowLight = true,
        )

        assertEquals(15 to 30, selected)
    }

    @Test
    fun `fps fallback descends without changing stream workflow`() {
        assertEquals(30, CameraStreamSelector.nextFallbackFpsCeiling(60))
        assertEquals(24, CameraStreamSelector.nextFallbackFpsCeiling(30))
        assertNull(CameraStreamSelector.nextFallbackFpsCeiling(24))
        assertNull(CameraStreamSelector.nextFallbackFpsCeiling(null))
    }

    @Test
    fun `omits explicit request when no advertised range stays under target`() {
        val selected = CameraStreamSelector.selectFpsRangeBounds(
            listOf(15 to 30, 30 to 60),
            requestedCeiling = 24,
        )

        assertNull(selected)
    }

    @Test
    fun `returns no request when camera advertises no ranges`() {
        assertNull(CameraStreamSelector.selectFpsRangeBounds(emptyList(), requestedCeiling = 30))
    }
}
