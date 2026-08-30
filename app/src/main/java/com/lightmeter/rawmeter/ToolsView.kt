package com.lightmeter.rawmeter

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Scrollable Tools ("小工具") panel that replaces the parameter area under the viewfinder.
 *
 * The panel only lays out over the parameter region; the viewfinder below it keeps receiving
 * touches. Tools are rendered from [ToolsCatalog] as a three-column grid of gray placeholder
 * cells. Tool pages are not implemented yet: taps dispatch through [Listener] so each tool can
 * grow its own page without touching this framework.
 */
@SuppressLint("ViewConstructor")
class ToolsView(
    context: Context,
    private val state: MeterState,
) : View(context) {

    interface Listener {
        fun onCloseRequested()
        fun onToolRequested(spec: ToolSpec)
    }

    var listener: Listener? = null

    private enum class TouchTarget { CLOSE, TOOL, NONE }

    private val density = resources.displayMetrics.density
    private val scaledDensity = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        1f,
        resources.displayMetrics,
    )
    private val lightBlack = Color.rgb(20, 20, 20)
    private val nightForeground = Color.rgb(210, 210, 206)
    private val background: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val foreground: Int get() = if (state.isDarkMode) nightForeground else lightBlack
    private val dividerColor: Int
        get() = if (state.isDarkMode) Color.rgb(84, 84, 80) else Color.rgb(190, 190, 186)
    private val cellColor: Int
        get() = if (state.isDarkMode) Color.rgb(58, 58, 56) else Color.rgb(214, 214, 210)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var scrollOffset = 0f
    private var touchTarget = TouchTarget.NONE
    private var touchTool: ToolSpec? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var dragStartOffset = 0f
    private var dragging = false

    private data class Grid(
        val columns: Int,
        val pad: Float,
        val gap: Float,
        val headerHeight: Float,
        val cellSize: Float,
        val contentTop: Float,
        val maxScroll: Float,
    )

    private fun grid(): Grid {
        val pad = 10f * density
        val gap = 8f * density
        val headerHeight = min(76f * density, height * 0.15f).coerceAtLeast(58f * density)
        val columns = 3
        val cellSize = (width - pad * 2f - gap * (columns - 1)) / columns
        val rows = ceil(ToolsCatalog.tools.size.toFloat() / columns).toInt()
        val contentTop = headerHeight + gap
        val contentBottom = contentTop + rows * (cellSize + gap) + pad
        return Grid(
            columns = columns,
            pad = pad,
            gap = gap,
            headerHeight = headerHeight,
            cellSize = cellSize,
            contentTop = contentTop,
            maxScroll = max(0f, contentBottom - height),
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        val g = grid()
        val title = if (state.menuLanguage == MenuLanguage.ENGLISH) "Tools" else "小工具"
        boldPaint.color = foreground
        boldPaint.textSize = 13f * density
        val titleBaseline = g.headerHeight * 0.5f - (boldPaint.ascent() + boldPaint.descent()) / 2f
        canvas.drawText(title, g.pad, titleBaseline, boldPaint)

        val closeSize = 48f * density
        val closeRect = RectF(
            width - g.pad - closeSize,
            (g.headerHeight - closeSize) / 2f,
            width - g.pad,
            (g.headerHeight + closeSize) / 2f,
        )
        paint.color = dividerColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        canvas.drawLine(
            closeRect.centerX() - 7f * density,
            closeRect.centerY() - 7f * density,
            closeRect.centerX() + 7f * density,
            closeRect.centerY() + 7f * density,
            paint,
        )
        canvas.drawLine(
            closeRect.centerX() + 7f * density,
            closeRect.centerY() - 7f * density,
            closeRect.centerX() - 7f * density,
            closeRect.centerY() + 7f * density,
            paint,
        )

        canvas.save()
        canvas.clipRect(0f, g.headerHeight, width.toFloat(), height.toFloat())
        ToolsCatalog.tools.forEachIndexed { index, spec ->
            val row = index / g.columns
            val column = index % g.columns
            val left = g.pad + column * (g.cellSize + g.gap)
            val top = g.contentTop + row * (g.cellSize + g.gap) - scrollOffset
            val cell = RectF(left, top, left + g.cellSize, top + g.cellSize)
            if (!RectF.intersects(cell, RectF(0f, g.headerHeight, width.toFloat(), height.toFloat()))) {
                return@forEachIndexed
            }
            paint.style = Paint.Style.FILL
            paint.color = cellColor
            canvas.drawRoundRect(cell, 6f * density, 6f * density, paint)
            boldPaint.color = foreground
            boldPaint.textSize = 11f * scaledDensity
            val label = spec.label.resolve(state.menuLanguage)
            val labelWidth = boldPaint.measureText(label)
            val lines = if (labelWidth > cell.width() - 12f * density) 2 else 1
            drawCellLabel(canvas, label, cell, lines)
        }
        canvas.restore()
        paint.style = Paint.Style.FILL
        paint.color = dividerColor
        canvas.drawRect(0f, g.headerHeight - 0.8f * density, width.toFloat(), g.headerHeight, paint)
    }

    private fun drawCellLabel(canvas: Canvas, label: String, cell: RectF, lines: Int) {
        val textSize = 11f * scaledDensity
        val half = label.length / 2
        if (lines == 1) {
            val x = cell.centerX() - boldPaint.measureText(label) / 2f
            val y = cell.centerY() - (boldPaint.ascent() + boldPaint.descent()) / 2f
            canvas.drawText(label, x, y, boldPaint)
        } else {
            val first = label.substring(0, half)
            val second = label.substring(half)
            val firstWidth = boldPaint.measureText(first)
            val secondWidth = boldPaint.measureText(second)
            val lineHeight = textSize * 1.25f
            val firstX = cell.centerX() - firstWidth / 2f
            val secondX = cell.centerX() - secondWidth / 2f
            val baseline = cell.centerY() - lineHeight / 2f
            canvas.drawText(first, firstX, baseline, boldPaint)
            canvas.drawText(second, secondX, baseline + lineHeight, boldPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                dragStartOffset = scrollOffset
                dragging = false
                touchTarget = TouchTarget.NONE
                touchTool = null
                val g = grid()
                val closeSize = 48f * density
                val closeRect = RectF(
                    width - g.pad - closeSize,
                    (g.headerHeight - closeSize) / 2f,
                    width - g.pad,
                    (g.headerHeight + closeSize) / 2f,
                )
                if (closeRect.contains(event.x, event.y)) {
                    touchTarget = TouchTarget.CLOSE
                    return true
                }
                if (event.y >= g.headerHeight) {
                    touchTarget = TouchTarget.TOOL
                    touchTool = toolAt(event.x, event.y, g)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - touchStartY
                val dx = event.x - touchStartX
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    val g = grid()
                    scrollOffset = (dragStartOffset - dy).coerceIn(0f, g.maxScroll)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (!dragging && !cancelled) {
                    performClick()
                    when (touchTarget) {
                        TouchTarget.CLOSE -> listener?.onCloseRequested()
                        TouchTarget.TOOL -> touchTool?.let { listener?.onToolRequested(it) }
                        TouchTarget.NONE -> Unit
                    }
                }
                touchTarget = TouchTarget.NONE
                touchTool = null
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun toolAt(x: Float, y: Float, g: Grid): ToolSpec? {
        ToolsCatalog.tools.forEachIndexed { index, spec ->
            val row = index / g.columns
            val column = index % g.columns
            val left = g.pad + column * (g.cellSize + g.gap)
            val top = g.contentTop + row * (g.cellSize + g.gap) - scrollOffset
            if (x >= left && x <= left + g.cellSize && y >= top && y <= top + g.cellSize) {
                return spec
            }
        }
        return null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
