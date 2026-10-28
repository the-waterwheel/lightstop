package com.lightmeter.rawmeter

import android.graphics.*
import android.animation.ValueAnimator
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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoMeterUiTest {
    private val context get()=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Lightstop)
    private fun reading(source: MeteringSource = MeteringSource.YUV_PREVIEW)=MeterReading(10.3,.18,0.0,1,100,10_000_000,2f,source)
    private fun size(view: View,w: Int,h: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY)); view.layout(0,0,w,h)
    }
    private fun touch(view: View,action: Int,x: Float,y: Float) {
        MotionEvent.obtain(0,20,action,x,y,0).also { view.onTouchEvent(it);it.recycle() }
    }
    @Test fun normalHandleEntersAutoByDraggingDownOrRightForEitherHand() {
        for(landscape in listOf(false,true)) for(left in listOf(false,true)) {
            val state=MeterState(context).apply { handedness=if(left) Handedness.LEFT else Handedness.RIGHT }
            val view=InstrumentView(context,state)
            var entered=0
            view.listener=object: InstrumentView.Listener {
                override fun onAutoEntryRequested(){ entered++ }
                override fun onMeasureRequested()=Unit
                override fun onOrientationToggle()=Unit
                override fun onMoreRequested()=Unit
                override fun onToolsRequested()=Unit
                override fun onZoneEntryDrag(progress: Float,released: Boolean)=Unit
                override fun onControlsChanged(frameChanged: Boolean)=Unit
            }
            val w=if(landscape)1800 else 840;val h=if(landscape)840 else 1800
            size(view,w,h)
            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap));bitmap.recycle()
            val rect=LayoutGeometry.calculate(w,h,view.layoutDensity(LayoutProfile.METER),left).zoneEntryHandle
            val x=rect.centerX();val y=rect.centerY()
            touch(view,MotionEvent.ACTION_DOWN,x,y)
            val endX=if(landscape)x+w*.1f else x;val endY=if(landscape)y else y+h*.1f
            touch(view,MotionEvent.ACTION_MOVE,endX,endY);touch(view,MotionEvent.ACTION_UP,endX,endY)
            assertEquals("landscape=$landscape left=$left",1,entered)
            touch(view,MotionEvent.ACTION_DOWN,x,y)
            touch(view,MotionEvent.ACTION_MOVE,endX,endY);touch(view,MotionEvent.ACTION_CANCEL,endX,endY)
            assertEquals(1,entered)
        }
    }
    @Test fun autoLayoutUsesItsOwnPreviewAndLeavesNormalReadingAndPreferencesIntact() {
        val state=MeterState(context)
        val original=reading(MeteringSource.RAW);state.acceptNormalReading(original)
        val originalMode=state.meteringMode;val pipeline=state.meteringPipelineMode
        val view=MeterLayout(context,state)
        view.listener=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(MeterLayout.Listener::class.java)) { _,_,_ -> null } as MeterLayout.Listener
        size(view,840,1800);val normalHeight=view.textureView.height
        view.enterAutoMode();size(view,840,1800)
        assertTrue(view.isAutoMode);assertFalse(view.isZoneMode)
        assertTrue(view.textureView.height>normalHeight)
        assertEquals(view.autoView.previewFrame(840,1800).height().toInt(),view.textureView.height)
        view.autoSession.accept(reading());assertSame(original,state.lastNormalReading)
        assertEquals(originalMode,state.meteringMode);assertEquals(pipeline,state.meteringPipelineMode)
        assertNotNull(view.currentExposurePreviewSelection())
        view.autoSession.beginRaw();assertTrue(view.closeAutoMode());assertTrue(view.isAutoMode)
        view.autoSession.fail("cancel");assertTrue(view.closeAutoMode());assertFalse(view.isAutoMode)
    }
    @Test fun allWheelsUseTheConfiguredDetentsAndKeepTheSameNeighborSpacing() {
        val state=MeterState(context);val session=AutoMeterSession().apply { accept(reading()) }
        for(step in ExposureCompensationStep.entries) {
            state.updateSetting(SettingKey.EXPOSURE_COMPENSATION_STEP,step.name);state.exposureCompSteps=0
            val interval=step.sixthStops/6.0
            assertEquals(interval,AutoMeterControls.interval(state,0),.000001)
            AutoMeterControls.adjust(state,session,0,3)
            val current=AutoMeterControls.coordinates(state,session)[0]!!
            assertEquals(3*interval,current,.000001)
            assertEquals(2*interval,(current+interval)-(current-interval),.000001)
        }
        for(step in ExposureStep.entries) {
            state.apertureStep=step;state.shutterStep=step
            state.setExposureLockModeFromCoordinates(ExposureLockMode.APERTURE,5.0,-7.0)
            assertEquals(1.0/step.denominator,AutoMeterControls.interval(state,1),.000001)
            AutoMeterControls.adjust(state,session,1,1)
            assertEquals(5.0+1.0/step.denominator,state.lockedApertureStop,.000001)
            state.setExposureLockModeFromCoordinates(ExposureLockMode.SHUTTER,5.0,-7.0)
            AutoMeterControls.adjust(state,session,2,1)
            assertEquals(-7.0-1.0/step.denominator,state.lockedShutterLogSeconds,.000001)
            assertEquals(1.0/step.denominator,AutoMeterControls.interval(state,2),.000001)
        }
    }
    @Test fun verticalReelGesturesAdjustEvApertureAndShutterWithoutChangingIso() {
        val state=MeterState(context).apply {
            isoIndex=isoValues.indexOf(100);exposureCompSteps=0
            updateSetting(SettingKey.EXPOSURE_COMPENSATION_STEP,ExposureCompensationStep.THIRD.name)
            apertureStep=ExposureStep.HALF;shutterStep=ExposureStep.FULL
            setExposureLockModeFromCoordinates(ExposureLockMode.APERTURE,5.0,-7.0)
        }
        val session=AutoMeterSession().apply {accept(reading())}
        val view=AutoMeterView(context,state,session);size(view,840,1800)
        val row=view.geometry().parameters
        var changes=0;view.onControls={changes++}
        fun drag(column: Int) {
            val x=row.left+row.width()*(column+.5f)/3;val y=row.centerY()
            touch(view,MotionEvent.ACTION_DOWN,x,y);touch(view,MotionEvent.ACTION_MOVE,x,y-48)
            touch(view,MotionEvent.ACTION_UP,x,y-48)
        }
        drag(0);assertEquals(1.0/3,state.exposureCompEv,.000001)
        drag(1);assertEquals(5.5,state.lockedApertureStop,.000001)
        val base=ExposureMath.nearestShutterLogSeconds(-AutoMeterControls.coordinates(state,session)[2]!!,state.shutterStep)
        drag(2);assertEquals(base-1,state.lockedShutterLogSeconds,.000001)
        assertEquals(100,state.iso);assertEquals(3,changes)
    }
    @Test fun theProductionReelRendersIntermediateFramesBeforeReachingTheNewValue() {
        val oldScale=ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java,"sDurationScale")
        ReflectionHelpers.setStaticField(ValueAnimator::class.java,"sDurationScale",1f)
        try {
            val state=MeterState(context).apply {
                updateSetting(SettingKey.EXPOSURE_COMPENSATION_STEP,ExposureCompensationStep.THIRD.name)
                exposureCompSteps=0
            }
            val view=AutoMeterView(context,state,AutoMeterSession().apply {accept(reading())})
            size(view,840,1800)
            val row=view.geometry().parameters
            fun render(): Bitmap {
                val full=Bitmap.createBitmap(840,1800,Bitmap.Config.ARGB_8888);view.draw(Canvas(full))
                val cell=Bitmap.createBitmap(full,row.left.toInt(),row.top.toInt(),(row.width()/3).toInt(),row.height().toInt())
                full.recycle();return cell
            }
            val before=render()
            state.exposureCompSteps=2
            val start=render()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90))
            val middle=render()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            val end=render()
            assertTrue("The first frame retains the previous value",before.sameAs(start))
            assertFalse("A middle frame is actually drawn",middle.sameAs(start))
            assertFalse("The middle frame is not already the final value",middle.sameAs(end))
            assertFalse(before.sameAs(end))
            listOf(before,start,middle,end).forEach(Bitmap::recycle)
        } finally { ReflectionHelpers.setStaticField(ValueAnimator::class.java,"sDurationScale",oldScale) }
    }
    @Test fun layoutControlsFitAndRenderProductionViewsInPortraitLandscapeAndCompact() {
        val output=File(requireNotNull(System.getProperty("uiPreview.output")),"auto").apply { mkdirs() }
        for((name,dimensions) in listOf("portrait" to (840 to 1800),"landscape" to (1800 to 840),"compact" to (720 to 1440))) for(dark in listOf(false,true)) {
            val state=MeterState(context).apply {
                appTheme=if(dark) AppTheme.DARK else AppTheme.LIGHT
                apertureStep=ExposureStep.THIRD;shutterStep=ExposureStep.THIRD
                updateSetting(SettingKey.EXPOSURE_COMPENSATION_STEP,ExposureCompensationStep.THIRD.name)
                exposureCompSteps=0
                setExposureLockModeFromCoordinates(ExposureLockMode.APERTURE,5.0,-7.0)
                frameIndex=0;zoom=1.4f;cameraInfo=CameraUiInfo(focalLengthMm=4.5f,sensorWidthMm=6.4f,sensorHeightMm=4.8f,aperture=1.8f)
            }
            val session=AutoMeterSession().apply { accept(reading()) }
            if(dark) { session.beginRaw();session.finishRaw(reading(MeteringSource.RAW)) }
            val view=AutoMeterView(context,state,session)
            val(w,h)=dimensions;size(view,w,h);val g=view.geometry()
            for(rect in listOf(g.preview,g.mode,g.format,g.orientation,g.status,g.parameters,g.simulation,g.raw,g.zoom)) {
                assertTrue("$name: $rect",rect.width()>0 && rect.height()>0 && rect.left>=0 && rect.top>=0 && rect.right<=w && rect.bottom<=h)
            }
            assertFalse(RectF.intersects(g.preview,g.parameters))
            assertFalse(RectF.intersects(g.parameters,g.simulation))
            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
            // Synthetic static scene behind the actual production overlay; no device/photo claim.
            val paint=Paint().apply { shader=LinearGradient(0f,0f,0f,h.toFloat(),Color.rgb(139,168,180),Color.rgb(206,204,170),Shader.TileMode.CLAMP) }
            canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint);paint.shader=null;paint.color=Color.rgb(76,102,91)
            val path=Path().apply {moveTo(0f,h*.63f);lineTo(w*.3f,h*.35f);lineTo(w*.6f,h*.55f);lineTo(w*.78f,h*.41f);lineTo(w.toFloat(),h*.7f);lineTo(w.toFloat(),h.toFloat());lineTo(0f,h.toFloat());close()}
            canvas.drawPath(path,paint);view.draw(canvas)
            File(output,"auto-$name-${if(dark)"locked-dark" else "live-light"}.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
        }
    }
}
