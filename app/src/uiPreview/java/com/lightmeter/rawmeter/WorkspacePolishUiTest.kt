package com.lightmeter.rawmeter

import android.graphics.*
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Render the real native views over a synthetic scene; no camera or personal records. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspacePolishUiTest {
    private val context get()=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Lightstop)
    private val output get()=File(requireNotNull(System.getProperty("uiPreview.output")),"polish").apply{mkdirs()}
    private fun scene(c:Canvas,w:Float,h:Float) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader=LinearGradient(0f,0f,0f,h,Color.rgb(156,184,190),Color.rgb(225,215,188),Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,w,h,p);p.shader=null
        p.color=Color.rgb(235,225,197);c.drawCircle(w*.72f,h*.27f,w*.065f,p)
        p.color=Color.rgb(110,139,131)
        c.drawPath(Path().apply{moveTo(0f,h*.60f);lineTo(w*.28f,h*.37f);lineTo(w*.55f,h*.57f);lineTo(w*.8f,h*.43f);lineTo(w,h*.55f);lineTo(w,h);lineTo(0f,h);close()},p)
        p.color=Color.rgb(63,97,90)
        c.drawPath(Path().apply{moveTo(0f,h*.72f);lineTo(w*.35f,h*.57f);lineTo(w*.65f,h*.72f);lineTo(w,h*.60f);lineTo(w,h);lineTo(0f,h);close()},p)
        p.color=Color.rgb(188,201,184);c.drawRect(0f,h*.78f,w,h,p)
        p.color=Color.rgb(218,220,199);p.strokeWidth=2f
        for(i in 0..6)c.drawLine(w*(.12f+i*.03f),h*(.81f+i*.025f),w*(.72f-i*.01f),h*(.81f+i*.025f),p)
    }
    private fun save(view:View,name:String,withScene:Boolean=false) {
        val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(b);if(withScene)scene(canvas,view.width.toFloat(),view.height.toFloat())
        view.draw(canvas)
        File(output,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
    private fun seed(repo:ParameterRecordRepository) {
        for(group in 0..2) {
            repo.startCategory(1_793_232_000_000L+group*86_400_000L)
            for(i in 0..3) {
                val draft=RecordPreviewFixtures.draft(repo,i)
                val b=Bitmap.createBitmap(900,600,Bitmap.Config.ARGB_8888);scene(Canvas(b),900f,600f)
                File(draft.previewTempPath).outputStream().use{b.compress(Bitmap.CompressFormat.JPEG,95,it)};b.recycle()
                repo.save(draft.copy(snapshot=draft.snapshot.copy(viewfinder=RecordedViewfinder("135",true,50,1.6f,1f,
                    NormalizedSensorViewport(.18f,.18f,.82f,.82f),MeteringMode.AVERAGE,null,MeteringSource.RAW)),
                    filmName=listOf("Kodak PORTRA 400","ILFORD HP5 PLUS","Kodak GOLD 200")[group],
                    capturedAtEpochMs=1_793_232_000_000L+group*86_400_000L+i*60_000L,
                    notes=listOf("保留天空高光，树影作为暗部参考。", "Soft afternoon light. Keep the foreground detail.")))
            }
            repo.finishActiveCategory()
        }
    }
    @Test fun renderClearAndRecordsAcrossThemesLanguagesAndHandedLayouts() {
        val repo=ParameterRecordRepository(context);seed(repo)
        val captured=repo.categories()
        data class Variant(val name:String,val w:Int,val h:Int,val dark:Boolean=false,val english:Boolean=false,val left:Boolean=false)
        for(v in listOf(Variant("portrait",840,1800),Variant("dark-en",840,1800,true,true),
            Variant("landscape-en",1800,840,false,true),Variant("landscape-left",1800,840,true,false,true),Variant("compact",720,1280))) {
            val state=MeterState(context).apply {
                appTheme=if(v.dark)AppTheme.DARK else AppTheme.LIGHT
                menuLanguage=if(v.english)MenuLanguage.ENGLISH else MenuLanguage.CHINESE
                handedness=if(v.left)Handedness.LEFT else Handedness.RIGHT
                cameraInfo=CameraUiInfo(focalLengthMm=4.5f,sensorWidthMm=6.4f,sensorHeightMm=4.8f,aperture=1.8f,rawAvailable=true)
                apertureStep=ExposureStep.THIRD;shutterStep=ExposureStep.THIRD
                setExposureLockModeFromCoordinates(ExposureLockMode.APERTURE,5.0,-7.0);frameIndex=0;zoom=1.4f
            }
            val session=AutoMeterSession().apply{accept(MeterReading(10.3,.18,0.0,1,100,10_000_000,2f,MeteringSource.YUV_PREVIEW))}
            val clear=AutoMeterView(context,state,session);RecordPreviewFixtures.measure(clear,v.w,v.h)
            save(clear,"clear-${v.name}",true)
            clear.setSettingsExpanded(true);RecordPreviewFixtures.settle();save(clear,"clear-${v.name}-settings",true)
            clear.setSettingsExpanded(false);RecordPreviewFixtures.settle()
            session.showInterfaceText=false;save(clear,"clear-${v.name}-quiet",true)
            for(page in listOf("history","history-category","history-detail","history-detail-expanded")) {
                val history=RecordPreviewFixtures.history(context,state,repo,v.w,v.h,page)
                save(history,"$page-${v.name}")
                if(page=="history-detail") {
                    val g=ParameterHistoryGeometryCalculator.calculate(v.w,v.h,2f,0f)
                    for((action,y) in listOf(MotionEvent.ACTION_DOWN to g.data.centerY(),MotionEvent.ACTION_MOVE to g.data.top,MotionEvent.ACTION_UP to g.data.top)) {
                        MotionEvent.obtain(0,20,action,g.data.centerX(),y,0).also{history.onTouchEvent(it);it.recycle()}
                    }
                    save(history,"history-metadata-${v.name}")
                }
            }
            val editor=ParameterRecordEditorView(context,state).apply{open(RecordPreviewFixtures.draft(repo))}
            RecordPreviewFixtures.measure(editor,v.w,v.h);save(editor,"editor-${v.name}");editor.clearDraft()
            val tool=ParameterRecordToolView(context,state,repo);RecordPreviewFixtures.measure(tool,v.w,v.h);save(tool,"record-tool-${v.name}")
        }
        assertEquals("Presentation and playback must not modify saved evidence",captured,repo.categories())
    }
    @Test fun largerRecordTilesOpenTheTappedRecordInBothRows() {
        val repo=ParameterRecordRepository(context);seed(repo)
        val view=RecordPreviewFixtures.history(context,MeterState(context),repo,840,1800,"history-category")
        val categoryId=ReflectionHelpers.getField<String>(view,"categoryId")
        val records=repo.category(categoryId)!!.records
        RecordPreviewFixtures.tap(view,620f,200f)
        assertEquals(1,ReflectionHelpers.getField<Int>(view,"detailIndex"))
        assertEquals(records[1].id,ReflectionHelpers.getField<ParameterRecordEntry>(view,"detailWorkingRecord").id)
        assertTrue(view.navigateBack());RecordPreviewFixtures.settle()
        RecordPreviewFixtures.tap(view,200f,560f)
        assertEquals(2,ReflectionHelpers.getField<Int>(view,"detailIndex"))
        assertEquals(records[2].id,ReflectionHelpers.getField<ParameterRecordEntry>(view,"detailWorkingRecord").id)
    }
    @Test fun recordSetupRowsAndFooterAreReachableAcrossWindowSizes() {
        for((w,h) in listOf(840 to 1800,1800 to 840,720 to 1280,480 to 600,600 to 480)) {
            val repo=ParameterRecordRepository(context)
            val view=ParameterRecordToolView(context,MeterState(context),repo)
            RecordPreviewFixtures.measure(view,w,h)
            val g=ParameterRecordToolGeometryCalculator.calculate(w,h,2f)
            val rects=listOf(g.history,g.gpsRow,g.timeRow,g.rawRow,g.finishCategory,g.startStop)
            for(r in rects)assertTrue("$w × $h: $r",r.width()>0&&r.height()>0&&r.left>=0&&r.top>=0&&r.right<=w&&r.bottom<=h)
            assertTrue(g.rawRow.bottom<g.startStop.top)
            assertFalse(RectF.intersects(g.startStop,g.finishCategory))
            val before=repo.options.recordTime
            RecordPreviewFixtures.tap(view,g.timeRow.left+12f,g.timeRow.centerY())
            assertEquals(!before,repo.options.recordTime)
            RecordPreviewFixtures.tap(view,g.startStop.centerX(),g.startStop.centerY())
            assertTrue(view.recording)
            RecordPreviewFixtures.tap(view,g.finishCategory.centerX(),g.finishCategory.centerY())
            assertFalse(view.recording);assertNull(repo.activeCategoryId)
        }
    }
}
