package com.lightmeter.rawmeter

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityFilterTest {
    @Test
    fun acceptsConventionalColorAndIncompletePhysicalMetadata() {
        assertTrue(
            CameraCatalog.CameraCapabilityFilter.isVisibleLightCamera(
                intArrayOf(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE),
                colorFilter = 0,
            ),
        )
        assertTrue(
            CameraCatalog.CameraCapabilityFilter.isVisibleLightCamera(
                capabilities = intArrayOf(),
                colorFilter = null,
            ),
        )
    }

    @Test
    fun rejectsMonochromeNirAndDepthOnlyCameras() {
        assertFalse(
            CameraCatalog.CameraCapabilityFilter.isVisibleLightCamera(
                intArrayOf(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME),
                colorFilter = 5,
            ),
        )
        assertFalse(
            CameraCatalog.CameraCapabilityFilter.isVisibleLightCamera(
                capabilities = intArrayOf(),
                colorFilter = 6,
            ),
        )
        assertFalse(
            CameraCatalog.CameraCapabilityFilter.isVisibleLightCamera(
                intArrayOf(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT),
                colorFilter = null,
            ),
        )
    }

    @Test
    fun acceptsColorCameraThatAlsoAdvertisesDepth() {
        assertTrue(
            CameraCatalog.CameraCapabilityFilter.isVisibleLightCamera(
                intArrayOf(
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT,
                ),
                colorFilter = 0,
            ),
        )
    }
}
