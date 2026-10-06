package com.lightmeter.rawmeter

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
 * touches. Tools are rendered from [ToolsCatalog] as a three-column grid; taps dispatch through
 * [Listener]. Only the artwork inset and surface treatment differ from the original grid.
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

    private val density get() = layoutDensity(LayoutProfile.SCROLL)
    private val scaledDensity get() = layoutTextDensity(LayoutProfile.SCROLL)
    private val lightBlack = InstrumentStyle.ink
    private val nightForeground = InstrumentStyle.nightInk
    private val background: Int get() = InstrumentStyle.background(state.isDarkMode)
    private val foreground: Int get() = if (state.isDarkMode) nightForeground else lightBlack
    private val dividerColor: Int
        get() = InstrumentStyle.border(state.isDarkMode)
    private val cellColor: Int
        get() = InstrumentStyle.iconTile(state.isDarkMode)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = InstrumentStyle.labelTypeface
    }
    private val iconPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG,
    )
    /**
     * These icons intentionally keep their authored white fill in both themes. The Tools cells
     * retain a gray backing, so tinting them with the normal foreground color would make the supplied
     * artwork less legible and would also discard its antialiased edge treatment.
     */
    private data class ToolIcon(
        val bitmap: Bitmap,
        val contentBounds: Rect,
    )

    private val toolIcons: Map<ToolId, ToolIcon> by lazy(LazyThreadSafetyMode.NONE) {
        mapOf(
            ToolId.LATITUDE to decodeToolIcon(R.drawable.tool_icon_latitude),
            ToolId.FLASH_INDEX to decodeToolIcon(R.drawable.tool_icon_flash_exposure),
            ToolId.PARAMETER_LOG to decodeToolIcon(R.drawable.tool_icon_parameter_record),
            ToolId.RECIPROCITY to decodeToolIcon(R.drawable.tool_icon_reciprocity),
            ToolId.COLOR_TEMPERATURE to decodeToolIcon(R.drawable.tool_icon_color_temperature),
            ToolId.DEPTH_OF_FIELD to decodeToolIcon(R.drawable.tool_icon_depth_of_field),
        )
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
        val columns = AdaptiveLayout.columns(width.toFloat(), pad, gap,
            72f * resources.displayMetrics.density, 3)
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
            canvas.drawRoundRect(cell, 8f * density, 8f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.75f * density
            paint.color = dividerColor
            canvas.drawRoundRect(cell, 8f * density, 8f * density, paint)
            paint.style = Paint.Style.FILL
            boldPaint.color = foreground
            boldPaint.textSize = TOOL_LABEL_TEXT_SP * scaledDensity
            val label = spec.label.resolve(state.menuLanguage)
            val labelWidth = boldPaint.measureText(label)
            val lines = if (labelWidth > cell.width() - 16f * density) 2 else 1
            val textBlockHeight = boldPaint.textSize * if (lines == 1) 1.25f else 2.45f
            val iconBox = RectF(
                cell.left + 17f * density,
                cell.top + 14f * density,
                cell.right - 17f * density,
                cell.bottom - textBlockHeight - 17f * density,
            )
            toolIcons[spec.id]?.let { icon ->
                drawIconFitCenter(canvas, icon, iconBox)
            }
            if (spec.id == ToolId.FILM_NEGATIVE) drawNegativeIcon(canvas, iconBox)
            drawCellLabel(canvas, label, cell, lines)
        }
        canvas.restore()
        paint.style = Paint.Style.FILL
        paint.color = dividerColor
        canvas.drawRect(0f, g.headerHeight - 0.8f * density, width.toFloat(), g.headerHeight, paint)
    }

    private fun drawNegativeIcon(canvas: Canvas, box: RectF) {
        val size = min(box.width(), box.height()) * 0.72f
        val film = RectF(box.centerX() - size * 0.55f, box.centerY() - size * 0.36f,
            box.centerX() + size * 0.55f, box.centerY() + size * 0.36f)
        paint.color = foreground
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        canvas.drawRoundRect(film, 3f * density, 3f * density, paint)
        canvas.drawLine(film.centerX(), film.top + size * 0.14f,
            film.centerX(), film.bottom - size * 0.14f, paint)
        paint.style = Paint.Style.FILL
        for (index in 0..3) {
            val x = film.left + size * (0.13f + index * 0.25f)
            canvas.drawRect(x, film.top + size * 0.04f, x + size * 0.07f,
                film.top + size * 0.10f, paint)
            canvas.drawRect(x, film.bottom - size * 0.10f, x + size * 0.07f,
                film.bottom - size * 0.04f, paint)
        }
        canvas.drawRect(film.left + size * 0.13f, film.top + size * 0.19f,
            film.centerX() - size * 0.08f, film.bottom - size * 0.19f, paint)
    }

    private fun drawCellLabel(canvas: Canvas, label: String, cell: RectF, lines: Int) {
        val textSize = TOOL_LABEL_TEXT_SP * scaledDensity
        val half = label.length / 2
        if (lines == 1) {
            val x = cell.centerX() - boldPaint.measureText(label) / 2f
            val y = cell.bottom - 9f * density - boldPaint.descent()
            canvas.drawText(label, x, y, boldPaint)
        } else {
            val first = label.substring(0, half)
            val second = label.substring(half)
            val firstWidth = boldPaint.measureText(first)
            val secondWidth = boldPaint.measureText(second)
            val lineHeight = textSize * 1.12f
            val firstX = cell.centerX() - firstWidth / 2f
            val secondX = cell.centerX() - secondWidth / 2f
            val secondBaseline = cell.bottom - 7f * density - boldPaint.descent()
            canvas.drawText(first, firstX, secondBaseline - lineHeight, boldPaint)
            canvas.drawText(second, secondX, secondBaseline, boldPaint)
        }
    }

    private fun drawIconFitCenter(canvas: Canvas, icon: ToolIcon, box: RectF) {
        if (box.width() <= 0f || box.height() <= 0f) return
        val source = icon.contentBounds
        val scale = min(box.width() / source.width(), box.height() / source.height()) *
            TOOL_ICON_VISIBLE_SCALE
        val width = source.width() * scale
        val height = source.height() * scale
        val destination = RectF(
            box.centerX() - width / 2f,
            box.centerY() - height / 2f,
            box.centerX() + width / 2f,
            box.centerY() + height / 2f,
        )
        canvas.drawBitmap(
            icon.bitmap,
            source,
            destination,
            iconPaint,
        )
    }

    private fun decodeToolIcon(resourceId: Int): ToolIcon {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, resourceId, bounds)
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= TOOL_ICON_DECODE_TARGET_PX &&
            bounds.outHeight / (sampleSize * 2) >= TOOL_ICON_DECODE_TARGET_PX
        ) {
            sampleSize *= 2
        }
        val bitmap = requireNotNull(
            BitmapFactory.decodeResource(
                resources,
                resourceId,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ),
        ) { "Unable to decode Tools icon resource $resourceId" }
        return ToolIcon(bitmap, findVisibleBounds(bitmap))
    }

    /** Crops only transparent padding at draw time; the supplied pixels remain unchanged. */
    private fun findVisibleBounds(bitmap: Bitmap): Rect {
        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = -1
        var maxY = -1
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in row.indices) {
                if (row[x] ushr 24 <= MIN_VISIBLE_ALPHA) continue
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }
        }
        return if (maxX >= minX && maxY >= minY) {
            Rect(minX, minY, maxX + 1, maxY + 1)
        } else {
            Rect(0, 0, bitmap.width, bitmap.height)
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

    private companion object {
        const val TOOL_LABEL_TEXT_SP = 13.5f
        const val TOOL_ICON_DECODE_TARGET_PX = 288
        const val TOOL_ICON_VISIBLE_SCALE = 0.66f
        const val MIN_VISIBLE_ALPHA = 8
    }
}
