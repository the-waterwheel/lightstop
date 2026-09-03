package com.lightmeter.rawmeter

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Long-exposure reciprocity calculator hosted in the same adaptive Tools panel as Latitude. */
@SuppressLint("ViewConstructor")
internal class ReciprocityView(
    context: Context,
    private val state: MeterState,
    private val latitudeRepository: FilmLatitudeRepository,
    private val repository: FilmReciprocityRepository,
) : View(context) {

    interface Listener {
        fun onBackToToolsRequested()
        fun onCloseRequested()
        fun onFilmSelectionRequested()
        fun onAppliedReciprocityChanged(method: ReciprocityMethod?)
    }

    var listener: Listener? = null

    private enum class TouchTarget { BACK, CLOSE, SCALE, SELECT, APPLY, NONE }

    private val density = resources.displayMetrics.density
    private val scaledDensity = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        1f,
        resources.displayMetrics,
    )
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val timeRenderer = ReciprocityTimeRenderer(density, scaledDensity)
    private val background: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val foreground: Int get() = if (state.isDarkMode) Color.rgb(218, 218, 214) else Color.rgb(20, 20, 20)
    private val muted: Int get() = if (state.isDarkMode) Color.rgb(70, 70, 68) else Color.rgb(166, 166, 162)
    private val secondaryStrong: Int get() = if (state.isDarkMode) Color.rgb(164, 164, 160) else Color.rgb(96, 96, 92)
    private val panel: Int get() = if (state.isDarkMode) Color.rgb(42, 42, 40) else Color.rgb(235, 235, 232)
    private val actionSurface: Int get() = if (state.isDarkMode) Color.rgb(24, 24, 22) else Color.WHITE
    private val actionActiveSurface: Int get() = if (state.isDarkMode) Color.rgb(72, 72, 68) else Color.rgb(218, 218, 214)
    private val actionText: Int get() = if (state.isDarkMode) foreground else Color.rgb(22, 22, 22)
    private val red = Color.rgb(205, 38, 45)

    private var geometry = ReciprocityGeometry.EMPTY
    private var selectedFilmId: String? = null
    private var applied = false
    private var initialized = false
    private var displayedCoordinate = 0.0
    private var coordinateAnimator: ValueAnimator? = null
    private var touchTarget = TouchTarget.NONE
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartCoordinate = 0.0
    private var moved = false
    private var lastHapticTick = Int.MIN_VALUE
    private var lastHapticAt = 0L

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun openPage(initialShutterCoordinate: Double) {
        coordinateAnimator?.cancel()
        val appliedFilmId = repository.appliedFilmId()
        selectedFilmId = appliedFilmId
            ?: latitudeRepository.lastSelectedFilmId()
            ?: latitudeRepository.loadApplied()?.filmId
            ?: repository.selectedFilmId()
        applied = appliedFilmId != null
        displayedCoordinate = ReciprocityShutterScale.nearestCoordinate(
            initialShutterCoordinate,
            state.shutterStep,
            currentMaximumInputSeconds(),
        )
        initialized = true
        invalidate()
    }

    fun resumePage() {
        if (!initialized) openPage(state.lockedShutterLogSeconds) else invalidate()
    }

    fun selectFilm(profile: FilmLatitudeProfile) {
        selectedFilmId = profile.id
        repository.saveSelectedFilmId(profile.id)
        if (applied) {
            repository.saveAppliedFilmId(profile.id)
            listener?.onAppliedReciprocityChanged(repository.methodForFilm(profile.id))
        }
        clampDisplayedCoordinate()
        haptic()
        invalidate()
    }

    fun onFilmReciprocityChanged(profile: FilmLatitudeProfile) {
        if (selectedFilmId != profile.id) return
        val method = repository.methodForFilm(profile.id)
        if (applied) {
            if (method?.hasCalculationData == true) {
                listener?.onAppliedReciprocityChanged(method)
            } else {
                applied = false
                repository.clearApplied()
                listener?.onAppliedReciprocityChanged(null)
            }
        }
        clampDisplayedCoordinate()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        geometry = ReciprocityGeometryCalculator.calculate(width, height, density)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        if (!initialized) openPage(state.lockedShutterLogSeconds)
        clampDisplayedCoordinate()
        drawHeader(canvas)
        drawShutterScale(canvas)
        drawFilmCard(canvas)
        drawResult(canvas)
        drawApplyButton(canvas)
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
        centeredText(canvas, localized("倒易率计算", "Reciprocity"), width / 2f, y, boldPaint)
        paint.style = Paint.Style.FILL
        paint.color = muted
        canvas.drawRect(0f, geometry.back.bottom - density, width.toFloat(), geometry.back.bottom, paint)
    }

    private fun drawShutterScale(canvas: Canvas) {
        val rect = geometry.scale
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRect(rect, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f * density
        paint.color = foreground
        canvas.drawRect(rect, paint)

        val titleWidth = titleWidth(rect)
        val content = if (state.isLeftHanded) {
            RectF(rect.left + 5f * density, rect.top, rect.right - titleWidth, rect.bottom)
        } else {
            RectF(rect.left + titleWidth, rect.top, rect.right - 5f * density, rect.bottom)
        }
        val titleLeft = if (state.isLeftHanded) rect.right - titleWidth else rect.left
        val seconds = currentSeconds()
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = foreground
        boldPaint.textSize = 13f * scaledDensity
        centeredText(canvas, "s", titleLeft + titleWidth * 0.20f, rect.centerY(), boldPaint)
        boldPaint.textSize = 12.5f * scaledDensity
        centeredText(
            canvas,
            ReciprocityTimeFormatter.scaleLabel(seconds),
            titleLeft + titleWidth * 0.64f,
            rect.centerY(),
            boldPaint,
        )

        val baselineY = rect.centerY() + 13f * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * density
        paint.color = muted
        canvas.drawLine(content.left, baselineY, content.right, baselineY, paint)
        val pixelsPerStop = pixelsPerStop(content)
        val threshold = calculationThreshold()
        val preThresholdColor = if (state.isDarkMode) Color.rgb(62, 62, 60) else Color.rgb(158, 158, 154)
        val calculatedColor = if (state.isDarkMode) Color.rgb(204, 204, 200) else Color.rgb(20, 20, 20)

        canvas.save()
        canvas.clipRect(content)
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 8.5f * scaledDensity
        var lastLabelRight = content.left - 5f * density
        currentTicks().forEach { tick ->
            val x = content.centerX() + ((tick.coordinate - displayedCoordinate) * pixelsPerStop).toFloat()
            if (x !in content.left..content.right) return@forEach
            val color = if (tick.nominalSeconds + 1e-9 >= threshold) calculatedColor else preThresholdColor
            val label = tick.takeIf { it.major }?.let {
                ReciprocityTimeFormatter.scaleLabel(it.nominalSeconds)
            }
            val halfLabel = label?.let { paint.measureText(it) / 2f } ?: 0f
            val showLabel = label != null &&
                x - halfLabel >= lastLabelRight + 4f * density &&
                x + halfLabel <= content.right
            drawScaleTick(canvas, x, baselineY, if (showLabel) label else null, color)
            if (showLabel) lastLabelRight = x + halfLabel
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.color = red
        canvas.drawLine(content.centerX(), baselineY - 14f * density, content.centerX(), baselineY + 4f * density, paint)
        canvas.restore()
    }

    private fun drawScaleTick(canvas: Canvas, x: Float, baselineY: Float, label: String?, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = color
        val height = if (label == null) 7f * density else 11f * density
        canvas.drawLine(x, baselineY - height, x, baselineY + 2f * density, paint)
        if (label != null) {
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = 8.5f * scaledDensity
            paint.color = color
            centeredText(canvas, label, x, baselineY - 18f * density, paint)
        }
    }

    private fun drawFilmCard(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawRoundRect(geometry.filmCard, 6f * density, 6f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = muted
        canvas.drawRoundRect(geometry.filmCard, 6f * density, 6f * density, paint)

        val selected = latitudeRepository.find(selectedFilmId)
        val display = selected ?: latitudeRepository.films().firstOrNull()
        val method = repository.methodForFilm(selected?.id)
        val x = geometry.filmCard.left + 9f * density
        val available = (geometry.filmCard.width() - 18f * density).coerceAtLeast(1f)
        val centerY = geometry.filmCard.centerY()
        val details = when {
            selected == null -> localized("未选择", "Not selected")
            method?.hasCalculationData != true -> localized("无倒易率数据", "No reciprocity data")
            else -> selected.iso?.let { "ISO $it" }.orEmpty()
        }
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = 12.5f * scaledDensity
        boldPaint.color = if (selected == null) muted else foreground
        val name = display?.displayName ?: localized("没有胶片数据", "No film data")
        canvas.drawText(
            TextUtils.ellipsize(name, boldPaint, available, TextUtils.TruncateAt.END).toString(),
            x,
            if (details.isBlank()) centerY + 4f * density else centerY - 3f * density,
            boldPaint,
        )
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 9f * scaledDensity
        textPaint.color = if (selected == null) muted else foreground
        if (details.isNotBlank()) {
            canvas.drawText(
                TextUtils.ellipsize(details, textPaint, available, TextUtils.TruncateAt.END).toString(),
                x,
                centerY + 15f * density,
                textPaint,
            )
        }
        drawNeutralButton(geometry.selectFilm, localized("选择胶片", "Select film"), canvas)
    }

    private fun drawResult(canvas: Canvas) {
        val rect = geometry.result
        val result = currentResult()
        val readout = result.correctedSeconds?.let(ReciprocityTimeReadout::from)
        val valueY = rect.centerY() + 5f * density
        if (readout == null) {
            boldPaint.textAlign = Paint.Align.CENTER
            boldPaint.color = foreground
            boldPaint.textSize = min(32f * scaledDensity, rect.height() * 0.48f)
            centeredText(canvas, "--:--", rect.centerX(), valueY, boldPaint)
        } else {
            timeRenderer.draw(canvas, rect, readout, foreground, secondaryStrong)
        }

        if (result.estimated) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 8.5f * scaledDensity
            textPaint.color = red
            val warning = localized("超出精确范围，仅为估算", "Outside exact range · estimate only")
            val fittedWarning = TextUtils.ellipsize(
                warning,
                textPaint,
                (rect.width() - 8f * density).coerceAtLeast(1f),
                TextUtils.TruncateAt.END,
            )
            centeredText(
                canvas,
                fittedWarning.toString(),
                rect.centerX(),
                max(rect.top + 10f * density, valueY - 34f * density),
                textPaint,
            )
        }

        val detail = result.filter?.let { localized("滤镜 $it", "Filter $it") } ?: when (result.status) {
            ReciprocityStatus.UNAVAILABLE -> localized("没有可靠倒易率数据", "No reliable reciprocity data")
            ReciprocityStatus.OUT_OF_RANGE -> localized("超出厂商建议范围", "Outside manufacturer range")
            ReciprocityStatus.ESTIMATED -> ""
            else -> ""
        }
        if (detail.isNotEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 8.5f * scaledDensity
            textPaint.color = if (result.filter == null) red else foreground
            val fitted = TextUtils.ellipsize(
                detail,
                textPaint,
                (rect.width() - 8f * density).coerceAtLeast(1f),
                TextUtils.TruncateAt.END,
            )
            centeredText(canvas, fitted.toString(), rect.centerX(), rect.bottom - 10f * density, textPaint)
        }
    }

    private fun drawApplyButton(canvas: Canvas) {
        val rect = geometry.apply
        paint.style = Paint.Style.FILL
        paint.color = if (applied) actionActiveSurface else actionSurface
        canvas.drawRoundRect(rect, 7f * density, 7f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * density
        paint.color = red
        canvas.drawRoundRect(rect, 7f * density, 7f * density, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = actionText
        boldPaint.textSize = min(13f * scaledDensity, rect.height() * 0.23f)
        val label = if (applied) {
            localized("取消测光补偿显示", "Hide compensation on meter")
        } else {
            localized("补偿显示到测光", "Show compensation on meter")
        }
        drawWrappedCenteredText(canvas, label, rect, boldPaint)
    }

    private fun drawNeutralButton(rect: RectF, label: String, canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = muted
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = foreground
        boldPaint.textSize = min(12f * scaledDensity, rect.height() * 0.30f)
        drawWrappedCenteredText(canvas, label, rect, boldPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchTarget = targetAt(event.x, event.y)
                touchStartX = event.x
                touchStartY = event.y
                touchStartCoordinate = displayedCoordinate
                moved = false
                if (touchTarget == TouchTarget.SCALE) {
                    coordinateAnimator?.cancel()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastHapticTick = nearestTickIndex(displayedCoordinate)
                }
                return touchTarget != TouchTarget.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - touchStartX) > touchSlop || abs(event.y - touchStartY) > touchSlop) moved = true
                if (touchTarget == TouchTarget.SCALE) {
                    val content = scaleContent()
                    val all = currentTicks()
                    displayedCoordinate = (
                        touchStartCoordinate - (event.x - touchStartX) / pixelsPerStop(content)
                        ).coerceIn(all.first().coordinate, all.last().coordinate)
                    val tick = nearestTickIndex(displayedCoordinate)
                    if (tick != lastHapticTick) {
                        haptic()
                        lastHapticTick = tick
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (touchTarget == TouchTarget.SCALE) {
                    snapToNearestTick()
                } else if (!cancelled && !moved) {
                    performClick()
                    when (touchTarget) {
                        TouchTarget.BACK -> listener?.onBackToToolsRequested()
                        TouchTarget.CLOSE -> listener?.onCloseRequested()
                        TouchTarget.SELECT -> listener?.onFilmSelectionRequested()
                        TouchTarget.APPLY -> toggleApplied()
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

    private fun targetAt(x: Float, y: Float): TouchTarget = when {
        geometry.back.contains(x, y) -> TouchTarget.BACK
        geometry.close.contains(x, y) -> TouchTarget.CLOSE
        geometry.scale.contains(x, y) -> TouchTarget.SCALE
        geometry.selectFilm.contains(x, y) -> TouchTarget.SELECT
        geometry.apply.contains(x, y) -> TouchTarget.APPLY
        else -> TouchTarget.NONE
    }

    private fun toggleApplied() {
        if (applied) {
            applied = false
            repository.clearApplied()
            listener?.onAppliedReciprocityChanged(null)
        } else {
            val filmId = selectedFilmId
            val method = repository.methodForFilm(filmId)
            if (filmId == null || method?.hasCalculationData != true) {
                Toast.makeText(
                    context,
                    localized("所选胶片没有可靠倒易率数据。", "The selected film has no reliable reciprocity data."),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            applied = true
            repository.saveAppliedFilmId(filmId)
            listener?.onAppliedReciprocityChanged(method)
        }
        haptic()
        invalidate()
    }

    private fun snapToNearestTick() {
        val target = ReciprocityShutterScale.nearestCoordinate(
            displayedCoordinate,
            state.shutterStep,
            currentMaximumInputSeconds(),
        )
        val start = displayedCoordinate
        coordinateAnimator?.cancel()
        coordinateAnimator = ValueAnimator.ofFloat(start.toFloat(), target.toFloat()).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayedCoordinate = (it.animatedValue as Float).toDouble()
                invalidate()
            }
            start()
        }
    }

    private fun currentMethod(): ReciprocityMethod? = repository.methodForFilm(selectedFilmId)

    private fun currentSeconds(): Double = ReciprocityShutterScale.valueForCoordinate(
        displayedCoordinate,
        state.shutterStep,
        currentMaximumInputSeconds(),
    )

    private fun currentResult(): ReciprocityResult = ReciprocityMath.calculate(currentMethod(), currentSeconds())

    private fun calculationThreshold(): Double = currentMethod()
        ?.takeIf { it.type in CALCULATED_METHOD_TYPES }
        ?.noCompensationSeconds
        ?: Double.POSITIVE_INFINITY

    private fun scaleContent(): RectF {
        val rect = geometry.scale
        val titleWidth = titleWidth(rect)
        return if (state.isLeftHanded) {
            RectF(rect.left + 5f * density, rect.top, rect.right - titleWidth, rect.bottom)
        } else {
            RectF(rect.left + titleWidth, rect.top, rect.right - 5f * density, rect.bottom)
        }
    }

    private fun pixelsPerStop(content: RectF): Double = max(58f * density, content.width() / 4.8f).toDouble()

    private fun nearestTickIndex(coordinate: Double): Int = currentTicks()
        .let { ticks ->
            ticks.indices.minByOrNull { abs(ticks[it].coordinate - coordinate) } ?: 0
        }

    private fun drawWrappedCenteredText(canvas: Canvas, label: String, rect: RectF, textPaint: TextPaint) {
        val available = (rect.width() - 10f * density).coerceAtLeast(1f)
        if (textPaint.measureText(label) <= available) {
            centeredText(canvas, label, rect.centerX(), rect.centerY(), textPaint)
            return
        }
        val words = label.split(' ').filter(String::isNotBlank)
        val lines = if (words.size > 1) {
            val split = (1 until words.size).minByOrNull { index ->
                abs(words.take(index).joinToString(" ").length - words.drop(index).joinToString(" ").length)
            } ?: 1
            listOf(words.take(split).joinToString(" "), words.drop(split).joinToString(" "))
        } else {
            val split = (label.length / 2).coerceAtLeast(1)
            listOf(label.take(split), label.drop(split))
        }
        val lineHeight = textPaint.textSize * 1.18f
        lines.forEachIndexed { index, line ->
            val fitted = TextUtils.ellipsize(line, textPaint, available, TextUtils.TruncateAt.END)
            centeredText(canvas, fitted.toString(), rect.centerX(), rect.centerY() + (index - 0.5f) * lineHeight, textPaint)
        }
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
        val film = latitudeRepository.find(selectedFilmId)?.displayName
            ?: localized("未选择胶片", "No film selected")
        val readout = currentResult().correctedSeconds?.let(ReciprocityTimeReadout::from)
        val result = readout?.spoken(state.menuLanguage == MenuLanguage.CHINESE) ?: "--:--"
        contentDescription = "$film, $result"
    }

    private fun currentMaximumInputSeconds(): Double = ReciprocityLimitPolicy.maximumInputSeconds(
        currentMethod(),
        state.shutterStep,
    )

    private fun currentTicks(): List<ReciprocityShutterTick> = ReciprocityShutterScale.ticks(
        state.shutterStep,
        currentMaximumInputSeconds(),
    )

    private fun clampDisplayedCoordinate() {
        val ticks = currentTicks()
        displayedCoordinate = displayedCoordinate.coerceIn(ticks.first().coordinate, ticks.last().coordinate)
    }

    private fun titleWidth(rect: RectF): Float = min(82f * density, rect.width() * 0.26f)

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        coordinateAnimator?.cancel()
        coordinateAnimator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        val CALCULATED_METHOD_TYPES = setOf(
            ReciprocityMethodType.TABLE,
            ReciprocityMethodType.POWER,
            ReciprocityMethodType.FIXED_EV,
            ReciprocityMethodType.BOUNDED_UNCHANGED,
        )
    }
}
