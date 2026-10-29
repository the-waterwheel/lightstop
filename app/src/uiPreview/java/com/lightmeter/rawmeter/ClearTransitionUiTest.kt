package com.lightmeter.rawmeter

import android.animation.ValueAnimator
import android.graphics.*
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClearTransitionUiTest {
    private val context get()=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Lightstop)
    private fun animated(block:()->Unit) {
        val scale=ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java,"sDurationScale")
        ReflectionHelpers.setStaticField(ValueAnimator::class.java,"sDurationScale",1f)
        try{block()}finally{ReflectionHelpers.setStaticField(ValueAnimator::class.java,"sDurationScale",scale)}
    }
    private fun advance(ms:Long)=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private inner class Fixture(val landscape:Boolean=false,val left:Boolean=false,val dark:Boolean=false) {
        var w=if(landscape)1800 else 840;var h=if(landscape)840 else 1800
        val state=MeterState(context).apply {
            handedness=if(left)Handedness.LEFT else Handedness.RIGHT;appTheme=if(dark)AppTheme.DARK else AppTheme.LIGHT
            frameIndex=0;zoom=1.4f;previewZoom=1f;exposureCompSteps=0
            setExposureLockModeFromCoordinates(ExposureLockMode.APERTURE,5.0,-7.0)
            cameraInfo=CameraUiInfo(focalLengthMm=4.5f,sensorWidthMm=6.4f,sensorHeightMm=4.8f,aperture=1.8f)
        }
        val modes=mutableListOf<Boolean>();val transitions=mutableListOf<Boolean>()
        val layout=MeterLayout(context,state)
        init {
            layout.listener=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(MeterLayout.Listener::class.java)){_,method,args->
                if(method.name=="onAutoModeChanged")modes+=args!![0] as Boolean
                if(method.name=="onAutoTransitionChanged")transitions+=args!![0] as Boolean
                null
            } as MeterLayout.Listener
            size();render().recycle()
        }
        fun size() {
            layout.requestLayout()
            layout.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));layout.layout(0,0,w,h)
        }
        fun event(action:Int,x:Float,y:Float) {
            MotionEvent.obtain(0,20,action,x,y,0).also{layout.dispatchTouchEvent(it);it.recycle()}
        }
        fun start(entering:Boolean):Pair<Float,Float> {
            val rect=if(entering)LayoutGeometry.calculate(w,h,context.resources.displayMetrics.density,state.isLeftHanded).zoneEntryHandle else layout.autoView.normalHandle()
            return (rect.centerX() to rect.centerY()).also{event(MotionEvent.ACTION_DOWN,it.first,it.second)}
        }
        fun point(start:Pair<Float,Float>,p:Float,entering:Boolean):Pair<Float,Float> {
            val distance=(if(w>h)w else h)*.18f*p
            val sign=if(entering) {if(state.isLeftHanded)-1f else 1f} else {if(state.isLeftHanded)1f else -1f}
            return if(w>h)(start.first+sign*distance) to start.second else start.first to (start.second+if(entering)distance else -distance)
        }
        fun move(start:Pair<Float,Float>,p:Float,entering:Boolean):Pair<Float,Float> =point(start,p,entering).also{event(MotionEvent.ACTION_MOVE,it.first,it.second);size()}
        fun release(point:Pair<Float,Float>,cancel:Boolean=false) {event(if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,point.first,point.second)}
        fun render():Bitmap {
            size();val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
            val paint=Paint().apply{shader=LinearGradient(0f,0f,0f,h.toFloat(),Color.rgb(139,168,180),Color.rgb(206,204,170),Shader.TileMode.CLAMP)}
            canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint);paint.shader=null;paint.color=Color.rgb(76,102,91)
            canvas.drawPath(Path().apply{moveTo(0f,h*.7f);lineTo(w*.3f,h*.4f);lineTo(w*.6f,h*.6f);lineTo(w*.8f,h*.43f);lineTo(w.toFloat(),h*.75f);lineTo(w.toFloat(),h.toFloat());lineTo(0f,h.toFloat());close()},paint)
            // The real production overlays over a synthetic static scene; no camera/phone claim.
            if(layout.instrumentView.visibility==View.VISIBLE)layout.instrumentView.draw(canvas)
            if(layout.autoView.visibility==View.VISIBLE)layout.autoView.draw(canvas)
            return bitmap
        }
    }
    private fun motion(view:View):ModePanelMotion?=ReflectionHelpers.getField(view,"clearMotion")
    @Test fun draggedPanelsMoveAndFadeInTheCorrectDirectionForEveryOrientationAndHand()=animated {
        for(landscape in listOf(false,true))for(left in listOf(false,true)) {
            val f=Fixture(landscape,left);val normalFrame=RectF(f.layout.textureView.left.toFloat(),f.layout.textureView.top.toFloat(),f.layout.textureView.right.toFloat(),f.layout.textureView.bottom.toFloat())
            val down=f.start(true);val at=f.move(down,.5f,true)
            assertTrue(f.layout.isClearTransitionInProgress);assertFalse(f.layout.isAutoMode);assertTrue(f.modes.isEmpty())
            val normal=motion(f.layout.instrumentView)!!;val clear=motion(f.layout.autoView)!!
            assertTrue(normal.alpha in .4f.. .6f);assertTrue(clear.alpha in .4f.. .6f)
            if(landscape){assertTrue(if(left)normal.x<0 else normal.x>0);assertTrue(if(left)clear.x>0 else clear.x<0)}
            else {assertTrue(normal.y>0);assertTrue(clear.y<0)}
            assertEquals(normal.preview,clear.preview)
            assertTrue(f.layout.textureView.height!=normalFrame.height().toInt()||landscape)
            f.release(at);assertTrue(f.layout.isClearTransitionInProgress);assertTrue(f.modes.isEmpty())
            advance(600);f.size();assertTrue(f.layout.isAutoMode);assertFalse(f.layout.isClearTransitionInProgress);assertEquals(listOf(true),f.modes)
            val up=f.start(false);val back=f.move(up,.7f,false)
            val exiting=motion(f.layout.autoView)!!
            if(landscape)assertTrue(if(left)exiting.x>0 else exiting.x<0) else assertTrue(exiting.y<0)
            assertTrue(f.layout.isAutoMode);f.release(back);advance(600)
            assertFalse(f.layout.isAutoMode);assertFalse(f.layout.isClearTransitionInProgress)
            assertEquals(listOf(true,false),f.modes);assertEquals(listOf(true,false,true,false),f.transitions)
            assertNull(motion(f.layout.instrumentView));assertNull(motion(f.layout.autoView))
        }
    }
    @Test fun shortCanceledAndReversedGesturesReturnToTheirOriginalModeWithoutSwitchingCameraWorkflow()=animated {
        val f=Fixture()
        for(cancel in listOf(false,true)) {
            val down=f.start(true);val at=f.move(down,if(cancel).8f else .2f,true);f.release(at,cancel);advance(600)
            assertFalse(f.layout.isAutoMode);assertFalse(f.layout.isClearTransitionInProgress);assertTrue(f.modes.isEmpty())
        }
        val down=f.start(true);f.move(down,.8f,true);val reversed=f.move(down,.1f,true);f.release(reversed);advance(600)
        assertFalse(f.layout.isAutoMode);assertTrue(f.modes.isEmpty())
        f.layout.enterAutoMode();f.size();f.render().recycle()
        val reading=MeterReading(10.3,.18,0.0,1,100,10_000_000,2f,MeteringSource.YUV_PREVIEW)
        f.layout.autoSession.accept(reading);f.layout.autoSession.beginManual(false);f.layout.autoSession.finishManual(reading)
        val old=f.layout.autoSession.reading
        val up=f.start(false);val at=f.move(up,.3f,false);f.release(at);advance(600)
        assertTrue(f.layout.isAutoMode);assertTrue(f.layout.autoSession.locked);assertSame(old,f.layout.autoSession.reading)
        assertEquals(listOf(true),f.modes)
    }
    @Test fun rotationHandednessAndLifecycleCancellationNeverLeavePartialLayers()=animated {
        for(clear in listOf(false,true)) {
            val f=Fixture();if(clear){f.layout.enterAutoMode();f.size();f.render().recycle()}
            val down=f.start(!clear);f.move(down,.6f,!clear)
            f.w=1800;f.h=840;f.size()
            assertFalse(f.layout.isClearTransitionInProgress);assertEquals(clear,f.layout.isAutoMode)
            assertNull(motion(f.layout.autoView));assertNull(motion(f.layout.instrumentView))
        }
        val f=Fixture(true);var down=f.start(true);f.move(down,.5f,true)
        f.state.handedness=Handedness.LEFT;f.size();f.render().recycle();assertFalse(f.layout.isClearTransitionInProgress);assertFalse(f.layout.isAutoMode)
        down=f.start(true);val at=f.move(down,.8f,true);f.release(at)
        f.layout.cancelClearModeTransition();advance(600)
        assertFalse(f.layout.isAutoMode);assertFalse(f.layout.isClearTransitionInProgress);assertTrue(f.modes.isEmpty())
    }
    @Test fun aNewTouchDuringSettlementCannotReachTheIncomingManualMeterButton()=animated {
        val f=Fixture();var captures=0;f.layout.autoView.onRaw={captures++}
        val down=f.start(true);val at=f.move(down,.6f,true);f.release(at)
        val button=f.layout.autoView.geometry().raw
        f.event(MotionEvent.ACTION_DOWN,button.centerX(),button.centerY());advance(600)
        f.event(MotionEvent.ACTION_UP,button.centerX(),button.centerY())
        assertTrue(f.layout.isAutoMode);assertEquals(0,captures)
        f.event(MotionEvent.ACTION_DOWN,button.centerX(),button.centerY());f.event(MotionEvent.ACTION_UP,button.centerX(),button.centerY())
        assertEquals(1,captures)
    }
    @Test fun endpointsRenderLikeSteadyModesWithoutAnExtraGrayMask()=animated {
        fun compare(expected:Bitmap,actual:Bitmap,name:String) {
            if(expected.sameAs(actual))return
            val output=File(requireNotNull(System.getProperty("uiPreview.output")),"clear-transition").apply{mkdirs()}
            for((suffix,b) in listOf("expected" to expected,"actual" to actual))File(output,"$name-$suffix.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
            val a=IntArray(expected.width*expected.height);val b=IntArray(a.size)
            expected.getPixels(a,0,expected.width,0,0,expected.width,expected.height);actual.getPixels(b,0,actual.width,0,0,actual.width,actual.height)
            val changed=a.indices.filter{a[it]!=b[it]}
            fail("$name: ${changed.size} differing pixels, x=${changed.minOf{it%expected.width}}..${changed.maxOf{it%expected.width}}, y=${changed.minOf{it/expected.width}}..${changed.maxOf{it/expected.width}}")
        }
        val f=Fixture();val before=f.render()
        val down=f.start(true);f.move(down,.2f,true);f.move(down,0f,true)
        val atStart=f.render();compare(before,atStart,"normal-endpoint")
        val at=f.move(down,1f,true);val atEnd=f.render();f.release(at);advance(600)
        val after=f.render();compare(atEnd,after,"clear-endpoint")
        listOf(before,atStart,atEnd,after).forEach(Bitmap::recycle)
    }
    @Test fun renderProductionGestureSequences()=animated {
        val output=File(requireNotNull(System.getProperty("uiPreview.output")),"clear-transition").apply{mkdirs()}
        for((name,f) in listOf("portrait" to Fixture(),"landscape-right" to Fixture(true),"landscape-left" to Fixture(true,true),"dark-portrait" to Fixture(dark=true))) {
            for(entering in listOf(true,false)) {
                val direction=if(entering)"enter" else "exit"
                fun save(index:Int) {val b=f.render();File(output,"$name-$direction-${index.toString().padStart(2,'0')}.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
                save(0);val down=f.start(entering);var at=down
                for(i in 1..12){at=f.move(down,i/12f,entering);save(i)}
                f.release(at);advance(600);save(13)
            }
        }
    }
}
