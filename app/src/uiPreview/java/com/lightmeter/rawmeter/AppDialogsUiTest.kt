package com.lightmeter.rawmeter

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppDialogsUiTest {
    private fun activity()=Robolectric.buildActivity(Activity::class.java).also{it.get().setTheme(R.style.Theme_Lightstop)}.setup().visible()
    private fun drain()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    private fun layout(dialog:AlertDialog):View {
        drain();val decor=dialog.window!!.decorView;val d=dialog.context.resources.displayMetrics.density
        val maxHeight=((dialog.context.resources.configuration.screenHeightDp-40)*d).toInt()
        decor.measure(View.MeasureSpec.makeMeasureSpec(dialog.window!!.attributes.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST))
        decor.layout(0,0,decor.measuredWidth,decor.measuredHeight);drain()
        for(id in listOf(AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL,AlertDialog.BUTTON_POSITIVE)) {
            dialog.getButton(id)?.takeIf{it.visibility==View.VISIBLE}?.let {
                val parent=it.parent as ViewGroup
                assertTrue("Dialog footer must not clip the top corners",it.top>=0)
                assertTrue("Dialog footer must not clip the bottom corners",it.bottom<=parent.height)
            }
        }
        return decor
    }
    private fun save(dialog:AlertDialog,name:String) {
        val decor=layout(dialog);val b=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(b));val output=File(requireNotNull(System.getProperty("uiPreview.output")),"framing-dialogs").apply{mkdirs()}
        File(output,"dialog-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
    @Test fun warningCheckboxAndConfirmCancelCallbacksSurviveTheSharedStyling() {
        for(dark in listOf(false,true))for(english in listOf(false,true)) {
            val controller=activity();val activity=controller.get();val state=MeterState(activity).apply{appTheme=if(dark)AppTheme.DARK else AppTheme.LIGHT}
            val checkbox=CheckBox(activity).apply{text=if(english)"Don't show again" else "不再提示"}
            var accepted=false;var canceled=0
            val dialog=AppDialogs.builder(activity,state).setTitle(if(english)"Smooth metering" else "平滑测光")
                .setMessage(if(english)"Clear will use YUV/ISP preview frames, including manual metering, to reduce capture pauses." else "开启后，clear 将使用 YUV/ISP 预览流进行测光，手动测光也会禁用 RAW，以减少捕获时的卡顿。")
                .setView(checkbox).setNegativeButton(if(english)"Cancel" else "取消"){_,_->canceled++}
                .setPositiveButton(if(english)"OK" else "确定"){_,_->accepted=checkbox.isChecked}.show()
            save(dialog,"warning-${if(dark)"dark" else "light"}-${if(english)"en" else "zh"}")
            assertEquals(InstrumentStyle.foreground(dark),dialog.findViewById<TextView>(android.R.id.message).currentTextColor)
            assertEquals(InstrumentStyle.foreground(dark),checkbox.currentTextColor)
            checkbox.isChecked=true;dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain()
            assertTrue(accepted);assertEquals(0,canceled);assertFalse(dialog.isShowing)
            val cancel=AppDialogs.builder(activity,state).setMessage("Cancel test").setNegativeButton("Cancel"){_,_->canceled++}.show()
            cancel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();drain();assertEquals(1,canceled)
            controller.pause().stop().destroy()
        }
    }
    @Test fun createThenShowKeepsValidationListenerAndScrollsLongCustomFormsInLandscape() {
        RuntimeEnvironment.setQualifiers("zh-rCN-w900dp-h360dp-xhdpi")
        val controller=activity();val activity=controller.get();val state=MeterState(activity).apply{appTheme=AppTheme.DARK}
        val fields=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        val name=EditText(activity).apply{hint="Film name"};fields.addView(name)
        repeat(12){fields.addView(EditText(activity).apply{hint="Parameter ${it+1}"})}
        var saves=0;var shown=0
        val dialog=AppDialogs.builder(activity,state).setTitle("Edit film parameters").setView(fields)
            .setNegativeButton("Cancel",null).setNeutralButton("Reset original",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener {
            shown++;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if(name.text.isBlank())name.error="Enter a name" else {saves++;dialog.dismiss()}
            }
        }
        dialog.show();save(dialog,"form-landscape-dark")
        assertEquals(1,shown);assertEquals(InstrumentStyle.foreground(true),name.currentTextColor)
        val scroll=fields.parent as ScrollView
        assertTrue("Custom fields must remain scrollable rather than disappearing below the window",scroll.canScrollVertically(1))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain();assertTrue(dialog.isShowing);assertEquals(0,saves)
        name.setText("Test film");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain();assertEquals(1,saves)
        controller.pause().stop().destroy()
    }
    @Test fun listSelectionUsesTheOriginalChoiceCallbackInBothThemes() {
        for(dark in listOf(false,true)) {
            val controller=activity();val activity=controller.get();val state=MeterState(activity).apply{appTheme=if(dark)AppTheme.DARK else AppTheme.LIGHT}
            var selected=-1
            val items=arrayOf("1/30","1/40","1/50","1/60","1/80","1/100","1/125","1/160","1/200")
            val dialog=AppDialogs.builder(activity,state).setTitle("Shutter speed").setSingleChoiceItems(items,1){d,index->selected=index;d.dismiss()}.setNegativeButton("Cancel",null).show()
            save(dialog,"choices-${if(dark)"dark" else "light"}")
            val list=dialog.listView;assertTrue(list.childCount>0)
            assertEquals(InstrumentStyle.foreground(dark),(list.getChildAt(0) as TextView).currentTextColor)
            list.performItemClick(list.getChildAt(2),2,2);drain();assertEquals(2,selected);assertFalse(dialog.isShowing)
            controller.pause().stop().destroy()
        }
    }
    @Test fun actualFilmEditorAndNestedConfirmationKeepTheirDataValidation() {
        val controller=activity();val activity=controller.get();val state=MeterState(activity).apply{appTheme=AppTheme.DARK}
        var saved:String?=null
        FilmLatitudeDialogs(activity,state).showCustomFilm {name,_,_->saved=name}
        val dialog=ShadowAlertDialog.getLatestAlertDialog();layout(dialog)
        fun editors(view:View):List<EditText> = if(view is EditText)listOf(view) else if(view is ViewGroup)(0 until view.childCount).flatMap{editors(view.getChildAt(it))} else emptyList()
        val inputs=editors(dialog.window!!.decorView);assertEquals(3,inputs.size)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain();assertNull(saved);assertTrue(dialog.isShowing)
        inputs[0].setText("Custom film");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain();assertEquals("Custom film",saved)
        var resets=0
        FilmReciprocityDialogs(activity,state).showForFilm("Test film",null,true,true,{fail("Reset must not save a new method")},{resets++})
        val editor=ShadowAlertDialog.getLatestAlertDialog();layout(editor)
        editor.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();drain()
        val canceled=ShadowAlertDialog.getLatestAlertDialog();assertNotSame(editor,canceled)
        canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();drain();assertEquals(0,resets);assertTrue(editor.isShowing)
        editor.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();drain()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain()
        assertEquals(1,resets);assertFalse(editor.isShowing)
        controller.pause().stop().destroy()
    }
    @Test fun renderActualClearNoticeAndFlashSettingsWithBothThemesAndLanguages() {
        for(dark in listOf(false,true))for(english in listOf(false,true)) {
            val controller=activity();val activity=controller.get()
            val state=MeterState(activity).apply{appTheme=if(dark)AppTheme.DARK else AppTheme.LIGHT;menuLanguage=if(english)MenuLanguage.ENGLISH else MenuLanguage.CHINESE}
            val clear=AutoMeterView(activity,state,AutoMeterSession())
            clear.measure(View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.EXACTLY));clear.layout(0,0,840,1800)
            fun tap(rect:android.graphics.RectF) {for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP))MotionEvent.obtain(0,20,action,rect.centerX(),rect.centerY(),0).also{clear.onTouchEvent(it);it.recycle()}}
            tap(clear.geometry().settingsBar);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));tap(clear.settingRow(4));drain()
            val warning=ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(warning.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility!=View.VISIBLE)
            save(warning,"clear-smooth-${if(dark)"dark" else "light"}-${if(english)"en" else "zh"}")
            warning.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain()
            FlashSettingsDialog.show(activity,state,FlashConfiguration(guideNumber=40.0,guideNumberReferenceIso=100,iso=100,powerDenominator=1,lossStops=0.0,distanceMeters=2.0)){}
            val flash=ShadowAlertDialog.getLatestAlertDialog()
            save(flash,"flash-${if(dark)"dark" else "light"}-${if(english)"en" else "zh"}")
            flash.dismiss();controller.pause().stop().destroy()
        }
    }
}
