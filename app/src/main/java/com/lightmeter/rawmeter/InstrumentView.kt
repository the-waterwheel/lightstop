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
        fun onControlsChanged(frameChanged: Boolean)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val lightBlack = Color.rgb(20, 20, 20)
    private val black: Int get() = if (state.isDarkMode) Color.WHITE else lightBlack
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
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private enum class TouchTarget {
        NONE,
        DIAL,
        ZOOM,
        APERTURE,
        SHUTTER,
        LOCK,
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
        drawPanels(canvas, g)
        drawCameraOverlay(canvas, g)
        drawExposureRows(canvas, g)
        drawDial(canvas, g)
        drawMeterButton(canvas, g)
        drawStatus(canvas, g)
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
        val spotRadius = min(g.cameraFrame.width(), g.cameraFrame.height()) * 0.045f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f * density
        paint.color = surfaceColor
        canvas.drawCircle(g.cameraFrame.centerX(), g.cameraFrame.centerY(), spotRadius + density, paint)
        paint.color = black
        paint.strokeWidth = 0.9f * density
        canvas.drawCircle(g.cameraFrame.centerX(), g.cameraFrame.centerY(), spotRadius, paint)
        canvas.drawCircle(g.cameraFrame.centerX(), g.cameraFrame.centerY(), 1.4f * density, paint)

        drawOutlinedButton(
            canvas,
            g.formatButton,
            formatShortLabel(state.frameFormat),
            formatMenuOpen,
        )
        if (formatMenuOpen) drawFormatMenu(canvas, g)
        drawOrientationButton(canvas, g.orientationButton, g.landscape)
        drawZoom(canvas, g.zoomTrack)
        drawMoreButton(canvas, g.moreButton)

        val equivalent = state.equivalent35mm()
        val focalText = buildString {
            val focal = state.cameraInfo.focalLengthMm
            if (focal > 0f) append("${"%.1f".format(focal)} mm")
            if (equivalent != null) append("  ≈ ${equivalent} mm")
        }
        if (focalText.isNotBlank()) {
            paint.style = Paint.Style.FILL
            paint.color = surfaceColor
            paint.textSize = 10f * density
            paint.typeface = Typeface.DEFAULT_BOLD
            val textWidth = paint.measureText(focalText)
            val baseline = g.cameraFrame.bottom - 8f * density
            canvas.drawRoundRect(
                RectF(
                    g.cameraFrame.centerX() - textWidth / 2f - 6f * density,
                    baseline - 13f * density,
                    g.cameraFrame.centerX() + textWidth / 2f + 6f * density,
                    baseline + 3f * density,
                ),
                4f * density,
                4f * density,
                paint,
            )
            paint.color = black
            canvas.drawText(focalText, g.cameraFrame.centerX() - textWidth / 2f, baseline, paint)
        }
    }

    private fun drawFormatMenu(canvas: Canvas, g: LayoutGeometry) {
        formatOptionRects(g).forEachIndexed { index, rect ->
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
                formatShortLabel(FrameFormat.ALL[index]),
                rect.centerX(),
                rect.centerY(),
                paint,
            )
        }
    }

    private fun formatOptionRects(g: LayoutGeometry): List<RectF> {
        val left: Float
        val right: Float
        if (g.formatButton.centerX() < g.orientationButton.centerX()) {
            left = g.formatButton.right + 4f * density
            right = g.orientationButton.left - 4f * density
        } else {
            left = g.orientationButton.right + 4f * density
            right = g.formatButton.left - 4f * density
        }
        val availableWidth = (right - left).coerceAtLeast(0f)
        if (availableWidth < 8f * density) return emptyList()
        val gap = 2f * density
        val itemWidth =
            ((availableWidth - gap * (FrameFormat.ALL.size - 1)) / FrameFormat.ALL.size)
                .coerceAtLeast(18f * density)
        return FrameFormat.ALL.indices.mapNotNull { index ->
            val itemLeft = left + index * (itemWidth + gap)
            val itemRight = min(itemLeft + itemWidth, right)
            if (itemLeft >= right) null else RectF(
                itemLeft,
                g.formatButton.top,
                itemRight,
                g.formatButton.bottom,
            )
        }
    }

    private fun formatShortLabel(format: FrameFormat): String = when (format.id) {
        "half" -> "半格"
        "645" -> "645"
        "66" -> "6×6"
        "67" -> "6×7"
        "69" -> "6×9"
        "xpan" -> "XPan"
        else -> "135"
    }

    private fun drawOrientationButton(canvas: Canvas, rect: RectF, landscape: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f * density
        paint.color = black
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
        paint.color = red
        canvas.drawArc(arc, 205f, 86f, false, paint)
    }

    private fun drawMoreButton(canvas: Canvas, rect: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = black
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = black
        val spacing = rect.width() * 0.18f
        val radius = 1.5f * density
        canvas.drawCircle(rect.centerX() - spacing, rect.centerY(), radius, paint)
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint)
        canvas.drawCircle(rect.centerX() + spacing, rect.centerY(), radius, paint)
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
        var apertureCenter: Double
        var shutterCenter: Double
        if (state.exposureLockMode == ExposureLockMode.APERTURE) {
            apertureCenter = lockedScaleDisplayCoordinate.takeIf(Double::isFinite)
                ?: state.lockedApertureStop
            shutterCenter = state.lockedApertureStop - evAtIso
        } else {
            shutterCenter = lockedScaleDisplayCoordinate.takeIf(Double::isFinite)
                ?: state.lockedShutterLogSeconds
            apertureCenter = state.lockedShutterLogSeconds + evAtIso
        }
        val dependentOverride = when {
            frozenDependentCoordinate.isFinite() -> frozenDependentCoordinate
            releasedDependentCoordinate.isFinite() -> releasedDependentCoordinate
            else -> Double.NaN
        }
        if (dependentOverride.isFinite()) {
            if (state.exposureLockMode == ExposureLockMode.APERTURE) {
                shutterCenter = dependentOverride
            } else {
                apertureCenter = dependentOverride
            }
        }
        drawExposureScale(
            canvas = canvas,
            rect = g.apertureRow,
            lockTrack = g.exposureLockTrack,
            title = "f",
            centerCoordinate = apertureCenter,
            apertureRow = true,
        )
        drawExposureScale(
            canvas = canvas,
            rect = g.shutterRow,
            lockTrack = g.exposureLockTrack,
            title = "s",
            centerCoordinate = shutterCenter,
            apertureRow = false,
        )
        drawExposureLock(canvas, g.exposureLockTrack)
    }

    private fun drawExposureScale(
        canvas: Canvas,
        rect: RectF,
        lockTrack: RectF,
        title: String,
        centerCoordinate: Double,
        apertureRow: Boolean,
    ) {
        val foreground = if (state.isDarkMode || apertureRow) Color.WHITE else lightBlack
        val background = if (state.isDarkMode || apertureRow) Color.BLACK else Color.WHITE
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRect(rect, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = black
        canvas.drawRect(rect, paint)

        val titleWidth = 46f * density
        val content = exposureScaleContent(rect, lockTrack, titleWidth)
        val titleLeft = if (state.isLeftHanded) rect.right - titleWidth else rect.left
        paint.style = Paint.Style.FILL
        paint.color = foreground
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 12f * density
        drawCenteredText(canvas, title, titleLeft + titleWidth * 0.24f, rect.centerY(), paint)
        paint.textSize = 8f * density
        paint.typeface = Typeface.DEFAULT
        val exactValue = if (apertureRow) {
            formatExactAperture(
                ExposureMath.apertureValueForCoordinate(
                    centerCoordinate,
                    state.apertureStep,
                ),
            )
        } else {
            formatExactShutter(
                ExposureMath.shutterValueForCoordinate(
                    centerCoordinate,
                    state.shutterStep,
                ),
            )
        }
        drawCenteredText(
            canvas,
            exactValue,
            titleLeft + titleWidth * 0.65f,
            rect.centerY(),
            paint,
        )

        val baselineY = rect.centerY() + 9f * density
        val pixelsPerStop = exposurePixelsPerStop(content)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * density
        paint.color = foreground
        canvas.drawLine(content.left, baselineY, content.right, baselineY, paint)

        canvas.save()
        canvas.clipRect(content)
        paint.textSize = 7.5f * density
        paint.typeface = Typeface.DEFAULT
        if (apertureRow) {
            var lastLabelRight = content.left - 4f * density
            val ticks = ExposureMath.apertureTicks(state.apertureStep)
            ticks.forEachIndexed { index, tick ->
                val coordinate = tick.coordinate
                val x = content.centerX() +
                    ((coordinate - centerCoordinate) * pixelsPerStop).toFloat()
                if (x in content.left..content.right) {
                    val label =
                        if (index % state.apertureStep.denominator == 0) {
                            formatApertureTick(tick.nominalValue)
                        } else {
                            null
                        }
                    val halfLabel = label?.let { paint.measureText(it) / 2f } ?: 0f
                    val showLabel = label != null &&
                        x - halfLabel >= lastLabelRight + 3f * density &&
                        x + halfLabel <= content.right
                    drawScaleTick(
                        canvas,
                        x,
                        baselineY,
                        if (showLabel) label else null,
                        foreground,
                    )
                    if (showLabel) lastLabelRight = x + halfLabel
                }
            }
        } else {
            var lastLabelLeft = content.right + 4f * density
            val ticks = ExposureMath.shutterTicks(state.shutterStep)
            ticks.forEachIndexed { index, tick ->
                val coordinate = tick.coordinate
                val x = content.centerX() +
                    ((coordinate - centerCoordinate) * pixelsPerStop).toFloat()
                if (x in content.left..content.right) {
                    val label =
                        if (index % state.shutterStep.denominator == 0) {
                            ExposureMath.formatShutter(tick.nominalValue)
                        } else {
                            null
                        }
                    val halfLabel = label?.let { paint.measureText(it) / 2f } ?: 0f
                    val showLabel = label != null &&
                        x + halfLabel <= lastLabelLeft - 3f * density &&
                        x - halfLabel >= content.left
                    drawScaleTick(
                        canvas,
                        x,
                        baselineY,
                        if (showLabel) label else null,
                        foreground,
                    )
                    if (showLabel) lastLabelLeft = x - halfLabel
                }
            }
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = red
        canvas.drawLine(
            content.centerX(),
            baselineY - 11f * density,
            content.centerX(),
            baselineY + 3f * density,
            paint,
        )
        canvas.restore()
    }

    private fun exposureScaleContent(
        rect: RectF,
        lockTrack: RectF,
        titleWidth: Float = 46f * density,
    ): RectF = if (state.isLeftHanded) {
        RectF(
            lockTrack.right + 4f * density,
            rect.top,
            rect.right - titleWidth,
            rect.bottom,
        )
    } else {
        RectF(
            rect.left + titleWidth,
            rect.top,
            lockTrack.left - 4f * density,
            rect.bottom,
        )
    }

    private fun drawScaleTick(
        canvas: Canvas,
        x: Float,
        baselineY: Float,
        label: String?,
        color: Int,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = color
        val tickHeight = if (label != null) 10f * density else 6f * density
        canvas.drawLine(x, baselineY - tickHeight, x, baselineY + 2f * density, paint)
        if (label != null) {
            paint.style = Paint.Style.FILL
            paint.textSize = 7.5f * density
            paint.typeface = Typeface.DEFAULT
            drawCenteredText(canvas, label, x, baselineY - 16f * density, paint)
        }
    }

    private fun drawExposureLock(canvas: Canvas, track: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
        canvas.drawRect(track, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = black
        canvas.drawRect(track, paint)
        val x = track.centerX()
        val topY = track.top + track.height() * 0.25f
        val bottomY = track.top + track.height() * 0.75f
        canvas.drawLine(x, topY, x, bottomY, paint)

        if (exposureLockSliderFraction.isNaN()) {
            exposureLockSliderFraction =
                if (state.exposureLockMode == ExposureLockMode.APERTURE) 0f else 1f
        }
        val knobY = topY + (bottomY - topY) * exposureLockSliderFraction
        val knobSize = min(28f * density, track.width() - 6f * density)
        val knob = RectF(
            x - knobSize / 2f,
            knobY - knobSize / 2f,
            x + knobSize / 2f,
            knobY + knobSize / 2f,
        )
        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
        canvas.drawRect(knob, paint)
        paint.style = Paint.Style.STROKE
        paint.color = black
        paint.strokeWidth = 1.4f * density
        canvas.drawRect(knob, paint)

        val shackle = RectF(
            x - knobSize * 0.22f,
            knobY - knobSize * 0.25f,
            x + knobSize * 0.22f,
            knobY + knobSize * 0.18f,
        )
        canvas.drawArc(shackle, 180f, 180f, false, paint)
        val body = RectF(
            x - knobSize * 0.28f,
            knobY - knobSize * 0.04f,
            x + knobSize * 0.28f,
            knobY + knobSize * 0.28f,
        )
        canvas.drawRect(body, paint)
        paint.style = Paint.Style.FILL
        paint.color = red
        canvas.drawCircle(x, knobY + knobSize * 0.10f, 1.7f * density, paint)
    }

    private fun exposurePixelsPerStop(content: RectF): Double =
        maxOf(62f * density, content.width() / 4.6f).toDouble()

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

    private fun formatApertureTick(value: Double): String =
        if (value >= 10.0 || abs(value - value.roundToInt()) < 0.02) {
            value.roundToInt().toString()
        } else {
            "%.1f".format(value)
        }

    private fun formatExactAperture(value: Double): String =
        if (value >= 10.0) "%.1f".format(value) else "%.2f".format(value)

    private fun formatExactShutter(seconds: Double): String = when {
        seconds >= 10.0 -> "${seconds.roundToInt()}″"
        seconds >= 1.0 -> "${"%.1f".format(seconds)}″"
        seconds >= 0.3 -> "${"%.2f".format(seconds)}″"
        else -> "1/${(1.0 / seconds).roundToInt()}"
    }

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
        for (step in -30..30) {
            val baseAngle = if (state.isLeftHanded) 180f else 0f
            val angle = baseAngle + (step - state.exposureCompSteps) * spacing
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
    ) {
        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = if (accented) red else black
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 9f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        drawCenteredText(canvas, text, rect.centerX(), rect.centerY(), paint)
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
                    val selectedFormat = formatOptionRects(g)
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
                    g.moreButton.contains(event.x, event.y) -> {
                        formatMenuOpen = false
                        haptic()
                        listener?.onMoreRequested()
                        return true
                    }
                    g.orientationButton.contains(event.x, event.y) -> {
                        formatMenuOpen = false
                        haptic()
                        listener?.onOrientationToggle()
                        return true
                    }
                    g.formatButton.contains(event.x, event.y) -> {
                        formatMenuOpen = !formatMenuOpen
                        haptic()
                        invalidate()
                        return true
                    }
                    g.isoModeButton.contains(event.x, event.y) -> {
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
                    g.dial.contains(event.x, event.y) -> {
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
        val content = exposureScaleContent(rect, lockTrack)
        val deltaX = x - lastScaleX
        lastScaleX = x
        val before = if (state.exposureLockMode == ExposureLockMode.APERTURE) {
            state.lockedApertureStop
        } else {
            state.lockedShutterLogSeconds
        }
        lockedScaleDragCoordinate -= deltaX / exposurePixelsPerStop(content)
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
                state.exposureCompSteps -= steps
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
}
