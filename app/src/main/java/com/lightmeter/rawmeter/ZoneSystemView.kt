package com.lightmeter.rawmeter

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private typealias Geometry = ZoneLayoutGeometry

class ZoneSystemView(
    context: Context,
    private val state: MeterState,
) : View(context) {

    interface Listener {
        fun onExitDrag(progress: Float, released: Boolean)
        fun onMarkRequested(marker: ZoneMarker)
        fun onMarkerRemoved(markerId: Int)
        fun onMarkersCleared(markerIds: List<Int>)
        fun onOrientationToggle()
        fun onPreviewMappingChanged()
        fun onZoomMappingChanged(zoom: Float)
        fun onControlsChanged(frameChanged: Boolean)
        fun onSettingsRequested()
        fun onToolsRequested()
    }

    private enum class TouchTarget {
        NONE,
        EXIT,
        APERTURE,
        SHUTTER,
        LOCK,
        ZONE_RAIL,
        LIST,
        CLEAR,
        MARK_BUTTON,
        PREVIEW_MARK,
        ZOOM,
        FORMAT,
        ORIENTATION,
        SETTINGS,
        TOOLS,
    }

    private data class MarkerDisplayMotion(
        var fromX: Float,
        var fromY: Float,
        var targetX: Float,
        var targetY: Float,
        var startedAtMs: Long,
        var durationMs: Long,
        var lastTargetAtMs: Long,
    )

    private data class MarkerDisplaySample(
        val x: Float,
        val y: Float,
        val animating: Boolean,
    )

    val session = ZoneMeterSession()
    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val lightBlack = Color.rgb(20, 20, 20)
    private val nightForeground = Color.rgb(210, 210, 206)
    private val foreground: Int get() = if (state.isDarkMode) nightForeground else lightBlack
    private val surface: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val railColor: Int
        get() = if (state.isDarkMode) Color.rgb(58, 58, 56) else Color.rgb(164, 164, 160)
    private val dividerColor: Int
        get() = if (state.isDarkMode) Color.rgb(84, 84, 80) else Color.rgb(190, 190, 186)
    private val red = Color.rgb(166, 27, 36)
    private val latitudeOutsideRed: Int
        get() = if (state.isDarkMode) Color.rgb(196, 116, 120) else Color.rgb(231, 151, 155)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var geometry: Geometry? = null
    private var appliedLatitudeRange: FilmLatitudeRange? = null
    private var touchTarget = TouchTarget.NONE
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var exposureStartCoordinate = 0.0
    private var railStartExposureEv100 = 0.0
    private var listScrollOffset = 0f
    private var listScrollStart = 0f
    private var listMarkerId: Int? = null
    private var listSwipeDistance = 0f
    private var listGestureHorizontal = false
    private var clearDragDistance = 0f
    private var exitProgress = 0f
    private var modeTransitionEnabled = true
    private var formatMenuOpen = false
    private var lastHapticAt = 0L
    private var lockSliderFraction = Float.NaN
    private var lockDragStartY = 0f
    private var lockDragStartFraction = 0f
    private var lockDragMoved = false
    private var lockAnimator: ValueAnimator? = null
    private val markerDisplayMotions = mutableMapOf<Int, MarkerDisplayMotion>()

    init {
        setWillNotDraw(false)
        isClickable = true
    }

    fun calculatePreviewFrame(width: Int, height: Int): RectF =
        calculateGeometry(width, height).cameraFrame

    fun enter() {
        session.initializeFromMeter(state)
        geometry = calculateGeometry(width, height)
        markerDisplayMotions.clear()
        formatMenuOpen = false
        exitProgress = 0f
        lockAnimator?.cancel()
        lockAnimator = null
        lockSliderFraction = if (state.exposureLockMode == ExposureLockMode.APERTURE) 0f else 1f
        invalidate()
    }

    /** Cancels and disables the covered Zone/Normal handle while the Tools host is visible. */
    fun setModeTransitionEnabled(enabled: Boolean) {
        modeTransitionEnabled = enabled
        if (!enabled) {
            if (touchTarget == TouchTarget.EXIT) touchTarget = TouchTarget.NONE
            exitProgress = 0f
        }
        invalidate()
    }

    fun completeMeasurement(reading: MeterReading): ZoneMarker? {
        val marker = session.completePending(reading, session.iso, state.exposureLockMode)
        session.syncLockedCoordinate(state)
        invalidate()
        return marker
    }

    fun failMeasurement(): ZoneMarker? {
        val removed = session.cancelPending()
        invalidate()
        return removed
    }

    fun updateMarkerTracking(id: Int, x: Float, y: Float, trackingState: ZoneTrackingState) {
        val now = SystemClock.uptimeMillis()
        val markerBeforeUpdate = session.markers.firstOrNull { it.id == id }
        val existing = markerDisplayMotions[id]
        val current = existing?.sampleAt(now) ?: MarkerDisplaySample(
            markerBeforeUpdate?.normalizedX ?: x,
            markerBeforeUpdate?.normalizedY ?: y,
            false,
        )
        session.updateTracking(id, x, y, trackingState)
        if (markerBeforeUpdate != null) {
            val updateInterval = existing?.let { now - it.lastTargetAtMs }
                ?.coerceIn(MIN_MARKER_INTERPOLATION_MS, MAX_MARKER_INTERPOLATION_MS)
                ?: DEFAULT_MARKER_INTERPOLATION_MS
            markerDisplayMotions[id] = MarkerDisplayMotion(
                fromX = current.x,
                fromY = current.y,
                targetX = x,
                targetY = y,
                startedAtMs = now,
                durationMs = updateInterval,
                lastTargetAtMs = now,
            )
        }
        postInvalidateOnAnimation()
    }

    /** Counter Android's forced app-orientation rotation while the physical camera stays still. */
    fun remapMarkersForLayoutOrientation(fromLandscape: Boolean, toLandscape: Boolean) {
        if (fromLandscape == toLandscape) return
        markerDisplayMotions.clear()
        session.markers.forEach { marker ->
            val (x, y) = ZoneCoordinateMapper.remapForLayoutOrientation(
                marker.normalizedX,
                marker.normalizedY,
                fromLandscape,
                toLandscape,
            )
            marker.normalizedX = x
            marker.normalizedY = y
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geometry = calculateGeometry(w, h)
        listScrollOffset = 0f
        // Orientation/layout changes are discontinuous coordinate remaps, not camera motion.
        markerDisplayMotions.clear()
    }

    fun setAppliedLatitude(range: FilmLatitudeRange?) {
        appliedLatitudeRange = range
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = geometry ?: calculateGeometry(width, height).also { geometry = it }
        drawSurfaceOutsidePreview(canvas, g.cameraFrame)
        drawPanels(canvas, g)
        drawCameraOverlay(canvas, g)
        drawExposureRows(canvas, g)
        drawZoneScale(canvas, g)
        drawMarkerRail(canvas, g)
        drawRecords(canvas, g)
    }

    private fun drawSurfaceOutsidePreview(canvas: Canvas, frame: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, paint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, paint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, paint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), paint)
    }

    private fun drawPanels(canvas: Canvas, g: Geometry) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f * density
        paint.color = foreground
        canvas.drawRect(g.previewPanel, paint)
        canvas.drawRect(g.cameraFrame, paint)
        canvas.drawRect(g.recordPanel, paint)
        if (g.landscape) {
            val dividerX = if (g.previewPanel.centerX() < g.zoneScale.centerX()) {
                (g.previewPanel.right + g.zoneScale.left) / 2f
            } else {
                (g.zoneScale.right + g.previewPanel.left) / 2f
            }
            canvas.drawLine(dividerX, 0f, dividerX, height.toFloat(), paint)
        } else {
            canvas.drawLine(0f, g.apertureRow.top - 4f * density, width.toFloat(), g.apertureRow.top - 4f * density, paint)
        }
    }

    private fun drawCameraOverlay(canvas: Canvas, g: Geometry) {
        val spotRadius = min(g.cameraFrame.width(), g.cameraFrame.height()) * 0.045f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = surface
        canvas.drawCircle(
            g.cameraFrame.centerX(),
            g.cameraFrame.centerY(),
            spotRadius + density,
            paint,
        )
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawCircle(g.cameraFrame.centerX(), g.cameraFrame.centerY(), spotRadius, paint)
        canvas.drawCircle(
            g.cameraFrame.centerX(),
            g.cameraFrame.centerY(),
            1.4f * density,
            paint,
        )

        drawPreviewMarkers(canvas, g.cameraFrame)
        if (state.measuring) {
            val pending = session.pendingMarkerId?.let { pendingId ->
                session.markers.firstOrNull { it.id == pendingId }
            }
            val centerX = pending?.let { g.cameraFrame.left + it.normalizedX * g.cameraFrame.width() }
                ?: g.cameraFrame.centerX()
            val centerY = pending?.let { g.cameraFrame.top + it.normalizedY * g.cameraFrame.height() }
                ?: g.cameraFrame.centerY()
            drawMeteringSpinner(canvas, centerX, centerY, spotRadius)
            postInvalidateOnAnimation()
        }
        drawOutlinedButton(
            canvas,
            g.formatButton,
            shortFormatLabel(),
            formatMenuOpen,
            overlayAlpha(g.formatButton, g.cameraFrame),
        )
        drawOrientationButton(
            canvas,
            g.orientationButton,
            overlayAlpha(g.orientationButton, g.cameraFrame),
        )
        drawSettingsButton(canvas, g.settingsButton, overlayAlpha(g.settingsButton, g.cameraFrame))
        drawToolsButton(canvas, g.toolsButton, overlayAlpha(g.toolsButton, g.cameraFrame))
        drawZoom(canvas, g.zoomTrack)
        drawNormalHandle(canvas, g)
        drawFocalInfo(canvas, g)
        if (formatMenuOpen) drawFormatMenu(canvas, g)
    }

    private fun drawMeteringSpinner(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        spotRadius: Float,
    ) {
        val radius = spotRadius + 6f * density
        val bounds = RectF(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius,
        )
        val gray = if (state.isDarkMode) 168 else 132
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.color = Color.argb(42, gray, gray, gray)
        canvas.drawCircle(centerX, centerY, radius, paint)

        val phase = (SystemClock.uptimeMillis() % METERING_SPINNER_PERIOD_MS).toFloat() /
            METERING_SPINNER_PERIOD_MS
        paint.strokeWidth = 2.6f * density
        paint.color = Color.argb(158, gray, gray, gray)
        canvas.drawArc(bounds, phase * 360f - 90f, METERING_SPINNER_SWEEP_DEGREES, false, paint)
    }

    private fun drawFocalInfo(canvas: Canvas, g: Geometry) {
        val frame = g.cameraFrame
        val equivalent = state.equivalentFrameFocalMm() ?: return
        val focalText = "≈ $equivalent mm (${shortFormatLabel()})"

        boldPaint.textSize = 10f * density
        val textWidth = boldPaint.measureText(focalText)
        val baseline = frame.bottom - 8f * density
        val horizontalPadding = 6f * density
        // Anchor the label to the viewfinder's bottom-right corner in every orientation;
        // the clamp keeps it inside the frame on devices with narrow fitted images.
        val left = max(frame.left, frame.right - textWidth - horizontalPadding * 2f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(
            168,
            Color.red(surface),
            Color.green(surface),
            Color.blue(surface),
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
        boldPaint.color = foreground
        canvas.drawText(focalText, left + horizontalPadding, baseline, boldPaint)
    }

    private fun drawNormalHandle(canvas: Canvas, g: Geometry) {
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRoundRect(g.normalHandle, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = foreground
        canvas.drawRoundRect(g.normalHandle, 4f * density, 4f * density, paint)
        boldPaint.color = foreground
        boldPaint.textSize = 8.5f * density
        if (g.landscape) {
            canvas.save()
            canvas.rotate(-90f, g.normalHandle.centerX(), g.normalHandle.centerY())
            drawCenteredText(canvas, "normal", g.normalHandle.centerX(), g.normalHandle.centerY(), boldPaint)
            canvas.restore()
        } else {
            drawCenteredText(canvas, "normal", g.normalHandle.centerX(), g.normalHandle.centerY(), boldPaint)
        }

        if (exitProgress > 0f || touchTarget == TouchTarget.EXIT) {
            paint.style = Paint.Style.FILL
            paint.color = foreground
            val path = Path()
            if (g.landscape) {
                val size = min(g.normalHandle.width(), g.normalHandle.height()) * 0.42f
                val direction = ModeTransitionDirection.normalEntrySign(state.isLeftHanded)
                val x = if (direction > 0f) {
                    g.normalHandle.right + 5f * density + exitProgress * 18f * density
                } else {
                    g.normalHandle.left - 5f * density - exitProgress * 18f * density
                }
                path.moveTo(x, g.normalHandle.centerY() - size)
                path.lineTo(x + direction * size, g.normalHandle.centerY())
                path.lineTo(x, g.normalHandle.centerY() + size)
            } else {
                val size = min(g.normalHandle.width(), g.normalHandle.height()) * 0.42f
                val y = g.normalHandle.bottom + 4f * density + exitProgress * 18f * density
                path.moveTo(g.normalHandle.centerX() - size, y)
                path.lineTo(g.normalHandle.centerX(), y + size)
                path.lineTo(g.normalHandle.centerX() + size, y)
            }
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun drawPreviewMarkers(canvas: Canvas, frame: RectF) {
        val now = SystemClock.uptimeMillis()
        val markerIds = session.markers.mapTo(mutableSetOf()) { it.id }
        markerDisplayMotions.keys.retainAll(markerIds)
        var animationPending = false
        session.markers.forEach { marker ->
            val display = markerDisplayMotions[marker.id]?.sampleAt(now)
                ?: MarkerDisplaySample(marker.normalizedX, marker.normalizedY, false)
            animationPending = animationPending || display.animating
            if (display.x !in 0f..1f || display.y !in 0f..1f) {
                return@forEach
            }
            val x = frame.left + display.x * frame.width()
            val y = frame.top + display.y * frame.height()
            val radius = 9f * density
            val selected = marker.id == session.selectedMarkerId
            val outsideLatitude = isOutsideAppliedLatitude(marker)
            // RAW capture temporarily freezes the preview and can make tracking report
            // UNCERTAIN/LOST. Keep the marker geometry stable: every state uses a solid circle.
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            paint.color = when {
                outsideLatitude -> latitudeOutsideRed
                selected -> Color.argb(150, Color.red(red), Color.green(red), Color.blue(red))
                else -> surface
            }
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = if (outsideLatitude || selected) red else foreground
            canvas.drawCircle(x, y, radius, paint)
            boldPaint.color = if ((outsideLatitude || selected) && !state.isDarkMode) Color.WHITE else foreground
            boldPaint.textSize = 7f * density
            drawCenteredText(canvas, marker.id.toString(), x, y, boldPaint)
        }
        if (animationPending) postInvalidateOnAnimation()
    }

    private fun MarkerDisplayMotion.sampleAt(nowMs: Long): MarkerDisplaySample {
        if (durationMs <= 0L) return MarkerDisplaySample(targetX, targetY, false)
        val fraction = ((nowMs - startedAtMs).toFloat() / durationMs).coerceIn(0f, 1f)
        return MarkerDisplaySample(
            x = fromX + (targetX - fromX) * fraction,
            y = fromY + (targetY - fromY) * fraction,
            animating = fraction < 1f,
        )
    }

    private fun drawExposureRows(canvas: Canvas, g: Geometry) {
        drawExposureScale(canvas, g.apertureRow, g.lockTrack, "f", session.apertureCoordinate, true)
        drawExposureScale(canvas, g.shutterRow, g.lockTrack, "s", session.shutterCoordinate, false)
        drawLock(canvas, g.lockTrack)
    }

    private fun drawExposureScale(
        canvas: Canvas,
        rect: RectF,
        lockTrack: RectF,
        title: String,
        centerCoordinate: Double,
        aperture: Boolean,
    ) {
        val background = if (state.isDarkMode || aperture) Color.BLACK else Color.WHITE
        val scaleForeground = when {
            state.isDarkMode -> nightForeground
            aperture -> Color.WHITE
            else -> lightBlack
        }
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRect(rect, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawRect(rect, paint)

        val content = RectF(rect)
        if (state.isLeftHanded) content.left = lockTrack.right else content.right = lockTrack.left
        val titleWidth = 40f * density
        val scaleLeft = content.left + titleWidth
        val scaleRight = content.right - 5f * density
        val centerX = (scaleLeft + scaleRight) / 2f
        val pixelsPerStop = max(25f * density, (scaleRight - scaleLeft) / 5.4f)
        val baselineY = rect.centerY() + rect.height() * 0.18f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.9f * density
        paint.color = scaleForeground
        canvas.drawLine(scaleLeft, baselineY, scaleRight, baselineY, paint)
        val ticks = if (aperture) ExposureMath.apertureTicks(state.apertureStep) else ExposureMath.shutterTicks(state.shutterStep)
        ticks.forEachIndexed { index, tick ->
            val x = centerX + ((tick.coordinate - centerCoordinate) * pixelsPerStop).toFloat()
            if (x !in scaleLeft..scaleRight) return@forEachIndexed
            val full = index % (if (aperture) state.apertureStep.denominator else state.shutterStep.denominator) == 0
            val tickHeight = if (full) rect.height() * 0.22f else rect.height() * 0.13f
            canvas.drawLine(x, baselineY - tickHeight, x, baselineY + tickHeight, paint)
            if (full) {
                paint.style = Paint.Style.FILL
                paint.textSize = 6.6f * density
                paint.typeface = Typeface.DEFAULT
                paint.color = scaleForeground
                val label = if (aperture) {
                    val value = tick.nominalValue
                    if (abs(value - value.roundToInt()) < 0.04) value.roundToInt().toString() else "%.1f".format(value)
                } else {
                    ExposureMath.formatShutter(tick.nominalValue)
                }
                drawCenteredText(canvas, label, x, rect.top + rect.height() * 0.23f, paint)
                paint.style = Paint.Style.STROKE
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = red
        canvas.drawRect(centerX - 1.1f * density, rect.top + 5f * density, centerX + 1.1f * density, rect.bottom - 5f * density, paint)

        val value = if (aperture) {
            val number = ExposureMath.apertureValueForCoordinate(centerCoordinate, state.apertureStep)
            if (number >= 10.0 || abs(number - number.toInt()) < 0.02) number.toInt().toString() else "%.1f".format(number)
        } else {
            ExposureMath.formatShutter(ExposureMath.shutterValueForCoordinate(centerCoordinate, state.shutterStep))
        }
        paint.color = scaleForeground
        paint.textSize = 7.5f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(title, content.left + 7f * density, rect.centerY() + 3f * density, paint)
        paint.textSize = 6.5f * density
        canvas.drawText(value, content.left + 18f * density, rect.centerY() + 3f * density, paint)
    }

    private fun drawLock(canvas: Canvas, track: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRect(track, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawRect(track, paint)
        val x = track.centerX()
        val topY = track.top + track.height() * 0.25f
        val bottomY = track.top + track.height() * 0.75f
        canvas.drawLine(x, topY, x, bottomY, paint)
        if (lockSliderFraction.isNaN()) {
            lockSliderFraction = if (state.exposureLockMode == ExposureLockMode.APERTURE) 0f else 1f
        }
        val y = topY + (bottomY - topY) * lockSliderFraction
        val knobSize = min(28f * density, track.width() - 6f * density)
        val knob = RectF(
            x - knobSize / 2f,
            y - knobSize / 2f,
            x + knobSize / 2f,
            y + knobSize / 2f,
        )
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRect(knob, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = foreground
        canvas.drawRect(knob, paint)
        val shackle = RectF(
            x - knobSize * 0.22f,
            y - knobSize * 0.25f,
            x + knobSize * 0.22f,
            y + knobSize * 0.18f,
        )
        canvas.drawArc(shackle, 180f, 180f, false, paint)
        val body = RectF(
            x - knobSize * 0.28f,
            y - knobSize * 0.04f,
            x + knobSize * 0.28f,
            y + knobSize * 0.28f,
        )
        canvas.drawRect(body, paint)
        paint.style = Paint.Style.FILL
        paint.color = red
        canvas.drawCircle(x, y + knobSize * 0.10f, 1.7f * density, paint)
    }

    private fun drawZoneScale(canvas: Canvas, g: Geometry) {
        val rect = g.zoneScale
        for (zone in 0..10) {
            val cell = zoneCell(rect, zone, g.landscape)
            val maxLuma = if (state.isDarkMode) 210 else 255
            val value = (maxLuma * zone / 10f).roundToInt()
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(value, value, value)
            canvas.drawRect(cell, paint)
            boldPaint.color = if (value < 120) nightForeground else lightBlack
            boldPaint.textSize = if (g.landscape) 6.5f * density else 7.5f * density
            drawCenteredText(canvas, ZoneMeterSession.ZONE_LABELS[zone], cell.centerX(), cell.centerY(), boldPaint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        drawLatitudeBoundaries(canvas, rect, g.landscape)
    }

    private fun drawMarkerRail(canvas: Canvas, g: Geometry) {
        paint.style = Paint.Style.FILL
        paint.color = railColor
        canvas.drawRect(g.markerRail, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawRect(g.markerRail, paint)
        drawLatitudeBoundaries(canvas, g.markerRail, g.landscape)
        val meanZone = session.weightedMeanZone(session.iso)
        if (meanZone != null) {
            paint.color = red
            paint.strokeWidth = 1.2f * density
            if (g.landscape) {
                val y = zonePositionY(g.markerRail, meanZone)
                canvas.drawLine(g.markerRail.left, y, g.markerRail.right, y, paint)
            } else {
                val x = zonePositionX(g.markerRail, meanZone)
                canvas.drawLine(x, g.markerRail.top, x, g.markerRail.bottom, paint)
            }
        }
        session.markers.filter { it.ev100 != null }.forEachIndexed { index, marker ->
            val zone = session.zoneFor(marker, session.iso) ?: return@forEachIndexed
            val x: Float
            val y: Float
            if (g.landscape) {
                x = g.markerRail.centerX() + ((index % 3) - 1) * 7f * density
                y = zonePositionY(g.markerRail, zone)
            } else {
                x = zonePositionX(g.markerRail, zone)
                y = g.markerRail.centerY() + ((index % 3) - 1) * 6f * density
            }
            val radius = 7.5f * density
            val outsideLatitude = isOutsideAppliedLatitude(marker)
            paint.style = Paint.Style.FILL
            paint.color = when {
                outsideLatitude -> latitudeOutsideRed
                marker.id == session.selectedMarkerId -> Color.argb(170, Color.red(red), Color.green(red), Color.blue(red))
                else -> surface
            }
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = if (outsideLatitude || marker.id == session.selectedMarkerId) red else foreground
            canvas.drawCircle(x, y, radius, paint)
            boldPaint.color = if (outsideLatitude && !state.isDarkMode) Color.WHITE else foreground
            boldPaint.textSize = 6f * density
            drawCenteredText(canvas, marker.id.toString(), x, y, boldPaint)
        }
    }

    private fun drawRecords(canvas: Canvas, g: Geometry) {
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRect(g.recordPanel, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawRect(g.recordPanel, paint)

        drawClearSlider(canvas, g)
        drawMarkButton(canvas, g.markButton)

        val rowHeight = 34f * density
        val totalHeight = session.markers.size * rowHeight
        val maxScroll = max(0f, totalHeight - g.recordViewport.height())
        listScrollOffset = listScrollOffset.coerceIn(0f, maxScroll)
        canvas.save()
        canvas.clipRect(g.recordViewport)
        session.markers.forEachIndexed { index, marker ->
            val top = g.recordViewport.top + index * rowHeight - listScrollOffset
            val row = RectF(g.recordViewport.left, top, g.recordViewport.right, top + rowHeight - 2f * density)
            if (!RectF.intersects(row, g.recordViewport)) return@forEachIndexed
            val swipe = if (marker.id == listMarkerId) listSwipeDistance else 0f
            val shifted = RectF(row).apply { offset(swipe, 0f) }
            paint.style = Paint.Style.FILL
            paint.color = surface
            canvas.drawRoundRect(shifted, 3f * density, 3f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f * density
            paint.color = if (marker.id == session.selectedMarkerId) red else dividerColor
            canvas.drawRoundRect(shifted, 3f * density, 3f * density, paint)
            val evText = marker.ev100?.let { "%.2f".format(it) } ?: "…"
            val zoneText = session.formattedZone(marker, session.iso)
            paint.style = Paint.Style.FILL
            paint.color = if (isOutsideAppliedLatitude(marker)) latitudeOutsideRed else foreground
            paint.textSize = 8f * density
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("${marker.id}   EV100 $evText   $zoneText", shifted.left + 10f * density, shifted.centerY() + 3f * density, paint)
            if (swipe > 0f) {
                canvas.save()
                canvas.clipRect(row)
                val gradientLeft = max(row.left, row.right - swipe)
                paint.shader = LinearGradient(
                    gradientLeft,
                    row.centerY(),
                    row.right,
                    row.centerY(),
                    Color.argb(18, Color.red(red), Color.green(red), Color.blue(red)),
                    Color.argb((60 + 150 * (swipe / row.width()).coerceIn(0f, 1f)).toInt(), Color.red(red), Color.green(red), Color.blue(red)),
                    Shader.TileMode.CLAMP,
                )
                paint.style = Paint.Style.FILL
                canvas.drawRect(gradientLeft, row.top, row.right, row.bottom, paint)
                paint.shader = null
                canvas.restore()
            }
        }
        canvas.restore()
    }

    private fun drawClearSlider(canvas: Canvas, g: Geometry) {
        val track = g.clearTrack
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRoundRect(track, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = dividerColor
        canvas.drawRoundRect(track, 3f * density, 3f * density, paint)

        val maxTravel = (track.width() - g.clearHandle.width()).coerceAtLeast(0f)
        val handle = RectF(g.clearHandle).apply {
            offset(clearDragDistance.coerceIn(0f, maxTravel), 0f)
        }
        val dragging = touchTarget == TouchTarget.CLEAR
        if (dragging && maxTravel > 0f) {
            val arrowStart = handle.right + 4f * density
            val arrowEnd = track.right - 6f * density
            if (arrowEnd > arrowStart) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.6f * density
                paint.color = red
                canvas.drawLine(arrowStart, track.centerY(), arrowEnd, track.centerY(), paint)
                val arrowSize = 4f * density
                canvas.drawLine(arrowEnd, track.centerY(), arrowEnd - arrowSize, track.centerY() - arrowSize, paint)
                canvas.drawLine(arrowEnd, track.centerY(), arrowEnd - arrowSize, track.centerY() + arrowSize, paint)
            }
        }

        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRoundRect(handle, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = if (dragging) red else foreground
        canvas.drawRoundRect(handle, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (dragging) red else foreground
        paint.textSize = 7f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        drawCenteredText(
            canvas,
            localized("\u6e05\u7a7a", "Clear"),
            handle.centerX(),
            handle.centerY(),
            paint,
        )
    }

    @Suppress("unused")
    private fun drawClearHandle(canvas: Canvas, g: Geometry) {
        val rect = g.clearHandle
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
        val dragging = touchTarget == TouchTarget.CLEAR
        val text = if (dragging) localized("清空  →", "Clear  →") else localized("清空", "Clear")
        paint.style = Paint.Style.FILL
        paint.color = if (dragging) red else foreground
        paint.textSize = 7f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        drawCenteredText(canvas, text, rect.centerX() + clearDragDistance * 0.18f, rect.centerY(), paint)
    }

    private fun drawMarkButton(canvas: Canvas, rect: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRoundRect(rect, 7f * density, 7f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = foreground
        canvas.drawRoundRect(rect, 7f * density, 7f * density, paint)
        val radius = min(rect.width(), rect.height()) * 0.23f
        paint.strokeWidth = 2f * density
        paint.color = red
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint)
        paint.style = Paint.Style.FILL
        paint.color = foreground
        paint.textSize = 6.5f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        drawCenteredText(canvas, "+", rect.centerX(), rect.centerY(), paint)
    }

    private fun drawOutlinedButton(
        canvas: Canvas,
        rect: RectF,
        label: String,
        selected: Boolean,
        alpha: Int = 255,
    ) {
        paint.style = Paint.Style.FILL
        paint.withAlpha(if (selected) foreground else surface, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.withAlpha(if (selected) red else foreground, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.FILL
        paint.withAlpha(if (selected) surface else foreground, alpha)
        paint.textSize = 7f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        drawCenteredText(canvas, label, rect.centerX(), rect.centerY(), paint)
    }

    /** Buttons overlapping the fitted viewfinder image become semi-transparent. */
    private fun overlayAlpha(rect: RectF, cameraFrame: RectF): Int =
        if (RectF.intersects(rect, cameraFrame)) OVERLAY_ALPHA else 255

    private fun Paint.withAlpha(color: Int, alpha: Int) {
        this.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun drawOrientationButton(canvas: Canvas, rect: RectF, alpha: Int = 255) {
        paint.style = Paint.Style.FILL
        paint.withAlpha(surface, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.withAlpha(foreground, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        val phone = RectF(rect.centerX() - 6f * density, rect.centerY() - 10f * density, rect.centerX() + 6f * density, rect.centerY() + 10f * density)
        canvas.drawRoundRect(phone, 2f * density, 2f * density, paint)
        paint.withAlpha(red, alpha)
        canvas.drawArc(RectF(rect.left + 6f * density, rect.top + 5f * density, rect.right - 6f * density, rect.bottom - 5f * density), 205f, 80f, false, paint)
    }

    private fun drawSettingsButton(canvas: Canvas, rect: RectF, alpha: Int = 255) {
        paint.style = Paint.Style.FILL
        paint.withAlpha(surface, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.withAlpha(foreground, alpha)
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
        paint.strokeWidth = 1f * density
        paint.withAlpha(foreground, alpha)
        canvas.drawPath(gear, paint)
        canvas.drawCircle(rect.centerX(), rect.centerY(), innerRadius, paint)
    }

    /** Four hollow squares with the top-right one rotated 45 degrees around its own center. */
    private fun drawToolsButton(canvas: Canvas, rect: RectF, alpha: Int = 255) {
        paint.style = Paint.Style.FILL
        paint.withAlpha(surface, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.withAlpha(foreground, alpha)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        val cell = rect.width() * 0.26f
        val innerGap = rect.width() * 0.07f
        val left = rect.centerX() - cell - innerGap / 2f
        val top = rect.centerY() - cell - innerGap / 2f
        paint.strokeWidth = 0.9f * density
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
        val vertical = track.height() > track.width()
        val maxZoom = state.cameraInfo.maxDisplayZoom.coerceAtLeast(1.01f)
        val normalized = ((state.zoom - 1f) / (maxZoom - 1f)).coerceIn(0f, 1f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f * density
        paint.color = foreground
        if (vertical) {
            val x = track.centerX()
            val top = track.top + 18f * density
            val bottom = track.bottom - 8f * density
            canvas.drawLine(x, top, x, bottom, paint)
            for (i in 0..6) {
                val y = top + (bottom - top) * i / 6f
                canvas.drawLine(x - 5f * density, y, x + 5f * density, y, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = red
            canvas.drawCircle(x, bottom - normalized * (bottom - top), 4.5f * density, paint)
        } else {
            val y = track.centerY()
            val left = track.left + 8f * density
            val right = track.right - 8f * density
            canvas.drawLine(left, y, right, y, paint)
            for (i in 0..6) {
                val x = left + (right - left) * i / 6f
                canvas.drawLine(x, y - 5f * density, x, y + 5f * density, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = red
            canvas.drawCircle(left + normalized * (right - left), y, 4.5f * density, paint)
        }
    }

    private fun drawFormatMenu(canvas: Canvas, g: Geometry) {
        formatOptionRects(g).forEachIndexed { index, rect ->
            val selected = index == state.frameIndex
            drawOutlinedButton(
                canvas,
                rect,
                FrameFormat.ALL[index].displayLabel(state.menuLanguage),
                selected,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = geometry ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                if (formatMenuOpen) {
                    val option = formatOptionRects(g).indexOfFirst { it.contains(event.x, event.y) }
                    if (option >= 0) {
                        state.selectFrame(option)
                        formatMenuOpen = false
                        haptic()
                        listener?.onControlsChanged(true)
                        listener?.onPreviewMappingChanged()
                        geometry = calculateGeometry(width, height)
                        invalidate()
                        return true
                    }
                }
                // Square formats place these controls inside the displayed camera rectangle.
                // Resolve them before marker hit-testing so 6x6 cannot swallow their gestures.
                val overlayControl = when {
                    g.formatButton.containsAccessibleTarget(event.x, event.y, density) ->
                        TouchTarget.FORMAT
                    g.orientationButton.containsAccessibleTarget(event.x, event.y, density) ->
                        TouchTarget.ORIENTATION
                    g.settingsButton.containsAccessibleTarget(event.x, event.y, density) ->
                        TouchTarget.SETTINGS
                    g.toolsButton.containsAccessibleTarget(event.x, event.y, density) ->
                        TouchTarget.TOOLS
                    modeTransitionEnabled &&
                        g.normalHandle.containsAccessibleTarget(event.x, event.y, density) &&
                        !state.measuring -> TouchTarget.EXIT
                    g.zoomTrack.contains(event.x, event.y) -> TouchTarget.ZOOM
                    else -> TouchTarget.NONE
                }
                if (overlayControl != TouchTarget.NONE) {
                    touchTarget = overlayControl
                    when (touchTarget) {
                        TouchTarget.EXIT -> exitProgress = 0.02f
                        TouchTarget.ZOOM -> updateZoom(event.x, event.y, g.zoomTrack)
                        else -> Unit
                    }
                    invalidate()
                    return true
                }
                // Marker taps are intentionally inert: no selection highlight and no
                // double-tap deletion. Markers are removed from the record list.
                if (markerAt(event.x, event.y, g.cameraFrame) != null) {
                    return true
                }
                if (g.cameraFrame.contains(event.x, event.y)) {
                    session.selectMarker(null)
                    if (state.zoneMarkingMethod == ZoneMarkingMethod.TOUCH &&
                        !state.measuring
                    ) {
                        touchTarget = TouchTarget.PREVIEW_MARK
                    }
                    invalidate()
                    return true
                }
                if (session.selectedMarkerId != null) session.selectMarker(null)
                touchTarget = when {
                    modeTransitionEnabled && g.normalHandle.contains(event.x, event.y) &&
                        !state.measuring -> TouchTarget.EXIT
                    g.markButton.contains(event.x, event.y) -> TouchTarget.MARK_BUTTON
                    g.clearHandle.contains(event.x, event.y) -> TouchTarget.CLEAR
                    g.lockTrack.contains(event.x, event.y) -> TouchTarget.LOCK
                    g.zoneScale.contains(event.x, event.y) || g.markerRail.contains(event.x, event.y) -> TouchTarget.ZONE_RAIL
                    g.apertureRow.contains(event.x, event.y) -> TouchTarget.APERTURE
                    g.shutterRow.contains(event.x, event.y) -> TouchTarget.SHUTTER
                    g.recordViewport.contains(event.x, event.y) -> TouchTarget.LIST
                    g.zoomTrack.contains(event.x, event.y) -> TouchTarget.ZOOM
                    g.formatButton.contains(event.x, event.y) -> TouchTarget.FORMAT
                    g.orientationButton.contains(event.x, event.y) -> TouchTarget.ORIENTATION
                    else -> TouchTarget.NONE
                }
                when (touchTarget) {
                    TouchTarget.APERTURE -> exposureStartCoordinate = session.apertureCoordinate
                    TouchTarget.SHUTTER -> exposureStartCoordinate = session.shutterCoordinate
                    TouchTarget.ZONE_RAIL -> railStartExposureEv100 = session.selectedExposureEv100(session.iso)
                    TouchTarget.LIST -> {
                        listScrollStart = listScrollOffset
                        listMarkerId = markerForListY(event.y, g)
                        listSwipeDistance = 0f
                        listGestureHorizontal = false
                    }
                    TouchTarget.CLEAR -> clearDragDistance = 0f
                    TouchTarget.LOCK -> beginLockDrag(event.y)
                    TouchTarget.EXIT -> exitProgress = 0.02f
                    TouchTarget.ZOOM -> updateZoom(event.x, event.y, g.zoomTrack)
                    else -> Unit
                }
                invalidate()
                return touchTarget != TouchTarget.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                when (touchTarget) {
                    TouchTarget.EXIT -> {
                        exitProgress = if (g.landscape) {
                            (ModeTransitionDirection.normalEntryDistance(
                                touchStartX,
                                event.x,
                                state.isLeftHanded,
                            ) / (width * 0.16f)).coerceIn(0f, 1f)
                        } else {
                            (dy / (height * 0.16f)).coerceIn(0f, 1f)
                        }
                        listener?.onExitDrag(exitProgress, false)
                    }
                    TouchTarget.APERTURE -> {
                        val pixelsPerStop = exposurePixelsPerStop(g.apertureRow, g.lockTrack)
                        session.setManualAperture(exposureStartCoordinate - dx / pixelsPerStop, snap = false)
                        listener?.onControlsChanged(false)
                    }
                    TouchTarget.SHUTTER -> {
                        val pixelsPerStop = exposurePixelsPerStop(g.shutterRow, g.lockTrack)
                        session.setManualShutter(exposureStartCoordinate - dx / pixelsPerStop, snap = false)
                        listener?.onControlsChanged(false)
                    }
                    TouchTarget.LOCK -> updateLockDrag(event.y, g.lockTrack)
                    TouchTarget.ZONE_RAIL -> {
                        val delta = if (g.landscape) {
                            -dy / (g.markerRail.height() / 10f)
                        } else {
                            dx / (g.markerRail.width() / 10f)
                        }
                        setExposureFromRailStart(railStartExposureEv100 - delta)
                        listener?.onControlsChanged(false)
                    }
                    TouchTarget.LIST -> {
                        if (!listGestureHorizontal && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                            listGestureHorizontal = true
                        }
                        if (listGestureHorizontal && listMarkerId != null) {
                            listSwipeDistance = max(0f, dx).coerceAtMost(g.recordViewport.width())
                        } else {
                            listScrollOffset = listScrollStart - dy
                        }
                    }
                    TouchTarget.CLEAR -> {
                        val maxTravel = (g.clearTrack.width() - g.clearHandle.width()).coerceAtLeast(0f)
                        clearDragDistance = max(0f, dx).coerceAtMost(maxTravel)
                    }
                    TouchTarget.ZOOM -> updateZoom(event.x, event.y, g.zoomTrack)
                    else -> Unit
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                when (touchTarget) {
                    TouchTarget.EXIT -> {
                        val commit = !cancelled && exitProgress >= 0.55f
                        listener?.onExitDrag(if (commit) 1f else 0f, true)
                        if (!commit) exitProgress = 0f
                    }
                    TouchTarget.APERTURE -> {
                        session.setManualAperture(session.apertureCoordinate, snap = true)
                        session.syncLockedCoordinate(state)
                        listener?.onControlsChanged(false)
                    }
                    TouchTarget.SHUTTER -> {
                        session.setManualShutter(session.shutterCoordinate, snap = true)
                        session.syncLockedCoordinate(state)
                        listener?.onControlsChanged(false)
                    }
                    TouchTarget.LOCK -> finishLockDrag(event.y, g.lockTrack, cancelled)
                    TouchTarget.LIST -> finishListGesture(g)
                    TouchTarget.CLEAR -> finishClear(g)
                    TouchTarget.MARK_BUTTON -> if (!cancelled && g.markButton.contains(event.x, event.y)) beginMarker()
                    TouchTarget.PREVIEW_MARK -> if (!cancelled &&
                        g.cameraFrame.contains(event.x, event.y) &&
                        abs(event.x - touchStartX) <= touchSlop * 2f &&
                        abs(event.y - touchStartY) <= touchSlop * 2f
                    ) {
                        beginMarkerAt(touchStartX, touchStartY, g.cameraFrame)
                    }
                    TouchTarget.FORMAT -> if (!cancelled) formatMenuOpen = !formatMenuOpen
                    TouchTarget.ORIENTATION -> if (!cancelled) {
                        listener?.onPreviewMappingChanged()
                        listener?.onOrientationToggle()
                    }
                    TouchTarget.SETTINGS -> if (!cancelled) listener?.onSettingsRequested()
                    TouchTarget.TOOLS -> if (!cancelled) listener?.onToolsRequested()
                    else -> Unit
                }
                touchTarget = TouchTarget.NONE
                performClick()
                invalidate()
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
        lockAnimator?.cancel()
        lockAnimator = null
        markerDisplayMotions.clear()
        super.onDetachedFromWindow()
    }

    private fun beginMarker() {
        if (state.measuring) return
        val marker = session.beginMarker(session.iso) ?: return
        haptic()
        listener?.onMarkRequested(marker)
    }

    private fun beginMarkerAt(x: Float, y: Float, frame: RectF) {
        if (state.measuring || frame.width() <= 0f || frame.height() <= 0f) return
        val marker = session.beginMarker(
            iso = session.iso,
            normalizedX = ((x - frame.left) / frame.width()).coerceIn(0f, 1f),
            normalizedY = ((y - frame.top) / frame.height()).coerceIn(0f, 1f),
        ) ?: return
        haptic()
        listener?.onMarkRequested(marker)
    }

    private fun removeMarker(id: Int) {
        session.removeMarker(id, session.iso, state.exposureLockMode)?.let {
            session.syncLockedCoordinate(state)
            listener?.onMarkerRemoved(id)
            haptic()
        }
    }

    private fun finishListGesture(g: Geometry) {
        val id = listMarkerId
        if (listGestureHorizontal && id != null && listSwipeDistance >= g.recordViewport.width() * 0.34f) {
            removeMarker(id)
        } else if (!listGestureHorizontal && abs(listScrollOffset - listScrollStart) < touchSlop && id != null) {
            session.selectMarker(id)
        }
        listMarkerId = null
        listSwipeDistance = 0f
        listGestureHorizontal = false
    }

    private fun finishClear(g: Geometry) {
        val maxTravel = (g.clearTrack.width() - g.clearHandle.width()).coerceAtLeast(0f)
        if (!state.measuring && maxTravel > 0f && clearDragDistance >= maxTravel * 0.78f) {
            val ids = session.clear().map(ZoneMarker::id)
            listener?.onMarkersCleared(ids)
            listScrollOffset = 0f
            haptic()
        }
        clearDragDistance = 0f
    }

    private fun beginLockDrag(y: Float) {
        lockAnimator?.cancel()
        lockAnimator = null
        if (lockSliderFraction.isNaN()) {
            lockSliderFraction = if (state.exposureLockMode == ExposureLockMode.APERTURE) 0f else 1f
        }
        lockDragStartY = y
        lockDragStartFraction = lockSliderFraction
        lockDragMoved = false
    }

    private fun updateLockDrag(y: Float, track: RectF) {
        val span = track.height() * 0.5f
        if (span <= 0f) return
        val deltaY = y - lockDragStartY
        if (abs(deltaY) >= touchSlop) lockDragMoved = true
        lockSliderFraction = (lockDragStartFraction + deltaY / span).coerceIn(0f, 1f)
        setLockModeForFraction(lockSliderFraction)
        postInvalidateOnAnimation()
    }

    private fun finishLockDrag(y: Float, track: RectF, cancelled: Boolean) {
        val target = when {
            cancelled -> if (state.exposureLockMode == ExposureLockMode.APERTURE) 0f else 1f
            !lockDragMoved -> if (y < track.centerY()) 0f else 1f
            lockSliderFraction < 0.5f -> 0f
            else -> 1f
        }
        setLockModeForFraction(target)
        animateLockTo(target)
    }

    private fun setLockModeForFraction(fraction: Float) {
        val mode = if (fraction < 0.5f) ExposureLockMode.APERTURE else ExposureLockMode.SHUTTER
        if (mode == state.exposureLockMode) return
        state.setExposureLockModeFromCoordinates(
            mode,
            session.apertureCoordinate,
            session.shutterCoordinate,
        )
        if (mode == ExposureLockMode.APERTURE) {
            session.setManualAperture(state.lockedApertureStop, snap = false)
        } else {
            session.setManualShutter(state.lockedShutterLogSeconds, snap = false)
        }
        haptic()
        listener?.onControlsChanged(false)
    }

    private fun animateLockTo(target: Float) {
        val start = lockSliderFraction
        lockAnimator?.cancel()
        if (abs(start - target) < 0.001f) {
            lockSliderFraction = target
            postInvalidateOnAnimation()
            return
        }
        lockAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lockSliderFraction = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) lockSliderFraction = target
                    if (lockAnimator === animation) lockAnimator = null
                    postInvalidateOnAnimation()
                }
            })
            start()
        }
    }

    private fun setExposureFromRailStart(targetExposureEv100: Double) {
        val current = session.selectedExposureEv100(session.iso)
        session.shiftZones(current - targetExposureEv100, session.iso, state.exposureLockMode)
        session.syncLockedCoordinate(state)
    }

    private fun updateZoom(x: Float, y: Float, track: RectF) {
        val vertical = track.height() > track.width()
        val fraction = if (vertical) {
            ((track.bottom - y) / track.height()).coerceIn(0f, 1f)
        } else {
            ((x - track.left) / track.width()).coerceIn(0f, 1f)
        }
        val maxZoom = state.cameraInfo.maxDisplayZoom.coerceAtLeast(1.01f)
        val newZoom = ((1f + fraction * (maxZoom - 1f)) * 10f).roundToInt() / 10f
        if (abs(newZoom - state.zoom) < 0.001f) return
        state.zoom = newZoom
        listener?.onControlsChanged(false)
        // The tracker remains entirely in the unzoomed 1x coordinate space. This callback only
        // changes how those stable coordinates are projected into the cropped UI.
        listener?.onZoomMappingChanged(newZoom)
    }

    private fun markerAt(x: Float, y: Float, frame: RectF): Int? =
        session.markers.asReversed().firstOrNull { marker ->
            val display = markerDisplayMotions[marker.id]?.sampleAt(SystemClock.uptimeMillis())
                ?: MarkerDisplaySample(marker.normalizedX, marker.normalizedY, false)
            if (display.x !in 0f..1f || display.y !in 0f..1f) {
                return@firstOrNull false
            }
            val markerX = frame.left + display.x * frame.width()
            val markerY = frame.top + display.y * frame.height()
            abs(x - markerX) <= 16f * density && abs(y - markerY) <= 14f * density
        }?.id

    private fun markerForListY(y: Float, g: Geometry): Int? {
        if (!g.recordViewport.contains(g.recordViewport.centerX(), y)) return null
        val rowHeight = 34f * density
        val index = ((y - g.recordViewport.top + listScrollOffset) / rowHeight).toInt()
        return session.markers.getOrNull(index)?.id
    }

    private fun exposurePixelsPerStop(row: RectF, lock: RectF): Float {
        val available = if (state.isLeftHanded) row.right - lock.right else lock.left - row.left
        return max(25f * density, (available - 45f * density) / 5.4f)
    }

    private fun zoneCell(rect: RectF, zone: Int, landscape: Boolean): RectF = if (landscape) {
        val height = rect.height() / 11f
        val bottom = rect.bottom - zone * height
        RectF(rect.left, bottom - height, rect.right, bottom)
    } else {
        val width = rect.width() / 11f
        RectF(rect.left + zone * width, rect.top, rect.left + (zone + 1) * width, rect.bottom)
    }

    private fun zonePositionX(rect: RectF, zone: Double): Float =
        rect.left + (zone.coerceIn(0.0, 10.0) / 10.0 * rect.width()).toFloat()

    private fun zonePositionY(rect: RectF, zone: Double): Float =
        rect.bottom - (zone.coerceIn(0.0, 10.0) / 10.0 * rect.height()).toFloat()

    private fun drawLatitudeBoundaries(canvas: Canvas, rect: RectF, landscape: Boolean) {
        val range = appliedLatitudeRange ?: return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = red
        if (landscape) {
            listOf(range.lowerZone, range.upperZone).forEach { zone ->
                val y = zonePositionY(rect, zone)
                canvas.drawLine(rect.left, y, rect.right, y, paint)
            }
        } else {
            listOf(range.lowerZone, range.upperZone).forEach { zone ->
                val x = zonePositionX(rect, zone)
                canvas.drawLine(x, rect.top, x, rect.bottom, paint)
            }
        }
    }

    private fun isOutsideAppliedLatitude(marker: ZoneMarker): Boolean {
        val range = appliedLatitudeRange ?: return false
        val zone = session.zoneFor(marker, session.iso) ?: return false
        return !range.containsZone(zone)
    }

    private fun shortFormatLabel(): String = InstrumentPresentation.formatShortLabel(
        state.frameFormat,
        state.menuLanguage,
    )

    private fun formatOptionRects(g: Geometry): List<RectF> {
        val gap = 4f * density
        val itemHeight = 28f * density
        val itemWidth = 85f * density
        val grid = InstrumentPresentation.formatMenuGridWithMaximumRows(
            FrameFormat.ALL.size,
            MAX_FORMAT_MENU_ROWS,
        )
        if (grid.columns == 0) return emptyList()
        val menuWidth = grid.columns * itemWidth + (grid.columns - 1) * gap
        val menuHeight = grid.rows * itemHeight + (grid.rows - 1) * gap
        val menuLeft = if (g.formatButton.centerX() <= width / 2f) {
            g.formatButton.left
        } else {
            g.formatButton.right - menuWidth
        }.coerceIn(gap, (width - gap - menuWidth).coerceAtLeast(gap))
        val belowTop = g.formatButton.bottom + gap
        val menuTop = if (belowTop + menuHeight <= height - gap) {
            belowTop
        } else {
            (g.formatButton.top - gap - menuHeight).coerceAtLeast(gap)
        }
        return FrameFormat.ALL.indices.map { index ->
            val column = index / grid.rows
            val row = index % grid.rows
            val left = menuLeft + column * (itemWidth + gap)
            val top = menuTop + row * (itemHeight + gap)
            RectF(left, top, left + itemWidth, top + itemHeight)
        }
    }

    private fun calculateGeometry(width: Int, height: Int): Geometry =
        ZoneLayoutCalculator.calculate(width, height, density, state)

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, y: Float, textPaint: Paint) {
        val metrics = textPaint.fontMetrics
        val baseline = y - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, x - textPaint.measureText(text) / 2f, baseline, textPaint)
    }

    private fun haptic() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHapticAt > 28L) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastHapticAt = now
        }
    }

    fun currentApertureCoordinate(): Double = session.apertureCoordinate

    fun currentShutterCoordinate(): Double = session.shutterCoordinate

    fun currentMeanEv100(): Double? = if (session.markers.any { it.ev100 != null }) {
        session.weightedMeanEv100()
    } else {
        state.effectiveEv100
    }

    fun recordedZonePoints(): List<RecordedZonePoint> = session.markers.map { marker ->
        RecordedZonePoint(
            id = marker.id,
            normalizedX = marker.normalizedX,
            normalizedY = marker.normalizedY,
            ev100 = marker.ev100,
            source = marker.source,
        )
    }

    fun recordButtonRect(): RectF = RectF(
        (geometry ?: calculateGeometry(width, height)).markButton,
    )

    private companion object {
        private const val MAX_FORMAT_MENU_ROWS = 6
        private const val MIN_MARKER_INTERPOLATION_MS = 12L
        private const val DEFAULT_MARKER_INTERPOLATION_MS = 33L
        private const val MAX_MARKER_INTERPOLATION_MS = 48L
        private const val METERING_SPINNER_PERIOD_MS = 820L
        private const val METERING_SPINNER_SWEEP_DEGREES = 108f
        private const val OVERLAY_ALPHA = 168
    }
}
