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
    val landscape: Boolean,
) {
    companion object {
        val EMPTY = ParameterHistoryGeometry(RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), false)
    }
}

internal object ParameterHistoryGeometryCalculator {
    fun calculate(width: Int, height: Int, density: Float, safeTop: Float): ParameterHistoryGeometry {
        if (width <= 0 || height <= 0) return ParameterHistoryGeometry.EMPTY
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
        val meteringHeight = (data.height() * 0.44f).coerceAtLeast(92f * density)
        val metering = RectF(data.left, data.bottom - meteringHeight, data.right, data.bottom)
        return ParameterHistoryGeometry(back, delete, content, image, data, metering, landscape)
    }
}
