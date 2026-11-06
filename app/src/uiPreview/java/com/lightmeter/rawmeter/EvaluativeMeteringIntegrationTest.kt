package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.hardware.camera2.CaptureResult
import android.media.EvaluativeTestImage
import android.view.ContextThemeWrapper
import android.view.View
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCaptureResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EvaluativeMeteringIntegrationTest {
    @Test fun censoredYuvRetainsExposureMatchedQualityButCannotBecomeASceneReading() {
        for (level in listOf(0, 255)) {
            val image = EvaluativeTestImage(level)
            val stat = MeteringAnalysis.analyzeYuvPreview(image, result(iso = 200), characteristics,
                "0", MeteringMode.AVERAGE, CameraCalibrationStore(context), evaluativeViewport = viewport)!!
            assertNull(MeteringFusion.fuse(listOf(stat), MeteringSource.YUV_PREVIEW))
            val evidence = MeteringSignalEvidence.from(stat, MeteringSource.YUV_PREVIEW)!!
            val cameraEv = ExposurePreviewMath.cameraEv100(2.0, 10_000_000L, 200)!!
            assertEquals(cameraEv, evidence.cameraEv100, 1e-9)
            val session = AutoMeterSession()
            assertTrue(session.acceptSignalLimited(evidence))
            assertNull(session.reading)
            assertEquals(cameraEv + if (level == 0) -.8 else .8, session.recoveryCameraEv100!!, 1e-9)
            image.close()
        }
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private val viewport = EvaluativeMeteringViewport(1f, 1f, NormalizedSensorViewport.FULL)
    private val characteristics get() = ShadowCameraCharacteristics.newCameraCharacteristics()
    private fun result(iso: Int = 100): CaptureResult = ShadowCaptureResult.newCaptureResult().also {
        shadowOf(it).set(CaptureResult.SENSOR_EXPOSURE_TIME, 10_000_000L)
        shadowOf(it).set(CaptureResult.SENSOR_SENSITIVITY, iso)
        shadowOf(it).set(CaptureResult.LENS_APERTURE, 2f)
    }

    @Test fun displayAnalysisIgnoresBrightPixelsOutsideTheVisibleFilmFrame() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        for (y in 25 until 75) for (x in 25 until 75) bitmap.setPixel(x, y, Color.rgb(128,128,128))
        val cropped = viewport.copy(displayCrop = NormalizedSensorViewport(.25f, .25f, .75f, .75f))
        val stat = MeteringAnalysis.analyzePreview(bitmap, result(), characteristics, "0", MeteringMode.AVERAGE,
            CameraCalibrationStore(context), evaluativeViewport = cropped)!!
        val reading = MeteringFusion.fuse(listOf(stat), MeteringSource.ISP_PREVIEW)!!
        assertEquals(0.0, reading.clippedFraction, 0.0)
        assertTrue(reading.evaluative!!.highlightsConfirmed)
        assertEquals(0.0, reading.evaluative.exposureReductionEv, 0.0)
        assertNotNull(stat.globalInput)
        assertEquals(6144,stat.globalInput!!.sampleCount)
        assertEquals(0f,stat.globalInput.features[771],0f)
        assertEquals(0f,stat.globalInput.features[770],1e-6f)
        bitmap.recycle()
    }

    @Test fun yuvAndDisplayUseTheSameLinearBrightnessWithPaddedOffsetPlanes() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128,128,128)) }
        val store = CameraCalibrationStore(context)
        val display = MeteringAnalysis.analyzePreview(bitmap, result(), characteristics, "0", MeteringMode.AVERAGE,
            store, evaluativeViewport = viewport)!!
        val image = EvaluativeTestImage(false)
        val yuv = MeteringAnalysis.analyzeYuvPreview(image, result(), characteristics, "0", MeteringMode.AVERAGE,
            store, evaluativeViewport = viewport)!!
        assertEquals(display.ev100, yuv.ev100, 1e-8)
        assertEquals(display.evaluative!!.highlightEv100, yuv.evaluative!!.highlightEv100, 1e-8)
        assertArrayEquals(display.globalInput!!.features,yuv.globalInput!!.features,1e-6f)
        assertFalse(image.isClosed()) // Analyzer borrows; CompatibleLightMeter owns close().
        bitmap.recycle()
    }

    @Test fun aMissingPlaneByteRejectsTheFrameInsteadOfDroppingAnArea() {
        val image = EvaluativeTestImage(true)
        assertNull(MeteringAnalysis.analyzeYuvPreview(image, result(), characteristics, "0", MeteringMode.AVERAGE,
            CameraCalibrationStore(context), evaluativeViewport = viewport))
    }

    @Test fun absoluteEvUsesTheExposureMetadataOfItsOwnFrame() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128,128,128)) }
        val store = CameraCalibrationStore(context)
        fun analyze(iso: Int) = EvaluativeFrameAnalyzer.preview(bitmap, result(iso), characteristics, "0", store, viewport)!!
        assertEquals(3.0, analyze(100).ev100 - analyze(800).ev100, 1e-8)
        assertArrayEquals(analyze(100).globalInput!!.features,analyze(800).globalInput!!.features,0f)
        bitmap.recycle()
    }

    @Test fun automaticAndUserCompensationRemainSeparateAndAreAppliedOnce() {
        val state = MeterState(context)
        state.exposureCompSteps = 0
        val decision = EvaluativeMeteringAlgorithm.decide(12.0, 15.0)
        val reading = MeterReading(12.0, .18, 0.0, 1, 100, 10_000_000L, 2f, evaluative = decision)
        state.acceptNormalReading(reading)
        assertEquals(12.0, state.sceneEv100!!, 0.0)
        assertEquals(12.5, state.effectiveEv100!!, 0.0)
        state.exposureCompSteps = 6
        assertEquals(11.5, state.effectiveEv100!!, 0.0)
        assertEquals(11.5, Ev100Readouts.normal(state).value!!, 0.0)
        state.acceptNormalReading(reading.copy(sceneEv100 = 13.0, evaluative = null))
        assertEquals(12.0, state.effectiveEv100!!, 0.0)
        state.invalidateMeteringResults()
        assertNull(state.effectiveEv100)
        assertEquals(0.0, state.automaticMeteringReductionEv, 0.0)
    }

    @Test fun previewGeometryOrientationIsIndependentOfPhysicalRawOrientation() {
        val geometry = PreviewStreamGeometry("logical", 1, 90, 1, Rect(0,0,4000,3000),
            640,480, NormalizedSensorViewport.FULL)
        // Deliberately wrong RAW-domain aspect; the preview-domain orientation must win.
        val input = viewport.copy(sensorFrameAspect = 1f, screenFrameAspect = 1.5f)
        val crop = EvaluativeMeteringGeometry.yuvCrop(640,480,input,geometry)!!
        assertEquals(160f, crop.left, .01f)
        assertEquals(480f, crop.right, .01f)
        assertEquals(0f, crop.top, .01f)
        val zoomed = EvaluativeMeteringGeometry.yuvCrop(640,480,input.copy(zoom = 2f),geometry)!!
        assertEquals(240f, zoomed.left, .01f)
        assertEquals(120f, zoomed.top, .01f)
    }

    @Test fun incompleteYuvFieldOfViewRequiresDisplayFallback() {
        val geometry = PreviewStreamGeometry("logical", 1, 0, 1, Rect(0,0,4000,3000),
            640,480, NormalizedSensorViewport.FULL)
        assertNull(EvaluativeMeteringGeometry.yuvCrop(640,360,
            viewport.copy(sensorFrameAspect = 4f/3, screenFrameAspect = 4f/3), geometry))
    }

    @Test fun rawGlobalCropHonorsPreviewFieldAndZoomOnce() {
        val crop=EvaluativeMeteringGeometry.rawCrop(4000,3000,Rect(0,0,4000,3000),4f/3,2f,
            NormalizedSensorViewport(.25f,.25f,.75f,.75f))
        assertEquals(1500f,crop.left,.01f);assertEquals(1125f,crop.top,.01f)
        assertEquals(2500f,crop.right,.01f);assertEquals(1875f,crop.bottom,.01f)
    }

    @Test fun localMeteringHasNoGlobalMappingPayload() {
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        for (mode in listOf(MeteringMode.SPOT,MeteringMode.CENTER_WEIGHTED,MeteringMode.ANGLE)) {
            val stat=MeteringAnalysis.analyzePreview(bitmap,result(),characteristics,"0",mode,
                CameraCalibrationStore(context),evaluativeViewport=viewport)!!
            assertNull(stat.globalInput)
        }
        bitmap.recycle()
    }

    @Test fun globalIsTheFourthModeAndKeepsTheExistingSavedIdentifier() {
        val option = SettingsCatalog.sections.flatMap { it.items }.single { it.key == SettingKey.METERING_MODE }.options.last()
        assertEquals(MeteringMode.AVERAGE.name, option.value)
        assertEquals("全局测光", option.label.resolve(MenuLanguage.CHINESE))
        assertEquals("Global", option.label.resolve(MenuLanguage.ENGLISH))
        val state = MeterState(context)
        state.updateSetting(SettingKey.METERING_MODE, MeteringMode.AVERAGE.name)
        assertEquals(MeteringMode.AVERAGE, MeterState(context).meteringMode)
        val decision = EvaluativeMeteringAlgorithm.decide(12.0, 15.0)
        assertTrue(EvaluativeMeteringPresentation.message(decision, MenuLanguage.CHINESE).startsWith("全局测光"))
        assertTrue(EvaluativeMeteringPresentation.message(decision, MenuLanguage.ENGLISH).startsWith("Global metering"))
    }

    @Test fun zoneSettingsNeverOverwriteTheNormalGlobalChoice() {
        val state = MeterState(context)
        state.updateSetting(SettingKey.METERING_MODE, MeteringMode.AVERAGE.name)
        state.setZoneMeteringActive(true)
        assertEquals(MeteringMode.ANGLE, state.meteringMode)
        assertEquals(MeteringMode.ANGLE.name, state.settingValue(SettingKey.METERING_MODE))
        state.updateSetting(SettingKey.LANGUAGE, MenuLanguage.ENGLISH.name)
        assertEquals(MeteringMode.AVERAGE, MeterState(context).meteringMode)
        state.updateSetting(SettingKey.METERING_MODE, MeteringMode.SPOT.name)
        assertEquals(MeteringMode.SPOT, state.meteringMode)
        assertEquals(MeteringMode.AVERAGE, MeterState(context).meteringMode)
        state.updateSetting(SettingKey.METERING_PIPELINE, MeteringPipelineMode.FAST.name)
        assertEquals(MeteringMode.AVERAGE, MeterState(context).meteringMode)
        state.setZoneMeteringActive(false)
        assertEquals(MeteringMode.AVERAGE, state.meteringMode)
    }

    @Test fun productionLayoutSwitchesToAngleAndRestoresGlobalWithTheSameAngleSetting() {
        val state = MeterState(context).apply {
            meteringPipelineMode = MeteringPipelineMode.AUTO
            updateSetting(SettingKey.METERING_MODE, MeteringMode.AVERAGE.name)
            selectAngleMeteringDegrees(7)
        }
        val themed = ContextThemeWrapper(context, R.style.Theme_Lightstop)
        val layout = MeterLayout(themed, state, trackerFactory = ZoneMarkerTrackerFactory { _, _, callback ->
            StaticZoneMarkerTracker(callback)
        })
        val output = File(System.getProperty("uiPreview.output"), "global-metering").apply { mkdirs() }
        val finish = MeterLayout::class.java.getDeclaredMethod("finishZoneTransition", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        render(layout, File(output, "normal-before-zone.png"))
        finish.invoke(layout, 1f)
        assertTrue(layout.isZoneMode)
        assertEquals(MeteringMode.ANGLE, state.meteringMode)
        assertEquals(7, state.angleMeteringDegrees)
        render(layout, File(output, "zone-angle.png"))
        val settings = SettingsView(themed, state).apply { selectSection(SettingsSectionKey.METERING) }
        render(settings, File(output, "zone-angle-settings.png"))
        finish.invoke(layout, 0f)
        assertFalse(layout.isZoneMode)
        assertEquals(MeteringMode.AVERAGE, state.meteringMode)
        assertEquals(7, state.angleMeteringDegrees)
        assertEquals(MeteringMode.AVERAGE, MeterState(context).meteringMode)
        render(layout, File(output, "normal-restored.png"))
    }

    @Test fun globalAnalysisNeverProcessesAZonePointEvenIfAnInvalidRequestReachesIt() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val target = ZoneMeteringTarget(.5f, .5f, .5f, .5f, 1f, 1f)
        val store = CameraCalibrationStore(context)
        assertNull(MeteringAnalysis.analyzePreview(bitmap, result(), characteristics, "0", MeteringMode.AVERAGE,
            store, target = target, evaluativeViewport = viewport))
        val image = EvaluativeTestImage(false)
        assertNull(MeteringAnalysis.analyzeRaw(image, result(), characteristics, CameraUiInfo(), 1f, 1f,
            MeteringMode.AVERAGE, store, RawMeterPoint(4f, 4f, 1.0)))
        assertFalse(image.isClosed())
        bitmap.recycle()
    }

    @Test fun renderGlobalViewfinderAndSettingsInBothThemesAndLanguages() {
        val output = File(System.getProperty("uiPreview.output"), "global-metering").apply { mkdirs() }
        for (english in listOf(false, true)) for (dark in listOf(false, true)) {
            val state = MeterState(context).apply {
                meteringMode = MeteringMode.AVERAGE
                menuLanguage = if (english) MenuLanguage.ENGLISH else MenuLanguage.CHINESE
                appTheme = if (dark) AppTheme.DARK else AppTheme.LIGHT
            }
            val themed = ContextThemeWrapper(context, R.style.Theme_Lightstop)
            val view = InstrumentView(themed, state)
            render(view, File(output, "viewfinder-$english-$dark.png"))
            val settings = SettingsView(themed, state)
            settings.selectSection(SettingsSectionKey.METERING)
            render(settings, File(output, "settings-$english-$dark.png"))
        }
    }

    private fun render(view: View, file: File) {
        view.measure(View.MeasureSpec.makeMeasureSpec(840, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
        view.layout(0,0,840,1800)
        val bitmap = Bitmap.createBitmap(840,1800,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    }

}
