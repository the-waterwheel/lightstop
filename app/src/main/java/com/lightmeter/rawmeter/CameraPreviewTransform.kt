package com.lightmeter.rawmeter

import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.view.Surface
import kotlin.math.max

/** Builds an aspect-preserving TextureView transform for every frame shape and orientation. */
internal object CameraPreviewTransform {
    internal data class Geometry(
        val scaleX: Float,
        val scaleY: Float,
        val displayRotationDegrees: Float,
    )

    fun shouldMirrorPreview(lensFacing: Int): Boolean =
        lensFacing == CameraCharacteristics.LENS_FACING_FRONT

    /** Mirrors Android's documented Camera2 sensor-to-display relative rotation formula. */
    fun relativeRotationDegrees(
        sensorOrientationDegrees: Int,
        displayRotation: Int,
        lensFacing: Int,
    ): Int {
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sign = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) 1 else -1
        return (sensorOrientationDegrees - displayDegrees * sign + 360) % 360
    }

    fun screenAspectInSensorCoordinates(
        screenAspect: Float,
        sensorOrientationDegrees: Int,
        displayRotation: Int,
        lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK,
    ): Float {
        val relativeRotation = relativeRotationDegrees(
            sensorOrientationDegrees,
            displayRotation,
            lensFacing,
        )
        return if (relativeRotation == 90 || relativeRotation == 270) {
            1f / screenAspect
        } else {
            screenAspect
        }
    }

    fun create(
        viewWidth: Int,
        viewHeight: Int,
        displayRotation: Int,
        displayZoom: Float,
        bufferSize: Size,
        sensorOrientationDegrees: Int,
        lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK,
    ): Matrix {
        val geometry = geometry(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            displayRotation = displayRotation,
            displayZoom = displayZoom,
            bufferWidth = bufferSize.width,
            bufferHeight = bufferSize.height,
            sensorOrientationDegrees = sensorOrientationDegrees,
            lensFacing = lensFacing,
        )
        val matrix = Matrix()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        matrix.setScale(geometry.scaleX, geometry.scaleY, centerX, centerY)
        matrix.postRotate(geometry.displayRotationDegrees, centerX, centerY)
        if (shouldMirrorPreview(lensFacing)) {
            matrix.postScale(-1f, 1f, centerX, centerY)
        }
        return matrix
    }

    /**
     * TextureView already applies the camera's sensor-to-natural-orientation transform. It still
     * stretches that oriented buffer to the View bounds and does not compensate display rotation.
     * Undo the implicit non-uniform scale, apply one uniform center-crop scale, then rotate only by
     * the display angle. Sensor orientation is nevertheless required to know which buffer axis
     * TextureView implicitly placed on X/Y, especially on laptops, tablets and external cameras.
     */
    internal fun geometry(
        viewWidth: Int,
        viewHeight: Int,
        displayRotation: Int,
        displayZoom: Float,
        bufferWidth: Int,
        bufferHeight: Int,
        sensorOrientationDegrees: Int,
        lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK,
    ): Geometry {
        require(viewWidth > 0 && viewHeight > 0)
        require(bufferWidth > 0 && bufferHeight > 0)
        val normalizedSensor = ((sensorOrientationDegrees % 360) + 360) % 360
        val sensorQuarterTurn = normalizedSensor == 90 || normalizedSensor == 270
        val naturalBufferWidth = if (sensorQuarterTurn) {
            bufferHeight.toFloat()
        } else {
            bufferWidth.toFloat()
        }
        val naturalBufferHeight = if (sensorQuarterTurn) {
            bufferWidth.toFloat()
        } else {
            bufferHeight.toFloat()
        }
        val relativeRotation = relativeRotationDegrees(
            normalizedSensor,
            displayRotation,
            lensFacing,
        )
        val relativeQuarterTurn = relativeRotation == 90 || relativeRotation == 270
        val displayedBufferWidth = if (relativeQuarterTurn) {
            bufferHeight.toFloat()
        } else {
            bufferWidth.toFloat()
        }
        val displayedBufferHeight = if (relativeQuarterTurn) {
            bufferWidth.toFloat()
        } else {
            bufferHeight.toFloat()
        }
        val implicitScaleX = viewWidth / naturalBufferWidth
        val implicitScaleY = viewHeight / naturalBufferHeight
        val centerCropScale = max(
            viewWidth / displayedBufferWidth,
            viewHeight / displayedBufferHeight,
        )
        val zoom = displayZoom.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        return Geometry(
            scaleX = centerCropScale / implicitScaleX * zoom,
            scaleY = centerCropScale / implicitScaleY * zoom,
            displayRotationDegrees = -displayRotationDegrees(displayRotation).toFloat(),
        )
    }

    private fun displayRotationDegrees(displayRotation: Int): Int = when (displayRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
