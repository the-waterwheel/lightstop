package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.min

/** Flash/ambient exposure calculator, deliberately sharing Reciprocity's compact tool geometry. */
@SuppressLint("ViewConstructor")
internal class FlashExposureView(
    context: Context,
    private val state: MeterState,
    private val repository: FlashExposureRepository,
) : View(context) {
    interface Listener {
        fun onBackToToolsRequested()
        fun onCloseRequested()
        fun onSettingsRequested(configuration: FlashConfiguration)
        fun onAppliedFlashChanged(configuration: FlashConfiguration?)
    }

    var listener: Listener? = null

    private enum class TouchTarget { BACK, CLOSE, SCALE, SETTINGS, APPLY, NONE }

    private val density = resources.displayMetrics.density
    private val scaledDensity = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        1f,
        resources.displayMetrics,
    )
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val background: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val foreground: Int get() = if (state.isDarkMode) Color.rgb(218, 218, 214) else Color.rgb(20, 20, 20)
    private val muted: Int get() = if (state.isDarkMode) Color.rgb(70, 70, 68) else Color.rgb(166, 166, 162)
    private val panel: Int get() = if (state.isDarkMode) Color.rgb(42, 42, 40) else Color.rgb(235, 235, 232)
    private val actionSurface: Int get() = if (state.isDarkMode) Color.rgb(24, 24, 22) else Color.WHITE
    private val actionActiveSurface: Int get() = if (state.isDarkMode) Color.rgb(31, 56, 83) else Color.rgb(218, 234, 250)
    private val blue = Color.rgb(38, 112, 184)

    private var geometry = ReciprocityGeometry.EMPTY
    private var configuration = FlashConfiguration(iso = state.iso)
    private var selectedDistanceIndex = 0
    private var applied = false
    private var initialized = false
    private var touchTarget = TouchTarget.NONE
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartIndex = 0
    private var moved = false
    private var lastHapticAt = 0L

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun openPage() {
        val appliedConfiguration = repository.applied(state.iso)
        configuration = appliedConfiguration ?: repository.selected(state.iso)
        applied = appliedConfiguration != null
        selectedDistanceIndex = FlashDistanceScale.nearestIndex(configuration.distanceMeters)
        initialized = true
        invalidate()
    }

    fun resumePage() {
        if (!initialized) openPage() else invalidate()
    }

    fun updateConfiguration(value: FlashConfiguration) {
        configuration = value.normalized()
        selectedDistanceIndex = FlashDistanceScale.nearestIndex(configuration.distanceMeters)
        repository.saveSelected(configuration)
        if (applied) repository.apply(configuration)
        listener?.onAppliedFlashChanged(configuration.takeIf { applied })
        invalidate()
    }

    fun updateDistance(value: Double?) {
        configuration = configuration.copy(distanceMeters = value).normalized()
        selectedDistanceIndex = FlashDistanceScale.nearestIndex(configuration.distanceMeters)
        repository.saveSelected(configuration)
        if (applied) repository.apply(configuration)
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        geometry = ReciprocityGeometryCalculator.calculate(width, height, density)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        if (!initialized) openPage()
        drawHeader(canvas)
        drawDistanceScale(canvas)
        drawSettingsCard(canvas)
        drawResult(canvas)
        drawApplyButton(canvas)
        updateContentDescription()
    }

    private fun drawHeader(canvas: Canvas) {
        val y = geometry.back.centerY()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * density
        paint.color = foreground
        canvas.drawLine(geometry.back.centerX() + 5f * density, y - 8f * density, geometry.back.centerX() - 4f * density, y, paint)
        canvas.drawLine(geometry.back.centerX() - 4f * density, y, geometry.back.centerX() + 5f * density, y + 8f * density, paint)
        val closeX = geometry.close.centerX()
        canvas.drawLine(closeX - 7f * density, y - 7f * density, closeX + 7f * density, y + 7f * density, paint)
        canvas.drawLine(closeX + 7f * density, y - 7f * density, closeX - 7f * density, y + 7f * density, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = foreground
        boldPaint.textSize = 14f * scaledDensity
        centered(canvas, localized("闪光曝光", "Flash exposure"), width / 2f, y, boldPaint)
        paint.style = Paint.Style.FILL
        paint.color = muted
        canvas.drawRect(0f, geometry.back.bottom - density, width.toFloat(), geometry.back.bottom, paint)
    }

    private fun drawDistanceScale(canvas: Canvas) {
        val rect = geometry.scale
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRect(rect, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f * density
        paint.color = foreground
        canvas.drawRect(rect, paint)

        val titleWidth = min(82f * density, rect.width() * 0.26f)
        val content = if (state.isLeftHanded) {
            RectF(rect.left + 5f * density, rect.top, rect.right - titleWidth, rect.bottom)
        } else {
            RectF(rect.left + titleWidth, rect.top, rect.right - 5f * density, rect.bottom)
        }
        val titleLeft = if (state.isLeftHanded) rect.right - titleWidth else rect.left
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = foreground
        boldPaint.textSize = 10f * scaledDensity
        centered(canvas, localized("距离", "Distance"), titleLeft + titleWidth * 0.50f, rect.centerY() - 12f * density, boldPaint)
        boldPaint.textSize = 12.5f * scaledDensity
        centered(canvas, currentDistanceLabel(), titleLeft + titleWidth * 0.50f, rect.centerY() + 10f * density, boldPaint)

        val baseline = rect.centerY() + 14f * density
        val spacing = maxOf(30f * density, content.width() / 6f)
        canvas.save()
        canvas.clipRect(content)
        FlashDistanceScale.meters.indices.forEach { index ->
            val x = content.centerX() + (index - selectedDistanceIndex) * spacing
            if (x !in (content.left - spacing)..(content.right + spacing)) return@forEach
            val major = index == 0 || index == selectedDistanceIndex || index % 3 == 1
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (index == selectedDistanceIndex) 2f * density else density
            paint.color = if (index == selectedDistanceIndex) blue else foreground
            canvas.drawLine(x, baseline - (if (major) 14f else 8f) * density, x, baseline + 3f * density, paint)
            if (major) {
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 8f * scaledDensity
                paint.color = foreground
                canvas.drawText(FlashDistanceScale.label(FlashDistanceScale.meters[index]), x, baseline - 19f * density, paint)
            }
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.color = blue
        canvas.drawLine(content.centerX(), baseline - 18f * density, content.centerX(), baseline + 5f * density, paint)
        canvas.restore()
    }

    private fun drawSettingsCard(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawRoundRect(geometry.filmCard, 6f * density, 6f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = muted
        canvas.drawRoundRect(geometry.filmCard, 6f * density, 6f * density, paint)

        val x = geometry.filmCard.left + 9f * density
        val available = geometry.filmCard.width() - 18f * density
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.color = foreground
        boldPaint.textSize = 12f * scaledDensity
        val title = "GN${formatGn(configuration.guideNumber)} · ISO ${configuration.iso} · ${FlashPowerScale.label(configuration.powerDenominator)}"
        canvas.drawText(TextUtils.ellipsize(title, boldPaint, available, TextUtils.TruncateAt.END).toString(), x, geometry.filmCard.centerY() - 3f * density, boldPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = foreground
        textPaint.textSize = 9f * scaledDensity
        canvas.drawText(localized("损失 %.2f 档", "Loss %.2f stops").format(java.util.Locale.US, configuration.lossStops), x, geometry.filmCard.centerY() + 15f * density, textPaint)
        drawNeutralButton(geometry.selectFilm, localized("闪光设置", "Flash settings"), canvas)
    }

    private fun drawResult(canvas: Canvas) {
        val rect = geometry.result
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = foreground
        boldPaint.textSize = min(30f * scaledDensity, rect.height() * 0.34f)
        centered(canvas, "GN ${formatGn(configuration.guideNumber)}", rect.centerX(), rect.centerY() - 11f * density, boldPaint)

        val adjustment = previewAdjustment()
        val detail = when (adjustment.status) {
            FlashAdjustmentStatus.DISTANCE_UNAVAILABLE -> state.distanceMeasurementState.diagnosticReason
                ?: localized("等待相机对焦距离", "Waiting for camera focus distance")
            FlashAdjustmentStatus.FLASH_DOMINATES -> localized("闪光已覆盖所需曝光", "Flash alone covers the exposure")
            FlashAdjustmentStatus.APPLIED -> localized(
                "总光量补偿 −%.2f 档",
                "Combined-light reduction −%.2f stops",
            ).format(java.util.Locale.US, adjustment.compensationStops)
            else -> localized("等待环境光测量", "Waiting for ambient reading")
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = if (adjustment.status == FlashAdjustmentStatus.DISTANCE_UNAVAILABLE) blue else foreground
        textPaint.textSize = 9f * scaledDensity
        val fitted = TextUtils.ellipsize(detail, textPaint, rect.width() - 8f * density, TextUtils.TruncateAt.END)
        centered(canvas, fitted.toString(), rect.centerX(), rect.centerY() + 24f * density, textPaint)
    }

    private fun drawApplyButton(canvas: Canvas) {
        val rect = geometry.apply
        paint.style = Paint.Style.FILL
        paint.color = if (applied) actionActiveSurface else actionSurface
        canvas.drawRoundRect(rect, 7f * density, 7f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * density
        paint.color = blue
        canvas.drawRoundRect(rect, 7f * density, 7f * density, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = foreground
        boldPaint.textSize = min(13f * scaledDensity, rect.height() * 0.23f)
        val label = if (applied) localized("取消应用", "Remove from meter") else localized("应用到测光", "Apply to meter")
        drawWrapped(canvas, label, rect, boldPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchTarget = targetAt(event.x, event.y)
                if (touchTarget == TouchTarget.NONE) return false
                touchStartX = event.x
                touchStartY = event.y
                touchStartIndex = selectedDistanceIndex
                moved = false
                if (touchTarget == TouchTarget.SCALE) parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchTarget == TouchTarget.NONE) return false
                if (abs(event.x - touchStartX) > touchSlop || abs(event.y - touchStartY) > touchSlop) moved = true
                if (touchTarget == TouchTarget.SCALE && moved) {
                    val spacing = maxOf(30f * density, scaleContentWidth() / 6f)
                    val index = (touchStartIndex - ((event.x - touchStartX) / spacing).toInt())
                        .coerceIn(0, FlashDistanceScale.meters.lastIndex)
                    selectDistanceIndex(index)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (!cancelled && !moved) {
                    performClick()
                    when (touchTarget) {
                        TouchTarget.BACK -> listener?.onBackToToolsRequested()
                        TouchTarget.CLOSE -> listener?.onCloseRequested()
                        TouchTarget.SETTINGS -> listener?.onSettingsRequested(configuration)
                        TouchTarget.APPLY -> toggleApplied()
                        else -> Unit
                    }
                }
                touchTarget = TouchTarget.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun targetAt(x: Float, y: Float): TouchTarget = when {
        geometry.back.contains(x, y) -> TouchTarget.BACK
        geometry.close.contains(x, y) -> TouchTarget.CLOSE
        geometry.scale.contains(x, y) -> TouchTarget.SCALE
        geometry.selectFilm.contains(x, y) -> TouchTarget.SETTINGS
        geometry.apply.contains(x, y) -> TouchTarget.APPLY
        else -> TouchTarget.NONE
    }

    private fun selectDistanceIndex(index: Int) {
        if (index == selectedDistanceIndex) return
        selectedDistanceIndex = index
        configuration = configuration.copy(distanceMeters = FlashDistanceScale.meters[index]).normalized()
        repository.saveSelected(configuration)
        if (applied) {
            repository.apply(configuration)
            listener?.onAppliedFlashChanged(configuration)
        }
        haptic()
        invalidate()
    }

    private fun toggleApplied() {
        applied = !applied
        if (applied) {
            repository.apply(configuration)
            listener?.onAppliedFlashChanged(configuration)
        } else {
            repository.clearApplied()
            listener?.onAppliedFlashChanged(null)
        }
        haptic()
        invalidate()
    }

    private fun previewAdjustment(): FlashAdjustment = FlashExposureMath.adjustment(
        configuration = configuration,
        autofocusDistanceMeters = state.distanceMeasurementState.effectiveMetersForFlash,
        meteringIso = configuration.iso,
        ambientEv100 = state.ambientEffectiveEv100,
        exposureCompensationEv = state.exposureCompEv,
        lockMode = state.exposureLockMode,
        lockedApertureStop = state.lockedApertureStop,
        lockedShutterLogSeconds = state.lockedShutterLogSeconds,
    )

    private fun currentDistanceLabel(): String {
        if (!configuration.isAutoDistance) return FlashDistanceScale.label(configuration.distanceMeters)
        val estimate = state.distanceMeasurementState.estimate?.takeIf { it.isFresh } ?: return "Auto"
        val source = when (estimate.source) {
            DistanceSource.FOCUS_CALIBRATED -> localized("AF", "AF")
            DistanceSource.FOCUS_APPROXIMATE -> localized("AF近似", "AF approx")
            DistanceSource.MANUAL -> localized("手动", "Manual")
        }
        val quality = when (estimate.quality) {
            DistanceQuality.HIGH -> localized("高", "high")
            DistanceQuality.MEDIUM -> localized("中", "med")
            DistanceQuality.LOW -> localized("低", "low")
        }
        return "${FlashDistanceScale.label(estimate.meters)} · $source · $quality"
    }

    private fun scaleContentWidth(): Float {
        val titleWidth = min(82f * density, geometry.scale.width() * 0.26f)
        return geometry.scale.width() - titleWidth - 5f * density
    }

    private fun drawNeutralButton(rect: RectF, label: String, canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = muted
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = foreground
        boldPaint.textSize = min(12f * scaledDensity, rect.height() * 0.30f)
        drawWrapped(canvas, label, rect, boldPaint)
    }

    private fun drawWrapped(canvas: Canvas, label: String, rect: RectF, textPaint: TextPaint) {
        val available = rect.width() - 10f * density
        if (textPaint.measureText(label) <= available) {
            centered(canvas, label, rect.centerX(), rect.centerY(), textPaint)
            return
        }
        val split = (label.length / 2).coerceAtLeast(1)
        val lines = listOf(label.take(split), label.drop(split))
        lines.forEachIndexed { index, line ->
            centered(canvas, line, rect.centerX(), rect.centerY() + (index - 0.5f) * textPaint.textSize * 1.16f, textPaint)
        }
    }

    private fun centered(canvas: Canvas, text: String, x: Float, y: Float, textPaint: Paint) {
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    private fun haptic() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHapticAt > 35L) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastHapticAt = now
        }
    }

    private fun updateContentDescription() {
        contentDescription = "GN${formatGn(configuration.guideNumber)}, ${currentDistanceLabel()}, ${FlashPowerScale.label(configuration.powerDenominator)}"
    }

    private fun formatGn(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(java.util.Locale.US, value)

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
