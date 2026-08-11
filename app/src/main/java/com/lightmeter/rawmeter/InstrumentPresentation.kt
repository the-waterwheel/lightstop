package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.min

internal data class ExposureScaleCenters(
    val aperture: Double,
    val shutter: Double,
)

/** Pure presentation decisions used by the normal meter screen. */
internal object InstrumentPresentation {
    fun exposureCenters(
        state: MeterState,
        evAtIso: Double,
        lockedDisplayCoordinate: Double,
        frozenDependentCoordinate: Double,
        releasedDependentCoordinate: Double,
    ): ExposureScaleCenters {
        var aperture: Double
        var shutter: Double
        if (state.exposureLockMode == ExposureLockMode.APERTURE) {
            aperture = lockedDisplayCoordinate.takeIf(Double::isFinite)
                ?: state.lockedApertureStop
            shutter = state.lockedApertureStop - evAtIso
        } else {
            shutter = lockedDisplayCoordinate.takeIf(Double::isFinite)
                ?: state.lockedShutterLogSeconds
            aperture = state.lockedShutterLogSeconds + evAtIso
        }
        val dependentOverride = when {
            frozenDependentCoordinate.isFinite() -> frozenDependentCoordinate
            releasedDependentCoordinate.isFinite() -> releasedDependentCoordinate
            else -> Double.NaN
        }
        if (dependentOverride.isFinite()) {
            if (state.exposureLockMode == ExposureLockMode.APERTURE) {
                shutter = dependentOverride
            } else {
                aperture = dependentOverride
            }
        }
        return ExposureScaleCenters(aperture, shutter)
    }

    fun formatShortLabel(format: FrameFormat): String = when (format.id) {
        "half" -> "半格"
        "645" -> "645"
        "66" -> "6×6"
        "67" -> "6×7"
        "69" -> "6×9"
        "65x24" -> "65:24"
        else -> "135"
    }

    fun formatOptionRects(geometry: LayoutGeometry, density: Float): List<RectF> {
        val left: Float
        val right: Float
        if (geometry.formatButton.centerX() < geometry.orientationButton.centerX()) {
            left = geometry.formatButton.right + 4f * density
            right = geometry.orientationButton.left - 4f * density
        } else {
            left = geometry.orientationButton.right + 4f * density
            right = geometry.formatButton.left - 4f * density
        }
        val availableWidth = (right - left).coerceAtLeast(0f)
        if (availableWidth < 8f * density) return emptyList()
        val gap = 2f * density
        val itemWidth = (
            (availableWidth - gap * (FrameFormat.ALL.size - 1)) / FrameFormat.ALL.size
            ).coerceAtLeast(18f * density)
        return FrameFormat.ALL.indices.mapNotNull { index ->
            val itemLeft = left + index * (itemWidth + gap)
            val itemRight = min(itemLeft + itemWidth, right)
            if (itemLeft >= right) {
                null
            } else {
                RectF(
                    itemLeft,
                    geometry.formatButton.top,
                    itemRight,
                    geometry.formatButton.bottom,
                )
            }
        }
    }
}
