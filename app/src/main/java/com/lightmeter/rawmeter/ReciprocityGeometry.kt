package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal data class ReciprocityGeometry(
    val back: RectF,
    val close: RectF,
    val scale: RectF,
    val filmCard: RectF,
    val selectFilm: RectF,
    val result: RectF,
    val apply: RectF,
) {
    companion object {
        val EMPTY = ReciprocityGeometry(
            RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(),
        )
    }
}

internal object ReciprocityGeometryCalculator {
    fun calculate(width: Int, height: Int, density: Float): ReciprocityGeometry {
        if (width <= 0 || height <= 0) return ReciprocityGeometry.EMPTY
        val w = width.toFloat()
        val h = height.toFloat()
        val shortSide = min(w, h)
        val pad = max(8f * density, shortSide * 0.018f)
        val gap = max(6f * density, shortSide * 0.012f)
        val headerHeight = (h * 0.12f).coerceIn(44f * density, 58f * density)
        val headerTarget = min(52f * density, w * 0.16f).coerceAtLeast(44f * density)
        val back = RectF(pad, 0f, pad + headerTarget, headerHeight)
        val close = RectF(w - pad - headerTarget, 0f, w - pad, headerHeight)

        val scaleHeight = (h * 0.24f).coerceIn(68f * density, 108f * density)
        val scaleTop = headerHeight + gap
        val scale = RectF(pad, scaleTop, w - pad, scaleTop + scaleHeight)

        val filmTop = scale.bottom + gap
        val remaining = (h - pad - filmTop).coerceAtLeast(1f)
        val filmHeight = (remaining * 0.34f).coerceIn(46f * density, 66f * density)
        val filmWidth = (w * 0.58f).coerceAtLeast(w * 0.50f)
        val filmCard = RectF(pad, filmTop, filmWidth, filmTop + filmHeight)
        val selectFilm = RectF(filmCard.right + gap, filmTop, w - pad, filmTop + filmHeight)

        val actionsTop = filmCard.bottom + gap
        val actionsBottom = h - pad
        val resultRight = (w * 0.56f).coerceAtMost(w - pad - 108f * density)
            .coerceAtLeast(w * 0.45f)
        val result = RectF(pad, actionsTop, resultRight, actionsBottom)
        val fullApply = RectF(result.right + gap, actionsTop, w - pad, actionsBottom)
        val applyHeight = fullApply.height() * 0.5f
        val apply = RectF(
            fullApply.left,
            fullApply.centerY() - applyHeight / 2f,
            fullApply.right,
            fullApply.centerY() + applyHeight / 2f,
        )
        return ReciprocityGeometry(back, close, scale, filmCard, selectFilm, result, apply)
    }
}
