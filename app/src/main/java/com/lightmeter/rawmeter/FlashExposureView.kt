package com.lightmeter.rawmeter

import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

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
        fun onGuideNumberRequested(configuration: FlashConfiguration)
        fun onMeteringIsoRequested(configuration: FlashConfiguration)
        fun onAppliedFlashChanged(configuration: FlashConfiguration?)
    }

    var listener: Listener? = null

    private enum class TouchTarget { BACK, CLOSE, SCALE, AUTO_DISTANCE, SETTINGS, GUIDE_NUMBER, METERING_ISO, APPLY, NONE }

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
    private var displayedDistancePosition = 0f
    private var applied = false
    private var initialized = false
    private var touchTarget = TouchTarget.NONE
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartDistancePosition = 0f
    private var moved = false
    private var lastHapticAt = 0L
    private var distanceAnimator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun openPage() {
        val appliedConfiguration = repository.applied(state.iso)
        applied = appliedConfiguration != null
        configuration = if (appliedConfiguration != null) {
            // An active flash correction must remain stable while it is applied.
            appliedConfiguration
        } else {
            // A fresh, unapplied visit always starts from the Normal meter dial ISO.
            repository.selected(state.iso)
                .copy(iso = state.iso)
                .normalized()
        }
        selectedDistanceIndex = FlashDistanceScale.nearestIndex(configuration.distanceMeters)
        displayedDistancePosition = selectedDistanceIndex.toFloat()
        if (!applied) repository.saveSelected(configuration)
        initialized = true
        invalidate()
    }

    fun resumePage() {
        if (!initialized) openPage() else invalidate()
    }

    fun updateConfiguration(value: FlashConfiguration) {
        configuration = value.normalized()
        selectedDistanceIndex = FlashDistanceScale.nearestIndex(configuration.distanceMeters)
        displayedDistancePosition = selectedDistanceIndex.toFloat()
        repository.saveSelected(configuration)
        if (applied) repository.apply(configuration)
        listener?.onAppliedFlashChanged(configuration.takeIf { applied })
        invalidate()
    }

    fun updateDistance(value: Double?) {
        configuration = configuration.copy(distanceMeters = value).normalized()
        selectedDistanceIndex = FlashDistanceScale.nearestIndex(configuration.distanceMeters)
        displayedDistancePosition = selectedDistanceIndex.toFloat()
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

        val titleWidth = distanceTitleWidth(rect)
        val content = if (state.isLeftHanded) {
            RectF(rect.left + 5f * density, rect.top, rect.right - titleWidth, rect.bottom)
        } else {
            RectF(rect.left + titleWidth, rect.top, rect.right - 5f * density, rect.bottom)
        }
        val titleLeft = if (state.isLeftHanded) rect.right - titleWidth else rect.left
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = foreground
        boldPaint.textSize = 11f * scaledDensity
        centered(canvas, localized("距离", "Distance"), titleLeft + titleWidth * 0.50f, rect.centerY() - 21f * density, boldPaint)
        boldPaint.textSize = 15.5f * scaledDensity
        centered(canvas, distanceValueLabel(), titleLeft + titleWidth * 0.50f, rect.centerY(), boldPaint)

        val autoButton = distanceAutoButtonRect()
        paint.style = Paint.Style.FILL
        paint.color = if (configuration.isAutoDistance) actionActiveSurface else background
        canvas.drawCircle(autoButton.centerX(), autoButton.centerY(), autoButton.width() / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f * density
        paint.color = if (configuration.isAutoDistance) blue else foreground
        canvas.drawCircle(autoButton.centerX(), autoButton.centerY(), autoButton.width() / 2f, paint)
        boldPaint.textSize = 10f * scaledDensity
        boldPaint.color = if (configuration.isAutoDistance) blue else foreground
        centered(canvas, "A", autoButton.centerX(), autoButton.centerY(), boldPaint)

        val baseline = rect.centerY() + 14f * density
        val spacing = distanceTickSpacing(content)
        canvas.save()
        canvas.clipRect(content)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.9f * density
        paint.color = foreground
        canvas.drawLine(content.left, baseline, content.right, baseline, paint)
        FlashDistanceScale.meters.indices.forEach { index ->
            val x = content.centerX() + (index - displayedDistancePosition) * spacing
            if (x !in (content.left - spacing)..(content.right + spacing)) return@forEach
            val major = index == selectedDistanceIndex || FlashDistanceScale.isMajorIndex(index)
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
        boldPaint.textSize = 13f * scaledDensity
        val title = localized(
            "ISO ${configuration.iso} · 点击修改",
            "ISO ${configuration.iso} · tap to edit",
        )
        canvas.drawText(TextUtils.ellipsize(title, boldPaint, available, TextUtils.TruncateAt.END).toString(), x, geometry.filmCard.centerY() - 3f * density, boldPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = foreground
        textPaint.textSize = 9f * scaledDensity
        val details = "GN${configuration.guideNumberReferenceIso} · " +
            "${FlashPowerScale.label(configuration.powerDenominator)} · " +
            localized("损失 %.2f 档", "Loss %.2f stops")
                .format(java.util.Locale.US, configuration.lossStops)
        canvas.drawText(
            TextUtils.ellipsize(details, textPaint, available, TextUtils.TruncateAt.END).toString(),
            x,
            geometry.filmCard.centerY() + 15f * density,
            textPaint,
        )
        drawNeutralButton(geometry.selectFilm, localized("闪光设置", "Flash settings"), canvas)
    }

    private fun drawResult(canvas: Canvas) {
        val rect = geometry.result
        paint.style = Paint.Style.FILL
        paint.color = actionSurface
        canvas.drawRoundRect(rect, 7f * density, 7f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = blue
        canvas.drawRoundRect(rect, 7f * density, 7f * density, paint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = foreground
        textPaint.textSize = 9f * scaledDensity
        centered(
            canvas,
            localized(
                "闪光指数数值 · GN${configuration.guideNumberReferenceIso}",
                "Guide number value · GN${configuration.guideNumberReferenceIso}",
            ),
            rect.centerX(),
            rect.top + 15f * density,
            textPaint,
        )
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.color = foreground
        boldPaint.textSize = min(30f * scaledDensity, rect.height() * 0.34f)
        centered(canvas, formatGn(configuration.guideNumber), rect.centerX(), rect.centerY() - 5f * density, boldPaint)

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
        centered(canvas, fitted.toString(), rect.centerX(), rect.bottom - 15f * density, textPaint)
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
                touchStartDistancePosition = displayedDistancePosition
                moved = false
                if (touchTarget == TouchTarget.SCALE) {
                    distanceAnimator?.cancel()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchTarget == TouchTarget.NONE) return false
                if (abs(event.x - touchStartX) > touchSlop || abs(event.y - touchStartY) > touchSlop) moved = true
                if (touchTarget == TouchTarget.SCALE && moved) {
                    val spacing = distanceTickSpacing(scaleContent())
                    displayedDistancePosition = (
                        touchStartDistancePosition -
                            (event.x - touchStartX) / spacing * DISTANCE_DRAG_INDEX_GAIN
                        ).coerceIn(0f, FlashDistanceScale.meters.lastIndex.toFloat())
                    selectDistanceIndex(displayedDistancePosition.roundToInt())
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (touchTarget == TouchTarget.SCALE) {
                    if (!cancelled && !moved) selectDistanceAt(event.x)
                    commitDistanceSelection()
                    settleDistanceScale()
                } else if (!cancelled && !moved) {
                    performClick()
                    when (touchTarget) {
                        TouchTarget.BACK -> listener?.onBackToToolsRequested()
                        TouchTarget.CLOSE -> listener?.onCloseRequested()
                        TouchTarget.AUTO_DISTANCE -> selectAutomaticDistance()
                        TouchTarget.SETTINGS -> listener?.onSettingsRequested(configuration)
                        TouchTarget.GUIDE_NUMBER -> listener?.onGuideNumberRequested(configuration)
                        TouchTarget.METERING_ISO -> listener?.onMeteringIsoRequested(configuration)
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
        distanceAutoButtonRect().contains(x, y) -> TouchTarget.AUTO_DISTANCE
        geometry.scale.contains(x, y) -> TouchTarget.SCALE
        geometry.selectFilm.contains(x, y) -> TouchTarget.SETTINGS
        geometry.result.contains(x, y) -> TouchTarget.GUIDE_NUMBER
        geometry.filmCard.contains(x, y) -> TouchTarget.METERING_ISO
        geometry.apply.contains(x, y) -> TouchTarget.APPLY
        else -> TouchTarget.NONE
    }

    private fun selectDistanceIndex(index: Int) {
        val safeIndex = index.coerceIn(0, FlashDistanceScale.meters.lastIndex)
        if (safeIndex == selectedDistanceIndex) return
        selectedDistanceIndex = safeIndex
        configuration = configuration.copy(distanceMeters = FlashDistanceScale.meters[safeIndex]).normalized()
        haptic()
        invalidate()
    }

    /** Persist and notify once after a gesture; SharedPreferences/UI work must not run per move. */
    private fun commitDistanceSelection() {
        repository.saveSelected(configuration)
        if (applied) {
            repository.apply(configuration)
            listener?.onAppliedFlashChanged(configuration)
        }
    }

    private fun selectAutomaticDistance() {
        distanceAnimator?.cancel()
        selectDistanceIndex(0)
        displayedDistancePosition = 0f
        commitDistanceSelection()
        settleDistanceScale()
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun toggleApplied() {
        applied = !applied
        if (applied) {
            repository.apply(configuration)
            listener?.onAppliedFlashChanged(configuration)
        } else {
            repository.clearApplied()
            configuration = configuration.copy(iso = state.iso).normalized()
            repository.saveSelected(configuration)
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

    private fun distanceValueLabel(): String {
        val meters = configuration.distanceMeters
            ?: state.distanceMeasurementState.estimate?.takeIf { it.isFresh }?.meters
            ?: return "-- m"
        return when {
            meters < 1.0 -> "%.2f m".format(java.util.Locale.US, meters)
            meters < 10.0 -> "%.1f m".format(java.util.Locale.US, meters)
            else -> "%.0f m".format(java.util.Locale.US, meters)
        }
    }

    private fun distanceTitleWidth(rect: RectF): Float = min(94f * density, rect.width() * 0.29f)

    private fun distanceAutoButtonRect(): RectF {
        val rect = geometry.scale
        if (rect.isEmpty) return RectF()
        val titleWidth = distanceTitleWidth(rect)
        val centerX = if (state.isLeftHanded) {
            rect.right - titleWidth * 0.5f
        } else {
            rect.left + titleWidth * 0.5f
        }
        val radius = 9f * density
        val centerY = rect.centerY() + 23f * density
        return RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
    }

    private fun scaleContent(): RectF {
        val rect = geometry.scale
        val titleWidth = distanceTitleWidth(rect)
        return if (state.isLeftHanded) {
            RectF(rect.left + 5f * density, rect.top, rect.right - titleWidth, rect.bottom)
        } else {
            RectF(rect.left + titleWidth, rect.top, rect.right - 5f * density, rect.bottom)
        }
    }

    private fun distanceTickSpacing(content: RectF): Float =
        maxOf(22f * density, content.width() / 8f)

    private fun selectDistanceAt(x: Float) {
        val content = scaleContent()
        val index = (
            displayedDistancePosition + (x - content.centerX()) / distanceTickSpacing(content)
            ).roundToInt().coerceIn(0, FlashDistanceScale.meters.lastIndex)
        selectDistanceIndex(index)
        displayedDistancePosition = index.toFloat()
    }

    private fun settleDistanceScale() {
        val start = displayedDistancePosition
        val end = selectedDistanceIndex.toFloat()
        distanceAnimator?.cancel()
        distanceAnimator = ValueAnimator.ofFloat(start, end).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayedDistancePosition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
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
        contentDescription = "${formatGn(configuration.guideNumber)} at " +
            "GN${configuration.guideNumberReferenceIso}, ISO ${configuration.iso}, " +
            "${currentDistanceLabel()}, ${FlashPowerScale.label(configuration.powerDenominator)}"
    }

    private fun formatGn(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(java.util.Locale.US, value)

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        distanceAnimator?.cancel()
        distanceAnimator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DISTANCE_DRAG_INDEX_GAIN = 1.8f
    }
}
