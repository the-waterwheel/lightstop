package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max

class SettingsView(
    context: Context,
    private val state: MeterState,
) : View(context) {

    interface Listener {
        fun onCloseRequested()
        fun onSettingChanged(key: SettingKey)
    }

    private data class OptionHitTarget(
        val item: SettingItemSpec,
        val option: SettingOptionSpec,
        val rect: RectF,
    )

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
    }

    private var selectedSectionIndex = 0
    private var closeRect = RectF()
    private var tabRects: List<RectF> = emptyList()
    private var optionTargets: List<OptionHitTarget> = emptyList()
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var scrollOffset = 0f
    private var maxScrollOffset = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val background = if (state.isDarkMode) Color.BLACK else Color.WHITE
        val foreground = if (state.isDarkMode) Color.WHITE else Color.rgb(20, 20, 20)
        val navGray = if (state.isDarkMode) Color.rgb(102, 102, 100) else Color.rgb(226, 226, 223)
        val divider = if (state.isDarkMode) Color.rgb(72, 72, 70) else Color.rgb(205, 205, 201)
        val red = Color.rgb(166, 27, 36)

        canvas.drawColor(background)
        val headerHeight = minOf(height * 0.15f, 76f * density).coerceAtLeast(58f * density)
        paint.style = Paint.Style.FILL
        paint.color = navGray
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, paint)

        val closeWidth = 48f * density
        closeRect = RectF(width - closeWidth, 0f, width.toFloat(), headerHeight)
        val tabsWidth = closeRect.left
        val sections = SettingsCatalog.sections
        val tabWidth = tabsWidth / sections.size
        tabRects = sections.indices.map { index ->
            RectF(index * tabWidth, 0f, (index + 1) * tabWidth, headerHeight)
        }
        sections.forEachIndexed { index, section ->
            val rect = tabRects[index]
            val selected = index == selectedSectionIndex
            if (selected) {
                paint.style = Paint.Style.FILL
                paint.color = foreground
                canvas.drawRect(rect, paint)
            }
            boldPaint.textSize = 12f * density
            boldPaint.color = when {
                !section.enabled -> Color.rgb(145, 145, 142)
                selected -> background
                else -> foreground
            }
            drawCenteredText(
                canvas,
                section.label.resolve(state.menuLanguage),
                rect.centerX(),
                rect.centerY(),
                boldPaint,
            )
            if (selected) {
                paint.style = Paint.Style.FILL
                paint.color = red
                canvas.drawRect(
                    rect.left + 12f * density,
                    rect.bottom - 2.2f * density,
                    rect.right - 12f * density,
                    rect.bottom,
                    paint,
                )
            }
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = foreground
        val closeInset = 15f * density
        canvas.drawLine(
            closeRect.left + closeInset,
            closeRect.centerY() - closeInset * 0.55f,
            closeRect.right - closeInset,
            closeRect.centerY() + closeInset * 0.55f,
            paint,
        )
        canvas.drawLine(
            closeRect.left + closeInset,
            closeRect.centerY() + closeInset * 0.55f,
            closeRect.right - closeInset,
            closeRect.centerY() - closeInset * 0.55f,
            paint,
        )

        val section = sections[selectedSectionIndex]
        val topPadding = 14f * density
        val bottomPadding = 18f * density
        val rowGap = 10f * density
        val rowHeight = if (width > height) 74f * density else 84f * density
        val contentHeight = topPadding +
            section.items.size * rowHeight +
            max(0, section.items.size - 1) * rowGap +
            bottomPadding
        maxScrollOffset = max(0f, contentHeight - (height - headerHeight))
        scrollOffset = scrollOffset.coerceIn(0f, maxScrollOffset)

        canvas.save()
        canvas.clipRect(0f, headerHeight, width.toFloat(), height.toFloat())
        var y = headerHeight + topPadding - scrollOffset
        val newTargets = mutableListOf<OptionHitTarget>()
        section.items.forEach { item ->
            val row = RectF(10f * density, y, width - 10f * density, y + rowHeight)
            paint.style = Paint.Style.FILL
            paint.color = background
            canvas.drawRect(row, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = divider
            canvas.drawRect(row, paint)

            boldPaint.textSize = 11f * density
            boldPaint.color = foreground
            drawTextAtBaseline(
                canvas,
                item.label.resolve(state.menuLanguage),
                row.left + 12f * density,
                row.top + 23f * density,
                boldPaint,
            )

            val optionTop = row.bottom - 36f * density
            val optionBottom = row.bottom - 8f * density
            val optionLeft = row.left + 10f * density
            val optionRight = row.right - 10f * density
            val optionGap = 5f * density
            val optionWidth =
                (optionRight - optionLeft - optionGap * (item.options.size - 1)) /
                    item.options.size
            item.options.forEachIndexed { index, option ->
                val left = optionLeft + index * (optionWidth + optionGap)
                val rect = RectF(left, optionTop, left + optionWidth, optionBottom)
                val selected = state.settingValue(item.key) == option.value
                paint.style = Paint.Style.FILL
                paint.color = if (selected) foreground else background
                canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = if (selected) 1.4f * density else 0.9f * density
                paint.color = if (selected) red else foreground
                canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
                paint.style = Paint.Style.FILL
                paint.color = if (selected) background else foreground
                paint.textSize = 9f * density
                paint.typeface = Typeface.DEFAULT_BOLD
                drawCenteredText(
                    canvas,
                    option.label.resolve(state.menuLanguage),
                    rect.centerX(),
                    rect.centerY(),
                    paint,
                )
                newTargets += OptionHitTarget(item, option, rect)
            }
            y += rowHeight + rowGap
        }
        optionTargets = newTargets
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastY = event.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = event.y - lastY
                if (abs(event.y - downY) > touchSlop) dragging = true
                if (dragging && maxScrollOffset > 0f) {
                    scrollOffset = (scrollOffset - delta).coerceIn(0f, maxScrollOffset)
                    invalidate()
                }
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && abs(event.x - downX) <= touchSlop * 2f) {
                    handleTap(event.x, event.y)
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        if (closeRect.contains(x, y)) {
            haptic()
            listener?.onCloseRequested()
            return
        }
        val tabIndex = tabRects.indexOfFirst { it.contains(x, y) }
        if (tabIndex >= 0) {
            val section = SettingsCatalog.sections[tabIndex]
            if (section.enabled && tabIndex != selectedSectionIndex) {
                selectedSectionIndex = tabIndex
                scrollOffset = 0f
                haptic()
                invalidate()
            }
            return
        }
        val target = optionTargets.firstOrNull { it.rect.contains(x, y) } ?: return
        if (state.settingValue(target.item.key) == target.option.value) return
        state.updateSetting(target.item.key, target.option.value)
        haptic()
        listener?.onSettingChanged(target.item.key)
        invalidate()
    }

    private fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        textPaint: Paint,
    ) {
        val metrics = textPaint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, centerX - textPaint.measureText(text) / 2f, baseline, textPaint)
    }

    private fun drawTextAtBaseline(
        canvas: Canvas,
        text: String,
        left: Float,
        baseline: Float,
        textPaint: Paint,
    ) {
        canvas.drawText(text, left, baseline, textPaint)
    }
}
