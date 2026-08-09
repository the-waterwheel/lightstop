package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class CalibrationView(
    context: Context,
    private val state: MeterState,
) : ViewGroup(context) {

    interface Listener {
        fun onExitRequested()
        fun onCameraRequested()
        fun onResetRequested()
        fun onMeasureRequested(referenceEv100: Double)
    }

    private data class Geometry(
        val preview: RectF,
        val back: RectF,
        val reset: RectF,
        val camera: RectF,
        val modes: List<RectF>,
        val editorArea: RectF,
        val primary: RectF,
        val controls: RectF,
    )

    var listener: Listener? = null
    var isMeasuring: Boolean = false
        private set

    private val density = resources.displayMetrics.density
    private val foreground: Int
        get() = if (state.isDarkMode) Color.rgb(210, 210, 206) else Color.rgb(20, 20, 20)
    private val surfaceColor: Int
        get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val red = Color.rgb(166, 27, 36)
    private val gray: Int
        get() = if (state.isDarkMode) Color.rgb(58, 58, 56) else Color.rgb(218, 218, 214)
    private val muted: Int
        get() = if (state.isDarkMode) Color.rgb(150, 150, 146) else Color.rgb(112, 112, 108)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
    }

    private val evEditor = numericEditor("EV100", signed = true)
    private val apertureEditor = numericEditor("f / N")
    private val shutterEditor = numericEditor(
        localized("1/125 或 0.008", "1/125 or 0.008"),
        fraction = true,
    )
    private val isoEditor = numericEditor("ISO")
    private val luxEditor = numericEditor("Lux")
    private val allEditors = listOf(
        evEditor,
        apertureEditor,
        shutterEditor,
        isoEditor,
        luxEditor,
    )

    private var mode = CalibrationReferenceMode.EV100
    private var geometry: Geometry? = null
    private var currentCorrectionEv = 0.0
    private var statusText = ""
    private var statusIsError = false

    init {
        setWillNotDraw(false)
        isClickable = true
        allEditors.forEach(::addView)
        apertureEditor.setText("5.6")
        shutterEditor.setText("1/125")
        isoEditor.setText(state.iso.toString())
        updateEditorVisibility()
    }

    fun calculatePreviewFrame(width: Int, height: Int): RectF =
        calculateGeometry(width, height).preview

    fun setCurrentCorrection(value: Double) {
        currentCorrectionEv = value
        invalidate()
    }

    fun applyTheme() {
        allEditors.forEach { editor ->
            editor.setTextColor(foreground)
            editor.setHintTextColor(muted)
            editor.background = editorBackground()
        }
        invalidate()
    }

    fun setMeasuring(measuring: Boolean, frameCount: Int? = null) {
        isMeasuring = measuring
        if (measuring) {
            statusText = if (frameCount != null) {
                localized(
                    "正在读取 $frameCount 帧中央画面…",
                    "Reading $frameCount center frames…",
                )
            } else {
                localized("正在准备测光…", "Preparing measurement…")
            }
            statusIsError = false
            clearEditorFocus()
        }
        invalidate()
    }

    fun showResult(
        referenceEv100: Double,
        measuredEv100: Double,
        correctionEv: Double,
    ) {
        isMeasuring = false
        currentCorrectionEv = correctionEv
        statusText = localized(
            "完成：手机 ${formatEv(measuredEv100)} → 参考 ${formatEv(referenceEv100)}",
            "Done: phone ${formatEv(measuredEv100)} → reference ${formatEv(referenceEv100)}",
        )
        statusIsError = false
        invalidate()
    }

    fun showError(message: String) {
        isMeasuring = false
        statusText = message
        statusIsError = true
        invalidate()
    }

    fun showReset() {
        isMeasuring = false
        currentCorrectionEv = 0.0
        statusText = localized("用户校准已重置", "User calibration reset")
        statusIsError = false
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val g = calculateGeometry(width, height)
        geometry = g
        val editHeight = g.editorArea.height().toInt().coerceAtLeast(1)
        val editWidth = g.editorArea.width().toInt().coerceAtLeast(1)
        when (mode) {
            CalibrationReferenceMode.CAMERA_EXPOSURE -> {
                val gap = 6f * density
                val fieldWidth = ((g.editorArea.width() - gap * 2f) / 3f)
                    .toInt().coerceAtLeast(1)
                listOf(apertureEditor, shutterEditor, isoEditor).forEach { editor ->
                    editor.measure(
                        MeasureSpec.makeMeasureSpec(fieldWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(editHeight, MeasureSpec.EXACTLY),
                    )
                }
            }
            CalibrationReferenceMode.EV100 -> evEditor.measure(
                MeasureSpec.makeMeasureSpec(editWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(editHeight, MeasureSpec.EXACTLY),
            )
            CalibrationReferenceMode.LUX_GRAY_CARD -> luxEditor.measure(
                MeasureSpec.makeMeasureSpec(editWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(editHeight, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val g = calculateGeometry(right - left, bottom - top)
        geometry = g
        if (mode == CalibrationReferenceMode.CAMERA_EXPOSURE) {
            val gap = 6f * density
            val fieldWidth = (g.editorArea.width() - gap * 2f) / 3f
            listOf(apertureEditor, shutterEditor, isoEditor).forEachIndexed { index, editor ->
                val fieldLeft = g.editorArea.left + index * (fieldWidth + gap)
                editor.layout(
                    fieldLeft.toInt(),
                    g.editorArea.top.toInt(),
                    (fieldLeft + fieldWidth).toInt(),
                    g.editorArea.bottom.toInt(),
                )
            }
        } else {
            val editor = if (mode == CalibrationReferenceMode.EV100) evEditor else luxEditor
            editor.layout(
                g.editorArea.left.toInt(),
                g.editorArea.top.toInt(),
                g.editorArea.right.toInt(),
                g.editorArea.bottom.toInt(),
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = geometry ?: calculateGeometry(width, height).also { geometry = it }
        drawSurfaceOutsidePreview(canvas, g.preview)
        drawHeader(canvas, g)
        drawPreviewOverlay(canvas, g.preview)
        drawControls(canvas, g)
    }

    private fun drawSurfaceOutsidePreview(canvas: Canvas, preview: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
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
            moveTo(g.back.left + 13f * density, g.back.centerY())
            lineTo(g.back.left + 23f * density, g.back.centerY() - 9f * density)
            moveTo(g.back.left + 13f * density, g.back.centerY())
            lineTo(g.back.left + 23f * density, g.back.centerY() + 9f * density)
        }
        canvas.drawPath(arrow, paint)

        boldPaint.color = foreground
        boldPaint.textSize = 15f * density
        drawCenteredText(
            canvas,
            localized("测光校准", "Meter calibration"),
            width / 2f,
            g.back.centerY(),
            boldPaint,
        )
        boldPaint.textSize = 10f * density
        boldPaint.color = if (isMeasuring) muted else red
        drawCenteredText(
            canvas,
            localized("重置", "Reset"),
            g.reset.centerX(),
            g.reset.centerY(),
            boldPaint,
        )
    }

    private fun drawPreviewOverlay(canvas: Canvas, preview: RectF) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = foreground
        canvas.drawRect(preview, paint)
        paint.color = surfaceColor
        paint.strokeWidth = 2f * density
        canvas.drawCircle(preview.centerX(), preview.centerY(), 11f * density, paint)
        paint.color = foreground
        paint.strokeWidth = 1f * density
        canvas.drawCircle(preview.centerX(), preview.centerY(), 3f * density, paint)

        geometry?.let { drawCameraSelector(canvas, it.camera) }

        val badge = RectF(
            preview.left + 7f * density,
            preview.bottom - 25f * density,
            preview.left + 112f * density,
            preview.bottom - 7f * density,
        )
        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
        canvas.drawRoundRect(badge, 3f * density, 3f * density, paint)
        paint.color = foreground
        paint.textSize = 8f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        drawCenteredText(
            canvas,
            state.frameFormat.label,
            badge.centerX(),
            badge.centerY(),
            paint,
        )
    }

    private fun drawControls(canvas: Canvas, g: Geometry) {
        g.modes.forEachIndexed { index, rect ->
            val itemMode = CalibrationReferenceMode.entries[index]
            val selected = itemMode == mode
            paint.style = Paint.Style.FILL
            paint.color = if (selected) foreground else surfaceColor
            canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (selected) 1.4f * density else 1f * density
            paint.color = if (selected) red else foreground
            canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
            paint.style = Paint.Style.FILL
            paint.color = if (selected) surfaceColor else foreground
            paint.textSize = 8.5f * density
            paint.typeface = Typeface.DEFAULT_BOLD
            drawCenteredText(canvas, modeLabel(itemMode), rect.centerX(), rect.centerY(), paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = muted
        paint.textSize = 7.5f * density
        paint.typeface = Typeface.DEFAULT
        val help = when (mode) {
            CalibrationReferenceMode.EV100 -> localized(
                "输入参考测光表给出的 EV100",
                "Enter reference EV100 from a trusted meter",
            )
            CalibrationReferenceMode.CAMERA_EXPOSURE -> localized(
                "输入相机在同一中心区域给出的光圈、快门秒数与 ISO",
                "Enter aperture, shutter seconds, and ISO from the reference camera",
            )
            CalibrationReferenceMode.LUX_GRAY_CARD -> localized(
                "Lux 仅适用于均匀照明的 18% 漫反射灰卡",
                "Lux requires an evenly lit 18% diffuse gray card",
            )
        }
        canvas.drawText(help, g.controls.left, g.editorArea.top - 7f * density, paint)

        boldPaint.textSize = 9f * density
        boldPaint.color = foreground
        val calibrationRecord = state.currentCamera()?.let {
            state.cameraCalibrationRecord(it.cameraId)
        }
        val correctionText = when {
            calibrationRecord == null -> localized(
                "当前镜头未校准 · 用户修正 ${signedEv(currentCorrectionEv)} EV",
                "Current lens not calibrated · correction ${signedEv(currentCorrectionEv)} EV",
            )
            calibrationRecord.referenceEv100 != null &&
                calibrationRecord.measuredEv100 != null -> localized(
                "记录 ${calibrationRecord.calibrationCount} 次 · " +
                    "手机 ${formatEv(calibrationRecord.measuredEv100)} → " +
                    "参考 ${formatEv(calibrationRecord.referenceEv100)} · " +
                    "修正 ${signedEv(calibrationRecord.correctionEv)} EV",
                "${calibrationRecord.calibrationCount} records · " +
                    "phone ${formatEv(calibrationRecord.measuredEv100)} → " +
                    "reference ${formatEv(calibrationRecord.referenceEv100)} · " +
                    "correction ${signedEv(calibrationRecord.correctionEv)} EV",
            )
            else -> localized(
                "历史校准 · 用户修正 ${signedEv(calibrationRecord.correctionEv)} EV",
                "Legacy calibration · correction ${signedEv(calibrationRecord.correctionEv)} EV",
            )
        }
        canvas.drawText(
            ellipsize(correctionText, g.controls.width(), boldPaint),
            g.controls.left,
            g.primary.top - 33f * density,
            boldPaint,
        )
        val secondaryText = if (statusText.isNotEmpty()) {
            statusText
        } else if (calibrationRecord != null && calibrationRecord.updatedAtEpochMs > 0L) {
            localized("最后校准：", "Last calibrated: ") +
                formatCalibrationTime(calibrationRecord.updatedAtEpochMs)
        } else {
            ""
        }
        if (secondaryText.isNotEmpty()) {
            paint.style = Paint.Style.FILL
            paint.color = if (statusText.isNotEmpty() && statusIsError) red else muted
            paint.textSize = 7.5f * density
            paint.typeface = Typeface.DEFAULT
            canvas.drawText(
                ellipsize(secondaryText, g.controls.width(), paint),
                g.controls.left,
                g.primary.top - 15f * density,
                paint,
            )
        }

        paint.style = Paint.Style.FILL
        paint.color = if (isMeasuring) gray else foreground
        canvas.drawRoundRect(g.primary, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = if (isMeasuring) muted else red
        canvas.drawRoundRect(g.primary, 4f * density, 4f * density, paint)
        boldPaint.color = if (isMeasuring) muted else surfaceColor
        boldPaint.textSize = 11f * density
        drawCenteredText(
            canvas,
            localized(
                if (isMeasuring) "正在测光" else "测光并校准",
                if (isMeasuring) "Measuring" else "Measure and calibrate",
            ),
            g.primary.centerX(),
            g.primary.centerY(),
            boldPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val g = geometry ?: return true
        when {
            g.back.contains(event.x, event.y) -> {
                haptic()
                if (isMeasuring) {
                    statusText = localized("请等待本次测光完成", "Wait for this measurement")
                    statusIsError = true
                    invalidate()
                } else {
                    listener?.onExitRequested()
                }
            }
            g.reset.contains(event.x, event.y) && !isMeasuring -> {
                haptic()
                listener?.onResetRequested()
            }
            g.camera.contains(event.x, event.y) && !isMeasuring -> {
                haptic()
                clearEditorFocus()
                listener?.onCameraRequested()
            }
            g.modes.indexOfFirst { it.contains(event.x, event.y) } >= 0 && !isMeasuring -> {
                val index = g.modes.indexOfFirst { it.contains(event.x, event.y) }
                mode = CalibrationReferenceMode.entries[index]
                statusText = ""
                updateEditorVisibility()
                haptic()
                requestLayout()
                invalidate()
            }
            g.primary.contains(event.x, event.y) && !isMeasuring -> {
                haptic()
                val reference = referenceEv100()
                if (reference == null) return true
                listener?.onMeasureRequested(reference)
            }
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun referenceEv100(): Double? = try {
        when (mode) {
            CalibrationReferenceMode.EV100 -> {
                evEditor.text.toString().trim().toDoubleOrNull()
                    ?.takeIf(Double::isFinite)
                    ?: throw IllegalArgumentException(
                        localized("请输入有效的 EV100", "Enter a valid EV100"),
                    )
            }
            CalibrationReferenceMode.CAMERA_EXPOSURE -> {
                val aperture = apertureEditor.text.toString().trim().toDoubleOrNull()
                    ?.takeIf { it > 0.0 && it.isFinite() }
                    ?: throw IllegalArgumentException(
                        localized("请输入有效光圈", "Enter a valid aperture"),
                    )
                val shutter = CalibrationMath.parseShutterSeconds(shutterEditor.text.toString())
                    ?: throw IllegalArgumentException(
                        localized("请输入有效快门，如 1/125", "Enter a shutter such as 1/125"),
                    )
                val iso = isoEditor.text.toString().trim().toDoubleOrNull()
                    ?.takeIf { it > 0.0 && it.isFinite() }
                    ?: throw IllegalArgumentException(
                        localized("请输入有效 ISO", "Enter a valid ISO"),
                    )
                CalibrationMath.ev100FromCameraExposure(aperture, shutter, iso)
            }
            CalibrationReferenceMode.LUX_GRAY_CARD -> {
                val lux = luxEditor.text.toString().trim().toDoubleOrNull()
                    ?.takeIf { it > 0.0 && it.isFinite() }
                    ?: throw IllegalArgumentException(
                        localized("请输入有效 Lux", "Enter a valid lux value"),
                    )
                CalibrationMath.ev100FromLuxOnGrayCard(lux)
            }
        }
    } catch (_: IllegalArgumentException) {
        showError(
            localized(
                "输入无效，请检查数值范围",
                "Invalid input. Check the value range",
            ),
        )
        null
    }

    private fun calculateGeometry(width: Int, height: Int): Geometry {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 10f * density
        val gap = 8f * density
        val headerHeight = minOf(68f * density, maxOf(52f * density, h * 0.075f))
        val back = RectF(pad, 0f, pad + 52f * density, headerHeight)
        val reset = RectF(w - pad - 70f * density, 0f, w - pad, headerHeight)
        val isLandscape = w > h
        val previewBounds: RectF
        val controls: RectF
        if (isLandscape) {
            val divider = w * 0.59f
            previewBounds = RectF(pad, headerHeight + gap, divider - gap, h - pad)
            controls = RectF(divider + gap, headerHeight + gap, w - pad, h - pad)
        } else {
            previewBounds = RectF(pad, headerHeight + gap, w - pad, h * 0.52f)
            controls = RectF(pad, h * 0.52f + gap, w - pad, h - pad)
        }
        val preview = fitAspect(previewBounds, state.frameFormat.landscapeAspect)
        val cameraWidth = minOf(preview.width() - 14f * density, 210f * density)
            .coerceAtLeast(96f * density)
        val camera = RectF(
            preview.left + 7f * density,
            preview.top + 7f * density,
            preview.left + 7f * density + cameraWidth,
            preview.top + 33f * density,
        )
        val modeGap = 5f * density
        val modeHeight = min(34f * density, controls.height() * 0.16f)
        val modeWidth = (controls.width() - modeGap * 2f) / 3f
        val modes = CalibrationReferenceMode.entries.indices.map { index ->
            val left = controls.left + index * (modeWidth + modeGap)
            RectF(left, controls.top, left + modeWidth, controls.top + modeHeight)
        }
        val editorHeight = min(48f * density, controls.height() * 0.22f)
        val editorTop = modes.first().bottom + 28f * density
        val editorArea = RectF(
            controls.left,
            editorTop,
            controls.right,
            editorTop + editorHeight,
        )
        val primaryHeight = min(48f * density, controls.height() * 0.19f)
        val primary = RectF(
            controls.left,
            controls.bottom - primaryHeight,
            controls.right,
            controls.bottom,
        )
        return Geometry(preview, back, reset, camera, modes, editorArea, primary, controls)
    }

    private fun fitAspect(bounds: RectF, aspect: Float): RectF {
        val boundsAspect = bounds.width() / bounds.height()
        return if (boundsAspect > aspect) {
            val frameWidth = bounds.height() * aspect
            RectF(
                bounds.centerX() - frameWidth / 2f,
                bounds.top,
                bounds.centerX() + frameWidth / 2f,
                bounds.bottom,
            )
        } else {
            val frameHeight = bounds.width() / aspect
            RectF(
                bounds.left,
                bounds.centerY() - frameHeight / 2f,
                bounds.right,
                bounds.centerY() + frameHeight / 2f,
            )
        }
    }

    private fun drawCameraSelector(canvas: Canvas, rect: RectF) {
        val camera = state.currentCamera()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(
            188,
            Color.red(surfaceColor),
            Color.green(surfaceColor),
            Color.blue(surfaceColor),
        )
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = if (camera?.rawAvailable == false) red else foreground
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)

        paint.style = Paint.Style.FILL
        paint.color = foreground
        paint.textSize = 8f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        val label = if (camera == null) {
            localized("选择摄像头", "Select camera")
        } else {
            val focal = if (camera.focalLengthMm > 0f) " · %.1f mm".format(camera.focalLengthMm)
            else ""
            "${state.cameraName(camera)}$focal  ›"
        }
        drawCenteredText(
            canvas,
            ellipsize(label, rect.width() - 12f * density, paint),
            rect.centerX(),
            rect.centerY(),
            paint,
        )

        if (camera?.rawAvailable == false) {
            paint.color = red
            paint.textSize = 7f * density
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(
                localized(
                    "该摄像头不支持 RAW，测光可能不准确",
                    "No RAW · metering may be inaccurate",
                ),
                rect.left,
                rect.bottom + 11f * density,
                paint,
            )
        }
    }

    private fun updateEditorVisibility() {
        evEditor.visibility = if (mode == CalibrationReferenceMode.EV100) VISIBLE else GONE
        val cameraVisible = if (mode == CalibrationReferenceMode.CAMERA_EXPOSURE) VISIBLE else GONE
        apertureEditor.visibility = cameraVisible
        shutterEditor.visibility = cameraVisible
        isoEditor.visibility = cameraVisible
        luxEditor.visibility = if (mode == CalibrationReferenceMode.LUX_GRAY_CARD) VISIBLE else GONE
    }

    private fun numericEditor(
        hintText: String,
        signed: Boolean = false,
        fraction: Boolean = false,
    ): EditText = EditText(context).apply {
        hint = hintText
        setTextColor(this@CalibrationView.foreground)
        setHintTextColor(muted)
        textSize = 15f
        gravity = android.view.Gravity.CENTER
        isSingleLine = true
        setPadding((8f * density).toInt(), 0, (8f * density).toInt(), 0)
        inputType = when {
            fraction -> InputType.TYPE_CLASS_TEXT
            signed -> InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        background = editorBackground()
    }

    private fun editorBackground() = GradientDrawable().apply {
        setColor(surfaceColor)
        setStroke(
            (1.2f * density).toInt().coerceAtLeast(1),
            foreground,
        )
        cornerRadius = 3f * density
    }

    private fun clearEditorFocus() {
        allEditors.forEach { it.clearFocus() }
        val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        input.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun modeLabel(value: CalibrationReferenceMode): String = when (value) {
        CalibrationReferenceMode.EV100 -> "EV100"
        CalibrationReferenceMode.CAMERA_EXPOSURE -> localized("相机曝光", "Camera")
        CalibrationReferenceMode.LUX_GRAY_CARD -> "Lux · 18%"
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private fun formatEv(value: Double): String = "%.2f EV".format(value)

    private fun formatCalibrationTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

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
