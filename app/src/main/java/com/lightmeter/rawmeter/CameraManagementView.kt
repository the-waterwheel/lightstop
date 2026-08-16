package com.lightmeter.rawmeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max

/** Camera selection UI shared by metering settings and the calibration workflow. */
class CameraManagementView(
    context: Context,
    private val state: MeterState,
) : View(context) {

    interface Listener {
        fun onCloseRequested()
        fun onCameraSelected(cameraId: String)
        fun onCameraNoteRequested(cameraId: String)
        fun onCameraVisibilityRequested(cameraId: String, hidden: Boolean)
    }

    private enum class RowAction { SELECT, NOTE, VISIBILITY }

    private data class HitTarget(
        val cameraId: String,
        val action: RowAction,
        val rect: RectF,
    )

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
    }

    private var closeRect = RectF()
    private var hiddenToggleRect = RectF()
    private var hitTargets: List<HitTarget> = emptyList()
    private var scrollOffset = 0f
    private var maxScrollOffset = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val background = if (state.isDarkMode) Color.BLACK else Color.WHITE
        val foreground = if (state.isDarkMode) Color.rgb(210, 210, 206) else Color.rgb(20, 20, 20)
        val navGray = if (state.isDarkMode) Color.rgb(102, 102, 100) else Color.rgb(226, 226, 223)
        val divider = if (state.isDarkMode) Color.rgb(72, 72, 70) else Color.rgb(205, 205, 201)
        val muted = if (state.isDarkMode) Color.rgb(145, 145, 142) else Color.rgb(108, 108, 104)
        val red = Color.rgb(166, 27, 36)

        canvas.drawColor(background)
        val headerHeight = minOf(height * 0.15f, 76f * density).coerceAtLeast(58f * density)
        paint.style = Paint.Style.FILL
        paint.color = navGray
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, paint)

        closeRect = RectF(0f, 0f, 56f * density, headerHeight)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * density
        paint.color = foreground
        val arrow = Path().apply {
            moveTo(closeRect.right - 12f * density, closeRect.centerY())
            lineTo(closeRect.left + 16f * density, closeRect.centerY())
            lineTo(closeRect.left + 27f * density, closeRect.centerY() - 9f * density)
            moveTo(closeRect.left + 16f * density, closeRect.centerY())
            lineTo(closeRect.left + 27f * density, closeRect.centerY() + 9f * density)
        }
        canvas.drawPath(arrow, paint)

        boldPaint.textSize = 14f * density
        boldPaint.color = foreground
        drawCenteredText(
            canvas,
            localized("摄像头", "Cameras"),
            width / 2f,
            headerHeight / 2f,
            boldPaint,
        )

        val contentTop = headerHeight
        val horizontalPad = 10f * density
        val toggleHeight = 44f * density
        hiddenToggleRect = RectF(
            horizontalPad,
            contentTop + 10f * density,
            width - horizontalPad,
            contentTop + 10f * density + toggleHeight,
        )
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRoundRect(hiddenToggleRect, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = divider
        canvas.drawRoundRect(hiddenToggleRect, 3f * density, 3f * density, paint)
        boldPaint.textSize = 10f * density
        boldPaint.color = foreground
        canvas.drawText(
            localized("显示已隐藏的摄像头", "Show hidden cameras"),
            hiddenToggleRect.left + 12f * density,
            textBaseline(hiddenToggleRect.centerY(), boldPaint),
            boldPaint,
        )
        val switchRect = RectF(
            hiddenToggleRect.right - 50f * density,
            hiddenToggleRect.centerY() - 12f * density,
            hiddenToggleRect.right - 10f * density,
            hiddenToggleRect.centerY() + 12f * density,
        )
        paint.style = Paint.Style.FILL
        paint.color = if (state.showHiddenCameras) foreground else background
        canvas.drawRoundRect(switchRect, 12f * density, 12f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = foreground
        canvas.drawRoundRect(switchRect, 12f * density, 12f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (state.showHiddenCameras) red else foreground
        val knobX = if (state.showHiddenCameras) switchRect.right - 12f * density
        else switchRect.left + 12f * density
        canvas.drawCircle(knobX, switchRect.centerY(), 7f * density, paint)

        val cameras = state.visibleCameras()
        val listTop = hiddenToggleRect.bottom + 10f * density
        val rowGap = 8f * density
        val rowHeight = 112f * density
        val contentHeight = cameras.size * rowHeight + max(0, cameras.size - 1) * rowGap
        maxScrollOffset = max(0f, contentHeight - (height - listTop - 12f * density))
        scrollOffset = scrollOffset.coerceIn(0f, maxScrollOffset)

        canvas.save()
        canvas.clipRect(0f, listTop, width.toFloat(), height.toFloat())
        var y = listTop - scrollOffset
        val targets = mutableListOf<HitTarget>()
        cameras.forEach { camera ->
            val selected = camera.cameraId == state.selectedCameraId
            val hidden = state.isCameraHidden(camera.cameraId)
            val row = RectF(horizontalPad, y, width - horizontalPad, y + rowHeight)
            drawCameraRow(
                canvas = canvas,
                camera = camera,
                row = row,
                selected = selected,
                hidden = hidden,
                background = background,
                foreground = foreground,
                divider = divider,
                muted = muted,
                red = red,
                targets = targets,
            )
            y += rowHeight + rowGap
        }
        if (cameras.isEmpty()) {
            paint.style = Paint.Style.FILL
            paint.color = muted
            paint.textSize = 10f * density
            paint.typeface = Typeface.DEFAULT
            drawCenteredText(
                canvas,
                localized("没有可显示的摄像头", "No cameras to display"),
                width / 2f,
                listTop + 40f * density,
                paint,
            )
        }
        hitTargets = targets
        canvas.restore()
    }

    private fun drawCameraRow(
        canvas: Canvas,
        camera: CameraDescriptor,
        row: RectF,
        selected: Boolean,
        hidden: Boolean,
        background: Int,
        foreground: Int,
        divider: Int,
        muted: Int,
        red: Int,
        targets: MutableList<HitTarget>,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRoundRect(row, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (selected) 1.6f * density else 1f * density
        paint.color = if (selected) red else divider
        canvas.drawRoundRect(row, 3f * density, 3f * density, paint)

        boldPaint.textSize = 11f * density
        boldPaint.color = if (hidden) muted else foreground
        canvas.drawText(
            ellipsize(state.cameraName(camera), row.width() - 24f * density, boldPaint),
            row.left + 12f * density,
            row.top + 22f * density,
            boldPaint,
        )
        paint.style = Paint.Style.FILL
        paint.color = muted
        paint.textSize = 8f * density
        paint.typeface = Typeface.DEFAULT
        val summary = buildString {
            append(camera.technicalSummary())
            if (hidden) append(localized("  ·  已隐藏", "  ·  Hidden"))
            if (selected) append(localized("  ·  当前", "  ·  Current"))
        }
        canvas.drawText(
            ellipsize(summary, row.width() - 24f * density, paint),
            row.left + 12f * density,
            row.top + 39f * density,
            paint,
        )
        val calibration = state.cameraCalibrationRecord(camera.cameraId)
        paint.color = if (calibration == null) muted else foreground
        paint.textSize = 7.5f * density
        val calibrationSummary = if (calibration == null) {
            localized("未校准", "Not calibrated")
        } else {
            val highAccuracy = calibration.rawCorrectionEv?.let {
                "${localized("高精度", "High accuracy")} ${signedEv(it)} EV"
            } ?: localized("高精度不可用", "High accuracy unavailable")
            "$highAccuracy · ${localized("兼容", "Compatible")} " +
                "${signedEv(calibration.compatibleCorrectionEv ?: 0.0)} EV" +
                " · ${calibration.calibrationCount}"
        }
        canvas.drawText(
            ellipsize(calibrationSummary, row.width() - 24f * density, paint),
            row.left + 12f * density,
            row.top + 57f * density,
            paint,
        )

        val gap = 6f * density
        val buttonTop = row.bottom - 34f * density
        val buttonBottom = row.bottom - 8f * density
        val buttonWidth = (row.width() - 24f * density - gap * 2f) / 3f
        val actions = listOf(RowAction.SELECT, RowAction.NOTE, RowAction.VISIBILITY)
        actions.forEachIndexed { index, action ->
            val left = row.left + 12f * density + index * (buttonWidth + gap)
            val rect = RectF(left, buttonTop, left + buttonWidth, buttonBottom)
            val active = action != RowAction.SELECT || !selected
            paint.style = Paint.Style.FILL
            paint.color = if (action == RowAction.SELECT && selected) foreground else background
            canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = when {
                !active -> muted
                action == RowAction.SELECT -> red
                else -> foreground
            }
            canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
            paint.style = Paint.Style.FILL
            paint.color = if (action == RowAction.SELECT && selected) background
            else if (active) foreground else muted
            paint.textSize = 8.5f * density
            paint.typeface = Typeface.DEFAULT_BOLD
            val label = when (action) {
                RowAction.SELECT -> if (selected) localized("当前", "Current")
                else localized("选择", "Select")
                RowAction.NOTE -> localized("备注", "Note")
                RowAction.VISIBILITY -> if (hidden) localized("取消隐藏", "Unhide")
                else localized("隐藏", "Hide")
            }
            drawCenteredText(canvas, label, rect.centerX(), rect.centerY(), paint)
            if (active) targets += HitTarget(camera.cameraId, action, rect)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastY = event.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = event.y - lastY
                if (abs(event.y - downY) > touchSlop) dragging = true
                if (dragging && maxScrollOffset > 0f) {
                    scrollOffset = (scrollOffset - delta).coerceIn(0f, maxScrollOffset)
                    invalidate()
                }
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && abs(event.x - downX) <= touchSlop * 2f) {
                    handleTap(event.x, event.y)
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        when {
            closeRect.contains(x, y) -> {
                haptic()
                listener?.onCloseRequested()
            }
            hiddenToggleRect.contains(x, y) -> {
                state.showHiddenCameras = !state.showHiddenCameras
                scrollOffset = 0f
                haptic()
                invalidate()
            }
            else -> hitTargets.firstOrNull { it.rect.contains(x, y) }?.let { target ->
                haptic()
                when (target.action) {
                    RowAction.SELECT -> listener?.onCameraSelected(target.cameraId)
                    RowAction.NOTE -> listener?.onCameraNoteRequested(target.cameraId)
                    RowAction.VISIBILITY -> listener?.onCameraVisibilityRequested(
                        target.cameraId,
                        !state.isCameraHidden(target.cameraId),
                    )
                }
            }
        }
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private fun signedEv(value: Double): String =
        if (value >= 0.0) "+${"%.2f".format(value)}" else "%.2f".format(value)

    private fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun textBaseline(centerY: Float, textPaint: Paint): Float =
        centerY - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        textPaint: Paint,
    ) {
        canvas.drawText(
            text,
            centerX - textPaint.measureText(text) / 2f,
            textBaseline(centerY, textPaint),
            textPaint,
        )
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
