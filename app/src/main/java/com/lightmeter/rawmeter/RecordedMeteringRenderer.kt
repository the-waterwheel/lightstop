package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Meter-like, stateless scene-reproduction UI used by parameter-record history. */
internal class RecordedMeteringRenderer(
    private val density: Float,
    private val state: MeterState,
) {
    private data class Geometry(
        val normalMode: RectF,
        val zoneMode: RectF,
        val zoneScale: RectF,
        val markerRail: RectF,
        val aperture: RectF,
        val shutter: RectF,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
    }
    private val foreground: Int get() = if (state.isDarkMode) Color.rgb(224, 224, 220) else Color.rgb(20, 20, 20)
    private val surface: Int get() = if (state.isDarkMode) Color.rgb(35, 35, 33) else Color.WHITE
    private val panel: Int get() = if (state.isDarkMode) Color.rgb(48, 48, 46) else Color.rgb(235, 235, 232)
    private val muted: Int get() = if (state.isDarkMode) Color.rgb(100, 100, 96) else Color.rgb(180, 180, 176)
    private val red = Color.rgb(201, 39, 46)

    fun draw(
        canvas: Canvas,
        rect: RectF,
        record: ParameterRecordEntry,
        session: RecordedMeteringSession,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        val geometry = geometry(rect, session.mode)
        drawModeButton(canvas, geometry.normalMode, "NORMAL", session.mode == ParameterRecordMode.NORMAL)
        drawModeButton(canvas, geometry.zoneMode, "ZONE", session.mode == ParameterRecordMode.ZONE)
        if (session.mode == ParameterRecordMode.ZONE) {
            drawZoneScale(canvas, geometry.zoneScale)
            drawMarkerRail(canvas, geometry.markerRail, record, session)
        } else {
            drawNormalSummary(canvas, geometry.zoneScale, geometry.markerRail, record)
        }
        drawExposureScale(canvas, geometry.aperture, session.apertureCoordinate, true)
        drawExposureScale(canvas, geometry.shutter, session.shutterCoordinate, false)
    }

    fun targetAt(rect: RectF, mode: ParameterRecordMode, x: Float, y: Float): RecordedMeteringTarget {
        val geometry = geometry(rect, mode)
        return when {
            geometry.normalMode.contains(x, y) -> RecordedMeteringTarget.NORMAL_MODE
            geometry.zoneMode.contains(x, y) -> RecordedMeteringTarget.ZONE_MODE
            geometry.aperture.contains(x, y) -> RecordedMeteringTarget.APERTURE
            geometry.shutter.contains(x, y) -> RecordedMeteringTarget.SHUTTER
            mode == ParameterRecordMode.ZONE &&
                (geometry.zoneScale.contains(x, y) || geometry.markerRail.contains(x, y)) ->
                RecordedMeteringTarget.ZONE_RAIL
            else -> RecordedMeteringTarget.NONE
        }
    }

    fun pixelsPerStop(rect: RectF): Double = max(50f * density, rect.width() / 5.1f).toDouble()

    private fun geometry(rect: RectF, mode: ParameterRecordMode): Geometry {
        val inset = 7f * density
        val gap = 5f * density
        val left = rect.left + inset
        val right = rect.right - inset
        val top = rect.top + inset
        val modeHeight = (27f * density).coerceAtMost(rect.height() * 0.17f)
        val modeWidth = ((right - left - gap) / 2f).coerceAtLeast(1f)
        val normal = RectF(left, top, left + modeWidth, top + modeHeight)
        val zone = RectF(normal.right + gap, top, right, top + modeHeight)
        val rowsHeight = (43f * density).coerceAtMost(rect.height() * 0.235f)
        val shutter = RectF(left, rect.bottom - inset - rowsHeight, right, rect.bottom - inset)
        val aperture = RectF(left, shutter.top - gap - rowsHeight, right, shutter.top - gap)
        val zoneTop = normal.bottom + gap
        val zoneBottom = aperture.top - gap
        val zoneHeight = (zoneBottom - zoneTop).coerceAtLeast(1f)
        val scaleRatio = if (mode == ParameterRecordMode.ZONE) 0.50f else 0.46f
        val zoneScale = RectF(left, zoneTop, right, zoneTop + zoneHeight * scaleRatio)
        val markerRail = RectF(left, zoneScale.bottom + gap, right, zoneBottom)
        return Geometry(normal, zone, zoneScale, markerRail, aperture, shutter)
    }

    private fun drawModeButton(canvas: Canvas, rect: RectF, label: String, selected: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (selected) red else surface
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = if (selected) red else foreground
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
        bold.color = if (selected) Color.WHITE else foreground
        bold.textAlign = Paint.Align.CENTER
        bold.textSize = 10f * density
        centered(canvas, label, rect.centerX(), rect.centerY(), bold)
    }

    private fun drawNormalSummary(canvas: Canvas, top: RectF, bottom: RectF, record: ParameterRecordEntry) {
        val area = RectF(top.left, top.top, bottom.right, bottom.bottom)
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRoundRect(area, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = muted
        canvas.drawRoundRect(area, 3f * density, 3f * density, paint)
        bold.textAlign = Paint.Align.CENTER
        bold.color = foreground
        bold.textSize = 10f * density
        centered(
            canvas,
            localized("场景复现 · EI ${record.ei}", "Scene reproduction · EI ${record.ei}"),
            area.centerX(),
            area.centerY(),
            bold,
        )
    }

    private fun drawZoneScale(canvas: Canvas, rect: RectF) {
        for (zone in 0..10) {
            val cellWidth = rect.width() / 11f
            val cell = RectF(rect.left + zone * cellWidth, rect.top, rect.left + (zone + 1) * cellWidth, rect.bottom)
            val maximum = if (state.isDarkMode) 215 else 255
            val gray = (zone / 10f * maximum).roundToInt()
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(gray, gray, gray)
            canvas.drawRect(cell, paint)
            bold.color = if (gray < 120) Color.WHITE else Color.BLACK
            bold.textSize = 7f * density
            bold.textAlign = Paint.Align.CENTER
            centered(canvas, ZoneMeterSession.ZONE_LABELS[zone], cell.centerX(), cell.centerY(), bold)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawRect(rect, paint)
    }

    private fun drawMarkerRail(
        canvas: Canvas,
        rect: RectF,
        record: ParameterRecordEntry,
        session: RecordedMeteringSession,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = if (state.isDarkMode) Color.rgb(76, 76, 73) else Color.rgb(202, 202, 198)
        canvas.drawRect(rect, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawRect(rect, paint)
        val selectedEv100 = session.apertureCoordinate - session.shutterCoordinate -
            ExposureMath.log2(record.ei / 100.0)
        record.zonePoints.forEachIndexed { index, point ->
            val pointEv100 = record.resolvedZonePointEv100(point) ?: return@forEachIndexed
            val zone = 5.0 + pointEv100 - selectedEv100
            val x = rect.left + (zone.coerceIn(0.0, 10.0) / 10.0 * rect.width()).toFloat()
            val y = rect.centerY() + ((index % 3) - 1) * 5f * density
            paint.style = Paint.Style.FILL
            paint.color = if (zone in 0.0..10.0) surface else Color.rgb(244, 150, 150)
            canvas.drawCircle(x, y, 7f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f * density
            paint.color = red
            canvas.drawCircle(x, y, 7f * density, paint)
            bold.style = Paint.Style.FILL
            bold.color = foreground
            bold.textSize = 6f * density
            bold.textAlign = Paint.Align.CENTER
            centered(canvas, point.id.toString(), x, y, bold)
        }
        if (record.zonePoints.isEmpty()) {
            paint.style = Paint.Style.FILL
            paint.color = foreground
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 8f * density
            centered(
                canvas,
                localized("点击 RAW 图片添加标点", "Tap RAW image to add a point"),
                rect.centerX(),
                rect.centerY(),
                paint,
            )
        }
    }

    private fun drawExposureScale(canvas: Canvas, rect: RectF, centerCoordinate: Double, aperture: Boolean) {
        val rowForeground = if (state.isDarkMode || !aperture) foreground else Color.WHITE
        val rowBackground = if (state.isDarkMode || aperture) Color.BLACK else Color.WHITE
        paint.style = Paint.Style.FILL
        paint.color = rowBackground
        canvas.drawRect(rect, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawRect(rect, paint)

        val titleWidth = (54f * density).coerceAtMost(rect.width() * 0.28f)
        val title = RectF(rect.left, rect.top, rect.left + titleWidth, rect.bottom)
        val content = RectF(title.right + 2f * density, rect.top, rect.right - 4f * density, rect.bottom)
        val centerX = content.centerX()
        val baselineY = rect.centerY() + 8f * density
        val pixelsPerStop = pixelsPerStop(content)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.9f * density
        paint.color = rowForeground
        canvas.drawLine(content.left, baselineY, content.right, baselineY, paint)
        canvas.save()
        canvas.clipRect(content)
        val ticks = if (aperture) ExposureMath.apertureTicks(state.apertureStep) else ExposureMath.shutterTicks(state.shutterStep)
        var lastLabelRight = content.left - 4f * density
        ticks.forEachIndexed { index, tick ->
            val x = centerX + ((tick.coordinate - centerCoordinate) * pixelsPerStop).toFloat()
            if (x !in content.left..content.right) return@forEachIndexed
            val denominator = if (aperture) state.apertureStep.denominator else state.shutterStep.denominator
            val full = index % denominator == 0
            val label = if (!full) null else if (aperture) {
                if (tick.nominalValue >= 10.0 || abs(tick.nominalValue - tick.nominalValue.roundToInt()) < 0.03) {
                    tick.nominalValue.roundToInt().toString()
                } else "%.1f".format(tick.nominalValue)
            } else ExposureMath.formatShutter(tick.nominalValue)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = rowForeground
            val height = if (full) 9f * density else 5f * density
            canvas.drawLine(x, baselineY - height, x, baselineY + 2f * density, paint)
            if (label != null) {
                paint.style = Paint.Style.FILL
                paint.textSize = 7f * density
                val half = paint.measureText(label) / 2f
                if (x - half >= lastLabelRight + 3f * density && x + half <= content.right) {
                    paint.textAlign = Paint.Align.CENTER
                    centered(canvas, label, x, baselineY - 14f * density, paint)
                    lastLabelRight = x + half
                }
            }
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = red
        canvas.drawLine(centerX, rect.top + 5f * density, centerX, rect.bottom - 4f * density, paint)
        canvas.restore()

        val value = if (aperture) {
            ExposureMath.formatAperture(
                ExposureMath.apertureValueForCoordinate(centerCoordinate, state.apertureStep),
            )
        } else {
            ExposureMath.formatShutter(
                ExposureMath.shutterValueForCoordinate(centerCoordinate, state.shutterStep),
            )
        }
        bold.color = rowForeground
        bold.textAlign = Paint.Align.CENTER
        bold.textSize = 11f * density
        centered(canvas, if (aperture) "f" else "s", title.left + title.width() * 0.25f, title.centerY(), bold)
        bold.textSize = 8f * density
        centered(canvas, value.removePrefix("f/"), title.left + title.width() * 0.67f, title.centerY(), bold)
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private fun centered(canvas: Canvas, text: String, x: Float, y: Float, textPaint: Paint) {
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }
}
