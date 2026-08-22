package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

@SuppressLint("ViewConstructor")
internal class ParameterRecordToolView(
    context: Context,
    private val state: MeterState,
    private val repository: ParameterRecordRepository,
) : View(context) {

    interface Listener {
        fun onBackToToolsRequested()
        fun onCloseRequested()
        fun onHistoryRequested()
        fun onRecordingStateChanged(recording: Boolean)
        fun onGpsEnableRequested()
    }

    var listener: Listener? = null
    var recording: Boolean = false
        private set

    private enum class Target { BACK, CLOSE, HISTORY, GPS, TIME, RAW, RAW_HELP, FINISH, START_STOP, NONE }

    private val density = resources.displayMetrics.density
    private val scaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val path = Path()
    private val background: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val foreground: Int get() = if (state.isDarkMode) Color.rgb(224, 224, 220) else Color.rgb(20, 20, 20)
    private val muted: Int get() = if (state.isDarkMode) Color.rgb(100, 100, 96) else Color.rgb(178, 178, 174)
    private val panel: Int get() = if (state.isDarkMode) Color.rgb(45, 45, 43) else Color.rgb(235, 235, 232)
    private val red = Color.rgb(201, 39, 46)
    private var geometry = ParameterRecordToolGeometry.EMPTY
    private var target = Target.NONE

    fun resumePage() {
        if (!state.cameraInfo.rawAvailable && repository.options.recordRaw) {
            repository.options = repository.options.copy(recordRaw = false)
        }
        invalidate()
    }

    fun setGpsEnabled(enabled: Boolean) {
        repository.options = repository.options.copy(recordGps = enabled)
        invalidate()
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        listener?.onRecordingStateChanged(false)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geometry = ParameterRecordToolGeometryCalculator.calculate(w, h, density)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        drawHeader(canvas)
        drawHistory(canvas)
        val options = repository.options
        drawOption(canvas, geometry.gpsRow, geometry.gpsToggle, localized("记录 GPS 定位", "Record GPS location"), options.recordGps, true)
        drawOption(canvas, geometry.timeRow, geometry.timeToggle, localized("记录时间", "Record time"), options.recordTime, true)
        drawOption(canvas, geometry.rawRow, geometry.rawToggle, localized("记录 RAW 数据", "Record RAW data"), options.recordRaw, state.cameraInfo.rawAvailable)
        drawHelp(canvas)
        drawFinishAction(canvas, repository.activeCategoryId != null)
        drawStartStopAction(canvas)
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
        centered(canvas, localized("参数记录", "Parameter log"), width / 2f, y, boldPaint)
    }

    private fun drawHistory(canvas: Canvas) {
        drawPanel(canvas, geometry.history)
        val icon = RectF(
            geometry.history.left + 12f * density,
            geometry.history.top + 10f * density,
            geometry.history.left + 50f * density,
            geometry.history.bottom - 10f * density,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = foreground
        canvas.drawRoundRect(icon, 4f * density, 4f * density, paint)
        path.reset()
        path.moveTo(icon.left + 5f * density, icon.bottom - 6f * density)
        path.lineTo(icon.centerX() - 2f * density, icon.centerY())
        path.lineTo(icon.centerX() + 5f * density, icon.bottom - 11f * density)
        path.lineTo(icon.right - 5f * density, icon.bottom - 6f * density)
        canvas.drawPath(path, paint)
        canvas.drawCircle(icon.right - 9f * density, icon.top + 9f * density, 3f * density, paint)
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.color = foreground
        boldPaint.textSize = 14f * scaledDensity
        centered(canvas, localized("过往记录", "History"), icon.right + 12f * density, geometry.history.centerY(), boldPaint)
    }

    private fun drawOption(canvas: Canvas, row: RectF, toggle: RectF, label: String, enabled: Boolean, available: Boolean) {
        drawPanel(canvas, row)
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = 12.5f * scaledDensity
        boldPaint.color = if (available) foreground else muted
        centered(canvas, label, row.left + 12f * density, row.centerY(), boldPaint)
        val track = RectF(
            toggle.centerX() - 20f * density,
            toggle.centerY() - 9f * density,
            toggle.centerX() + 20f * density,
            toggle.centerY() + 9f * density,
        )
        paint.style = Paint.Style.FILL
        paint.color = when {
            !available -> muted
            enabled -> red
            else -> Color.rgb(150, 150, 146)
        }
        canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint)
        paint.color = if (enabled && available) Color.WHITE else panel
        val x = if (enabled && available) track.right - 9f * density else track.left + 9f * density
        canvas.drawCircle(x, track.centerY(), 7f * density, paint)
    }

    private fun drawHelp(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = if (state.cameraInfo.rawAvailable) foreground else muted
        val radius = 9f * density
        canvas.drawCircle(geometry.rawHelp.centerX(), geometry.rawHelp.centerY(), radius, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 11f * scaledDensity
        boldPaint.color = paint.color
        centered(canvas, "?", geometry.rawHelp.centerX(), geometry.rawHelp.centerY(), boldPaint)
    }

    private fun drawFinishAction(canvas: Canvas, enabled: Boolean) {
        val rect = geometry.finishCategory
        paint.style = Paint.Style.FILL
        paint.color = if (enabled) red else muted
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        val iconSize = minOf(rect.width(), rect.height()) * 0.30f
        paint.color = Color.WHITE
        canvas.drawRect(
            rect.centerX() - iconSize / 2f,
            rect.centerY() - iconSize / 2f,
            rect.centerX() + iconSize / 2f,
            rect.centerY() + iconSize / 2f,
            paint,
        )
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 10f * scaledDensity
        boldPaint.color = if (enabled) foreground else muted
        canvas.drawText(
            localized("结束该类记录", "Finish category"),
            rect.centerX(),
            rect.bottom + 17f * density,
            boldPaint,
        )
    }

    private fun drawStartStopAction(canvas: Canvas) {
        val rect = geometry.startStop
        val radius = minOf(rect.width(), rect.height()) / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (recording) panel else Color.WHITE
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = red
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius - density, paint)
        paint.style = Paint.Style.FILL
        paint.color = red
        val iconSize = radius * 0.36f
        if (!recording) {
            canvas.drawCircle(rect.centerX(), rect.centerY(), iconSize, paint)
        }
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 11f * scaledDensity
        boldPaint.color = foreground
        canvas.drawText(
            if (recording) localized("停止记录", "Stop recording") else localized("开始记录", "Start recording"),
            rect.centerX(),
            rect.bottom + 18f * density,
            boldPaint,
        )
    }

    private fun drawPanel(canvas: Canvas, rect: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * density
        paint.color = muted
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                target = when {
                    geometry.back.contains(event.x, event.y) -> Target.BACK
                    geometry.close.contains(event.x, event.y) -> Target.CLOSE
                    geometry.history.contains(event.x, event.y) -> Target.HISTORY
                    geometry.gpsToggle.contains(event.x, event.y) -> Target.GPS
                    geometry.timeToggle.contains(event.x, event.y) -> Target.TIME
                    geometry.rawHelp.contains(event.x, event.y) -> Target.RAW_HELP
                    geometry.rawToggle.contains(event.x, event.y) -> Target.RAW
                    geometry.finishCategory.contains(event.x, event.y) -> Target.FINISH
                    geometry.startStop.contains(event.x, event.y) -> Target.START_STOP
                    else -> Target.NONE
                }
                return target != Target.NONE
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                handle(target)
                target = Target.NONE
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                target = Target.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handle(target: Target) {
        when (target) {
            Target.BACK -> listener?.onBackToToolsRequested()
            Target.CLOSE -> listener?.onCloseRequested()
            Target.HISTORY -> listener?.onHistoryRequested()
            Target.GPS -> if (repository.options.recordGps) setGpsEnabled(false) else listener?.onGpsEnableRequested()
            Target.TIME -> repository.options = repository.options.copy(recordTime = !repository.options.recordTime)
            Target.RAW -> toggleRaw()
            Target.RAW_HELP -> showRawHelp()
            Target.FINISH -> if (repository.activeCategoryId != null) {
                recording = false
                repository.finishActiveCategory()
                listener?.onRecordingStateChanged(false)
            }
            Target.START_STOP -> {
                recording = !recording
                if (recording) repository.startCategory()
                listener?.onRecordingStateChanged(recording)
            }
            else -> Unit
        }
        invalidate()
    }

    private fun toggleRaw() {
        if (!state.cameraInfo.rawAvailable) {
            showRawHelp()
            return
        }
        if (repository.options.recordRaw) {
            repository.options = repository.options.copy(recordRaw = false)
            return
        }
        if (repository.suppressRawWarning) {
            repository.options = repository.options.copy(recordRaw = true)
            return
        }
        AlertDialog.Builder(context)
            .setTitle(localized("记录 RAW 数据", "Record RAW data"))
            .setMessage(localized(
                "记录 RAW 数据，后续可以随意标点测光，但是会占用大量存储空间。",
                "RAW recording allows later point metering, but uses substantial storage space.",
            ))
            .setNegativeButton(localized("取消", "Cancel"), null)
            .setPositiveButton(localized("确定", "Confirm")) { _, _ ->
                repository.options = repository.options.copy(recordRaw = true)
                invalidate()
            }
            .setNeutralButton(localized("不再显示", "Don't show again")) { _, _ ->
                repository.suppressRawWarning = true
                repository.options = repository.options.copy(recordRaw = true)
                invalidate()
            }
            .show()
    }

    private fun showRawHelp() {
        val message = if (state.cameraInfo.rawAvailable) {
            localized(
                "记录 RAW 数据，后续可以随意标点测光，但是会占用大量存储空间。",
                "RAW recording allows later point metering, but uses substantial storage space.",
            )
        } else {
            localized(
                "该设备或者摄像头不支持 RAW，无法记录 RAW 数据。请选择其他摄像头或者受支持的设备。",
                "This device or camera does not support RAW recording. Choose another camera or a supported device.",
            )
        }
        AlertDialog.Builder(context)
            .setTitle(localized("RAW 数据", "RAW data"))
            .setMessage(message)
            .setPositiveButton(localized("确定", "OK"), null)
            .show()
    }

    private fun centered(canvas: Canvas, text: String, x: Float, y: Float, textPaint: Paint) {
        val metrics = textPaint.fontMetrics
        val baseline = y - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, x, baseline, textPaint)
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
