package com.lightmeter.rawmeter

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.graphics.*
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClearModeUiTest {
    private val context get()=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Lightstop)
    private fun size(view:View,w:Int=840,h:Int=1800) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
        view.layout(0,0,w,h)
    }
    private fun touch(view:View,action:Int,x:Float,y:Float) {
        MotionEvent.obtain(0,20,action,x,y,0).also {view.onTouchEvent(it);it.recycle()}
    }
    private fun tap(view:View,rect:RectF,x:Float=rect.centerX()) {
        touch(view,MotionEvent.ACTION_DOWN,x,rect.centerY());touch(view,MotionEvent.ACTION_UP,x,rect.centerY())
    }
    private fun fixture(dark:Boolean=false):AutoMeterView {
        val state=MeterState(context).apply {
            appTheme=if(dark)AppTheme.DARK else AppTheme.LIGHT;menuLanguage=MenuLanguage.CHINESE
            cameraInfo=CameraUiInfo(focalLengthMm=4.5f,sensorWidthMm=6.4f,sensorHeightMm=4.8f,aperture=1.8f)
            exposureCompSteps=0;apertureStep=ExposureStep.THIRD;shutterStep=ExposureStep.THIRD
            setExposureLockModeFromCoordinates(ExposureLockMode.APERTURE,5.0,-7.0)
            frameIndex=0;zoom=1.4f
        }
        val session=AutoMeterSession().apply {accept(MeterReading(10.3,.18,0.0,1,100,10_000_000,2f,MeteringSource.YUV_PREVIEW))}
        return AutoMeterView(context,state,session).also {size(it)}
    }
    private fun render(view:View,scene:Boolean=false):Bitmap {
        val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);val c=Canvas(b)
        if(scene) {
            val w=view.width.toFloat();val h=view.height.toFloat()
            val p=Paint().apply {shader=LinearGradient(0f,0f,0f,h,Color.rgb(139,168,180),Color.rgb(206,204,170),Shader.TileMode.CLAMP)}
            c.drawRect(0f,0f,w,h,p);p.shader=null;p.color=Color.rgb(76,102,91)
            c.drawPath(Path().apply {moveTo(0f,h*.63f);lineTo(w*.3f,h*.35f);lineTo(w*.6f,h*.55f);lineTo(w*.78f,h*.41f);lineTo(w,h*.7f);lineTo(w,h);lineTo(0f,h);close()},p)
        }
        view.draw(c);return b
    }
    private fun open(view:AutoMeterView) {
        tap(view,view.geometry().settingsBar)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        render(view).recycle()
        assertTrue(view.settingsExpanded)
    }
    @Test fun inlineSettingsKeepThePreviewAndDisableFrequencyWhenAutoIsOff() {
        val view=fixture();val preview=RectF(view.geometry().preview);var options=0
        view.onOptionsChanged={options++}
        open(view);assertEquals(preview,view.geometry().preview)
        tap(view,view.settingRow(0));assertFalse(view.session.simulation)
        tap(view,view.settingRow(2),view.settingRow(2).right-10);assertEquals(200L,view.session.sampleIntervalMs)
        tap(view,view.settingRow(1));assertFalse(view.session.continuousMetering)
        tap(view,view.settingRow(2),view.settingRow(2).left+10);assertEquals(200L,view.session.sampleIntervalMs)
        assertEquals(3,options)
        render(view).recycle()
        assertNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(22))
        tap(view,view.settingRow(1));assertTrue(view.session.continuousMetering)
        tap(view,view.settingRow(2),view.settingRow(2).left+10);assertEquals(350L,view.session.sampleIntervalMs)
        tap(view,view.geometry().settingsBar);assertFalse(view.settingsExpanded)
        assertEquals(preview,view.geometry().preview)
    }
    @Test fun smoothNoticeIsInformationalAndCanBeSuppressed() {
        val view=fixture();open(view);tap(view,view.settingRow(4))
        assertTrue(view.session.smoothMetering);assertFalse(view.session.shouldUseRaw(true))
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertEquals("确定",dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility!=View.VISIBLE)
        fun checkbox(view:View):CheckBox? {
            if(view is CheckBox)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)checkbox(view.getChildAt(i))?.let{return it}
            return null
        }
        checkNotNull(checkbox(dialog.window!!.decorView)).isChecked=true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ClearModePreferences(context).suppressSmoothNotice)
        tap(view,view.settingRow(4));tap(view,view.settingRow(4))
        assertTrue(view.session.smoothMetering)
        assertSame(dialog,ShadowAlertDialog.getLatestAlertDialog());assertFalse(dialog.isShowing)
        assertTrue(ClearModePreferences(context).suppressSmoothNotice)
    }
    @Test fun clearOptionsRoundTripWithoutOverwritingNormalAndInvalidCadenceRecovers() {
        val prefs=ClearModePreferences(context);val original=MeterState(context)
        val pipeline=original.meteringPipelineMode;val mode=original.meteringMode
        val session=AutoMeterSession().apply {
            simulation=false;continuousMetering=false;sampleIntervalMs=1000;showInterfaceText=false;smoothMetering=true
        }
        prefs.save(session)
        val restored=AutoMeterSession().apply {prefs.restore(this)}
        assertFalse(restored.simulation);assertFalse(restored.continuousMetering)
        assertEquals(1000L,restored.sampleIntervalMs);assertFalse(restored.showInterfaceText);assertTrue(restored.smoothMetering)
        assertEquals(pipeline,MeterState(context).meteringPipelineMode);assertEquals(mode,MeterState(context).meteringMode)
        context.getSharedPreferences("clear_meter_options",0).edit().putLong("interval_ms",-1).apply()
        prefs.restore(restored);assertEquals(350L,restored.sampleIntervalMs)
    }
    @Test fun hiddenCaptionsKeepManualMeteringAndTheNumericalControlsReachable() {
        val view=fixture();val before=render(view)
        open(view);tap(view,view.settingRow(3));assertFalse(view.session.showInterfaceText)
        tap(view,view.geometry().settingsBar)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        val after=render(view);val status=view.geometry().status
        fun crop(b:Bitmap)=Bitmap.createBitmap(b,status.left.toInt(),status.top.toInt(),status.width().toInt(),status.height().toInt())
        val old=crop(before);val hidden=crop(after);assertFalse(old.sameAs(hidden))
        var manual=0;view.onRaw={manual++};tap(view,view.geometry().raw);assertEquals(1,manual)
        assertNotNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(3))
        listOf(before,after,old,hidden).forEach(Bitmap::recycle)
    }
    @Test fun previewBottomHandleRequiresAnUpwardSwipeInBothOrientations() {
        for((w,h) in listOf(840 to 1800,1800 to 840)) {
            val view=fixture();size(view,w,h);var exits=0;view.onExit={exits++}
            tap(view,view.geometry().mode);assertEquals(0,exits)
            val handle=view.normalHandle();val before=render(view)
            tap(view,handle);val guided=render(view);assertFalse(before.sameAs(guided))
            assertEquals(0,exits)
            fun swipe(action:Int) {
                touch(view,MotionEvent.ACTION_DOWN,handle.centerX(),handle.centerY())
                touch(view,MotionEvent.ACTION_MOVE,handle.centerX(),handle.centerY()-150)
                touch(view,action,handle.centerX(),handle.centerY()-150)
            }
            swipe(MotionEvent.ACTION_CANCEL);assertEquals(0,exits)
            swipe(MotionEvent.ACTION_UP);assertEquals(1,exits)
            before.recycle();guided.recycle()
        }
    }
    @Test fun expandedLandscapeSettingsHideCoveredAccessibilityTargetsAndCloseCleanly() {
        val view=fixture();size(view,1800,840);open(view)
        val provider=view.accessibilityNodeProvider
        assertNull(provider.createAccessibilityNodeInfo(3))
        assertNotNull(provider.createAccessibilityNodeInfo(20))
        assertTrue(provider.performAction(20,AccessibilityNodeInfo.ACTION_CLICK,null))
        assertFalse(view.session.simulation)
        view.closeTransientUi();assertFalse(view.settingsExpanded)
        render(view).recycle();assertNotNull(provider.createAccessibilityNodeInfo(3));assertNull(provider.createAccessibilityNodeInfo(20))
    }
    @Test fun rapidReelRetargetingKeepsTheExactVisibleIntermediateFrame() {
        val scale=ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java,"sDurationScale")
        ReflectionHelpers.setStaticField(ValueAnimator::class.java,"sDurationScale",1f)
        try {
            val reel=RollingParameterWindow();val rect=RectF(0f,0f,240f,200f)
            fun frame(value:Double):Bitmap=Bitmap.createBitmap(240,200,Bitmap.Config.ARGB_8888).also {
                reel.draw(Canvas(it),rect,value,1.0,2f,Color.BLACK,{n->"%.1f".format(n)},{})
            }
            frame(5.0).recycle();frame(6.0).recycle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80))
            val middle=frame(6.0);val retarget=frame(7.0)
            assertTrue("Retarget must preserve the currently visible glyph positions",middle.sameAs(retarget))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            val end=frame(7.0);assertFalse(end.sameAs(middle))
            val settled=frame(7.0);assertTrue(settled.sameAs(end))
            listOf(middle,retarget,end,settled).forEach(Bitmap::recycle)
            repeat(100) {index->
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
                frame(8.0+index).recycle()
            }
            val retained=ReflectionHelpers.getField<List<*>>(reel,"fromGlyphs")
            assertTrue("An always-on 5 Hz meter must release fully transparent animation remnants",retained.size<20)
        } finally {ReflectionHelpers.setStaticField(ValueAnimator::class.java,"sDurationScale",scale)}
    }
    @Test fun renderCurrentClearLayoutsFromProductionCanvas() {
        val output=File(requireNotNull(System.getProperty("uiPreview.output")),"clear").apply{mkdirs()}
        for((name,dimensions) in listOf("portrait" to (840 to 1800),"landscape" to (1800 to 840),"compact" to (720 to 1440),"dark-portrait" to (840 to 1800))) {
            val view=fixture(name.startsWith("dark"));size(view,dimensions.first,dimensions.second)
            fun save(suffix:String) {render(view,true).also {b->File(output,"clear-$name-$suffix.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
            save("closed");open(view);save("settings")
            tap(view,view.settingRow(3));tap(view,view.geometry().settingsBar)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));save("text-off")
            tap(view,view.normalHandle());save("guide")
        }
    }
    @Test fun renderTheThreeProductionModeHandlesWithTheirGuidesVisible() {
        val output=File(requireNotNull(System.getProperty("uiPreview.output")),"clear").apply{mkdirs()}
        val state=MeterState(context).apply{appTheme=AppTheme.LIGHT}
        val normal=InstrumentView(context,state);val zone=ZoneSystemView(context,state).apply{enter()}
        val clear=fixture()
        for((name,view) in listOf("normal" to normal,"zone" to zone,"clear" to clear)) {
            size(view);render(view).recycle()
            val handle=when(view) {
                is InstrumentView->LayoutGeometry.calculate(840,1800,view.layoutDensity(LayoutProfile.METER),false).zoneEntryHandle
                is ZoneSystemView->ZoneLayoutCalculator.calculate(840,1800,context.resources.displayMetrics.density,state).normalHandle
                else->clear.normalHandle()
            }
            val before=render(view)
            touch(view,MotionEvent.ACTION_DOWN,handle.centerX(),handle.centerY())
            val after=render(view,true)
            File(output,"$name-mode-guide.png").outputStream().use{after.compress(Bitmap.CompressFormat.PNG,100,it)}
            // Both frames use the same scene for the interaction assertion.
            val pressed=render(view);assertFalse("$name must show a guide on press",before.sameAs(pressed))
            touch(view,MotionEvent.ACTION_CANCEL,handle.centerX(),handle.centerY())
            listOf(before,after,pressed).forEach(Bitmap::recycle)
        }
    }
}
