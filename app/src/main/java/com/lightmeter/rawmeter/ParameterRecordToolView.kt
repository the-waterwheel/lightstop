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
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
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

    private val density get() = layoutDensity(LayoutProfile.TOOL)
    private val scaledDensity get() = layoutTextDensity(LayoutProfile.TOOL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = InstrumentStyle.labelTypeface }
    private val path = Path()
    private val background: Int get() = InstrumentStyle.background(state.isDarkMode)
    private val foreground: Int get() = InstrumentStyle.foreground(state.isDarkMode)
    private val muted: Int get() = InstrumentStyle.secondary(state.isDarkMode)
    private val panel: Int get() = InstrumentStyle.panel(state.isDarkMode)
    private val actionSurface: Int get() = InstrumentStyle.control(state.isDarkMode)
    private val red = InstrumentStyle.red
    private var geometry = ParameterRecordToolGeometry.EMPTY
    private var target = Target.NONE
    private var locationState = ParameterLocationDisplayState.DISABLED
    private val accessibilityHelper = CanvasAccessibilityHelper(this, ::handleAccessibilityClick)
    private val accessibilityManager: AccessibilityManager?
        get() = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    fun resumePage() {
        // A resident Zone/YUV session can temporarily have no RAW surface while the selected
        // workflow still supports transient RAW capture. Never erase the user's record intent
        // merely because this instantaneous session is not RAW-ready.
        invalidate()
    }

    fun setGpsEnabled(enabled: Boolean) {
        repository.options = repository.options.copy(recordGps = enabled)
        if (!enabled) locationState = ParameterLocationDisplayState.DISABLED
        invalidate()
    }

    fun setLocationState(state: ParameterLocationDisplayState) {
        if (locationState == state) return
        locationState = state
        invalidate()
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        listener?.onRecordingStateChanged(false)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geometry = ParameterRecordToolGeometryCalculator.calculate(w, h, resources.displayMetrics.density)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        accessibilityHelper.update(accessibilityNodes())
        drawHeader(canvas)
        drawHistory(canvas)
        val options = repository.options
        drawOption(canvas, geometry.gpsRow, geometry.gpsToggle, localized("记录 GPS 定位", "Record GPS location"),
            locationDescription(), options.recordGps, true)
        drawOption(canvas, geometry.timeRow, geometry.timeToggle, localized("记录时间", "Record time"),
            localized("保存拍摄时间", "Keep capture time"), options.recordTime, true)
        drawOption(canvas, geometry.rawRow, geometry.rawToggle, localized("记录 RAW 数据", "Record RAW data"),
            localized("保留原始测光数据", "Keep original metering data"), options.recordRaw, state.cameraInfo.rawAvailable)
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
        paint.color = InstrumentStyle.border(state.isDarkMode)
        paint.strokeWidth = 0.8f * density
        canvas.drawLine(geometry.history.left, geometry.back.bottom,
            geometry.history.right, geometry.back.bottom, paint)
    }

    private fun drawHistory(canvas: Canvas) {
        drawPanel(canvas, geometry.history)
        val icon = RectF(
            geometry.history.left + 14f * density,
            geometry.history.centerY() - 18f * density,
            geometry.history.left + 43f * density,
            geometry.history.centerY() + 18f * density,
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
        centered(canvas, localized("过往记录", "History"), icon.right + 14f * density,
            geometry.history.centerY() - 7f * density, boldPaint)
        paint.style = Paint.Style.FILL
        paint.color = muted
        paint.textSize = 10f * scaledDensity
        paint.textAlign = Paint.Align.LEFT
        val count = repository.categories().sumOf { it.records.size }
        centered(canvas, localized("$count 张已保存", "$count saved records"), icon.right + 14f * density,
            geometry.history.centerY() + 13f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        val x = geometry.history.right - 18f * density
        val y = geometry.history.centerY()
        canvas.drawLine(x - 3f * density, y - 5f * density, x + 2f * density, y, paint)
        canvas.drawLine(x + 2f * density, y, x - 3f * density, y + 5f * density, paint)
    }

    private fun drawOption(canvas: Canvas, row: RectF, toggle: RectF, label: String,
        description: String, enabled: Boolean, available: Boolean) {
        drawPanel(canvas, row)
        boldPaint.textAlign = Paint.Align.LEFT
        boldPaint.textSize = 12.5f * scaledDensity
        boldPaint.color = if (available) foreground else muted
        val availableWidth = (toggle.left - row.left - 22f * density).coerceAtLeast(0f)
        val spacious = row.height() >= 66f * density
        val fitted = TextUtils.ellipsize(label, TextPaint(boldPaint), availableWidth, TextUtils.TruncateAt.END)
        centered(canvas, fitted.toString(), row.left + 12f * density,
            row.centerY() - if (spacious) 9f * density else 0f, boldPaint)
        if (spacious) {
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.DEFAULT
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 9.5f * scaledDensity
            paint.color = muted
            centered(canvas, TextUtils.ellipsize(description, TextPaint(paint), availableWidth,
                TextUtils.TruncateAt.END).toString(), row.left + 12f * density,
                row.centerY() + 13f * density, paint)
        }
        val track = RectF(
            toggle.centerX() - 20f * density,
            toggle.centerY() - 9f * density,
            toggle.centerX() + 20f * density,
            toggle.centerY() + 9f * density,
        )
        paint.style = Paint.Style.FILL
        paint.color = when {
            !available -> InstrumentStyle.border(state.isDarkMode)
            enabled -> red
            else -> InstrumentStyle.border(state.isDarkMode)
        }
        canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint)
        paint.color = if (enabled && available) Color.WHITE else actionSurface
        val x = if (enabled && available) track.right - 9f * density else track.left + 9f * density
        canvas.drawCircle(x, track.centerY(), 7f * density, paint)
    }

    private fun locationDescription(): String = when (locationState) {
            ParameterLocationDisplayState.DISABLED -> localized("保存拍摄位置", "Keep capture location")
            ParameterLocationDisplayState.REQUESTING -> localized("定位中…", "Locating…")
            ParameterLocationDisplayState.FINE_FIX -> localized("已定位", "Located")
            ParameterLocationDisplayState.COARSE_FIX -> localized("粗略位置", "Coarse")
            ParameterLocationDisplayState.NO_FIX -> localized("无有效定位", "No fix")
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
        paint.color = actionSurface
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = InstrumentStyle.border(state.isDarkMode)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, paint)
        val iconSize = minOf(rect.width(), rect.height()) * 0.30f
        paint.style = Paint.Style.FILL
        paint.color = if (enabled) red else muted
        canvas.drawRect(
            rect.centerX() - iconSize / 2f,
            rect.centerY() - iconSize / 2f,
            rect.centerX() + iconSize / 2f,
            rect.centerY() + iconSize / 2f,
            paint,
        )
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 12f * scaledDensity
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
        paint.color = if (recording) panel else actionSurface
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = InstrumentStyle.border(state.isDarkMode)
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius - density, paint)
        paint.strokeWidth = 1.6f * density
        paint.color = red
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius * 0.62f, paint)
        paint.style = Paint.Style.FILL
        paint.color = red
        val iconSize = radius * 0.26f
        if (!recording) {
            canvas.drawCircle(rect.centerX(), rect.centerY(), iconSize, paint)
        } else canvas.drawRoundRect(RectF(rect.centerX() - iconSize, rect.centerY() - iconSize,
            rect.centerX() + iconSize, rect.centerY() + iconSize), 2f * density, 2f * density, paint)
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.textSize = 12.5f * scaledDensity
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
        paint.color = actionSurface
        canvas.drawRoundRect(rect, 9f * density, 9f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * density
        paint.color = InstrumentStyle.border(state.isDarkMode)
        canvas.drawRoundRect(rect, 9f * density, 9f * density, paint)
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

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibilityHelper.provider

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.view.View"
        info.contentDescription = localized("参数记录工具", "Parameter log tools")
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (accessibilityHelper.handleHoverEvent(event, accessibilityManager)) return true
        return super.dispatchHoverEvent(event)
    }

    private fun accessibilityNodes(): List<CanvasAccessibilityHelper.VirtualNode> = listOf(
        CanvasAccessibilityHelper.VirtualNode(A11Y_BACK, geometry.back, localized("返回", "Back")),
        CanvasAccessibilityHelper.VirtualNode(A11Y_CLOSE, geometry.close, localized("关闭", "Close")),
        CanvasAccessibilityHelper.VirtualNode(
            A11Y_HISTORY,
            geometry.history,
            localized("过往记录", "History"),
        ),
        CanvasAccessibilityHelper.VirtualNode(
            A11Y_GPS,
            geometry.gpsToggle,
            localized("记录 GPS 定位", "Record GPS location"),
            selected = repository.options.recordGps,
        ),
        CanvasAccessibilityHelper.VirtualNode(
            A11Y_TIME,
            geometry.timeToggle,
            localized("记录时间", "Record time"),
            selected = repository.options.recordTime,
        ),
        CanvasAccessibilityHelper.VirtualNode(
            A11Y_RAW,
            geometry.rawToggle,
            localized("记录 RAW 数据", "Record RAW data"),
            selected = repository.options.recordRaw,
        ),
        CanvasAccessibilityHelper.VirtualNode(
            A11Y_START_STOP,
            geometry.startStop,
            if (recording) {
                localized("停止记录", "Stop recording")
            } else {
                localized("开始记录", "Start recording")
            },
            selected = recording,
        ),
        CanvasAccessibilityHelper.VirtualNode(
            A11Y_FINISH,
            geometry.finishCategory,
            localized("结束该类记录", "Finish category"),
        ),
    )

    private fun handleAccessibilityClick(virtualViewId: Int): Boolean {
        val mapped = when (virtualViewId) {
            A11Y_BACK -> Target.BACK
            A11Y_CLOSE -> Target.CLOSE
            A11Y_HISTORY -> Target.HISTORY
            A11Y_GPS -> Target.GPS
            A11Y_TIME -> Target.TIME
            A11Y_RAW -> Target.RAW
            A11Y_START_STOP -> Target.START_STOP
            A11Y_FINISH -> Target.FINISH
            else -> return false
        }
        handle(mapped)
        return true
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

    override fun onDetachedFromWindow() {
        accessibilityHelper.clearFocusForHostExit()
        super.onDetachedFromWindow()
    }

    private companion object {
        private const val A11Y_BACK = 1
        private const val A11Y_CLOSE = 2
        private const val A11Y_HISTORY = 3
        private const val A11Y_GPS = 4
        private const val A11Y_TIME = 5
        private const val A11Y_RAW = 6
        private const val A11Y_START_STOP = 7
        private const val A11Y_FINISH = 8
    }
}
