package com.lightmeter.rawmeter

import android.app.Activity
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParameterHistoryIsolationTest {
    @Test fun blankHistoryTouchesNeverReachTheUnderlyingMeterEvenIfThatControlIsVisible() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply {setTheme(R.style.Theme_Lightstop)}
        val state=MeterState(activity)
        val host=android.widget.FrameLayout(activity)
        var leakedTouches=0
        host.addView(object:View(activity) {
            override fun onTouchEvent(event: MotionEvent): Boolean {leakedTouches++;return true}
        },android.widget.FrameLayout.LayoutParams(-1,-1))
        val repo=ParameterRecordRepository(activity);RecordPreviewFixtures.seed(repo)
        val history=ParameterHistoryView(activity,state,repo)
        host.addView(history,android.widget.FrameLayout.LayoutParams(-1,-1));activity.setContentView(host)
        RecordPreviewFixtures.measure(host,840,1800)
        history.open();RecordPreviewFixtures.settle()
        val g=ParameterHistoryGeometryCalculator.calculate(host.width,host.height,2f,0f)
        repeat(2) { RecordPreviewFixtures.tap(history,g.content.left+20,g.content.top+20) }
        for ((x,y) in listOf(host.width-2f to host.height-2f,g.content.left-2f to g.data.centerY(),host.width-2f to g.content.top+20f)) {
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(0,10,action,x,y,0).also {
                    assertTrue("Touch $action at $x,$y in ${host.width}×${host.height}",host.dispatchTouchEvent(it));it.recycle()
                }
            }
        }
        assertEquals(0,leakedTouches)
        // Only running/finished state controls gesture ownership. Avoid Robolectric's automatic
        // animator completion, so the running -> finished boundary occurs precisely mid-gesture.
        val animation=object:ValueAnimator() {
            var active=true
            override fun isRunning():Boolean=active
        }
        ReflectionHelpers.setField(history,"imageSlideAnimator",animation)
        val x=g.image.centerX();val y=g.image.centerY()
        MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,x,y,0).also {assertTrue(host.dispatchTouchEvent(it));it.recycle()}
        assertTrue(field(history,"suppressTouchUntilUp"))
        animation.active=false
        MotionEvent.obtain(0,30,MotionEvent.ACTION_UP,x,y,0).also {assertTrue(host.dispatchTouchEvent(it));it.recycle()}
        assertFalse(field(history,"suppressTouchUntilUp"))
        assertEquals(0,leakedTouches)
        controller.pause().stop().destroy()
    }

    @Test fun refreshAndLayoutCannotRaiseTheRecordButtonOverHistoryAndClosingRestoresIt() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply {setTheme(R.style.Theme_Lightstop)}
        val state=MeterState(activity).apply {meteringMode=MeteringMode.ANGLE}
        val layout=MeterLayout(activity,state)
        layout.listener=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(MeterLayout.Listener::class.java)) {_,_,_->null} as MeterLayout.Listener
        activity.setContentView(layout);RecordPreviewFixtures.measure(layout,840,1800)
        val repo=field<ParameterRecordRepository>(layout,"parameterRecordRepository");RecordPreviewFixtures.seed(repo)
        val tool=field<ParameterRecordToolView>(layout,"parameterRecordToolView")
        tool.javaClass.getDeclaredField("recording").apply {isAccessible=true}.setBoolean(tool,true)
        call(layout,"setRecordSliderVisible",arrayOf(Boolean::class.javaPrimitiveType!!),true)
        val slider=field<RecordCaptureSliderView>(layout,"recordCaptureSliderView")
        assertEquals(View.VISIBLE,slider.visibility)
        call(layout,"showParameterHistory")
        RecordPreviewFixtures.settle()
        val history=field<ParameterHistoryView>(layout,"parameterHistoryView")
        assertTrue(layout.isParameterHistoryOpen)
        for(mode in listOf(MeteringMode.AVERAGE,MeteringMode.ANGLE,MeteringMode.SPOT)) {
            state.meteringMode=mode
            layout.refresh(true);RecordPreviewFixtures.measure(layout,840,1800)
            assertEquals(View.GONE,slider.visibility)
            assertTrue(layout.indexOfChild(history)>layout.indexOfChild(slider))
        }
        assertTrue(layout.closeParameterHistoryFromBack())
        RecordPreviewFixtures.settle();RecordPreviewFixtures.measure(layout,840,1800)
        layout.refresh()
        assertFalse(layout.isParameterHistoryOpen)
        assertTrue(tool.recording)
        assertEquals(View.VISIBLE,slider.visibility)
        controller.pause().stop().destroy()
    }

    @Test fun hidingTheRecordControlCancelsAnAlreadyScheduledLongPress() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        val slider=RecordCaptureSliderView(activity,MeterState(activity))
        activity.setContentView(slider);RecordPreviewFixtures.measure(slider,840,1800)
        slider.setAnchor(RectF(580f,1400f,790f,1480f))
        val track=field<RectF>(slider,"track")
        var captures=0;slider.onCaptureRequested={captures++}
        MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,track.centerX(),track.centerY(),0).also {
            assertTrue(slider.onTouchEvent(it));it.recycle()
        }
        slider.visibility=View.GONE
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout()+100L))
        assertEquals(0,captures)
        slider.visibility=View.VISIBLE
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(0,10,action,track.centerX(),track.centerY(),0).also {slider.onTouchEvent(it);it.recycle()}
        }
        assertEquals(0,captures)
        controller.pause().stop().destroy()
    }

    @Suppress("UNCHECKED_CAST") private fun <T> field(value:Any,name:String):T =
        value.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(value) as T
    private fun call(value:Any,name:String,types:Array<Class<*>> = emptyArray(),vararg args:Any) {
        value.javaClass.getDeclaredMethod(name,*types).apply {isAccessible=true}.invoke(value,*args)
    }
}
