package com.lightmeter.rawmeter

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal data class ParameterRecordEditorGeometry(
    val close: RectF,
    val film: RectF,
    val filmEdit: RectF,
    val addNote: RectF,
    val notes: RectF,
    val aperture: RectF,
    val shutter: RectF,
    val ei: RectF,
    val save: RectF,
) {
    companion object {
        val EMPTY = ParameterRecordEditorGeometry(
            RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(),
        )
    }
}

internal object ParameterRecordEditorGeometryCalculator {
    fun calculate(width: Int, height: Int, density: Float): ParameterRecordEditorGeometry {
        if (width <= 0 || height <= 0) return ParameterRecordEditorGeometry.EMPTY
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = max(8f * density, min(w, h) * 0.018f)
        val gap = max(5f * density, min(w, h) * 0.010f)
        val header = (h * 0.10f).coerceIn(40f * density, 52f * density)
        val close = RectF(w - pad - 46f * density, 0f, w - pad, header)
        val filmHeight = (h * 0.13f).coerceIn(42f * density, 58f * density)
        val film = RectF(pad, header + gap, w - pad, header + gap + filmHeight)
        val editWidth = min(70f * density, film.width() * 0.24f)
        val filmEdit = RectF(film.right - editWidth, film.top, film.right, film.bottom)
        val notesTop = film.bottom + gap
        val controlsHeight = (h * 0.27f).coerceIn(86f * density, 142f * density)
        val saveHeight = (h * 0.12f).coerceIn(42f * density, 58f * density)
        val save = RectF(pad, h - pad - saveHeight, w - pad, h - pad)
        val controlsBottom = save.top - gap
        val controlsTop = controlsBottom - controlsHeight
        val addWidth = min(52f * density, w * 0.16f)
        val addNote = RectF(pad, notesTop, pad + addWidth, controlsTop - gap)
        val notes = RectF(addNote.right + gap, notesTop, w - pad, controlsTop - gap)
        val columnGap = gap
        val columnWidth = (w - pad * 2f - columnGap * 2f) / 3f
        val aperture = RectF(pad, controlsTop, pad + columnWidth, controlsBottom)
        val shutter = RectF(aperture.right + columnGap, controlsTop, aperture.right + columnGap + columnWidth, controlsBottom)
        val ei = RectF(shutter.right + columnGap, controlsTop, w - pad, controlsBottom)
        return ParameterRecordEditorGeometry(close, film, filmEdit, addNote, notes, aperture, shutter, ei, save)
    }
}
