package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CameraRouteResolverTest {
    @Test
    fun physicalSelectionAlwaysTriesTheRequestedPhysicalRouteFirst() {
        val routes = CameraRouteResolver.candidates(physicalDescriptor(), forceLogicalFallback = false)

        assertEquals(2, routes.size)
        assertEquals("0", routes[0].logicalCameraId)
        assertEquals("2", routes[0].physicalCameraId)
        assertFalse(routes[0].isLogicalFallback)
        assertNull(routes[1].physicalCameraId)
    }

    @Test
    fun logicalFallbackRemovesOnlyTheExplicitPhysicalRoute() {
        val routes = CameraRouteResolver.candidates(physicalDescriptor(), forceLogicalFallback = true)

        assertEquals(1, routes.size)
        assertNull(routes.single().physicalCameraId)
        assertEquals("0", routes.single().logicalCameraId)
    }

    @Test
    fun automaticSelectionUsesOnlyTheLogicalRoute() {
        val automatic = physicalDescriptor().copy(cameraId = "0", physicalCameraId = null)

        val routes = CameraRouteResolver.candidates(automatic, forceLogicalFallback = false)

        assertEquals(1, routes.size)
        assertNull(routes.single().physicalCameraId)
        assertFalse(routes.single().isLogicalFallback)
    }

    private fun physicalDescriptor() = CameraDescriptor(
        cameraId = "0@2",
        logicalCameraId = "0",
        physicalCameraId = "2",
        lensFacing = CameraCharacteristics.LENS_FACING_BACK,
        rawAvailable = true,
        manualSensorAvailable = true,
        focalLengthsMm = listOf(5f),
        apertures = listOf(1.8f),
        sensorWidthMm = 6f,
        sensorHeightMm = 4.5f,
        sensorOrientationDegrees = 90,
        maxDigitalZoom = 5f,
    )
}
