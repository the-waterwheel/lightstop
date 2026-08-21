package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs

/** Shared recorded exposure/Zone presentation used by history details. */
internal class RecordedMeteringRenderer(
    private val density: Float,
    private val state: MeterState,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.NORMAL) }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.BOLD) }
    private val foreground: Int get() = if (state.isDarkMode) Color.rgb(224, 224, 220) else Color.rgb(20, 20, 20)
    private val surface: Int get() = if (state.isDarkMode) Color.rgb(48, 48, 46) else Color.rgb(235, 235, 232)
    private val red = Color.rgb(201, 39, 46)

    fun draw(canvas: Canvas, rect: RectF, record: ParameterRecordEntry) {
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        if (record.mode == ParameterRecordMode.ZONE) drawZoneRail(canvas, rect, record)
        drawExposurePair(canvas, rect, record)
    }

    fun shifted(record: ParameterRecordEntry, stops: Double): ParameterRecordEntry {
        if (abs(stops) < 0.001) return record
        return record.copy(
            apertureCoordinate = ExposureMath.nearestApertureStop(
                record.apertureCoordinate + stops,
                state.apertureStep,
            ),
            shutterCoordinate = ExposureMath.nearestShutterLogSeconds(
                record.shutterCoordinate + stops,
                state.shutterStep,
            ),
        )
    }

    private fun drawZoneRail(canvas: Canvas, rect: RectF, record: ParameterRecordEntry) {
        val rail = RectF(rect.left + 8f * density, rect.top + 8f * density, rect.right - 8f * density, rect.top + rect.height() * 0.40f)
        for (zone in 0..10) {
            val cellWidth = rail.width() / 11f
            val cell = RectF(rail.left + zone * cellWidth, rail.top, rail.left + (zone + 1) * cellWidth, rail.bottom)
            val gray = (zone / 10f * 255f).toInt()
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(gray, gray, gray)
            canvas.drawRect(cell, paint)
            bold.color = if (gray < 120) Color.WHITE else Color.BLACK
            bold.textSize = 6f * density
            centered(canvas, zone.toString(), cell.centerX(), cell.centerY(), bold)
        }
        val selectedEv100 = record.apertureCoordinate - record.shutterCoordinate - ExposureMath.log2(record.ei / 100.0)
        record.zonePoints.forEachIndexed { index, point ->
            val zone = point.ev100?.let { 5.0 + it - selectedEv100 } ?: return@forEachIndexed
            val x = rail.left + ((zone.coerceIn(0.0, 10.0) + 0.5) / 11.0 * rail.width()).toFloat()
            val y = rail.bottom + 8f * density + (index % 2) * 8f * density
            paint.style = Paint.Style.FILL
            paint.color = red
            canvas.drawCircle(x, y, 4.5f * density, paint)
        }
    }

    private fun drawExposurePair(canvas: Canvas, rect: RectF, record: ParameterRecordEntry) {
        val top = if (record.mode == ParameterRecordMode.ZONE) rect.top + rect.height() * 0.58f else rect.top + rect.height() * 0.18f
        val rowHeight = (rect.bottom - top) / 2f
        drawExposureRow(
            canvas,
            RectF(rect.left + 8f * density, top, rect.right - 8f * density, top + rowHeight),
            "f",
            ExposureMath.formatAperture(ExposureMath.apertureValueForCoordinate(record.apertureCoordinate, state.apertureStep)),
        )
        drawExposureRow(
            canvas,
            RectF(rect.left + 8f * density, top + rowHeight, rect.right - 8f * density, rect.bottom - 5f * density),
            "s",
            ExposureMath.formatShutter(ExposureMath.shutterValueForCoordinate(record.shutterCoordinate, state.shutterStep)),
        )
    }

    private fun drawExposureRow(canvas: Canvas, rect: RectF, title: String, value: String) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        val center = rect.centerX()
        canvas.drawLine(rect.left + 24f * density, rect.centerY(), rect.right, rect.centerY(), paint)
        for (index in -4..4) {
            val x = center + index * rect.width() / 9f
            val length = if (index == 0) 9f * density else 5f * density
            canvas.drawLine(x, rect.centerY() - length, x, rect.centerY() + length, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = red
        canvas.drawCircle(center, rect.centerY(), 4f * density, paint)
        bold.color = foreground
        bold.textSize = 8f * density
        bold.textAlign = Paint.Align.LEFT
        centered(canvas, title, rect.left + 4f * density, rect.centerY(), bold)
        bold.textAlign = Paint.Align.CENTER
        centered(canvas, value, center, rect.centerY() - 13f * density, bold)
    }

    private fun centered(canvas: Canvas, text: String, x: Float, y: Float, textPaint: Paint) {
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }
}
