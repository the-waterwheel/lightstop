package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min

/** Draws a reciprocity result as separate numeric columns so unit labels remain aligned. */
internal class ReciprocityTimeRenderer(
    private val density: Float,
    private val scaledDensity: Float,
) {
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    fun draw(canvas: Canvas, rect: RectF, readout: ReciprocityTimeReadout, foreground: Int, secondary: Int) {
        numberPaint.color = foreground
        val valueY = rect.centerY() - if (readout.units.isEmpty()) 0f else 6f * density
        if (readout.reciprocal != null) {
            numberPaint.textSize = min(32f * scaledDensity, rect.height() * 0.48f)
            fittedTextSize(numberPaint, readout.text, rect.width() - 10f * density, 16f * scaledDensity)
            centered(canvas, readout.text, rect.centerX(), valueY, numberPaint)
            return
        }
        val columns = readout.fields.size
        val fullAvailable = (rect.width() - 12f * density).coerceAtLeast(1f)
        // A two-field mm:ss readout is easier to scan as one compact time value. Three-field
        // hh:mm:ss keeps the wider layout because it needs the additional numeric column.
        val available = if (columns == 2) {
            min(fullAvailable, 160f * density)
        } else {
            fullAvailable
        }
        val contentLeft = rect.centerX() - available / 2f
        val cellWidth = available / columns
        numberPaint.textSize = min(if (columns == 3) 30f else 32f, rect.height() * 0.40f / scaledDensity) * scaledDensity
        while (numberPaint.textSize > 18f * scaledDensity &&
            readout.fields.any { numberPaint.measureText(it) > cellWidth * 0.88f }
        ) numberPaint.textSize -= scaledDensity
        readout.fields.forEachIndexed { index, value ->
            val x = contentLeft + cellWidth * (index + 0.5f)
            centered(canvas, value, x, valueY, numberPaint)
            if (index < columns - 1) {
                numberPaint.textSize *= 0.82f
                centered(canvas, ":", x + cellWidth / 2f, valueY, numberPaint)
                numberPaint.textSize /= 0.82f
            }
        }
        unitPaint.color = secondary
        unitPaint.textSize = 10f * scaledDensity
        val unitY = valueY + numberPaint.textSize * 0.66f + 10f * density
        readout.units.forEachIndexed { index, unit ->
            val x = contentLeft + cellWidth * (index + 0.5f)
            centered(canvas, unit, x, unitY, unitPaint)
        }
    }

    private fun fittedTextSize(paint: Paint, text: String, width: Float, minimum: Float) {
        while (paint.textSize > minimum && paint.measureText(text) > width) paint.textSize -= scaledDensity
    }

    private fun centered(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        val metrics = paint.fontMetrics
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, paint)
    }
}
