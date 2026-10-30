package com.lightmeter.rawmeter

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.ContextThemeWrapper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClearShutterStopsTest {
    private val context get()=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Lightstop)
    private data class Label(val text:String,val x:Float,val y:Float)
    private class RecordingCanvas(bitmap:Bitmap):Canvas(bitmap) {
        val labels=mutableListOf<Label>()
        override fun drawText(text:String,x:Float,y:Float,paint:Paint) {
            labels+=Label(text,x,y+(paint.ascent()+paint.descent())/2)
            super.drawText(text,x,y,paint)
        }
    }
    private fun view(state:MeterState,session:AutoMeterSession)=AutoMeterView(context,state,session).apply {
        measure(View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.EXACTLY));layout(0,0,840,1800)
    }
    private fun labels(view:AutoMeterView):List<String> {
        val row=view.geometry().parameters;val x=row.left+row.width()*2.5f/3
        val bitmap=Bitmap.createBitmap(840,1800,Bitmap.Config.ARGB_8888)
        val canvas=RecordingCanvas(bitmap);view.draw(canvas);bitmap.recycle()
        val values=canvas.labels.filter{kotlin.math.abs(it.x-x)<2&&it.y>row.top+34&&it.y<row.bottom-14}.sortedBy{it.y}.map{it.text}
        assertEquals(3,values.size);return values
    }
    private fun noAnimations(block:()->Unit) {
        val previous=ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java,"sDurationScale")
        ReflectionHelpers.setStaticField(ValueAnimator::class.java,"sDurationScale",0f)
        try {block()} finally {ReflectionHelpers.setStaticField(ValueAnimator::class.java,"sDurationScale",previous)}
    }
    private fun state()=MeterState(context).apply {
        exposureCompSteps=0;isoIndex=isoValues.indexOf(100)
        setExposureLockModeFromCoordinates(ExposureLockMode.APERTURE,5.0,-7.0)
    }
    private fun reading(ev:Double)=MeterReading(ev,.18,0.0,1,100,10_000_000,2f,MeteringSource.YUV_PREVIEW)
    @Test fun liveOffGridExposureShowsStandardStopsForAllThreeStepSettings()=noAnimations {
        val state=state();val session=AutoMeterSession().apply{accept(reading(10.3))};val view=view(state,session)
        for((step,expected) in listOf(
            ExposureStep.FULL to listOf("1/15","1/30","1/60"),
            ExposureStep.HALF to listOf("1/30","1/45","1/60"),
            ExposureStep.THIRD to listOf("1/30","1/40","1/50"))) {
            state.shutterStep=step
            assertEquals(step.name,expected,labels(view))
            assertEquals("The metering result must remain precise",10.3,session.reading!!.sceneEv100,0.0)
            assertEquals("Display rounding must not change the exposure calculation",5.3,AutoMeterControls.coordinates(state,session)[2]!!,.000001)
        }
    }
    @Test fun eachStandardPositionAndItsNeighborsMatchTheNormalZoneShutterScale()=noAnimations {
        val state=state();val session=AutoMeterSession().apply{accept(reading(10.0))};val view=view(state,session)
        for(step in ExposureStep.entries) {
            state.shutterStep=step
            val ticks=ExposureMath.shutterTicks(step)
            for((index,tick) in ticks.withIndex()) {
                state.setExposureLockModeFromCoordinates(ExposureLockMode.SHUTTER,5.0,tick.coordinate)
                val visible=labels(view)
                assertEquals("$step centre $index",ExposureMath.formatShutter(tick.nominalValue),visible[1])
                if(index>0)assertEquals("$step slower neighbour $index",ExposureMath.formatShutter(ticks[index-1].nominalValue),visible[0])
                if(index<ticks.lastIndex)assertEquals("$step faster neighbour $index",ExposureMath.formatShutter(ticks[index+1].nominalValue),visible[2])
                else assertEquals("The faster neighbour of 1/8000 is unavailable","—",visible[2])
            }
        }
    }
    @Test fun longExposuresContinueOnTheConfiguredGridWithoutClampingAtThirtySeconds()=noAnimations {
        val state=state();val session=AutoMeterSession().apply{accept(reading(-1.6))};val view=view(state,session)
        for(step in ExposureStep.entries) {
            state.shutterStep=step
            val coordinate=ExposureMath.nearestShutterLogSeconds(6.6,step)
            val expected=ExposureMath.shutterValueForCoordinate(coordinate,step)
            assertTrue(expected>30)
            assertEquals(ExposureMath.formatShutter(expected),labels(view)[1])
        }
        state.setExposureLockModeFromCoordinates(ExposureLockMode.SHUTTER,5.0,ExposureMath.maxShutterLogSeconds)
        assertEquals("—",labels(view)[0])
    }
}
