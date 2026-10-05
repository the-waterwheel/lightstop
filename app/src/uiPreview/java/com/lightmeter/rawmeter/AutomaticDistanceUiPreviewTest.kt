package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
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
import java.io.File

/** Opt-in production Canvas previews and an unapplied Auto selection interaction. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomaticDistanceUiPreviewTest {
    private fun context() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Lightstop)

    @Test fun selectingAutoBeforeApplyingFlashStillNotifiesSamplingOwner() {
        val context = context()
        val state = MeterState(context)
        val repository = FlashExposureRepository(context)
        repository.clearApplied()
        repository.saveSelected(FlashConfiguration(distanceMeters = 2.0))
        val selections = mutableListOf<Boolean>()
        val view = FlashExposureView(context, state, repository).apply {
            listener = object : FlashExposureView.Listener {
                override fun onBackToToolsRequested() = Unit
                override fun onCloseRequested() = Unit
                override fun onSettingsRequested(configuration: FlashConfiguration) = Unit
                override fun onGuideNumberRequested(configuration: FlashConfiguration) = Unit
                override fun onMeteringIsoRequested(configuration: FlashConfiguration) = Unit
                override fun onAppliedFlashChanged(configuration: FlashConfiguration?) = Unit
                override fun onDistanceSelectionChanged(automatic: Boolean) { selections += automatic }
            }
            openPage()
        }
        size(view, 840, 945)
        val rect = ReciprocityGeometryCalculator.calculate(840, 945, context.resources.displayMetrics.density).scale
        val density = AdaptiveLayout.density(840, 945, context.resources.displayMetrics.density, LayoutProfile.TOOL)
        val titleWidth = minOf(94f * density, rect.width() * 0.29f)
        val x = if (state.isLeftHanded) rect.right - titleWidth / 2 else rect.left + titleWidth / 2
        val y = rect.centerY() + 23f * density
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(0L, 10L, action, x, y, 0).let { event ->
                assertTrue(view.onTouchEvent(event))
                event.recycle()
            }
        }
        assertEquals(listOf(true), selections)
        assertTrue(view.isAutomaticDistanceEnabled())
        assertNull(repository.selected(state.iso).distanceMeters)
        assertNull(repository.applied(state.iso))
    }

    @Test fun renderApproximateDistanceAndUnavailableAutoInBothThemes() {
        val context = context()
        val output = File(requireNotNull(System.getProperty("uiPreview.output"))).apply { mkdirs() }
        val estimate = DistanceEstimate(2.0, null, null, 0.25, DistanceQuality.LOW,
            DistanceSource.FOCUS_ESTIMATED, 1L, "0", NormalizedPoint.CENTER, 5, true)
        for (dark in listOf(false, true)) for (unavailable in listOf(false, true)) {
            val state = MeterState(context).apply {
                appTheme = if (dark) AppTheme.DARK else AppTheme.LIGHT
                menuLanguage = MenuLanguage.CHINESE
                sceneEv100 = 10.0
                distanceMeasurementState = if (unavailable) DistanceMeasurementState(
                    status = DistanceMeasurementStatus.UNSUPPORTED, diagnosticReason = "Fixed-focus lens")
                    else DistanceMeasurementState(estimate, DistanceMeasurementStatus.AVAILABLE)
            }
            val configuration = FlashConfiguration(distanceMeters = null)
            val repository = FlashExposureRepository(context).apply { clearApplied(); saveSelected(configuration) }
            val flash = FlashExposureView(context, state, repository).apply { openPage() }
            size(flash, 840, 945)
            val dial = FlashDistanceDialView(context, state).apply { setConfiguration(configuration, state.distanceMeasurementState) }
            size(dial, 840, 250)
            dial.setAnchor(RectF(280f, 210f, 560f, 245f))
            val bitmap = Bitmap.createBitmap(840, 1195, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(InstrumentStyle.background(dark))
            flash.draw(canvas)
            canvas.translate(0f, 945f)
            dial.draw(canvas)
            val name = "auto-${if (unavailable) "unavailable" else "estimate"}-${if (dark) "dark" else "light"}.png"
            File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    private fun size(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
}
