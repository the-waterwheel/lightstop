package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Reusable, canvas-native vertical value strip with adjacent detents kept visible. */
internal object VerticalDetentStripRenderer {
    fun draw(
        canvas: Canvas,
        bounds: RectF,
        title: String,
        labels: List<String>,
        position: Float,
        density: Float,
        scaledDensity: Float,
        background: Int,
        foreground: Int,
        muted: Int,
        accent: Int,
        paint: Paint,
        boldPaint: Paint,
    ) {
        if (bounds.isEmpty || labels.isEmpty()) return
        val titleHeight = 22f * density
        val viewport = RectF(bounds.left, bounds.top + titleHeight, bounds.right, bounds.bottom)
        val spacing = (viewport.height() * 0.28f).coerceIn(20f * density, 30f * density)

        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = accent
        boldPaint.textSize = 9f * scaledDensity
        drawCentered(canvas, title, bounds.centerX(), bounds.top + titleHeight * 0.52f, boldPaint)

        canvas.save()
        canvas.clipRect(viewport)
        val first = floor(position - viewport.height() / spacing / 2f).toInt() - 1
        val last = ceil(position + viewport.height() / spacing / 2f).toInt() + 1
        for (index in first..last) {
            val label = labels.getOrNull(index) ?: continue
            val distance = abs(index - position)
            if (distance > 2.6f) continue
            val y = viewport.centerY() + (index - position) * spacing
            val selected = distance < 0.5f
            paint.style = Paint.Style.FILL
            paint.color = if (selected) foreground else muted
            paint.alpha = if (selected) 255 else (205 - distance * 42f).toInt().coerceIn(70, 180)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = (if (selected) 11f else 8.5f) * scaledDensity
            drawCentered(canvas, label, bounds.centerX(), y, paint)
        }
        paint.alpha = 255

        val selectionHeight = spacing * 0.82f
        paint.style = Paint.Style.FILL
        paint.color = background
        paint.alpha = 230
        canvas.drawRoundRect(
            RectF(bounds.left + 4f * density, viewport.centerY() - selectionHeight / 2f,
                bounds.right - 4f * density, viewport.centerY() + selectionHeight / 2f),
            4f * density,
            4f * density,
            paint,
        )
        paint.alpha = 255
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f * density
        paint.color = accent
        canvas.drawLine(bounds.left + 8f * density, viewport.centerY(), bounds.left + 16f * density, viewport.centerY(), paint)
        canvas.drawLine(bounds.right - 16f * density, viewport.centerY(), bounds.right - 8f * density, viewport.centerY(), paint)

        val selectedIndex = position.roundToInt().coerceIn(0, labels.lastIndex)
        boldPaint.color = foreground
        boldPaint.textSize = 11f * scaledDensity
        drawCentered(canvas, labels[selectedIndex], bounds.centerX(), viewport.centerY(), boldPaint)
        canvas.restore()
    }

    private fun drawCentered(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        val metrics = paint.fontMetrics
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, paint)
    }
}
