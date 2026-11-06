package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** Persistent feedback inside the viewfinder; does not intercept any camera controls. */
internal class ExposurePreviewLimitBanner {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()

    fun draw(canvas: Canvas, frame: RectF, density: Float, limit: ExposurePreviewLimit, language: MenuLanguage) {
        if (frame.width() <= 0f || frame.height() <= 0f) return
        val d = minOf(density, frame.width() / 220f, frame.height() / 80f)
        bounds.set(frame.left + 8 * d, frame.top + 8 * d, frame.right - 8 * d, frame.top + 48 * d)
        paint.color = Color.argb(220, 20, 20, 20)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(bounds, 6 * d, 6 * d, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        fun line(text: String, centerY: Float, bold: Boolean) {
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textSize = 10.5f * d
            val width = paint.measureText(text)
            if (width > bounds.width() - 12 * d) paint.textSize *= (bounds.width() - 12 * d) / width
            canvas.drawText(text, bounds.centerX(), centerY - (paint.ascent() + paint.descent()) / 2, paint)
        }
        line(limit.title(language), bounds.top + 12 * d, true)
        line(limit.detail(language), bounds.top + 28 * d, false)
    }
}
