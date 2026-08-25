package com.lightmeter.rawmeter

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class InstrumentView(
    context: Context,
    private val state: MeterState,
) : View(context) {

    interface Listener {
        fun onMeasureRequested()
        fun onOrientationToggle()
        fun onMoreRequested()
        fun onToolsRequested()
        fun onZoneEntryDrag(progress: Float, released: Boolean)
        fun onControlsChanged(frameChanged: Boolean)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val lightBlack = Color.rgb(20, 20, 20)
    private val nightForeground = Color.rgb(210, 210, 206)
    private val black: Int get() = if (state.isDarkMode) nightForeground else lightBlack
    private val surfaceColor: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val red = Color.rgb(166, 27, 36)
    private val paleGray: Int
        get() = if (state.isDarkMode) Color.rgb(38, 38, 36) else Color.rgb(232, 232, 229)
    private val middleGray = Color.rgb(130, 130, 126)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
    }
    private val exposureRenderer = InstrumentExposureRenderer(state, density)
    private var geometry: LayoutGeometry? = null
    private var touchTarget = TouchTarget.NONE
    private var lastDialAngle = 0f
    private var dialStepAccumulator = 0f
    private var lastClickTime = 0L
    private var lastScaleX = 0f
    private var lockedScaleDragCoordinate = Double.NaN
    private var lockedScaleDisplayCoordinate = Double.NaN
    private var lockedScaleDragging = false
    private var lockedScaleAnimator: ValueAnimator? = null
    private var formatMenuOpen = false
    private var animatedEv100 = Double.NaN
    private var animationTargetEv100 = Double.NaN
    private var evAnimator: ValueAnimator? = null
    private var frozenDependentCoordinate = Double.NaN
    private var releasedDependentCoordinate = Double.NaN
    private var dependentAnimator: ValueAnimator? = null
    private var exposureLockSliderFraction = Float.NaN
    private var exposureLockAnimator: ValueAnimator? = null
    private var exposureLockDragStartY = 0f
    private var exposureLockDragStartFraction = 0f
    private var exposureLockDragMoved = false
    private var zoneTransitionFraction = 0f
    private var modeTransitionEnabled = true
    private var parameterDialInteractionEnabled = true
    private var zoneDragStartX = 0f
    private var zoneDragStartY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private enum class TouchTarget {
        NONE,
        DIAL,
        ZOOM,
        APERTURE,
        SHUTTER,
        LOCK,
        ZONE_ENTRY,
    }

    internal fun setAppliedReciprocity(method: ReciprocityMethod?) {
        exposureRenderer.setAppliedReciprocity(method)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = LayoutGeometry.calculate(
            width,
            height,
            density,
            state.frameFormat,
            state.frameLandscape,
            state.isLeftHanded,
        )
        geometry = g
        drawSurfaceOutsidePreview(canvas, g.cameraFrame)
        drawPanels(canvas, g)
        drawCameraOverlay(canvas, g)
        drawExposureRows(canvas, g)
        drawDial(canvas, g)
        drawMeterButton(canvas, g)
        drawStatus(canvas, g)
    }

    private fun drawSurfaceOutsidePreview(canvas: Canvas, frame: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, paint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, paint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, paint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), paint)
    }

    private fun drawPanels(canvas: Canvas, g: LayoutGeometry) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f * density
        paint.color = black
        canvas.drawRect(g.previewPanel, paint)
        canvas.drawRect(g.cameraFrame, paint)
        if (g.landscape) {
            val dividerX = if (g.previewPanel.centerX() < g.apertureRow.centerX()) {
                (g.previewPanel.right + g.apertureRow.left) / 2f
            } else {
                (g.apertureRow.right + g.previewPanel.left) / 2f
            }
            canvas.drawLine(dividerX, 0f, dividerX, height.toFloat(), paint)
        } else {
            val dividerY = (g.previewPanel.bottom + g.apertureRow.top) / 2f
            canvas.drawLine(0f, dividerY, width.toFloat(), dividerY, paint)
        }
    }

    private fun drawCameraOverlay(canvas: Canvas, g: LayoutGeometry) {
        val roiFraction = if (state.meteringMode == MeteringMode.ANGLE) {
            state.angleMeteringRoiFraction() ?: DEFAULT_SPOT_DIAMETER_FRACTION
        } else {
            DEFAULT_SPOT_DIAMETER_FRACTION
        }
        val spotRadius = (
            min(g.cameraFrame.width(), g.cameraFrame.height()) * roiFraction / 2f
            ).coerceAtLeast(4f * density)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f * density
        paint.color = surfaceColor
        canvas.drawCircle(g.cameraFrame.centerX(), g.cameraFrame.centerY(), spotRadius + density, paint)
        paint.color = black
        paint.strokeWidth = 0.9f * density
        canvas.drawCircle(g.cameraFrame.centerX(), g.cameraFrame.centerY(), spotRadius, paint)
        canvas.drawCircle(g.cameraFrame.centerX(), g.cameraFrame.centerY(), 1.4f * density, paint)

        if (state.measuring) {
            drawMeteringSpinner(canvas, g.cameraFrame, spotRadius)
            postInvalidateOnAnimation()
        }

        drawOutlinedButton(
            canvas,
            g.formatButton,
            InstrumentPresentation.formatShortLabel(state.frameFormat, state.menuLanguage),
            formatMenuOpen,
            overlayAlpha(g.formatButton, g.cameraFrame),
        )
        if (formatMenuOpen) drawFormatMenu(canvas, g)
        drawOrientationButton(
            canvas,
            g.orientationButton,
            g.landscape,
            overlayAlpha(g.orientationButton, g.cameraFrame),
        )
        drawZoom(canvas, g.zoomTrack)
        drawSettingsButton(canvas, g.moreButton, overlayAlpha(g.moreButton, g.cameraFrame))
        drawToolsButton(canvas, g.toolsButton, overlayAlpha(g.toolsButton, g.cameraFrame))
        drawZoneEntryHandle(canvas, g)

        val equivalent = state.equivalentFrameFocalMm()
        if (equivalent != null) {
            val format = InstrumentPresentation.formatShortLabel(
                state.frameFormat,
                state.menuLanguage,
            )
            val focalText = "≈ $equivalent mm ($format)"
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(
                168,
                Color.red(surfaceColor),
                Color.green(surfaceColor),
                Color.blue(surfaceColor),
            )
            paint.textSize = 10f * density
            paint.typeface = Typeface.DEFAULT_BOLD
            val textWidth = paint.measureText(focalText)
            val baseline = g.cameraFrame.bottom - 8f * density
            val horizontalPadding = 6f * density
            // Anchor to the viewfinder's bottom-right corner; the clamp keeps the label inside
            // the frame on devices with narrow fitted images.
            val left = max(
                g.cameraFrame.left,
                g.cameraFrame.right - textWidth - horizontalPadding * 2f,
            )
            canvas.drawRoundRect(
                RectF(
                    left,
                    baseline - 13f * density,
                    left + textWidth + horizontalPadding * 2f,
                    baseline + 3f * density,
                ),
                4f * density,
                4f * density,
                paint,
            )
            paint.color = black
            canvas.drawText(focalText, left + horizontalPadding, baseline, paint)
        }
    }

    private fun drawMeteringSpinner(canvas: Canvas, frame: RectF, spotRadius: Float) {
        val radius = spotRadius + 6f * density
        val bounds = RectF(
            frame.centerX() - radius,
            frame.centerY() - radius,
            frame.centerX() + radius,
            frame.centerY() + radius,
        )
        val gray = if (state.isDarkMode) 168 else 132
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.color = Color.argb(42, gray, gray, gray)
        canvas.drawCircle(frame.centerX(), frame.centerY(), radius, paint)

        val phase = (SystemClock.uptimeMillis() % METERING_SPINNER_PERIOD_MS).toFloat() /
            METERING_SPINNER_PERIOD_MS
        paint.strokeWidth = 2.6f * density
        paint.color = Color.argb(158, gray, gray, gray)
        canvas.drawArc(bounds, phase * 360f - 90f, METERING_SPINNER_SWEEP_DEGREES, false, paint)
    }

    fun setZoneTransitionFraction(fraction: Float) {
        zoneTransitionFraction = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    /** Prevents the covered Normal/Zone handle from retaining a partial drag under Tools. */
    fun setModeTransitionEnabled(enabled: Boolean) {
        modeTransitionEnabled = enabled
        if (!enabled) {
            if (touchTarget == TouchTarget.ZONE_ENTRY) touchTarget = TouchTarget.NONE
            zoneTransitionFraction = 0f
        }
        invalidate()
    }

    /** Keeps the exposed Normal dial from receiving touches beneath a Tools overlay. */
    fun setParameterDialInteractionEnabled(enabled: Boolean) {
        parameterDialInteractionEnabled = enabled
        if (!enabled && touchTarget == TouchTarget.DIAL) {
            touchTarget = TouchTarget.NONE
            dialStepAccumulator = 0f
        }
    }

    private fun drawZoneEntryHandle(canvas: Canvas, g: LayoutGeometry) {
        val rect = RectF(g.zoneEntryHandle)
        if (g.landscape) {
            val direction = ModeTransitionDirection.zoneEntrySign(state.isLeftHanded)
            rect.offset(direction * zoneTransitionFraction * width * 0.14f, 0f)
        } else {
            rect.offset(0f, -zoneTransitionFraction * height * 0.16f)
        }
        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = black
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = black
        paint.textSize = 8.5f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        if (g.landscape) {
            canvas.save()
            canvas.rotate(-90f, rect.centerX(), rect.centerY())
            drawCenteredText(canvas, "zone", rect.centerX(), rect.centerY(), paint)
            canvas.restore()
        } else {
            drawCenteredText(canvas, "zone", rect.centerX(), rect.centerY(), paint)
        }

        if (touchTarget == TouchTarget.ZONE_ENTRY || zoneTransitionFraction > 0f) {
            val arrow = Path()
            val size = min(rect.width(), rect.height()) * 0.42f
            if (g.landscape) {
                val direction = ModeTransitionDirection.zoneEntrySign(state.isLeftHanded)
                val x = if (direction < 0f) {
                    rect.left - 5f * density - zoneTransitionFraction * 18f * density
                } else {
                    rect.right + 5f * density + zoneTransitionFraction * 18f * density
                }
                arrow.moveTo(x, rect.centerY() - size)
                arrow.lineTo(x + direction * size, rect.centerY())
                arrow.lineTo(x, rect.centerY() + size)
            } else {
                val y = rect.top - 4f * density - zoneTransitionFraction * 18f * density
                arrow.moveTo(rect.centerX() - size, y)
                arrow.lineTo(rect.centerX(), y - size)
                arrow.lineTo(rect.centerX() + size, y)
            }
            arrow.close()
            canvas.drawPath(arrow, paint)
        }
    }

    private fun drawFormatMenu(canvas: Canvas, g: LayoutGeometry) {
        InstrumentPresentation.formatOptionRects(g, density).forEachIndexed { index, rect ->
            val selected = index == state.frameIndex
            paint.style = Paint.Style.FILL
            paint.color = if (selected) black else surfaceColor
            canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (selected) 1.6f * density else 1f * density
            paint.color = if (selected) red else black
            canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
            paint.style = Paint.Style.FILL
            paint.color = if (selected) surfaceColor else black
            paint.textSize = 7.5f * density
            paint.typeface = Typeface.DEFAULT_BOLD
            drawCenteredText(
                canvas,
                InstrumentPresentation.formatShortLabel(
                    FrameFormat.ALL[index],
                    state.menuLanguage,
                ),
                rect.centerX(),
                rect.centerY(),
                paint,
            )
        }
    }

    private fun drawOrientationButton(
        canvas: Canvas,
        rect: RectF,
        landscape: Boolean,
        alpha: Int = 255,
    ) {
        paint.style = Paint.Style.FILL
        paint.withAlpha(surfaceColor, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f * density
        paint.withAlpha(black, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        val iconW = if (landscape) rect.width() * 0.52f else rect.width() * 0.36f
        val iconH = if (landscape) rect.height() * 0.36f else rect.height() * 0.52f
        canvas.drawRoundRect(
            RectF(
                rect.centerX() - iconW / 2f,
                rect.centerY() - iconH / 2f,
                rect.centerX() + iconW / 2f,
                rect.centerY() + iconH / 2f,
            ),
            2f * density,
            2f * density,
            paint,
        )
        val arc = RectF(
            rect.left + 5f * density,
            rect.top + 5f * density,
            rect.right - 5f * density,
            rect.bottom - 5f * density,
        )
        paint.withAlpha(red, alpha)
        canvas.drawArc(arc, 205f, 86f, false, paint)
    }

    private fun drawSettingsButton(canvas: Canvas, rect: RectF, alpha: Int = 255) {
        paint.style = Paint.Style.FILL
        paint.withAlpha(surfaceColor, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.withAlpha(black, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        val outerRadius = min(rect.width(), rect.height()) * 0.27f
        val rootRadius = outerRadius * 0.78f
        val innerRadius = outerRadius * 0.31f
        val gear = Path()
        repeat(16) { index ->
            val angle = Math.toRadians((-90.0 + index * 22.5))
            val radius = if (index % 2 == 0) outerRadius else rootRadius
            val x = rect.centerX() + cos(angle).toFloat() * radius
            val y = rect.centerY() + sin(angle).toFloat() * radius
            if (index == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
        }
        gear.close()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.withAlpha(black, alpha)
        canvas.drawPath(gear, paint)
        canvas.drawCircle(rect.centerX(), rect.centerY(), innerRadius, paint)
    }

    /** Four hollow squares with the top-right one rotated 45 degrees around its own center. */
    private fun drawToolsButton(canvas: Canvas, rect: RectF, alpha: Int = 255) {
        paint.style = Paint.Style.FILL
        paint.withAlpha(surfaceColor, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.withAlpha(black, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        val cell = rect.width() * 0.26f
        val innerGap = rect.width() * 0.07f
        val left = rect.centerX() - cell - innerGap / 2f
        val top = rect.centerY() - cell - innerGap / 2f
        paint.strokeWidth = 1.1f * density
        canvas.drawRect(left, top, left + cell, top + cell, paint)
        canvas.drawRect(
            left,
            top + cell + innerGap,
            left + cell,
            top + cell * 2f + innerGap,
            paint,
        )
        canvas.drawRect(
            left + cell + innerGap,
            top + cell + innerGap,
            left + cell * 2f + innerGap,
            top + cell * 2f + innerGap,
            paint,
        )
        val rotated = RectF(
            left + cell + innerGap,
            top,
            left + cell * 2f + innerGap,
            top + cell,
        )
        canvas.save()
        canvas.rotate(45f, rotated.centerX(), rotated.centerY())
        canvas.drawRect(rotated, paint)
        canvas.restore()
    }

    private fun drawZoom(canvas: Canvas, track: RectF) {
        val maxZoom = state.cameraInfo.maxDisplayZoom.coerceAtLeast(1.01f)
        val normalized = ((state.zoom - 1f) / (maxZoom - 1f)).coerceIn(0f, 1f)
        val x = track.centerX()
        val top = track.top + 22f * density
        val bottom = track.bottom - 16f * density
        val knobY = bottom - normalized * (bottom - top)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = black
        canvas.drawLine(x, top, x, bottom, paint)
        for (i in 0..8) {
            val y = top + (bottom - top) * i / 8f
            val length = if (i % 2 == 0) 7f * density else 4f * density
            canvas.drawLine(x - length, y, x + length, y, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = red
        canvas.drawCircle(x, knobY, 5.2f * density, paint)
        paint.textSize = 9f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = black
        drawCenteredText(canvas, "${"%.1f".format(state.zoom)}×", x, track.top + 8f * density, paint)
    }

    private fun drawExposureRows(canvas: Canvas, g: LayoutGeometry) {
        val evAtIso = animatedExposureValue()
        val centers = InstrumentPresentation.exposureCenters(
            state = state,
            evAtIso = evAtIso,
            lockedDisplayCoordinate = lockedScaleDisplayCoordinate,
            frozenDependentCoordinate = frozenDependentCoordinate,
            releasedDependentCoordinate = releasedDependentCoordinate,
        )
        if (exposureLockSliderFraction.isNaN()) {
            exposureLockSliderFraction =
                if (state.exposureLockMode == ExposureLockMode.APERTURE) 0f else 1f
        }
        exposureRenderer.draw(canvas, g, centers, exposureLockSliderFraction)
    }

    fun currentApertureCoordinate(): Double = InstrumentPresentation.exposureCenters(
        state = state,
        evAtIso = animatedExposureValue(),
        lockedDisplayCoordinate = lockedScaleDisplayCoordinate,
        frozenDependentCoordinate = frozenDependentCoordinate,
        releasedDependentCoordinate = releasedDependentCoordinate,
    ).aperture

    fun currentShutterCoordinate(): Double = InstrumentPresentation.exposureCenters(
        state = state,
        evAtIso = animatedExposureValue(),
        lockedDisplayCoordinate = lockedScaleDisplayCoordinate,
        frozenDependentCoordinate = frozenDependentCoordinate,
        releasedDependentCoordinate = releasedDependentCoordinate,
    ).shutter

    fun recordButtonRect(): RectF = RectF(
        (geometry ?: LayoutGeometry.calculate(
            width,
            height,
            density,
            state.frameFormat,
            state.frameLandscape,
            state.isLeftHanded,
        )).meterButton,
    )

    private fun animatedExposureValue(): Double {
        val fallback = state.lockedApertureStop - state.lockedShutterLogSeconds
        val target = state.effectiveEv100
            ?.plus(ExposureMath.log2(state.iso / 100.0))
            ?: fallback
        if (animatedEv100.isNaN()) {
            animatedEv100 = target
            animationTargetEv100 = target
        } else if (abs(target - animationTargetEv100) > 0.0001) {
            evAnimator?.cancel()
            animationTargetEv100 = target
            evAnimator = ValueAnimator.ofFloat(animatedEv100.toFloat(), target.toFloat()).apply {
                duration = 320L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    animatedEv100 = (it.animatedValue as Float).toDouble()
                    postInvalidateOnAnimation()
                }
                start()
            }
        }
        return animatedEv100
    }

    private fun exactEvAtIso(): Double = animatedExposureValue()

    private fun drawDial(canvas: Canvas, g: LayoutGeometry) {
        val dial = g.dial
        if (dial.width() <= 0f || dial.height() <= 0f) return
        val cx = dial.centerX()
        val cy = dial.centerY()
        val radius = min(dial.width(), dial.height()) / 2f

        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = black
        canvas.drawCircle(cx, cy, radius - density, paint)
        canvas.drawCircle(cx, cy, radius * 0.72f, paint)

        drawIsoScale(canvas, dial, cx, cy, radius)
        drawCompensationScale(canvas, dial, cx, cy, radius)
        drawDialModeIndicator(canvas, cx, cy, radius)

        paint.style = Paint.Style.FILL
        paint.color = red
        canvas.drawCircle(cx - radius + 2.2f * density, cy, 2.7f * density, paint)
        canvas.drawCircle(cx + radius - 2.2f * density, cy, 2.7f * density, paint)

        val centerRadius = radius * 0.68f
        paint.color = paleGray
        canvas.drawCircle(cx, cy, centerRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = black
        canvas.drawCircle(cx, cy, centerRadius, paint)
        canvas.drawLine(cx, cy - centerRadius * 0.62f, cx, cy + centerRadius * 0.62f, paint)

        boldPaint.color = black
        boldPaint.textSize = maxOf(10f * density, radius * 0.12f)
        val isoX = cx + centerRadius * if (state.isLeftHanded) 0.47f else -0.47f
        val evX = cx - centerRadius * if (state.isLeftHanded) 0.47f else -0.47f
        drawCenteredText(canvas, "ISO", isoX, cy - radius * 0.14f, boldPaint)
        boldPaint.textSize = maxOf(15f * density, radius * 0.20f)
        drawCenteredText(canvas, state.iso.toString(), isoX, cy + radius * 0.09f, boldPaint)

        boldPaint.textSize = maxOf(10f * density, radius * 0.12f)
        drawCenteredText(canvas, "EV", evX, cy - radius * 0.14f, boldPaint)
        boldPaint.textSize = maxOf(14f * density, radius * 0.18f)
        val comp = state.exposureCompEv
        val compText = if (comp >= 0.0) "+${"%.2f".format(comp)}" else "%.2f".format(comp)
        drawCenteredText(canvas, compText, evX, cy + radius * 0.09f, boldPaint)

        drawIsoModeButton(canvas, g.isoModeButton)
    }

    private fun drawDialModeIndicator(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
    ) {
        // The scale windows expose roughly the outer 37% of either side. Match that
        // opening with two parallel arcs so the active control is apparent without
        // covering its ticks or labels.
        val isoOnRight = state.isLeftHanded
        val activeOnRight = if (state.isoAdjustMode) isoOnRight else !isoOnRight
        val arcStart = if (activeOnRight) -75f else 105f
        val strokeWidth = 1.4f * density

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.strokeCap = Paint.Cap.ROUND

        paint.color = surfaceColor
        val accentRadius = radius + 2.2f * density
        canvas.drawArc(
            RectF(
                cx - accentRadius,
                cy - accentRadius,
                cx + accentRadius,
                cy + accentRadius,
            ),
            arcStart,
            150f,
            false,
            paint,
        )

        paint.color = black
        val outlineRadius = radius + 4.8f * density
        canvas.drawArc(
            RectF(
                cx - outlineRadius,
                cy - outlineRadius,
                cx + outlineRadius,
                cy + outlineRadius,
            ),
            arcStart,
            150f,
            false,
            paint,
        )
    }

    private fun drawIsoScale(
        canvas: Canvas,
        dial: RectF,
        cx: Float,
        cy: Float,
        radius: Float,
    ) {
        canvas.save()
        if (state.isLeftHanded) {
            canvas.clipRect(
                dial.right - dial.width() * 0.37f,
                dial.top - density,
                dial.right + density,
                dial.bottom + density,
            )
        } else {
            canvas.clipRect(
                dial.left - density,
                dial.top - density,
                dial.left + dial.width() * 0.37f,
                dial.bottom + density,
            )
        }
        val spacing = 12f
        val textRadius = radius * 0.83f
        for (index in state.isoValues.indices) {
            val baseAngle = if (state.isLeftHanded) 0f else 180f
            val angle = baseAngle + (index - state.isoIndex) * spacing
            if (state.isLeftHanded) {
                if (angle < -90f || angle > 90f) continue
            } else if (angle < 90f || angle > 270f) {
                continue
            }
            drawDialTick(
                canvas,
                cx,
                cy,
                radius,
                textRadius,
                angle,
                if (index % 3 == 0 || index == state.isoIndex) state.isoValues[index].toString() else null,
                index == state.isoIndex,
            )
        }
        canvas.restore()
    }

    private fun drawCompensationScale(
        canvas: Canvas,
        dial: RectF,
        cx: Float,
        cy: Float,
        radius: Float,
    ) {
        canvas.save()
        if (state.isLeftHanded) {
            canvas.clipRect(
                dial.left - density,
                dial.top - density,
                dial.left + dial.width() * 0.37f,
                dial.bottom + density,
            )
        } else {
            canvas.clipRect(
                dial.right - dial.width() * 0.37f,
                dial.top - density,
                dial.right + density,
                dial.bottom + density,
            )
        }
        val spacing = 8f
        val textRadius = radius * 0.83f
        val compensationStep = state.exposureCompensationStep
        for (step in
            ExposureCompensationDial.MIN_SIXTH_STOPS..ExposureCompensationDial.MAX_SIXTH_STOPS
        ) {
            if (step % compensationStep.sixthStops != 0) continue
            val baseAngle = if (state.isLeftHanded) 180f else 0f
            val angle = baseAngle + ExposureCompensationDial.relativeTickAngleDegrees(
                tickSixthStops = step,
                currentSixthStops = state.exposureCompSteps,
                step = compensationStep,
                leftHanded = state.isLeftHanded,
                spacingDegrees = spacing,
            )
            if (state.isLeftHanded) {
                if (angle < 90f || angle > 270f) continue
            } else if (angle < -90f || angle > 90f) {
                continue
            }
            val label = if (step % 6 == 0) {
                val value = step / 6
                if (value > 0) "+$value" else value.toString()
            } else {
                null
            }
            drawDialTick(
                canvas,
                cx,
                cy,
                radius,
                textRadius,
                angle,
                label,
                step == state.exposureCompSteps,
            )
        }
        canvas.restore()
    }

    private fun drawDialTick(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        textRadius: Float,
        angleDegrees: Float,
        label: String?,
        selected: Boolean,
    ) {
        val angle = Math.toRadians(angleDegrees.toDouble())
        val cos = cos(angle).toFloat()
        val sin = sin(angle).toFloat()
        val outer = radius - 2f * density
        val inner = radius - if (label != null) 10f * density else 6f * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (selected) 2f * density else 0.9f * density
        paint.color = if (selected) red else black
        canvas.drawLine(
            cx + cos * inner,
            cy + sin * inner,
            cx + cos * outer,
            cy + sin * outer,
            paint,
        )
        if (label != null) {
            paint.style = Paint.Style.FILL
            paint.textSize = 8f * density
            paint.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            drawCenteredText(
                canvas,
                label,
                cx + cos * textRadius,
                cy + sin * textRadius,
                paint,
            )
        }
    }

    private fun drawIsoModeButton(canvas: Canvas, rect: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = if (state.isoAdjustMode) red else black
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, paint)
        if (state.isoAdjustMode) {
            paint.style = Paint.Style.FILL
            paint.color = red
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.34f, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = if (state.isoAdjustMode) surfaceColor else black
        paint.textSize = 7f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        drawCenteredText(canvas, "ISO", rect.centerX(), rect.centerY(), paint)
    }

    private fun drawMeterButton(canvas: Canvas, g: LayoutGeometry) {
        val rect = g.meterButton
        val radius = min(rect.width(), rect.height()) * 0.18f
        paint.style = Paint.Style.FILL
        paint.color = if (state.measuring) paleGray else surfaceColor
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = black
        canvas.drawRoundRect(rect, radius, radius, paint)
        val circleRadius = min(rect.width(), rect.height()) * 0.22f
        paint.strokeWidth = 2f * density
        paint.color = red
        canvas.drawCircle(rect.centerX(), rect.centerY(), circleRadius, paint)
    }

    private fun drawStatus(canvas: Canvas, g: LayoutGeometry) {
        val message = state.transientMessage ?: state.cameraInfo.status
        if (message.isBlank()) return
        paint.style = Paint.Style.FILL
        paint.color = if (state.transientMessage != null) red else middleGray
        paint.textSize = 8f * density
        paint.typeface = Typeface.DEFAULT
        val x = if (g.landscape) g.apertureRow.centerX() else width / 2f
        val y = if (g.landscape) height - 6f * density else height - 5f * density
        drawCenteredText(canvas, message, x, y, paint)
    }

    private fun drawOutlinedButton(
        canvas: Canvas,
        rect: RectF,
        text: String,
        accented: Boolean,
        alpha: Int = 255,
    ) {
        paint.style = Paint.Style.FILL
        paint.withAlpha(surfaceColor, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.withAlpha(if (accented) red else black, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 9f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        drawCenteredText(canvas, text, rect.centerX(), rect.centerY(), paint)
    }

    /** Buttons overlapping the fitted viewfinder image become semi-transparent. */
    private fun overlayAlpha(rect: RectF, cameraFrame: RectF): Int =
        if (RectF.intersects(rect, cameraFrame)) OVERLAY_ALPHA else 255

    private fun Paint.withAlpha(color: Int, alpha: Int) {
        this.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = geometry ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (formatMenuOpen) {
                    val selectedFormat = InstrumentPresentation.formatOptionRects(g, density)
                        .indexOfFirst { it.contains(event.x, event.y) }
                    if (selectedFormat >= 0) {
                        state.selectFrame(selectedFormat)
                        formatMenuOpen = false
                        haptic()
                        listener?.onControlsChanged(true)
                        invalidate()
                        return true
                    }
                }
                when {
                    modeTransitionEnabled &&
                        g.zoneEntryHandle.containsAccessibleTarget(event.x, event.y, density) &&
                        !state.measuring -> {
                        formatMenuOpen = false
                        touchTarget = TouchTarget.ZONE_ENTRY
                        zoneDragStartX = event.x
                        zoneDragStartY = event.y
                        return true
                    }
                    g.moreButton.containsAccessibleTarget(event.x, event.y, density) -> {
                        formatMenuOpen = false
                        haptic()
                        listener?.onMoreRequested()
                        return true
                    }
                    g.toolsButton.containsAccessibleTarget(event.x, event.y, density) -> {
                        formatMenuOpen = false
                        haptic()
                        listener?.onToolsRequested()
                        return true
                    }
                    g.orientationButton.containsAccessibleTarget(event.x, event.y, density) -> {
                        formatMenuOpen = false
                        haptic()
                        listener?.onOrientationToggle()
                        return true
                    }
                    g.formatButton.containsAccessibleTarget(event.x, event.y, density) -> {
                        formatMenuOpen = !formatMenuOpen
                        haptic()
                        invalidate()
                        return true
                    }
                    !parameterDialInteractionEnabled &&
                        (g.isoModeButton.containsAccessibleTarget(event.x, event.y, density) ||
                            g.dial.contains(event.x, event.y)) -> {
                        // Tools can be smaller than Normal's parameter panel on some devices.
                        // Consume the covered dial touch instead of letting it leak through.
                        formatMenuOpen = false
                        return true
                    }
                    parameterDialInteractionEnabled &&
                        g.isoModeButton.containsAccessibleTarget(event.x, event.y, density) -> {
                        formatMenuOpen = false
                        state.isoAdjustMode = !state.isoAdjustMode
                        haptic()
                        listener?.onControlsChanged(false)
                        invalidate()
                        return true
                    }
                    g.meterButton.contains(event.x, event.y) -> {
                        formatMenuOpen = false
                        if (!state.measuring) {
                            haptic()
                            listener?.onMeasureRequested()
                        }
                        return true
                    }
                    g.exposureLockTrack.contains(event.x, event.y) -> {
                        formatMenuOpen = false
                        touchTarget = TouchTarget.LOCK
                        beginExposureLockDrag(event.y)
                    }
                    g.zoomTrack.contains(event.x, event.y) -> {
                        formatMenuOpen = false
                        touchTarget = TouchTarget.ZOOM
                        updateZoom(event.y, g.zoomTrack)
                    }
                    g.apertureRow.contains(event.x, event.y) -> {
                        formatMenuOpen = false
                        if (state.exposureLockMode == ExposureLockMode.APERTURE) {
                            beginLockedScaleDrag(TouchTarget.APERTURE, event.x)
                        }
                    }
                    g.shutterRow.contains(event.x, event.y) -> {
                        formatMenuOpen = false
                        if (state.exposureLockMode == ExposureLockMode.SHUTTER) {
                            beginLockedScaleDrag(TouchTarget.SHUTTER, event.x)
                        }
                    }
                    parameterDialInteractionEnabled && g.dial.contains(event.x, event.y) -> {
                        formatMenuOpen = false
                        touchTarget = TouchTarget.DIAL
                        lastDialAngle = angleFor(event.x, event.y, g.dial)
                        dialStepAccumulator = 0f
                    }
                    else -> {
                        if (formatMenuOpen) {
                            formatMenuOpen = false
                            invalidate()
                            return true
                        }
                        return false
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (touchTarget) {
                    TouchTarget.ZOOM -> updateZoom(event.y, g.zoomTrack)
                    TouchTarget.APERTURE -> updateLockedScaleDrag(event.x, g.apertureRow)
                    TouchTarget.SHUTTER -> updateLockedScaleDrag(event.x, g.shutterRow)
                    TouchTarget.LOCK -> updateExposureLockDrag(event.y, g.exposureLockTrack)
                    TouchTarget.DIAL -> updateDial(event.x, event.y, g.dial)
                    TouchTarget.ZONE_ENTRY -> {
                        zoneTransitionFraction = if (g.landscape) {
                            (ModeTransitionDirection.zoneEntryDistance(
                                zoneDragStartX,
                                event.x,
                                state.isLeftHanded,
                            ) / (width * 0.18f)).coerceIn(0f, 1f)
                        } else {
                            ((zoneDragStartY - event.y) / (height * 0.18f)).coerceIn(0f, 1f)
                        }
                        listener?.onZoneEntryDrag(zoneTransitionFraction, false)
                        invalidate()
                    }
                    TouchTarget.NONE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchTarget == TouchTarget.APERTURE || touchTarget == TouchTarget.SHUTTER) {
                    finishLockedScaleDrag()
                    listener?.onControlsChanged(false)
                    invalidate()
                } else if (touchTarget == TouchTarget.LOCK) {
                    finishExposureLockDrag(
                        event.y,
                        g.exposureLockTrack,
                        cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL,
                    )
                } else if (touchTarget == TouchTarget.ZONE_ENTRY) {
                    listener?.onZoneEntryDrag(
                        if (event.actionMasked == MotionEvent.ACTION_CANCEL) 0f else zoneTransitionFraction,
                        true,
                    )
                }
                touchTarget = TouchTarget.NONE
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateZoom(y: Float, track: RectF) {
        val top = track.top + 22f * density
        val bottom = track.bottom - 16f * density
        val fraction = ((bottom - y) / (bottom - top)).coerceIn(0f, 1f)
        val max = state.cameraInfo.maxDisplayZoom.coerceAtLeast(1.01f)
        val raw = 1f + fraction * (max - 1f)
        state.zoom = (raw * 10f).roundToInt() / 10f
        listener?.onControlsChanged(false)
        invalidate()
    }

    private fun updateLockedScaleDrag(x: Float, rect: RectF) {
        val lockTrack = geometry?.exposureLockTrack ?: return
        val content = exposureRenderer.scaleContent(rect, lockTrack)
        val deltaX = x - lastScaleX
        lastScaleX = x
        val before = if (state.exposureLockMode == ExposureLockMode.APERTURE) {
            state.lockedApertureStop
        } else {
            state.lockedShutterLogSeconds
        }
        lockedScaleDragCoordinate -= deltaX / exposureRenderer.pixelsPerStop(content)
        state.setLockedExposureCoordinate(lockedScaleDragCoordinate)
        val after = if (state.exposureLockMode == ExposureLockMode.APERTURE) {
            state.lockedApertureStop
        } else {
            state.lockedShutterLogSeconds
        }
        if (abs(after - before) > 0.0001) {
            animateLockedScaleTo(after)
            haptic()
        }
        listener?.onControlsChanged(false)
        invalidate()
    }

    private fun beginLockedScaleDrag(target: TouchTarget, x: Float) {
        dependentAnimator?.cancel()
        dependentAnimator = null
        releasedDependentCoordinate = Double.NaN
        val evAtIso = animatedExposureValue()
        frozenDependentCoordinate =
            if (state.exposureLockMode == ExposureLockMode.APERTURE) {
                state.lockedApertureStop - evAtIso
            } else {
                state.lockedShutterLogSeconds + evAtIso
            }
        touchTarget = target
        lastScaleX = x
        lockedScaleDragging = true
        lockedScaleAnimator?.cancel()
        lockedScaleAnimator = null
        lockedScaleDragCoordinate =
            if (state.exposureLockMode == ExposureLockMode.APERTURE) {
                state.lockedApertureStop
            } else {
                state.lockedShutterLogSeconds
            }
        lockedScaleDisplayCoordinate = lockedScaleDragCoordinate
    }

    private fun finishLockedScaleDrag() {
        state.snapLockedExposure()
        lockedScaleDragging = false
        lockedScaleDragCoordinate = Double.NaN
        if (lockedScaleAnimator == null) {
            lockedScaleDisplayCoordinate = Double.NaN
        }
        val from = frozenDependentCoordinate
        frozenDependentCoordinate = Double.NaN
        val evAtIso = animatedExposureValue()
        val target =
            if (state.exposureLockMode == ExposureLockMode.APERTURE) {
                state.lockedApertureStop - evAtIso
            } else {
                state.lockedShutterLogSeconds + evAtIso
            }
        if (!from.isFinite() || abs(target - from) < 0.0001) {
            releasedDependentCoordinate = Double.NaN
            return
        }
        releasedDependentCoordinate = from
        dependentAnimator?.cancel()
        dependentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedFraction.toDouble()
                releasedDependentCoordinate = from + (target - from) * fraction
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    releasedDependentCoordinate = Double.NaN
                    dependentAnimator = null
                    postInvalidateOnAnimation()
                }
            })
            start()
        }
    }

    private fun animateLockedScaleTo(target: Double) {
        val start = lockedScaleDisplayCoordinate.takeIf(Double::isFinite) ?: target
        lockedScaleAnimator?.cancel()
        if (abs(target - start) < 0.0001) {
            lockedScaleDisplayCoordinate = target
            postInvalidateOnAnimation()
            return
        }
        lockedScaleAnimator = ValueAnimator.ofFloat(start.toFloat(), target.toFloat()).apply {
            duration = 130L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lockedScaleDisplayCoordinate = (it.animatedValue as Float).toDouble()
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    lockedScaleDisplayCoordinate =
                        if (lockedScaleDragging) target else Double.NaN
                    if (lockedScaleAnimator === animation) {
                        lockedScaleAnimator = null
                    }
                    postInvalidateOnAnimation()
                }
            })
            start()
        }
    }

    private fun beginExposureLockDrag(y: Float) {
        exposureLockAnimator?.cancel()
        exposureLockAnimator = null
        if (exposureLockSliderFraction.isNaN()) {
            exposureLockSliderFraction =
                if (state.exposureLockMode == ExposureLockMode.APERTURE) 0f else 1f
        }
        exposureLockDragStartY = y
        exposureLockDragStartFraction = exposureLockSliderFraction
        exposureLockDragMoved = false
    }

    private fun updateExposureLockDrag(y: Float, track: RectF) {
        val span = track.height() * 0.5f
        if (span <= 0f) return
        val deltaY = y - exposureLockDragStartY
        if (abs(deltaY) >= touchSlop) {
            exposureLockDragMoved = true
        }
        exposureLockSliderFraction =
            (exposureLockDragStartFraction + deltaY / span).coerceIn(0f, 1f)
        setExposureLockModeForFraction(exposureLockSliderFraction)
        postInvalidateOnAnimation()
    }

    private fun finishExposureLockDrag(y: Float, track: RectF, cancelled: Boolean) {
        val targetFraction =
            if (cancelled) {
                if (state.exposureLockMode == ExposureLockMode.APERTURE) 0f else 1f
            } else if (!exposureLockDragMoved) {
                if (y < track.centerY()) 0f else 1f
            } else if (exposureLockSliderFraction < 0.5f) {
                0f
            } else {
                1f
            }
        setExposureLockModeForFraction(targetFraction)
        animateExposureLockTo(targetFraction)
    }

    private fun setExposureLockModeForFraction(fraction: Float) {
        val newMode =
            if (fraction < 0.5f) ExposureLockMode.APERTURE else ExposureLockMode.SHUTTER
        if (newMode != state.exposureLockMode) {
            dependentAnimator?.cancel()
            dependentAnimator = null
            frozenDependentCoordinate = Double.NaN
            releasedDependentCoordinate = Double.NaN
            state.setExposureLockMode(newMode, exactEvAtIso())
            haptic()
            listener?.onControlsChanged(false)
        }
    }

    private fun animateExposureLockTo(targetFraction: Float) {
        val startFraction = exposureLockSliderFraction
        exposureLockAnimator?.cancel()
        if (abs(targetFraction - startFraction) < 0.001f) {
            exposureLockSliderFraction = targetFraction
            postInvalidateOnAnimation()
            return
        }
        exposureLockAnimator = ValueAnimator.ofFloat(startFraction, targetFraction).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                exposureLockSliderFraction = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        exposureLockSliderFraction = targetFraction
                    }
                    if (exposureLockAnimator === animation) {
                        exposureLockAnimator = null
                    }
                    postInvalidateOnAnimation()
                }
            })
            start()
        }
    }

    private fun updateDial(x: Float, y: Float, dial: RectF) {
        val angle = angleFor(x, y, dial)
        var delta = angle - lastDialAngle
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        lastDialAngle = angle
        dialStepAccumulator += delta
        val stepAngle = if (state.isoAdjustMode) 10f else 7f
        val steps = (dialStepAccumulator / stepAngle).toInt()
        if (steps != 0) {
            if (state.isoAdjustMode) {
                state.isoIndex -= steps
            } else {
                state.exposureCompSteps = ExposureCompensationDial.applyDetents(
                    currentSixthStops = state.exposureCompSteps,
                    clockwiseDetents = steps,
                    step = state.exposureCompensationStep,
                    leftHanded = state.isLeftHanded,
                )
            }
            dialStepAccumulator -= steps * stepAngle
            haptic()
            listener?.onControlsChanged(false)
            invalidate()
        }
    }

    private fun angleFor(x: Float, y: Float, rect: RectF): Float =
        Math.toDegrees(
            atan2((y - rect.centerY()).toDouble(), (x - rect.centerX()).toDouble()),
        ).toFloat()

    private fun haptic() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime > 24L) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastClickTime = now
        }
    }

    override fun onDetachedFromWindow() {
        evAnimator?.cancel()
        dependentAnimator?.cancel()
        exposureLockAnimator?.cancel()
        lockedScaleAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        private const val METERING_SPINNER_PERIOD_MS = 820L
        private const val METERING_SPINNER_SWEEP_DEGREES = 108f
        private const val DEFAULT_SPOT_DIAMETER_FRACTION = 0.09f
        private const val OVERLAY_ALPHA = 168
    }
}
