package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Normalized portion of the active sensor array represented by the displayed preview stream. */
data class NormalizedSensorViewport(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0.0001f)
    val height: Float get() = (bottom - top).coerceAtLeast(0.0001f)

    fun isCloseTo(other: NormalizedSensorViewport, tolerance: Float = 0.001f): Boolean =
        abs(left - other.left) <= tolerance &&
            abs(top - other.top) <= tolerance &&
            abs(right - other.right) <= tolerance &&
            abs(bottom - other.bottom) <= tolerance

    companion object {
        val FULL = NormalizedSensorViewport(0f, 0f, 1f, 1f)
    }
}

/** Pure preview geometry shared by layout, per-lens overrides, and Zone touch mapping. */
internal object PreviewOutputGeometry {
    data class VisibleSensorSizeMm(
        val width: Double,
        val height: Double,
    ) {
        val diagonal: Double get() = kotlin.math.hypot(width, height)
    }

    fun landscapeAspect(width: Int, height: Int, overrideAspect: Float?): Float {
        val automatic = if (width > 0 && height > 0) {
            max(width, height).toFloat() / min(width, height).toFloat()
        } else {
            DEFAULT_ASPECT
        }
        return overrideAspect
            ?.takeIf { it.isFinite() && it in MIN_ASPECT..MAX_ASPECT }
            ?.let { max(it, 1f / it) }
            ?: automatic
    }

    fun displayAspect(landscapeAspect: Float, relativeRotationDegrees: Int): Float =
        if (relativeRotationDegrees == 90 || relativeRotationDegrees == 270) {
            1f / landscapeAspect.coerceAtLeast(0.01f)
        } else {
            landscapeAspect.coerceAtLeast(0.01f)
        }

    /**
     * Combines the live Camera2 crop/zoom metadata with the configured stream aspect.
     * Coordinates are returned in the physical sensor's normalized active-array space.
     */
    fun sensorViewport(
        activeLeft: Int,
        activeTop: Int,
        activeRight: Int,
        activeBottom: Int,
        cropLeft: Int?,
        cropTop: Int?,
        cropRight: Int?,
        cropBottom: Int?,
        zoomRatio: Float?,
        outputWidth: Int,
        outputHeight: Int,
    ): NormalizedSensorViewport {
        val activeWidth = activeRight - activeLeft
        val activeHeight = activeBottom - activeTop
        if (activeWidth <= 0 || activeHeight <= 0) return NormalizedSensorViewport.FULL

        var left = 0f
        var top = 0f
        var right = activeWidth.toFloat()
        var bottom = activeHeight.toFloat()

        val zoom = zoomRatio?.takeIf { it.isFinite() && it > 1f } ?: 1f
        if (zoom > 1f) {
            val zoomedWidth = activeWidth / zoom
            val zoomedHeight = activeHeight / zoom
            left = (activeWidth - zoomedWidth) / 2f
            top = (activeHeight - zoomedHeight) / 2f
            right = left + zoomedWidth
            bottom = top + zoomedHeight
        }

        if (cropLeft != null && cropTop != null && cropRight != null && cropBottom != null &&
            cropRight > cropLeft && cropBottom > cropTop
        ) {
            left = max(left, (cropLeft - activeLeft).toFloat())
            top = max(top, (cropTop - activeTop).toFloat())
            right = min(right, (cropRight - activeLeft).toFloat())
            bottom = min(bottom, (cropBottom - activeTop).toFloat())
        }
        if (right <= left || bottom <= top) return NormalizedSensorViewport.FULL

        if (outputWidth > 0 && outputHeight > 0) {
            val outputAspect = outputWidth.toFloat() / outputHeight
            val currentAspect = (right - left) / (bottom - top)
            if (currentAspect > outputAspect) {
                val width = (bottom - top) * outputAspect
                val center = (left + right) / 2f
                left = center - width / 2f
                right = center + width / 2f
            } else if (currentAspect < outputAspect) {
                val height = (right - left) / outputAspect
                val center = (top + bottom) / 2f
                top = center - height / 2f
                bottom = center + height / 2f
            }
        }

        return NormalizedSensorViewport(
            left = (left / activeWidth).coerceIn(0f, 1f),
            top = (top / activeHeight).coerceIn(0f, 1f),
            right = (right / activeWidth).coerceIn(0f, 1f),
            bottom = (bottom / activeHeight).coerceIn(0f, 1f),
        )
    }

    fun parseAspect(text: String): Float? {
        val normalized = text.trim().replace('：', ':')
        if (normalized.isBlank()) return null
        val value = if (':' in normalized) {
            val parts = normalized.split(':')
            if (parts.size != 2) return null
            val width = parts[0].trim().toFloatOrNull() ?: return null
            val height = parts[1].trim().toFloatOrNull() ?: return null
            if (width <= 0f || height <= 0f) return null
            width / height
        } else {
            normalized.toFloatOrNull() ?: return null
        }
        val landscape = max(value, 1f / value)
        return landscape.takeIf { it.isFinite() && it in MIN_ASPECT..MAX_ASPECT }
    }

    fun label(aspect: Float): String {
        val value = max(aspect, 1f / aspect)
        val known = KNOWN_RATIOS.minByOrNull { abs(it.first - value) }
        return if (known != null && abs(known.first - value) <= 0.015f) {
            known.second
        } else {
            "%.3f:1".format(value)
        }
    }

    /** Physical sensor area actually visible after Camera2 crop, stream crop and display crop. */
    fun visibleSensorSizeMm(
        sensorWidthMm: Double,
        sensorHeightMm: Double,
        sensorViewport: NormalizedSensorViewport,
        frameAspectInSensor: Double,
        displayZoom: Double,
    ): VisibleSensorSizeMm? {
        if (!sensorWidthMm.isFinite() || !sensorHeightMm.isFinite() ||
            sensorWidthMm <= 0.0 || sensorHeightMm <= 0.0 ||
            !frameAspectInSensor.isFinite() || frameAspectInSensor <= 0.0 ||
            !displayZoom.isFinite() || displayZoom <= 0.0
        ) return null
        var width = sensorWidthMm * sensorViewport.width.toDouble()
        var height = sensorHeightMm * sensorViewport.height.toDouble()
        if (width / height > frameAspectInSensor) {
            width = height * frameAspectInSensor
        } else {
            height = width / frameAspectInSensor
        }
        width /= displayZoom
        height /= displayZoom
        return VisibleSensorSizeMm(width, height).takeIf {
            it.width > 0.0 && it.height > 0.0 && it.diagonal > 0.0
        }
    }

    fun fullFrameEquivalentFocalMm(
        physicalFocalLengthMm: Double,
        visibleSensorSize: VisibleSensorSizeMm,
    ): Double? = physicalFocalLengthMm
        .takeIf { it.isFinite() && it > 0.0 && visibleSensorSize.diagonal > 0.0 }
        ?.let { it * FULL_FRAME_DIAGONAL_MM / visibleSensorSize.diagonal }

    private val KNOWN_RATIOS = listOf(
        1f to "1:1",
        5f / 4f to "5:4",
        4f / 3f to "4:3",
        3f / 2f to "3:2",
        16f / 9f to "16:9",
        2f to "2:1",
    )
    private const val DEFAULT_ASPECT = 4f / 3f
    private const val MIN_ASPECT = 1f
    private const val MAX_ASPECT = 4f
    private const val FULL_FRAME_DIAGONAL_MM = 43.266615
}
