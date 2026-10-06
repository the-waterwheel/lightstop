package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal data class ParameterHistoryGeometry(
    val back: RectF,
    val delete: RectF,
    val content: RectF,
    val image: RectF,
    val data: RectF,
    val metering: RectF,
    val meteringDrawer: RectF,
    val meteringHandle: RectF,
    val landscape: Boolean,
) {
    companion object {
        val EMPTY = ParameterHistoryGeometry(RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), false)
    }
}

internal object ParameterHistoryGeometryCalculator {
    fun calculate(width: Int, height: Int, density: Float, safeTop: Float,
        meteringExpansion: Float = 0f): ParameterHistoryGeometry {
        if (width <= 0 || height <= 0) return ParameterHistoryGeometry.EMPTY
        val density = AdaptiveLayout.density(width, height, density, LayoutProfile.SCROLL)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = max(10f * density, min(w, h) * 0.018f)
        val headerTop = max(pad, safeTop + 3f * density)
        val headerHeight = 50f * density
        val back = RectF(pad, headerTop, pad + 48f * density, headerTop + headerHeight)
        val delete = RectF(w - pad - 70f * density, headerTop, w - pad, headerTop + headerHeight)
        val content = RectF(pad, headerTop + headerHeight + 5f * density, w - pad, h - pad)
        val landscape = w > h
        val image: RectF
        val data: RectF
        if (landscape) {
            image = RectF(content.left, content.top, content.left + content.width() * 0.52f, content.bottom)
            data = RectF(image.right + pad, content.top, content.right, content.bottom)
        } else {
            image = RectF(content.left, content.top, content.right, content.top + content.height() * 0.46f)
            data = RectF(content.left, image.bottom + pad, content.right, content.bottom)
        }
        // The playback panel contains a mode switch, Zone/marker rails, and two full exposure
        // scales. Derive its size from the available panel while keeping it usable on short,
        // wide screens instead of assuming one phone resolution.
        val handleHeight = min(44f * density, data.height())
        val availableMeteringHeight = (data.height() - handleHeight - 56f * density).coerceAtLeast(0f)
        val preferredMeteringHeight = data.height() * if (landscape) 0.60f else 0.56f
        val meteringHeight = preferredMeteringHeight
            .coerceAtLeast(min(132f * density, availableMeteringHeight))
            .coerceAtMost(min(205f * density, availableMeteringHeight))
        val drawerHeight = handleHeight + meteringHeight * meteringExpansion.coerceIn(0f, 1f)
        val drawer = RectF(data.left, data.bottom - drawerHeight, data.right, data.bottom)
        val handle = RectF(drawer.left, drawer.top, drawer.right, drawer.top + handleHeight)
        // Translate the full panel below the handle; clipping hides its content when collapsed.
        // A stable content height keeps the scales from stretching during a drag or animation.
        val metering = RectF(data.left, handle.bottom, data.right, handle.bottom + meteringHeight)
        return ParameterHistoryGeometry(back, delete, content, image, data, metering, drawer, handle, landscape)
    }
}
