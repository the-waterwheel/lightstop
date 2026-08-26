package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
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
}
