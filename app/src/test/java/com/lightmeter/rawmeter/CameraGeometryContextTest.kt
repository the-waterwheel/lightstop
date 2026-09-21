package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CameraGeometryContextTest {
    private val preview = PreviewStreamGeometry(
        routeId = "0",
        generation = 1L,
        sensorOrientationDegrees = 90,
        lensFacing = 0,
        activeArray = null,
        previewWidth = 1920,
        previewHeight = 1080,
        sensorViewport = NormalizedSensorViewport.FULL,
    )
    private val raw = RawSensorGeometry(
        routeId = "0",
        generation = 1L,
        physicalCameraId = "2",
        sensorOrientationDegrees = 270,
        lensFacing = 0,
        activeArray = null,
        rawWidth = 4000,
        rawHeight = 3000,
    )

    @Test
    fun `unchanged domains keep the epoch`() {
        val context = CameraGeometryContext.EMPTY.withDomains(preview, raw)
        val same = context.withDomains(preview, raw)
        assertEquals(context, same)
        assertEquals(context.epoch, same.epoch)
    }

    @Test
    fun `changing only the raw domain advances the epoch`() {
        val context = CameraGeometryContext.EMPTY.withDomains(preview, raw)
        val updated = context.withDomains(preview, raw.copy(physicalCameraId = "3"))
        assertNotEquals(context.epoch, updated.epoch)
        assertEquals(preview, updated.preview)
        assertEquals("3", updated.raw?.physicalCameraId)
    }

    @Test
    fun `changing the preview stream advances the epoch`() {
        val context = CameraGeometryContext.EMPTY.withDomains(preview, raw)
        val updated = context.withDomains(
            preview.copy(generation = 2L, previewWidth = 1280, previewHeight = 720),
            raw,
        )
        assertNotEquals(context.epoch, updated.epoch)
    }

    @Test
    fun `preview and raw orientation may differ independently`() {
        val context = CameraGeometryContext.EMPTY.withDomains(preview, raw)
        assertEquals(90, context.preview?.sensorOrientationDegrees)
        assertEquals(270, context.raw?.sensorOrientationDegrees)
    }

    @Test
    fun `updating the raw domain never overwrites the preview domain`() {
        val context = CameraGeometryContext.EMPTY.withDomains(preview, raw)
        val updated = CameraGeometryPolicy.updateRawDomain(
            context,
            raw.copy(physicalCameraId = "3", sensorOrientationDegrees = 0),
        )
        assertEquals(preview, updated.preview)
        assertEquals(90, updated.preview?.sensorOrientationDegrees)
    }
}
