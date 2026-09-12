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

    @Test
    fun frontCameraMirroringIsExplicitAndAppliedBeforeSensorRotation() {
        val transform = ScreenToSensorCoordinateTransform(
            rotationDegrees = 90,
            mirrored = CameraPreviewTransform.shouldMirrorPreview(
                CameraCharacteristics.LENS_FACING_FRONT,
            ),
        )

        assertEquals(0f, transform.map(0f, 0f).first, 0.0001f)
        assertEquals(0f, transform.map(0f, 0f).second, 0.0001f)
        assertEquals(0f, transform.map(1f, 0f).first, 0.0001f)
        assertEquals(1f, transform.map(1f, 0f).second, 0.0001f)
        assertEquals(false, CameraPreviewTransform.shouldMirrorPreview(CameraCharacteristics.LENS_FACING_BACK))
    }

    @Test
    fun `scaling axes follow sensor orientation instead of assuming a phone camera`() {
        val phoneSensor = CameraPreviewTransform.geometry(
            viewWidth = 1_000,
            viewHeight = 1_000,
            displayRotation = Surface.ROTATION_0,
            displayZoom = 1f,
            bufferWidth = 1_440,
            bufferHeight = 1_080,
            sensorOrientationDegrees = 90,
        )
        val unrotatedSensor = CameraPreviewTransform.geometry(
            viewWidth = 1_000,
            viewHeight = 1_000,
            displayRotation = Surface.ROTATION_0,
            displayZoom = 1f,
            bufferWidth = 1_440,
            bufferHeight = 1_080,
            sensorOrientationDegrees = 0,
        )

        assertEquals(1f, phoneSensor.scaleX, 0.0001f)
        assertEquals(4f / 3f, phoneSensor.scaleY, 0.0001f)
        assertEquals(4f / 3f, unrotatedSensor.scaleX, 0.0001f)
        assertEquals(1f, unrotatedSensor.scaleY, 0.0001f)
    }

    @Test
    fun `display rotation remains separate from TextureView sensor compensation`() {
        val geometry = CameraPreviewTransform.geometry(
            viewWidth = 2_000,
            viewHeight = 1_000,
            displayRotation = Surface.ROTATION_90,
            displayZoom = 1f,
            bufferWidth = 1_440,
            bufferHeight = 1_080,
            sensorOrientationDegrees = 90,
        )

        assertEquals(0.75f, geometry.scaleX, 0.0001f)
        assertEquals(2f, geometry.scaleY, 0.0001f)
        assertEquals(-90f, geometry.displayRotationDegrees, 0.0001f)
    }
}
