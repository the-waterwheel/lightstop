package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.TextPaint
import android.util.LruCache
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
    private enum class Target { BACK, DELETE, GRID, IMAGE, DATA, METERING, NONE }

    private val density = resources.displayMetrics.density
    private val scaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.NORMAL) }
    private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.BOLD) }
    private val background: Int get() = if (state.isDarkMode) Color.BLACK else Color.WHITE
    private val foreground: Int get() = if (state.isDarkMode) Color.rgb(224, 224, 220) else Color.rgb(20, 20, 20)
    private val muted: Int get() = if (state.isDarkMode) Color.rgb(104, 104, 100) else Color.rgb(174, 174, 170)
    private val panel: Int get() = if (state.isDarkMode) Color.rgb(45, 45, 43) else Color.rgb(235, 235, 232)
    private val red = Color.rgb(201, 39, 46)
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val bitmapCache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val meteringRenderer = RecordedMeteringRenderer(density, state)
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
    private var detailScroll = 0f
    private var detailScrollStart = 0f
    private var detailMaxScroll = 0f
    private var imageDrawRect = RectF()

    fun open() {
        page = Page.CATEGORIES
        categoryId = null
        detailIndex = 0
        scroll = 0f
        detailWorkingRecord = null
        detailPlayback = null
        detailScroll = 0f
        invalidate()
    }

    fun navigateBack(): Boolean = when (page) {
        Page.DETAIL -> {
            page = Page.CATEGORY
            detailWorkingRecord = null
            detailPlayback = null
            detailScroll = 0f
            scroll = 0f
            invalidate()
            true
        }
        Page.CATEGORY -> {
            page = Page.CATEGORIES
            categoryId = null
            scroll = 0f
            invalidate()
            true
        }
        Page.CATEGORIES -> false
    }

    @Suppress("DEPRECATION")
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val safeTop = rootWindowInsets?.systemWindowInsetTop?.toFloat() ?: 0f
        geometry = ParameterHistoryGeometryCalculator.calculate(w, h, density, safeTop)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        drawHeader(canvas)
        when (page) {
            Page.CATEGORIES -> drawCategories(canvas)
            Page.CATEGORY -> drawCategory(canvas)
            Page.DETAIL -> drawDetail(canvas)
        }
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
            bold.textSize = 11f * scaledDensity
            bold.color = red
            centered(canvas, localized("删除", "Delete"), geometry.delete.centerX(), geometry.delete.centerY(), bold)
        }
    }

    private fun drawCategories(canvas: Canvas) {
        val categories = repository.categories()
        val gap = 10f * density
        val columns = 2
        val cellWidth = (geometry.content.width() - gap) / columns
        val cellHeight = cellWidth * 0.82f
        val rows = (categories.size + 1) / 2
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
        val image = RectF(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.68f)
        drawImage(canvas, image, category.coverPath)
        paint.color = foreground
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 9f * scaledDensity
        canvas.drawText(dateFormat.format(Date(category.startedAtEpochMs)), rect.left + 7f * density, image.bottom + 16f * density, paint)
        category.filmSummary?.let { film ->
            val fitted = TextUtils.ellipsize(film, TextPaint(paint), rect.width() - 14f * density, TextUtils.TruncateAt.END)
            canvas.drawText(fitted.toString(), rect.left + 7f * density, rect.bottom - 8f * density, paint)
        }
    }

    private fun drawCategory(canvas: Canvas) {
        val category = repository.category(categoryId) ?: return
        val gap = 6f * density
        val columns = 3
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
        drawImage(canvas, geometry.image, record.previewPath)
        if (playback.mode == ParameterRecordMode.ZONE) {
            drawRecordedPoints(canvas, imageDrawRect, record.zonePoints)
        }
        drawDetailText(canvas, geometry.data, record)
        meteringRenderer.draw(canvas, geometry.metering, record, playback)
    }

    private fun drawDetailText(canvas: Canvas, rect: RectF, record: ParameterRecordEntry) {
        val viewportBottom = geometry.metering.top - 5f * density
        val viewportHeight = (viewportBottom - rect.top).coerceAtLeast(0f)
        val lineHeight = 18f * density
        val noteRowHeight = 25f * density
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
        paint.color = foreground
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 10f * scaledDensity
        var y = rect.top + 14f * density - detailScroll
        val textLeft = rect.left + 5f * density
        val textRight = rect.right - 5f * density
        fun line(text: String, color: Int = foreground) {
            paint.color = color
            val fitted = TextUtils.ellipsize(text, TextPaint(paint), textRight - textLeft, TextUtils.TruncateAt.END)
            canvas.drawText(fitted.toString(), textLeft, y, paint)
            y += lineHeight
        }
        parameterLines.forEach(::line)
        if (record.notes.isNotEmpty()) {
            y += lineHeight
            line(localized("备注", "Notes"))
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = muted
            canvas.drawLine(textLeft, y - lineHeight * 0.48f, textRight, y - lineHeight * 0.48f, paint)
            record.notes.forEach { note ->
                paint.style = Paint.Style.FILL
                paint.color = foreground
                paint.textSize = 10f * scaledDensity
                val fitted = TextUtils.ellipsize(note, TextPaint(paint), textRight - textLeft, TextUtils.TruncateAt.END)
                centered(canvas, fitted.toString(), textLeft, y - lineHeight * 0.48f + noteRowHeight / 2f, paint)
                y += noteRowHeight
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f * density
                paint.color = muted
                canvas.drawLine(textLeft, y - lineHeight * 0.48f, textRight, y - lineHeight * 0.48f, paint)
            }
        }
        if (record.rawPath != null) {
            y += lineHeight
            line(
                localized("RAW 已保存 · 点击图片增减标点", "RAW saved · tap image to add/remove points"),
                red,
            )
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
        return location.accuracyMeters?.let { "$coordinates  ±${it.roundToInt()} m" } ?: coordinates
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

    private fun drawImage(canvas: Canvas, target: RectF, path: String?) {
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawRect(target, paint)
        val bitmap = path?.let(::bitmap) ?: return
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
        if (page == Page.DETAIL && target == geometry.image) imageDrawRect = destination
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
        paint.color = panel
        canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)
    }

    private fun drawEmpty(canvas: Canvas) {
        paint.color = muted
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 13f * scaledDensity
        centered(canvas, localized("暂无记录", "No records"), geometry.content.centerX(), geometry.content.centerY(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                scrollStart = scroll
                detailScrollStart = detailScroll
                moved = false
                longPressTriggered = false
                target = targetAt(event.x, event.y)
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
                if (target == Target.GRID && page != Page.DETAIL) {
                    scroll = (scrollStart - dy).coerceAtLeast(0f)
                    invalidate()
                } else if (target == Target.DATA && page == Page.DETAIL) {
                    detailScroll = (detailScrollStart - dy).coerceIn(0f, detailMaxScroll)
                    invalidate()
                } else if (target == Target.METERING && page == Page.DETAIL) {
                    if (meteringTarget.isDraggable()) detailStartPlayback?.let { start ->
                        detailPlayback = start.shifted(
                            -dx / meteringRenderer.pixelsPerStop(geometry.metering),
                            state,
                        )
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (!cancelled && !longPressTriggered) {
                    if (!moved) {
                        performClick()
                        handleTap(event.x, event.y)
                    } else handleSwipe(event.x - touchStartX)
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
            if (geometry.image.contains(x, y)) return Target.IMAGE
            if (geometry.metering.contains(x, y)) {
                meteringTarget = detailPlayback?.let { playback ->
                    meteringRenderer.targetAt(geometry.metering, playback.mode, x, y)
                } ?: RecordedMeteringTarget.NONE
                return if (meteringTarget == RecordedMeteringTarget.NONE) Target.NONE else Target.METERING
            }
            if (geometry.data.contains(x, y)) return Target.DATA
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
                    invalidate()
                }
            } else {
                touchedRecordIndex?.let {
                    detailIndex = it
                    repository.category(categoryId)?.records?.getOrNull(it)?.let(::showDetailRecord)
                    detailScroll = 0f
                    page = Page.DETAIL
                    invalidate()
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

    private fun handleSwipe(dx: Float) {
        if (page != Page.DETAIL || target != Target.IMAGE || abs(dx) < width * 0.12f) return
        val records = repository.category(categoryId)?.records.orEmpty()
        if (records.isEmpty()) return
        detailIndex = if (dx < 0f) (detailIndex + 1).coerceAtMost(records.lastIndex)
        else (detailIndex - 1).coerceAtLeast(0)
        showDetailRecord(records[detailIndex])
        detailScroll = 0f
        invalidate()
    }

    private fun editRawPoint(x: Float, y: Float) {
        val record = detailWorkingRecord ?: return
        val grid = record.rawGrid ?: return
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
            val relative = grid.relativeEvAt(normalizedX, normalizedY)
            val base = record.ev100
            record.zonePoints + RecordedZonePoint(
                id = (record.zonePoints.maxOfOrNull(RecordedZonePoint::id) ?: 0) + 1,
                normalizedX = normalizedX,
                normalizedY = normalizedY,
                ev100 = if (base != null && relative != null) base + relative else null,
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
        detailWorkingRecord = record
        detailPlayback = RecordedMeteringSession.from(record)
    }

    private fun setPlaybackMode(mode: ParameterRecordMode) {
        val playback = detailPlayback ?: return
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

    private fun categoryAt(x: Float, y: Float): String? {
        val categories = repository.categories()
        val gap = 10f * density
        val width = (geometry.content.width() - gap) / 2f
        val height = width * 0.82f
        val column = ((x - geometry.content.left) / (width + gap)).toInt()
        val row = ((y - geometry.content.top + scroll) / (height + gap)).toInt()
        if (column !in 0..1 || row < 0) return null
        val rect = RectF(
            geometry.content.left + column * (width + gap),
            geometry.content.top + row * (height + gap) - scroll,
            geometry.content.left + column * (width + gap) + width,
            geometry.content.top + row * (height + gap) - scroll + height,
        )
        return categories.getOrNull(row * 2 + column)?.id?.takeIf { rect.contains(x, y) }
    }

    private fun recordAt(x: Float, y: Float): Int? {
        val records = repository.category(categoryId)?.records.orEmpty()
        val gap = 6f * density
        val width = (geometry.content.width() - gap * 2f) / 3f
        val height = width * 0.76f
        val column = ((x - geometry.content.left) / (width + gap)).toInt()
        val row = ((y - geometry.content.top + scroll) / (height + gap)).toInt()
        if (column !in 0..2 || row < 0) return null
        val rect = RectF(
            geometry.content.left + column * (width + gap),
            geometry.content.top + row * (height + gap) - scroll,
            geometry.content.left + column * (width + gap) + width,
            geometry.content.top + row * (height + gap) - scroll + height,
        )
        if (!rect.contains(x, y)) return null
        val index = row * 3 + column
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

    override fun onDetachedFromWindow() {
        cancelPendingLongPress()
        snapAnimator?.cancel()
        bitmapCache.evictAll()
        super.onDetachedFromWindow()
    }
}
