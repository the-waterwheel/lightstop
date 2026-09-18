package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/** Focused UI for the auxiliary 0-EV exposure-preview comparison. */
internal class ExposurePreviewCalibrationPanel(
    context: Context,
    private val state: MeterState,
) : View(context) {
    interface Listener {
        fun onComparisonRequested(correctionEv: Double?)
        fun onSaveRequested(correctionEv: Double)
    }

    private data class Geometry(
        val slider: RectF,
        val comparison: RectF,
        val primary: RectF,
    )

    var listener: Listener? = null
    private val density = resources.displayMetrics.density
    private val foreground: Int
        get() = if (state.isDarkMode) Color.rgb(210, 210, 206) else Color.rgb(20, 20, 20)
    private val surfaceColor: Int
        get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val gray: Int
        get() = if (state.isDarkMode) Color.rgb(58, 58, 56) else Color.rgb(218, 218, 214)
    private val muted: Int
        get() = if (state.isDarkMode) Color.rgb(150, 150, 146) else Color.rgb(112, 112, 108)
    private val red = Color.rgb(166, 27, 36)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
    }

    private var ready = false
    private var referenceVisible = true
    private var draftCorrectionEv = 0.0
    private var statusText = ""
    private var statusIsError = false
    private var sliderDragging = false

    init {
        isClickable = true
    }

    fun begin(correctionEv: Double) {
        ready = false
        referenceVisible = true
        sliderDragging = false
        draftCorrectionEv = correctionEv
        statusText = localized("正在锁定曝光补偿 0 EV 参考…", "Locking the 0 EV reference…")
        statusIsError = false
        invalidate()
    }

    fun setReady(available: Boolean) {
        ready = available
        referenceVisible = true
        statusText = if (available) {
            localized(
                "已锁定 0 EV 参考；拖动滑块后可随时切换对照",
                "0 EV reference locked; drag the slider and switch views to compare",
            )
        } else {
            localized(
                "当前摄像头无法控制曝光预览，不能进行辅助校准",
                "This camera cannot control exposure preview for auxiliary calibration",
            )
        }
        statusIsError = !available
        invalidate()
    }

    fun updateInitialCorrection(correctionEv: Double) {
        if (ready || sliderDragging) return
        draftCorrectionEv = correctionEv
        invalidate()
    }

    fun reset() {
        draftCorrectionEv = 0.0
        referenceVisible = true
        sliderDragging = false
        statusText = localized(
            "曝光预览辅助修正已重置为 0 EV",
            "Exposure-preview correction reset to 0 EV",
        )
        statusIsError = false
        listener?.onComparisonRequested(null)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = geometry()
        paint.style = Paint.Style.FILL
        paint.color = muted
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 7.5f * density
        drawCenteredText(
            canvas,
            localized(
                "先观察曝光补偿为 0 EV 的画面",
                "First observe the view at 0 EV exposure compensation",
            ),
            width / 2f,
            9f * density,
            paint,
        )
        drawCenteredText(
            canvas,
            localized(
                "再调节档位，使校准预览与参考亮度一致",
                "Then match the calibrated preview to the reference brightness",
            ),
            width / 2f,
            20f * density,
            paint,
        )

        boldPaint.color = if (ready) foreground else muted
        boldPaint.textSize = 16f * density
        drawCenteredText(
            canvas,
            "${signedEv(draftCorrectionEv)} EV",
            width / 2f,
            g.slider.top - 5f * density,
            boldPaint,
        )
        drawSlider(canvas, g.slider)
        drawComparison(canvas, g.comparison)

        if (statusText.isNotEmpty()) {
            paint.style = Paint.Style.FILL
            paint.color = if (statusIsError) red else muted
            paint.textSize = 7.2f * density
            paint.typeface = Typeface.DEFAULT
            drawCenteredText(
                canvas,
                ellipsize(statusText, width.toFloat(), paint),
                width / 2f,
                g.primary.top - 8f * density,
                paint,
            )
        }
        paint.style = Paint.Style.FILL
        paint.color = if (ready) foreground else gray
        canvas.drawRoundRect(g.primary, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = if (ready) red else muted
        canvas.drawRoundRect(g.primary, 4f * density, 4f * density, paint)
        boldPaint.color = if (ready) surfaceColor else muted
        boldPaint.textSize = 11f * density
        drawCenteredText(
            canvas,
            localized("保存辅助校准", "Save auxiliary calibration"),
            g.primary.centerX(),
            g.primary.centerY(),
            boldPaint,
        )
    }

    private fun drawSlider(canvas: Canvas, slider: RectF) {
        val centerY = slider.centerY()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = if (ready) foreground else gray
        canvas.drawLine(slider.left, centerY, slider.right, centerY, paint)
        val stepCount = ((ExposurePreviewCalibrationMath.MAX_CORRECTION_EV -
            ExposurePreviewCalibrationMath.MIN_CORRECTION_EV) /
            ExposurePreviewCalibrationMath.STEP_EV).roundToInt()
        for (index in 0..stepCount) {
            val x = slider.left + slider.width() * index.toFloat() / stepCount
            val halfHeight = (if (index % 3 == 0) 5f else 2.5f) * density
            canvas.drawLine(x, centerY - halfHeight, x, centerY + halfHeight, paint)
        }
        val fraction = ((draftCorrectionEv - ExposurePreviewCalibrationMath.MIN_CORRECTION_EV) /
            (ExposurePreviewCalibrationMath.MAX_CORRECTION_EV -
                ExposurePreviewCalibrationMath.MIN_CORRECTION_EV)).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = if (ready) red else muted
        canvas.drawCircle(slider.left + slider.width() * fraction.coerceIn(0f, 1f), centerY, 7f * density, paint)
        paint.color = muted
        paint.textSize = 7f * density
        paint.typeface = Typeface.DEFAULT
        canvas.drawText("-4 EV", slider.left, slider.bottom + 8f * density, paint)
        drawCenteredText(canvas, "0", slider.centerX(), slider.bottom + 8f * density, paint)
        val maxLabel = "+4 EV"
        canvas.drawText(
            maxLabel,
            slider.right - paint.measureText(maxLabel),
            slider.bottom + 8f * density,
            paint,
        )
    }

    private fun drawComparison(canvas: Canvas, rect: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = if (ready) foreground else gray
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
        boldPaint.color = if (ready) surfaceColor else muted
        boldPaint.textSize = 9f * density
        val text = if (referenceVisible) {
            localized("当前：0 EV 参考 · 点击查看校准预览", "0 EV reference · Tap for calibrated preview")
        } else {
            localized("当前：校准预览 · 点击查看 0 EV 参考", "Calibrated preview · Tap for 0 EV reference")
        }
        drawCenteredText(
            canvas,
            ellipsize(text, rect.width() - 12f * density, boldPaint),
            rect.centerX(),
            rect.centerY(),
            boldPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = geometry()
        val sliderTouch = RectF(g.slider).apply { inset(0f, -18f * density) }
        if (ready) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (sliderTouch.contains(event.x, event.y)) {
                    sliderDragging = true
                    updateSlider(event.x, g.slider)
                    return true
                }
                MotionEvent.ACTION_MOVE -> if (sliderDragging) {
                    updateSlider(event.x, g.slider)
                    return true
                }
                MotionEvent.ACTION_UP -> if (sliderDragging) {
                    updateSlider(event.x, g.slider)
                    sliderDragging = false
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> if (sliderDragging) {
                    sliderDragging = false
                    return true
                }
            }
        }
        if (event.actionMasked != MotionEvent.ACTION_UP || !ready) return true
        when {
            g.comparison.contains(event.x, event.y) -> {
                referenceVisible = !referenceVisible
                listener?.onComparisonRequested(if (referenceVisible) null else draftCorrectionEv)
                haptic()
                invalidate()
            }
            g.primary.contains(event.x, event.y) -> {
                haptic()
                listener?.onSaveRequested(draftCorrectionEv)
            }
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateSlider(x: Float, slider: RectF) {
        val fraction = ((x - slider.left) / slider.width()).coerceIn(0f, 1f)
        val raw = ExposurePreviewCalibrationMath.MIN_CORRECTION_EV + fraction *
            (ExposurePreviewCalibrationMath.MAX_CORRECTION_EV -
                ExposurePreviewCalibrationMath.MIN_CORRECTION_EV)
        val correction = ExposurePreviewCalibrationMath.clampAndSnapCorrection(raw)
        if (correction == draftCorrectionEv && !referenceVisible) return
        draftCorrectionEv = correction
        referenceVisible = false
        haptic()
        listener?.onComparisonRequested(correction)
        invalidate()
    }

    private fun geometry(): Geometry {
        val primaryHeight = min(48f * density, height * 0.19f)
        val primary = RectF(0f, height - primaryHeight, width.toFloat(), height.toFloat())
        val comparisonHeight = min(36f * density, height * 0.14f)
        val comparison = RectF(
            0f,
            primary.top - 24f * density - comparisonHeight,
            width.toFloat(),
            primary.top - 24f * density,
        )
        val sliderCenterY = (comparison.top * 0.58f).coerceAtLeast(60f * density)
        val slider = RectF(
            10f * density,
            sliderCenterY - 7f * density,
            width - 10f * density,
            sliderCenterY + 7f * density,
        )
        return Geometry(slider, comparison, primary)
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private fun signedEv(value: Double): String =
        if (value >= 0.0) "+${"%.2f".format(value)}" else "%.2f".format(value)

    private fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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

    private fun ellipsize(text: String, maxWidth: Float, textPaint: Paint): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        val suffix = "…"
        val count = textPaint.breakText(
            text,
            true,
            (maxWidth - textPaint.measureText(suffix)).coerceAtLeast(0f),
            null,
        )
        return text.take(count.coerceAtLeast(0)) + suffix
    }
}
