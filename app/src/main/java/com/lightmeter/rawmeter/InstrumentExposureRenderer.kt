package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Stateless renderer for the two exposure scales and their lock control.
 *
 * Gesture and animation ownership remains in [InstrumentView]; this class only turns an immutable
 * exposure snapshot into pixels. That separation prevents drawing code from mutating meter state.
 */
internal class InstrumentExposureRenderer(
    private val state: MeterState,
    private val density: Float,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val lightBlack = Color.rgb(20, 20, 20)
    private val nightForeground = Color.rgb(210, 210, 206)
    private val red = Color.rgb(166, 27, 36)
    private val foreground: Int get() = if (state.isDarkMode) nightForeground else lightBlack
    private val surface: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE

    fun draw(
        canvas: Canvas,
        geometry: LayoutGeometry,
        centers: ExposureScaleCenters,
        lockSliderFraction: Float,
    ) {
        drawScale(
            canvas,
            geometry.apertureRow,
            geometry.exposureLockTrack,
            "f",
            centers.aperture,
            apertureRow = true,
        )
        drawScale(
            canvas,
            geometry.shutterRow,
            geometry.exposureLockTrack,
            "s",
            centers.shutter,
            apertureRow = false,
        )
        drawLock(canvas, geometry.exposureLockTrack, lockSliderFraction)
    }

    fun scaleContent(
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

    fun pixelsPerStop(content: RectF): Double =
        maxOf(62f * density, content.width() / 4.6f).toDouble()

    private fun drawScale(
        canvas: Canvas,
        rect: RectF,
        lockTrack: RectF,
        title: String,
        centerCoordinate: Double,
        apertureRow: Boolean,
    ) {
        val scaleForeground = when {
            state.isDarkMode -> nightForeground
            apertureRow -> Color.WHITE
            else -> lightBlack
        }
        val background = if (state.isDarkMode || apertureRow) Color.BLACK else Color.WHITE
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRect(rect, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = foreground
        canvas.drawRect(rect, paint)

        val titleWidth = 46f * density
        val content = scaleContent(rect, lockTrack, titleWidth)
        val titleLeft = if (state.isLeftHanded) rect.right - titleWidth else rect.left
        paint.style = Paint.Style.FILL
        paint.color = scaleForeground
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 12f * density
        drawCenteredText(canvas, title, titleLeft + titleWidth * 0.24f, rect.centerY())
        paint.textSize = 8f * density
        paint.typeface = Typeface.DEFAULT
        val exactValue = if (apertureRow) {
            formatExactAperture(
                ExposureMath.apertureValueForCoordinate(centerCoordinate, state.apertureStep),
            )
        } else {
            formatExactShutter(
                ExposureMath.shutterValueForCoordinate(centerCoordinate, state.shutterStep),
            )
        }
        drawCenteredText(canvas, exactValue, titleLeft + titleWidth * 0.65f, rect.centerY())

        val baselineY = rect.centerY() + 9f * density
        val pixelsPerStop = pixelsPerStop(content)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * density
        paint.color = scaleForeground
        canvas.drawLine(content.left, baselineY, content.right, baselineY, paint)

        canvas.save()
        canvas.clipRect(content)
        paint.textSize = 7.5f * density
        paint.typeface = Typeface.DEFAULT
        if (apertureRow) {
            var lastLabelRight = content.left - 4f * density
            ExposureMath.apertureTicks(state.apertureStep).forEachIndexed { index, tick ->
                val x = content.centerX() +
                    ((tick.coordinate - centerCoordinate) * pixelsPerStop).toFloat()
                if (x in content.left..content.right) {
                    val label = if (index % state.apertureStep.denominator == 0) {
                        formatApertureTick(tick.nominalValue)
                    } else {
                        null
                    }
                    val halfLabel = label?.let { paint.measureText(it) / 2f } ?: 0f
                    val showLabel = label != null &&
                        x - halfLabel >= lastLabelRight + 3f * density &&
                        x + halfLabel <= content.right
                    drawScaleTick(canvas, x, baselineY, if (showLabel) label else null, scaleForeground)
                    if (showLabel) lastLabelRight = x + halfLabel
                }
            }
        } else {
            var lastLabelLeft = content.right + 4f * density
            ExposureMath.shutterTicks(state.shutterStep).forEachIndexed { index, tick ->
                val x = content.centerX() +
                    ((tick.coordinate - centerCoordinate) * pixelsPerStop).toFloat()
                if (x in content.left..content.right) {
                    val label = if (index % state.shutterStep.denominator == 0) {
                        ExposureMath.formatShutter(tick.nominalValue)
                    } else {
                        null
                    }
                    val halfLabel = label?.let { paint.measureText(it) / 2f } ?: 0f
                    val showLabel = label != null &&
                        x + halfLabel <= lastLabelLeft - 3f * density &&
                        x - halfLabel >= content.left
                    drawScaleTick(canvas, x, baselineY, if (showLabel) label else null, scaleForeground)
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
            drawCenteredText(canvas, label, x, baselineY - 16f * density)
        }
    }

    private fun drawLock(canvas: Canvas, track: RectF, sliderFraction: Float) {
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRect(track, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = foreground
        canvas.drawRect(track, paint)
        val x = track.centerX()
        val topY = track.top + track.height() * 0.25f
        val bottomY = track.top + track.height() * 0.75f
        canvas.drawLine(x, topY, x, bottomY, paint)

        val knobY = topY + (bottomY - topY) * sliderFraction
        val knobSize = min(28f * density, track.width() - 6f * density)
        val knob = RectF(
            x - knobSize / 2f,
            knobY - knobSize / 2f,
            x + knobSize / 2f,
            knobY + knobSize / 2f,
        )
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRect(knob, paint)
        paint.style = Paint.Style.STROKE
        paint.color = foreground
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

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, y: Float) {
        val metrics = paint.fontMetrics
        canvas.drawText(text, x - paint.measureText(text) / 2f, y - (metrics.ascent + metrics.descent) / 2f, paint)
    }
}
