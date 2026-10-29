package com.lightmeter.rawmeter

import android.graphics.*
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClearNavigationUiTest {
    private val context get()=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Lightstop)
    private fun size(view:View,w:Int,h:Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
    }
    private fun touch(view:View,action:Int,x:Float,y:Float) {
        MotionEvent.obtain(0,20,action,x,y,0).also {view.onTouchEvent(it);it.recycle()}
    }
    private fun tap(view:View,rect:RectF) {
        touch(view,MotionEvent.ACTION_DOWN,rect.centerX(),rect.centerY());touch(view,MotionEvent.ACTION_UP,rect.centerX(),rect.centerY())
    }
    private fun render(view:View,scene:Boolean=false):Bitmap {
        val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);val c=Canvas(b)
        if(scene) {
            val w=view.width.toFloat();val h=view.height.toFloat()
            val p=Paint().apply{shader=LinearGradient(0f,0f,0f,h,Color.rgb(139,168,180),Color.rgb(206,204,170),Shader.TileMode.CLAMP)}
            c.drawRect(0f,0f,w,h,p);p.shader=null;p.color=Color.rgb(76,102,91)
            c.drawPath(Path().apply{moveTo(0f,h*.68f);lineTo(w*.3f,h*.38f);lineTo(w*.6f,h*.58f);lineTo(w*.78f,h*.44f);lineTo(w,h*.7f);lineTo(w,h);lineTo(0f,h);close()},p)
        }
        view.draw(c);return b
    }
    private fun reading()=MeterReading(10.3,.18,0.0,1,100,10_000_000,2f,MeteringSource.YUV_PREVIEW)
    @Test fun normalTapAndAccessibleClickOnlyShowTravelingArrowsWithoutOpeningADialog() {
        val state=MeterState(context);val view=InstrumentView(context,state);size(view,840,1800)
        val clean=render(view)
        val handle=LayoutGeometry.calculate(840,1800,view.layoutDensity(LayoutProfile.METER),false).zoneEntryHandle
        tap(view,handle);val start=render(view)
        assertNull(ShadowAlertDialog.getLatestAlertDialog());assertFalse(clean.sameAs(start))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));val held=render(view)
        assertTrue(start.sameAs(held))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));val moving=render(view)
        assertFalse(start.sameAs(moving));assertFalse(clean.sameAs(moving))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));val ended=render(view)
        assertTrue(clean.sameAs(ended))
        val provider=view.accessibilityNodeProvider
        assertTrue(provider.performAction(7,AccessibilityNodeInfo.ACTION_CLICK,null))
        val accessible=render(view);assertFalse(clean.sameAs(accessible));assertNull(ShadowAlertDialog.getLatestAlertDialog())
        listOf(clean,start,held,moving,ended,accessible).forEach(Bitmap::recycle)
    }
    @Test fun handlesShareOneAlignmentAxisWithoutForcingPreviewSizesToMatch() {
        for((w,h) in listOf(840 to 1800,1800 to 840,720 to 1440,1440 to 720)) for(left in listOf(false,true)) {
            val state=MeterState(context).apply{handedness=if(left)Handedness.LEFT else Handedness.RIGHT}
            val normal=LayoutGeometry.calculate(w,h,2f,left)
            val zone=ZoneLayoutCalculator.calculate(w,h,2f,state)
            val clear=AutoMeterView(context,state,AutoMeterSession());size(clear,w,h)
            val handles=listOf(normal.zoneEntryHandle,zone.normalHandle,clear.normalHandle())
            if(w<h) {
                handles.forEach{assertEquals(w/2f,it.centerX(),.01f)}
                assertEquals(w/2f,clear.geometry().preview.centerX(),.01f)
                assertTrue(clear.geometry().zoom.top>clear.geometry().preview.bottom)
            } else {
                handles.forEach{assertEquals(h/2f,it.centerY(),.01f)}
                val frame=clear.geometry().preview;val handle=clear.normalHandle()
                assertTrue(frame.contains(handle))
                assertTrue(if(left)handle.centerX()<frame.centerX() else handle.centerX()>frame.centerX())
                assertTrue(if(left)clear.geometry().parameters.right<frame.left else clear.geometry().parameters.left>frame.right)
            }
            assertTrue(clear.geometry().preview.height()>zone.cameraFrame.height()||w>h)
        }
    }
    @Test fun bothHandsCanReachZoneAndClearWithoutConflictingLandscapeDirections() {
        for(left in listOf(false,true)) {
            val state=MeterState(context).apply{handedness=if(left)Handedness.LEFT else Handedness.RIGHT}
            val layout=MeterLayout(context,state)
            layout.listener=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(MeterLayout.Listener::class.java)){_,_,_->null} as MeterLayout.Listener
            size(layout,1800,840);render(layout).recycle()
            val normal=layout.instrumentView
            val handle=LayoutGeometry.calculate(1800,840,normal.layoutDensity(LayoutProfile.METER),left).zoneEntryHandle
            fun swipe(sign:Float) {
                touch(normal,MotionEvent.ACTION_DOWN,handle.centerX(),handle.centerY())
                touch(normal,MotionEvent.ACTION_MOVE,handle.centerX()+sign*250,handle.centerY())
                touch(normal,MotionEvent.ACTION_UP,handle.centerX()+sign*250,handle.centerY())
            }
            swipe(ModeTransitionDirection.zoneEntrySign(left));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertTrue(layout.isZoneMode);assertFalse(layout.isAutoMode)
            layout.closeZoneMode();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));size(layout,1800,840);render(layout).recycle()
            swipe(ModeTransitionDirection.normalEntrySign(left));assertTrue(layout.isAutoMode)
            layout.autoSession.beginManual(false);layout.autoSession.finishManual(reading())
            assertTrue(layout.autoSession.locked)
            assertTrue(layout.closeAutoMode());assertFalse("A completed manual lock must not trap the mode",layout.isAutoMode)
        }
    }
    @Test fun clearLanguageThemeAndHandednessMatrixUsesVisibleHitTargetsAndABlankTextlessButton() {
        val output=File(requireNotNull(System.getProperty("uiPreview.output")),"clear-navigation").apply{mkdirs()}
        for(landscape in listOf(false,true)) for(left in listOf(false,true)) for(english in listOf(false,true)) for(dark in listOf(false,true)) {
            val state=MeterState(context).apply {
                handedness=if(left)Handedness.LEFT else Handedness.RIGHT
                menuLanguage=if(english)MenuLanguage.ENGLISH else MenuLanguage.CHINESE
                appTheme=if(dark)AppTheme.DARK else AppTheme.LIGHT
                exposureCompSteps=0;setExposureLockModeFromCoordinates(ExposureLockMode.APERTURE,5.0,-7.0)
                frameIndex=0;zoom=1.4f;previewZoom=1f
                cameraInfo=CameraUiInfo(focalLengthMm=4.5f,sensorWidthMm=6.4f,sensorHeightMm=4.8f,aperture=1.8f)
            }
            val session=AutoMeterSession().apply{accept(reading())};val view=AutoMeterView(context,state,session)
            val w=if(landscape)1800 else 840;val h=if(landscape)840 else 1800;size(view,w,h)
            val name="${if(landscape)"landscape" else "portrait"}-${if(left)"left" else "right"}-${if(dark)"dark" else "light"}-${if(english)"en" else "zh"}"
            fun save(suffix:String) {render(view,true).also{b->File(output,"clear-$name-$suffix.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
            val g=view.geometry()
            for(rect in listOf(g.preview,g.parameters,g.raw,g.settingsBar,g.zoom,view.normalHandle())) {
                assertTrue("$name $rect",rect.width()>0&&rect.height()>0&&rect.left>=0&&rect.top>=0&&rect.right<=w&&rect.bottom<=h)
            }
            save("live")
            assertEquals(if(english)"Manual meter" else "手动测光",view.accessibilityNodeProvider.createAccessibilityNodeInfo(7)!!.contentDescription.toString())
            var captures=0
            view.onRaw={if(!session.releaseManualLock()){captures++;session.beginManual(false)}}
            tap(view,g.raw);assertTrue(session.locked);assertTrue(session.continuousMetering)
            save("locked")
            assertEquals(if(english)"Metering locked" else "测光锁定",view.accessibilityNodeProvider.createAccessibilityNodeInfo(7)!!.contentDescription.toString())
            tap(view,g.raw);assertFalse(session.locked);assertEquals(1,captures)
            assertTrue(session.finishManual(reading()));assertFalse(session.locked)
            session.continuousMetering=false
            repeat(2){tap(view,g.raw);assertTrue(session.rawPending);session.finishManual(reading());assertFalse(session.locked)}
            assertEquals(3,captures)
            session.continuousMetering=true
            tap(view,g.settingsBar);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));save("settings")
            assertNotNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(20))
            tap(view,view.settingRow(3));assertFalse(session.showInterfaceText)
            tap(view,g.settingsBar);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            val hidden=render(view)
            val cx=g.raw.centerX().toInt();val cy=g.raw.centerY().toInt()
            for(dx in -16..16 step 4)for(dy in -16..16 step 4)assertEquals("$name: no crosshair or glyph may remain",InstrumentStyle.foreground(dark),hidden.getPixel(cx+dx,cy+dy))
            hidden.recycle();save("text-off")
            // Portrait zoom must increase to the right, including left-handed layouts.
            if(!landscape) {
                tap(view,RectF(g.zoom.left,g.zoom.top,g.zoom.left+10,g.zoom.bottom));val small=state.zoomControlValue
                tap(view,RectF(g.zoom.right-10,g.zoom.top,g.zoom.right,g.zoom.bottom));assertTrue(state.zoomControlValue>small)
            } else {
                tap(view,RectF(g.zoom.left,g.zoom.bottom-10,g.zoom.right,g.zoom.bottom));val small=state.zoomControlValue
                tap(view,RectF(g.zoom.left,g.zoom.top,g.zoom.right,g.zoom.top+10));assertTrue(state.zoomControlValue>small)
            }
        }
    }
}
