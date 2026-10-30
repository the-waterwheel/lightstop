package com.lightmeter.rawmeter

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.text.StaticLayout
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
    private val pointIo: ParameterRecordIoDispatcher = ParameterRecordIoDispatcher(Handler(Looper.getMainLooper()), "history-point-io"),
) : View(context) {

    interface Listener {
        fun onCloseRequested()
        fun onRepositoryChanged()
    }
    var listener: Listener? = null

    private enum class Page { CATEGORIES, CATEGORY, DETAIL }
    private enum class Target { BACK, DELETE, GRID, IMAGE, DATA, METERING, DRAWER, FRAME, POINTS, UNDO, NONE }

    private val density get() = layoutDensity(LayoutProfile.SCROLL)
    private val scaledDensity get() = layoutTextDensity(LayoutProfile.SCROLL)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.NORMAL) }
    private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = InstrumentStyle.labelTypeface }
    private val clipPath = Path()
    private val chrome = PanelChrome()
    private val background: Int get() = InstrumentStyle.background(state.isDarkMode)
    private val foreground: Int get() = InstrumentStyle.foreground(state.isDarkMode)
    private val muted: Int get() = InstrumentStyle.secondary(state.isDarkMode)
    private val panel: Int get() = InstrumentStyle.panel(state.isDarkMode)
    private val red get() = InstrumentStyle.accent(state.isDarkMode)
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
    private var suppressTouchUntilUp = false
    private var touchedCategoryId: String? = null
    private var touchedRecordIndex: Int? = null
    private var moved = false
    private var longPressTriggered = false
    private var longPressRunnable: Runnable? = null
    private var deletingCategoryId: String? = null
    private var detailWorkingRecord: ParameterRecordEntry? = null
    private var showFraming = true
    private var pointEditing = false
    private var savingPoints = false
    private val pointUndo = ArrayDeque<List<RecordedZonePoint>>()
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
        chrome.card(canvas, RectF(x-18*density,y-18*density,x+18*density,y+18*density), density, state.isDarkMode)
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
                chrome.card(canvas, RectF(geometry.delete.centerX()-24*density,y-13*density,
                    geometry.delete.centerX()+24*density,y+13*density),density,state.isDarkMode)
                paint.color = muted
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 9f * scaledDensity
                centered(canvas, "${detailIndex + 1}/$total", geometry.delete.centerX(), geometry.delete.centerY(), paint)
            }
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f * density
        paint.color = InstrumentStyle.hairline(state.isDarkMode)
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
        val image = RectF(rect.left + 6f * density, rect.top + 6f * density,
            rect.right - 6f * density, rect.top + rect.height() * 0.62f)
        drawImage(canvas, image, category.coverPath, viewfinder = category.records.lastOrNull()?.viewfinder)
        val badge = RectF(image.right-33*density,image.top+6*density,image.right-6*density,image.top+24*density)
        paint.style=Paint.Style.FILL;paint.color=Color.argb(190,20,20,20)
        canvas.drawRoundRect(badge,9*density,9*density,paint)
        bold.textSize=9*scaledDensity;bold.textAlign=Paint.Align.CENTER;bold.color=Color.WHITE
        centered(canvas,category.records.size.toString(),badge.centerX(),badge.centerY(),bold)
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.DEFAULT
        paint.color = muted
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 9f * scaledDensity
        val date = TextUtils.ellipsize(dateFormat.format(Date(category.startedAtEpochMs)),
            TextPaint(paint), rect.width() - 18f * density, TextUtils.TruncateAt.END)
        canvas.drawText(date.toString(), rect.left + 10f * density, rect.bottom - 10f * density, paint)
        paint.typeface = InstrumentStyle.labelTypeface
        paint.textSize = 11.5f * scaledDensity
        paint.color = foreground
        val label = category.filmSummary ?: localized("${category.records.size} 张记录", "${category.records.size} records")
        val fitted = TextUtils.ellipsize(label, TextPaint(paint), rect.width() - 18f * density, TextUtils.TruncateAt.END)
        canvas.drawText(fitted.toString(), rect.left + 10f * density, image.bottom + 17f * density, paint)
    }

    private fun drawCategory(canvas: Canvas) {
        val category = repository.category(categoryId) ?: return
        val gap = 6f * density
        val columns = recordColumns()
        val cellWidth = (geometry.content.width() - gap * (columns - 1)) / columns
        val cellHeight = recordCellHeight(cellWidth)
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
            drawPanel(canvas,rect)
            val image=RectF(rect.left+5*density,rect.top+5*density,rect.right-5*density,rect.bottom-40*density)
            drawImage(canvas, image, record.previewPath, viewfinder = record.viewfinder)
            bold.color=foreground;bold.textSize=11f*scaledDensity;bold.textAlign=Paint.Align.LEFT
            val exposure="${ExposureMath.formatAperture(ExposureMath.apertureValueForCoordinate(record.apertureCoordinate,state.apertureStep))}  ${ExposureMath.formatShutter(ExposureMath.shutterValueForCoordinate(record.shutterCoordinate,state.shutterStep))}"
            centered(canvas,TextUtils.ellipsize(exposure,bold,rect.width()-20*density,TextUtils.TruncateAt.END).toString(),rect.left+10*density,rect.bottom-26*density,bold)
            paint.style=Paint.Style.FILL;paint.color=muted;paint.typeface=Typeface.DEFAULT;paint.textSize=8.5f*scaledDensity
            paint.textAlign=Paint.Align.LEFT
            centered(canvas,record.capturedAtEpochMs?.let{timeFormat.format(Date(it))}?:"#${index+1}",rect.left+10*density,rect.bottom-11*density,paint)
            paint.textAlign=Paint.Align.RIGHT
            centered(canvas,"EI ${record.ei}",rect.right-10*density,rect.bottom-11*density,paint)
        }
        if (category.records.isEmpty()) drawEmpty(canvas)
        canvas.restore()
    }

    private fun drawDetail(canvas: Canvas) {
        val category = repository.category(categoryId) ?: return
        val record = detailWorkingRecord ?: category.records.getOrNull(detailIndex)?.also(::showDetailRecord) ?: return
        val playback = detailPlayback ?: RecordedMeteringSession.from(record).also { detailPlayback = it }
        drawDetailImages(canvas, category, record, playback.mode)
        drawImageActions(canvas, record)
        drawDetailText(canvas, geometry.data, record)
        drawMeteringDrawer(canvas, record, playback)
    }

    private fun imageActionRect(index: Int): RectF {
        val bounds = geometry.imageActions
        val cell = bounds.width() / 3
        return RectF(bounds.left + cell * index + 2f * density, bounds.top,
            bounds.left + cell * (index + 1) - 2f * density, bounds.bottom)
    }

    private fun drawImageActions(canvas: Canvas, record: ParameterRecordEntry) {
        val frame = record.viewfinder?.frameCrop
        val labels = listOf(if (record.viewfinder == null) localized("旧记录", "Legacy")
                else if (frame == null) localized("无画幅", "No frame") else localized(if (showFraming) "隐藏灰框" else "显示灰框", if (showFraming) "Hide frame" else "Show frame"),
            if (savingPoints) localized("保存中…", "Saving…") else if (pointEditing) localized("补点中", "Adding points") else localized("RAW 补点", "RAW points"), localized("撤销补点", "Undo point"))
        for (index in 0..2) {
            val rect = imageActionRect(index)
            val enabled = when (index) { 0 -> frame != null; 1 -> RecordedHistoryCapability.canRecalculateZone(record) && !savingPoints; else -> pointUndo.isNotEmpty() && !savingPoints }
            val selected=enabled&&(index==0&&showFraming||index==1&&pointEditing)
            chrome.card(canvas,rect,density,state.isDarkMode,selected)
            bold.textAlign = Paint.Align.CENTER; bold.textSize = 10f * scaledDensity
            bold.color = if (!enabled) muted else if(selected)InstrumentStyle.accent(state.isDarkMode) else foreground
            centered(canvas, labels[index], rect.centerX(), rect.centerY(), bold)
        }
    }

    private fun drawMeteringDrawer(canvas: Canvas, record: ParameterRecordEntry, playback: RecordedMeteringSession) {
        val drawer = geometry.meteringDrawer
        drawPanel(canvas, drawer)
        canvas.save()
        clipPath.reset();clipPath.addRoundRect(drawer,14*density,14*density,Path.Direction.CW)
        canvas.clipPath(clipPath)
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
        centered(canvas, localized("曝光参数", "Exposure"), handle.left + 16f * density,
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
        val bitmap = bitmap(record.previewPath) ?: run { if (updateInteractionBounds) imageDrawRect.setEmpty(); return }
        val drawnRect = RecordedPreviewRenderer.draw(canvas, target, bitmap, record.viewfinder, record.zonePoints, density, showFraming)
        if (updateInteractionBounds) imageDrawRect.set(drawnRect)
    }

    private fun drawDetailText(canvas: Canvas, rect: RectF, record: ParameterRecordEntry) {
        val viewportBottom = (geometry.meteringDrawer.top - 8f*density).coerceAtLeast(rect.top)
        val viewportHeight = (viewportBottom-rect.top).coerceAtLeast(0f)
        val left=rect.left+14*density
        val right=rect.right-14*density
        val valueLeft=left+62*density
        val rows=buildList<Pair<String,String>> {
            record.ev100?.let{add("EV100" to String.format(Locale.US,"%.2f",it))}
            add(localized("取景范围","Framing") to (record.viewfinder?.summary(state.menuLanguage)
                ?:localized("旧记录 · 原裁切画面","Legacy · Original saved crop")))
            record.viewfinder?.let { frame ->
                val source=when(frame.meteringSource){MeteringSource.RAW->"RAW";MeteringSource.YUV_PREVIEW->"YUV";MeteringSource.ISP_PREVIEW->"ISP";null->"—"}
                add(localized("测光方式","Metering") to (frame.meteringLabel(state.menuLanguage)+" · "+source))
                if(frame.frameCrop!=null)add(localized("变焦","Zoom") to
                    (localized("构图 ","Frame ")+String.format(Locale.US,"%.1f×",frame.framingZoom)+" · "+
                        localized("观察 ","Inspection ")+String.format(Locale.US,"%.1f×",frame.inspectionZoom)))
            }
            add("RAW" to if(RecordedHistoryCapability.canRecalculateZone(record))
                localized("可补点 · 已记录 ${record.zonePoints.size} 点","Editable · ${record.zonePoints.size} points")
                else localized("无法新增测点","Point editing unavailable"))
            record.filmName?.let{add(localized("胶片","Film") to it)}
            record.location?.let{add(localized("位置","Location") to formatLocation(it))}
            record.distance?.let{add(localized("距离","Distance") to formatRecordedDistance(it).substringAfter("  "))}
            record.flash?.let { flash ->
                val mode=if(flash.distanceMode==RecordedFlashDistanceMode.AUTO)localized("自动","Auto") else localized("手动","Manual")
                add(localized("闪光","Flash") to ("GN${String.format(Locale.US,"%.1f",flash.configuredGuideNumber)} (ISO ${flash.guideNumberReferenceIso}) · ISO ${flash.configuredIso} · "+
                    "${FlashPowerScale.label(flash.powerDenominator)} · "+localized("损失 ","Loss ")+String.format(Locale.US,"%.2f",flash.lossStops)+" · $mode"))
                add(localized("补偿","Adjustment") to formatRecordedFlashAdjustment(flash))
            }
        }
        val bodyPaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color=foreground;textSize=11.5f*scaledDensity;typeface=Typeface.DEFAULT
        }
        fun wrap(value:String,width:Float)=StaticLayout.Builder.obtain(value,0,value.length,bodyPaint,width.toInt().coerceAtLeast(1))
            .setIncludePad(false).setLineSpacing(3*density,1f).build()
        val values=rows.map{wrap(it.second,right-valueLeft)}
        val notes=record.notes.map{wrap(it,right-left-12*density)}
        val rowHeights=values.map{maxOf(29*density,it.height+14*density)}
        val contentHeight=100*density+rowHeights.sum()+if(notes.isEmpty())8*density else 34*density+notes.sumOf{it.height.toDouble()}.toFloat()+notes.size*20*density
        detailMaxScroll=(contentHeight-viewportHeight).coerceAtLeast(0f)
        detailScroll=detailScroll.coerceIn(0f,detailMaxScroll)
        val viewport=RectF(rect.left,rect.top,rect.right,viewportBottom)
        drawPanel(canvas,viewport)
        canvas.save()
        clipPath.reset();clipPath.addRoundRect(viewport,14*density,14*density,Path.Direction.CW)
        canvas.clipPath(clipPath)
        val top=rect.top-detailScroll
        fun label(value:String,x:Float,y:Float,size:Float,color:Int=foreground,medium:Boolean=false,width:Float=right-x,fit:Boolean=false) {
            paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.LEFT;paint.color=color
            paint.textSize=size*scaledDensity;paint.typeface=if(medium)InstrumentStyle.labelTypeface else Typeface.DEFAULT
            if(fit&&paint.measureText(value)>width)paint.textSize*=width.coerceAtLeast(1f)/paint.measureText(value)
            centered(canvas,TextUtils.ellipsize(value,TextPaint(paint),width.coerceAtLeast(1f),TextUtils.TruncateAt.END).toString(),x,y,paint)
        }
        label(localized("拍摄参数","Captured"),left,top+18*density,10f,muted,true,width=(right-left)*.35f)
        record.capturedAtEpochMs?.let {
            paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.RIGHT;paint.textSize=9f*scaledDensity;paint.color=muted;paint.typeface=Typeface.DEFAULT
            centered(canvas,dateFormat.format(Date(it)),right,top+18*density,paint)
        }
        val exposure=listOf(ExposureMath.formatAperture(ExposureMath.apertureValueForCoordinate(record.apertureCoordinate,state.apertureStep)),
            ExposureMath.formatShutter(ExposureMath.shutterValueForCoordinate(record.shutterCoordinate,state.shutterStep)),record.ei.toString())
        val captions=listOf(localized("光圈","Aperture"),localized("快门","Shutter"),"EI")
        val column=(right-left)/3
        for(i in 0..2) {
            val x=left+i*column
            label(captions[i],x,top+41*density,9f,muted,width=column-8*density)
            label(exposure[i],x,top+65*density,21f,foreground,true,width=column-8*density,fit=true)
            if(i>0) {
                paint.style=Paint.Style.STROKE;paint.strokeWidth=.6f*density;paint.color=InstrumentStyle.hairline(state.isDarkMode)
                canvas.drawLine(x-8*density,top+39*density,x-8*density,top+77*density,paint)
            }
        }
        paint.style=Paint.Style.STROKE;paint.strokeWidth=.7f*density;paint.color=InstrumentStyle.hairline(state.isDarkMode)
        canvas.drawLine(left,top+90*density,right,top+90*density,paint)
        var y=top+100*density
        rows.forEachIndexed { index,row ->
            label(row.first,left,y+7*density+bodyPaint.textSize/2,9.5f,muted,width=valueLeft-left-8*density)
            canvas.save();canvas.translate(valueLeft,y+7*density);values[index].draw(canvas);canvas.restore()
            y+=rowHeights[index]
        }
        if(notes.isNotEmpty()) {
            y+=10*density
            label(localized("备注","Notes"),left,y+7*density,10f,muted,true)
            y+=24*density
            notes.forEach { note ->
                paint.style=Paint.Style.FILL;paint.color=InstrumentStyle.accent(state.isDarkMode)
                canvas.drawCircle(left+2*density,y+7*density,1.8f*density,paint)
                canvas.save();canvas.translate(left+12*density,y);note.draw(canvas);canvas.restore()
                y+=note.height+20*density
            }
        }
        canvas.restore()
        if(detailMaxScroll>0&&viewportHeight>24*density) {
            val trackHeight=viewportHeight-20*density
            val thumbHeight=(trackHeight*viewportHeight/contentHeight).coerceIn(12*density,trackHeight)
            val thumbTop=viewport.top+10*density+(trackHeight-thumbHeight)*detailScroll/detailMaxScroll
            paint.style=Paint.Style.FILL;paint.color=InstrumentStyle.border(state.isDarkMode)
            canvas.drawRoundRect(RectF(viewport.right-5*density,thumbTop,viewport.right-3*density,thumbTop+thumbHeight),density,density,paint)
        }
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
        viewfinder: RecordedViewfinder? = null,
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
        val destination = RecordedPreviewRenderer.draw(canvas, target, bitmap, viewfinder, emptyList(), density)
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
        chrome.card(canvas,rect,density,state.isDarkMode)
    }

    private fun drawEmpty(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = muted
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 13f * scaledDensity
        centered(canvas, localized("暂无记录", "No records"), geometry.content.centerX(), geometry.content.centerY(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            suppressTouchUntilUp = page == Page.DETAIL && imageSlideAnimator?.isRunning == true
            if (suppressTouchUntilUp) { cancelPendingLongPress(); target = Target.NONE }
        }
        if (suppressTouchUntilUp) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                suppressTouchUntilUp = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pageAnimator?.end()
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
                // History is a full-screen overlay. Empty/disabled areas must not dispatch the
                // gesture to the live meter and its hold-to-record control underneath.
                return true
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
            if (imageActionRect(0).contains(x, y)) return Target.FRAME
            if (imageActionRect(1).contains(x, y)) return Target.POINTS
            if (imageActionRect(2).contains(x, y)) return Target.UNDO
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
            Target.FRAME -> if (detailWorkingRecord?.viewfinder?.frameCrop != null) { showFraming = !showFraming; invalidate() }
            Target.POINTS -> if (!savingPoints && detailWorkingRecord?.let(RecordedHistoryCapability::canRecalculateZone) == true) {
                pointEditing = !pointEditing
                if (pointEditing) { setPlaybackMode(ParameterRecordMode.ZONE); setMeteringExpanded(true) }
                invalidate()
            }
            Target.UNDO -> if (!savingPoints && pointUndo.isNotEmpty()) persistPoints(pointUndo.last(), undo = true)
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
        if (!pointEditing || savingPoints || !RecordedHistoryCapability.canRecalculateZone(record) || !imageDrawRect.contains(x, y)) return
        val normalizedX = ((x - imageDrawRect.left) / imageDrawRect.width()).coerceIn(0f, 1f)
        val normalizedY = ((y - imageDrawRect.top) / imageDrawRect.height()).coerceIn(0f, 1f)
        val points = RecordedPointEditing.toggle(record.zonePoints, normalizedX, normalizedY,
            18f * density / imageDrawRect.width(), 18f * density / imageDrawRect.height(), record::rawEv100At) ?: return
        persistPoints(points, undo = false)
    }

    private fun persistPoints(points: List<RecordedZonePoint>, undo: Boolean) {
        val before = detailWorkingRecord ?: return
        savingPoints = true
        val accepted = pointIo.submit(background = { runCatching { repository.updateZonePoints(before.categoryId, before.id, points) } }, onMain = { result ->
            savingPoints = false
            if (detailWorkingRecord?.id == before.id) {
                result.getOrNull()?.let { updated ->
                    if (undo) pointUndo.removeLastOrNull() else {
                        if (pointUndo.size >= 30) pointUndo.removeFirst()
                        pointUndo.addLast(before.zonePoints)
                    }
                    detailWorkingRecord = updated
                    detailPlayback = (detailPlayback ?: RecordedMeteringSession.from(updated)).copy(mode = ParameterRecordMode.ZONE)
                }
                if (result.isFailure) android.widget.Toast.makeText(context, localized("补点未保存，请重试", "Point was not saved. Try again."), android.widget.Toast.LENGTH_SHORT).show()
            }
            listener?.onRepositoryChanged()
            invalidate()
        })
        if (!accepted) savingPoints = false
        invalidate()
    }

    private fun showDetailRecord(record: ParameterRecordEntry) {
        snapAnimator?.cancel()
        detailImageOffset = 0f
        detailWorkingRecord = record
        showFraming = true
        pointEditing = false
        pointUndo.clear()
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
        10f * density, 120f * resources.displayMetrics.density, if(geometry.landscape)3 else 2)

    private fun recordColumns() = AdaptiveLayout.columns(geometry.content.width(), 0f,
        6f * density, 140f * resources.displayMetrics.density, if(geometry.landscape)4 else 2)

    private fun recordCellHeight(width:Float)=width*.67f+40f*density

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
        val height = recordCellHeight(width)
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
        AppDialogs.builder(context,state)
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
                            AppDialogs.builder(context,state)
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
            if (detailWorkingRecord?.viewfinder?.frameCrop != null) info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_FRAME, localized("切换灰框显示", "Toggle frame")))
            if (!savingPoints && detailWorkingRecord?.let(RecordedHistoryCapability::canRecalculateZone) == true)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_POINTS, localized("切换 RAW 补点", "Toggle RAW points")))
            if (!savingPoints && pointUndo.isNotEmpty()) info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_UNDO, localized("撤销补点", "Undo point")))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (page == Page.DETAIL && action in listOf(ACTION_FRAME, ACTION_POINTS, ACTION_UNDO)) {
            target = when (action) { ACTION_FRAME -> Target.FRAME; ACTION_POINTS -> Target.POINTS; else -> Target.UNDO }
            handleTap(0f, 0f)
            target = Target.NONE
            return true
        }
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

    private companion object {
        const val ACTION_FRAME = 0x01010001
        const val ACTION_POINTS = 0x01010002
        const val ACTION_UNDO = 0x01010003
    }
}
