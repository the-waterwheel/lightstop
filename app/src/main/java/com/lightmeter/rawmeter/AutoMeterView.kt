package com.lightmeter.rawmeter

import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeProvider
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class AutoMeterGeometry(
    val preview: RectF, val mode: RectF, val format: RectF, val orientation: RectF,
    val status: RectF, val parameters: RectF,
    val simulation: RectF, val raw: RectF, val zoom: RectF,
) {
    companion object {
        fun calculate(w: Int, h: Int, d: Float): AutoMeterGeometry {
            val width = w.toFloat(); val height = h.toFloat()
            fun r(l: Float, t: Float, right: Float, b: Float) = RectF(l, t, right, b)
            if (w > h) {
                val x = width - 226*d
                return AutoMeterGeometry(r(12*d,12*d,x-56*d,height-12*d),
                    r(x,12*d,width-12*d,46*d), r(x,58*d,width-60*d,94*d),
                    r(width-52*d,58*d,width-12*d,94*d),r(x,108*d,width-12*d,136*d),
                    r(x,150*d,width-12*d,252*d),
                    r(x,height-102*d,width-12*d,height-62*d),r(x,height-52*d,width-12*d,height-12*d),
                    r(x-48*d,40*d,x-10*d,height-40*d))
            }
            return AutoMeterGeometry(r(12*d,60*d,width-56*d,height-196*d),
                r(12*d,12*d,78*d,46*d),r(width-162*d,12*d,width-56*d,46*d),
                r(width-48*d,12*d,width-12*d,46*d),r(16*d,height-186*d,width-16*d,height-162*d),
                r(12*d,height-154*d,width-12*d,height-60*d),
                r(12*d,height-48*d,width*.48f,height-8*d),r(width*.51f,height-48*d,width-12*d,height-8*d),
                r(width-48*d,84*d,width-8*d,height-220*d))
        }
    }
}

/** Minimal full-screen camera overlay; framing and pinch use the same geometry as Normal/Zone. */
@SuppressLint("ViewConstructor") // Created only by MeterLayout with its owned session/state.
internal class AutoMeterView(context: Context, private val state: MeterState, val session: AutoMeterSession) : View(context) {
    var onExit: (() -> Unit)? = null
    var onControls: ((Boolean) -> Unit)? = null
    var onRaw: (() -> Unit)? = null
    var onResumeMetering: (() -> Unit)? = null
    var onOrientation: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zoomRenderer = ZoomControlRenderer()
    private var zooming = false
    private var down = -1
    private var parameterDrag = -1
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragLastY = 0f
    private var dragRemainder = 0f
    private var dragMoved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val reels = List(3) { RollingParameterWindow() }
    private val reelRect = RectF()
    private val captionRect = RectF()
    private val reelInvalidation: ()->Unit = { postInvalidateOnAnimation() }
    private val reelFormats = List<(Double)->String>(3) { index -> { value -> formatParameter(index,value) } }
    private val focalLabelRect = RectF()
    private var cachedGeometry: AutoMeterGeometry? = null
    private var cachedNodes = emptyList<CanvasAccessibilityHelper.VirtualNode>()
    private var nodeLanguage: MenuLanguage? = null
    private val pinch = ViewfinderPinch(context) { factor ->
        state.previewZoom *= factor; changed(false,framing=state.isUnframed)
    }
    private val accessibility = CanvasAccessibilityHelper(this) { action -> activate(action); true }
    private val d get() = min(resources.displayMetrics.density, min(width,height).coerceAtLeast(1) / 380f)
    internal fun geometry(): AutoMeterGeometry = cachedGeometry ?: AutoMeterGeometry.calculate(width,height,d).also { cachedGeometry=it }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        cachedGeometry=null
        cachedNodes=emptyList()
    }
    fun previewFrame(w: Int, h: Int) = AutoMeterGeometry.calculate(w,h,
        min(resources.displayMetrics.density,min(w,h).coerceAtLeast(1)/380f)).preview
    private val ink get() = InstrumentStyle.ink.takeUnless { state.isDarkMode } ?: InstrumentStyle.nightInk
    private val surface get() = InstrumentStyle.background(state.isDarkMode)
    private fun text(canvas: Canvas, label: String, rect: RectF, size: Float, color: Int = ink, bold: Boolean = false) {
        paint.style = Paint.Style.FILL; paint.color = color
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textSize = size*d
        val measured = paint.measureText(label)
        if (measured > rect.width()-8*d) paint.textSize *= (rect.width()-8*d).coerceAtLeast(1f)/measured
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label,rect.centerX(),rect.centerY()-(paint.ascent()+paint.descent())/2,paint)
    }
    private fun button(canvas: Canvas, label: String, rect: RectF, active: Boolean = false) {
        paint.style = Paint.Style.FILL; paint.color = if (active) ink else InstrumentStyle.panel(state.isDarkMode)
        canvas.drawRoundRect(rect,9*d,9*d,paint)
        text(canvas,label,rect,12f,if(active) surface else ink,true)
    }
    override fun onDraw(canvas: Canvas) {
        val g = geometry()
        state.viewfinderAspect = g.preview.width()/g.preview.height().coerceAtLeast(1f)
        ViewfinderOverlay.outside(canvas,g.preview,width,height,surface,d)
        if (!state.isUnframed) ViewfinderOverlay.framing(canvas,g.preview,state.displayedFramingCrop(width>height),d)
        button(canvas,l("模式", "Modes"),g.mode)
        button(canvas,state.frameFormat.displayLabel(state.menuLanguage),g.format)
        button(canvas,"↻",g.orientation)
        val status = when {
            session.rawPending -> l("RAW 测光中…", "Measuring RAW…")
            session.locked -> l("RAW · 已锁定", "RAW · LOCKED")
            session.recoveryCameraEv100 != null -> l("正在恢复测光信号", "Recovering metering signal")
            session.signalLimited -> l("预览信号受限 · 可用 RAW 锁定", "Limited preview signal · use RAW lock")
            else -> l("自动 · 全局测光", "AUTO · GLOBAL")
        }
        val ev = session.reading?.sceneEv100?.let { "EV100 ${"%.1f".format(it)}" } ?: "EV100 —"
        val source = session.reading?.source?.let { if(it==MeteringSource.YUV_PREVIEW) "YUV" else if(it==MeteringSource.RAW) "RAW" else "ISP" } ?: "…"
        text(canvas,session.error ?: "$status   ·   $source   ·   $ev",g.status,11f,InstrumentStyle.secondary(state.isDarkMode))
        val values = AutoMeterControls.coordinates(state,session)
        val captions = listOf(l("EV 曝光补偿", "EV COMP"),l("光圈", "APERTURE"),l("快门", "SHUTTER"))
        for(index in 0..2) {
            val rect = parameterRect(g,index)
            paint.style=Paint.Style.FILL; paint.color=InstrumentStyle.panel(state.isDarkMode)
            canvas.drawRoundRect(rect,10*d,10*d,paint)
            captionRect.set(rect.left,rect.top+4*d,rect.right,rect.top+15*d)
            text(canvas,captions[index],captionRect,8f,InstrumentStyle.secondary(state.isDarkMode))
            reelRect.set(rect.left+2*d,rect.top+18*d,rect.right-2*d,rect.bottom-8*d)
            reels[index].draw(canvas,reelRect,values[index],AutoMeterControls.interval(state,index),d,ink,reelFormats[index],reelInvalidation)
            if ((index==1 && state.exposureLockMode==ExposureLockMode.APERTURE) ||
                (index==2 && state.exposureLockMode==ExposureLockMode.SHUTTER)) {
                paint.color=InstrumentStyle.red
                canvas.drawCircle(rect.centerX(),rect.bottom-4*d,1.6f*d,paint)
            }
        }
        button(canvas,l("曝光模拟", "Simulation")+if(session.simulation) " ON" else " OFF",g.simulation,session.simulation)
        button(canvas,when { session.rawPending -> l("测光中…", "Measuring…");session.locked -> l("继续测光", "Resume metering");else -> l("RAW 锁定", "RAW lock") },g.raw,true)
        val fraction = ((state.zoomControlValue-1)/(state.zoomControlMaximum-1)).coerceIn(0f,1f)
        zoomRenderer.draw(canvas,true,g.zoom.centerX(),g.zoom.top+24*d,g.zoom.bottom-24*d,
            g.zoom.bottom-24*d-fraction*(g.zoom.height()-48*d),d,state.isDarkMode)
        focalLabelRect.set(g.zoom.left,g.zoom.top-24*d,g.zoom.right+4*d,g.zoom.top)
        text(canvas,if(state.isUnframed) "${"%.1f".format(state.previewZoom)}×" else state.equivalentFrameFocalMm()?.let { "${it}mm" } ?: "—",focalLabelRect,10f)
        if(cachedNodes.isEmpty() || nodeLanguage!=state.menuLanguage) rebuildAccessibility(g)
        accessibility.update(cachedNodes)
    }
    private fun rebuildAccessibility(g: AutoMeterGeometry) {
        nodeLanguage=state.menuLanguage
        cachedNodes=targets(g).mapIndexed { index,rect ->
            CanvasAccessibilityHelper.VirtualNode(index,rect,listOf(l("返回 Normal", "Return to Normal"),l("画幅", "Format"),l("横竖屏", "Orientation"),
                l("曝光补偿", "Exposure compensation"),l("光圈", "Aperture"),l("快门", "Shutter"),l("曝光模拟", "Simulation"),l("RAW 锁定或继续测光", "RAW lock or resume"))[index])
        }
    }
    private fun parameterRect(g: AutoMeterGeometry,index: Int) = RectF(g.parameters.left+g.parameters.width()*index/3+3*d,
        g.parameters.top,g.parameters.left+g.parameters.width()*(index+1)/3-3*d,g.parameters.bottom)
    private fun targets(g: AutoMeterGeometry) = listOf(g.mode,g.format,g.orientation,
        parameterRect(g,0),parameterRect(g,1),parameterRect(g,2),g.simulation,g.raw)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = geometry()
        if (pinch.consume(event,g.preview,!session.rawPending)) { zooming=false; down=-1; parameterDrag=-1; return true }
        if(event.pointerCount>1) { zooming=false;down=-1;parameterDrag=-1;return true }
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zooming=g.zoom.contains(event.x,event.y)
                down=targets(g).indexOfFirst { it.contains(event.x,event.y) }
                parameterDrag=if(down in 3..5 && !session.rawPending) down-3 else -1
                dragStartX=event.x;dragStartY=event.y;dragLastY=event.y;dragRemainder=0f;dragMoved=false
                if(zooming) zoom(event.y,g)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(zooming) zoom(event.y,g)
                if(parameterDrag>=0) {
                    if(!dragMoved && abs(event.x-dragStartX)>touchSlop && abs(event.x-dragStartX)>abs(event.y-dragStartY)) {
                        parameterDrag=-1;down=-1;return true
                    }
                    if(dragMoved || abs(event.y-dragStartY)>touchSlop) {
                        dragMoved=true
                        dragRemainder+=(dragLastY-event.y)/(24*d)
                        dragLastY=event.y
                        val detents=dragRemainder.toInt()
                        if(detents!=0) {
                            AutoMeterControls.adjust(state,session,parameterDrag,detents)
                            dragRemainder-=detents
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            changed(false)
                        }
                    }
                } else if(down>=0 && !targets(g)[down].contains(event.x,event.y)) down=-1
                return true
            }
            MotionEvent.ACTION_UP -> {
                if(!zooming && !dragMoved && down>=0) activate(down)
                zooming=false;down=-1;parameterDrag=-1;performClick();return true
            }
            MotionEvent.ACTION_CANCEL -> { zooming=false;down=-1;parameterDrag=-1;return true }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility.provider
    private fun zoom(y: Float,g: AutoMeterGeometry) {
        if(session.rawPending) return
        val fraction=((g.zoom.bottom-24*d-y)/(g.zoom.height()-48*d)).coerceIn(0f,1f)
        state.setZoomControl(((1+fraction*(state.zoomControlMaximum-1))*10).roundToInt()/10f)
        changed(false,framing=true)
    }
    private fun activate(action: Int) {
        if(session.rawPending) return
        when(action) {
            0 -> onExit?.invoke()
            1 -> {
                val labels = FrameFormat.ALL.map { it.displayLabel(state.menuLanguage) }+
                    l(if(state.frameLandscape) "竖拍构图" else "横拍构图",if(state.frameLandscape) "Portrait frame" else "Landscape frame")
                choose(l("画幅", "Format"),labels,state.frameIndex) { index ->
                    if(index==FrameFormat.ALL.size) state.toggleFrameOrientation() else state.selectFrame(index)
                    changed(true,framing=true)
                }
            }
            2 -> onOrientation?.invoke()
            3 -> {
                val values=(-30..30 step state.exposureCompensationStep.sixthStops).toList()
                choose(l("曝光补偿", "Exposure compensation"),values.map { formatParameter(0,it/6.0)+" EV" },values.indexOf(state.exposureCompSteps)) {
                    state.exposureCompSteps=values[it]; changed(false)
                }
            }
            4 -> {
                val stops=ExposureMath.apertureStops(state.apertureStep)
                choose(l("固定光圈", "Fixed aperture"),stops.map { ExposureMath.formatAperture(ExposureMath.apertureValueForCoordinate(it,state.apertureStep)) },-1) {
                    state.setExposureLockModeFromCoordinates(ExposureLockMode.APERTURE,stops[it],state.lockedShutterLogSeconds)
                    state.setLockedExposureCoordinate(stops[it]); changed(false)
                }
            }
            5 -> {
                val stops=ExposureMath.shutterStops(state.shutterStep)
                choose(l("固定快门", "Fixed shutter"),stops.map { ExposureMath.formatShutter(ExposureMath.shutterValueForCoordinate(it,state.shutterStep)) },-1) {
                    state.setExposureLockModeFromCoordinates(ExposureLockMode.SHUTTER,state.lockedApertureStop,stops[it])
                    state.setLockedExposureCoordinate(stops[it]); changed(false)
                }
            }
            6 -> { session.simulation=!session.simulation; changed(false) }
            7 -> if(session.locked) onResumeMetering?.invoke() else onRaw?.invoke()
        }
    }
    private fun formatParameter(column: Int,value: Double): String = when(column) {
        0 -> if(abs(value)>5.00001) "—" else {
            val normalized=if(abs(value)<.00001) 0.0 else value
            (if(state.exposureCompensationStep.sixthStops<=2) "%+.2f" else "%+.1f").format(normalized)
        }
        1 -> ExposureMath.formatAperture(ExposureMath.apertureValueForCoordinate(value,state.apertureStep))
        else -> ExposureMath.formatShutter(ExposureMath.shutterValueForCoordinate(-value,state.shutterStep))
    }
    private fun choose(title: String,items: List<String>,selected: Int,accept: (Int)->Unit) {
        AlertDialog.Builder(context).setTitle(title).setSingleChoiceItems(items.toTypedArray(),selected) { dialog,index ->
            dialog.dismiss(); if(!session.rawPending && visibility==VISIBLE) accept(index)
        }.setNegativeButton(l("取消", "Cancel"),null).show()
    }
    private fun changed(frame: Boolean,framing: Boolean = false) {
        if(framing) { session.unlock(clear=true); onResumeMetering?.invoke() }
        onControls?.invoke(frame); invalidate()
    }
    private fun l(zh: String,en: String) = if(state.menuLanguage==MenuLanguage.ENGLISH) en else zh
}
