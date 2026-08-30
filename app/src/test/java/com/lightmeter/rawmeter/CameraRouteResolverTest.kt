package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CameraRouteResolverTest {
    @Test
    fun physicalSelectionAlwaysTriesTheRequestedPhysicalRouteFirst() {
        val routes = CameraRouteResolver.candidates(physicalDescriptor())

        assertEquals(2, routes.size)
        assertEquals("0", routes[0].cameraIdToOpen)
        assertEquals("2", routes[0].physicalCameraId)
        assertFalse(routes[0].isLogicalFallback)
        assertNull(routes[1].physicalCameraId)
    }

    @Test
    fun publicPhysicalIdAddsDirectRouteWithoutAddingAnotherVisibleCamera() {
        val routes = CameraRouteResolver.candidates(
            physicalDescriptor().copy(directCameraId = "2"),
        )

        assertEquals(3, routes.size)
        assertEquals(CameraRouteKind.PUBLIC_DIRECT, routes[0].kind)
        assertEquals("2", routes[0].cameraIdToOpen)
        assertNull(routes[0].physicalCameraId)
        assertEquals(CameraRouteKind.FIXED_PHYSICAL, routes[1].kind)
        assertEquals("0", routes[1].cameraIdToOpen)
        assertEquals("2", routes[1].physicalCameraId)
        assertEquals(CameraRouteKind.LOGICAL_FALLBACK, routes[2].kind)
        assertEquals("0", routes[2].cameraIdToOpen)
        assertNull(routes[2].physicalCameraId)
    }

    @Test
    fun automaticSelectionUsesOnlyTheLogicalRoute() {
        val automatic = physicalDescriptor().copy(cameraId = "0", physicalCameraId = null)

        val routes = CameraRouteResolver.candidates(automatic)

        assertEquals(1, routes.size)
        assertNull(routes.single().physicalCameraId)
        assertFalse(routes.single().isLogicalFallback)
        assertEquals(CameraRouteKind.LOGICAL_AUTO, routes.single().kind)
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
