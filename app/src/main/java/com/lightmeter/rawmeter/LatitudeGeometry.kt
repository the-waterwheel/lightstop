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
        val filmHeight = (contentHeight * 0.36f).coerceIn(44f * density, 66f * density)
        val filmWidth = (w * 0.56f).coerceAtLeast(w * 0.48f)
        val filmCard = RectF(pad, contentTop, filmWidth, contentTop + filmHeight)
        val select = RectF(filmCard.right + gap, contentTop, w - pad, contentTop + filmHeight)
        val buttonsTop = filmCard.bottom + gap
        val availableActionHeight = (contentBottom - buttonsTop).coerceAtLeast(1f)

        return if (w >= 300f * density) {
            val applyHeight = min(60f * density, availableActionHeight)
            val applyWidth = min(168f * density, w * 0.42f)
            val apply = RectF(w - pad - applyWidth, buttonsTop, w - pad, buttonsTop + applyHeight)
            val resetSize = min(38f * density, applyHeight * 0.68f)
            val reset = RectF(pad, buttonsTop, pad + resetSize, buttonsTop + resetSize)
            val recordLeft = reset.right + gap
            val recordWidth = min(148f * density, (apply.left - gap - recordLeft).coerceAtLeast(1f))
            val recordHeight = min(44f * density, applyHeight * 0.78f)
            val recordTop = buttonsTop + (applyHeight - recordHeight) / 2f
            val record = RectF(recordLeft, recordTop, recordLeft + recordWidth, recordTop + recordHeight)
            LatitudeGeometry(back, close, rail, filmCard, select, apply, reset, record)
        } else {
            val applyWidth = (w - pad * 2f) * 0.44f
            val apply = RectF(w - pad - applyWidth, buttonsTop, w - pad, contentBottom)
            val compactRight = apply.left - gap
            val compactHeight = ((contentBottom - buttonsTop - gap) / 2f).coerceAtLeast(1f)
            val reset = RectF(pad, buttonsTop, compactRight, buttonsTop + compactHeight)
            val record = RectF(pad, reset.bottom + gap, compactRight, contentBottom)
            LatitudeGeometry(back, close, rail, filmCard, select, apply, reset, record)
        }
    }

}
