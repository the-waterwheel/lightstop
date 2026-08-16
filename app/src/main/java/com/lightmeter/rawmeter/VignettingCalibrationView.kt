package com.lightmeter.rawmeter

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
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** A parameter-free calibration surface dedicated to per-lens RAW vignetting correction. */
class VignettingCalibrationView(
    context: Context,
    private val state: MeterState,
) : View(context) {

    interface Listener {
        fun onExitRequested()
        fun onCameraRequested()
        fun onResetRequested()
        fun onHistoryRestoreRequested(createdAtEpochMs: Long)
        fun onCalibrationRequested()
    }

    private data class Geometry(
        val preview: RectF,
        val back: RectF,
        val reset: RectF,
        val camera: RectF,
        val controls: RectF,
        val historyRows: List<RectF>,
        val primary: RectF,
    )

    private data class PreviewSelection(
        val cameraId: String,
        val createdAtEpochMs: Long,
    )

    var listener: Listener? = null
    var isCalibrating: Boolean = false
        private set

    private val density = resources.displayMetrics.density
    private val foreground: Int
        get() = if (state.isDarkMode) Color.rgb(210, 210, 206) else Color.rgb(20, 20, 20)
    private val surfaceColor: Int
        get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val muted: Int
        get() = if (state.isDarkMode) Color.rgb(150, 150, 146) else Color.rgb(112, 112, 108)
    private val disabled: Int
        get() = if (state.isDarkMode) Color.rgb(64, 64, 62) else Color.rgb(218, 218, 214)
    private val red = Color.rgb(166, 27, 36)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans", Typeface.NORMAL)
    }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans", Typeface.BOLD)
    }
    private var geometry: Geometry? = null
    private var statusText = ""
    private var statusIsError = false
    private var enlargedPreview: PreviewSelection? = null
    private var enlargedPreviewClose = RectF()
    private val previewBitmaps = LinkedHashMap<String, Bitmap>(12, 0.75f, true)

    init {
        isClickable = true
    }

    fun calculatePreviewFrame(width: Int, height: Int): RectF =
        calculateGeometry(width, height).preview

    fun applyTheme() = invalidate()

    fun closeEnlargedPreview(): Boolean {
        if (enlargedPreview == null) return false
        enlargedPreview = null
        enlargedPreviewClose.setEmpty()
        invalidate()
        return true
    }

    fun setCalibrating() {
        isCalibrating = true
        statusText = localized(
            "正在拍摄并校准…",
            "Capturing and calibrating…",
        )
        statusIsError = false
        invalidate()
    }

    fun showResult(info: VignettingCalibrationInfo) {
        isCalibrating = false
        statusText = localized(
            "暗角矫正完成 · ${formatCalibrationTime(info.createdAtEpochMs)}",
            "Vignetting correction completed · ${formatCalibrationTime(info.createdAtEpochMs)}",
        )
        statusIsError = false
        invalidate()
    }

    fun showError(message: String) {
        isCalibrating = false
        statusText = message
        statusIsError = true
        invalidate()
    }

    fun showReset() {
        isCalibrating = false
        statusText = localized(
            "当前暗角矫正已重置，可从历史回退",
            "Current correction reset; history remains available",
        )
        statusIsError = false
        invalidate()
    }

    fun showHistoryRestored(info: VignettingCalibrationInfo) {
        isCalibrating = false
        statusText = localized(
            "已回退到 ${formatCalibrationTime(info.createdAtEpochMs)} 的矫正",
            "Restored correction from ${formatCalibrationTime(info.createdAtEpochMs)}",
        )
        statusIsError = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = geometry ?: calculateGeometry(width, height).also { geometry = it }
        drawSurfaceOutsidePreview(canvas, g.preview)
        drawHeader(canvas, g)
        drawPreviewOverlay(canvas, g)
        drawControls(canvas, g)
        drawEnlargedPreview(canvas)
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
            localized("暗角矫正", "Vignetting correction"),
            width / 2f,
            g.back.centerY(),
            boldPaint,
        )
        boldPaint.textSize = 10f * density
        boldPaint.color = if (isCalibrating) muted else red
        drawCenteredText(
            canvas,
            localized("重置", "Reset"),
            g.reset.centerX(),
            g.reset.centerY(),
            boldPaint,
        )
    }

    private fun drawPreviewOverlay(canvas: Canvas, g: Geometry) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = foreground
        canvas.drawRect(g.preview, paint)
        paint.color = surfaceColor
        paint.strokeWidth = 2f * density
        canvas.drawCircle(g.preview.centerX(), g.preview.centerY(), 11f * density, paint)
        paint.color = foreground
        paint.strokeWidth = 1f * density
        canvas.drawCircle(g.preview.centerX(), g.preview.centerY(), 3f * density, paint)
        drawCameraSelector(canvas, g.camera)

        val badge = RectF(
            g.preview.left + 7f * density,
            g.preview.bottom - 25f * density,
            g.preview.left + 132f * density,
            g.preview.bottom - 7f * density,
        )
        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
        canvas.drawRoundRect(badge, 3f * density, 3f * density, paint)
        paint.color = foreground
        paint.textSize = 8f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        drawCenteredText(
            canvas,
            localized("完整取景", "Full camera view"),
            badge.centerX(),
            badge.centerY(),
            paint,
        )
    }

    private fun drawControls(canvas: Canvas, g: Geometry) {
        val rawAvailable = currentCameraRawAvailable()
        val info = state.currentCamera()?.let { state.vignettingCalibrationInfo(it.cameraId) }
        boldPaint.color = foreground
        boldPaint.textSize = 13f * density
        drawCenteredText(
            canvas,
            localized("对准亮度均匀的画面", "Aim at a uniformly lit scene"),
            g.controls.centerX(),
            g.controls.top + 20f * density,
            boldPaint,
        )
        paint.style = Paint.Style.FILL
        paint.color = muted
        paint.textSize = 8f * density
        paint.typeface = Typeface.DEFAULT
        val instructions = listOf(
            localized(
                "让均匀白墙、柔光板或均匀天空填满整个取景框。",
                "Fill the full frame with an even wall, diffuser, or uniform sky.",
            ),
            localized(
                "避开阴影、反光和过曝，拍摄时保持手机稳定。",
                "Avoid shadows, glare, and clipping, and hold the phone steady.",
            ),
        )
        instructions.forEachIndexed { index, line ->
            drawCenteredText(
                canvas,
                ellipsize(line, g.controls.width(), paint),
                g.controls.centerX(),
                g.controls.top + (43f + index * 17f) * density,
                paint,
            )
        }

        drawHistory(canvas, g, info)
        if (statusText.isNotEmpty()) {
            paint.color = if (statusIsError) red else muted
            paint.textSize = 7.5f * density
            drawCenteredText(
                canvas,
                ellipsize(statusText, g.controls.width(), paint),
                g.controls.centerX(),
                g.primary.top - 10f * density,
                paint,
            )
        }

        val enabled = rawAvailable && !isCalibrating
        paint.style = Paint.Style.FILL
        paint.color = if (enabled) foreground else disabled
        canvas.drawRoundRect(g.primary, 4f * density, 4f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = if (enabled) red else muted
        canvas.drawRoundRect(g.primary, 4f * density, 4f * density, paint)
        boldPaint.color = if (enabled) surfaceColor else muted
        boldPaint.textSize = 11f * density
        val buttonText = when {
            !rawAvailable -> localized(
                "当前镜头不支持此项校准",
                "This lens does not support this calibration",
            )
            isCalibrating -> localized("正在校准", "Calibrating")
            info != null -> localized("重新拍摄并矫正", "Capture and recalibrate")
            else -> localized("拍摄并矫正", "Capture and calibrate")
        }
        drawCenteredText(canvas, buttonText, g.primary.centerX(), g.primary.centerY(), boldPaint)
    }

    private fun drawHistory(
        canvas: Canvas,
        g: Geometry,
        active: VignettingCalibrationInfo?,
    ) {
        val cameraId = state.currentCamera()?.cameraId
        val history = cameraId?.let(state::vignettingCalibrationHistory).orEmpty().take(3)
        if (g.historyRows.isEmpty()) return
        paint.style = Paint.Style.FILL
        paint.color = muted
        paint.textSize = 7.5f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(
            localized("当前镜头 · 最近 3 次矫正", "Current lens · last 3 corrections"),
            g.controls.left,
            g.historyRows.first().top - 5f * density,
            paint,
        )
        if (history.isEmpty()) {
            paint.typeface = Typeface.DEFAULT
            drawCenteredText(
                canvas,
                if (currentCameraRawAvailable()) {
                    localized("暂无矫正记录", "No correction history")
                } else {
                    localized(
                        "当前镜头不支持此项校准",
                        "This lens does not support this calibration",
                    )
                },
                g.historyRows.first().centerX(),
                g.historyRows.first().centerY(),
                paint,
            )
            return
        }
        history.forEachIndexed { index, record ->
            val row = g.historyRows[index]
            val isActive = active?.createdAtEpochMs == record.createdAtEpochMs
            paint.style = Paint.Style.FILL
            paint.color = if (isActive) disabled else surfaceColor
            canvas.drawRoundRect(row, 3f * density, 3f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = if (isActive) red else disabled
            canvas.drawRoundRect(row, 3f * density, 3f * density, paint)

            val action = historyActionRect(row)
            paint.style = Paint.Style.FILL
            paint.color = if (isActive) muted else foreground
            canvas.drawRoundRect(action, 2.5f * density, 2.5f * density, paint)
            boldPaint.color = surfaceColor
            boldPaint.textSize = 7.5f * density
            drawCenteredText(
                canvas,
                localized(if (isActive) "当前" else "回退", if (isActive) "Current" else "Restore"),
                action.centerX(),
                action.centerY(),
                boldPaint,
            )

            val thumbnail = historyThumbnailRect(row, action)
            val bitmap = previewBitmap(cameraId.orEmpty(), record)
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            canvas.drawRect(thumbnail, paint)
            if (bitmap != null) {
                paint.isFilterBitmap = true
                canvas.drawBitmap(bitmap, null, thumbnail, paint)
            }
            paint.isFilterBitmap = false
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = if (isActive) red else muted
            canvas.drawRect(thumbnail, paint)

            paint.style = Paint.Style.FILL
            paint.color = foreground
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = 7.4f * density
            val textWidth = thumbnail.left - row.left - 12f * density
            canvas.drawText(
                ellipsize(formatCalibrationTime(record.createdAtEpochMs), textWidth, paint),
                row.left + 7f * density,
                row.centerY() - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f,
                paint,
            )
        }
    }

    private fun historyActionRect(row: RectF): RectF {
        val width = min(62f * density, row.width() * 0.24f)
        return RectF(row.right - width, row.top, row.right, row.bottom)
    }

    private fun historyThumbnailRect(row: RectF, action: RectF): RectF {
        val inset = 2f * density
        val availableSize = (row.height() - inset * 2f).coerceAtLeast(1f)
        val size = min(availableSize, 34f * density)
        val right = action.left - 5f * density
        return RectF(right - size, row.centerY() - size / 2f, right, row.centerY() + size / 2f)
    }

    private fun previewBitmap(
        cameraId: String,
        info: VignettingCalibrationInfo,
    ): Bitmap? {
        if (cameraId.isBlank() || info.previewWidth <= 0 || info.previewHeight <= 0 ||
            info.previewGrayscale.size != info.previewWidth * info.previewHeight
        ) return null
        val key = "$cameraId:${info.createdAtEpochMs}"
        previewBitmaps[key]?.takeIf { !it.isRecycled }?.let { return it }
        val pixels = IntArray(info.previewGrayscale.size) { index ->
            val value = info.previewGrayscale[index].toInt() and 0xFF
            Color.rgb(value, value, value)
        }
        val bitmap = Bitmap.createBitmap(
            pixels,
            info.previewWidth,
            info.previewHeight,
            Bitmap.Config.ARGB_8888,
        )
        while (previewBitmaps.size >= MAX_CACHED_PREVIEWS) {
            val eldest = previewBitmaps.entries.firstOrNull() ?: break
            previewBitmaps.remove(eldest.key)
            eldest.value.recycle()
        }
        previewBitmaps[key] = bitmap
        return bitmap
    }

    private fun drawEnlargedPreview(canvas: Canvas) {
        val selection = enlargedPreview ?: return
        val info = state.vignettingCalibrationHistory(selection.cameraId).firstOrNull {
            it.createdAtEpochMs == selection.createdAtEpochMs
        } ?: run {
            closeEnlargedPreview()
            return
        }
        val bitmap = previewBitmap(selection.cameraId, info) ?: return
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(205, 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val bounds = RectF(
            width * 0.09f,
            height * 0.16f,
            width * 0.91f,
            height * 0.84f,
        )
        val image = fitAspect(bounds, bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
        val frame = RectF(image).apply { inset(-5f * density, -5f * density) }
        paint.color = Color.rgb(245, 245, 242)
        canvas.drawRoundRect(frame, 4f * density, 4f * density, paint)
        paint.isFilterBitmap = true
        canvas.drawBitmap(bitmap, null, image, paint)
        paint.isFilterBitmap = false
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = Color.WHITE
        canvas.drawRect(image, paint)

        val closeSize = 38f * density
        enlargedPreviewClose = RectF(
            frame.right - closeSize,
            frame.top,
            frame.right,
            frame.top + closeSize,
        )
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(205, 20, 20, 20)
        canvas.drawCircle(
            enlargedPreviewClose.centerX(),
            enlargedPreviewClose.centerY(),
            closeSize * 0.38f,
            paint,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        val cross = closeSize * 0.18f
        canvas.drawLine(
            enlargedPreviewClose.centerX() - cross,
            enlargedPreviewClose.centerY() - cross,
            enlargedPreviewClose.centerX() + cross,
            enlargedPreviewClose.centerY() + cross,
            paint,
        )
        canvas.drawLine(
            enlargedPreviewClose.centerX() + cross,
            enlargedPreviewClose.centerY() - cross,
            enlargedPreviewClose.centerX() - cross,
            enlargedPreviewClose.centerY() + cross,
            paint,
        )
    }

    private fun drawCameraSelector(canvas: Canvas, rect: RectF) {
        val camera = state.currentCamera()
        val rawAvailable = currentCameraRawAvailable()
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
        paint.color = if (rawAvailable) foreground else muted
        canvas.drawRoundRect(rect, 3f * density, 3f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (rawAvailable) foreground else muted
        paint.textSize = 8f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        val label = if (camera == null) {
            localized("选择摄像头", "Select camera")
        } else {
            val focal = if (camera.focalLengthMm > 0f) " · %.1f mm".format(camera.focalLengthMm) else ""
            "${state.cameraName(camera)}$focal  ›"
        }
        drawCenteredText(
            canvas,
            ellipsize(label, rect.width() - 12f * density, paint),
            rect.centerX(),
            rect.centerY(),
            paint,
        )
        if (!rawAvailable) {
            paint.color = muted
            paint.textSize = 7f * density
            canvas.drawText(
                localized(
                    "当前镜头不支持此项校准",
                    "This lens does not support this calibration",
                ),
                rect.left,
                rect.bottom + 11f * density,
                paint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        if (enlargedPreview != null) {
            if (enlargedPreviewClose.contains(event.x, event.y)) {
                haptic()
                closeEnlargedPreview()
            }
            performClick()
            return true
        }
        val g = geometry ?: return true
        when {
            g.back.contains(event.x, event.y) -> {
                haptic()
                if (isCalibrating) {
                    statusText = localized("请等待本次校准完成", "Wait for this calibration")
                    statusIsError = true
                    invalidate()
                } else {
                    listener?.onExitRequested()
                }
            }
            g.reset.contains(event.x, event.y) && !isCalibrating -> {
                haptic()
                listener?.onResetRequested()
            }
            g.camera.contains(event.x, event.y) && !isCalibrating -> {
                haptic()
                listener?.onCameraRequested()
            }
            !isCalibrating -> {
                val cameraId = state.currentCamera()?.cameraId
                val history = cameraId?.let(state::vignettingCalibrationHistory).orEmpty().take(3)
                val thumbnailIndex = g.historyRows.indexOfFirst { row ->
                    historyThumbnailRect(row, historyActionRect(row)).contains(event.x, event.y)
                }
                val thumbnailRecord = history.getOrNull(thumbnailIndex)
                if (cameraId != null && thumbnailRecord != null) {
                    haptic()
                    enlargedPreview = PreviewSelection(cameraId, thumbnailRecord.createdAtEpochMs)
                    invalidate()
                    performClick()
                    return true
                }
                val historyIndex = g.historyRows.indexOfFirst { row ->
                    historyActionRect(row).contains(event.x, event.y)
                }
                val selected = history.getOrNull(historyIndex)
                val active = cameraId?.let(state::vignettingCalibrationInfo)
                if (selected != null && active?.createdAtEpochMs != selected.createdAtEpochMs) {
                    haptic()
                    listener?.onHistoryRestoreRequested(selected.createdAtEpochMs)
                } else if (g.primary.contains(event.x, event.y)) {
                    haptic()
                    if (currentCameraRawAvailable()) {
                        listener?.onCalibrationRequested()
                    } else {
                        statusText = localized(
                            "当前镜头不支持此项校准",
                            "This lens does not support this calibration",
                        )
                        statusIsError = false
                        invalidate()
                    }
                }
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
        val headerHeight = minOf(68f * density, maxOf(52f * density, h * 0.075f))
        val back = RectF(pad, 0f, pad + 52f * density, headerHeight)
        val reset = RectF(w - pad - 70f * density, 0f, w - pad, headerHeight)
        val landscape = w > h
        val previewBounds: RectF
        val controls: RectF
        if (landscape) {
            val divider = w * 0.59f
            previewBounds = RectF(pad, headerHeight + gap, divider - gap, h - pad)
            controls = RectF(divider + gap, headerHeight + gap, w - pad, h - pad)
        } else {
            previewBounds = RectF(pad, headerHeight + gap, w - pad, h * 0.61f)
            controls = RectF(pad, h * 0.61f + gap, w - pad, h - pad)
        }
        val preview = fitAspect(previewBounds, fullPreviewAspect(landscape))
        val cameraWidth = minOf(preview.width() - 14f * density, 210f * density)
            .coerceAtLeast(96f * density)
        val camera = RectF(
            preview.left + 7f * density,
            preview.top + 7f * density,
            preview.left + 7f * density + cameraWidth,
            preview.top + 33f * density,
        )
        val primaryHeight = minOf(52f * density, controls.height() * 0.24f)
        val primary = RectF(
            controls.left,
            controls.bottom - primaryHeight,
            controls.right,
            controls.bottom,
        )
        val historyGap = 4f * density
        val historyTop = controls.top + 88f * density
        val historyBottom = primary.top - 22f * density
        val historyHeight = ((historyBottom - historyTop - historyGap * 2f) / 3f)
            .coerceAtLeast(18f * density)
            .coerceAtMost(34f * density)
        val historyRows = (0 until 3).map { index ->
            val top = historyTop + index * (historyHeight + historyGap)
            RectF(controls.left, top, controls.right, top + historyHeight)
        }
        return Geometry(preview, back, reset, camera, controls, historyRows, primary)
    }

    private fun fullPreviewAspect(landscape: Boolean): Float {
        val size = state.cameraInfo.previewSize
        val longAspect = if (size != null && size.width > 0 && size.height > 0) {
            max(size.width, size.height).toFloat() / min(size.width, size.height).toFloat()
        } else {
            4f / 3f
        }
        return if (landscape) longAspect else 1f / longAspect
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

    private fun currentCameraRawAvailable(): Boolean {
        val camera = state.currentCamera()
        return if (camera != null && state.cameraInfo.cameraId == camera.cameraId) {
            state.cameraInfo.rawAvailable
        } else {
            camera?.rawAvailable ?: state.cameraInfo.rawAvailable
        }
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    private fun formatCalibrationTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

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
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && textPaint.measureText(text.substring(0, end) + ellipsis) > maxWidth) {
            end--
        }
        return text.substring(0, end) + ellipsis
    }

    private fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun onDetachedFromWindow() {
        previewBitmaps.values.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        previewBitmaps.clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_CACHED_PREVIEWS = 12
    }
}
