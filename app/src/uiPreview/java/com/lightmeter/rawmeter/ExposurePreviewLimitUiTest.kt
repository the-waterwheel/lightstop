package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.ContextThemeWrapper
import android.view.View
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExposurePreviewLimitUiTest {
    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Lightstop)

    @Test fun rangeFeedbackRemainsVisibleWithoutInformationBarAndDoesNotAlterMetering() {
        val state = MeterState(context).apply {
            exposurePreviewMode = ExposurePreviewMode.ON
            previewInformationBarMode = PreviewInformationBarMode.OFF
        }
        val reading = MeterReading(10.0, .18, 0.0, 1, 100, 10_000_000L, 2f, MeteringSource.RAW)
        state.acceptNormalReading(reading)
        val view = MeterLayout(context, state)
        val limit = ExposurePreviewLimit(-2.0, 2.9068906, true)
        view.setExposurePreviewLimit(limit)
        assertSame(limit, view.visibleExposurePreviewLimit())
        assertSame(reading, state.lastNormalReading)
        state.exposurePreviewMode = ExposurePreviewMode.OFF
        assertNull(view.visibleExposurePreviewLimit())
        state.exposurePreviewMode = ExposurePreviewMode.ON
        view.setExposurePreviewLimit(null)
        assertNull(view.visibleExposurePreviewLimit())
        assertSame(reading, state.lastNormalReading)
    }

    @Test fun limitIsHiddenBySettingsAndUsesClearSimulationToggleIndependentlyOfNormalPreview() {
        val state = MeterState(context).apply { exposurePreviewMode = ExposurePreviewMode.ON }
        val view = MeterLayout(context, state)
        val limit = ExposurePreviewLimit(-2.0, 2.9068906, true)
        view.setExposurePreviewLimit(limit)
        view.showSettings()
        assertNull(view.visibleExposurePreviewLimit())

        val clearView = MeterLayout(context, state)
        clearView.enterAutoMode()
        clearView.setExposurePreviewLimit(limit)
        clearView.autoSession.simulation = true
        assertSame(limit, clearView.visibleExposurePreviewLimit())
        clearView.autoSession.simulation = false
        assertNull(clearView.visibleExposurePreviewLimit())
        assertEquals(ExposurePreviewMode.ON, state.exposurePreviewMode)
    }

    @Test fun bannerFitsCompactAndWidePreviewAndClearsCompletely() {
        val output = File(System.getProperty("uiPreview.output", "build/ui-preview")).apply { mkdirs() }
        for (width in listOf(440, 840)) for (language in MenuLanguage.entries) {
            val bitmap = Bitmap.createBitmap(width, 180, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.GRAY)
            val banner = ExposurePreviewLimitBanner()
            banner.draw(canvas, RectF(0f, 0f, width.toFloat(), 180f), 2f,
                ExposurePreviewLimit(-2.0, 2.9068906, true), language)
            assertNotEquals(Color.GRAY, bitmap.getPixel(width / 2, 24))
            assertEquals(Color.GRAY, bitmap.getPixel(0, 24))
            File(output, "preview-limit-$width-${language.name}.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }
}
