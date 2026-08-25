package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("ViewConstructor")
internal class ColorTemperatureView(
    context: Context,
    private val state: MeterState,
) : View(context) {
    interface Listener {
        fun onBackToToolsRequested()
        fun onCloseRequested()
        fun onEstimateRequested()
    }

    var listener: Listener? = null
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.NORMAL)
        strokeCap = Paint.Cap.ROUND
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
    }
    private val red = Color.rgb(190, 28, 34)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var geometry = ColorTemperatureGeometry.EMPTY
    private var rawSupported = false
    private var measuring = false
    private var reading: ColorTemperatureReading? = null
    private var message: String? = null
    private var showingReference = false
    private var referenceScroll = 0f
    private var downY = 0f
    private var scrollAtDown = 0f
    private var dragging = false

    private val background: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val foreground: Int get() = if (state.isDarkMode) Color.rgb(218, 218, 214) else Color.rgb(24, 24, 24)
    private val muted: Int get() = if (state.isDarkMode) Color.rgb(128, 128, 124) else Color.rgb(132, 132, 128)
    private val pressedFill: Int get() = if (state.isDarkMode) Color.rgb(55, 55, 52) else Color.rgb(226, 226, 222)

    fun openPage(rawSupported: Boolean) {
        this.rawSupported = rawSupported
        if (!measuring) message = null
        showingReference = false
        referenceScroll = 0f
        invalidate()
    }

    fun updateRawSupport(supported: Boolean) {
        rawSupported = supported
        if (!supported) measuring = false
        invalidate()
    }

    fun setMeasuring(value: Boolean) {
        measuring = value
        if (value) message = null
        invalidate()
    }

    fun showReading(value: ColorTemperatureReading) {
        measuring = false
        reading = value
        message = null
        invalidate()
    }

    fun showError(value: String) {
        measuring = false
        message = value
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geometry = ColorTemperatureGeometryCalculator.calculate(w, h, density)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        if (showingReference) drawReference(canvas) else drawEstimator(canvas)
        drawHeader(canvas)
    }

    private fun drawHeader(canvas: Canvas) {
        boldPaint.color = foreground
        val title = localized(
            if (showingReference) "常见色温对照" else "白色温估算",
            if (showingReference) "Common color temperatures" else "Color temperature",
        )
        boldPaint.textSize = fittedTextSize(title, width - 100f * density, 17f, 11f)
        centeredText(canvas, title, width / 2f, geometry.headerHeight / 2f, boldPaint)
        paint.color = foreground
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * density
        val cy = geometry.headerHeight / 2f
        canvas.drawLine(22f * density, cy, 31f * density, cy - 8f * density, paint)
        canvas.drawLine(22f * density, cy, 31f * density, cy + 8f * density, paint)
        val cx = width - 24f * density
        canvas.drawLine(cx - 7f * density, cy - 7f * density, cx + 7f * density, cy + 7f * density, paint)
        canvas.drawLine(cx + 7f * density, cy - 7f * density, cx - 7f * density, cy + 7f * density, paint)
        paint.color = if (state.isDarkMode) Color.rgb(76, 76, 72) else Color.rgb(205, 205, 200)
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, geometry.headerHeight - density, width.toFloat(), geometry.headerHeight, paint)
    }

    private fun drawEstimator(canvas: Canvas) {
        val area = geometry.content
        boldPaint.color = if (rawSupported) foreground else muted
        boldPaint.textSize = (30f * density).coerceAtMost(area.height() * 0.3f)
        val resultText = reading?.let { "${it.kelvin} K" } ?: "— — K"
        centeredText(canvas, resultText, area.centerX(), area.top + area.height() * 0.28f, boldPaint)

        paint.color = foreground
        val instruction = localized("对准灰卡，将灰卡填满取景框中央区域", "Aim at a gray card and fill the center guide")
        paint.textSize = fittedTextSize(instruction, area.width(), 12f, 8.5f)
        centeredText(canvas, instruction, area.centerX(), area.top + area.height() * 0.58f, paint)
        val status = when {
            measuring -> localized("正在读取 RAW…", "Reading RAW…")
            !rawSupported -> localized("当前摄像头不支持 RAW，无法估算", "RAW is unavailable on this camera")
            message != null -> message!!
            reading != null -> confidenceText(reading!!.confidence)
            else -> localized("使用中央区域的 RAW 数据与传感器色彩矩阵估算", "Uses center RAW data and sensor color matrices")
        }
        paint.color = if (message != null || !rawSupported) red else muted
        paint.textSize = fittedTextSize(status, area.width(), 10.5f, 7.5f)
        centeredText(canvas, status, area.centerX(), area.top + area.height() * 0.78f, paint)

        drawButton(
            canvas,
            geometry.estimate,
            when {
                measuring -> localized("估算中", "Estimating")
                reading != null -> localized("重新估算", "Measure again")
                else -> localized("拍摄灰卡并估算", "Capture gray card")
            },
            enabled = rawSupported && !measuring,
            emphasized = true,
        )
        drawButton(
            canvas,
            geometry.reference,
            localized("常见对照表", "Common reference"),
            enabled = true,
            emphasized = false,
        )
    }

    private fun drawReference(canvas: Canvas) {
        val rowHeight = 46f * density
        val clip = RectF(0f, geometry.headerHeight, width.toFloat(), height.toFloat())
        canvas.save()
        canvas.clipRect(clip)
        ColorTemperatureReference.entries.forEachIndexed { index, entry ->
            val top = geometry.headerHeight + index * rowHeight - referenceScroll
            if (top + rowHeight < geometry.headerHeight || top > height) return@forEachIndexed
            paint.color = if (index % 2 == 0) {
                if (state.isDarkMode) Color.rgb(24, 24, 22) else Color.rgb(246, 246, 243)
            } else {
                background
            }
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, top, width.toFloat(), top + rowHeight, paint)
            boldPaint.color = foreground
            val label = if (state.menuLanguage == MenuLanguage.ENGLISH) entry.english else entry.chinese
            boldPaint.textSize = fittedTextSize(label, width * 0.52f, 12f, 8f)
            canvas.drawText(label, 14f * density, top + rowHeight * 0.58f, boldPaint)
            paint.color = red
            paint.textSize = fittedTextSize(entry.kelvin, width * 0.38f, 11f, 8f)
            canvas.drawText(entry.kelvin, width - 14f * density - paint.measureText(entry.kelvin), top + rowHeight * 0.58f, paint)
        }
        canvas.restore()
    }

    private fun drawButton(canvas: Canvas, rect: RectF, label: String, enabled: Boolean, emphasized: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (measuring && emphasized) pressedFill else background
        canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f * density
        paint.color = when {
            !enabled -> muted
            emphasized -> red
            else -> foreground
        }
        canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
        boldPaint.color = paint.color
        boldPaint.textSize = fittedTextSize(label, rect.width() - 12f * density, 11.5f, 7.5f)
        centeredText(canvas, label, rect.centerX(), rect.centerY(), boldPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                scrollAtDown = referenceScroll
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (showingReference && abs(event.y - downY) > touchSlop) {
                    dragging = true
                    val maxScroll = max(0f, ColorTemperatureReference.entries.size * 46f * density - (height - geometry.headerHeight))
                    referenceScroll = (scrollAtDown - (event.y - downY)).coerceIn(0f, maxScroll)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    performClick()
                    when {
                        geometry.close.contains(event.x, event.y) -> listener?.onCloseRequested()
                        geometry.back.contains(event.x, event.y) && showingReference -> {
                            showingReference = false
                            invalidate()
                        }
                        geometry.back.contains(event.x, event.y) -> listener?.onBackToToolsRequested()
                        !showingReference && geometry.estimate.contains(event.x, event.y) && rawSupported && !measuring -> {
                            setMeasuring(true)
                            listener?.onEstimateRequested()
                        }
                        !showingReference && geometry.reference.contains(event.x, event.y) -> {
                            showingReference = true
                            referenceScroll = 0f
                            invalidate()
                        }
                    }
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun confidenceText(confidence: ColorTemperatureConfidence): String = when (confidence) {
        ColorTemperatureConfidence.HIGH -> localized("估算可信度：高", "Estimate confidence: high")
        ColorTemperatureConfidence.MEDIUM -> localized("估算可信度：中", "Estimate confidence: medium")
        ColorTemperatureConfidence.LOW -> localized("估算可信度：低，请确认灰卡填满中央区域", "Low confidence; check the gray card")
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private fun centeredText(canvas: Canvas, text: String, x: Float, y: Float, target: Paint) {
        canvas.drawText(text, x - target.measureText(text) / 2f, y - (target.ascent() + target.descent()) / 2f, target)
    }

    private fun fittedTextSize(
        text: String,
        maximumWidth: Float,
        preferredSp: Float,
        minimumSp: Float,
    ): Float {
        var size = preferredSp * density
        paint.textSize = size
        while (size > minimumSp * density && paint.measureText(text) > maximumWidth) {
            size -= 0.5f * density
            paint.textSize = size
        }
        return size
    }
}
