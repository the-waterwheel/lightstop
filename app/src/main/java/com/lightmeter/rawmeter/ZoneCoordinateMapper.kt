package com.lightmeter.rawmeter

import org.opencv.core.Point

/** The portion of the unzoomed preview that is visible inside the film-frame viewport. */
internal data class ZoneVisibleViewport(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0.0001f)
    val height: Float get() = (bottom - top).coerceAtLeast(0.0001f)
}

/**
 * Converts between Zone UI, unzoomed preview, display, and OpenCV analysis coordinates.
 *
 * These conversions are intentionally pure. Keeping all rotation/zoom math together makes
 * orientation and device-aspect regressions easier to reason about and unit-test later.
 */
internal object ZoneCoordinateMapper {
    fun basePreviewToTexture(
        baseX: Float,
        baseY: Float,
        width: Int,
        height: Int,
        viewport: ZoneVisibleViewport,
        displayOriented: Boolean,
        displayRotationDegrees: Int,
    ): Point {
        val baseDisplayX = viewport.left + baseX * viewport.width
        val baseDisplayY = viewport.top + baseY * viewport.height
        val analysis = if (displayOriented) {
            Point(baseDisplayX.toDouble(), baseDisplayY.toDouble())
        } else {
            displayToAnalysis(baseDisplayX.toDouble(), baseDisplayY.toDouble(), displayRotationDegrees)
        }
        return Point(analysis.x * width, analysis.y * height)
    }

    fun textureToBasePreview(
        point: Point,
        width: Int,
        height: Int,
        viewport: ZoneVisibleViewport,
        displayOriented: Boolean,
        displayRotationDegrees: Int,
    ): Pair<Float, Float> {
        val baseDisplay = if (displayOriented) {
            Point(point.x / width, point.y / height)
        } else {
            analysisToDisplay(point.x / width, point.y / height, displayRotationDegrees)
        }
        return ((baseDisplay.x - viewport.left) / viewport.width).toFloat() to
            ((baseDisplay.y - viewport.top) / viewport.height).toFloat()
    }

    fun uiPreviewToBasePreview(
        uiX: Float,
        uiY: Float,
        viewport: ZoneVisibleViewport,
        displayZoom: Float,
    ): Pair<Float, Float> {
        val zoom = displayZoom.toDouble().coerceAtLeast(1.0)
        val zoomedDisplayX = viewport.left + uiX * viewport.width
        val zoomedDisplayY = viewport.top + uiY * viewport.height
        val baseDisplayX = 0.5 + (zoomedDisplayX - 0.5) / zoom
        val baseDisplayY = 0.5 + (zoomedDisplayY - 0.5) / zoom
        return ((baseDisplayX - viewport.left) / viewport.width).toFloat() to
            ((baseDisplayY - viewport.top) / viewport.height).toFloat()
    }

    fun basePreviewToUiPreview(
        baseX: Float,
        baseY: Float,
        viewport: ZoneVisibleViewport,
        displayZoom: Float,
    ): Pair<Float, Float> {
        val zoom = displayZoom.toDouble().coerceAtLeast(1.0)
        val baseDisplayX = viewport.left + baseX * viewport.width
        val baseDisplayY = viewport.top + baseY * viewport.height
        val zoomedDisplayX = 0.5 + (baseDisplayX - 0.5) * zoom
        val zoomedDisplayY = 0.5 + (baseDisplayY - 0.5) * zoom
        return ((zoomedDisplayX - viewport.left) / viewport.width).toFloat() to
            ((zoomedDisplayY - viewport.top) / viewport.height).toFloat()
    }

    fun displayVectorToAnalysis(
        x: Double,
        y: Double,
        displayOriented: Boolean,
        displayRotationDegrees: Int,
    ): Point = if (displayOriented) {
        Point(x, y)
    } else when (displayRotationDegrees) {
        90 -> Point(-y, x)
        180 -> Point(-x, -y)
        270 -> Point(y, -x)
        else -> Point(x, y)
    }

    fun analysisVectorToDisplay(
        x: Double,
        y: Double,
        displayOriented: Boolean,
        displayRotationDegrees: Int,
    ): Point = if (displayOriented) {
        Point(x, y)
    } else when (displayRotationDegrees) {
        90 -> Point(y, -x)
        180 -> Point(-x, -y)
        270 -> Point(-y, x)
        else -> Point(x, y)
    }

    private fun displayToAnalysis(x: Double, y: Double, rotation: Int): Point = when (rotation) {
        90 -> Point(1.0 - y, x)
        180 -> Point(1.0 - x, 1.0 - y)
        270 -> Point(y, 1.0 - x)
        else -> Point(x, y)
    }

    private fun analysisToDisplay(x: Double, y: Double, rotation: Int): Point = when (rotation) {
        90 -> Point(y, 1.0 - x)
        180 -> Point(1.0 - x, 1.0 - y)
        270 -> Point(1.0 - y, x)
        else -> Point(x, y)
    }
}
