package com.lightmeter.rawmeter

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOutputAspectIdentityTest {
    @Test
    fun staleRuntimePhysicalLensIsNotAppliedToANewSelection() {
        assertEquals(
            "1",
            CameraOutputAspectIdentity.resolve(
                cameraId = "1",
                selectedCameraId = "1",
                lensRole = CameraLensRole.AUTOMATIC,
                runtimeCameraId = "0",
                activePhysicalCameraId = "2",
                runtimeCalibrationCameraId = "0@2",
            ),
        )
    }

    @Test
    fun activeAutomaticRouteUsesItsConfirmedPhysicalLens() {
        assertEquals(
            "0@2",
            CameraOutputAspectIdentity.resolve(
                cameraId = "0",
                selectedCameraId = "0",
                lensRole = CameraLensRole.AUTOMATIC,
                runtimeCameraId = "0",
                activePhysicalCameraId = "2",
                runtimeCalibrationCameraId = "0@2",
            ),
        )
    }

    @Test
    fun fixedRouteAlwaysKeepsItsOwnPreferenceIdentity() {
        assertEquals(
            "0@3",
            CameraOutputAspectIdentity.resolve(
                cameraId = "0@3",
                selectedCameraId = "0@3",
                lensRole = CameraLensRole.TELEPHOTO,
                runtimeCameraId = "0@3",
                activePhysicalCameraId = "3",
                runtimeCalibrationCameraId = "0@3",
            ),
        )
    }
}
