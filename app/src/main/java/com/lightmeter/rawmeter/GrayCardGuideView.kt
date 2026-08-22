package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

@SuppressLint("ViewConstructor")
internal class GrayCardGuideView(
    context: Context,
    private val state: MeterState,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.SQUARE
        typeface = Typeface.create("sans", Typeface.BOLD)
    }
    private var previewFrame = RectF()
    private val guideRect = RectF()
    private val labelRect = RectF()
    private var guideActive = false

    init {
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    fun setGuide(previewFrame: RectF, active: Boolean) {
        this.previewFrame = RectF(previewFrame)
        guideActive = active
        visibility = if (active) VISIBLE else GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!guideActive || previewFrame.isEmpty) return
        val size = min(previewFrame.width(), previewFrame.height()) * 0.3f
        guideRect.set(
            previewFrame.centerX() - size / 2f,
            previewFrame.centerY() - size / 2f,
            previewFrame.centerX() + size / 2f,
            previewFrame.centerY() + size / 2f,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = Color.argb(190, 255, 255, 255)
        canvas.drawRect(guideRect, paint)
        paint.strokeWidth = 3f * density
        paint.color = Color.rgb(200, 35, 35)
        val corner = size * 0.18f
        drawCorner(canvas, guideRect.left, guideRect.top, corner, 1f, 1f)
        drawCorner(canvas, guideRect.right, guideRect.top, corner, -1f, 1f)
        drawCorner(canvas, guideRect.left, guideRect.bottom, corner, 1f, -1f)
        drawCorner(canvas, guideRect.right, guideRect.bottom, corner, -1f, -1f)

        val label = if (state.menuLanguage == MenuLanguage.ENGLISH) {
            "Place the gray card inside the frame"
        } else {
            "将灰卡放入框内"
        }
        paint.style = Paint.Style.FILL
        paint.textSize = 12f * density
        val textWidth = paint.measureText(label)
        val labelHeight = 28f * density
        labelRect.set(
            previewFrame.centerX() - textWidth / 2f - 10f * density,
            guideRect.top - labelHeight - 9f * density,
            previewFrame.centerX() + textWidth / 2f + 10f * density,
            guideRect.top - 9f * density,
        )
        paint.color = Color.argb(185, 0, 0, 0)
        canvas.drawRoundRect(labelRect, 5f * density, 5f * density, paint)
        paint.color = Color.WHITE
        val baseline = labelRect.centerY() - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(label, labelRect.centerX() - textWidth / 2f, baseline, paint)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, length: Float, dx: Float, dy: Float) {
        canvas.drawLine(x, y, x + length * dx, y, paint)
        canvas.drawLine(x, y, x, y + length * dy, paint)
    }
}
