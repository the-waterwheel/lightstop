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
import android.util.TypedValue
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

/** Interactive depth-of-field calculator hosted over the Tools grid. */
@SuppressLint("ViewConstructor")
class DepthOfFieldView(
    context: Context,
    private val state: MeterState,
) : View(context) {

    interface Listener {
        fun onBackToToolsRequested()
        fun onCloseRequested()
    }

    var listener: Listener? = null

    private enum class TouchTarget {
        BACK, CLOSE, FRAME, COC, APERTURE, FOCAL_LENGTH, FOCUS_RULER, FOCUS_VALUE, NONE
    }

    private val density = resources.displayMetrics.density
    private val scaledDensity = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        1f,
        resources.displayMetrics,
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val path = Path()
    private var geometry = DepthOfFieldGeometry.EMPTY
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val background: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val foreground: Int get() = if (state.isDarkMode) Color.rgb(224, 224, 220) else Color.rgb(20, 20, 20)
    private val muted: Int get() = if (state.isDarkMode) Color.rgb(96, 96, 92) else Color.rgb(190, 190, 186)
    private val secondaryStrong: Int get() = if (state.isDarkMode) Color.rgb(164, 164, 160) else Color.rgb(106, 106, 102)
    private val panel: Int get() = if (state.isDarkMode) Color.rgb(48, 48, 46) else Color.rgb(224, 224, 220)
    private val red = Color.rgb(211, 43, 43)

    private val session = DepthOfFieldSession()
    private val dialogs = DepthOfFieldDialogs(
        context = context,
        state = state,
        onFrameSelected = {
            session.selectFrame(it)
            animateCurrentResult()
        },
        onCircleOfConfusionSelected = {
            session.selectCircleOfConfusion(it)
            animateCurrentResult()
        },
        onFocusDistanceSelected = {
            session.selectFocusDistance(it)
            animateCurrentResult()
        },
    )
    private var markerAnimator: ValueAnimator? = null
    private var displayedNearFraction = 0.0
    private var displayedFocusFraction = 0.0
    private var displayedFarFraction = 1.0

    private var touchTarget = TouchTarget.NONE
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastDialAngle = 0f
    private var dialStepAccumulator = 0f
    private var apertureVisualOffset = 0f
    private var focalVisualOffset = 0f
    private var moved = false

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Starts a fresh calculator page using the controls visible on the metering screen. */
    fun openWithMeterDefaults(apertureStop: Double) {
        session.reset(
            frameFormat = state.frameFormat,
            aperture = ExposureMath.apertureValueForCoordinate(apertureStop, state.apertureStep),
            fullFrameEquivalentMm = state.equivalent35mm()?.toDouble() ?: 50.0,
        )
        updateMarkers(animate = false)
    }

    /** Keeps user selections when the entire Tools stack is hidden and shown again. */
    fun resumePage() {
        if (!session.initialized) {
            openWithMeterDefaults(state.lockedApertureStop)
            return
        }
        updateMarkers(animate = true)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        geometry = DepthOfFieldGeometryCalculator.calculate(width, height, density)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        if (!session.initialized) openWithMeterDefaults(state.lockedApertureStop)
        drawHeader(canvas)
        drawDistanceRuler(canvas)
        drawControls(canvas)
        updateContentDescription()
    }

    private fun drawHeader(canvas: Canvas) {
        val centerY = geometry.headerBack.centerY()
        paint.color = foreground
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * density
        canvas.drawLine(
            geometry.headerBack.centerX() + 5f * density,
            centerY - 8f * density,
            geometry.headerBack.centerX() - 4f * density,
            centerY,
            paint,
        )
        canvas.drawLine(
            geometry.headerBack.centerX() - 4f * density,
            centerY,
            geometry.headerBack.centerX() + 5f * density,
            centerY + 8f * density,
            paint,
        )
        val closeX = geometry.headerClose.centerX()
        canvas.drawLine(closeX - 7f * density, centerY - 7f * density, closeX + 7f * density, centerY + 7f * density, paint)
        canvas.drawLine(closeX + 7f * density, centerY - 7f * density, closeX - 7f * density, centerY + 7f * density, paint)
        boldPaint.color = foreground
        boldPaint.textSize = 14f * scaledDensity
        boldPaint.textAlign = Paint.Align.CENTER
        drawTextCentered(
            canvas,
            localized("景深计算", "Depth of field"),
            width / 2f,
            centerY,
            boldPaint,
        )
        paint.style = Paint.Style.FILL
        paint.color = muted
        canvas.drawRect(0f, geometry.headerBack.bottom - density, width.toFloat(), geometry.headerBack.bottom, paint)
    }

    private fun drawDistanceRuler(canvas: Canvas) {
        val lineLeft = geometry.ruler.left + 17f * density
        val lineRight = geometry.ruler.right - 17f * density
        val rulerY = geometry.ruler.top + 24f * density
        val lineWidth = lineRight - lineLeft

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = foreground
        canvas.drawLine(lineLeft, rulerY, lineRight, rulerY, paint)
        val tickDistances = listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 50.0, null)
        tickDistances.forEach { distance ->
            val x = lineLeft + lineWidth * DepthOfFieldMath.distanceFraction(distance).toFloat()
            val tall = distance == 1.0 || distance == 10.0 || distance == null
            canvas.drawLine(x, rulerY - if (tall) 7f * density else 4f * density, x, rulerY + if (tall) 7f * density else 4f * density, paint)
        }

        val nearX = lineLeft + lineWidth * displayedNearFraction.toFloat()
        val focusX = lineLeft + lineWidth * displayedFocusFraction.toFloat()
        val farX = lineLeft + lineWidth * displayedFarFraction.toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 9f * density
        paint.color = muted
        canvas.drawLine(nearX, rulerY, farX, rulerY, paint)
        paint.strokeWidth = 2.4f * density
        paint.color = red
        canvas.drawLine(nearX, rulerY - 14f * density, nearX, rulerY + 14f * density, paint)
        canvas.drawLine(farX, rulerY - 14f * density, farX, rulerY + 14f * density, paint)
        paint.strokeWidth = 3f * density
        paint.color = foreground
        canvas.drawLine(focusX, rulerY - 18f * density, focusX, rulerY + 18f * density, paint)
        paint.color = red
        canvas.drawCircle(focusX, rulerY - 18f * density, 3.2f * density, paint)

        // Keep the pictograms inside the ruler region: clear of both the header and the scale.
        drawFlower(canvas, lineLeft, rulerY - 18f * density)
        drawMountain(canvas, lineRight, rulerY - 15f * density)

        val labelY = geometry.ruler.top + min(91f * density, geometry.ruler.height() * 0.64f)
        val labelCenters = floatArrayOf(
            geometry.ruler.left + geometry.ruler.width() * 0.16f,
            geometry.ruler.centerX(),
            geometry.ruler.right - geometry.ruler.width() * 0.16f,
        )
        drawConnector(canvas, nearX, rulerY + 15f * density, labelCenters[0], labelY - 17f * density, red)
        drawConnector(canvas, focusX, rulerY + 19f * density, labelCenters[1], labelY - 17f * density, foreground)
        drawConnector(canvas, farX, rulerY + 15f * density, labelCenters[2], labelY - 17f * density, red)

        val current = session.result
        drawDistanceLabel(canvas, localized("近界", "Near"), formatDistance(current?.nearLimitM), labelCenters[0], labelY, red)
        drawDistanceLabel(canvas, localized("对焦", "Focus"), formatDistance(focusDistanceM()), labelCenters[1], labelY, foreground)
        drawDistanceLabel(canvas, localized("远界", "Far"), formatDistance(current?.farLimitM, infinityWhenNull = current != null), labelCenters[2], labelY, red)

        val summaryY = geometry.ruler.bottom - 9f * density
        paint.style = Paint.Style.FILL
        paint.color = foreground
        paint.textSize = 10f * scaledDensity
        paint.textAlign = Paint.Align.CENTER
        val focal = focalLengthMm()
        val summary = if (current == null) {
            localized("当前组合无法计算", "Combination unavailable")
        } else {
            localized(
                "镜头 ${formatMillimetres(focal)} · 超焦距 ${formatDistance(current.hyperfocalDistanceM)}",
                "Lens ${formatMillimetres(focal)} · H ${formatDistance(current.hyperfocalDistanceM)}",
            )
        }
        canvas.drawText(summary, geometry.ruler.centerX(), summaryY, paint)
    }

    private fun drawControls(canvas: Canvas) {
        drawDial(
            canvas = canvas,
            bounds = geometry.apertureDial,
            caption = localized("光圈", "Aperture"),
            value = "f/${formatAperture(session.selectedAperture)}",
            visualOffset = apertureVisualOffset,
        )
        drawDial(
            canvas = canvas,
            bounds = geometry.focalDial,
            caption = localized("焦距", "Focal length"),
            value = formatMillimetres(focalLengthMm()),
            visualOffset = focalVisualOffset,
        )

        val frameLabel = if (session.selectedFormat.id == "custom") {
            session.selectedFormat.displayLabel(state.menuLanguage)
        } else {
            session.selectedFormat.displayLabel(state.menuLanguage).substringBefore(" ·")
        }
        drawSelector(
            canvas,
            geometry.frameControl,
            localized("画幅", "Format"),
            frameLabel,
        )
        drawSelector(canvas, geometry.cocControl, "c", "${"%.3f".format(session.circleOfConfusionMm)} mm")
    }

    private fun drawDial(
        canvas: Canvas,
        bounds: RectF,
        caption: String,
        value: String,
        visualOffset: Float,
    ) {
        if (bounds.isEmpty) return
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val radius = min(bounds.width(), bounds.height()) / 2f
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.25f * density
        paint.color = foreground
        canvas.drawCircle(centerX, centerY, radius - 0.8f * density, paint)
        canvas.drawCircle(centerX, centerY, radius * 0.70f, paint)

        for (baseAngle in floatArrayOf(-90f, 90f)) {
            for (offset in -3..3) {
                // Android's polar angle grows clockwise. Adding the accumulated gesture angle
                // makes both dial faces rotate with the finger instead of against it.
                val angle = baseAngle + (offset + visualOffset.coerceIn(-1f, 1f)) * 12f
                val radians = Math.toRadians(angle.toDouble())
                val inner = radius - if (offset == 0) 10f * density else 7f * density
                val outer = radius - 2f * density
                paint.strokeWidth = if (offset == 0) 1.35f * density else 0.9f * density
                paint.color = muted
                canvas.drawLine(
                    centerX + cos(radians).toFloat() * inner,
                    centerY + sin(radians).toFloat() * inner,
                    centerX + cos(radians).toFloat() * outer,
                    centerY + sin(radians).toFloat() * outer,
                    paint,
                )
            }
        }

        boldPaint.color = foreground
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = (radius / density * 0.27f).coerceIn(18f, 24f) * scaledDensity
        drawTextCentered(canvas, value, centerX, centerY, boldPaint)

        // The top red mark belongs to the dial housing; only gray tick marks rotate.
        paint.style = Paint.Style.STROKE
        paint.color = red
        paint.strokeWidth = 2.4f * density
        canvas.drawLine(centerX, bounds.top + 2f * density, centerX, bounds.top + 12f * density, paint)
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(centerX - 4f * density, bounds.top + 3f * density)
        path.lineTo(centerX + 4f * density, bounds.top + 3f * density)
        path.lineTo(centerX, bounds.top + 8f * density)
        path.close()
        canvas.drawPath(path, paint)
        paint.color = secondaryStrong
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 10.5f * scaledDensity
        canvas.drawText(caption, centerX, centerY + radius * 0.50f, paint)
    }

    private fun drawSelector(canvas: Canvas, bounds: RectF, title: String, value: String) {
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawRoundRect(bounds, 5f * density, 5f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f * density
        paint.color = foreground
        canvas.drawRoundRect(bounds, 5f * density, 5f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = secondaryStrong
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 10.5f * scaledDensity
        canvas.drawText(title, bounds.centerX(), bounds.top + bounds.height() * 0.34f, paint)
        boldPaint.color = foreground
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 12.5f * scaledDensity
        canvas.drawText(value, bounds.centerX(), bounds.bottom - bounds.height() * 0.20f, boldPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchStartX = event.x
                touchStartY = event.y
                moved = false
                dialStepAccumulator = 0f
                apertureVisualOffset = 0f
                focalVisualOffset = 0f
                touchTarget = when {
                    geometry.headerBack.contains(event.x, event.y) -> TouchTarget.BACK
                    geometry.headerClose.contains(event.x, event.y) -> TouchTarget.CLOSE
                    geometry.frameControl.contains(event.x, event.y) -> TouchTarget.FRAME
                    geometry.cocControl.contains(event.x, event.y) -> TouchTarget.COC
                    geometry.focusValue.contains(event.x, event.y) -> TouchTarget.FOCUS_VALUE
                    geometry.rulerTrack.contains(event.x, event.y) -> TouchTarget.FOCUS_RULER
                    geometry.apertureDial.contains(event.x, event.y) -> TouchTarget.APERTURE
                    geometry.focalDial.contains(event.x, event.y) -> TouchTarget.FOCAL_LENGTH
                    else -> TouchTarget.NONE
                }
                if (touchTarget == TouchTarget.APERTURE) {
                    lastDialAngle = angleFor(event.x, event.y, geometry.apertureDial)
                } else if (touchTarget == TouchTarget.FOCAL_LENGTH) {
                    lastDialAngle = angleFor(event.x, event.y, geometry.focalDial)
                }
                if (touchTarget == TouchTarget.FOCUS_RULER) selectFocusForX(event.x)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
                when (touchTarget) {
                    TouchTarget.FOCUS_RULER -> selectFocusForX(event.x)
                    TouchTarget.APERTURE, TouchTarget.FOCAL_LENGTH -> if (moved) {
                        updateDialRotation(event.x, event.y)
                    }
                    else -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    performClick()
                    when (touchTarget) {
                        TouchTarget.BACK -> listener?.onBackToToolsRequested()
                        TouchTarget.CLOSE -> listener?.onCloseRequested()
                        TouchTarget.FRAME -> dialogs.showFrame(session.selectedFormat)
                        TouchTarget.COC -> dialogs.showCircleOfConfusion(
                            session.selectedFormat,
                            session.circleOfConfusionMm,
                        )
                        TouchTarget.FOCUS_VALUE -> dialogs.showFocusDistance(focusDistanceM())
                        else -> Unit
                    }
                }
                apertureVisualOffset = 0f
                focalVisualOffset = 0f
                invalidate()
                touchTarget = TouchTarget.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                apertureVisualOffset = 0f
                focalVisualOffset = 0f
                invalidate()
                touchTarget = TouchTarget.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        markerAnimator?.cancel()
        markerAnimator = null
        super.onDetachedFromWindow()
    }

    private fun selectApertureIndex(index: Int) {
        val values = apertureValues()
        if (!session.selectApertureIndex(index, values)) return
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        animateCurrentResult()
    }

    private fun selectFocalIndex(index: Int) {
        if (!session.selectFocalIndex(index)) return
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        animateCurrentResult()
    }

    private fun selectFocusForX(x: Float) {
        val track = geometry.rulerTrack
        if (track.width() <= 0f) return
        val fraction = ((x - track.left) / track.width()).coerceIn(0f, 1f)
        if (!session.selectFocusDistance(DepthOfFieldMath.distanceForFraction(fraction.toDouble()))) return
        updateMarkers(animate = false)
    }

    private fun updateDialRotation(x: Float, y: Float) {
        val bounds = if (touchTarget == TouchTarget.APERTURE) geometry.apertureDial else geometry.focalDial
        val angle = angleFor(x, y, bounds)
        var delta = angle - lastDialAngle
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        lastDialAngle = angle
        val stepAngle = 12f
        dialStepAccumulator += delta

        val currentIndex: Int
        val lastIndex: Int
        if (touchTarget == TouchTarget.APERTURE) {
            val values = apertureValues()
            currentIndex = apertureIndex()
            lastIndex = values.lastIndex
        } else {
            currentIndex = session.focalIndex()
            lastIndex = DepthOfFieldMath.commonFocalLengthsMm.lastIndex
        }
        // At either end the wheel becomes mechanically firm in that direction. Resetting the
        // partial visual travel also prevents tick animation from continuing past the last value.
        if ((currentIndex <= 0 && dialStepAccumulator < 0f) ||
            (currentIndex >= lastIndex && dialStepAccumulator > 0f)
        ) {
            dialStepAccumulator = 0f
        }
        val steps = (dialStepAccumulator / stepAngle).toInt()
        if (steps != 0) {
            val targetIndex = (currentIndex + steps).coerceIn(0, lastIndex)
            val appliedSteps = targetIndex - currentIndex
            when (touchTarget) {
                TouchTarget.APERTURE -> selectApertureIndex(targetIndex)
                TouchTarget.FOCAL_LENGTH -> selectFocalIndex(targetIndex)
                else -> Unit
            }
            dialStepAccumulator = if (appliedSteps == steps) {
                dialStepAccumulator - appliedSteps * stepAngle
            } else {
                0f
            }
        }
        val visual = (dialStepAccumulator / stepAngle).coerceIn(-1f, 1f)
        if (touchTarget == TouchTarget.APERTURE) apertureVisualOffset = visual else focalVisualOffset = visual
        invalidate()
    }

    private fun angleFor(x: Float, y: Float, bounds: RectF): Float = Math.toDegrees(
        atan2((y - bounds.centerY()).toDouble(), (x - bounds.centerX()).toDouble()),
    ).toFloat()

    private fun animateCurrentResult() = updateMarkers(animate = true)

    private fun updateMarkers(animate: Boolean) {
        val nextResult = session.result
        val targetFocus = DepthOfFieldMath.distanceFraction(focusDistanceM())
        val targetNear = nextResult?.let { DepthOfFieldMath.distanceFraction(it.nearLimitM) }
            ?: targetFocus
        val targetFar = nextResult?.let { DepthOfFieldMath.distanceFraction(it.farLimitM) }
            ?: targetFocus
        markerAnimator?.cancel()
        if (!animate || !isLaidOut) {
            displayedNearFraction = targetNear
            displayedFocusFraction = targetFocus
            displayedFarFraction = targetFar
            invalidate()
            return
        }
        val startNear = displayedNearFraction
        val startFocus = displayedFocusFraction
        val startFar = displayedFarFraction
        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction.toDouble()
                displayedNearFraction = lerp(startNear, targetNear, fraction)
                displayedFocusFraction = lerp(startFocus, targetFocus, fraction)
                displayedFarFraction = lerp(startFar, targetFar, fraction)
                invalidate()
            }
            start()
        }
    }

    private fun drawConnector(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = color
        val bendY = fromY + (toY - fromY) * 0.55f
        path.reset()
        path.moveTo(fromX, fromY)
        path.lineTo(fromX, bendY)
        path.lineTo(toX, toY)
        canvas.drawPath(path, paint)
    }

    private fun drawDistanceLabel(canvas: Canvas, title: String, value: String, x: Float, y: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = color
        paint.textSize = 11.5f * scaledDensity
        canvas.drawText(title, x, y, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = foreground
        boldPaint.textSize = 15.5f * scaledDensity
        canvas.drawText(value, x, y + 20f * density, boldPaint)
    }

    private fun drawFlower(canvas: Canvas, x: Float, y: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = foreground
        canvas.drawLine(x, y + 5f * density, x, y + 17f * density, paint)
        repeat(5) { index ->
            val angle = Math.toRadians((index * 72.0 - 90.0))
            canvas.drawCircle(
                x + cos(angle).toFloat() * 4f * density,
                y + sin(angle).toFloat() * 4f * density,
                3f * density,
                paint,
            )
        }
        paint.style = Paint.Style.FILL
        paint.color = red
        canvas.drawCircle(x, y, 2f * density, paint)
    }

    private fun drawMountain(canvas: Canvas, x: Float, y: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * density
        paint.color = foreground
        path.reset()
        path.moveTo(x - 12f * density, y + 10f * density)
        path.lineTo(x - 4f * density, y - 1f * density)
        path.lineTo(x + 1f * density, y + 5f * density)
        path.lineTo(x + 7f * density, y - 7f * density)
        path.lineTo(x + 13f * density, y + 10f * density)
        canvas.drawPath(path, paint)
    }

    private fun drawTextCentered(canvas: Canvas, text: String, x: Float, y: Float, textPaint: Paint) {
        canvas.drawText(text, x, y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private fun apertureValues(): DoubleArray =
        ExposureMath.apertureTicks(state.apertureStep).map { it.nominalValue }.toDoubleArray()

    private fun apertureIndex(): Int {
        val values = apertureValues()
        return session.apertureIndex(values)
    }

    private fun focusDistanceM(): Double = session.focusDistanceM()

    private fun focalLengthMm(): Double = session.focalLengthMm()

    private fun formatAperture(value: Double): String =
        if (value >= 10.0 || abs(value - value.roundToInt()) < 0.01) value.roundToInt().toString() else "%.1f".format(value)

    private fun formatMillimetres(value: Double): String =
        if (value >= 10.0) "${value.roundToInt()} mm" else "%.1f mm".format(value)

    private fun formatDistance(value: Double?, infinityWhenNull: Boolean = false): String = when {
        value == null && infinityWhenNull -> "∞"
        value == null -> "—"
        value == Double.POSITIVE_INFINITY -> "∞"
        value >= 100.0 -> "${value.roundToInt()} m"
        value >= 10.0 -> "%.1f m".format(value)
        value >= 1.0 -> "%.2f m".format(value)
        else -> "%.2f m".format(value)
    }

    private fun updateContentDescription() {
        val current = session.result
        contentDescription = localized(
            "景深计算，${session.selectedFormat.label}，光圈 ${formatAperture(session.selectedAperture)}，对焦 ${formatDistance(focusDistanceM())}，近界 ${formatDistance(current?.nearLimitM)}，远界 ${formatDistance(current?.farLimitM, current != null)}",
            "Depth of field, ${session.selectedFormat.englishLabel}, aperture ${formatAperture(session.selectedAperture)}, focus ${formatDistance(focusDistanceM())}, near ${formatDistance(current?.nearLimitM)}, far ${formatDistance(current?.farLimitM, current != null)}",
        )
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private fun lerp(start: Double, end: Double, fraction: Double): Double =
        start + (end - start) * fraction
}
