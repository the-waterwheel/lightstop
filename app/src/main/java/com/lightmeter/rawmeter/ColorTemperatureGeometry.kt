package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.min

internal data class ColorTemperatureGeometry(
    val back: RectF,
    val close: RectF,
    val content: RectF,
    val estimate: RectF,
    val reference: RectF,
    val headerHeight: Float,
) {
    companion object {
        val EMPTY = ColorTemperatureGeometry(RectF(), RectF(), RectF(), RectF(), RectF(), 0f)
    }
}

internal object ColorTemperatureGeometryCalculator {
    fun calculate(width: Int, height: Int, density: Float): ColorTemperatureGeometry {
        if (width <= 0 || height <= 0) return ColorTemperatureGeometry.EMPTY
        val pad = (12f * density).coerceAtMost(min(width, height) * 0.06f)
        val header = (54f * density).coerceAtMost(height * 0.18f).coerceAtLeast(42f * density)
        val touch = 46f * density
        val buttonHeight = (48f * density).coerceAtMost(height * 0.18f).coerceAtLeast(40f * density)
        val gap = 9f * density
        val buttonTop = height - pad - buttonHeight
        val availableWidth = width - pad * 2f - gap
        val estimateWidth = availableWidth * 0.58f
        return ColorTemperatureGeometry(
            back = RectF(0f, 0f, touch, header),
            close = RectF(width - touch, 0f, width.toFloat(), header),
            content = RectF(pad, header, width - pad, buttonTop - gap),
            estimate = RectF(pad, buttonTop, pad + estimateWidth, buttonTop + buttonHeight),
            reference = RectF(pad + estimateWidth + gap, buttonTop, width - pad, buttonTop + buttonHeight),
            headerHeight = header,
        )
    }
}
