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

/** Compact distance badge; equal-angle detents select the non-linear [FlashDistanceScale]. */
@SuppressLint("ViewConstructor")
internal class FlashDistanceDialView(
    context: Context,
    private val state: MeterState,
) : View(context) {
    var onDistanceChanged: ((Double?) -> Unit)? = null
    var onExpandedChanged: ((Boolean) -> Unit)? = null

    private enum class Gesture { NONE, TOGGLE, DIAL }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
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
    private val foreground: Int get() = if (state.isDarkMode) Color.rgb(222, 222, 218) else Color.rgb(20, 20, 20)
    private val surface: Int get() = if (state.isDarkMode) Color.rgb(28, 28, 27) else Color.WHITE
    private val blue = Color.rgb(38, 112, 184)

    private var configuration: FlashConfiguration? = null
    private var distanceState = DistanceMeasurementState()
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

    fun setConfiguration(value: FlashConfiguration?, distanceState: DistanceMeasurementState) {
        configuration = value
        this.distanceState = distanceState
        invalidate()
    }

    fun collapse() {
        if (expanded || expansion > 0f) setExpanded(false)
    }

    fun isExpanded(): Boolean = expanded

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = calculateGeometry()

    override fun onDraw(canvas: Canvas) {
        if (compactBounds.isEmpty || configuration == null) return
        val compactRadius = compactBounds.width() / 2f
        val centerX = lerp(compactBounds.centerX(), expandedCenterX, expansion)
        val centerY = lerp(compactBounds.centerY(), expandedCenterY, expansion)
        val radius = lerp(compactRadius, expandedRadius, expansion)
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = blue
        canvas.drawCircle(centerX, centerY, radius, paint)
        if (expansion > 0.02f) drawDialFace(canvas, centerX, centerY, radius)
        drawValue(canvas, centerX, centerY)
        drawCompactLabel(canvas)
    }

    private fun drawDialFace(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val selected = selectedIndex()
        val alpha = (255 * expansion).toInt().coerceIn(0, 255)
        FlashDistanceScale.meters.indices.forEach { index ->
            val angleDegrees = -90f + (index - selected) * TICK_ANGLE_DEGREES + dialAccumulatorDegrees
            val radians = Math.toRadians(angleDegrees.toDouble())
            val selectedTick = index == selected && abs(dialAccumulatorDegrees) < 1.5f
            val inner = radius * if (selectedTick) 0.74f else 0.80f
            val outer = radius * 0.92f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (selectedTick) 2.2f * density else density
            paint.color = withAlpha(if (selectedTick) blue else foreground, alpha)
            canvas.drawLine(
                centerX + cos(radians).toFloat() * inner,
                centerY + sin(radians).toFloat() * inner,
                centerX + cos(radians).toFloat() * outer,
                centerY + sin(radians).toFloat() * outer,
                paint,
            )
            val angleFromPointer = signedAngleDistance(angleDegrees, -90f)
            val nearSelectedPointer = index != selected && abs(angleFromPointer) < LABEL_CLEARANCE_DEGREES
            val duplicateAutomaticNeighbor = index == 1 && selected != 1
            if ((index == selected || FlashDistanceScale.isMajorIndex(index)) &&
                !nearSelectedPointer && !duplicateAutomaticNeighbor
            ) {
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 7.8f * scaledDensity
                paint.color = withAlpha(foreground, alpha)
                val labelRadius = radius * 0.63f
                val metrics = paint.fontMetrics
                canvas.drawText(
                    compactLabel(FlashDistanceScale.meters[index]),
                    centerX + cos(radians).toFloat() * labelRadius,
                    centerY + sin(radians).toFloat() * labelRadius - (metrics.ascent + metrics.descent) / 2f,
                    paint,
                )
            }
        }
        // The fixed blue pointer overlays the selected scale tick when a detent settles.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f * density
        paint.color = withAlpha(blue, alpha)
        canvas.drawLine(
            centerX,
            centerY - radius * 0.73f,
            centerX,
            centerY - radius * 0.94f,
            paint,
        )
    }

    private fun drawValue(canvas: Canvas, centerX: Float, centerY: Float) {
        boldPaint.color = foreground
        val configuration = configuration ?: return
        if (configuration.isAutoDistance) {
            boldPaint.textSize = lerp(9f, 15.5f, expansion) * scaledDensity
            centeredText(canvas, "Auto", centerX, centerY - lerp(5f, 10f, expansion) * density, boldPaint)
            boldPaint.textSize = lerp(7.5f, 12.5f, expansion) * scaledDensity
            val measured = when (val value = distanceState.estimate?.takeIf { it.isFresh }?.meters) {
                null -> "--"
                else -> compactLabel(value)
            }
            centeredText(canvas, measured, centerX, centerY + lerp(6f, 12f, expansion) * density, boldPaint)
        } else {
            boldPaint.textSize = lerp(9f, 18f, expansion) * scaledDensity
            centeredText(canvas, compactLabel(configuration.distanceMeters), centerX, centerY, boldPaint)
        }
    }

    private fun drawCompactLabel(canvas: Canvas) {
        if (expansion >= 0.98f) return
        val alpha = (255 * (1f - expansion)).roundToInt().coerceIn(0, 255)
        boldPaint.color = withAlpha(foreground, alpha)
        boldPaint.textSize = 9.5f * scaledDensity
        centeredText(
            canvas,
            if (state.menuLanguage == MenuLanguage.ENGLISH) "Flash distance" else "闪光距离",
            compactBounds.centerX(),
            compactBounds.top - 8f * density,
            boldPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (compactBounds.isEmpty || configuration == null) return false
        val centerX = lerp(compactBounds.centerX(), expandedCenterX, expansion)
        val centerY = lerp(compactBounds.centerY(), expandedCenterY, expansion)
        val radius = lerp(compactBounds.width() / 2f, expandedRadius, expansion)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val distance = kotlin.math.hypot(event.x - centerX, event.y - centerY)
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
                if (!moved && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) moved = true
                if (gesture == Gesture.DIAL && moved) updateDial(event.x, event.y, centerX, centerY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (gesture == Gesture.TOGGLE && !moved) {
                    performClick()
                    setExpanded(!expanded)
                } else if (gesture == Gesture.DIAL) settleDial()
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

    private fun updateDial(x: Float, y: Float, centerX: Float, centerY: Float) {
        val angle = angleFor(x, y, centerX, centerY)
        var delta = angle - lastTouchAngle
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        lastTouchAngle = angle
        dialAccumulatorDegrees += delta
        val current = selectedIndex()
        if ((current <= 0 && dialAccumulatorDegrees > 0f) ||
            (current >= FlashDistanceScale.meters.lastIndex && dialAccumulatorDegrees < 0f)
        ) dialAccumulatorDegrees = 0f
        val detents = (dialAccumulatorDegrees / TICK_ANGLE_DEGREES).toInt()
        if (detents != 0) {
            val target = (current - detents).coerceIn(0, FlashDistanceScale.meters.lastIndex)
            val appliedDelta = target - current
            if (appliedDelta != 0) {
                configuration = configuration!!.copy(distanceMeters = FlashDistanceScale.meters[target])
                dialAccumulatorDegrees += appliedDelta * TICK_ANGLE_DEGREES
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onDistanceChanged?.invoke(FlashDistanceScale.meters[target])
            } else dialAccumulatorDegrees = 0f
        }
        dialAccumulatorDegrees = dialAccumulatorDegrees.coerceIn(-TICK_ANGLE_DEGREES, TICK_ANGLE_DEGREES)
        invalidate()
    }

    private fun settleDial() {
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofFloat(dialAccumulatorDegrees, 0f).apply {
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
        if (expanded == value && value == (expansion >= 1f)) return
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

    private fun calculateGeometry() {
        if (width <= 0 || height <= 0 || anchor.isEmpty) return
        val margin = 10f * density
        val compactSize = 34f * density
        val gap = 5f * density
        val labelReserve = 18f * density
        // Keep both metering accessories in the row above the meter button. Falling below used
        // to collide with the parameter-record slider and made the two modes place controls on
        // different sides of their primary action.
        val compactTop = (anchor.top - gap - compactSize).coerceAtLeast(
            margin + labelReserve,
        )
        val compactLeft = (anchor.centerX() + compactCenterOffsetX - compactSize / 2f)
            .coerceIn(margin, (width - margin - compactSize).coerceAtLeast(margin))
        compactBounds = RectF(compactLeft, compactTop, compactLeft + compactSize, compactTop + compactSize)
        expandedRadius = min(88f * density, min(width * 0.22f, height * 0.23f)).coerceAtLeast(compactSize / 2f)
        expandedCenterX = anchor.centerX().coerceIn(
            margin + expandedRadius,
            (width - margin - expandedRadius).coerceAtLeast(margin + expandedRadius),
        )
        // Keep the enlarged dial entirely above the primary action and its recording slider.
        // The compact badge and expanded dial therefore share the same lower visual edge.
        expandedCenterY = (anchor.top - gap - expandedRadius).coerceIn(
            margin + expandedRadius,
            (height - margin - expandedRadius).coerceAtLeast(margin + expandedRadius),
        )
    }

    private fun selectedIndex(): Int = FlashDistanceScale.nearestIndex(configuration?.distanceMeters)

    private fun compactLabel(value: Double?): String = when {
        value == null -> "A"
        value < 1.0 -> "%.2fm".format(java.util.Locale.US, value)
        value < 10.0 -> "%.1fm".format(java.util.Locale.US, value)
        else -> "%.0fm".format(java.util.Locale.US, value)
    }

    private fun signedAngleDistance(value: Float, target: Float): Float {
        var delta = (value - target) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    private fun centeredText(canvas: Canvas, text: String, x: Float, y: Float, textPaint: Paint) {
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    private fun angleFor(x: Float, y: Float, centerX: Float, centerY: Float): Float =
        Math.toDegrees(atan2((y - centerY).toDouble(), (x - centerX).toDouble())).toFloat()

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

    private fun finishGesture() {
        gesture = Gesture.NONE
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        expansionAnimator?.cancel()
        settleAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TICK_ANGLE_DEGREES = 6f
        const val LABEL_CLEARANCE_DEGREES = 25f
    }
}
