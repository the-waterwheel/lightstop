package com.lightmeter.rawmeter

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.text.TextPaint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min

/** Interactive film-latitude editor hosted over the Tools grid. */
@SuppressLint("ViewConstructor")
internal class LatitudeView(
    context: Context,
    private val state: MeterState,
    private val repository: FilmLatitudeRepository,
) : View(context) {

    interface Listener {
        fun onBackToToolsRequested()
        fun onCloseRequested()
        fun onFilmSelectionRequested()
        fun onAppliedLatitudeChanged(value: AppliedFilmLatitude?)
    }

    var listener: Listener? = null

    private enum class TouchTarget { BACK, CLOSE, SHADOW, HIGHLIGHT, SELECT, APPLY, RESET, RECORD, NONE }

    private val density = resources.displayMetrics.density
    private val scaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val path = Path()
    private val background: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val foreground: Int get() = if (state.isDarkMode) Color.rgb(224, 224, 220) else Color.rgb(20, 20, 20)
    private val muted: Int get() = if (state.isDarkMode) Color.rgb(112, 112, 108) else Color.rgb(168, 168, 164)
    private val panel: Int get() = if (state.isDarkMode) Color.rgb(44, 44, 42) else Color.rgb(235, 235, 232)
    private val inversePanel: Int get() = if (state.isDarkMode) Color.rgb(218, 218, 214) else Color.rgb(48, 48, 46)
    private val red = Color.rgb(205, 38, 45)
    private var geometry = LatitudeGeometry.EMPTY
    private val session = LatitudeSession()
    private var displayedRange = FilmLatitudeRange.FULL_SCALE
    private var markerAnimator: ValueAnimator? = null
    private var touchTarget = TouchTarget.NONE
    private var moved = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastHapticAt = 0L

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun openPage() {
        session.open(repository)
        displayedRange = session.range
        invalidate()
    }

    fun resumePage() {
        if (!session.initialized) openPage() else invalidate()
    }

    fun selectFilm(profile: FilmLatitudeProfile) {
        session.select(profile, repository.effectiveRange(profile))
        animateToSessionRange()
        syncAppliedIfNeeded()
    }

    fun onFilmRangeReset(profile: FilmLatitudeProfile) {
        if (session.selectedFilmId != profile.id) return
        session.select(profile, repository.effectiveRange(profile))
        animateToSessionRange()
        syncAppliedIfNeeded()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        geometry = LatitudeGeometryCalculator.calculate(width, height, density)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        if (!session.initialized) openPage()
        drawHeader(canvas)
        drawLatitudeRail(canvas)
        drawFilmCard(canvas)
        drawActions(canvas)
        updateContentDescription()
    }

    private fun drawHeader(canvas: Canvas) {
        val y = geometry.back.centerY()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * density
        paint.color = foreground
        canvas.drawLine(geometry.back.centerX() + 5f * density, y - 8f * density, geometry.back.centerX() - 4f * density, y, paint)
        canvas.drawLine(geometry.back.centerX() - 4f * density, y, geometry.back.centerX() + 5f * density, y + 8f * density, paint)
        val closeX = geometry.close.centerX()
        canvas.drawLine(closeX - 7f * density, y - 7f * density, closeX + 7f * density, y + 7f * density, paint)
        canvas.drawLine(closeX + 7f * density, y - 7f * density, closeX - 7f * density, y + 7f * density, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = foreground
        boldPaint.textSize = 14f * scaledDensity
        centeredText(canvas, localized("宽容度", "Latitude"), width / 2f, y, boldPaint)
        paint.style = Paint.Style.FILL
        paint.color = muted
        canvas.drawRect(0f, geometry.back.bottom - density, width.toFloat(), geometry.back.bottom, paint)
    }

    private fun drawLatitudeRail(canvas: Canvas) {
        val scale = latitudeScaleRect()
        for (zone in 0..10) {
            val cellLeft = scale.left + scale.width() * zone / 11f
            val cellRight = scale.left + scale.width() * (zone + 1) / 11f
            val cell = RectF(cellLeft, scale.top, cellRight, scale.bottom)
            val maximum = if (state.isDarkMode) 210 else 255
            val luma = (maximum * zone / 10f).toInt()
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(luma, luma, luma)
            canvas.drawRect(cell, paint)
            boldPaint.textAlign = Paint.Align.CENTER
            boldPaint.textSize = 8.5f * scaledDensity
            boldPaint.color = if (luma < 120) Color.rgb(225, 225, 222) else Color.rgb(20, 20, 20)
            centeredText(canvas, ZoneMeterSession.ZONE_LABELS[zone], cell.centerX(), cell.centerY(), boldPaint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawRoundRect(scale, 3f * density, 3f * density, paint)

        val shadowX = zoneX(scale.left, scale.right, displayedRange.lowerZone)
        val highlightX = zoneX(scale.left, scale.right, displayedRange.upperZone)
        drawFilmMarker(canvas, shadowX, scale, displayedRange.shadowEv, isShadow = true)
        drawFilmMarker(canvas, highlightX, scale, displayedRange.highlightEv, isShadow = false)
    }

    private fun drawFilmMarker(canvas: Canvas, x: Float, scale: RectF, ev: Double, isShadow: Boolean) {
        val tabWidth = 40f * density
        val tabHeight = 24f * density
        val tabTop = scale.bottom + 16f * density
        val preferredLeft = if (isShadow) x - tabWidth * 0.82f else x - tabWidth * 0.18f
        val tabLeft = preferredLeft.coerceIn(geometry.rail.left, geometry.rail.right - tabWidth)
        val tab = RectF(tabLeft, tabTop, tabLeft + tabWidth, tabTop + tabHeight)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = red
        canvas.drawLine(x, scale.top - 4f * density, x, scale.bottom + 8f * density, paint)
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(x - 5f * density, scale.bottom + 7f * density)
        path.lineTo(x + 5f * density, scale.bottom + 7f * density)
        path.lineTo(x, scale.bottom + 13f * density)
        path.close()
        paint.color = red
        canvas.drawPath(path, paint)

        paint.color = Color.rgb(248, 248, 246)
        canvas.drawRoundRect(tab, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = Color.rgb(22, 22, 22)
        canvas.drawRoundRect(tab, 3f * density, 3f * density, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 9f * scaledDensity
        boldPaint.color = Color.rgb(22, 22, 22)
        centeredText(
            canvas,
            if (state.menuLanguage == MenuLanguage.ENGLISH) "${signed(ev)} EV" else "${signed(ev)} 档",
            tab.centerX(),
            tab.centerY(),
            boldPaint,
        )
    }

    private fun latitudeScaleRect(): RectF {
        val height = (geometry.rail.height() * 0.38f).coerceIn(28f * density, 42f * density)
        return RectF(
            geometry.rail.left,
            geometry.rail.top + 5f * density,
            geometry.rail.right,
            geometry.rail.top + 5f * density + height,
        )
    }

    private fun drawFilmCard(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawRoundRect(geometry.filmCard, 6f * density, 6f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = muted
        canvas.drawRoundRect(geometry.filmCard, 6f * density, 6f * density, paint)
        val selected = repository.find(session.selectedFilmId)
        val display = selected ?: repository.films().firstOrNull()
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = 13f * scaledDensity
        boldPaint.color = if (selected == null) muted else foreground
        val available = (geometry.filmCard.width() - 18f * density).coerceAtLeast(1f)
        val label = display?.displayName ?: localized("没有胶片数据", "No film data")
        val fitted = TextUtils.ellipsize(label, boldPaint, available, TextUtils.TruncateAt.END)
        val x = geometry.filmCard.left + 9f * density
        val centerY = geometry.filmCard.centerY()
        canvas.drawText(fitted.toString(), x, centerY - 2f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 9.5f * scaledDensity
        paint.color = if (selected == null) muted else foreground
        canvas.drawText(
            "${signed(session.range.shadowEv)} / ${signed(session.range.highlightEv)} EV",
            x,
            centerY + 15f * density,
            paint,
        )
    }

    private fun drawActions(canvas: Canvas) {
        drawButton(canvas, geometry.selectFilm, localized("选择胶片", "Select film"), false)
        drawApplyButton(
            canvas = canvas,
            rect = geometry.apply,
            label = if (session.applied) localized("取消应用宽容度", "Cancel metering latitude")
            else localized("应用宽容度到测光", "Apply latitude to meter"),
            active = session.applied,
        )
        drawResetAction(canvas)
        drawButton(canvas, geometry.record, localized("记录当前宽容度到数据", "Save current latitude"), false)
    }

    private fun drawApplyButton(canvas: Canvas, rect: RectF, label: String, active: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (active) Color.rgb(218, 218, 214) else Color.WHITE
        canvas.drawRoundRect(rect, 7f * density, 7f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * density
        paint.color = red
        canvas.drawRoundRect(rect, 7f * density, 7f * density, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = Color.rgb(22, 22, 22)
        boldPaint.textSize = min(11.5f * scaledDensity, rect.height() * 0.25f)
        val fitted = TextUtils.ellipsize(
            label,
            boldPaint,
            (rect.width() - 12f * density).coerceAtLeast(1f),
            TextUtils.TruncateAt.END,
        )
        centeredText(canvas, fitted.toString(), rect.centerX(), rect.centerY(), boldPaint)
    }

    private fun drawResetAction(canvas: Canvas) {
        val rect = geometry.reset
        val radius = min(rect.width(), rect.height()) / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (state.isDarkMode) panel else Color.WHITE
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = foreground
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius - density, paint)

        paint.color = red
        paint.strokeWidth = 2f * density
        val arrowRadius = radius * 0.48f
        val arc = RectF(
            rect.centerX() - arrowRadius,
            rect.centerY() - arrowRadius,
            rect.centerX() + arrowRadius,
            rect.centerY() + arrowRadius,
        )
        canvas.drawArc(arc, -65f, 300f, false, paint)
        paint.style = Paint.Style.FILL
        path.reset()
        val arrowX = rect.centerX() + arrowRadius * 0.88f
        val arrowY = rect.centerY() - arrowRadius * 0.48f
        path.moveTo(arrowX + 4f * density, arrowY - 1f * density)
        path.lineTo(arrowX - 1f * density, arrowY - 4f * density)
        path.lineTo(arrowX, arrowY + 3f * density)
        path.close()
        canvas.drawPath(path, paint)

        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = foreground
        boldPaint.textSize = 9.5f * scaledDensity
        canvas.drawText(
            localized("重置宽容度", "Reset"),
            rect.centerX(),
            rect.bottom + 16f * density,
            boldPaint,
        )
    }

    private fun drawButton(canvas: Canvas, rect: RectF, label: String, active: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (active) inversePanel else panel
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = if (active) inversePanel else muted
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = if (active) background else foreground
        boldPaint.textSize = min(11f * scaledDensity, rect.height() * 0.30f)
        val availableWidth = (rect.width() - 10f * density).coerceAtLeast(1f)
        if (boldPaint.measureText(label) <= availableWidth) {
            centeredText(canvas, label, rect.centerX(), rect.centerY(), boldPaint)
        } else {
            boldPaint.textSize = min(10f * scaledDensity, rect.height() * 0.22f)
            val lines = splitButtonLabel(label)
            val lineHeight = boldPaint.textSize * 1.15f
            lines.forEachIndexed { index, line ->
                val fitted = TextUtils.ellipsize(line, boldPaint, availableWidth, TextUtils.TruncateAt.END)
                centeredText(
                    canvas,
                    fitted.toString(),
                    rect.centerX(),
                    rect.centerY() + (index - 0.5f) * lineHeight,
                    boldPaint,
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                moved = false
                touchTarget = targetAt(event.x, event.y)
                if (touchTarget == TouchTarget.SHADOW || touchTarget == TouchTarget.HIGHLIGHT) {
                    markerAnimator?.cancel()
                    displayedRange = session.range
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return touchTarget != TouchTarget.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - touchStartX) > touchSlop || abs(event.y - touchStartY) > touchSlop) moved = true
                val boundary = when (touchTarget) {
                    TouchTarget.SHADOW -> LatitudeBoundary.SHADOW
                    TouchTarget.HIGHLIGHT -> LatitudeBoundary.HIGHLIGHT
                    else -> null
                }
                if (boundary != null) {
                    val scale = latitudeScaleRect()
                    val left = scale.left
                    val right = scale.right
                    val zone = ((event.x - left) / (right - left) * 10.0).coerceIn(0.0, 10.0)
                    if (session.setBoundary(boundary, zone)) {
                        displayedRange = session.range
                        haptic()
                        syncAppliedIfNeeded()
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (!cancelled && !moved) {
                    performClick()
                    when (touchTarget) {
                        TouchTarget.BACK -> listener?.onBackToToolsRequested()
                        TouchTarget.CLOSE -> listener?.onCloseRequested()
                        TouchTarget.SELECT -> listener?.onFilmSelectionRequested()
                        TouchTarget.APPLY -> toggleApplied()
                        TouchTarget.RESET -> reset()
                        TouchTarget.RECORD -> recordCurrent()
                        else -> Unit
                    }
                }
                touchTarget = TouchTarget.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun targetAt(x: Float, y: Float): TouchTarget {
        if (geometry.back.contains(x, y)) return TouchTarget.BACK
        if (geometry.close.contains(x, y)) return TouchTarget.CLOSE
        if (geometry.selectFilm.contains(x, y)) return TouchTarget.SELECT
        if (geometry.apply.contains(x, y)) return TouchTarget.APPLY
        if (geometry.reset.contains(x, y)) return TouchTarget.RESET
        if (geometry.record.contains(x, y)) return TouchTarget.RECORD
        val scale = latitudeScaleRect()
        val left = scale.left
        val right = scale.right
        val shadowX = zoneX(left, right, session.range.lowerZone)
        val highlightX = zoneX(left, right, session.range.upperZone)
        val expanded = RectF(geometry.rail.left, geometry.rail.top, geometry.rail.right, geometry.rail.bottom)
        if (!expanded.contains(x, y)) return TouchTarget.NONE
        if (abs(shadowX - highlightX) < density) {
            return if (x < shadowX) TouchTarget.SHADOW else TouchTarget.HIGHLIGHT
        }
        return if (abs(x - shadowX) <= abs(x - highlightX)) TouchTarget.SHADOW else TouchTarget.HIGHLIGHT
    }

    private fun toggleApplied() {
        val enable = !session.applied
        session.setApplied(enable)
        if (enable) syncAppliedIfNeeded() else {
            repository.clearApplied()
            listener?.onAppliedLatitudeChanged(null)
        }
        haptic()
        invalidate()
    }

    private fun reset() {
        session.reset()
        animateToSessionRange()
        syncAppliedIfNeeded()
        haptic()
    }

    private fun recordCurrent() {
        val profile = repository.find(session.selectedFilmId)
        if (profile == null) {
            Toast.makeText(
                context,
                localized("请先选择胶片，再记录宽容度。", "Select a film before saving its latitude."),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        repository.saveOverride(profile.id, session.range)
        syncAppliedIfNeeded()
        haptic()
        Toast.makeText(
            context,
            localized("已记录当前宽容度。", "Current latitude saved."),
            Toast.LENGTH_SHORT,
        ).show()
        invalidate()
    }

    private fun syncAppliedIfNeeded() {
        if (!session.applied) return
        val film = repository.find(session.selectedFilmId)
        val applied = AppliedFilmLatitude(session.range, film?.id, film?.displayName)
        repository.saveApplied(applied)
        listener?.onAppliedLatitudeChanged(applied)
    }

    private fun animateToSessionRange() {
        val start = displayedRange
        val target = session.range
        markerAnimator?.cancel()
        if (!isLaidOut) {
            displayedRange = target
            invalidate()
            return
        }
        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction.toDouble()
                displayedRange = FilmLatitudeRange(
                    start.shadowEv + (target.shadowEv - start.shadowEv) * fraction,
                    start.highlightEv + (target.highlightEv - start.highlightEv) * fraction,
                )
                invalidate()
            }
            start()
        }
    }

    private fun zoneX(left: Float, right: Float, zone: Double): Float =
        left + ((zone.coerceIn(0.0, 10.0) / 10.0) * (right - left)).toFloat()

    private fun splitButtonLabel(label: String): List<String> {
        val words = label.split(' ').filter(String::isNotBlank)
        if (words.size > 1) {
            val split = (1 until words.size).minByOrNull { index ->
                abs(words.take(index).joinToString(" ").length - words.drop(index).joinToString(" ").length)
            } ?: 1
            return listOf(words.take(split).joinToString(" "), words.drop(split).joinToString(" "))
        }
        val middle = (label.length / 2).coerceAtLeast(1)
        return listOf(label.take(middle), label.drop(middle))
    }

    private fun signed(value: Double): String = when {
        value > 0.0 -> "+${decimal(value)}"
        else -> decimal(value)
    }

    private fun decimal(value: Double): String = if (abs(value % 1.0) < 0.001) {
        value.toInt().toString()
    } else {
        "%.1f".format(value)
    }

    private fun centeredText(canvas: Canvas, text: String, x: Float, y: Float, textPaint: Paint) {
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    private fun haptic() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHapticAt > 35L) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastHapticAt = now
        }
    }

    private fun updateContentDescription() {
        val film = repository.find(session.selectedFilmId)?.displayName ?: localized("未选择胶片", "No film selected")
        contentDescription = "$film, ${signed(session.range.shadowEv)} / ${signed(session.range.highlightEv)} EV"
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        markerAnimator?.cancel()
        markerAnimator = null
        super.onDetachedFromWindow()
    }
}
