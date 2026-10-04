package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiInfoTest {
    @Test
    fun automaticRouteUsesTheLogicalIdentityUntilAnActivePhysicalIdIsKnown() {
        val info = CameraUiInfo(cameraId = "0", logicalCameraId = "0")

        assertEquals("0", info.calibrationCameraId)
    }

    @Test
    fun activePhysicalIdProducesASeparateCalibrationIdentity() {
        val info = CameraUiInfo(
            cameraId = "0",
            logicalCameraId = "0",
            activePhysicalCameraId = "2",
        )

        assertEquals("0@2", info.calibrationCameraId)
    }

    @Test
    fun logicalFallbackUsesRuntimeIdentityInsteadOfSelectedPhysicalLens() {
        val info = CameraUiInfo(
            cameraId = "0@2",
            logicalCameraId = "0",
            runtimeCameraId = "0",
            activePhysicalCameraId = null,
        )

        assertEquals("0", info.calibrationCameraId)
    }

    @Test
    fun publicDirectLensKeepsItsSelectionIdentity() {
        val info = CameraUiInfo(
            cameraId = "0@2",
            logicalCameraId = "0",
            runtimeCameraId = "0@2",
            activePhysicalCameraId = null,
        )

        assertEquals("0@2", info.calibrationCameraId)
    }

    @Test
    fun sessionDowngradeDoesNotEraseHardwareRawCapability() {
        val info = CameraUiInfo(
            cameraId = "0",
            rawHardwareAvailable = true,
            rawAvailable = true,
        )

        val downgraded = info.copy(rawAvailable = false)

        assertTrue(downgraded.rawHardwareAvailable)
        assertFalse(downgraded.rawAvailable)
    }
}
