package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Color
import android.graphics.Path
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FramingControlsUiTest {
    private val context get()=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Lightstop)
    private fun size(view:View,w:Int,h:Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
    }
    private fun tap(view:View,rect:RectF) {for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP))MotionEvent.obtain(0,20,action,rect.centerX(),rect.centerY(),0).also{view.onTouchEvent(it);it.recycle()}}
    private fun draw(view:View,name:String?=null) {
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
        if(name!=null) {
            val w=view.width.toFloat();val h=view.height.toFloat()
            val paint=Paint().apply{shader=LinearGradient(0f,0f,0f,h,Color.rgb(139,168,180),Color.rgb(206,204,170),Shader.TileMode.CLAMP)}
            canvas.drawRect(0f,0f,w,h,paint);paint.shader=null;paint.color=Color.rgb(76,102,91)
            canvas.drawPath(Path().apply{moveTo(0f,h*.7f);lineTo(w*.3f,h*.4f);lineTo(w*.6f,h*.6f);lineTo(w*.8f,h*.43f);lineTo(w,h*.75f);lineTo(w,h);lineTo(0f,h);close()},paint)
        }
        view.draw(canvas)
        if(name!=null) {
            val output=File(requireNotNull(System.getProperty("uiPreview.output")),"framing-dialogs").apply{mkdirs()}
            File(output,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        bitmap.recycle()
    }
    @Test fun clearHasWorkingRotationAndTheSameInlineFormatChoicesInBothOrientationsAndHands() {
        for(landscape in listOf(false,true))for(left in listOf(false,true))for(dark in listOf(false,true))for(english in listOf(false,true)) {
            val state=MeterState(context).apply {
                handedness=if(left)Handedness.LEFT else Handedness.RIGHT
                appTheme=if(dark)AppTheme.DARK else AppTheme.LIGHT
                menuLanguage=if(english)MenuLanguage.ENGLISH else MenuLanguage.CHINESE
                frameIndex=0
            }
            val view=AutoMeterView(context,state,AutoMeterSession())
            size(view,if(landscape)1800 else 840,if(landscape)840 else 1800)
            var rotations=0;var changes=0;view.onOrientation={rotations++};view.onControls={if(it)changes++}
            draw(view)
            tap(view,view.geometry().orientation);assertEquals(1,rotations)
            tap(view,view.geometry().format);assertTrue(view.formatMenuOpen);assertNull(ShadowAlertDialog.getLatestAlertDialog())
            val options=view.formatOptionRects();assertEquals(FrameFormat.ALL.size+1,options.size)
            options.forEach{assertTrue(view.geometry().preview.contains(it))}
            val suffix="${if(landscape)"landscape" else "portrait"}-${if(left)"left" else "right"}-${if(dark)"dark" else "light"}-${if(english)"en" else "zh"}"
            draw(view,"clear-formats-$suffix")
            tap(view,options[6]);assertEquals(6,state.frameIndex);assertFalse(view.formatMenuOpen)
            tap(view,view.geometry().format);val previous=state.frameLandscape
            tap(view,view.formatOptionRects().last());assertEquals(!previous,state.frameLandscape)
            assertEquals("Frame orientation must not rotate the interface",1,rotations)
            tap(view,view.geometry().format);draw(view)
            assertTrue(view.accessibilityNodeProvider.performAction(40+FrameFormat.ALL.lastIndex,AccessibilityNodeInfo.ACTION_CLICK,null))
            assertTrue(state.isUnframed);assertEquals(3,changes)
            tap(view,view.geometry().format);tap(view,view.geometry().orientation)
            assertFalse(view.formatMenuOpen);assertEquals(2,rotations)
        }
    }
    @Test fun normalAndClearShareFrameSelectionIncludingTheFinalCompositionToggle() {
        val state=MeterState(context);val controls=ViewfinderControls(state)
        for(index in FrameFormat.ALL.indices){controls.select(index);assertEquals(index,state.frameIndex)}
        val before=state.frameLandscape;controls.select(FrameFormat.ALL.size)
        assertEquals(!before,state.frameLandscape)
        assertEquals(if(state.frameLandscape)"竖拍" else "横拍",controls.label(FrameFormat.ALL.size))
        val normal=InstrumentView(context,state);size(normal,840,1800);draw(normal)
        assertTrue(normal.accessibilityNodeProvider.performAction(2,AccessibilityNodeInfo.ACTION_CLICK,null));draw(normal)
        assertTrue(normal.accessibilityNodeProvider.performAction(200+FrameFormat.ALL.lastIndex,AccessibilityNodeInfo.ACTION_CLICK,null))
        assertTrue(state.isUnframed)
    }
}
