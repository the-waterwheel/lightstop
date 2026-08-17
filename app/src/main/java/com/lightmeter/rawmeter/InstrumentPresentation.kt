package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.min

internal data class FormatMenuGrid(val columns: Int, val rows: Int)

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

    fun formatShortLabel(format: FrameFormat, language: MenuLanguage): String = when (format.id) {
        "half" -> if (language == MenuLanguage.ENGLISH) "Half" else "半格"
        "645" -> "645"
        "66" -> "6×6"
        "67" -> "6×7"
        "69" -> "6×9"
        "65x24" -> "65:24"
        "612" -> "6×12"
        "617" -> "6×17"
        "45" -> "4×5"
        "57" -> "5×7"
        "810" -> "8×10"
        else -> "135"
    }

    /** Chooses enough columns to keep labels readable, then wraps remaining formats into rows. */
    fun formatMenuGrid(
        itemCount: Int,
        availableWidth: Float,
        density: Float,
    ): FormatMenuGrid {
        if (itemCount <= 0 || availableWidth <= 0f) return FormatMenuGrid(0, 0)
        val gap = 2f * density
        val minimumItemWidth = 36f * density
        val columnsThatFit = ((availableWidth + gap) / (minimumItemWidth + gap))
            .toInt()
            .coerceAtLeast(1)
        val columns = minOf(itemCount, MAX_FORMAT_COLUMNS, columnsThatFit)
        val rows = (itemCount + columns - 1) / columns
        return FormatMenuGrid(columns, rows)
    }

    /** Grid used by the Zone overlay, capped vertically so it cannot run off-screen. */
    fun formatMenuGridWithMaximumRows(itemCount: Int, maximumRows: Int): FormatMenuGrid {
        if (itemCount <= 0 || maximumRows <= 0) return FormatMenuGrid(0, 0)
        val columns = (itemCount + maximumRows - 1) / maximumRows
        val rows = (itemCount + columns - 1) / columns
        return FormatMenuGrid(columns, rows)
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
        val grid = formatMenuGrid(FrameFormat.ALL.size, availableWidth, density)
        if (grid.columns == 0) return emptyList()
        val itemWidth = (availableWidth - gap * (grid.columns - 1)) / grid.columns
        val itemHeight = geometry.formatButton.height()
        return FrameFormat.ALL.indices.map { index ->
            val column = index % grid.columns
            val row = index / grid.columns
            val itemLeft = left + column * (itemWidth + gap)
            val itemTop = geometry.formatButton.top + row * (itemHeight + gap)
            RectF(
                itemLeft,
                itemTop,
                min(itemLeft + itemWidth, right),
                itemTop + itemHeight,
            )
        }
    }

    private const val MAX_FORMAT_COLUMNS = 6
}
