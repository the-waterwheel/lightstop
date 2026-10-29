package com.lightmeter.rawmeter

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.CheckBox
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Locale

internal data class AutoMeterGeometry(
    val preview:RectF,val mode:RectF,val format:RectF,val orientation:RectF,
    val status:RectF,val parameters:RectF,val simulation:RectF,val raw:RectF,val zoom:RectF,
) {
    val settingsBar:RectF get()=simulation
    companion object {
        fun calculate(w:Int,h:Int,d:Float,leftHanded:Boolean=false):AutoMeterGeometry {
            val width=w.toFloat();val height=h.toFloat()
            fun r(l:Float,t:Float,right:Float,b:Float)=RectF(l,t,right,b)
            if(w>h) {
                val x=width-226*d
                val geometry=AutoMeterGeometry(r(12*d,12*d,x-56*d,height-12*d),
                    r(x,12*d,width-12*d,46*d),r(x,58*d,width-60*d,94*d),
                    r(width-52*d,58*d,width-12*d,94*d),r(x,108*d,width-12*d,136*d),
                    r(x,150*d,width-12*d,252*d),r(x,height-102*d,width-12*d,height-68*d),
                    r(x,height-52*d,width-12*d,height-12*d),r(x-48*d,40*d,x-10*d,height-40*d))
                if(!leftHanded)return geometry
                fun mirror(rect:RectF)=r(width-rect.right,rect.top,width-rect.left,rect.bottom)
                return AutoMeterGeometry(mirror(geometry.preview),mirror(geometry.mode),mirror(geometry.format),mirror(geometry.orientation),
                    mirror(geometry.status),mirror(geometry.parameters),mirror(geometry.simulation),mirror(geometry.raw),mirror(geometry.zoom))
            }
            return AutoMeterGeometry(r(12*d,56*d,width-12*d,height-254*d),
                r(12*d,12*d,112*d,46*d),r(width-162*d,12*d,width-56*d,46*d),
                r(width-48*d,12*d,width-12*d,46*d),r(16*d,height-218*d,width-16*d,height-198*d),
                r(12*d,height-156*d,width-12*d,height-60*d),r(12*d,height-192*d,width-12*d,height-162*d),
                r(12*d,height-50*d,width-12*d,height-10*d),r(24*d,height-248*d,width-96*d,height-222*d))
        }
    }
}

/** Clear's native overlay. Inline settings never resize or restart the camera preview. */
@SuppressLint("ViewConstructor")
internal class AutoMeterView(context:Context,private val state:MeterState,val session:AutoMeterSession,
    private val preferences:ClearModePreferences=ClearModePreferences(context)):View(context) {
    var onExit:(()->Unit)?=null
    var onControls:((Boolean)->Unit)?=null
    var onRaw:(()->Unit)?=null
    var onResumeMetering:(()->Unit)?=null
    var onOrientation:(()->Unit)?=null
    var onOptionsChanged:(()->Unit)?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val zoomRenderer=ZoomControlRenderer()
    private val viewfinderControls=ViewfinderControls(state)
    internal var formatMenuOpen=false
        private set
    private val arrow=ModeGuideArrow()
    private val reels=List(3){RollingParameterWindow()}
    private val reelFormats=List<(Double)->String>(3){index->{value->formatParameter(index,value)}}
    private val animationInvalidation:()->Unit={postInvalidateOnAnimation()}
    private val scratch=RectF()
    private var cachedGeometry:AutoMeterGeometry?=null
    private var cachedLeftHanded=false
    private val d get()=min(resources.displayMetrics.density,min(width,height).coerceAtLeast(1)/380f)
    private val ink get()=InstrumentStyle.foreground(state.isDarkMode)
    private val surface get()=InstrumentStyle.background(state.isDarkMode)
    private val touchSlop=ViewConfiguration.get(context).scaledTouchSlop
    private var down=-1
    private var downX=0f
    private var downY=0f
    private var lastY=0f
    private var remainder=0f
    private var moved=false
    private var zooming=false
    private var normalHeld=false
    private var normalDistance=0f
    private var arrowUntil=0L
    private var settingsTouch=false
    private var settingTarget=-1
    private var dismissGesture=false
    internal var settingsExpanded=false
        private set
    private var settingsFraction=0f
    private var settingsAnimator:ValueAnimator?=null
    private val accessibility=CanvasAccessibilityHelper(this){action->activate(action);true}
    private val pinch=ViewfinderPinch(context){factor->
        state.previewZoom*=factor;changed(false,framing=state.isUnframed)
    }
    internal fun geometry():AutoMeterGeometry {
        if(cachedLeftHanded!=state.isLeftHanded){cachedGeometry=null;cachedLeftHanded=state.isLeftHanded}
        return cachedGeometry?:AutoMeterGeometry.calculate(width,height,d,state.isLeftHanded).also{cachedGeometry=it}
    }
    fun previewFrame(w:Int,h:Int)=AutoMeterGeometry.calculate(w,h,min(resources.displayMetrics.density,min(w,h).coerceAtLeast(1)/380f),state.isLeftHanded).preview
    internal fun normalHandle():RectF {
        val frame=geometry().preview
        if(width>height) {
            val left=if(state.isLeftHanded)frame.left+4*d else frame.right-26*d
            return RectF(left,height/2f-38*d,left+22*d,height/2f+38*d)
        }
        return RectF(frame.centerX()-34*d,frame.bottom-26*d,frame.centerX()+34*d,frame.bottom-4*d)
    }
    internal fun settingsPanel():RectF {
        val g=geometry()
        return RectF(g.parameters.left,if(width>height)g.status.top else g.settingsBar.top-185*d,
            g.parameters.right,g.settingsBar.top-5*d)
    }
    internal fun settingRow(index:Int):RectF {
        val panel=settingsPanel();val row=panel.height()/5
        return RectF(panel.left,panel.top+index*row,panel.right,panel.top+(index+1)*row)
    }
    internal fun formatOptionRects():List<RectF> {
        val bounds=RectF(geometry().preview).apply{inset(8*d,8*d)}
        return InstrumentPresentation.formatOptionRects(bounds,d,34*d)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);cachedGeometry=null}
    private fun text(canvas:Canvas,label:String,rect:RectF,size:Float,color:Int=ink,bold:Boolean=false) {
        paint.style=Paint.Style.FILL;paint.color=color;paint.textAlign=Paint.Align.CENTER
        paint.typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT;paint.textSize=size*d
        val measured=paint.measureText(label)
        if(measured>rect.width()-8*d)paint.textSize*=(rect.width()-8*d).coerceAtLeast(1f)/measured
        canvas.drawText(label,rect.centerX(),rect.centerY()-(paint.ascent()+paint.descent())/2,paint)
    }
    private fun button(canvas:Canvas,label:String,rect:RectF,active:Boolean=false) {
        paint.style=Paint.Style.FILL;paint.color=if(active)ink else InstrumentStyle.panel(state.isDarkMode)
        canvas.drawRoundRect(rect,9*d,9*d,paint)
        text(canvas,label,rect,12f,if(active)surface else ink,true)
    }
    override fun onDraw(canvas:Canvas) {
        val g=geometry();state.viewfinderAspect=g.preview.width()/g.preview.height().coerceAtLeast(1f)
        ViewfinderOverlay.outside(canvas,g.preview,width,height,surface,d)
        if(!state.isUnframed)ViewfinderOverlay.framing(canvas,g.preview,state.displayedFramingCrop(width>height),d)
        if(session.showInterfaceText)text(canvas,"clear",g.mode,16f,bold=true)
        viewfinderControls.format(canvas,g.format,d,formatMenuOpen,label=if(session.showInterfaceText)InstrumentPresentation.formatShortLabel(state.frameFormat,state.menuLanguage) else state.frameFormat.id)
        viewfinderControls.orientation(canvas,g.orientation,d,width>height)
        if(session.showInterfaceText) {
            val status=when {
                session.rawPending->l("手动测光中…","Measuring…")
                session.locked->l("测光锁定 · 全局","LOCKED · GLOBAL")
                session.recoveryCameraEv100!=null->l("正在恢复测光信号","Recovering signal")
                session.signalLimited->l("预览信号受限","Limited preview signal")
                !session.continuousMetering->l("手动 · 全局","MANUAL · GLOBAL")
                else->"clear · "+l("持续全局测光","LIVE GLOBAL")
            }
            val source=session.reading?.source?.let {if(it==MeteringSource.YUV_PREVIEW)"YUV" else if(it==MeteringSource.RAW)"RAW" else "ISP"}?:"…"
            val ev=session.reading?.sceneEv100?.let{"EV100 ${String.format(Locale.US,"%.1f",it)}"}?:"EV100 —"
            text(canvas,session.error?:"$status · $source · $ev",g.status,10f,InstrumentStyle.secondary(state.isDarkMode))
        }
        val values=AutoMeterControls.coordinates(state,session)
        val captions=if(session.showInterfaceText)listOf(l("EV 曝光补偿","EV COMP"),l("光圈","APERTURE"),l("快门","SHUTTER")) else listOf("EV","f","s")
        for(index in 0..2) {
            val rect=parameterRect(index);paint.style=Paint.Style.FILL;paint.color=InstrumentStyle.panel(state.isDarkMode)
            canvas.drawRoundRect(rect,10*d,10*d,paint)
            scratch.set(rect.left,rect.top+4*d,rect.right,rect.top+15*d)
            text(canvas,captions[index],scratch,8f,InstrumentStyle.secondary(state.isDarkMode))
            scratch.set(rect.left+2*d,rect.top+18*d,rect.right-2*d,rect.bottom-8*d)
            // A shutter reel has discrete labelled positions. Keep the live EV precise, but
            // anchor all three labels to the same nominal stops used by Normal and Zone.
            val displayValue=values[index]?.let {if(index==2)-ExposureMath.nearestShutterLogSeconds(-it,state.shutterStep) else it}
            reels[index].draw(canvas,scratch,displayValue,AutoMeterControls.interval(state,index),d,ink,reelFormats[index],animationInvalidation)
            if(index==1&&state.exposureLockMode==ExposureLockMode.APERTURE || index==2&&state.exposureLockMode==ExposureLockMode.SHUTTER) {
                paint.color=InstrumentStyle.red;canvas.drawCircle(rect.centerX(),rect.bottom-4*d,1.6f*d,paint)
            }
        }
        button(canvas,if(session.showInterfaceText)l("参数调节","Adjustments") else "☷",g.settingsBar)
        scratch.set(g.settingsBar.right-30*d,g.settingsBar.top,g.settingsBar.right-6*d,g.settingsBar.bottom)
        text(canvas,if(settingsExpanded)"⌃" else "⌄",scratch,14f)
        button(canvas,if(session.showInterfaceText)manualLabel() else "",g.raw,true)
        if(session.locked){paint.style=Paint.Style.STROKE;paint.color=InstrumentStyle.red;paint.strokeWidth=2*d;canvas.drawRoundRect(g.raw,9*d,9*d,paint)}
        val fraction=((state.zoomControlValue-1)/(state.zoomControlMaximum-1)).coerceIn(0f,1f)
        val landscape=width>height
        val start=if(landscape)g.zoom.top+24*d else g.zoom.left+12*d
        val end=if(landscape)g.zoom.bottom-24*d else g.zoom.right-12*d
        zoomRenderer.draw(canvas,landscape,if(landscape)g.zoom.centerX() else g.zoom.centerY(),start,end,
            if(landscape)end-fraction*(end-start) else start+fraction*(end-start),d,state.isDarkMode)
        if(landscape)scratch.set(g.zoom.left,g.zoom.top-24*d,g.zoom.right+4*d,g.zoom.top)
        else scratch.set(g.zoom.right+6*d,g.zoom.top,width-12*d,g.zoom.bottom)
        text(canvas,if(state.isUnframed)"${"%.1f".format(state.previewZoom)}×" else state.equivalentFrameFocalMm()?.let{"${it}mm"}?:"—",scratch,10f)
        val handle=normalHandle().apply{
            val travel=normalDistance.coerceIn(0f,70*d)
            if(landscape)offset(ModeTransitionDirection.zoneEntrySign(state.isLeftHanded)*travel,0f) else offset(0f,-travel)
        }
        paint.style=Paint.Style.FILL;paint.color=surface;canvas.drawRoundRect(handle,4*d,4*d,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.2f*d;paint.color=ink;canvas.drawRoundRect(handle,4*d,4*d,paint)
        if(landscape){canvas.save();canvas.rotate(-90f,handle.centerX(),handle.centerY());scratch.set(handle.centerX()-handle.height()/2,handle.centerY()-handle.width()/2,handle.centerX()+handle.height()/2,handle.centerY()+handle.width()/2);text(canvas,"normal",scratch,8.5f,bold=true);canvas.restore()}
        else text(canvas,"normal",handle,8.5f,bold=true)
        if(normalHeld || SystemClock.uptimeMillis()<arrowUntil) {
            if(landscape){val sign=ModeTransitionDirection.zoneEntrySign(state.isLeftHanded);arrow.draw(canvas,handle.centerX()+sign*(handle.width()/2+20*d),handle.centerY(),d,sign*90f)}
            else arrow.draw(canvas,handle.centerX(),handle.top-20*d,d)
        }
        if(settingsFraction>0f)drawSettings(canvas)
        if(formatMenuOpen)viewfinderControls.options(canvas,formatOptionRects(),d)
        accessibility.update(accessibilityNodes())
    }
    private fun drawSettings(canvas:Canvas) {
        val panel=settingsPanel()
        canvas.save();canvas.clipRect(panel.left,panel.bottom-panel.height()*settingsFraction,panel.right,panel.bottom)
        paint.style=Paint.Style.FILL;paint.color=InstrumentStyle.control(state.isDarkMode)
        canvas.drawRoundRect(panel,10*d,10*d,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=d;paint.color=InstrumentStyle.border(state.isDarkMode)
        canvas.drawRoundRect(panel,10*d,10*d,paint)
        val labels=listOf(l("曝光模拟","Exposure simulation"),l("自动持续测光","Continuous metering"),l("自动测光频率","Metering frequency"),
            l("界面文字显示","Interface text"),l("平滑测光","Smooth metering"))
        for(index in 0..4) {
            val row=settingRow(index)
            val enabled=!session.rawPending&&(index!=2||session.continuousMetering)
            val color=if(enabled)ink else InstrumentStyle.secondary(state.isDarkMode)
            val labelWidth=if(index==2)row.width()*.58f else row.width()*.7f
            scratch.set(row.left+6*d,row.top,row.left+labelWidth,row.bottom)
            text(canvas,labels[index],scratch,10.5f,color)
            scratch.set(row.left+labelWidth,row.top,row.right-6*d,row.bottom)
            if(index==2)text(canvas,"−  ${frequencyLabel()}  +",scratch,10f,color,true)
            else {
                val active=when(index){0->session.simulation;1->session.continuousMetering;3->session.showInterfaceText;else->session.smoothMetering}
                text(canvas,if(active)l("开启","ON") else l("关闭","OFF"),scratch,10f,if(enabled&&active)InstrumentStyle.red else color,true)
            }
        }
        canvas.restore()
    }
    private fun accessibilityNodes():List<CanvasAccessibilityHelper.VirtualNode> {
        val exitLabel=if(width<=height)l("返回 normal，向上滑动","Return to normal; swipe up") else if(state.isLeftHanded)l("返回 normal，向右滑动","Return to normal; swipe right") else l("返回 normal，向左滑动","Return to normal; swipe left")
        val labels=listOf(exitLabel,l("画幅","Format"),l("横竖屏","Orientation"),
            l("曝光补偿","Exposure compensation"),l("光圈","Aperture"),l("快门","Shutter"),l("参数调节","Adjustments"),manualLabel())
        val panel=if(settingsExpanded)settingsPanel() else null
        val controls=targets().mapIndexedNotNull {index,rect->
            if(panel!=null&&RectF.intersects(panel,rect)) null
            else CanvasAccessibilityHelper.VirtualNode(index,rect,labels[index],enabled=!session.rawPending||index==6||index==7&&session.locked)
        }
        val formats=if(formatMenuOpen)formatOptionRects().mapIndexed{index,rect->CanvasAccessibilityHelper.VirtualNode(40+index,rect,viewfinderControls.label(index),enabled=!session.rawPending)} else emptyList()
        return formats+(if(settingsExpanded)(0..4).map {index->CanvasAccessibilityHelper.VirtualNode(20+index,settingRow(index),
                listOf(l("曝光模拟","Exposure simulation"),l("自动持续测光","Continuous metering"),l("测光频率","Frequency"),l("界面文字","Interface text"),l("平滑测光","Smooth metering"))[index],
                enabled=!session.rawPending&&(index!=2||session.continuousMetering))} else emptyList())+controls
    }
    private fun parameterRect(index:Int):RectF {
        val r=geometry().parameters
        return RectF(r.left+r.width()*index/3+3*d,r.top,r.left+r.width()*(index+1)/3-3*d,r.bottom)
    }
    private fun targets():List<RectF> {
        val g=geometry();return listOf(normalHandle(),g.format,g.orientation,parameterRect(0),parameterRect(1),parameterRect(2),g.settingsBar,g.raw)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(dismissGesture) {if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL)dismissGesture=false;return true}
        if(event.actionMasked==MotionEvent.ACTION_DOWN&&formatMenuOpen) {
            val selected=formatOptionRects().indexOfFirst{it.contains(event.x,event.y)}
            if(selected>=0&&!session.rawPending){selectFormat(selected);dismissGesture=true;return true}
            if(!geometry().format.contains(event.x,event.y)&&!geometry().orientation.contains(event.x,event.y)) {
                formatMenuOpen=false;dismissGesture=true;invalidate();return true
            }
        }
        if(event.actionMasked==MotionEvent.ACTION_DOWN && settingsFraction>0f && settingsPanel().contains(event.x,event.y)) {
            settingsTouch=true;settingTarget=if(settingsExpanded&&settingsFraction>.95f)(0..4).firstOrNull{settingRow(it).contains(event.x,event.y)}?:-1 else -1
            downX=event.x;downY=event.y;return true
        }
        if(settingsTouch) {
            if(abs(event.x-downX)>touchSlop||abs(event.y-downY)>touchSlop)settingTarget=-1
            if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL) {
                if(event.actionMasked==MotionEvent.ACTION_UP&&settingTarget>=0)activateSetting(settingTarget,event.x)
                settingsTouch=false;settingTarget=-1;performClick()
            }
            return true
        }
        val g=geometry()
        if(event.actionMasked==MotionEvent.ACTION_DOWN&&settingsExpanded&&g.preview.contains(event.x,event.y)) {
            setSettingsExpanded(false);dismissGesture=true;return true
        }
        if(pinch.consume(event,g.preview,!session.rawPending)){normalHeld=false;normalDistance=0f;zooming=false;down=-1;return true}
        if(event.pointerCount>1){normalHeld=false;normalDistance=0f;down=-1;zooming=false;return true}
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{
                down=targets().indexOfFirst{it.contains(event.x,event.y)};zooming=g.zoom.contains(event.x,event.y)
                downX=event.x;downY=event.y;lastY=event.y;remainder=0f;moved=false
                normalHeld=down==0&&!session.rawPending;normalDistance=0f
                if(zooming)zoom(event.x,event.y);invalidate();return true
            }
            MotionEvent.ACTION_MOVE->{
                if(normalHeld){
                    val along=if(width>height)(event.x-downX)*ModeTransitionDirection.zoneEntrySign(state.isLeftHanded) else downY-event.y
                    val cross=if(width>height)event.y-downY else event.x-downX
                    if(abs(cross)>touchSlop&&abs(cross)>abs(along)*1.2f){normalHeld=false;down=-1}
                    normalDistance=along.coerceAtLeast(0f);invalidate();return true
                }
                if(zooming)zoom(event.x,event.y)
                if(down in 3..5&&!session.rawPending) {
                    if(!moved&&abs(event.x-downX)>touchSlop&&abs(event.x-downX)>abs(event.y-downY)){down=-1;return true}
                    if(moved||abs(event.y-downY)>touchSlop) {
                        moved=true;remainder+=(lastY-event.y)/(24*d);lastY=event.y
                        val detents=remainder.toInt()
                        if(detents!=0){AutoMeterControls.adjust(state,session,down-3,detents);remainder-=detents;performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);changed(false)}
                    }
                } else if(down>=0&&!targets()[down].contains(event.x,event.y))down=-1
                return true
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{
                if(normalHeld) {
                    if(event.actionMasked==MotionEvent.ACTION_UP&&normalDistance>=48*d)onExit?.invoke()
                    else if(event.actionMasked==MotionEvent.ACTION_UP){arrowUntil=SystemClock.uptimeMillis()+900;postInvalidateDelayed(900)}
                } else if(event.actionMasked==MotionEvent.ACTION_UP&&!zooming&&!moved&&down>=0)activate(down)
                normalHeld=false;normalDistance=0f;zooming=false;down=-1;invalidate();performClick();return true
            }
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
    override fun getAccessibilityNodeProvider():AccessibilityNodeProvider=accessibility.provider
    internal fun setSettingsExpanded(expanded:Boolean) {
        if(expanded)formatMenuOpen=false
        settingsExpanded=expanded;settingsAnimator?.cancel()
        if(!ValueAnimator.areAnimatorsEnabled()){settingsFraction=if(expanded)1f else 0f;invalidate();return}
        settingsAnimator=ValueAnimator.ofFloat(settingsFraction,if(expanded)1f else 0f).apply {
            duration=180;addUpdateListener{settingsFraction=it.animatedValue as Float;invalidate()};start()
        }
    }
    private fun activate(action:Int) {
        if(action in 40..40+FrameFormat.ALL.size){if(!session.rawPending)selectFormat(action-40);return}
        if(action in 20..24){activateSetting(action-20,settingRow(action-20).right);return}
        if(action==6){setSettingsExpanded(!settingsExpanded);return}
        if(action==7){if(!session.rawPending||session.locked)onRaw?.invoke();return}
        if(session.rawPending)return
        when(action) {
            0->{arrowUntil=SystemClock.uptimeMillis()+900;postInvalidateDelayed(900);invalidate()}
            1->{setSettingsExpanded(false);formatMenuOpen=!formatMenuOpen;invalidate()}
            2->{formatMenuOpen=false;onOrientation?.invoke()}
            3->{val values=(-30..30 step state.exposureCompensationStep.sixthStops).toList()
                choose(l("曝光补偿","Exposure compensation"),values.map{formatParameter(0,it/6.0)+" EV"},values.indexOf(state.exposureCompSteps)){state.exposureCompSteps=values[it];changed(false)}}
            4->{val stops=ExposureMath.apertureStops(state.apertureStep)
                choose(l("固定光圈","Fixed aperture"),stops.map{formatParameter(1,it)},-1){state.setExposureLockModeFromCoordinates(ExposureLockMode.APERTURE,stops[it],state.lockedShutterLogSeconds);changed(false)}}
            5->{val stops=ExposureMath.shutterStops(state.shutterStep)
                choose(l("固定快门","Fixed shutter"),stops.map{formatParameter(2,-it)},-1){state.setExposureLockModeFromCoordinates(ExposureLockMode.SHUTTER,state.lockedApertureStop,stops[it]);changed(false)}}
        }
    }
    private fun selectFormat(index:Int) {
        viewfinderControls.select(index);formatMenuOpen=false
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);changed(true,true)
    }
    private fun activateSetting(index:Int,x:Float) {
        if(session.rawPending||index==2&&!session.continuousMetering)return
        when(index) {
            0->session.simulation=!session.simulation
            1->{session.continuousMetering=!session.continuousMetering;if(session.continuousMetering)session.unlock()}
            2->{
                val options=ClearModePreferences.INTERVALS;val current=options.indexOf(session.sampleIntervalMs).coerceAtLeast(0)
                val row=settingRow(2);val direction=if(x<row.left+row.width()*.79f)-1 else 1
                session.sampleIntervalMs=options[(current+direction).coerceIn(0,options.lastIndex)]
            }
            3->session.showInterfaceText=!session.showInterfaceText
            4->{session.smoothMetering=!session.smoothMetering;if(session.smoothMetering&&!preferences.suppressSmoothNotice)showSmoothNotice()}
        }
        preferences.save(session);onOptionsChanged?.invoke();invalidate()
    }
    private fun showSmoothNotice() {
        val checkbox=CheckBox(context).apply{text=l("不再提示","Don't show again");setPadding((20*d).toInt(),(8*d).toInt(),(20*d).toInt(),(8*d).toInt())}
        AppDialogs.builder(context,state).setTitle(l("平滑测光","Smooth metering"))
            .setMessage(l("开启后，clear 模式会禁用 RAW 测光，手动测光也改用 YUV/ISP 预览流，以减少部分设备的 RAW 测光卡顿。",
                "Clear will disable RAW, including manual metering, and use YUV/ISP preview frames to reduce RAW capture pauses on some devices."))
            .setView(checkbox).setPositiveButton(l("确定","OK")){_,_->if(checkbox.isChecked)preferences.suppressSmoothNotice=true}.show()
    }
    private fun manualLabel():String=when {
        session.locked->l("测光锁定","Metering locked")
        session.rawPending->l("测光中…","Measuring…")
        else->l("手动测光","Manual meter")
    }
    private fun frequencyLabel():String {
        val count=when(session.sampleIntervalMs){2000L->"0.5";1000L->"1";500L->"2";200L->"5";else->"≈3"}
        return count+l("次/秒","/s")
    }
    private fun formatParameter(column:Int,value:Double):String=when(column) {
        0->if(abs(value)>5.00001)"—" else String.format(Locale.US,if(state.exposureCompensationStep.sixthStops<=2)"%+.2f" else "%+.1f",if(abs(value)<.00001)0.0 else value)
        1->ExposureMath.formatAperture(ExposureMath.apertureValueForCoordinate(value,state.apertureStep))
        else->if(-value<ExposureMath.minShutterLogSeconds-.00001 || -value>ExposureMath.maxShutterLogSeconds+.00001)"—"
            else ExposureMath.formatShutter(ExposureMath.shutterValueForCoordinate(-value,state.shutterStep))
    }
    private fun zoom(x:Float,y:Float) {
        if(session.rawPending)return
        val g=geometry()
        val f=(if(width>height)(g.zoom.bottom-24*d-y)/(g.zoom.height()-48*d)
            else (x-g.zoom.left-12*d)/(g.zoom.width()-24*d)).coerceIn(0f,1f)
        state.setZoomControl(((1+f*(state.zoomControlMaximum-1))*10).roundToInt()/10f);changed(false,true)
    }
    private fun changed(frame:Boolean,framing:Boolean=false) {
        if(framing){session.unlock(true,resumeContinuous=session.continuousMetering);onResumeMetering?.invoke()}
        onControls?.invoke(frame);invalidate()
    }
    private fun choose(title:String,items:List<String>,selected:Int,accept:(Int)->Unit) {
        AppDialogs.builder(context,state).setTitle(title).setSingleChoiceItems(items.toTypedArray(),selected){dialog,index->
            dialog.dismiss();if(!session.rawPending&&visibility==VISIBLE)accept(index)
        }.setNegativeButton(l("取消","Cancel"),null).show()
    }
    internal fun closeTransientUi() {
        formatMenuOpen=false
        settingsAnimator?.cancel();settingsAnimator=null;settingsExpanded=false;settingsFraction=0f
        normalHeld=false;normalDistance=0f;arrowUntil=0L;zooming=false;down=-1
        settingsTouch=false;settingTarget=-1;dismissGesture=false
        accessibility.clearFocusForHostExit()
    }
    override fun onDetachedFromWindow(){closeTransientUi();super.onDetachedFromWindow()}
    private fun l(zh:String,en:String)=if(state.menuLanguage==MenuLanguage.ENGLISH)en else zh
}
