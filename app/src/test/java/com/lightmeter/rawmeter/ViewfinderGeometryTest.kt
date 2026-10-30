package com.lightmeter.rawmeter

import org.junit.Assert.*
import org.junit.Test

class ViewfinderGeometryTest {
    @Test fun everyUnzoomedFormatTouchesThePreviewWithoutCroppingItsImage() {
        for (preview in listOf(.75f, 4f / 3, 9f / 16, 16f / 9)) {
            for (format in FrameFormat.ALL) for (portrait in listOf(false, true)) {
                val aspect = if (portrait) 1f / format.landscapeAspect else format.landscapeAspect
                val crop = ViewfinderGeometry.framingCrop(preview, aspect, 1f)
                assertEquals(aspect, crop.width * preview / crop.height, .0001f)
                assertTrue(crop.left >= -.00001 && crop.top >= -.00001 && crop.right <= 1.00001 && crop.bottom <= 1.00001)
                assertTrue(crop.left < .00001f || crop.top < .00001f)
            }
        }
    }

    @Test fun inspectionProjectionPreservesTheSceneCropWhileFocalZoomChangesIt() {
        val crop = ViewfinderGeometry.framingCrop(.75f, 1.5f, 2f)
        for (zoom in listOf(1f, 2f, 5f, 8f)) {
            val display = ViewfinderGeometry.project(crop, zoom)
            val restored = ViewfinderGeometry.project(display, 1f / zoom)
            assertTrue(crop.isCloseTo(restored, .000001f))
        }
        val wide = ViewfinderGeometry.framingCrop(.75f, 1.5f, 1f)
        assertEquals(wide.width / 2, crop.width, .000001f)
        assertEquals(wide.height / 2, crop.height, .000001f)
        assertTrue(ViewfinderGeometry.project(wide, 3f).left < 0f)
    }

    @Test fun framingMapsToTheSameSensorRegionForMirroredAndRotatedDisplays() {
        val crop = NormalizedSensorViewport(.2f, .35f, .8f, .65f)
        for (rotation in listOf(0, 90, 180, 270)) for (mirrored in listOf(false, true)) {
            val transform = ScreenToSensorCoordinateTransform(rotation, mirrored)
            val sensor = ViewfinderGeometry.sensorCrop(crop, transform)
            assertEquals(if (rotation % 180 == 0) crop.width else crop.height, sensor.width, .000001f)
            assertEquals(if (rotation % 180 == 0) crop.height else crop.width, sensor.height, .000001f)
        }
    }
}
