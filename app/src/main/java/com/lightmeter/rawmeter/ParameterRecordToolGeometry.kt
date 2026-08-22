package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal data class ParameterRecordToolGeometry(
    val back: RectF,
    val close: RectF,
    val history: RectF,
    val gpsRow: RectF,
    val timeRow: RectF,
    val rawRow: RectF,
    val gpsToggle: RectF,
    val timeToggle: RectF,
    val rawToggle: RectF,
    val rawHelp: RectF,
    val finishCategory: RectF,
    val startStop: RectF,
) {
    companion object {
        val EMPTY = ParameterRecordToolGeometry(
            RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(),
            RectF(), RectF(), RectF(), RectF(),
        )
    }
}

internal object ParameterRecordToolGeometryCalculator {
    fun calculate(width: Int, height: Int, density: Float): ParameterRecordToolGeometry {
        if (width <= 0 || height <= 0) return ParameterRecordToolGeometry.EMPTY
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = max(8f * density, min(w, h) * 0.018f)
        val gap = max(6f * density, min(w, h) * 0.012f)
        val headerHeight = (h * 0.12f).coerceIn(44f * density, 58f * density)
        val target = min(52f * density, w * 0.16f).coerceAtLeast(44f * density)
        val back = RectF(pad, 0f, pad + target, headerHeight)
        val close = RectF(w - pad - target, 0f, w - pad, headerHeight)
        val contentTop = headerHeight + gap
        val contentHeight = (h - contentTop - pad).coerceAtLeast(1f)
        val historyHeight = (contentHeight * 0.22f).coerceIn(48f * density, 76f * density)
        val history = RectF(pad, contentTop, w - pad, contentTop + historyHeight)
        val rowGap = gap * 0.75f
        val optionsTop = history.bottom + gap
        val optionsBottom = h - pad
        val actionWidth = min(98f * density, w * 0.27f)
        val optionsRight = (w - pad - actionWidth - gap).coerceAtLeast(pad + 1f)
        val rowHeight = ((optionsBottom - optionsTop - rowGap * 2f) / 3f).coerceAtLeast(1f)
        val gps = RectF(pad, optionsTop, optionsRight, optionsTop + rowHeight)
        val time = RectF(pad, gps.bottom + rowGap, optionsRight, gps.bottom + rowGap + rowHeight)
        val raw = RectF(pad, time.bottom + rowGap, optionsRight, optionsBottom)
        val toggleWidth = min(54f * density, gps.width() * 0.26f)
        val helpWidth = min(38f * density, raw.width() * 0.18f)
        fun toggle(row: RectF, reserveHelp: Boolean) = RectF(
            row.right - toggleWidth - if (reserveHelp) helpWidth else 0f,
            row.top,
            row.right - if (reserveHelp) helpWidth else 0f,
            row.bottom,
        )
        val actionLeft = optionsRight + gap
        val actionHeight = (optionsBottom - optionsTop).coerceAtLeast(1f)
        val startSize = minOf(82f * density, actionWidth, actionHeight * 0.48f)
        val startTop = optionsTop + (actionHeight - startSize) * 0.58f
        val start = RectF(
            actionLeft + (actionWidth - startSize) / 2f,
            startTop,
            actionLeft + (actionWidth + startSize) / 2f,
            startTop + startSize,
        )
        val finishWidth = min(50f * density, w * 0.16f)
        val finishHeight = min(34f * density, startSize * 0.48f)
        val finish = RectF(
            actionLeft + (actionWidth - finishWidth) / 2f,
            optionsTop + 5f * density,
            actionLeft + (actionWidth + finishWidth) / 2f,
            optionsTop + 5f * density + finishHeight,
        )
        return ParameterRecordToolGeometry(
            back,
            close,
            history,
            gps,
            time,
            raw,
            toggle(gps, false),
            toggle(time, false),
            toggle(raw, true),
            RectF(raw.right - helpWidth, raw.top, raw.right, raw.bottom),
            finish,
            start,
        )
    }
}
