package com.lightmeter.rawmeter

import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.media.RecordedRawTestImage
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.ContextThemeWrapper
import android.view.View
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
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
class ParameterRecordFramingTest {
    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Lightstop)
    private fun framing(mode: MeteringMode = MeteringMode.AVERAGE) = RecordedViewfinder("135", true, 64, 2f, 3f,
        NormalizedSensorViewport(.3f, .3f, .7f, .7f), mode, null, MeteringSource.RAW)
    private fun draft(repo: ParameterRecordRepository, mode: MeteringMode = MeteringMode.AVERAGE): ParameterCaptureDraft {
        val draft = RecordPreviewFixtures.draft(repo)
        return draft.copy(snapshot = draft.snapshot.copy(ev100 = -20.0, zonePoints = emptyList(), viewfinder = framing(mode)),
            rawGrid = RecordedRawGrid(2, 1, floatArrayOf(1f, 2f), 1f, referenceEv100 = 10.0))
    }
    private fun flush(io: ParameterRecordIoDispatcher) {
        val done = CountDownLatch(1)
        assertTrue(io.execute { done.countDown() })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun settleIndex() {
        // Android rename replaces an existing index; complete the same operation on Windows.
        val index = File(context.filesDir, "parameter_records/index.json")
        val pending = File(index.path + ".new")
        if (System.getProperty("os.name")?.startsWith("Windows") == true && pending.isFile) {
            java.nio.file.Files.move(pending.toPath(), index.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private fun render(view: View, w: Int = 840, h: Int = 1800): Bitmap {
        RecordPreviewFixtures.measure(view, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    @Test fun viewfinderAndAbsoluteRawAnchorRoundTripWithoutRebasingOnGlobalEv() {
        val repo = ParameterRecordRepository(context)
        val draft = draft(repo)
        val saved = repo.save(draft.copy(snapshot = draft.snapshot.copy(viewfinder = framing().copy(screenToSensorRotationDegrees = 270, screenToSensorMirrored = true))))
        settleIndex()
        val reloaded = ParameterRecordRepository(context).record(saved.categoryId, saved.id)!!
        assertEquals(saved.viewfinder, reloaded.viewfinder)
        assertEquals(-20.0, reloaded.ev100!!, 0.0)
        assertEquals(10.0, reloaded.rawEv100At(.1f, .5f)!!, .0001)
        assertEquals(11.0, reloaded.rawEv100At(1f, .5f)!!, .0001)
        assertEquals(1f, reloaded.rawGrid!!.rebasedForKnownPoints(-20.0,
            listOf(RecordedZonePoint(1, .1f, .5f, -50.0, MeteringSource.RAW))).referenceLuma, 0f)
    }

    @Test fun historyReadsDoNotWaitForTheBackgroundIndexWriterLock() {
        val repo = ParameterRecordRepository(context)
        val saved = repo.save(draft(repo))
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val read = CountDownLatch(1)
        val records = java.util.concurrent.atomic.AtomicInteger()
        val writer = Thread { synchronized(repo) { locked.countDown(); release.await(5, TimeUnit.SECONDS) } }.apply { start() }
        assertTrue(locked.await(5, TimeUnit.SECONDS))
        val reader = Thread {
            repo.category(saved.categoryId)
            repo.latestRecordedDistance()
            records.set(repo.categories().sumOf { it.records.size })
            read.countDown()
        }.apply { start() }
        try {
            assertTrue("History must remain readable while an index write owns the repository lock", read.await(2, TimeUnit.SECONDS))
            assertEquals(1, records.get())
        } finally {
            release.countDown(); writer.join(5000); reader.join(5000)
        }
    }

    @Test fun legacyIndicesOneThroughThreeKeepTheirOriginalCropsAndExposureFallback() {
        val repo = ParameterRecordRepository(context)
        val original = repo.save(RecordPreviewFixtures.draft(repo))
        settleIndex()
        val file = File(context.filesDir, "parameter_records/index.json")
        val json = JSONObject(file.readText())
        val record = json.getJSONArray("categories").getJSONObject(0).getJSONArray("records").getJSONObject(0)
        record.remove("viewfinder")
        record.getJSONObject("rawGrid").remove("referenceEv100")
        for (version in 1..3) {
            file.writeText(json.put("version", version).toString())
            val loadedRepo = ParameterRecordRepository(context)
            val loaded = loadedRepo.record(original.categoryId, original.id)!!
            assertNull(loaded.viewfinder)
            assertNull(loaded.rawGrid!!.referenceEv100)
            assertEquals(original.rawEv100At(.5f, .5f)!!, loaded.rawEv100At(.5f, .5f)!!, 0.0)
            assertEquals(original.apertureCoordinate, loaded.apertureCoordinate, 0.0)
            assertEquals(original.previewPath, loaded.previewPath)
            assertEquals(original.notes, loaded.notes)
        }
    }

    @Test fun unframedRecordsRoundTripAndMalformedNewMetadataDoesNotHideOldEvidence() {
        val repo = ParameterRecordRepository(context)
        val draft = draft(repo)
        val saved = repo.save(draft.copy(snapshot = draft.snapshot.copy(viewfinder = framing().copy(formatId = "none", frameCrop = null, focalLengthMm = null))))
        settleIndex()
        var loaded = ParameterRecordRepository(context).record(saved.categoryId, saved.id)!!
        assertEquals("none", loaded.viewfinder!!.formatId)
        assertNull(loaded.viewfinder.frameCrop)
        val file = File(context.filesDir, "parameter_records/index.json")
        val json = JSONObject(file.readText())
        json.getJSONArray("categories").getJSONObject(0).getJSONArray("records").getJSONObject(0)
            .getJSONObject("viewfinder").put("frameCrop", org.json.JSONArray(listOf(-1.0, 0.0, 1.0, 1.0)))
        file.writeText(json.toString())
        loaded = ParameterRecordRepository(context).record(saved.categoryId, saved.id)!!
        assertNull(loaded.viewfinder)
        assertEquals(saved.previewPath, loaded.previewPath)
        assertEquals(saved.ev100, loaded.ev100)
        assertEquals(10.0, loaded.rawEv100At(.1f, .5f)!!, .0001)
    }

    @Test fun recordControlsStayWithinTheWindowAcrossScreenSizes() {
        for ((w, h) in listOf(840 to 1800, 1800 to 840, 720 to 1280, 480 to 600)) {
            val g = ParameterRecordEditorGeometryCalculator.calculate(w, h, 2f)
            val rects = listOf(g.preview, g.status, g.film, g.addNote, g.notes, g.aperture, g.shutter, g.ei, g.save)
            for (rect in rects) {
                assertTrue("$w × $h: $rect", rect.width() > 0f && rect.height() > 0f)
                assertTrue("$w × $h: $rect", rect.left >= 0f && rect.top >= 0f && rect.right <= w && rect.bottom <= h)
            }
            assertTrue(g.notes.bottom <= g.aperture.top)
            assertTrue(g.aperture.bottom <= g.save.top)
        }
    }

    @Test fun editorAddsPointsOutsideTheGrayFrameInEveryMeteringModeAndSupportsUndo() {
        val repo = ParameterRecordRepository(context)
        for (mode in MeteringMode.entries) {
            val captured = draft(repo, mode)
            val view = ParameterRecordEditorView(context, MeterState(context)).apply { open(captured) }
            render(view).recycle()
            val rect = field<RectF>(view, "previewImageRect")
            RecordPreviewFixtures.tap(view, rect.left + .1f * rect.width(), rect.centerY())
            val edited = view.currentDraft()!!
            assertEquals(1, edited.snapshot.zonePoints.size)
            assertEquals(10.0, edited.snapshot.zonePoints.single().ev100!!, .0001)
            assertEquals(captured.snapshot.apertureCoordinate, edited.snapshot.apertureCoordinate, 0.0)
            val g = ParameterRecordEditorGeometryCalculator.calculate(840, 1800, 2f)
            RecordPreviewFixtures.tap(view, g.undo.centerX(), g.undo.centerY())
            assertTrue(view.currentDraft()!!.snapshot.zonePoints.isEmpty())
            view.setSaving(true)
            RecordPreviewFixtures.tap(view, rect.left + .1f * rect.width(), rect.centerY())
            assertTrue(view.currentDraft()!!.snapshot.zonePoints.isEmpty())
            view.clearDraft()
        }
        val missing = ParameterRecordEditorView(context, MeterState(context)).apply { open(draft(repo).copy(previewTempPath = "missing-record.jpg")) }
        render(missing).recycle()
        assertTrue(field<RectF>(missing, "previewImageRect").isEmpty)
    }

    @Test fun historyAddsAndUndoesRawPointsForGlobalCaptureWithoutChangingCapturedExposureOrJpeg() {
        val repo = ParameterRecordRepository(context)
        val captured = repo.save(draft(repo))
        val bytes = File(captured.previewPath).readBytes()
        val io = ParameterRecordIoDispatcher(Handler(Looper.getMainLooper()), "record-test-io")
        val state = MeterState(context).apply { meteringMode = MeteringMode.SPOT }
        val view = ParameterHistoryView(context, state, repo, io)
        RecordPreviewFixtures.measure(view, 840, 1800); view.open(); RecordPreviewFixtures.settle()
        val g = ParameterHistoryGeometryCalculator.calculate(840, 1800, 2f, 0f)
        repeat(2) { RecordPreviewFixtures.tap(view, g.content.left + 20f, g.content.top + 20f) }
        render(view).recycle()
        var image = field<RectF>(view, "imageDrawRect")
        RecordPreviewFixtures.tap(view, image.left + .1f * image.width(), image.centerY())
        assertTrue(repo.record(captured.categoryId, captured.id)!!.zonePoints.isEmpty())
        RecordPreviewFixtures.tap(view, g.imageActions.centerX(), g.imageActions.centerY())
        render(view).recycle()
        image = field(view, "imageDrawRect")
        RecordPreviewFixtures.tap(view, image.left + .1f * image.width(), image.centerY())
        flush(io)
        val edited = repo.record(captured.categoryId, captured.id)!!
        assertEquals(1, edited.zonePoints.size)
        assertEquals(10.0, edited.zonePoints.single().ev100!!, .0001)
        assertEquals(captured.apertureCoordinate, edited.apertureCoordinate, 0.0)
        assertEquals(captured.shutterCoordinate, edited.shutterCoordinate, 0.0)
        assertEquals(captured.ev100, edited.ev100)
        assertEquals(captured.viewfinder, edited.viewfinder)
        assertArrayEquals(bytes, File(captured.previewPath).readBytes())
        RecordPreviewFixtures.tap(view, g.imageActions.left + g.imageActions.width() * 5 / 6, g.imageActions.centerY())
        flush(io)
        assertTrue(repo.record(captured.categoryId, captured.id)!!.zonePoints.isEmpty())
        io.shutdown()
    }

    @Test fun syntheticRawAnchorUsesItsOwnExposureCalibrationAndRotatedImageCrop() {
        val result = ShadowCaptureResult.newCaptureResult().also {
            shadowOf(it).set(CaptureResult.SENSOR_EXPOSURE_TIME, 10_000_000L)
            shadowOf(it).set(CaptureResult.SENSOR_SENSITIVITY, 100)
            shadowOf(it).set(CaptureResult.LENS_APERTURE, 2f)
            shadowOf(it).set(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL, floatArrayOf(0f, 0f, 0f, 0f))
            shadowOf(it).set(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL, 4095)
        }
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics().also {
            shadowOf(it).set(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT, CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB)
            shadowOf(it).set(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, Rect(0, 0, 512, 384))
        }
        val grid = RecordedRawGridSampler.sample(RecordedRawTestImage(), result, chars, 4f / 3, 1f,
            ScreenToSensorCoordinateTransform(90, false), NormalizedSensorViewport(.25f, 0f, .75f, 1f), .5)!!
        assertEquals(64, grid.width); assertEquals(48, grid.height)
        val expectedDark = ExposureMath.log2(400.0) + ExposureMath.log2(1024.0 / 4095 / .18) + .5
        assertEquals(expectedDark, grid.referenceEv100!! + grid.relativeEvAt(.2f, 0f)!!, .0001)
        assertEquals(expectedDark + 1, grid.referenceEv100 + grid.relativeEvAt(.8f, 1f)!!, .0001)
        val color = RecordedRawGridSampler.sample(RecordedRawTestImage(3000, 1000, 500), result, chars, 4f / 3, 1f,
            ScreenToSensorCoordinateTransform(0, false), NormalizedSensorViewport.FULL, .5)!!
        val expectedColor = ExposureMath.log2(400.0) + ExposureMath.log2((.21 * 3000 + .72 * 1000 + .07 * 500) / 4095 / .18) + .5
        assertEquals(expectedColor, color.referenceEv100!! + color.relativeEvAt(.5f, .5f)!!, .0001)
    }

    @Test fun captureFreezesTheWindowAndFrameInsteadOfBakingTheGrayCropIntoJpeg() {
        for (unframed in listOf(false, true)) {
        val state = MeterState(context).apply {
            landscape = false
            selectFrame(if (unframed) FrameFormat.ALL.lastIndex else 0); if (!frameLandscape) toggleFrameOrientation()
            cameraInfo = CameraUiInfo(cameraId = selectedCameraId, focalLengthMm = 4f, sensorWidthMm = 6.4f,
                sensorHeightMm = 4.8f, sensorOrientationDegrees = 90, activeArray = Rect(0, 0, 4000, 3000), previewSize = Size(640, 480))
            zoom = 2f; previewZoom = 3f
        }
        val view = MeterLayout(context, state)
        RecordPreviewFixtures.measure(view, 840, 1800)
        var receive: ((Bitmap?) -> Unit)? = null
        var args: Array<out Any?>? = null
        view.listener = Proxy.newProxyInstance(MeterLayout.Listener::class.java.classLoader, arrayOf(MeterLayout.Listener::class.java)) { _, method, values ->
            when (method.name) {
                "onOriginalPreviewRequested" -> {
                    @Suppress("UNCHECKED_CAST") val callback = values!![0] as (Bitmap?) -> Unit
                    receive = callback
                }
                "onParameterCaptureRequested" -> args = values
            }
            null
        } as MeterLayout.Listener
        val expectedAspect = state.viewfinderAspect!!
        val expectedFrame = state.framingCrop()
        val expectedSensor = if (unframed) view.evaluativeMeteringViewport().sensorCrop else view.evaluativeMeteringViewport().previewSensorViewport
        MeterLayout::class.java.getDeclaredMethod("startParameterCapture").apply { isAccessible = true }.invoke(view)
        state.selectFrame(if (unframed) 0 else FrameFormat.ALL.lastIndex); state.previewZoom = 7f; state.landscape = true
        receive!!(Bitmap.createBitmap(750, 1000, Bitmap.Config.ARGB_8888))
        flush(field(view, "parameterIoDispatcher"))
        val snapshot = args!![1] as ParameterMeterSnapshot
        val image = BitmapFactory.decodeFile(args!![3] as String)
        assertEquals(if (unframed) "none" else "135", snapshot.viewfinder!!.formatId)
        if (unframed) assertNull(snapshot.viewfinder.frameCrop) else assertTrue(expectedFrame.isCloseTo(snapshot.viewfinder.frameCrop!!))
        assertEquals(3f, snapshot.viewfinder.inspectionZoom, 0f)
        assertEquals(90, snapshot.viewfinder.screenToSensorRotationDegrees)
        assertEquals(expectedAspect, image.width.toFloat() / image.height, .003f)
        assertTrue(kotlin.math.abs(image.width.toFloat() / image.height - 1.5f) > .1f)
        assertTrue(expectedSensor!!.isCloseTo(args!![6] as NormalizedSensorViewport))
        assertEquals(840, field<View>(view, "parameterRecordEditorView").measuredWidth)
        image.recycle()
        field<ParameterRecordIoDispatcher>(view, "parameterIoDispatcher").shutdown()
        }
    }

    @Test fun renderNewRecordConfirmationAndHistoryLayouts() {
        val output = File(System.getProperty("uiPreview.output"), "records-framing").apply { mkdirs() }
        val repo = ParameterRecordRepository(context)
        val original = draft(repo)
        val captured = original.copy(snapshot = original.snapshot.copy(ev100 = 10.0, zonePoints = listOf(
            RecordedZonePoint(1, .18f, .4f, 9.0, MeteringSource.RAW), RecordedZonePoint(2, .65f, .65f, 11.0, MeteringSource.RAW))))
        repo.save(captured)
        for ((w, h) in listOf(840 to 1800, 1800 to 840)) {
            val editor = ParameterRecordEditorView(context, MeterState(context)).apply {
                open(captured.copy(previewTempPath = repo.categories().first().records.first().previewPath))
            }
            val history = RecordPreviewFixtures.history(context, MeterState(context), repo, w, h, "history-detail")
            for ((name, view) in listOf("editor" to editor, "history" to history)) {
                val bitmap = render(view, w, h)
                File(output, "$name-${w}x$h.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            editor.clearDraft()
        }
        val sheet = Bitmap.createBitmap(840, 940, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 22f }
        for ((index, name) in listOf("editor", "history").withIndex()) {
            val source = BitmapFactory.decodeFile(File(output, "$name-840x1800.png").absolutePath)
            canvas.drawText(if (index == 0) "保存前确认 / 补点" else "历史回看 / 灰框", index * 420f + 14, 28f, paint)
            canvas.drawBitmap(source, null, RectF(index * 420f, 40f, (index + 1) * 420f, 940f), Paint(Paint.FILTER_BITMAP_FLAG))
            source.recycle()
        }
        File(output, "records-overview.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(value: Any, name: String): T = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value) as T
}
