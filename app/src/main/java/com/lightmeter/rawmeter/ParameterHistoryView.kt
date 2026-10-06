package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.text.TextUtils
import android.text.TextPaint
import android.util.LruCache
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class ParameterHistoryView(
    context: Context,
    private val state: MeterState,
    private val repository: ParameterRecordRepository,
) : View(context) {

    interface Listener {
        fun onCloseRequested()
        fun onRepositoryChanged()
    }
    var listener: Listener? = null

    private enum class Page { CATEGORIES, CATEGORY, DETAIL }
    private enum class Target { BACK, DELETE, GRID, IMAGE, DATA, METERING, DRAWER, NONE }

    private val density get() = layoutDensity(LayoutProfile.SCROLL)
    private val scaledDensity get() = layoutTextDensity(LayoutProfile.SCROLL)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.NORMAL) }
    private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = InstrumentStyle.labelTypeface }
    private val clipPath = Path()
    private val background: Int get() = InstrumentStyle.background(state.isDarkMode)
    private val foreground: Int get() = InstrumentStyle.foreground(state.isDarkMode)
    private val muted: Int get() = InstrumentStyle.secondary(state.isDarkMode)
    private val panel: Int get() = InstrumentStyle.panel(state.isDarkMode)
    private val red = InstrumentStyle.red
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val bitmapCache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val meteringRenderer = RecordedMeteringRenderer({ density }, state)
    private var geometry = ParameterHistoryGeometry.EMPTY
    private var page = Page.CATEGORIES
    private var categoryId: String? = null
    private var detailIndex = 0
    private var scroll = 0f
    private var scrollStart = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var target = Target.NONE
    private var touchedCategoryId: String? = null
    private var touchedRecordIndex: Int? = null
    private var moved = false
    private var longPressTriggered = false
    private var longPressRunnable: Runnable? = null
    private var deletingCategoryId: String? = null
    private var detailWorkingRecord: ParameterRecordEntry? = null
    private var detailPlayback: RecordedMeteringSession? = null
    private var detailStartPlayback: RecordedMeteringSession? = null
    private var meteringTarget = RecordedMeteringTarget.NONE
    private var snapAnimator: ValueAnimator? = null
    private var imageSlideAnimator: ValueAnimator? = null
    private var detailImageOffset = 0f
    private var detailScroll = 0f
    private var detailScrollStart = 0f
    private var detailMaxScroll = 0f
    private var imageDrawRect = RectF()
    private var meteringExpanded = false
    private var meteringExpansion = 0f
    private var drawerStartExpansion = 0f
    private var drawerAnimator: ValueAnimator? = null
    private var pageAnimator: ValueAnimator? = null
    private var pageReveal = 1f

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun open() {
        imageSlideAnimator?.cancel()
        resetMeteringDrawer()
        page = Page.CATEGORIES
        categoryId = null
        detailIndex = 0
        scroll = 0f
        detailWorkingRecord = null
        detailPlayback = null
        detailImageOffset = 0f
        detailScroll = 0f
        animatePageIn()
    }

    fun navigateBack(): Boolean = when (page) {
        Page.DETAIL -> {
            imageSlideAnimator?.cancel()
            snapAnimator?.cancel()
            resetMeteringDrawer()
            page = Page.CATEGORY
            detailWorkingRecord = null
            detailPlayback = null
            detailImageOffset = 0f
            detailScroll = 0f
            scroll = 0f
            animatePageIn()
            true
        }
        Page.CATEGORY -> {
            page = Page.CATEGORIES
            categoryId = null
            scroll = 0f
            animatePageIn()
            true
        }
        Page.CATEGORIES -> false
    }

    @Suppress("DEPRECATION")
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateGeometry()
    }

    private fun updateGeometry() {
        geometry = ParameterHistoryGeometryCalculator.calculate(width, height,
            resources.displayMetrics.density, 0f, meteringExpansion)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        drawHeader(canvas)
        canvas.save()
        canvas.clipRect(geometry.content)
        val layer = canvas.saveLayerAlpha(geometry.content, (255 * pageReveal).toInt())
        canvas.translate(0f, (1f - pageReveal) * 8f * density)
        when (page) {
            Page.CATEGORIES -> drawCategories(canvas)
            Page.CATEGORY -> drawCategory(canvas)
            Page.DETAIL -> drawDetail(canvas)
        }
        canvas.restoreToCount(layer)
        canvas.restore()
    }

    private fun drawHeader(canvas: Canvas) {
        val x = geometry.back.centerX()
        val y = geometry.back.centerY()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * density
        paint.color = foreground
        canvas.drawLine(x + 5f * density, y - 8f * density, x - 4f * density, y, paint)
        canvas.drawLine(x - 4f * density, y, x + 5f * density, y + 8f * density, paint)
        val title = when (page) {
            Page.CATEGORIES -> localized("过往记录", "History")
            Page.CATEGORY, Page.DETAIL -> repository.category(categoryId)?.filmSummary
                ?: localized("参数记录", "Parameter records")
        }
        bold.textAlign = Paint.Align.CENTER
        bold.textSize = 17f * scaledDensity
        bold.color = foreground
        val fitted = TextUtils.ellipsize(title, bold, width - 180f * density, TextUtils.TruncateAt.END)
        centered(canvas, fitted.toString(), width / 2f, y, bold)
        if (page == Page.CATEGORY) {
            bold.textAlign = Paint.Align.CENTER
            bold.textSize = 13f * scaledDensity
            bold.color = red
            centered(canvas, localized("删除", "Delete"), geometry.delete.centerX(), geometry.delete.centerY(), bold)
        } else if (page == Page.DETAIL) {
            val total = repository.category(categoryId)?.records?.size ?: 0
            if (total > 0) {
                paint.style = Paint.Style.FILL
                paint.color = muted
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 9f * scaledDensity
                centered(canvas, "${detailIndex + 1}/$total", geometry.delete.centerX(), geometry.delete.centerY(), paint)
            }
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * density
        paint.color = InstrumentStyle.border(state.isDarkMode)
        canvas.drawLine(geometry.content.left, geometry.back.bottom,
            geometry.content.right, geometry.back.bottom, paint)
    }

    private fun drawCategories(canvas: Canvas) {
        val categories = repository.categories()
        val gap = 10f * density
        val columns = categoryColumns()
        val cellWidth = (geometry.content.width() - gap * (columns - 1)) / columns
        val cellHeight = cellWidth * 0.82f
        val rows = (categories.size + columns - 1) / columns
        val maxScroll = (rows * (cellHeight + gap) - geometry.content.height()).coerceAtLeast(0f)
        scroll = scroll.coerceIn(0f, maxScroll)
        canvas.save()
        canvas.clipRect(geometry.content)
        categories.forEachIndexed { index, category ->
            val column = index % columns
            val row = index / columns
            val rect = RectF(
                geometry.content.left + column * (cellWidth + gap),
                geometry.content.top + row * (cellHeight + gap) - scroll,
                geometry.content.left + column * (cellWidth + gap) + cellWidth,
                geometry.content.top + row * (cellHeight + gap) - scroll + cellHeight,
            )
            if (RectF.intersects(rect, geometry.content)) drawCategoryTile(canvas, rect, category)
        }
        if (categories.isEmpty()) drawEmpty(canvas)
        canvas.restore()
    }

    private fun drawCategoryTile(canvas: Canvas, rect: RectF, category: ParameterRecordCategory) {
        drawPanel(canvas, rect)
        val image = RectF(rect.left + 5f * density, rect.top + 5f * density,
            rect.right - 5f * density, rect.top + rect.height() * 0.64f)
        drawImage(canvas, image, category.coverPath)
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.DEFAULT
        paint.color = muted
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 9f * scaledDensity
        val date = TextUtils.ellipsize(dateFormat.format(Date(category.startedAtEpochMs)),
            TextPaint(paint), rect.width() - 18f * density, TextUtils.TruncateAt.END)
        canvas.drawText(date.toString(), rect.left + 9f * density, image.bottom + 15f * density, paint)
        paint.typeface = InstrumentStyle.labelTypeface
        paint.textSize = 10.5f * scaledDensity
        paint.color = foreground
        val label = category.filmSummary ?: localized("${category.records.size} 张记录", "${category.records.size} records")
        val fitted = TextUtils.ellipsize(label, TextPaint(paint), rect.width() - 18f * density, TextUtils.TruncateAt.END)
        canvas.drawText(fitted.toString(), rect.left + 9f * density, rect.bottom - 10f * density, paint)
    }

    private fun drawCategory(canvas: Canvas) {
        val category = repository.category(categoryId) ?: return
        val gap = 6f * density
        val columns = recordColumns()
        val cellWidth = (geometry.content.width() - gap * (columns - 1)) / columns
        val cellHeight = cellWidth * 0.76f
        val rows = (category.records.size + columns - 1) / columns
        val maxScroll = (rows * (cellHeight + gap) - geometry.content.height()).coerceAtLeast(0f)
        scroll = scroll.coerceIn(0f, maxScroll)
        canvas.save()
        canvas.clipRect(geometry.content)
        category.records.forEachIndexed { index, record ->
            val column = index % columns
            val row = index / columns
            val rect = RectF(
                geometry.content.left + column * (cellWidth + gap),
                geometry.content.top + row * (cellHeight + gap) - scroll,
                geometry.content.left + column * (cellWidth + gap) + cellWidth,
                geometry.content.top + row * (cellHeight + gap) - scroll + cellHeight,
            )
            if (!RectF.intersects(rect, geometry.content)) return@forEachIndexed
            drawImage(canvas, rect, record.previewPath)
            record.capturedAtEpochMs?.let { timestamp ->
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(155, 0, 0, 0)
                canvas.drawRect(rect.left, rect.bottom - 20f * density, rect.right, rect.bottom, paint)
                paint.color = Color.WHITE
                paint.textSize = 8f * scaledDensity
                paint.textAlign = Paint.Align.CENTER
                centered(canvas, timeFormat.format(Date(timestamp)), rect.centerX(), rect.bottom - 10f * density, paint)
            }
        }
        if (category.records.isEmpty()) drawEmpty(canvas)
        canvas.restore()
    }

    private fun drawDetail(canvas: Canvas) {
        val category = repository.category(categoryId) ?: return
        val record = detailWorkingRecord ?: category.records.getOrNull(detailIndex)?.also(::showDetailRecord) ?: return
        val playback = detailPlayback ?: RecordedMeteringSession.from(record).also { detailPlayback = it }
        drawDetailImages(canvas, category, record, playback.mode)
        drawDetailText(canvas, geometry.data, record)
        drawMeteringDrawer(canvas, record, playback)
    }

    private fun drawMeteringDrawer(canvas: Canvas, record: ParameterRecordEntry, playback: RecordedMeteringSession) {
        val drawer = geometry.meteringDrawer
        drawPanel(canvas, drawer)
        canvas.save()
        canvas.clipRect(drawer)
        val handle = geometry.meteringHandle
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 2.5f * density
        paint.color = InstrumentStyle.border(state.isDarkMode)
        canvas.drawLine(handle.centerX() - 12f * density, handle.top + 6f * density,
            handle.centerX() + 12f * density, handle.top + 6f * density, paint)
        bold.textAlign = Paint.Align.LEFT
        bold.textSize = 11f * scaledDensity
        bold.color = foreground
        centered(canvas, localized("曝光参数", "Exposure"), handle.left + 12f * density,
            handle.centerY() + 3f * density, bold)
        val summary = exposureSummary(playback)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 10f * scaledDensity
        paint.color = muted
        val summaryWidth = (handle.width() - 135f * density).coerceAtLeast(0f)
        centered(canvas, TextUtils.ellipsize(summary, TextPaint(paint), summaryWidth,
            TextUtils.TruncateAt.END).toString(), handle.right - 30f * density,
            handle.centerY() + 3f * density, paint)
        val x = handle.right - 16f * density
        val y = handle.centerY() + 3f * density
        val direction = 1f - 2f * meteringExpansion
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f * density
        canvas.drawLine(x - 4f * density, y + direction * 2f * density, x,
            y - direction * 2f * density, paint)
        canvas.drawLine(x, y - direction * 2f * density, x + 4f * density,
            y + direction * 2f * density, paint)
        if (meteringExpansion > 0f && geometry.metering.height() > 0f) {
            meteringRenderer.draw(canvas, geometry.metering, record, playback)
        }
        canvas.restore()
    }

    private fun resetMeteringDrawer() {
        drawerAnimator?.cancel()
        meteringExpanded = false
        meteringExpansion = 0f
        updateGeometry()
    }

    private fun exposureSummary(playback: RecordedMeteringSession): String =
        ExposureMath.formatAperture(ExposureMath.apertureValueForCoordinate(
            playback.apertureCoordinate, state.apertureStep)) + "  " + ExposureMath.formatShutter(
            ExposureMath.shutterValueForCoordinate(playback.shutterCoordinate, state.shutterStep))

    private fun setMeteringExpanded(value: Boolean) {
        drawerAnimator?.cancel()
        meteringExpanded = value
        val end = if (value) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            meteringExpansion = end
            updateGeometry()
            invalidate()
            return
        }
        drawerAnimator = ValueAnimator.ofFloat(meteringExpansion, end).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                meteringExpansion = it.animatedValue as Float
                updateGeometry()
                invalidate()
            }
            start()
        }
    }

    private fun animatePageIn() {
        pageAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            pageReveal = 1f
            invalidate()
            return
        }
        pageAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { pageReveal = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun drawDetailImages(
        canvas: Canvas,
        category: ParameterRecordCategory,
        record: ParameterRecordEntry,
        mode: ParameterRecordMode,
    ) {
        val pageWidth = geometry.image.width()
        canvas.save()
        canvas.clipRect(geometry.image)
        val currentTarget = RectF(geometry.image).apply { offset(detailImageOffset, 0f) }
        drawDetailImage(canvas, currentTarget, record, mode, updateInteractionBounds = detailImageOffset == 0f)
        if (detailImageOffset < 0f) {
            category.records.getOrNull(detailIndex + 1)?.let { adjacent ->
                val target = RectF(currentTarget).apply { offset(pageWidth, 0f) }
                drawDetailImage(canvas, target, adjacent, mode, updateInteractionBounds = false)
            }
        } else if (detailImageOffset > 0f) {
            category.records.getOrNull(detailIndex - 1)?.let { adjacent ->
                val target = RectF(currentTarget).apply { offset(-pageWidth, 0f) }
                drawDetailImage(canvas, target, adjacent, mode, updateInteractionBounds = false)
            }
        }
        canvas.restore()
    }

    private fun drawDetailImage(
        canvas: Canvas,
        target: RectF,
        record: ParameterRecordEntry,
        mode: ParameterRecordMode,
        updateInteractionBounds: Boolean,
    ) {
        val drawnRect = drawImage(canvas, target, record.previewPath, updateInteractionBounds) ?: return
        if (mode == ParameterRecordMode.ZONE ||
            (!RecordedHistoryCapability.canRecalculateZone(record) && record.zonePoints.isNotEmpty())
        ) {
            drawRecordedPoints(canvas, drawnRect, record.zonePoints)
        }
    }

    private fun drawDetailText(canvas: Canvas, rect: RectF, record: ParameterRecordEntry) {
        val viewportBottom = (geometry.meteringDrawer.top - 8f * density).coerceAtLeast(rect.top)
        val viewportHeight = (viewportBottom - rect.top).coerceAtLeast(0f)
        val lineHeight = 22f * density
        val noteRowHeight = 30f * density
        val parameterLines = buildList {
            record.capturedAtEpochMs?.let { add(dateFormat.format(Date(it))) }
            add(
                localized("拍摄参数  ", "Captured  ") +
                    "${ExposureMath.formatAperture(ExposureMath.apertureValueForCoordinate(record.apertureCoordinate, state.apertureStep))}  " +
                    "${ExposureMath.formatShutter(ExposureMath.shutterValueForCoordinate(record.shutterCoordinate, state.shutterStep))}  " +
                    "EI ${record.ei}",
            )
            record.ev100?.let { add("EV100 ${"%.2f".format(it)}") }
            record.filmName?.let { add(localized("胶片  $it", "Film  $it")) }
            record.location?.let { add(formatLocation(it)) }
            record.flash?.let { flash ->
                val mode = if (flash.distanceMode == RecordedFlashDistanceMode.AUTO) "Auto" else localized("手动", "Manual")
                add(
                    localized("闪光", "Flash") + "  ${"%.1f".format(flash.configuredGuideNumber)} " +
                        "(GN${flash.guideNumberReferenceIso}) · ISO ${flash.configuredIso} · " +
                        "${FlashPowerScale.label(flash.powerDenominator)} · " +
                        localized("损失", "loss") + " ${"%.2f".format(flash.lossStops)} · $mode",
                )
                add(formatRecordedFlashAdjustment(flash))
            }
            record.distance?.let { distance -> add(formatRecordedDistance(distance)) }
        }
        var contentHeight = 8f * density + parameterLines.size * lineHeight
        if (record.notes.isNotEmpty()) {
            contentHeight += lineHeight + lineHeight + record.notes.size * noteRowHeight
        }
        if (record.rawPath != null) contentHeight += lineHeight + lineHeight
        detailMaxScroll = (contentHeight - viewportHeight).coerceAtLeast(0f)
        detailScroll = detailScroll.coerceIn(0f, detailMaxScroll)

        canvas.save()
        canvas.clipRect(rect.left, rect.top, rect.right, viewportBottom)
        drawPanel(canvas, RectF(rect.left, rect.top, rect.right, viewportBottom))
        paint.style = Paint.Style.FILL
        paint.color = foreground
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 11.5f * scaledDensity
        var y = rect.top + 16f * density - detailScroll
        val textLeft = rect.left + 12f * density
        val textRight = rect.right - 12f * density
        fun line(
            text: String,
            color: Int = foreground,
            textSize: Float = 11.5f,
            typeface: Typeface = Typeface.create("sans-serif", Typeface.NORMAL),
        ) {
            paint.color = color
            paint.textSize = textSize * scaledDensity
            paint.typeface = typeface
            val fitted = TextUtils.ellipsize(text, TextPaint(paint), textRight - textLeft, TextUtils.TruncateAt.END)
            canvas.drawText(fitted.toString(), textLeft, y, paint)
            y += lineHeight
        }
        parameterLines.forEachIndexed { index, text ->
            val emphasized = text.startsWith("EV100") ||
                text.startsWith("胶片") || text.startsWith("Film") ||
                text.startsWith("拍摄参数") || text.startsWith("Captured")
            line(
                text = text,
                color = if (index == 0 && record.capturedAtEpochMs != null) muted else foreground,
                textSize = if (emphasized) 13f else 11.5f,
                typeface = if (emphasized) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL),
            )
        }
        if (record.notes.isNotEmpty()) {
            y += lineHeight
            line(
                localized("备注", "Notes"),
                textSize = 12f,
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            )
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = InstrumentStyle.border(state.isDarkMode)
            canvas.drawLine(textLeft, y - lineHeight * 0.48f, textRight, y - lineHeight * 0.48f, paint)
            record.notes.forEach { note ->
                paint.style = Paint.Style.FILL
                paint.color = foreground
                paint.textSize = 13f * scaledDensity
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                val fitted = TextUtils.ellipsize(note, TextPaint(paint), textRight - textLeft, TextUtils.TruncateAt.END)
                centered(canvas, fitted.toString(), textLeft, y - lineHeight * 0.48f + noteRowHeight / 2f, paint)
                y += noteRowHeight
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f * density
                paint.color = InstrumentStyle.border(state.isDarkMode)
                canvas.drawLine(textLeft, y - lineHeight * 0.48f, textRight, y - lineHeight * 0.48f, paint)
            }
        }
        if (record.rawPath != null) {
            y += lineHeight
            val previousTypeface = paint.typeface
            val previousTextSize = paint.textSize
            paint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            paint.textSize = 9.5f * scaledDensity
            line(
                localized("RAW 已保存 · 点击图片增减标点", "RAW saved · tap image to add/remove points"),
                muted,
                textSize = 9.5f,
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL),
            )
            paint.typeface = previousTypeface
            paint.textSize = previousTextSize
        }
        canvas.restore()
    }

    private fun formatLocation(location: RecordedLocation): String {
        val latitude = kotlin.math.abs(location.latitude)
        val longitude = kotlin.math.abs(location.longitude)
        val latitudeSide = if (location.latitude >= 0.0) localized("北纬", "N") else localized("南纬", "S")
        val longitudeSide = if (location.longitude >= 0.0) localized("东经", "E") else localized("西经", "W")
        val coordinates = if (state.menuLanguage == MenuLanguage.ENGLISH) {
            "GPS ${"%.5f".format(latitude)}° $latitudeSide · ${"%.5f".format(longitude)}° $longitudeSide"
        } else {
            "GPS $latitudeSide ${"%.5f".format(latitude)}° · $longitudeSide ${"%.5f".format(longitude)}°"
        }
        val accuracy = location.accuracyMeters?.let { "$coordinates  ±${it.roundToInt()} m" } ?: coordinates
        return if (location.permissionQuality == LocationPermissionQuality.COARSE.name) {
            accuracy + localized("  ·  粗略位置", "  ·  Coarse location")
        } else {
            accuracy
        }
    }

    private fun formatRecordedFlashAdjustment(flash: RecordedFlashSnapshot): String = when (flash.adjustmentStatus) {
        RecordedFlashAdjustmentStatus.APPLIED -> localized(
            "闪光补偿  −${"%.2f".format(flash.compensationStops)} 档 · ${flash.effectiveDistanceMeters?.let(::formatDistance).orEmpty()}",
            "Flash adjustment  −${"%.2f".format(flash.compensationStops)} stops · ${flash.effectiveDistanceMeters?.let(::formatDistance).orEmpty()}",
        )
        RecordedFlashAdjustmentStatus.DISTANCE_UNAVAILABLE -> localized("闪光补偿  距离不可用", "Flash adjustment  distance unavailable")
        RecordedFlashAdjustmentStatus.FLASH_DOMINATES -> localized("闪光补偿  闪光已主导曝光", "Flash adjustment  flash dominates")
        RecordedFlashAdjustmentStatus.INVALID -> localized("闪光补偿  保存时无效", "Flash adjustment  invalid at capture")
    }

    private fun formatRecordedDistance(distance: RecordedDistanceSnapshot): String {
        val meters = distance.meters ?: return localized("距离  未记录有效估值", "Distance  no valid estimate recorded")
        val source = when (distance.source) {
            DistanceSource.FOCUS_CALIBRATED -> "AF"
            DistanceSource.FOCUS_APPROXIMATE -> localized("AF近似", "AF approx")
            DistanceSource.FOCUS_ESTIMATED -> localized("AF估算", "AF estimate")
            DistanceSource.MOTION_PARALLAX -> localized("运动视差", "Motion parallax")
            DistanceSource.FUSED -> localized("融合", "Fused")
            DistanceSource.MANUAL -> localized("手动", "Manual")
            null -> localized("未知来源", "unknown source")
        }
        val quality = when (distance.quality) {
            DistanceQuality.HIGH -> localized("高", "high")
            DistanceQuality.MEDIUM -> localized("中", "medium")
            DistanceQuality.LOW -> localized("低", "low")
            null -> localized("未评级", "unrated")
        }
        return localized("距离", "Distance") + "  ${formatDistance(meters)} · $source · $quality"
    }

    private fun formatDistance(meters: Double): String = when {
        meters < 1.0 -> "%.2f m".format(meters)
        meters < 10.0 -> "%.1f m".format(meters)
        else -> "%.0f m".format(meters)
    }

    private fun drawRecordedPoints(canvas: Canvas, rect: RectF, points: List<RecordedZonePoint>) {
        points.forEach { point ->
            val x = rect.left + point.normalizedX * rect.width()
            val y = rect.top + point.normalizedY * rect.height()
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(175, Color.red(red), Color.green(red), Color.blue(red))
            canvas.drawCircle(x, y, 9f * density, paint)
            bold.color = Color.WHITE
            bold.textAlign = Paint.Align.CENTER
            bold.textSize = 7f * scaledDensity
            centered(canvas, point.id.toString(), x, y, bold)
        }
    }

    private fun drawImage(
        canvas: Canvas,
        target: RectF,
        path: String?,
        updateInteractionBounds: Boolean = false,
    ): RectF? {
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(target, 8f * density, 8f * density, Path.Direction.CW)
        canvas.clipPath(clipPath)
        canvas.drawRect(target, paint)
        val bitmap = path?.let(::bitmap) ?: run {
            if (updateInteractionBounds) imageDrawRect.setEmpty()
            canvas.restore()
            return null
        }
        val scale = min(target.width() / bitmap.width, target.height() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val destination = RectF(
            target.centerX() - width / 2f,
            target.centerY() - height / 2f,
            target.centerX() + width / 2f,
            target.centerY() + height / 2f,
        )
        canvas.drawBitmap(bitmap, null, destination, paint)
        canvas.restore()
        if (updateInteractionBounds) imageDrawRect.set(destination)
        return destination
    }

    private fun bitmap(path: String): Bitmap? {
        bitmapCache.get(path)?.let { if (!it.isRecycled) return it }
        val file = File(path)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val maximum = 1200
        var sample = 1
        while (bounds.outWidth / sample > maximum || bounds.outHeight / sample > maximum) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.also { bitmapCache.put(path, it) }
    }

    private fun drawPanel(canvas: Canvas, rect: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = InstrumentStyle.control(state.isDarkMode)
        canvas.drawRoundRect(rect, 10f * density, 10f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * density
        paint.color = InstrumentStyle.border(state.isDarkMode)
        canvas.drawRoundRect(rect, 10f * density, 10f * density, paint)
    }

    private fun drawEmpty(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = muted
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 13f * scaledDensity
        centered(canvas, localized("暂无记录", "No records"), geometry.content.centerX(), geometry.content.centerY(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pageAnimator?.end()
                if (page == Page.DETAIL && imageSlideAnimator?.isRunning == true) return false
                touchStartX = event.x
                touchStartY = event.y
                scrollStart = scroll
                detailScrollStart = detailScroll
                moved = false
                longPressTriggered = false
                target = targetAt(event.x, event.y)
                if (target == Target.DRAWER) {
                    drawerAnimator?.cancel()
                    drawerStartExpansion = meteringExpansion
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (page == Page.DETAIL && target == Target.METERING) {
                    snapAnimator?.cancel()
                    detailStartPlayback = detailPlayback
                }
                if (page == Page.CATEGORIES && target == Target.GRID && touchedCategoryId != null) scheduleLongPress()
                return target != Target.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    moved = true
                    cancelPendingLongPress()
                }
                if (target == Target.DRAWER) {
                    val travel = geometry.metering.height().coerceAtLeast(1f)
                    meteringExpansion = (drawerStartExpansion - dy / travel).coerceIn(0f, 1f)
                    updateGeometry()
                    invalidate()
                } else if (target == Target.GRID && page != Page.DETAIL) {
                    scroll = (scrollStart - dy).coerceAtLeast(0f)
                    invalidate()
                } else if (target == Target.DATA && page == Page.DETAIL) {
                    detailScroll = (detailScrollStart - dy).coerceIn(0f, detailMaxScroll)
                    invalidate()
                } else if (target == Target.IMAGE && page == Page.DETAIL && abs(dx) >= abs(dy)) {
                    updateDetailImageDrag(dx)
                } else if (target == Target.METERING && page == Page.DETAIL) {
                    if (meteringTarget.isDraggable()) detailStartPlayback?.let { start ->
                        val stops = -dx / meteringRenderer.pixelsPerStop(geometry.metering)
                        detailPlayback = if (meteringTarget == RecordedMeteringTarget.ZONE_RAIL) {
                            start.exposureShifted(stops, state)
                        } else {
                            start.shifted(stops, state)
                        }
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (target == Target.DRAWER) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val dy = event.y - touchStartY
                    val expand = when {
                        cancelled -> meteringExpanded
                        !moved -> !meteringExpanded
                        abs(dy) > 24f * density -> dy < 0f
                        else -> meteringExpansion >= 0.5f
                    }
                    if (!cancelled && !moved) performClick()
                    setMeteringExpanded(expand)
                } else if (!cancelled && !longPressTriggered) {
                    if (!moved) {
                        performClick()
                        handleTap(event.x, event.y)
                    } else handleSwipe(event.x - touchStartX)
                } else if (cancelled && target == Target.IMAGE && detailImageOffset != 0f) {
                    animateDetailImageOffset(0f) {
                        detailImageOffset = 0f
                        invalidate()
                    }
                }
                if (!cancelled && target == Target.METERING && moved && meteringTarget.isDraggable()) {
                    animatePlaybackSnap(meteringTarget)
                }
                target = Target.NONE
                touchedCategoryId = null
                touchedRecordIndex = null
                detailStartPlayback = null
                meteringTarget = RecordedMeteringTarget.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun targetAt(x: Float, y: Float): Target {
        if (geometry.back.contains(x, y)) return Target.BACK
        if (page == Page.CATEGORY && geometry.delete.contains(x, y)) return Target.DELETE
        if (page == Page.DETAIL) {
            if (geometry.meteringHandle.contains(x, y)) return Target.DRAWER
            if (geometry.image.contains(x, y)) return Target.IMAGE
            if (geometry.meteringDrawer.contains(x, y) && geometry.metering.contains(x, y)) {
                if (meteringExpansion < 0.99f) return Target.NONE
                meteringTarget = detailPlayback?.let { playback ->
                    meteringRenderer.targetAt(geometry.metering, detailWorkingRecord ?: return@let RecordedMeteringTarget.NONE, playback.mode, x, y)
                } ?: RecordedMeteringTarget.NONE
                return if (meteringTarget == RecordedMeteringTarget.NONE) Target.NONE else Target.METERING
            }
            if (geometry.data.contains(x, y) && y < geometry.meteringDrawer.top) return Target.DATA
            return Target.NONE
        }
        if (!geometry.content.contains(x, y)) return Target.NONE
        if (page == Page.CATEGORIES) touchedCategoryId = categoryAt(x, y)
        else touchedRecordIndex = recordAt(x, y)
        return Target.GRID
    }

    private fun handleTap(x: Float, y: Float) {
        when (target) {
            Target.BACK -> if (!navigateBack()) listener?.onCloseRequested()
            Target.DELETE -> confirmDelete(categoryId)
            Target.GRID -> if (page == Page.CATEGORIES) {
                touchedCategoryId?.let {
                    categoryId = it
                    page = Page.CATEGORY
                    scroll = 0f
                    animatePageIn()
                }
            } else {
                touchedRecordIndex?.let {
                    detailIndex = it
                    repository.category(categoryId)?.records?.getOrNull(it)?.let(::showDetailRecord)
                    detailScroll = 0f
                    page = Page.DETAIL
                    animatePageIn()
                }
            }
            Target.IMAGE -> editRawPoint(x, y)
            Target.METERING -> when (meteringTarget) {
                RecordedMeteringTarget.NORMAL_MODE -> setPlaybackMode(ParameterRecordMode.NORMAL)
                RecordedMeteringTarget.ZONE_MODE -> setPlaybackMode(ParameterRecordMode.ZONE)
                else -> Unit
            }
            else -> Unit
        }
    }

    private fun updateDetailImageDrag(dx: Float) {
        val records = repository.category(categoryId)?.records.orEmpty()
        if (records.isEmpty()) return
        val hasAdjacent = if (dx < 0f) detailIndex < records.lastIndex else detailIndex > 0
        detailImageOffset = if (hasAdjacent) dx else dx * 0.22f
        invalidate()
    }

    private fun handleSwipe(dx: Float) {
        if (page != Page.DETAIL || target != Target.IMAGE) return
        val records = repository.category(categoryId)?.records.orEmpty()
        if (records.isEmpty()) return
        val nextIndex = when {
            dx < 0f && detailIndex < records.lastIndex -> detailIndex + 1
            dx > 0f && detailIndex > 0 -> detailIndex - 1
            else -> detailIndex
        }
        val shouldChange = nextIndex != detailIndex && abs(detailImageOffset) >= geometry.image.width() * 0.12f
        val targetOffset = if (shouldChange) {
            if (nextIndex > detailIndex) -geometry.image.width() else geometry.image.width()
        } else {
            0f
        }
        animateDetailImageOffset(targetOffset) {
            if (shouldChange) {
                detailIndex = nextIndex
                showDetailRecord(records[detailIndex])
                detailScroll = 0f
            }
            detailImageOffset = 0f
            invalidate()
        }
    }

    private fun animateDetailImageOffset(targetOffset: Float, onFinished: () -> Unit) {
        imageSlideAnimator?.cancel()
        val startOffset = detailImageOffset
        if (abs(targetOffset - startOffset) < 0.5f) {
            onFinished()
            return
        }
        imageSlideAnimator = ValueAnimator.ofFloat(startOffset, targetOffset).apply {
            duration = 170L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                detailImageOffset = animation.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onFinished()
                }
            })
            start()
        }
    }

    private fun editRawPoint(x: Float, y: Float) {
        val record = detailWorkingRecord ?: return
        if (record.rawGrid == null) return
        if (record.rawPath == null || !imageDrawRect.contains(x, y)) return
        val normalizedX = ((x - imageDrawRect.left) / imageDrawRect.width()).coerceIn(0f, 1f)
        val normalizedY = ((y - imageDrawRect.top) / imageDrawRect.height()).coerceIn(0f, 1f)
        val existing = record.zonePoints.firstOrNull { point ->
            abs(point.normalizedX - normalizedX) * imageDrawRect.width() < 18f * density &&
                abs(point.normalizedY - normalizedY) * imageDrawRect.height() < 18f * density
        }
        val points = if (existing != null) {
            record.zonePoints.filterNot { it.id == existing.id }
        } else {
            record.zonePoints + RecordedZonePoint(
                id = (record.zonePoints.maxOfOrNull(RecordedZonePoint::id) ?: 0) + 1,
                normalizedX = normalizedX,
                normalizedY = normalizedY,
                ev100 = record.rawEv100At(normalizedX, normalizedY),
                source = MeteringSource.RAW,
            )
        }
        detailWorkingRecord = repository.updateZonePoints(record.categoryId, record.id, points) ?: return
        detailPlayback = (detailPlayback ?: RecordedMeteringSession.from(record)).copy(
            mode = ParameterRecordMode.ZONE,
        )
        invalidate()
    }

    private fun showDetailRecord(record: ParameterRecordEntry) {
        snapAnimator?.cancel()
        detailImageOffset = 0f
        detailWorkingRecord = record
        detailPlayback = RecordedMeteringSession.from(record)
        preloadDetailNeighbors()
    }

    private fun preloadDetailNeighbors() {
        val records = repository.category(categoryId)?.records.orEmpty()
        val paths = listOfNotNull(
            records.getOrNull(detailIndex - 1)?.previewPath,
            records.getOrNull(detailIndex + 1)?.previewPath,
        ).distinct()
        if (paths.isEmpty()) return
        Thread({
            paths.forEach(::bitmap)
            post { if (page == Page.DETAIL) invalidate() }
        }, "parameter-history-preload").start()
    }

    private fun setPlaybackMode(mode: ParameterRecordMode) {
        val playback = detailPlayback ?: return
        if (mode == ParameterRecordMode.ZONE &&
            !RecordedHistoryCapability.canRecalculateZone(detailWorkingRecord ?: return)
        ) return
        if (playback.mode == mode) return
        detailPlayback = playback.copy(mode = mode)
        invalidate()
    }

    private fun animatePlaybackSnap(anchor: RecordedMeteringTarget) {
        val start = detailPlayback ?: return
        val end = start.snapped(anchor, state)
        if (start == end) return
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                detailPlayback = start.copy(
                    apertureCoordinate = start.apertureCoordinate +
                        (end.apertureCoordinate - start.apertureCoordinate) * fraction,
                    shutterCoordinate = start.shutterCoordinate +
                        (end.shutterCoordinate - start.shutterCoordinate) * fraction,
                )
                invalidate()
            }
            start()
        }
    }

    private fun RecordedMeteringTarget.isDraggable(): Boolean = when (this) {
        RecordedMeteringTarget.APERTURE,
        RecordedMeteringTarget.SHUTTER,
        RecordedMeteringTarget.ZONE_RAIL,
        -> true
        else -> false
    }

    private fun categoryColumns() = AdaptiveLayout.columns(geometry.content.width(), 0f,
        10f * density, 120f * resources.displayMetrics.density, 2)

    private fun recordColumns() = AdaptiveLayout.columns(geometry.content.width(), 0f,
        6f * density, 72f * resources.displayMetrics.density, 3)

    private fun categoryAt(x: Float, y: Float): String? {
        val categories = repository.categories()
        val gap = 10f * density
        val columns = categoryColumns()
        val width = (geometry.content.width() - gap * (columns - 1)) / columns
        val height = width * 0.82f
        val column = ((x - geometry.content.left) / (width + gap)).toInt()
        val row = ((y - geometry.content.top + scroll) / (height + gap)).toInt()
        if (column !in 0 until columns || row < 0) return null
        val rect = RectF(
            geometry.content.left + column * (width + gap),
            geometry.content.top + row * (height + gap) - scroll,
            geometry.content.left + column * (width + gap) + width,
            geometry.content.top + row * (height + gap) - scroll + height,
        )
        return categories.getOrNull(row * columns + column)?.id?.takeIf { rect.contains(x, y) }
    }

    private fun recordAt(x: Float, y: Float): Int? {
        val records = repository.category(categoryId)?.records.orEmpty()
        val gap = 6f * density
        val columns = recordColumns()
        val width = (geometry.content.width() - gap * (columns - 1)) / columns
        val height = width * 0.76f
        val column = ((x - geometry.content.left) / (width + gap)).toInt()
        val row = ((y - geometry.content.top + scroll) / (height + gap)).toInt()
        if (column !in 0 until columns || row < 0) return null
        val rect = RectF(
            geometry.content.left + column * (width + gap),
            geometry.content.top + row * (height + gap) - scroll,
            geometry.content.left + column * (width + gap) + width,
            geometry.content.top + row * (height + gap) - scroll + height,
        )
        if (!rect.contains(x, y)) return null
        val index = row * columns + column
        return index.takeIf { it in records.indices }
    }

    private fun scheduleLongPress() {
        val id = touchedCategoryId ?: return
        val runnable = Runnable {
            longPressTriggered = true
            confirmDelete(id)
        }
        longPressRunnable = runnable
        handler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelPendingLongPress() {
        longPressRunnable?.let(handler::removeCallbacks)
        longPressRunnable = null
    }

    private fun confirmDelete(id: String?) {
        val target = id ?: return
        if (deletingCategoryId != null) return
        AlertDialog.Builder(context)
            .setTitle(localized("删除该类记录？", "Delete this category?"))
            .setMessage(localized("图片、RAW 和记录数据将被彻底删除，无法恢复。", "Images, RAW files, and record data will be permanently deleted."))
            .setNegativeButton(localized("取消", "Cancel"), null)
            .setPositiveButton(localized("删除", "Delete")) { _, _ ->
                deletingCategoryId = target
                Thread({
                    val deleted = repository.deleteCategory(target)
                    post {
                        deletingCategoryId = null
                        if (deleted) {
                            page = Page.CATEGORIES
                            categoryId = null
                            scroll = 0f
                            bitmapCache.evictAll()
                            listener?.onRepositoryChanged()
                            invalidate()
                        } else {
                            AlertDialog.Builder(context)
                                .setTitle(localized("未删除记录", "Record not deleted"))
                                .setMessage(
                                    localized(
                                        "安全校验未通过，文件保持原样。请勿手动移动记录目录中的文件。",
                                        "The safety check failed and all files were kept. Do not move files inside the record directory manually.",
                                    ),
                                )
                                .setPositiveButton(localized("确定", "OK"), null)
                                .show()
                        }
                    }
                }, "parameter-record-delete").start()
            }
            .show()
    }

    private fun centered(canvas: Canvas, text: String, x: Float, y: Float, textPaint: Paint) {
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    private fun localized(chinese: String, english: String): String =
        if (state.menuLanguage == MenuLanguage.ENGLISH) english else chinese

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.contentDescription = localized("过往记录", "History")
        if (page == Page.DETAIL) {
            detailPlayback?.let { info.contentDescription = "${info.contentDescription} · ${exposureSummary(it)}" }
            info.addAction(if (meteringExpanded) AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (page == Page.DETAIL && action in listOf(AccessibilityNodeInfo.ACTION_EXPAND, AccessibilityNodeInfo.ACTION_COLLAPSE)) {
            setMeteringExpanded(action == AccessibilityNodeInfo.ACTION_EXPAND)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onDetachedFromWindow() {
        cancelPendingLongPress()
        snapAnimator?.cancel()
        imageSlideAnimator?.cancel()
        drawerAnimator?.cancel()
        pageAnimator?.cancel()
        bitmapCache.evictAll()
        super.onDetachedFromWindow()
    }
}
