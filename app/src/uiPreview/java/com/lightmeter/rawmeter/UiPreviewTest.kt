package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.log2

/** Opt-in, camera-free rendering of production Views, using Android's native Canvas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiPreviewTest {
    @Test fun renderLongExposureReadouts() {
        val output = File(requireNotNull(System.getProperty("uiPreview.output"))).apply { mkdirs() }
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Lightstop)
        for (dark in listOf(false, true)) for (left in listOf(false, true)) {
            val state = MeterState(context).apply {
                appTheme = if (dark) AppTheme.DARK else AppTheme.LIGHT
                handedness = if (left) Handedness.LEFT else Handedness.RIGHT
            }
            val geometry = LayoutGeometry.calculate(720, 1440, 2f, FrameFormat.ALL.first(), true, left)
            val renderer = InstrumentExposureRenderer(state) { 2f }
            val rowHeight = (geometry.shutterRow.bottom - geometry.apertureRow.top).toInt() + 24
            val bitmap = Bitmap.createBitmap(720, rowHeight * 4, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(InstrumentStyle.background(dark))
            for ((index, seconds) in listOf(1.0 / 8000, 0.33, 30.0, 3600.0).withIndex()) {
                canvas.save()
                canvas.translate(0f, index * rowHeight + 12f - geometry.apertureRow.top)
                renderer.draw(canvas, geometry, ExposureScaleCenters(14.0, log2(seconds)), 0f)
                canvas.restore()
            }
            File(output, "readouts-${if (left) "left" else "right"}-${if (dark) "dark" else "light"}.png")
                .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    @Test fun renderGallery() {
        val output = File(requireNotNull(System.getProperty("uiPreview.output"))).apply { mkdirs() }
        for ((variant, size) in linkedMapOf("portrait" to (420 to 900), "compact" to (360 to 720),
            "landscape-left-en" to (900 to 420), "large-text" to (420 to 900))) {
          RuntimeEnvironment.setQualifiers("zh-rCN-w${size.first}dp-h${size.second}dp-xhdpi")
          val app = RuntimeEnvironment.getApplication()
          val configuration = android.content.res.Configuration(app.resources.configuration).apply {
              fontScale = if (variant == "large-text") 1.3f else 1f
          }
          val context = ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.Theme_Lightstop)
          for (dark in listOf(false, true)) {
            val state = MeterState(context).apply {
                appTheme = if (dark) AppTheme.DARK else AppTheme.LIGHT
                menuLanguage = if (variant == "landscape-left-en") MenuLanguage.ENGLISH else MenuLanguage.CHINESE
                handedness = if (variant == "landscape-left-en") Handedness.LEFT else Handedness.RIGHT
            }
            val latitude = FilmLatitudeRepository(context)
            val reciprocity = FilmReciprocityRepository(context)
            val views = linkedMapOf<String, () -> View>(
                "meter" to { InstrumentView(context, state) },
                "zone" to { ZoneSystemView(context, state).apply { enter() } },
                "settings" to { SettingsView(context, state) },
                "tools" to { ToolsView(context, state) },
                "negative" to { FilmNegativeToolView(context, state) },
                "depth" to { DepthOfFieldView(context, state) },
                "latitude" to { LatitudeView(context, state, latitude) },
                "reciprocity" to { ReciprocityView(context, state, latitude, reciprocity) },
                "flash" to { FlashExposureView(context, state, FlashExposureRepository(context)) },
                "temperature" to { ColorTemperatureView(context, state) },
                "records" to { ParameterRecordToolView(context, state, ParameterRecordRepository(context)) },
                "cameras" to { CameraManagementView(context, state) },
                "about" to { InformationView(context, state) },
                "calibration" to { CalibrationView(context, state) },
                "negative-adjustments" to { FilmNegativeToolView(context, state).apply {
                    findButton(this, if (state.menuLanguage == MenuLanguage.ENGLISH) "Manual adjustments" else "手动调整")!!.performClick()
                } },
            )
            for ((name, create) in views) {
                val view = create()
                val fullPage = name in listOf("meter", "zone", "negative", "negative-adjustments", "about", "calibration")
                val w = if (!fullPage && size.first > size.second) 960 else size.first * 2
                val h = if (!fullPage && size.first < size.second) (size.second * 1.05f).toInt() else size.second * 2
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, w, h)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(80, 80, 78)) // Placeholder for the live camera, never a fake scene.
                view.draw(canvas)
                File(output, "$variant-$name-${if (dark) "dark" else "light"}.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
          }
        }
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findButton(view.getChildAt(index), text)?.let { return it }
        }
        return null
    }
}
