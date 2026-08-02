package com.lightmeter.rawmeter

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    }

    private data class Geometry(
        val landscape: Boolean,
        val previewPanel: RectF,
        val cameraFrame: RectF,
        val formatButton: RectF,
        val orientationButton: RectF,
        val zoomTrack: RectF,
        val normalHandle: RectF,
        val apertureRow: RectF,
        val shutterRow: RectF,
        val lockTrack: RectF,
        val zoneScale: RectF,
        val markerRail: RectF,
        val recordPanel: RectF,
        val recordViewport: RectF,
        val clearTrack: RectF,
        val clearHandle: RectF,
        val markButton: RectF,
    )

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
        ZOOM,
        FORMAT,
        ORIENTATION,
    }

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
    private var formatMenuOpen = false
    private var lastHapticAt = 0L
    private var lockSliderFraction = Float.NaN
    private var lockDragStartY = 0f
    private var lockDragStartFraction = 0f
    private var lockDragMoved = false
    private var lockAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        isClickable = true
    }

    fun calculatePreviewFrame(width: Int, height: Int): RectF =
        calculateGeometry(width, height).cameraFrame

    fun enter() {
        session.initializeFromMeter(state)
        formatMenuOpen = false
        exitProgress = 0f
        lockAnimator?.cancel()
        lockAnimator = null
        lockSliderFraction = if (state.exposureLockMode == ExposureLockMode.APERTURE) 0f else 1f
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
        session.updateTracking(id, x, y, trackingState)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geometry = calculateGeometry(w, h)
        listScrollOffset = 0f
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
        canvas.drawCircle(g.cameraFrame.centerX(), g.cameraFrame.centerY(), spotRadius + density, paint)
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawCircle(g.cameraFrame.centerX(), g.cameraFrame.centerY(), spotRadius, paint)
        canvas.drawCircle(g.cameraFrame.centerX(), g.cameraFrame.centerY(), 1.4f * density, paint)

        drawPreviewMarkers(canvas, g.cameraFrame)
        drawOutlinedButton(canvas, g.formatButton, shortFormatLabel(), formatMenuOpen)
        drawOrientationButton(canvas, g.orientationButton)
        drawZoom(canvas, g.zoomTrack)
        drawNormalHandle(canvas, g)
        drawFocalInfo(canvas, g.cameraFrame)
        if (formatMenuOpen) drawFormatMenu(canvas, g)
    }

    private fun drawFocalInfo(canvas: Canvas, frame: RectF) {
        val equivalent = state.equivalent35mm()
        val focalText = buildString {
            val focal = state.cameraInfo.focalLengthMm
            if (focal > 0f) append("${"%.1f".format(focal)} mm")
            if (equivalent != null) append("  ≈ $equivalent mm")
        }
        if (focalText.isBlank()) return

        boldPaint.textSize = 10f * density
        val textWidth = boldPaint.measureText(focalText)
        val baseline = frame.bottom - 8f * density
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(
            168,
            Color.red(surface),
            Color.green(surface),
            Color.blue(surface),
        )
        canvas.drawRoundRect(
            RectF(
                frame.centerX() - textWidth / 2f - 6f * density,
                baseline - 13f * density,
                frame.centerX() + textWidth / 2f + 6f * density,
                baseline + 3f * density,
            ),
            4f * density,
            4f * density,
            paint,
        )
        boldPaint.color = foreground
        canvas.drawText(focalText, frame.centerX() - textWidth / 2f, baseline, boldPaint)
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
                val x = g.normalHandle.right + 5f * density + exitProgress * 18f * density
                path.moveTo(x, g.normalHandle.centerY() - size)
                path.lineTo(x + size, g.normalHandle.centerY())
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
        session.markers.forEach { marker ->
            if (marker.normalizedX !in 0f..1f || marker.normalizedY !in 0f..1f) {
                return@forEach
            }
            val x = frame.left + marker.normalizedX * frame.width()
            val y = frame.top + marker.normalizedY * frame.height()
            val radius = 9f * density
            val selected = marker.id == session.selectedMarkerId
            paint.pathEffect = when (marker.trackingState) {
                ZoneTrackingState.UNCERTAIN -> DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
                ZoneTrackingState.LOST -> DashPathEffect(floatArrayOf(2f * density, 4f * density), 0f)
                else -> null
            }
            paint.style = Paint.Style.FILL
            paint.color = if (selected) Color.argb(150, Color.red(red), Color.green(red), Color.blue(red)) else surface
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = if (selected) red else foreground
            canvas.drawCircle(x, y, radius, paint)
            paint.pathEffect = null
            boldPaint.color = if (selected && !state.isDarkMode) Color.WHITE else foreground
            boldPaint.textSize = 7f * density
            drawCenteredText(canvas, marker.id.toString(), x, y, boldPaint)
        }
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
    }

    private fun drawMarkerRail(canvas: Canvas, g: Geometry) {
        paint.style = Paint.Style.FILL
        paint.color = railColor
        canvas.drawRect(g.markerRail, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawRect(g.markerRail, paint)
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
            paint.style = Paint.Style.FILL
            paint.color = if (marker.id == session.selectedMarkerId) Color.argb(170, Color.red(red), Color.green(red), Color.blue(red)) else surface
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = if (marker.id == session.selectedMarkerId) red else foreground
            canvas.drawCircle(x, y, radius, paint)
            boldPaint.color = foreground
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
            paint.color = foreground
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

    private fun drawOutlinedButton(canvas: Canvas, rect: RectF, label: String, selected: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (selected) foreground else surface
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = if (selected) red else foreground
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (selected) surface else foreground
        paint.textSize = 7f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        drawCenteredText(canvas, label, rect.centerX(), rect.centerY(), paint)
    }

    private fun drawOrientationButton(canvas: Canvas, rect: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        val phone = RectF(rect.centerX() - 6f * density, rect.centerY() - 10f * density, rect.centerX() + 6f * density, rect.centerY() + 10f * density)
        canvas.drawRoundRect(phone, 2f * density, 2f * density, paint)
        paint.color = red
        canvas.drawArc(RectF(rect.left + 6f * density, rect.top + 5f * density, rect.right - 6f * density, rect.bottom - 5f * density), 205f, 80f, false, paint)
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
            drawOutlinedButton(canvas, rect, FrameFormat.ALL[index].label, selected)
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
                    g.formatButton.contains(event.x, event.y) -> TouchTarget.FORMAT
                    g.orientationButton.contains(event.x, event.y) -> TouchTarget.ORIENTATION
                    g.normalHandle.contains(event.x, event.y) && !state.measuring -> TouchTarget.EXIT
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
                val markerId = markerAt(event.x, event.y, g.cameraFrame)
                if (markerId != null) {
                    val alreadySelected = session.selectMarker(markerId)
                    if (alreadySelected) removeMarker(markerId)
                    haptic()
                    invalidate()
                    return true
                } else if (g.cameraFrame.contains(event.x, event.y)) {
                    session.selectMarker(null)
                    invalidate()
                    return true
                }
                if (session.selectedMarkerId != null) session.selectMarker(null)
                touchTarget = when {
                    g.normalHandle.contains(event.x, event.y) && !state.measuring -> TouchTarget.EXIT
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
                            (dx / (width * 0.16f)).coerceIn(0f, 1f)
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
                    TouchTarget.FORMAT -> if (!cancelled) formatMenuOpen = !formatMenuOpen
                    TouchTarget.ORIENTATION -> if (!cancelled) {
                        listener?.onPreviewMappingChanged()
                        listener?.onOrientationToggle()
                    }
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
        super.onDetachedFromWindow()
    }

    private fun beginMarker() {
        if (state.measuring) return
        val marker = session.beginMarker(session.iso) ?: return
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
            if (marker.normalizedX !in 0f..1f || marker.normalizedY !in 0f..1f) {
                return@firstOrNull false
            }
            val markerX = frame.left + marker.normalizedX * frame.width()
            val markerY = frame.top + marker.normalizedY * frame.height()
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

    private fun shortFormatLabel(): String = state.frameFormat.id.uppercase()

    private fun formatOptionRects(g: Geometry): List<RectF> {
        val gap = 4f * density
        val itemHeight = 28f * density
        return FrameFormat.ALL.indices.map { index ->
            if (g.landscape) {
                RectF(
                    g.formatButton.left,
                    g.formatButton.bottom + gap + index * (itemHeight + gap),
                    g.formatButton.left + 85f * density,
                    g.formatButton.bottom + gap + index * (itemHeight + gap) + itemHeight,
                )
            } else {
                val top = g.formatButton.bottom + gap + index * (itemHeight + gap)
                RectF(g.formatButton.left, top, g.formatButton.left + 85f * density, top + itemHeight)
            }
        }
    }

    private fun calculateGeometry(width: Int, height: Int): Geometry {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 10f * density
        val gap = 6f * density
        val landscape = w > h
        val button = 34f * density
        val previewPanel: RectF
        val cameraFrame: RectF
        val formatButton: RectF
        val orientationButton: RectF
        val zoomTrack: RectF
        val normalHandle: RectF
        val apertureRow: RectF
        val shutterRow: RectF
        val lockTrack: RectF
        val zoneScale: RectF
        val markerRail: RectF
        var recordPanel: RectF
        val recordViewport: RectF
        val clearTrack: RectF
        val clearHandle: RectF
        val markButton: RectF

        val aspect = if (state.frameLandscape) state.frameFormat.landscapeAspect else 1f / state.frameFormat.landscapeAspect
        if (!landscape) {
            previewPanel = RectF(pad, pad, w - pad, h * 0.42f)
            val frameArea = RectF(previewPanel.left + gap, previewPanel.top + gap, previewPanel.right - 34f * density - gap, previewPanel.bottom - gap)
            cameraFrame = fitAspect(frameArea, aspect)
            formatButton = RectF(previewPanel.left + gap, previewPanel.top + gap, previewPanel.left + gap + button * 2.1f, previewPanel.top + gap + button)
            orientationButton = RectF(previewPanel.right - gap - button, previewPanel.top + gap, previewPanel.right - gap, previewPanel.top + gap + button)
            zoomTrack = RectF(previewPanel.right - 30f * density, orientationButton.bottom + gap, previewPanel.right - gap, previewPanel.bottom - gap)
            normalHandle = RectF(cameraFrame.centerX() - 38f * density, previewPanel.bottom - 27f * density, cameraFrame.centerX() + 38f * density, previewPanel.bottom - 7f * density)

            val controlsTop = previewPanel.bottom + gap * 1.4f
            val rowHeight = h * 0.055f
            apertureRow = RectF(pad, controlsTop, w - pad, controlsTop + rowHeight)
            shutterRow = RectF(pad, apertureRow.bottom + gap * 0.55f, w - pad, apertureRow.bottom + gap * 0.55f + rowHeight)
            val lockWidth = 34f * density
            lockTrack = if (state.isLeftHanded) {
                RectF(apertureRow.left, apertureRow.top, apertureRow.left + lockWidth, shutterRow.bottom)
            } else {
                RectF(apertureRow.right - lockWidth, apertureRow.top, apertureRow.right, shutterRow.bottom)
            }
            zoneScale = RectF(pad, shutterRow.bottom + gap, w - pad, shutterRow.bottom + gap + h * 0.038f)
            markerRail = RectF(pad, zoneScale.bottom + gap * 0.45f, w - pad, zoneScale.bottom + gap * 0.45f + h * 0.052f)
            recordPanel = RectF(pad, markerRail.bottom + gap, w - pad, h - pad)
            val markSize = min(72f * density, min(recordPanel.width() * 0.20f, recordPanel.height() * 0.34f))
            markButton = RectF(
                recordPanel.right - gap - markSize,
                recordPanel.centerY() - markSize / 2f,
                recordPanel.right - gap,
                recordPanel.centerY() + markSize / 2f,
            )
            clearTrack = RectF(
                recordPanel.left + gap,
                recordPanel.top + gap,
                markButton.left - gap,
                recordPanel.top + 31f * density,
            )
            clearHandle = RectF(
                clearTrack.left,
                clearTrack.top,
                clearTrack.left + min(66f * density, clearTrack.width() * 0.34f),
                clearTrack.bottom,
            )
            recordViewport = RectF(recordPanel.left + gap, clearTrack.bottom + gap, markButton.left - gap, recordPanel.bottom - gap)
        } else {
            val previewRight = w * 0.43f
            previewPanel = if (state.isLeftHanded) RectF(w - previewRight + pad, pad, w - pad, h - pad) else RectF(pad, pad, previewRight, h - pad)
            val sideGap = 31f * density
            val frameArea = if (state.isLeftHanded) {
                RectF(previewPanel.left + sideGap, previewPanel.top + gap, previewPanel.right - gap, previewPanel.bottom - gap)
            } else {
                RectF(previewPanel.left + gap, previewPanel.top + gap, previewPanel.right - sideGap, previewPanel.bottom - gap)
            }
            cameraFrame = fitAspect(frameArea, aspect)
            formatButton = RectF(previewPanel.left + gap, previewPanel.top + gap, previewPanel.left + gap + button * 2.1f, previewPanel.top + gap + button)
            orientationButton = RectF(previewPanel.right - gap - button, previewPanel.top + gap, previewPanel.right - gap, previewPanel.top + gap + button)
            zoomTrack = if (state.isLeftHanded) {
                RectF(previewPanel.left + gap, orientationButton.bottom + gap, previewPanel.left + 28f * density, previewPanel.bottom - gap)
            } else {
                RectF(previewPanel.right - 28f * density, orientationButton.bottom + gap, previewPanel.right - gap, previewPanel.bottom - gap)
            }
            val normalWidth = 22f * density
            val normalHeight = min(74f * density, zoomTrack.height() * 0.34f)
            val normalLeft = if (state.isLeftHanded) {
                zoomTrack.right + gap
            } else {
                zoomTrack.left - gap - normalWidth
            }
            normalHandle = RectF(
                normalLeft,
                zoomTrack.centerY() - normalHeight / 2f,
                normalLeft + normalWidth,
                zoomTrack.centerY() + normalHeight / 2f,
            )

            val scaleWidth = w * 0.045f
            val railWidth = w * 0.05f
            if (state.isLeftHanded) {
                zoneScale = RectF(previewPanel.left - gap - scaleWidth, pad, previewPanel.left - gap, h - pad)
                markerRail = RectF(zoneScale.left - gap - railWidth, pad, zoneScale.left - gap, h - pad)
                recordPanel = RectF(pad, pad, markerRail.left - gap, h - pad)
            } else {
                zoneScale = RectF(previewPanel.right + gap, pad, previewPanel.right + gap + scaleWidth, h - pad)
                markerRail = RectF(zoneScale.right + gap, pad, zoneScale.right + gap + railWidth, h - pad)
                recordPanel = RectF(markerRail.right + gap, pad, w - pad, h - pad)
            }
            val rowHeight = recordPanel.height() * 0.145f
            apertureRow = RectF(recordPanel.left, recordPanel.top, recordPanel.right, recordPanel.top + rowHeight)
            shutterRow = RectF(recordPanel.left, apertureRow.bottom + gap, recordPanel.right, apertureRow.bottom + gap + rowHeight)
            val lockWidth = 32f * density
            lockTrack = if (state.isLeftHanded) {
                RectF(apertureRow.left, apertureRow.top, apertureRow.left + lockWidth, shutterRow.bottom)
            } else {
                RectF(apertureRow.right - lockWidth, apertureRow.top, apertureRow.right, shutterRow.bottom)
            }
            recordPanel = RectF(recordPanel.left, shutterRow.bottom + gap, recordPanel.right, recordPanel.bottom)
            val markSize = min(68f * density, min(recordPanel.width() * 0.18f, recordPanel.height() * 0.42f))
            markButton = RectF(
                recordPanel.right - gap - markSize,
                recordPanel.centerY() - markSize / 2f,
                recordPanel.right - gap,
                recordPanel.centerY() + markSize / 2f,
            )
            clearTrack = RectF(
                recordPanel.left + gap,
                recordPanel.bottom - 28f * density,
                markButton.left - gap,
                recordPanel.bottom - gap,
            )
            clearHandle = RectF(
                clearTrack.left,
                clearTrack.top,
                clearTrack.left + min(66f * density, clearTrack.width() * 0.34f),
                clearTrack.bottom,
            )
            recordViewport = RectF(recordPanel.left + gap, recordPanel.top + gap, markButton.left - gap, clearTrack.top - gap)
        }
        return Geometry(
            landscape,
            previewPanel,
            cameraFrame,
            formatButton,
            orientationButton,
            zoomTrack,
            normalHandle,
            apertureRow,
            shutterRow,
            lockTrack,
            zoneScale,
            markerRail,
            recordPanel,
            recordViewport,
            clearTrack,
            clearHandle,
            markButton,
        )
    }

    private fun fitAspect(bounds: RectF, aspect: Float): RectF {
        val boundsAspect = bounds.width() / bounds.height()
        return if (boundsAspect > aspect) {
            val fittedWidth = bounds.height() * aspect
            RectF(bounds.centerX() - fittedWidth / 2f, bounds.top, bounds.centerX() + fittedWidth / 2f, bounds.bottom)
        } else {
            val fittedHeight = bounds.width() / aspect
            RectF(bounds.left, bounds.centerY() - fittedHeight / 2f, bounds.right, bounds.centerY() + fittedHeight / 2f)
        }
    }

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
}
