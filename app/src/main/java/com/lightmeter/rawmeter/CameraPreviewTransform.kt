package com.lightmeter.rawmeter

import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.view.Surface
import kotlin.math.max

/** Builds an aspect-preserving TextureView transform for every frame shape and orientation. */
internal object CameraPreviewTransform {
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
    ): Matrix {
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (displayRotation == Surface.ROTATION_90 ||
            displayRotation == Surface.ROTATION_270
        ) {
            val bufferRect = RectF(
                0f,
                0f,
                bufferSize.height.toFloat(),
                bufferSize.width.toFloat(),
            )
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / bufferSize.height,
                viewWidth.toFloat() / bufferSize.width,
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(
                if (displayRotation == Surface.ROTATION_90) -90f else 90f,
                centerX,
                centerY,
            )
        } else {
            val orientedBufferWidth = bufferSize.height.toFloat()
            val orientedBufferHeight = bufferSize.width.toFloat()
            val implicitScaleX = viewWidth / orientedBufferWidth
            val implicitScaleY = viewHeight / orientedBufferHeight
            val centerCropScale = max(implicitScaleX, implicitScaleY)
            matrix.postScale(
                centerCropScale / implicitScaleX,
                centerCropScale / implicitScaleY,
                centerX,
                centerY,
            )
            if (displayRotation == Surface.ROTATION_180) {
                matrix.postRotate(180f, centerX, centerY)
            }
        }
        matrix.postScale(displayZoom, displayZoom, centerX, centerY)
        return matrix
    }
}
