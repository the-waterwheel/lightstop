package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal data class LatitudeGeometry(
    val back: RectF,
    val close: RectF,
    val rail: RectF,
    val filmCard: RectF,
    val selectFilm: RectF,
    val apply: RectF,
    val reset: RectF,
    val record: RectF,
) {
    companion object {
        val EMPTY = LatitudeGeometry(
            RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(),
        )
    }
}

internal object LatitudeGeometryCalculator {
    fun calculate(width: Int, height: Int, density: Float): LatitudeGeometry {
        if (width <= 0 || height <= 0) return LatitudeGeometry.EMPTY
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = max(8f * density, min(w, h) * 0.018f)
        val gap = max(6f * density, min(w, h) * 0.012f)
        val headerHeight = (h * 0.12f).coerceIn(44f * density, 58f * density)
        val headerTarget = min(52f * density, w * 0.16f).coerceAtLeast(44f * density)
        val back = RectF(pad, 0f, pad + headerTarget, headerHeight)
        val close = RectF(w - pad - headerTarget, 0f, w - pad, headerHeight)

        val remaining = (h - headerHeight - pad).coerceAtLeast(1f)
        val railHeight = (remaining * 0.29f).coerceIn(
            min(82f * density, remaining * 0.45f),
            min(142f * density, remaining * 0.48f),
        )
        val rail = RectF(pad * 1.4f, headerHeight + gap, w - pad * 1.4f, headerHeight + gap + railHeight)
        val contentTop = rail.bottom + gap
        val contentBottom = h - pad
        val contentHeight = (contentBottom - contentTop).coerceAtLeast(1f)
        val narrow = w < 420f * density && h > w * 1.15f

        return if (narrow) {
            val filmHeight = (contentHeight * 0.20f).coerceAtLeast(42f * density)
            val filmCard = RectF(pad, contentTop, w - pad, contentTop + filmHeight)
            val buttonsTop = filmCard.bottom + gap
            val buttonHeight = ((contentBottom - buttonsTop - gap) / 2f).coerceAtLeast(1f)
            val columnWidth = ((w - pad * 2f - gap) / 2f).coerceAtLeast(1f)
            val select = RectF(pad, buttonsTop, pad + columnWidth, buttonsTop + buttonHeight)
            val apply = RectF(select.right + gap, buttonsTop, w - pad, buttonsTop + buttonHeight)
            val reset = RectF(pad, select.bottom + gap, pad + columnWidth, contentBottom)
            val record = RectF(reset.right + gap, apply.bottom + gap, w - pad, contentBottom)
            LatitudeGeometry(back, close, rail, filmCard, select, apply, reset, record)
        } else {
            val filmHeight = (contentHeight * 0.38f).coerceIn(44f * density, 72f * density)
            val filmWidth = (w * 0.56f).coerceAtLeast(w * 0.48f)
            val filmCard = RectF(pad, contentTop, filmWidth, contentTop + filmHeight)
            val select = RectF(filmCard.right + gap, contentTop, w - pad, contentTop + filmHeight)
            val buttonsTop = filmCard.bottom + gap
            val buttonHeight = (contentBottom - buttonsTop).coerceAtLeast(34f * density)
            val columnGap = gap
            val columnWidth = (w - pad * 2f - columnGap * 2f) / 3f
            val apply = RectF(pad, buttonsTop, pad + columnWidth, buttonsTop + buttonHeight)
            val reset = RectF(apply.right + columnGap, buttonsTop, apply.right + columnGap + columnWidth, buttonsTop + buttonHeight)
            val record = RectF(reset.right + columnGap, buttonsTop, w - pad, buttonsTop + buttonHeight)
            LatitudeGeometry(back, close, rail, filmCard, select, apply, reset, record)
        }
    }

}
