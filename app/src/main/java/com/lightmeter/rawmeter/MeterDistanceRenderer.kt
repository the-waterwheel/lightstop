package com.lightmeter.rawmeter

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.util.Locale
import kotlin.math.min

internal const val METER_DISTANCE_ROW_DP = 28f

/** Shared readout for the Normal/Zone ruler and the Clear viewfinder capsule. */
internal class MeterDistanceRenderer(private val state: MeterState) {
    private val chrome = PanelChrome()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    fun drawRuler(canvas: Canvas, bounds: RectF, density: Float, aperture: Double) {
        val progress = state.distanceDisplayProgress
        if (bounds.isEmpty || progress <= 0f) return
        val dark = state.isDarkMode
        val foreground = InstrumentStyle.foreground(dark)
        val muted = InstrumentStyle.secondary(dark)
        val accent = InstrumentStyle.accent(dark)
        val estimate = DepthOfFieldAutomaticDistancePolicy.usableEstimate(state.distanceMeasurementState)
        val format = state.depthToolFormat ?: FrameFormat.ALL.first()
        val equivalent = state.distanceEquivalentFocalLengthMm()
        val result = if (estimate != null && equivalent != null) DepthOfFieldMath.calculate(
            focalLengthMm = DepthOfFieldMath.focalLengthForFormat(format, equivalent),
            aperture = aperture,
            circleOfConfusionMm = state.depthToolCircle ?: DepthOfFieldMath.recommendedCircleOfConfusionMm(format),
            focusDistanceM = estimate.meters,
        ) else null

        val labelWidth = min(88f * density, bounds.width() * .30f)
        val labelX = bounds.left + labelWidth / 2
        // Keep the settled text size as the card opens. Clip and fade it instead of stretching it.
        val scale = min(density, bounds.height() / (METER_DISTANCE_ROW_DP * progress))
        val left = bounds.left + labelWidth + 8 * scale
        val right = bounds.right - 8 * scale
        if (right <= left) return
        val layer = if (progress < 1f) canvas.saveLayerAlpha(bounds, (progress * 255).toInt()) else canvas.save()
        canvas.clipRect(bounds)
        chrome.card(canvas, bounds, density * .75f, dark)
        label(canvas, localized("对焦", "Focus"), labelX, bounds.centerY() - 8f * scale, 7.5f * scale, muted, labelWidth)
        label(canvas, focusText(estimate), labelX, bounds.centerY() + 4.5f * scale, 16f * scale, foreground, labelWidth - 8 * scale)
        paint.color = InstrumentStyle.hairline(dark)
        paint.strokeWidth = density
        canvas.drawLine(bounds.left + labelWidth, bounds.centerY() - 9 * scale, bounds.left + labelWidth, bounds.centerY() + 9 * scale, paint)

        val y = bounds.centerY() + 5.5f * scale
        val radius = 2.5f * scale
        paint.color = InstrumentStyle.border(dark)
        canvas.drawRoundRect(left, y - radius, right, y + radius, radius, radius, paint)
        fun position(distance: Double?) = left + (right - left) * DepthOfFieldMath.distanceFraction(distance).toFloat()
        if (result != null) {
            val near = position(result.nearLimitM)
            val far = position(result.farLimitM)
            paint.color = InstrumentStyle.accentSurface(dark)
            canvas.drawRoundRect(near, y - radius, far, y + radius, radius, radius, paint)
            paint.color = accent
            paint.strokeWidth = scale
            canvas.drawLine(near, y - radius, near, y + radius, paint)
            canvas.drawLine(far, y - radius, far, y + radius, paint)
        }
        if (estimate != null) {
            val focus = position(estimate.meters)
            paint.color = accent
            paint.strokeWidth = 2 * scale
            canvas.drawLine(focus, y - 4.5f * scale, focus, y + 4.5f * scale, paint)
        }
        val half = (right - left) / 2
        val labelY = bounds.centerY() - 5.5f * scale
        val nearLabel = localized("近", "Near") + " " + distance(result?.nearLimitM)
        val farLabel = localized("远", "Far") + " " + if (result != null && result.farLimitM == null) "∞" else distance(result?.farLimitM)
        val labelSize = 8.5f * scale
        paint.textSize = labelSize
        paint.typeface = InstrumentStyle.labelTypeface
        val labelBudget = (half - 2 * scale).coerceAtLeast(0f)
        val nearInset = min(labelBudget, paint.measureText(nearLabel)) / 2
        val farInset = min(labelBudget, paint.measureText(farLabel)) / 2
        val nearX = result?.let { position(it.nearLimitM).coerceIn(left + nearInset, left + half - nearInset) }
            ?: left + half / 2
        val farX = result?.let { position(it.farLimitM).coerceIn(right - half + farInset, right - farInset) }
            ?: right - half / 2
        label(canvas, nearLabel, nearX, labelY, labelSize, foreground, half - 2 * scale)
        label(canvas, farLabel, farX, labelY, labelSize, foreground, half - 2 * scale)
        canvas.restoreToCount(layer)
    }

    fun drawCapsule(canvas: Canvas, preview: RectF, handle: RectF, density: Float) {
        if (preview.isEmpty) return
        val estimate = DepthOfFieldAutomaticDistancePolicy.usableEstimate(state.distanceMeasurementState)
        val text = localized("对焦", "Focus") + "  " + focusText(estimate)
        paint.typeface = InstrumentStyle.labelTypeface
        paint.textSize = 10f * density
        val width = min(paint.measureText(text) + 22 * density, preview.width() - 16 * density)
        if (width <= 0) return
        val left = preview.left + 9 * density
        val right = left + width
        var bottom = preview.bottom - 9 * density
        if (handle.right >= left && handle.left < right && handle.bottom >= bottom - 26 * density) {
            bottom = min(bottom, handle.top - 5 * density)
        }
        val radius = 13f * density
        paint.color = Color.argb(205, 18, 18, 18)
        canvas.drawRoundRect(left, bottom - 2 * radius, right, bottom, radius, radius, paint)
        label(canvas, text, left + width / 2, bottom - radius, 10f * density, Color.WHITE, width - 8 * density)
    }

    private fun focusText(estimate: DistanceEstimate?) =
        (if (estimate?.isApproximate == true) "≈" else "") + distance(estimate?.meters)

    private fun distance(value: Double?): String = when {
        value == null -> "—"
        value >= 1000.0 -> "%.0f m".format(Locale.US, value)
        value >= 10.0 -> "%.1f m".format(Locale.US, value)
        else -> "%.2f m".format(Locale.US, value)
    }

    private fun localized(chinese: String, english: String) =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private fun label(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, maxWidth: Float) {
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = InstrumentStyle.labelTypeface
        paint.color = color
        paint.textSize = size
        val width = paint.measureText(text)
        if (width > maxWidth) paint.textSize *= maxWidth.coerceAtLeast(1f) / width
        canvas.drawText(text, x, y - (paint.ascent() + paint.descent()) / 2, paint)
    }
}
