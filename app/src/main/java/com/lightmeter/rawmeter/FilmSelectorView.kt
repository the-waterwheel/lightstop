package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.TextUtils
import android.text.TextPaint
import android.text.TextWatcher
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import kotlin.math.abs
import kotlin.math.max

/** Full-screen searchable film sheet. Portrait presentation is animated from the top. */
@SuppressLint("ViewConstructor")
internal class FilmSelectorView(
    context: Context,
    private val state: MeterState,
    private val repository: FilmLatitudeRepository,
) : ViewGroup(context) {

    interface Listener {
        fun onCloseRequested()
        fun onFilmSelected(profile: FilmLatitudeProfile)
        fun onFilmRangeReset(profile: FilmLatitudeProfile)
    }

    var listener: Listener? = null

    private enum class TouchTarget { BACK, ADD, FAVORITES, ROW_SELECT, ROW_RESET, ROW_FAVORITE, LIST, EMPTY_ADD, NONE }

    private val density = resources.displayMetrics.density
    private val scaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.NORMAL) }
    private val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.BOLD) }
    private val background: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val foreground: Int get() = if (state.isDarkMode) Color.rgb(224, 224, 220) else Color.rgb(20, 20, 20)
    private val muted: Int get() = if (state.isDarkMode) Color.rgb(112, 112, 108) else Color.rgb(158, 158, 154)
    private val divider: Int get() = if (state.isDarkMode) Color.rgb(76, 76, 72) else Color.rgb(205, 205, 201)
    private val surface: Int get() = if (state.isDarkMode) Color.rgb(38, 38, 36) else Color.rgb(242, 242, 239)
    private val red = Color.rgb(201, 39, 46)
    private val blue = Color.rgb(36, 112, 190)
    private var geometry = FilmSelectorGeometry.EMPTY
    private var visibleFilms: List<FilmLatitudeProfile> = emptyList()
    private var favoritesOnly = false
    private var scrollOffset = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchTarget = TouchTarget.NONE
    private var touchFilm: FilmLatitudeProfile? = null
    private var dragging = false
    private val flingScroller = VerticalFlingScroller(context)
    private val dialogs = FilmLatitudeDialogs(context, state)

    private val searchField = EditText(context).apply {
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding((12f * density).toInt(), 0, (12f * density).toInt(), 0)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshFilms()
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(searchField)
        refreshFilms()
    }

    fun open() {
        flingScroller.cancel()
        applyTheme()
        refreshFilms()
        scrollOffset = 0f
    }

    fun applyTheme() {
        searchField.setTextColor(foreground)
        searchField.setHintTextColor(muted)
        searchField.setBackgroundColor(surface)
        searchField.hint = localized("搜索胶片", "Search films")
        invalidate()
    }

    fun releaseInput() {
        searchField.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }

    @Suppress("DEPRECATION")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val safeTop = rootWindowInsets?.systemWindowInsetTop?.toFloat() ?: 0f
        geometry = FilmSelectorGeometryCalculator.calculate(width, height, density, safeTop)
        searchField.measure(
            MeasureSpec.makeMeasureSpec(geometry.search.width().toInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(geometry.search.height().toInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        searchField.layout(
            geometry.search.left.toInt(),
            geometry.search.top.toInt(),
            geometry.search.right.toInt(),
            geometry.search.bottom.toInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        drawHeader(canvas)
        drawSearchControls(canvas)
        drawFilms(canvas)
        updateContentDescription()
    }

    private fun drawHeader(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f * density
        paint.color = foreground
        val x = geometry.back.centerX()
        val y = geometry.back.centerY()
        canvas.drawLine(x + 5f * density, y - 8f * density, x - 4f * density, y, paint)
        canvas.drawLine(x - 4f * density, y, x + 5f * density, y + 8f * density, paint)
        boldPaint.color = foreground
        boldPaint.textSize = 18f * scaledDensity
        boldPaint.textAlign = Paint.Align.CENTER
        centeredText(canvas, localized("选择胶片", "Select film"), width / 2f, y, boldPaint)
    }

    private fun drawSearchControls(canvas: Canvas) {
        drawOutlinedBox(canvas, geometry.add, if (state.menuLanguage == MenuLanguage.ENGLISH) "+" else "＋", false)
        drawOutlinedBox(canvas, geometry.favorites, if (favoritesOnly) "★" else "☆", favoritesOnly)
    }

    private fun drawFilms(canvas: Canvas) {
        val totalHeight = visibleFilms.size * geometry.rowHeight
        val maxScroll = max(0f, totalHeight - geometry.list.height())
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
        canvas.save()
        canvas.clipRect(geometry.list)
        if (visibleFilms.isEmpty()) {
            drawEmpty(canvas)
        } else {
            val firstIndex = (scrollOffset / geometry.rowHeight).toInt().coerceIn(0, visibleFilms.lastIndex)
            val lastIndex = ((scrollOffset + geometry.list.height()) / geometry.rowHeight)
                .toInt().plus(1).coerceIn(firstIndex, visibleFilms.lastIndex)
            for (index in firstIndex..lastIndex) {
                val profile = visibleFilms[index]
                val top = geometry.list.top + index * geometry.rowHeight - scrollOffset
                val row = RectF(geometry.list.left, top, geometry.list.right, top + geometry.rowHeight - 5f * density)
                if (RectF.intersects(row, geometry.list)) drawFilmRow(canvas, row, profile)
            }
        }
        canvas.restore()
    }

    private fun drawFilmRow(canvas: Canvas, row: RectF, profile: FilmLatitudeProfile) {
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRoundRect(row, 6f * density, 6f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * density
        paint.color = divider
        canvas.drawRoundRect(row, 6f * density, 6f * density, paint)

        val actionWidth = (row.width() * 0.22f).coerceIn(64f * density, 104f * density)
        val favoriteWidth = 44f * density
        val textRight = row.right - actionWidth - favoriteWidth - 14f * density
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = 14f * scaledDensity
        boldPaint.color = foreground
        val label = TextUtils.ellipsize(profile.displayName, boldPaint, textRight - row.left - 14f * density, TextUtils.TruncateAt.END)
        canvas.drawText(label.toString(), row.left + 10f * density, row.top + 25f * density, boldPaint)

        val range = repository.effectiveRange(profile)
        paint.style = Paint.Style.FILL
        paint.color = if (state.isDarkMode) Color.rgb(168, 168, 164) else Color.rgb(96, 96, 92)
        paint.textSize = 13f * scaledDensity
        paint.textAlign = Paint.Align.LEFT
        val iso = profile.iso?.let { "  ISO $it" }.orEmpty()
        canvas.drawText(
            "${signed(range.shadowEv)} / ${signed(range.highlightEv)} EV$iso",
            row.left + 10f * density,
            row.bottom - 13f * density,
            paint,
        )

        val resetRect = rowResetRect(row)
        paint.textAlign = Paint.Align.CENTER
        paint.color = if (range == profile.originalRange) muted else foreground
        paint.textSize = 10f * scaledDensity
        centeredText(canvas, localized("重置宽容度", "Reset latitude"), resetRect.centerX(), resetRect.centerY(), paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 18f * scaledDensity
        boldPaint.color = if (repository.isFavorite(profile.id)) red else foreground
        centeredText(canvas, if (repository.isFavorite(profile.id)) "★" else "☆", rowFavoriteRect(row).centerX(), row.centerY(), boldPaint)
    }

    private fun drawEmpty(canvas: Canvas) {
        val first = localized("没有找到？", "Not found? ")
        val action = localized("手动录入数据", "Enter data manually")
        paint.textSize = 14f * scaledDensity
        paint.textAlign = Paint.Align.LEFT
        val total = paint.measureText(first) + paint.measureText(action)
        val x = geometry.list.centerX() - total / 2f
        val y = geometry.list.top + geometry.list.height() * 0.30f
        paint.color = muted
        canvas.drawText(first, x, y, paint)
        paint.color = blue
        canvas.drawText(action, x + paint.measureText(first), y, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                dragging = false
                touchFilm = null
                touchTarget = when {
                    geometry.back.contains(event.x, event.y) -> TouchTarget.BACK
                    geometry.add.contains(event.x, event.y) -> TouchTarget.ADD
                    geometry.favorites.contains(event.x, event.y) -> TouchTarget.FAVORITES
                    visibleFilms.isEmpty() && geometry.list.contains(event.x, event.y) -> TouchTarget.EMPTY_ADD
                    geometry.list.contains(event.x, event.y) -> targetForRow(event.x, event.y)
                    else -> TouchTarget.NONE
                }
                if (geometry.list.contains(event.x, event.y)) flingScroller.begin(event)
                return touchTarget != TouchTarget.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (!dragging && geometry.list.contains(touchStartX, touchStartY) &&
                    (abs(dx) > touchSlop || abs(dy) > touchSlop)
                ) dragging = true
                if (dragging) {
                    val maxScroll = max(0f, visibleFilms.size * geometry.rowHeight - geometry.list.height())
                    scrollOffset = flingScroller.drag(event, scrollOffset, maxScroll)
                    postInvalidateOnAnimation()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (!cancelled && !dragging) {
                    performClick()
                    handleTap()
                    flingScroller.cancel()
                } else if (!cancelled && dragging) {
                    val maxScroll = max(0f, visibleFilms.size * geometry.rowHeight - geometry.list.height())
                    if (flingScroller.finish(event, scrollOffset, maxScroll)) postInvalidateOnAnimation()
                } else {
                    flingScroller.cancel()
                }
                touchTarget = TouchTarget.NONE
                touchFilm = null
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun computeScroll() {
        val maxScroll = max(0f, visibleFilms.size * geometry.rowHeight - geometry.list.height())
        flingScroller.compute(maxScroll)?.let {
            scrollOffset = it
            postInvalidateOnAnimation()
        }
    }

    private fun targetForRow(x: Float, y: Float): TouchTarget {
        val index = ((y - geometry.list.top + scrollOffset) / geometry.rowHeight).toInt()
        val profile = visibleFilms.getOrNull(index) ?: return TouchTarget.LIST
        touchFilm = profile
        val top = geometry.list.top + index * geometry.rowHeight - scrollOffset
        val row = RectF(geometry.list.left, top, geometry.list.right, top + geometry.rowHeight - 5f * density)
        return when {
            rowFavoriteRect(row).contains(x, y) -> TouchTarget.ROW_FAVORITE
            rowResetRect(row).contains(x, y) -> TouchTarget.ROW_RESET
            else -> TouchTarget.ROW_SELECT
        }
    }

    private fun handleTap() {
        when (touchTarget) {
            TouchTarget.BACK -> listener?.onCloseRequested()
            TouchTarget.ADD, TouchTarget.EMPTY_ADD -> showCustomDialog()
            TouchTarget.FAVORITES -> {
                favoritesOnly = !favoritesOnly
                refreshFilms()
            }
            TouchTarget.ROW_SELECT -> touchFilm?.let { listener?.onFilmSelected(it) }
            TouchTarget.ROW_RESET -> touchFilm?.let { profile ->
                repository.resetOverride(profile.id)
                listener?.onFilmRangeReset(profile)
                refreshFilms()
            }
            TouchTarget.ROW_FAVORITE -> touchFilm?.let {
                repository.toggleFavorite(it.id)
                refreshFilms()
            }
            else -> Unit
        }
    }

    private fun showCustomDialog() {
        dialogs.showCustomFilm { name, shadow, highlight ->
            val profile = repository.addCustomFilm(name, shadow, highlight)
            refreshFilms()
            listener?.onFilmSelected(profile)
        }
    }

    private fun refreshFilms() {
        val query = searchField.text?.toString()?.trim().orEmpty()
        visibleFilms = repository.films().filter { profile ->
            (!favoritesOnly || repository.isFavorite(profile.id)) &&
                (query.isBlank() || profile.displayName.contains(query, ignoreCase = true) ||
                    profile.type.contains(query, ignoreCase = true))
        }
        scrollOffset = 0f
        invalidate()
    }

    private fun rowFavoriteRect(row: RectF): RectF = RectF(row.right - 50f * density, row.top, row.right, row.bottom)

    private fun rowResetRect(row: RectF): RectF = RectF(
        row.right - 156f * density,
        row.top,
        row.right - 50f * density,
        row.bottom,
    )

    private fun drawOutlinedBox(canvas: Canvas, rect: RectF, label: String, active: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (active) foreground else surface
        canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = divider
        canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)
        boldPaint.color = if (active) background else foreground
        boldPaint.textSize = 18f * scaledDensity
        boldPaint.textAlign = Paint.Align.CENTER
        centeredText(canvas, label, rect.centerX(), rect.centerY(), boldPaint)
    }

    private fun signed(value: Double): String = when {
        value > 0.0 -> "+${decimal(value)}"
        else -> decimal(value)
    }

    private fun decimal(value: Double): String = if (value % 1.0 == 0.0) {
        value.toInt().toString()
    } else {
        "%.1f".format(value)
    }

    private fun centeredText(canvas: Canvas, text: String, x: Float, y: Float, textPaint: Paint) {
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    private fun updateContentDescription() {
        contentDescription = localized(
            "选择胶片，共 ${visibleFilms.size} 个结果",
            "Select film, ${visibleFilms.size} results",
        )
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        flingScroller.cancel()
        super.onDetachedFromWindow()
    }
}
