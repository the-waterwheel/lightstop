package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewOutputGeometryTest {
    @Test
    fun `manual aspect accepts ratios and decimal values`() {
        assertEquals(4f / 3f, PreviewOutputGeometry.parseAspect("4:3")!!, 0.0001f)
        assertEquals(16f / 9f, PreviewOutputGeometry.parseAspect("9：16")!!, 0.0001f)
        assertEquals(1.5f, PreviewOutputGeometry.parseAspect("1.5")!!, 0.0001f)
        assertEquals(null, PreviewOutputGeometry.parseAspect("0:3"))
    }

    @Test
    fun `reported crop and stream shape produce a normalized sensor viewport`() {
        val viewport = PreviewOutputGeometry.sensorViewport(
            activeLeft = 0,
            activeTop = 0,
            activeRight = 4000,
            activeBottom = 3000,
            cropLeft = 500,
            cropTop = 375,
            cropRight = 3500,
            cropBottom = 2625,
            zoomRatio = null,
            outputWidth = 1920,
            outputHeight = 1080,
        )

        assertEquals(0.125f, viewport.left, 0.0001f)
        assertEquals(0.21875f, viewport.top, 0.0001f)
        assertEquals(0.875f, viewport.right, 0.0001f)
        assertEquals(0.78125f, viewport.bottom, 0.0001f)
    }

    @Test
    fun `screen transform maps through cropped sensor viewport`() {
        val transform = ScreenToSensorCoordinateTransform(
            rotationDegrees = 0,
            mirrored = false,
            sensorViewport = NormalizedSensorViewport(0.2f, 0.1f, 0.8f, 0.9f),
        )

        assertEquals(0.2f, transform.map(0f, 0f).first, 0.0001f)
        assertEquals(0.9f, transform.map(1f, 1f).second, 0.0001f)
        assertEquals(0.5f, transform.map(0.5f, 0.5f).first, 0.0001f)
    }

    @Test
    fun `equivalent focal uses live preview crop film crop and display zoom`() {
        val visible = PreviewOutputGeometry.visibleSensorSizeMm(
            sensorWidthMm = 6.4,
            sensorHeightMm = 4.8,
            sensorViewport = NormalizedSensorViewport(0f, 0.125f, 1f, 0.875f),
            frameAspectInSensor = 3.0 / 2.0,
            displayZoom = 2.0,
        )!!

        // The 16:9 preview exposes 6.4×3.6 mm; a 3:2 film frame and 2× display zoom expose 2.7×1.8 mm.
        assertEquals(2.7, visible.width, 0.0001)
        assertEquals(1.8, visible.height, 0.0001)
        assertEquals(
            53.34,
            PreviewOutputGeometry.fullFrameEquivalentFocalMm(4.0, visible)!!,
            0.02,
        )
    }
}
