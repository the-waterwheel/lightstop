package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Presentation only: callers retain their existing layout and touch geometry. */
internal class ToolChromeRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = InstrumentStyle.labelTypeface
    }
    private val inset = RectF()

    fun surface(
        canvas: Canvas,
        bounds: RectF,
        density: Float,
        dark: Boolean,
        fill: Int = InstrumentStyle.control(dark),
        border: Int = InstrumentStyle.border(dark),
    ) {
        if (bounds.isEmpty) return
        val stroke = density.coerceAtLeast(1f)
        inset.set(bounds)
        inset.inset(stroke / 2f, stroke / 2f)
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(inset, 8f * density, 8f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = border
        canvas.drawRoundRect(inset, 8f * density, 8f * density, paint)
    }

    fun header(
        canvas: Canvas,
        width: Float,
        back: RectF,
        close: RectF,
        title: String,
        density: Float,
        textDensity: Float,
        dark: Boolean,
    ) {
        val y = back.centerY()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * density
        paint.color = InstrumentStyle.foreground(dark)
        canvas.drawLine(back.centerX() + 5f * density, y - 8f * density, back.centerX() - 4f * density, y, paint)
        canvas.drawLine(back.centerX() - 4f * density, y, back.centerX() + 5f * density, y + 8f * density, paint)
        val x = close.centerX()
        canvas.drawLine(x - 7f * density, y - 7f * density, x + 7f * density, y + 7f * density, paint)
        canvas.drawLine(x + 7f * density, y - 7f * density, x - 7f * density, y + 7f * density, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 14f * textDensity
        paint.textAlign = Paint.Align.CENTER
        val available = (minOf(width / 2f - back.right, close.left - width / 2f) * 2f - 8f * density).coerceAtLeast(1f)
        val measured = paint.measureText(title)
        if (measured > available) paint.textSize *= available / measured
        canvas.drawText(title, width / 2f, y - (paint.ascent() + paint.descent()) / 2f, paint)
        paint.color = InstrumentStyle.border(dark)
        canvas.drawRect(0f, back.bottom - density * 0.75f, width, back.bottom, paint)
    }
}
