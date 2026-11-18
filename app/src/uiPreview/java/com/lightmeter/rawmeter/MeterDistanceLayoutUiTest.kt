package com.lightmeter.rawmeter

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeterDistanceLayoutUiTest {
    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Lightstop)
    private fun sampleDistance() = DistanceMeasurementState(
        DistanceEstimate(2.4, 2.2, 2.6, .9, DistanceQuality.HIGH, DistanceSource.FOCUS_CALIBRATED,
            1, "fixture", NormalizedPoint.CENTER, 5, true), DistanceMeasurementStatus.AVAILABLE,
    )

    @Test fun rulerAndActionGroupsFitAndMirrorOnSmallAndLargeScreens() {
        val state = MeterState(context)
        for ((w, h) in listOf(840 to 1800, 1800 to 840, 480 to 600, 720 to 480)) {
            for (left in listOf(false, true)) {
                state.handedness = if (left) Handedness.LEFT else Handedness.RIGHT
                state.showDistanceOnMeter = false
                val normalWithout = LayoutGeometry.calculate(w, h, 2f, left)
                val zoneWithout = ZoneLayoutCalculator.calculate(w, h, 2f, state)
                state.showDistanceOnMeter = true
                val normal = LayoutGeometry.calculate(w, h, 2f, left, distanceProgress = 1f)
                val zone = ZoneLayoutCalculator.calculate(w, h, 2f, state)
                assertTrue(normal.cameraFrame.height() < normalWithout.cameraFrame.height())
                assertTrue(zone.cameraFrame.height() < zoneWithout.cameraFrame.height())
                for ((ruler, shutter) in listOf(normal.distanceRow to normal.shutterRow, zone.distanceRow to zone.shutterRow)) {
                    assertTrue(ruler.height() in 1f..56.01f)
                    assertTrue(ruler.height() < shutter.height())
                }
                assertTrue(normal.distanceRow.bottom < normal.apertureRow.top)
                assertTrue(zone.distanceRow.bottom < zone.apertureRow.top)
                assertFalse(RectF.intersects(normal.moreButton, normal.zoomTrack))
                assertFalse(RectF.intersects(zone.settingsButton, zone.zoomTrack))
                for ((action, badge) in listOf(normal.meterButton to normal.ev100Badge, zone.markButton to zone.ev100Badge)) {
                    assertFalse(RectF.intersects(action, badge))
                    assertTrue(if (left) badge.centerX() < action.centerX() else badge.centerX() > action.centerX())
                }
                val circles = (0..2).map { zone.auxiliaryBadge(it, left) }
                for (circle in circles) {
                    assertTrue("${w}×$h, left=$left: $circle must fit ${zone.recordPanel}", zone.recordPanel.contains(circle))
                    assertTrue(circle.bottom < zone.markButton.top)
                    assertFalse(RectF.intersects(circle, zone.recordViewport))
                }
                assertFalse(RectF.intersects(circles[0], circles[1]))
                assertFalse(RectF.intersects(circles[1], circles[2]))
            }
        }
    }

    @Test fun formatSwitchAnimatesTheRulerWhileTheDepthToolStaysStill() {
        val durationScale = ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java, "sDurationScale")
        ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", 1f)
        try {
            for (zone in listOf(false, true)) for (landscape in listOf(false, true)) {
                val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
                val w = if (landscape) 1800 else 840
                val h = if (landscape) 840 else 1800
                val state = MeterState(activity).apply {
                    showDistanceOnMeter = false
                    if (landscape && zone) handedness = Handedness.LEFT
                    menuLanguage = MenuLanguage.CHINESE
                    cameraInfo = CameraUiInfo(focalLengthMm = 4.5f, sensorWidthMm = 6.4f, sensorHeightMm = 4.8f)
                    distanceMeasurementState = sampleDistance()
                }
                val layout = MeterLayout(activity, state)
                activity.setContentView(layout)
                layout.listener = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MeterLayout.Listener::class.java)) { _, _, _ -> null }
                    as MeterLayout.Listener
                fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
                size(layout, w, h)
                if (zone) {
                    layout.instrumentView.listener!!.onZoneEntryDrag(1f, false)
                    layout.instrumentView.listener!!.onZoneEntryDrag(1f, true)
                    advance(600)
                    assertTrue(layout.isZoneMode)
                }
                size(layout, w, h)
                layout.showTools()
                advance(330)
                val grid = ReflectionHelpers.getField<ToolsView>(layout, "toolsView")
                grid.listener!!.onToolRequested(ToolsCatalog.tools.first { it.id == ToolId.DEPTH_OF_FIELD })
                size(layout, w, h)
                val tool = layout.depthOfFieldView
                val panel = tool.parent as View
                fun toolBounds() = RectF(panel.x, panel.y, panel.x + panel.width, panel.y + panel.height)
                val initialBounds = toolBounds()
                val initialHeight = layout.textureView.height
                val density = layout.resources.displayMetrics.density
                val format = DepthOfFieldGeometryCalculator.calculate(tool.width, tool.height, density).frameControl
                RecordPreviewFixtures.tap(tool, format.centerX(), format.centerY())
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                val switch = requireNotNull(findSwitch(dialog.window!!.decorView))

                fun toggle(): ValueAnimator {
                    switch.performClick()
                    return requireNotNull(ReflectionHelpers.getField<ValueAnimator?>(layout, "distanceLayoutAnimator"))
                }
                fun assertCoexistence() {
                    assertEquals(initialBounds, toolBounds())
                    assertEquals(View.VISIBLE, tool.visibility)
                    val ruler = if (zone) ZoneLayoutCalculator.calculate(w, h, density, state).distanceRow
                        else LayoutGeometry.calculate(w, h, density, state.isLeftHanded,
                            state.distanceDisplayProgress, state.distanceBesideTools).distanceRow
                    assertFalse("Tools cover the ruler: $ruler and ${toolBounds()}", RectF.intersects(ruler, toolBounds()))
                }
                val opening = toggle()
                assertTrue(switch.isChecked)
                assertTrue(layout.canSampleMeterDistance())
                val name = (if (zone) "zone" else "normal") + if (landscape) "-landscape" else "-portrait"
                val sampledHeights = (0L..280L step 28L).mapIndexed { index, time ->
                    opening.currentPlayTime = time
                    render(layout, w, h, "$name-tools-${index.toString().padStart(2, '0')}")
                    assertCoexistence()
                    layout.textureView.height
                }
                opening.end()
                assertEquals(initialHeight, sampledHeights.first())
                assertTrue("$name must resize over multiple frames: $sampledHeights", sampledHeights.distinct().size >= 5)
                assertTrue(sampledHeights.last() < initialHeight)

                val closing = toggle()
                closing.currentPlayTime = 90L
                size(layout, w, h)
                val closingHeight = layout.textureView.height
                assertTrue(closingHeight in (sampledHeights.last() + 1) until initialHeight)
                assertCoexistence()
                val reversing = toggle()
                reversing.currentPlayTime = 0L
                size(layout, w, h)
                assertEquals(closingHeight, layout.textureView.height)
                assertCoexistence()
                reversing.end()
                dialog.dismiss()
                assertTrue(layout.isToolsOpen)
                assertCoexistence()
                activity.finish()
            }
        } finally {
            ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", durationScale)
        }
    }

    @Test fun formatDialogSwitchPersistsAndTheThreeModesRenderDistance() {
        val state = MeterState(context).apply {
            menuLanguage = MenuLanguage.CHINESE
            cameraInfo = CameraUiInfo(focalLengthMm = 4.5f, sensorWidthMm = 6.4f, sensorHeightMm = 4.8f)
            meteringMode = MeteringMode.ANGLE
            setExposureLockModeFromCoordinates(ExposureLockMode.APERTURE, 5.0, -7.0)
            setAppliedFlashConfiguration(FlashConfiguration(guideNumber = 40.0, distanceMeters = 2.0))
            distanceMeasurementState = sampleDistance()
        }
        var chosen: FrameFormat? = null
        DepthOfFieldDialogs(context, state, { chosen = it }, {}, {}, { state.showDistanceOnMeter = it })
            .showFrame(FrameFormat.ALL.first())
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val toggle = requireNotNull(findSwitch(dialog.window!!.decorView))
        toggle.performClick()
        assertTrue(toggle.isChecked)
        assertTrue(MeterState(context).showDistanceOnMeter)
        assertTrue(dialog.listView.performItemClick(View(context), 0, 0))
        assertEquals(FrameFormat.ALL.first(), chosen)

        val layout = MeterLayout(context, state).apply {
            listener = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MeterLayout.Listener::class.java)) { _, _, _ -> null }
                as MeterLayout.Listener
        }
        state.setAppliedFlashConfiguration(FlashConfiguration(guideNumber = 40.0, distanceMeters = 2.0))
        state.acceptNormalReading(MeterReading(10.3, .18, 0.0, 1, 100, 10_000_000, 2f, MeteringSource.RAW))
        layout.refresh()
        render(layout, 840, 1800, "normal-distance")
        layout.instrumentView.listener!!.onZoneEntryDrag(1f, false)
        layout.instrumentView.listener!!.onZoneEntryDrag(1f, true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue(layout.isZoneMode)
        render(layout, 840, 1800, "zone-distance")
        state.handedness = Handedness.LEFT
        state.appTheme = AppTheme.DARK
        layout.refresh(frameChanged = true)
        render(layout, 1800, 840, "zone-left-landscape")
        layout.closeZoneMode()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        layout.enterAutoMode()
        assertTrue(layout.isAutoMode)
        layout.autoSession.accept(MeterReading(10.3, .18, 0.0, 1, 100, 10_000_000, 2f, MeteringSource.YUV_PREVIEW))
        render(layout, 840, 1800, "clear-distance")
        val tool = DepthOfFieldView(context, state).apply { openWithMeterDefaults(5.0) }
        render(tool, 840, 1300, "distance-tool")
    }

    private fun findSwitch(view: View): Switch? {
        if (view is Switch) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findSwitch(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun size(view: View, width: Int, height: Int) {
        view.requestLayout()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun render(view: View, width: Int, height: Int, name: String) {
        size(view, width, height)
        view.background = null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(115, 143, 144))
        view.draw(canvas)
        val output = File(requireNotNull(System.getProperty("uiPreview.output")), "meter-distance").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
