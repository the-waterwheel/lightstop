package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** Shared passive EV100 readout rendered beside Normal metering and Zone marking actions. */
internal class Ev100BadgeRenderer(
    private val density: Float,
    private val scaledDensity: Float,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    fun draw(
        canvas: Canvas,
        rect: RectF,
        readout: Ev100Readout,
        darkMode: Boolean,
        red: Int,
    ) {
        if (rect.isEmpty) return
        val foreground = if (darkMode) Color.rgb(218, 218, 214) else Color.rgb(22, 22, 22)
        val surface = if (darkMode) Color.rgb(32, 32, 30) else Color.WHITE
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f * density
        paint.color = foreground
        canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)

        bold.textAlign = Paint.Align.CENTER
        bold.color = foreground
        bold.textSize = 7.5f * scaledDensity
        centered(canvas, "EV100", rect.centerX(), rect.top + rect.height() * 0.31f, bold)
        bold.color = if (readout.pending) red else foreground
        bold.textSize = 11.5f * scaledDensity
        val text = when {
            readout.pending -> "…"
            readout.value != null -> "%.2f".format(java.util.Locale.US, readout.value)
            else -> "--"
        }
        centered(canvas, text, rect.centerX(), rect.top + rect.height() * 0.70f, bold)
    }

    fun drawFlashIndicator(
        canvas: Canvas,
        evRect: RectF,
        configuration: FlashConfiguration?,
        darkMode: Boolean,
        blue: Int,
    ) {
        val flash = configuration ?: return
        if (evRect.isEmpty) return
        val width = 42f * density
        val height = 22f * density
        val top = evRect.bottom + 4f * density
        val left = (evRect.centerX() - width / 2f).coerceIn(
            2f * density,
            (canvas.width - width - 2f * density).coerceAtLeast(2f * density),
        )
        val rect = RectF(left, top, left + width, top + height)
        val foreground = if (darkMode) Color.rgb(218, 218, 214) else Color.rgb(22, 22, 22)
        val surface = if (darkMode) Color.rgb(28, 38, 49) else Color.rgb(229, 241, 252)
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = blue
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
        bold.textAlign = Paint.Align.CENTER
        bold.color = foreground
        bold.textSize = 8.5f * scaledDensity
        val gn = if (flash.guideNumber % 1.0 == 0.0) {
            flash.guideNumber.toInt().toString()
        } else {
            "%.1f".format(java.util.Locale.US, flash.guideNumber)
        }
        centered(canvas, "GN$gn", rect.centerX(), rect.centerY(), bold)
    }

    private fun centered(canvas: Canvas, text: String, x: Float, y: Float, textPaint: Paint) {
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }
}
