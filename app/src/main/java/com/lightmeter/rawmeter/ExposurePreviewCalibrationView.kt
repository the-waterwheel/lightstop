package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import kotlin.math.min

/** Independent per-camera workflow for the visual rendering of an exposure-preview 0 EV. */
@SuppressLint("ViewConstructor")
internal class ExposurePreviewCalibrationView(
    context: Context,
    private val state: MeterState,
) : ViewGroup(context) {
    interface Listener {
        fun onExitRequested()
        fun onCameraRequested()
        fun onStarted()
        fun onAdjustmentRequested(correctionEv: Double)
        fun onSaveRequested(correctionEv: Double)
        fun onResetRequested()
        fun onCancelled()
    }

    private data class Geometry(
        val preview: RectF,
        val back: RectF,
        val reset: RectF,
        val camera: RectF,
        val controls: RectF,
    )

    var listener: Listener? = null
    private val density get() = layoutDensity(LayoutProfile.METER)
    private val foreground: Int get() = InstrumentStyle.foreground(state.isDarkMode)
    private val surface: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val muted: Int get() = InstrumentStyle.secondary(state.isDarkMode)
    private val red = InstrumentStyle.red
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
    }
    private val panel = ExposurePreviewCalibrationPanel(context, state).apply {
        listener = object : ExposurePreviewCalibrationPanel.Listener {
            override fun onComparisonRequested(correctionEv: Double?) {
                correctionEv?.let { this@ExposurePreviewCalibrationView.listener?.onAdjustmentRequested(it) }
            }

            override fun onSaveRequested(correctionEv: Double) {
                this@ExposurePreviewCalibrationView.listener?.onSaveRequested(correctionEv)
            }
        }
    }
    private var geometry: Geometry? = null
    private var active = false
    private var preparing = false
    private var cameraId = ""
    private var correctionEv = 0.0
    private var histogram = FloatArray(PreviewHistogram.BIN_COUNT)

    init {
        setWillNotDraw(false)
        isClickable = true
        addView(panel)
    }

    fun calculatePreviewFrame(width: Int, height: Int): RectF = calculateGeometry(width, height).preview

    fun open(cameraId: String, correctionEv: Double) {
        this.cameraId = cameraId
        this.correctionEv = correctionEv
        active = true
        preparing = true
        histogram.fill(0f)
        panel.begin(correctionEv)
        listener?.onStarted()
        requestLayout()
        invalidate()
    }

    fun updateCamera(cameraId: String, correctionEv: Double) {
        this.cameraId = cameraId
        this.correctionEv = correctionEv
        if (active && preparing) panel.updateInitialCorrection(correctionEv)
        invalidate()
    }

    fun setReady(ready: Boolean) {
        if (!active) return
        preparing = false
        panel.setReady(ready)
    }

    fun showSaved(correctionEv: Double) {
        this.correctionEv = correctionEv
        active = false
        preparing = false
        invalidate()
    }

    fun showReset() {
        correctionEv = 0.0
        panel.reset()
    }

    fun close(): Boolean {
        if (!active && visibility != VISIBLE) return false
        if (active) listener?.onCancelled()
        active = false
        preparing = false
        return true
    }

    fun updateHistogram(bitmap: Bitmap) {
        histogram = PreviewHistogram.fromBitmap(bitmap)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val g = calculateGeometry(width, height).also { geometry = it }
        panel.measure(
            MeasureSpec.makeMeasureSpec(g.controls.width().toInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(g.controls.height().toInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val g = calculateGeometry(right - left, bottom - top).also { geometry = it }
        panel.layout(g.controls.left.toInt(), g.controls.top.toInt(), g.controls.right.toInt(), g.controls.bottom.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = geometry ?: calculateGeometry(width, height).also { geometry = it }
        drawOutsidePreview(canvas, g.preview)
        drawHeader(canvas, g)
        drawPreviewOverlay(canvas, g)
    }

    private fun drawOutsidePreview(canvas: Canvas, preview: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRect(0f, 0f, width.toFloat(), preview.top, paint)
        canvas.drawRect(0f, preview.top, preview.left, preview.bottom, paint)
        canvas.drawRect(preview.right, preview.top, width.toFloat(), preview.bottom, paint)
        canvas.drawRect(0f, preview.bottom, width.toFloat(), height.toFloat(), paint)
    }

    private fun drawHeader(canvas: Canvas, g: Geometry) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = foreground
        val arrow = Path().apply {
            moveTo(g.back.right - 11f * density, g.back.centerY())
            lineTo(g.back.left + 13f * density, g.back.centerY())
            lineTo(g.back.left + 23f * density, g.back.centerY() - 9f * density)
            moveTo(g.back.left + 13f * density, g.back.centerY())
            lineTo(g.back.left + 23f * density, g.back.centerY() + 9f * density)
        }
        canvas.drawPath(arrow, paint)
        boldPaint.color = foreground
        boldPaint.textSize = 15f * density
        centeredText(canvas, localized("预览曝光校准", "Preview exposure calibration"), width / 2f, g.back.centerY(), boldPaint)
        boldPaint.textSize = 10f * density
        boldPaint.color = if (preparing) muted else red
        centeredText(canvas, localized("重置", "Reset"), g.reset.centerX(), g.reset.centerY(), boldPaint)
    }

    private fun drawPreviewOverlay(canvas: Canvas, g: Geometry) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = foreground
        canvas.drawRect(g.preview, paint)
        drawCameraSelector(canvas, g.camera)
        drawHistogram(canvas, RectF(
            g.preview.left + 8f * density,
            g.camera.bottom + 8f * density,
            g.preview.left + min(132f * density, g.preview.width() * 0.38f),
            g.camera.bottom + min(76f * density, g.preview.height() * 0.25f),
        ))
    }

    private fun drawCameraSelector(canvas: Canvas, rect: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(176, Color.red(surface), Color.green(surface), Color.blue(surface))
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = foreground
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
        val camera = state.currentCamera()
        val label = camera?.let { descriptor ->
            val focal = descriptor.focalLengthMm.takeIf { it > 0f }?.let { " · %.1f mm".format(it) }.orEmpty()
            "${state.cameraName(descriptor)}$focal  ›"
        } ?: localized("选择摄像头", "Select camera")
        boldPaint.color = foreground
        boldPaint.textSize = 8f * density
        centeredText(canvas, label, rect.centerX(), rect.centerY(), boldPaint)
    }

    private fun drawHistogram(canvas: Canvas, rect: RectF) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(145, 0, 0, 0)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
        val inner = RectF(rect).apply { inset(6f * density, 6f * density) }
        val barWidth = inner.width() / histogram.size.coerceAtLeast(1)
        paint.color = Color.argb(210, 238, 238, 238)
        histogram.forEachIndexed { index, value ->
            val left = inner.left + index * barWidth
            canvas.drawRect(left, inner.bottom - inner.height() * value, left + barWidth + 0.5f, inner.bottom, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * density
        paint.color = Color.argb(210, 255, 255, 255)
        canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val g = geometry ?: return true
        when {
            g.back.contains(event.x, event.y) -> {
                haptic()
                if (!preparing) listener?.onExitRequested()
            }
            g.reset.contains(event.x, event.y) && !preparing -> {
                haptic()
                listener?.onResetRequested()
            }
            g.camera.contains(event.x, event.y) && !preparing -> {
                haptic()
                listener?.onCameraRequested()
            }
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun calculateGeometry(width: Int, height: Int): Geometry {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 10f * density
        val gap = 8f * density
        val headerHeight = min(68f * density, maxOf(52f * density, h * 0.075f))
        val back = RectF(pad, 0f, pad + 52f * density, headerHeight)
        val reset = RectF(w - pad - 70f * density, 0f, w - pad, headerHeight)
        val previewBounds: RectF
        val controls: RectF
        if (w > h) {
            val divider = w * 0.59f
            previewBounds = RectF(pad, headerHeight + gap, divider - gap, h - pad)
            controls = RectF(divider + gap, headerHeight + gap, w - pad, h - pad)
        } else {
            previewBounds = RectF(pad, headerHeight + gap, w - pad, h * 0.57f)
            controls = RectF(pad, h * 0.57f + gap, w - pad, h - pad)
        }
        val preview = fitAspect(previewBounds, state.frameFormat.landscapeAspect)
        val cameraWidth = min(preview.width() - 14f * density, 210f * density).coerceAtLeast(96f * density)
        val camera = RectF(preview.left + 7f * density, preview.top + 7f * density, preview.left + 7f * density + cameraWidth, preview.top + 33f * density)
        return Geometry(preview, back, reset, camera, controls)
    }

    private fun fitAspect(bounds: RectF, aspect: Float): RectF {
        val boundsAspect = bounds.width() / bounds.height()
        return if (boundsAspect > aspect) {
            val frameWidth = bounds.height() * aspect
            RectF(bounds.centerX() - frameWidth / 2f, bounds.top, bounds.centerX() + frameWidth / 2f, bounds.bottom)
        } else {
            val frameHeight = bounds.width() / aspect
            RectF(bounds.left, bounds.centerY() - frameHeight / 2f, bounds.right, bounds.centerY() + frameHeight / 2f)
        }
    }

    private fun haptic() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private fun centeredText(canvas: Canvas, text: String, x: Float, y: Float, textPaint: Paint) {
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, x - textPaint.measureText(text) / 2f, y - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }
}
