package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPreviewTransformTest {
    @Test
    fun relativeRotationUsesTheCamera2FrontAndBackFacingSigns() {
        assertEquals(
            180,
            CameraPreviewTransform.relativeRotationDegrees(
                90,
                Surface.ROTATION_90,
                CameraCharacteristics.LENS_FACING_BACK,
            ),
        )
        assertEquals(
            180,
            CameraPreviewTransform.relativeRotationDegrees(
                270,
                Surface.ROTATION_90,
                CameraCharacteristics.LENS_FACING_FRONT,
            ),
        )
    }

    @Test
    fun sensorAspectIsInvertedOnlyForQuarterTurns() {
        assertEquals(
            3f / 4f,
            CameraPreviewTransform.screenAspectInSensorCoordinates(
                4f / 3f,
                90,
                Surface.ROTATION_0,
                CameraCharacteristics.LENS_FACING_BACK,
            ),
            0.0001f,
        )
        assertEquals(
            4f / 3f,
            CameraPreviewTransform.screenAspectInSensorCoordinates(
                4f / 3f,
                90,
                Surface.ROTATION_90,
                CameraCharacteristics.LENS_FACING_BACK,
            ),
            0.0001f,
        )
    }
}
