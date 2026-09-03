package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
        fun onSettingRejected(key: SettingKey, value: String)
        fun onActionRequested(key: SettingActionKey)
    }

    private data class OptionHitTarget(
        val item: SettingItemSpec,
        val option: SettingOptionSpec,
        val rect: RectF,
    )

    private data class ActionHitTarget(
        val action: SettingActionSpec,
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
    private var nestedBackRect = RectF()
    private var tabRects: List<RectF> = emptyList()
    private var tabSections: List<SettingsSectionSpec> = emptyList()
    private var optionTargets: List<OptionHitTarget> = emptyList()
    private var actionTargets: List<ActionHitTarget> = emptyList()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var scrollOffset = 0f
    private var maxScrollOffset = 0f
    private val flingScroller = VerticalFlingScroller(context)

    fun selectSection(key: SettingsSectionKey) {
        val index = SettingsCatalog.sections.indexOfFirst { it.key == key }
        if (index < 0) return
        selectedSectionIndex = index
        scrollOffset = 0f
        flingScroller.cancel()
        invalidate()
    }

    fun resetNestedSection() {
        if (SettingsCatalog.sections.getOrNull(selectedSectionIndex)?.key == SettingsSectionKey.MORE) {
            selectSection(SettingsSectionKey.METERING)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val background = if (state.isDarkMode) Color.BLACK else Color.WHITE
        val foreground = if (state.isDarkMode) {
            Color.rgb(210, 210, 206)
        } else {
            Color.rgb(20, 20, 20)
        }
        val navGray = if (state.isDarkMode) Color.rgb(102, 102, 100) else Color.rgb(226, 226, 223)
        val divider = if (state.isDarkMode) Color.rgb(72, 72, 70) else Color.rgb(205, 205, 201)
        val red = Color.rgb(166, 27, 36)
        val sections = SettingsCatalog.sections
        val section = sections[selectedSectionIndex]
        val nestedMore = section.key == SettingsSectionKey.MORE

        canvas.drawColor(background)
        val headerHeight = minOf(height * 0.15f, 76f * density).coerceAtLeast(58f * density)
        paint.style = Paint.Style.FILL
        paint.color = navGray
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, paint)

        val closeWidth = 48f * density
        closeRect = RectF(width - closeWidth, 0f, width.toFloat(), headerHeight)
        if (nestedMore) {
            tabRects = emptyList()
            tabSections = emptyList()
            nestedBackRect = RectF(0f, 0f, 56f * density, headerHeight)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f * density
            paint.color = foreground
            val arrow = Path().apply {
                moveTo(nestedBackRect.right - 12f * density, nestedBackRect.centerY())
                lineTo(nestedBackRect.left + 16f * density, nestedBackRect.centerY())
                lineTo(nestedBackRect.left + 27f * density, nestedBackRect.centerY() - 9f * density)
                moveTo(nestedBackRect.left + 16f * density, nestedBackRect.centerY())
                lineTo(nestedBackRect.left + 27f * density, nestedBackRect.centerY() + 9f * density)
            }
            canvas.drawPath(arrow, paint)
            boldPaint.textSize = 14f * density
            boldPaint.color = foreground
            drawCenteredText(
                canvas,
                section.label.resolve(state.menuLanguage),
                width / 2f,
                headerHeight / 2f,
                boldPaint,
            )
        } else {
            nestedBackRect.setEmpty()
            val tabsWidth = closeRect.left
            tabSections = sections.filter { it.key != SettingsSectionKey.MORE }
            val tabWidth = tabsWidth / tabSections.size
            tabRects = tabSections.indices.map { index ->
                RectF(index * tabWidth, 0f, (index + 1) * tabWidth, headerHeight)
            }
            tabSections.forEachIndexed { index, tabSection ->
                val rect = tabRects[index]
                val selected = tabSection.key == section.key
                if (selected) {
                    paint.style = Paint.Style.FILL
                    paint.color = foreground
                    canvas.drawRect(rect, paint)
                }
                boldPaint.textSize = 12f * density
                boldPaint.color = when {
                    !tabSection.enabled -> Color.rgb(145, 145, 142)
                    selected -> background
                    else -> foreground
                }
                drawCenteredText(
                    canvas,
                    tabSection.label.resolve(state.menuLanguage),
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

        val topPadding = 14f * density
        val bottomPadding = 18f * density
        val rowGap = 10f * density
        val rowHeight = if (width > height) 74f * density else 84f * density
        val compactActionHeight = if (width > height) 48f * density else 54f * density
        val inlineActions = section.inlineActions.values.flatten()
        val allActions = inlineActions + section.actions
        fun actionHeight(action: SettingActionSpec): Float = when (action.key) {
            SettingActionKey.SHOW_ABOUT,
            SettingActionKey.MANAGE_CAMERAS,
            SettingActionKey.MANAGE_OUTPUT_ASPECTS,
            SettingActionKey.SHOW_MORE_SETTINGS,
            -> compactActionHeight
            else -> rowHeight
        }
        val rowCount = section.items.size + allActions.size
        val contentHeight = topPadding +
            section.items.size * rowHeight +
            allActions.sumOf { actionHeight(it).toDouble() }.toFloat() +
            max(0, rowCount - 1) * rowGap +
            bottomPadding
        maxScrollOffset = max(0f, contentHeight - (height - headerHeight))
        scrollOffset = scrollOffset.coerceIn(0f, maxScrollOffset)

        canvas.save()
        canvas.clipRect(0f, headerHeight, width.toFloat(), height.toFloat())
        var y = headerHeight + topPadding - scrollOffset
        val newTargets = mutableListOf<OptionHitTarget>()
        val newActionTargets = mutableListOf<ActionHitTarget>()
        val drawAction: (SettingActionSpec) -> Unit = { action ->
            val height = actionHeight(action)
            val sameSurface = action.key in setOf(
                SettingActionKey.MANAGE_CAMERAS,
                SettingActionKey.MANAGE_OUTPUT_ASPECTS,
                SettingActionKey.SHOW_MORE_SETTINGS,
            )
            val row = RectF(10f * density, y, width - 10f * density, y + height)
            paint.style = Paint.Style.FILL
            paint.color = background
            canvas.drawRect(row, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = divider
            canvas.drawRect(row, paint)

            val verticalInset = if (height == compactActionHeight) 6f * density else 9f * density
            val button = RectF(
                row.left + 10f * density,
                row.top + verticalInset,
                row.right - 10f * density,
                row.bottom - verticalInset,
            )
            paint.style = Paint.Style.FILL
            paint.color = if (sameSurface) background else foreground
            canvas.drawRoundRect(button, 3f * density, 3f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (sameSurface) 1f * density else 1.4f * density
            paint.color = if (sameSurface) foreground else red
            canvas.drawRoundRect(button, 3f * density, 3f * density, paint)

            boldPaint.textSize = (if (height == compactActionHeight) 9.5f else 11f) * density
            boldPaint.color = if (sameSurface) foreground else background
            val showDescription = action.description != null && height != compactActionHeight
            val titleY = if (showDescription) button.centerY() - 8f * density else button.centerY()
            drawCenteredText(
                canvas,
                action.label.resolve(state.menuLanguage),
                button.centerX(),
                titleY,
                boldPaint,
            )
            if (showDescription) {
                paint.style = Paint.Style.FILL
                paint.typeface = Typeface.DEFAULT
                paint.textSize = 7.5f * density
                paint.color = background
                drawCenteredText(
                    canvas,
                    requireNotNull(action.description).resolve(state.menuLanguage),
                    button.centerX(),
                    button.centerY() + 11f * density,
                    paint,
                )
            }
            newActionTargets += ActionHitTarget(action, button)
            y += height + rowGap
        }
        section.inlineActions[0].orEmpty().forEach(drawAction)
        section.items.forEachIndexed { itemIndex, item ->
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
                val optionText = option.label.resolve(state.menuLanguage)
                val maximumTextWidth = rect.width() - 8f * density
                while (paint.textSize > 6.5f * density &&
                    paint.measureText(optionText) > maximumTextWidth
                ) {
                    paint.textSize -= 0.5f * density
                }
                drawCenteredText(
                    canvas,
                    optionText,
                    rect.centerX(),
                    rect.centerY(),
                    paint,
                )
                newTargets += OptionHitTarget(item, option, rect)
            }
            y += rowHeight + rowGap
            section.inlineActions[itemIndex + 1].orEmpty().forEach(drawAction)
        }
        section.actions.forEach(drawAction)
        optionTargets = newTargets
        actionTargets = newActionTargets
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flingScroller.begin(event)
                downX = event.x
                downY = event.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.y - downY) > touchSlop) dragging = true
                if (dragging && maxScrollOffset > 0f) {
                    scrollOffset = flingScroller.drag(event, scrollOffset, maxScrollOffset)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && abs(event.x - downX) <= touchSlop * 2f) {
                    handleTap(event.x, event.y)
                }
                if (flingScroller.finish(event, scrollOffset, maxScrollOffset)) {
                    postInvalidateOnAnimation()
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                flingScroller.cancel()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun computeScroll() {
        flingScroller.compute(maxScrollOffset)?.let {
            scrollOffset = it
            postInvalidateOnAnimation()
        }
    }

    private fun handleTap(x: Float, y: Float) {
        if (closeRect.contains(x, y)) {
            haptic()
            listener?.onCloseRequested()
            return
        }
        if (nestedBackRect.contains(x, y)) {
            haptic()
            selectSection(SettingsSectionKey.METERING)
            return
        }
        val tabIndex = tabRects.indexOfFirst { it.contains(x, y) }
        if (tabIndex >= 0) {
            val section = tabSections[tabIndex]
            val sectionIndex = SettingsCatalog.sections.indexOf(section)
            if (section.enabled && sectionIndex != selectedSectionIndex) {
                selectedSectionIndex = sectionIndex
                scrollOffset = 0f
                flingScroller.cancel()
                haptic()
                invalidate()
            }
            return
        }
        val actionTarget = actionTargets.firstOrNull { it.rect.contains(x, y) }
        if (actionTarget != null) {
            haptic()
            if (actionTarget.action.key == SettingActionKey.SHOW_MORE_SETTINGS) {
                selectSection(SettingsSectionKey.MORE)
            } else {
                listener?.onActionRequested(actionTarget.action.key)
            }
            return
        }
        val target = optionTargets.firstOrNull { it.rect.contains(x, y) } ?: return
        if (state.settingValue(target.item.key) == target.option.value) return
        if (!state.updateSetting(target.item.key, target.option.value)) {
            haptic()
            listener?.onSettingRejected(target.item.key, target.option.value)
            return
        }
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
