package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Shared zoom finish; callers supply the original gesture endpoints unchanged. */
internal class ZoomControlRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val thumb = RectF()

    fun draw(canvas: Canvas, vertical: Boolean, cross: Float, start: Float, end: Float,
        position: Float, density: Float, dark: Boolean) {
        fun line(a: Float, b: Float, offset: Float = 0f) {
            if (vertical) canvas.drawLine(cross + offset, a, cross + offset, b, paint)
            else canvas.drawLine(a, cross + offset, b, cross + offset, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = InstrumentStyle.panel(dark)
        line(start, end)
        paint.strokeWidth = 1.2f * density
        paint.color = InstrumentStyle.secondary(dark)
        line(if (vertical) position else start, if (vertical) end else position)
        paint.strokeWidth = 0.8f * density
        paint.color = InstrumentStyle.border(dark)
        for (index in 0..8) {
            val value = start + (end - start) * index / 8f
            val tick = (if (index % 4 == 0) 4f else 2f) * density
            if (vertical) canvas.drawLine(cross + 6f * density, value, cross + (6f * density) + tick, value, paint)
            else canvas.drawLine(value, cross + 6f * density, value, cross + (6f * density) + tick, paint)
        }
        val x = if (vertical) cross else position
        val y = if (vertical) position else cross
        val halfW = (if (vertical) 7f else 10f) * density
        val halfH = (if (vertical) 10f else 7f) * density
        thumb.set(x - halfW, y - halfH, x + halfW, y + halfH)
        paint.style = Paint.Style.FILL
        paint.color = InstrumentStyle.control(dark)
        canvas.drawRoundRect(thumb, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = InstrumentStyle.secondary(dark)
        canvas.drawRoundRect(thumb, 4f * density, 4f * density, paint)
        paint.strokeWidth = 1.6f * density
        paint.color = InstrumentStyle.red
        if (vertical) canvas.drawLine(x - 3f * density, y, x + 3f * density, y, paint)
        else canvas.drawLine(x, y - 3f * density, x, y + 3f * density, paint)
    }
}
