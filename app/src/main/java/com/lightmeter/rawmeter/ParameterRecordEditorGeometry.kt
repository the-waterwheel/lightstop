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
    val preview: RectF,
    val status: RectF,
    val undo: RectF,
) {
    companion object {
        val EMPTY = ParameterRecordEditorGeometry(
            RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(), RectF(),
        )
    }
}

internal object ParameterRecordEditorGeometryCalculator {
    fun calculate(width: Int, height: Int, density: Float): ParameterRecordEditorGeometry {
        if (width <= 0 || height <= 0) return ParameterRecordEditorGeometry.EMPTY
        val density = AdaptiveLayout.density(width, height, density, LayoutProfile.EDITOR)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = max(8f * density, min(w, h) * 0.018f)
        val gap = max(5f * density, min(w, h) * 0.010f)
        val header = (h * 0.10f).coerceIn(40f * density, 52f * density)
        val close = RectF(w - pad - 46f * density, 0f, w - pad, header)
        val landscape = w > h
        val previewFraction = if (h / density < 520f) .15f else .28f
        val preview = if (landscape) RectF(pad, header + gap, w * .46f - gap, h - pad - 38f * density)
            else RectF(pad, header + gap, w - pad, header + gap + h * previewFraction)
        val status = RectF(preview.left, preview.bottom + gap, preview.right, preview.bottom + gap + 28f * density)
        val undo = RectF(status.right - 64f * density, status.top, status.right, status.bottom)
        val formLeft = if (landscape) w * .46f + gap else pad
        val formTop = if (landscape) header + gap else status.bottom + gap
        val filmHeight = (h * 0.075f).coerceIn(36f * density, 54f * density)
        val film = RectF(formLeft, formTop, w - pad, formTop + filmHeight)
        val editWidth = min(70f * density, film.width() * 0.24f)
        val filmEdit = RectF(film.right - editWidth, film.top, film.right, film.bottom)
        val notesTop = film.bottom + gap
        val controlsHeight = (h * 0.17f).coerceIn(70f * density, 132f * density)
        val saveHeight = (h * 0.12f).coerceIn(42f * density, 58f * density)
        val save = RectF(formLeft, h - pad - saveHeight, w - pad, h - pad)
        val controlsBottom = save.top - gap
        val controlsTop = controlsBottom - controlsHeight
        val addNote = RectF(formLeft, notesTop, w - pad, notesTop + min(32f * density, (controlsTop - gap - notesTop) * .35f))
        val notes = RectF(formLeft, addNote.bottom + gap, w - pad, controlsTop - gap)
        val columnGap = gap
        val columnWidth = (w - pad - formLeft - columnGap * 2f) / 3f
        val aperture = RectF(formLeft, controlsTop, formLeft + columnWidth, controlsBottom)
        val shutter = RectF(aperture.right + columnGap, controlsTop, aperture.right + columnGap + columnWidth, controlsBottom)
        val ei = RectF(shutter.right + columnGap, controlsTop, w - pad, controlsBottom)
        return ParameterRecordEditorGeometry(close, film, filmEdit, addNote, notes, aperture, shutter, ei, save, preview, status, undo)
    }
}
