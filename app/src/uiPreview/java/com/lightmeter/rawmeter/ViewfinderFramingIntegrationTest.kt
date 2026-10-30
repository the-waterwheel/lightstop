package com.lightmeter.rawmeter

import android.app.Activity
import android.graphics.*
import android.hardware.camera2.CaptureResult
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCaptureResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w420dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewfinderFramingIntegrationTest {
    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Lightstop)
    private fun state() = MeterState(context).apply {
        if (!frameLandscape) toggleFrameOrientation()
        selectFrame(0)
        landscape = false
        cameraInfo = CameraUiInfo(cameraId = selectedCameraId, focalLengthMm = 4f,
            sensorWidthMm = 6.4f, sensorHeightMm = 4.8f, sensorOrientationDegrees = 90,
            activeArray = Rect(0, 0, 4000, 3000), previewSize = Size(640, 480))
        zoom = 1f
        previewZoom = 1f
    }
    private fun layout(view: View, w: Int = 840, h: Int = 1800) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    @Test fun focalLengthAndAngleUseTheirOwnIndependentRegions() {
        val state = state()
        val focal = state.equivalentFrameFocalMm()!!
        val angle = state.angleMeteringRoiFraction()!!
        state.previewZoom = 4f
        assertEquals(focal, state.equivalentFrameFocalMm())
        assertEquals(angle * 4, state.angleMeteringIndicatorFraction()!!, .00001f)
        state.zoom = 2f
        assertTrue(kotlin.math.abs(focal * 2 - state.equivalentFrameFocalMm()!!) <= 1)
        assertEquals(angle, state.angleMeteringRoiFraction()!!, .00001f)
        state.selectFrame(6)
        assertEquals(angle, state.angleMeteringRoiFraction()!!, .00001f)
    }

    @Test fun portraitCompositionPersistsWithoutChangingDeviceLayoutOrientation() {
        val state = state()
        val landscape = state.landscape
        state.toggleFrameOrientation()
        state.selectFrame(4)
        assertFalse(state.frameLandscape)
        assertEquals(landscape, state.landscape)
        assertFalse(MeterState(context).frameLandscape)
        assertEquals(1f / state.frameFormat.landscapeAspect, state.frameAspect(), .00001f)
    }

    @Test fun realLayoutKeepsFullPreviewAndGlobalSensorCropIndependentOfInspection() {
        val state = state()
        val view = MeterLayout(context, state)
        layout(view)
        val before = view.evaluativeMeteringViewport()
        val bounds = Rect(view.textureView.left, view.textureView.top, view.textureView.right, view.textureView.bottom)
        state.previewZoom = 5f
        state.selectFrame(state.frameIndex)
        view.refresh(true)
        layout(view)
        val after = view.evaluativeMeteringViewport()
        assertTrue(before.sensorCrop!!.isCloseTo(after.sensorCrop!!))
        assertEquals(bounds, Rect(view.textureView.left, view.textureView.top, view.textureView.right, view.textureView.bottom))
        assertTrue(after.displayCrop.left < 0f)
        state.zoom = 2f
        val tighter = view.evaluativeMeteringViewport()
        assertEquals(before.sensorCrop.width / 2, tighter.sensorCrop!!.width, .0001f)
    }

    private fun pinch(view: View, frame: RectF) {
        val cx = frame.centerX(); val cy = frame.centerY()
        fun send(action: Int, spread: Float, count: Int) {
            val props = Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { MotionEvent.PointerCoords().apply {
                x = cx + if (it == 0) -spread else spread; y = cy; pressure = 1f; size = 1f
            } }
            MotionEvent.obtain(0L, System.nanoTime() / 1_000_000, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
                .also { view.onTouchEvent(it); it.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, frame.width() * .1f, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), frame.width() * .1f, 2)
        send(MotionEvent.ACTION_MOVE, frame.width() * .25f, 2)
        send(MotionEvent.ACTION_MOVE, frame.width() * .35f, 2)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), frame.width() * .35f, 2)
        send(MotionEvent.ACTION_UP, frame.width() * .35f, 1)
    }

    @Test fun pinchingNormalAndZoneOnlyChangesInspectionAndNeverCreatesAMarker() {
        for (zone in listOf(false, true)) {
            val state = state()
            val view = if (zone) ZoneSystemView(context, state).apply { enter() } else InstrumentView(context, state)
            layout(view)
            view.draw(Canvas(Bitmap.createBitmap(840, 1800, Bitmap.Config.ARGB_8888)))
            val frame = if (view is ZoneSystemView) view.calculatePreviewFrame(840, 1800) else
                LayoutGeometry.calculate(840, 1800, 2f, state.isLeftHanded).cameraFrame
            val focal = state.equivalentFrameFocalMm()
            pinch(view, frame)
            assertTrue("Pinch must magnify the preview", state.previewZoom > 1f)
            assertEquals(1f, state.zoom, 0f)
            assertEquals(focal, state.equivalentFrameFocalMm())
            if (view is ZoneSystemView) assertTrue(view.session.markers.isEmpty())
        }
    }

    private fun tap(view: View, rect: RectF) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(0, 10, action, rect.centerX(), rect.centerY(), 0).also {
                view.onTouchEvent(it); it.recycle()
            }
        }
    }

    @Test fun focalSliderChangesFramingWithoutChangingInspectionInEitherMode() {
        for (zone in listOf(false, true)) {
            val state = state().apply { previewZoom = 2f }
            val view = if (zone) ZoneSystemView(context, state).apply { enter() } else InstrumentView(context, state)
            layout(view)
            view.draw(Canvas(Bitmap.createBitmap(840, 1800, Bitmap.Config.ARGB_8888)))
            val track = if (zone) ZoneLayoutCalculator.calculate(840, 1800, 2f, state).zoomTrack else
                LayoutGeometry.calculate(840, 1800, 2f, false).zoomTrack
            tap(view, RectF(track.left, track.top + track.height() * .2f, track.right, track.top + track.height() * .21f))
            assertTrue(state.zoom > 1f)
            assertEquals(2f, state.previewZoom, 0f)
        }
    }

    @Test fun finalFormatMenuActionChangesCompositionInBothModes() {
        for (zone in listOf(false, true)) {
            val state = state()
            val view = if (zone) ZoneSystemView(context, state).apply { enter() } else InstrumentView(context, state)
            layout(view)
            view.draw(Canvas(Bitmap.createBitmap(840, 1800, Bitmap.Config.ARGB_8888)))
            val normalGeometry = LayoutGeometry.calculate(840, 1800, 2f, false)
            val formatButton = if (zone) ZoneLayoutCalculator.calculate(840, 1800, 2f, state).formatButton else normalGeometry.formatButton
            tap(view, formatButton)
            val options = if (zone) {
                val method = ZoneSystemView::class.java.declaredMethods.single { it.name == "formatOptionRects" }.apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                (method.invoke(view, ZoneLayoutCalculator.calculate(840, 1800, 2f, state)) as List<RectF>)
            } else InstrumentPresentation.formatOptionRects(normalGeometry, 2f)
            assertEquals(FrameFormat.ALL.size + 1, options.size)
            tap(view, options.last())
            assertFalse(state.frameLandscape)
            assertFalse(state.landscape)
        }
    }

    @Test fun normalAndZoneUseTheirOwnAvailableWindowInBothOrientations() {
        for ((w, h) in listOf(840 to 1800, 1800 to 840)) {
            val state = state().apply { landscape = w > h }
            val normal = LayoutGeometry.calculate(w, h, 2f, false)
            val zone = ZoneLayoutCalculator.calculate(w, h, 2f, state)
            val normalAspect = normal.cameraFrame.width() / normal.cameraFrame.height()
            val zoneAspect = zone.cameraFrame.width() / zone.cameraFrame.height()
            assertTrue("The two modes must have independently sized previews", kotlin.math.abs(normalAspect - zoneAspect) > .05f)
            assertEquals(normal.previewPanel.height() - 32f, normal.cameraFrame.height(), .01f)
            assertEquals(zone.previewPanel.height() - 24f, zone.cameraFrame.height(), .01f)
            for (frame in listOf(normal.cameraFrame, zone.cameraFrame)) {
                state.viewfinderAspect = frame.width() / frame.height()
                val source = state.sourcePreviewCrop()
                assertEquals(state.viewfinderAspect!!, source.width * state.previewDisplayAspect() / source.height, .0001f)
                val crop = state.framingCrop()
                assertTrue(crop.left < .0001f || crop.top < .0001f)
                val original = state.originalFramingCrop()
                val focal = state.equivalentFrameFocalMm()
                state.previewZoom = 5f
                assertTrue(original.isCloseTo(state.originalFramingCrop()))
                assertEquals(focal, state.equivalentFrameFocalMm())
                state.previewZoom = 1f
            }
        }
    }

    @Test fun noneMenuSelectionHidesFocalAndSharesSliderAndPinchInBothModes() {
        for (zone in listOf(false, true)) {
            val state = state()
            val view = if (zone) ZoneSystemView(context, state).apply { enter() } else InstrumentView(context, state)
            layout(view)
            val bitmap = Bitmap.createBitmap(840, 1800, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val normal = LayoutGeometry.calculate(840, 1800, 2f, false)
            val zoneGeometry = ZoneLayoutCalculator.calculate(840, 1800, 2f, state)
            tap(view, if (zone) zoneGeometry.formatButton else normal.formatButton)
            val options = if (zone) {
                val method = ZoneSystemView::class.java.declaredMethods.single { it.name == "formatOptionRects" }.apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                (method.invoke(view, zoneGeometry) as List<RectF>)
            } else InstrumentPresentation.formatOptionRects(normal, 2f)
            tap(view, options[FrameFormat.ALL.lastIndex])
            assertTrue(state.isUnframed)
            assertNull(state.equivalentFrameFocalMm())
            val track = if (zone) zoneGeometry.zoomTrack else normal.zoomTrack
            tap(view, RectF(track.left, track.top + track.height() * .7f, track.right, track.top + track.height() * .71f))
            val afterSlider = state.previewZoom
            assertTrue(afterSlider > 1f)
            assertEquals(afterSlider, state.zoomControlValue, 0f)
            assertEquals(1f, state.zoom, 0f)
            pinch(view, if (zone) zoneGeometry.cameraFrame else normal.cameraFrame)
            assertTrue(state.previewZoom > afterSlider)
            assertEquals(state.previewZoom, state.zoomControlValue, 0f)
            assertTrue(state.displayedFramingCrop().isCloseTo(NormalizedSensorViewport.FULL))
            if (view is ZoneSystemView) assertTrue(view.session.markers.isEmpty())
            bitmap.recycle()
        }
    }

    @Test fun noneGlobalMeteringFollowsVisibleWindowAndOriginalFrameCoordinates() {
        val state = state().apply { selectFrame(FrameFormat.ALL.lastIndex) }
        val view = MeterLayout(context, state)
        layout(view)
        val initial = view.evaluativeMeteringViewport()
        state.previewZoom = 3f
        val magnified = view.evaluativeMeteringViewport()
        assertTrue(magnified.displayCrop.isCloseTo(NormalizedSensorViewport.FULL))
        assertTrue(magnified.sensorCrop!!.isCloseTo(magnified.previewSensorCrop!!))
        assertEquals(initial.sensorCrop!!.width / 3, magnified.sensorCrop.width, .0001f)
        assertEquals(initial.sensorCrop.height / 3, magnified.sensorCrop.height, .0001f)
        assertTrue(initial.originalDisplayCrop!!.isCloseTo(state.sourcePreviewCrop()))
        assertTrue(magnified.originalDisplayCrop!!.isCloseTo(state.originalFramingCrop()))
        assertTrue(initial.previewSensorViewport!!.isCloseTo(magnified.previewSensorViewport!!))
    }

    @Test fun focalDisplayMatchesTheGrayFrameSensorAreaForEachWindowAndComposition() {
        val state = state()
        val view = MeterLayout(context, state)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(view)
        for ((w, h) in listOf(840 to 1800, 1800 to 840)) {
            state.landscape = w > h
            shadowOf(view.textureView.display).setRotation(if (w > h) Surface.ROTATION_90 else Surface.ROTATION_0)
            layout(view, w, h)
            for (portrait in listOf(false, true)) for (zoom in listOf(1f, 2f, 5f)) {
                if (state.frameLandscape == portrait) state.toggleFrameOrientation()
                state.zoom = zoom
                val crop = view.evaluativeMeteringViewport().sensorCrop!!
                val physicalDiagonal = kotlin.math.hypot(crop.width * 6.4, crop.height * 4.8)
                val equivalent35 = 4.0 * kotlin.math.hypot(36.0, 24.0) / physicalDiagonal
                assertEquals(state.frameFormat.focalLengthForFullFrameEquivalent(equivalent35), state.equivalentFrameFocalMm())
            }
        }
        activity.finish()
    }

    @Test fun rawSpotFollowsWindowZoomWhilePhysicalAngleKeepsItsSensorDiameter() {
        val roiMethod = MeteringAnalysis::class.java.declaredMethods.single { it.name == "rawMeterRoi" }.apply { isAccessible = true }
        val window = NormalizedSensorViewport(.1f, .2f, .7f, .8f)
        fun roi(zoom: Float, angle: Boolean) = roiMethod.invoke(MeteringAnalysis,
            2000, 1500, Rect(100, 150, 1900, 1350), 1.5f, zoom, .3f, null, angle, window) as Rect
        val initial = roi(1f, false)
        val zoomed = roi(4f, false)
        assertEquals(initial.width() / 4f, zoomed.width().toFloat(), 1f)
        assertEquals(initial.centerX().toFloat(), zoomed.centerX().toFloat(), 2f)
        assertEquals(initial.centerY().toFloat(), zoomed.centerY().toFloat(), 2f)
        assertEquals(roi(1f, true), roi(4f, true))
        assertEquals(820f, initial.exactCenterX(), 2f)
    }

    @Test fun offscreenFramingIsClippedByRoundedPreviewWhileItsOwnCornersStaySquare() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.BLUE) }
        val bounds = RectF(20f, 20f, 180f, 180f)
        ViewfinderOverlay.outside(canvas, bounds, 200, 200, Color.WHITE, 1f)
        ViewfinderOverlay.framing(canvas, bounds, NormalizedSensorViewport(-.3f, .2f, 1.3f, .8f), 1f)
        assertEquals(Color.WHITE, bitmap.getPixel(10, 100))
        assertEquals(Color.WHITE, bitmap.getPixel(20, 20))
        assertEquals(Color.BLUE, bitmap.getPixel(100, 100))
        assertNotEquals(Color.BLUE, bitmap.getPixel(100, 30))
        bitmap.recycle()
    }

    @Test fun globalIspReadsTheUnzoomedFrameAndPairsItsOwnTimestamp() {
        val handler = Handler(Looper.getMainLooper())
        val readings = mutableListOf<MeterReading>()
        val errors = mutableListOf<String>()
        val meter = CompatibleLightMeter(handler, CameraCalibrationStore(context), { _, en -> en },
            object : CompatibleLightMeterListener {
                override fun onCompatibleMeteringStarted(source: MeteringSource, frameCount: Int) = Unit
                override fun onCompatibleMeteringReading(reading: MeterReading) { readings += reading }
                override fun onCompatibleMeteringError(message: String) { errors += message }
                override fun onCompatibleMeteringCompleted(requiresPreviewOnlySession: Boolean) = Unit
            })
        val texture = object : TextureView(context) { override fun isAvailable() = true }
        layout(texture, 300, 400)
        val result = ShadowCaptureResult.newCaptureResult().also {
            shadowOf(it).set(CaptureResult.SENSOR_TIMESTAMP, 123L)
            shadowOf(it).set(CaptureResult.SENSOR_EXPOSURE_TIME, 10_000_000L)
            shadowOf(it).set(CaptureResult.SENSOR_SENSITIVITY, 100)
            shadowOf(it).set(CaptureResult.LENS_APERTURE, 2f)
        }
        var captured: Bitmap? = null
        val base = ViewfinderGeometry.framingCrop(.75f, 1.5f, 1f)
        val crop = ViewfinderGeometry.project(base, 5f)
        meter.measure(MeteringMode.AVERAGE, null, CompatibleMeteringContext(texture, handler, false,
            { true }, { if (it == 123L) result else null }, { ShadowCameraCharacteristics.newCameraCharacteristics() }, { "framing" },
            EvaluativeMeteringViewport(4f / 3, 1f, crop, previewZoom = 5f, sensorCrop = NormalizedSensorViewport.FULL,
                originalDisplayCrop = NormalizedSensorViewport(.1f, .35f, .9f, .65f)),
            captureFullPreview = { callback ->
                val bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                // All highlights lie outside the focal frame in the original image.
                for (y in 56 until 104) for (x in 0 until 120) bitmap.setPixel(x, y, Color.rgb(128, 128, 128))
                captured = bitmap
                callback(bitmap, 123L)
            }), forceProcessedPreview = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(1, readings.size)
        assertEquals(0.0, readings.single().clippedFraction, 0.0)
        assertTrue(captured!!.isRecycled)
    }

    @Test fun renderFramingPortraitAndLandscapeWithBothModes() {
        val output = File(System.getProperty("uiPreview.output"), "framing").apply { mkdirs() }
        for ((w, h) in listOf(840 to 1800, 1800 to 840)) for (zone in listOf(false, true)) {
            val state = state().apply { landscape = w > h }
            val view = if (zone) ZoneSystemView(context, state).apply { enter() } else InstrumentView(context, state)
            if (view is ZoneSystemView) for ((index, x) in listOf(.14f, .58f).withIndex()) {
                view.session.beginMarker(state.iso, x, .40f + index * .15f)
                view.session.completePending(MeterReading(9.0 + index * 2, .18, 0.0, 1, 100, 10_000_000L, 2f), state.iso, state.exposureLockMode)
            }
            layout(view, w, h)
            val frame = if (view is ZoneSystemView) view.calculatePreviewFrame(w, h) else
                LayoutGeometry.calculate(w, h, 2f, state.isLeftHanded).cameraFrame
            for (scenario in listOf("initial", "focal-2x", "inspect-3x", "portrait-frame", "none", "none-zoom")) {
                state.selectFrame(if (scenario.startsWith("none")) FrameFormat.ALL.lastIndex else 0)
                state.zoom = if (scenario == "focal-2x" || scenario == "inspect-3x") 2f else 1f
                state.previewZoom = if (scenario == "inspect-3x" || scenario == "none-zoom") 3f else 1f
                if ((scenario == "portrait-frame") == state.frameLandscape) state.toggleFrameOrientation()
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.save()
                canvas.clipRect(frame)
                canvas.scale(state.previewZoom, state.previewZoom, frame.centerX(), frame.centerY())
                // Simulate the same aspect-preserving source crop as the production GL renderer.
                state.viewfinderAspect = frame.width() / frame.height()
                val sourceCrop = state.sourcePreviewCrop(w > h)
                val scene = RectF(frame.centerX() - frame.width() / sourceCrop.width / 2,
                    frame.centerY() - frame.height() / sourceCrop.height / 2,
                    frame.centerX() + frame.width() / sourceCrop.width / 2,
                    frame.centerY() + frame.height() / sourceCrop.height / 2)
                canvas.save()
                canvas.translate(scene.left, scene.top)
                canvas.scale(scene.width() / frame.width(), scene.height() / frame.height())
                canvas.translate(-frame.left, -frame.top)
                val paint = Paint().apply { shader = LinearGradient(0f, frame.top, frame.right, frame.bottom,
                    intArrayOf(Color.rgb(92, 150, 180), Color.rgb(185, 203, 163), Color.rgb(65, 112, 94)), null, Shader.TileMode.CLAMP) }
                canvas.drawRect(frame, paint)
                paint.shader = null
                paint.color = Color.rgb(72, 108, 91)
                val mountain = Path().apply {
                    moveTo(frame.left, frame.bottom); lineTo(frame.left, frame.centerY())
                    lineTo(frame.left + frame.width() * .24f, frame.top + frame.height() * .32f)
                    lineTo(frame.left + frame.width() * .56f, frame.top + frame.height() * .63f)
                    lineTo(frame.left + frame.width() * .82f, frame.top + frame.height() * .43f)
                    lineTo(frame.right, frame.top + frame.height() * .6f); lineTo(frame.right, frame.bottom); close()
                }
                canvas.drawPath(mountain, paint)
                paint.color = Color.argb(70, 255, 255, 255); paint.strokeWidth = 1f
                for (i in 1..9) {
                    val x = frame.left + frame.width() * i / 10
                    val y = frame.top + frame.height() * i / 10
                    canvas.drawLine(x, frame.top, x, frame.bottom, paint)
                    canvas.drawLine(frame.left, y, frame.right, y, paint)
                }
                canvas.restore()
                canvas.restore()
                if (view is ZoneSystemView) view.session.markers.forEachIndexed { index, marker ->
                    marker.normalizedX = .5f + ((if (index == 0) .14f else .58f) - .5f) * state.previewZoom
                    marker.normalizedY = .5f + (.40f + index * .15f - .5f) * state.previewZoom
                }
                view.draw(canvas)
                File(output, "${if(zone) "zone" else "normal"}-${w}x${h}-$scenario.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        fun contact(name: String, labels: List<Pair<String, String>>, columns: Int, cellWidth: Int, cellHeight: Int) {
            val bitmap = Bitmap.createBitmap(columns * cellWidth, ((labels.size + columns - 1) / columns) * (cellHeight + 48), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(Color.rgb(238, 240, 241)) }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 40, 44); textSize = 19f; typeface = Typeface.DEFAULT_BOLD }
            labels.forEachIndexed { index, (label, file) ->
                val x = (index % columns) * cellWidth
                val y = (index / columns) * (cellHeight + 48)
                canvas.drawText(label, x + 14f, y + 30f, textPaint)
                val source = BitmapFactory.decodeFile(File(output, file).absolutePath)
                canvas.drawBitmap(source, null, RectF(x.toFloat(), y + 48f, (x + cellWidth).toFloat(), (y + 48 + cellHeight).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                source.recycle()
            }
            File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        contact("preview-initial.png", listOf("Normal · 初始取景" to "normal-840x1800-initial.png",
            "Zone · 初始取景" to "zone-840x1800-initial.png"), 2, 420, 900)
        contact("preview-zoom-comparison.png", listOf("焦距 1× · 观察 1×" to "normal-840x1800-initial.png",
            "焦距 2× · 观察 1×" to "normal-840x1800-focal-2x.png",
            "焦距 2× · 双指观察 3×" to "normal-840x1800-inspect-3x.png"), 3, 420, 900)
        contact("preview-landscape.png", listOf("Normal · 横屏" to "normal-1800x840-initial.png",
            "Zone · 横屏" to "zone-1800x840-initial.png"), 1, 900, 420)
        contact("preview-portrait-composition.png", listOf("Normal · 竖拍构图" to "normal-840x1800-portrait-frame.png",
            "Zone · 竖拍构图" to "zone-840x1800-portrait-frame.png"), 2, 420, 900)
        contact("preview-none.png", listOf("Normal · 无画幅" to "normal-840x1800-none.png",
            "Zone · 无画幅" to "zone-840x1800-none.png",
            "Normal · 无画幅 · 3×" to "normal-840x1800-none-zoom.png",
            "Zone · 无画幅 · 3×" to "zone-840x1800-none-zoom.png"), 4, 420, 900)
    }
}
