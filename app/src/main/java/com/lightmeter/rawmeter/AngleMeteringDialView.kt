package com.lightmeter.rawmeter

import android.animation.ValueAnimator
import android.annotation.SuppressLint
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
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Compact angle badge that expands into a bounded radial detent dial. */
@SuppressLint("ViewConstructor")
internal class AngleMeteringDialView(
    context: Context,
    private val state: MeterState,
) : View(context) {
    var onAngleChanged: (() -> Unit)? = null
    var onExpandedChanged: ((Boolean) -> Unit)? = null

    private enum class Gesture { NONE, TOGGLE, DIAL }

    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val foreground: Int
        get() = if (state.isDarkMode) Color.rgb(222, 222, 218) else Color.rgb(20, 20, 20)
    private val surface: Int
        get() = if (state.isDarkMode) Color.rgb(28, 28, 27) else Color.WHITE
    private val muted: Int
        get() = if (state.isDarkMode) Color.rgb(92, 92, 89) else Color.rgb(188, 188, 184)
    private val red = Color.rgb(184, 31, 39)

    private var anchor = RectF()
    private var compactCenterOffsetX = 0f
    private var compactBounds = RectF()
    private var expandedCenterX = 0f
    private var expandedCenterY = 0f
    private var expandedRadius = 0f
    private var expansion = 0f
    private var expanded = false
    private var gesture = Gesture.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastTouchAngle = 0f
    private var dialAccumulatorDegrees = 0f
    private var moved = false
    private var expansionAnimator: ValueAnimator? = null
    private var settleAnimator: ValueAnimator? = null

    fun setAnchor(value: RectF, compactCenterOffsetX: Float = 0f) {
        anchor = RectF(value)
        this.compactCenterOffsetX = compactCenterOffsetX
        calculateGeometry()
        invalidate()
    }

    fun refreshSupport() {
        val maxIndex = maximumSupportedIndex()
        val currentIndex = selectedIndex()
        if (currentIndex > maxIndex) {
            state.selectAngleMeteringDegrees(AngleMeteringMath.selectableDegrees[maxIndex])
            dialAccumulatorDegrees = 0f
            onAngleChanged?.invoke()
        }
        calculateGeometry()
        invalidate()
    }

    fun collapse() {
        if (expanded || expansion > 0f) setExpanded(false)
    }

    fun isExpanded(): Boolean = expanded

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        calculateGeometry()
    }

    override fun onDraw(canvas: Canvas) {
        if (compactBounds.isEmpty) return
        val compactRadius = compactBounds.width() / 2f
        val centerX = lerp(compactBounds.centerX(), expandedCenterX, expansion)
        val centerY = lerp(compactBounds.centerY(), expandedCenterY, expansion)
        val radius = lerp(compactRadius, expandedRadius, expansion)

        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = red
        canvas.drawCircle(centerX, centerY, radius, paint)

        if (expansion > 0.02f) drawDialFace(canvas, centerX, centerY, radius)
        drawValue(canvas, centerX, centerY)
        drawCompactLabel(canvas)
    }

    private fun drawDialFace(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val values = AngleMeteringMath.selectableDegrees
        val selected = selectedIndex()
        val maxSupported = maximumSupportedIndex()
        val alpha = (255 * expansion).toInt().coerceIn(0, 255)
        values.indices.forEach { index ->
            val angleDegrees = -90f +
                (index - selected) * TICK_ANGLE_DEGREES + dialAccumulatorDegrees
            val radians = Math.toRadians(angleDegrees.toDouble())
            val supported = index <= maxSupported
            val selectedTick = index == selected && abs(dialAccumulatorDegrees) < 1.5f
            val inner = radius * if (selectedTick) 0.74f else 0.79f
            val outer = radius * 0.92f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (selectedTick) 2.2f * density else 1f * density
            paint.color = withAlpha(
                when {
                    !supported -> muted
                    selectedTick -> red
                    else -> foreground
                },
                alpha,
            )
            canvas.drawLine(
                centerX + cos(radians).toFloat() * inner,
                centerY + sin(radians).toFloat() * inner,
                centerX + cos(radians).toFloat() * outer,
                centerY + sin(radians).toFloat() * outer,
                paint,
            )
            if (index % 2 == 0 || index == selected || !supported) {
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 7.5f * scaledDensity
                paint.color = withAlpha(if (supported) foreground else muted, alpha)
                val labelRadius = radius * 0.64f
                val metrics = paint.fontMetrics
                canvas.drawText(
                    values[index].toString(),
                    centerX + cos(radians).toFloat() * labelRadius,
                    centerY + sin(radians).toFloat() * labelRadius -
                        (metrics.ascent + metrics.descent) / 2f,
                    paint,
                )
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(red, alpha)
        canvas.drawCircle(centerX, centerY - radius * 0.92f, 2.2f * density, paint)
    }

    private fun drawValue(canvas: Canvas, centerX: Float, centerY: Float) {
        boldPaint.color = foreground
        boldPaint.textSize = lerp(COMPACT_TEXT_SP, EXPANDED_TEXT_SP, expansion) * scaledDensity
        val metrics = boldPaint.fontMetrics
        canvas.drawText(
            "${state.angleMeteringDegrees}°",
            centerX,
            centerY - (metrics.ascent + metrics.descent) / 2f,
            boldPaint,
        )
    }

    private fun drawCompactLabel(canvas: Canvas) {
        if (expansion >= 0.98f) return
        val alpha = (255 * (1f - expansion)).roundToInt().coerceIn(0, 255)
        boldPaint.color = withAlpha(foreground, alpha)
        boldPaint.textSize = 9.5f * scaledDensity
        val label = if (state.menuLanguage == MenuLanguage.ENGLISH) "Metering angle" else "测光角度"
        val metrics = boldPaint.fontMetrics
        canvas.drawText(
            label,
            compactBounds.centerX(),
            compactBounds.bottom + 7f * density - (metrics.ascent + metrics.descent) / 2f,
            boldPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (compactBounds.isEmpty) return false
        val centerX = lerp(compactBounds.centerX(), expandedCenterX, expansion)
        val centerY = lerp(compactBounds.centerY(), expandedCenterY, expansion)
        val radius = lerp(compactBounds.width() / 2f, expandedRadius, expansion)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val distance = distance(event.x, event.y, centerX, centerY)
                val centerHit = distance <= compactBounds.width() * 0.62f
                gesture = when {
                    !expanded && compactBounds.contains(event.x, event.y) -> Gesture.TOGGLE
                    expanded && centerHit -> Gesture.TOGGLE
                    expanded && distance <= radius + 10f * density -> Gesture.DIAL
                    else -> Gesture.NONE
                }
                if (gesture == Gesture.NONE) return false
                downX = event.x
                downY = event.y
                moved = false
                if (gesture == Gesture.DIAL) {
                    settleAnimator?.cancel()
                    lastTouchAngle = angleFor(event.x, event.y, centerX, centerY)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (gesture == Gesture.NONE) return false
                if (!moved && (abs(event.x - downX) > touchSlop ||
                        abs(event.y - downY) > touchSlop)
                ) moved = true
                if (gesture == Gesture.DIAL && moved) {
                    updateDial(event.x, event.y, centerX, centerY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (gesture == Gesture.TOGGLE && !moved) {
                    performClick()
                    setExpanded(!expanded)
                } else if (gesture == Gesture.DIAL) {
                    settleDial()
                }
                finishGesture()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (gesture == Gesture.DIAL) settleDial()
                finishGesture()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        expansionAnimator?.cancel()
        settleAnimator?.cancel()
        expansionAnimator = null
        settleAnimator = null
        super.onDetachedFromWindow()
    }

    private fun updateDial(x: Float, y: Float, centerX: Float, centerY: Float) {
        val angle = angleFor(x, y, centerX, centerY)
        var delta = angle - lastTouchAngle
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        lastTouchAngle = angle
        dialAccumulatorDegrees += delta

        val current = selectedIndex()
        val maxSupported = maximumSupportedIndex()
        if ((current <= 0 && dialAccumulatorDegrees > 0f) ||
            (current >= maxSupported && dialAccumulatorDegrees < 0f)
        ) {
            dialAccumulatorDegrees = 0f
        }
        val detents = (dialAccumulatorDegrees / TICK_ANGLE_DEGREES).toInt()
        if (detents != 0) {
            val target = (current - detents).coerceIn(0, maxSupported)
            val appliedIndexDelta = target - current
            if (appliedIndexDelta != 0) {
                state.selectAngleMeteringDegrees(AngleMeteringMath.selectableDegrees[target])
                dialAccumulatorDegrees += appliedIndexDelta * TICK_ANGLE_DEGREES
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onAngleChanged?.invoke()
            } else {
                dialAccumulatorDegrees = 0f
            }
        }
        dialAccumulatorDegrees = dialAccumulatorDegrees.coerceIn(
            -TICK_ANGLE_DEGREES,
            TICK_ANGLE_DEGREES,
        )
        invalidate()
    }

    private fun settleDial() {
        val start = dialAccumulatorDegrees
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofFloat(start, 0f).apply {
            duration = 150L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                dialAccumulatorDegrees = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value && (value == (expansion >= 1f))) return
        expanded = value
        onExpandedChanged?.invoke(value)
        expansionAnimator?.cancel()
        expansionAnimator = ValueAnimator.ofFloat(expansion, if (value) 1f else 0f).apply {
            duration = 230L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                expansion = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun finishGesture() {
        gesture = Gesture.NONE
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun calculateGeometry() {
        if (width <= 0 || height <= 0 || anchor.isEmpty) return
        val margin = 2f * density
        val compactSize = 34f * density
        val gap = 2f * density
        // The angle selector belongs below the meter button. Do not fall back above it on
        // short portrait layouts: that position is reserved for the flash-distance selector.
        // The compact label only needs its actual line height, so the previous 18 dp reserve
        // unnecessarily forced both controls into the same location on tall phones.
        val labelReserve = 13f * density
        val compactTop = (anchor.bottom + gap).coerceAtMost(
            (height - margin - labelReserve - compactSize).coerceAtLeast(anchor.bottom),
        )
        val compactLeft = (anchor.centerX() + compactCenterOffsetX - compactSize / 2f)
            .coerceIn(margin, (width - margin - compactSize).coerceAtLeast(margin))
        compactBounds = RectF(
            compactLeft,
            compactTop,
            compactLeft + compactSize,
            compactTop + compactSize,
        )
        expandedRadius = min(
            88f * density,
            min(width * 0.22f, height * 0.23f),
        ).coerceAtLeast(compactSize / 2f)
        expandedCenterX = anchor.centerX().coerceIn(
            margin + expandedRadius,
            (width - margin - expandedRadius).coerceAtLeast(margin + expandedRadius),
        )
        expandedCenterY = anchor.centerY().coerceIn(
            margin + expandedRadius,
            (height - margin - expandedRadius).coerceAtLeast(margin + expandedRadius),
        )
    }

    private fun selectedIndex(): Int = AngleMeteringMath.selectableDegrees
        .indexOf(state.angleMeteringDegrees)
        .coerceAtLeast(0)

    private fun maximumSupportedIndex(): Int = AngleMeteringMath.maximumSupportedIndex(
        state.maximumAngleMeteringDegrees(),
    )

    private fun angleFor(x: Float, y: Float, centerX: Float, centerY: Float): Float =
        Math.toDegrees(atan2((y - centerY).toDouble(), (x - centerX).toDouble())).toFloat()

    private fun distance(x: Float, y: Float, centerX: Float, centerY: Float): Float =
        kotlin.math.hypot(x - centerX, y - centerY)

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha,
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    private companion object {
        private const val TICK_ANGLE_DEGREES = 18f
        private const val COMPACT_TEXT_SP = 11f
        private const val EXPANDED_TEXT_SP = 18f
    }
}
