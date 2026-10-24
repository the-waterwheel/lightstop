package com.lightmeter.rawmeter

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.RectF
import android.hardware.camera2.CaptureResult
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.IdentityHashMap
import kotlin.math.abs

/** Opt-in, dependency-free device checks. Never packaged in the production application. */
class DeviceMeteringInstrumentation : Instrumentation() {
    private lateinit var activity: MainActivity
    private lateinit var state: MeterState
    private lateinit var layout: MeterLayout
    private lateinit var camera: CameraController
    private var suite = "baseline"
    private val results = JSONArray()
    private var failures = 0
    private val measureTimes = IdentityHashMap<MeterReading, Double>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        suite = arguments?.getString("suite") ?: "baseline"
        start()
    }

    override fun onStart() {
        var original: Controls? = null
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            state = field(activity, "state")
            layout = field(activity, "meterLayout")
            camera = field(activity, "cameraController")
            dismissCalibrationNotice()
            waitCameraReady()
            SystemClock.sleep(1_500)
            original = main { Controls(state.meteringMode, state.meteringPipelineMode, state.zoom,
                state.angleMeteringDegrees, state.landscape, state.exposureCompSteps, state.selectedCameraId,
                state.exposurePreviewMode) }
            main { state.exposureCompSteps = 0 }
            when (suite) {
                "stress" -> stressChecks()
                "recovery" -> recoveryChecks()
                "capability" -> capabilityChecks()
                else -> baselineChecks()
            }
        } catch (error: Throwable) {
            record("runner", error)
        } finally {
            if (original != null) runCatching { restore(original) }.onFailure { record("restore", it) }
            val report = JSONObject().put("suite", suite).put("failures", failures).put("cases", results)
            File(targetContext.filesDir, "device-validation-$suite.json").writeText(report.toString(2))
            finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", report.toString(2)); putInt("failures", failures) })
        }
    }

    private fun baselineChecks() {
        test("native_bayer_sampler_on_device") {
            val buffer = ByteBuffer.allocateDirect(8 * 8 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(64) { buffer.putShort(512) }
            buffer.rewind()
            val data = RawMeterBridge.sampleEvaluativeRaw(buffer, 0, buffer.limit(), 8, 8, 16, 2, 0,
                floatArrayOf(0f, 0f, 0f, 0f), 1024, 0, 0, 8, 8,
                doubleArrayOf(.21, .36, .36, .07), 0, 0, floatArrayOf(), floatArrayOf())
            check(data.size == 19 && data[2] == 16.0 && data[0] == 0.0)
            check(data.drop(3).all { abs(it - .5) < 1e-12 })
            JSONObject().put("samples", data[2]).put("luma", data[3])
        }
        test("all_normal_modes_use_real_raw") {
            pipeline(MeteringPipelineMode.AUTO)
            val readings = JSONArray()
            for (mode in listOf(MeteringMode.CENTER_WEIGHTED, MeteringMode.SPOT, MeteringMode.ANGLE, MeteringMode.AVERAGE)) {
                val reading = measure(mode)
                check(reading.source == MeteringSource.RAW)
                check((reading.evaluative != null) == (mode == MeteringMode.AVERAGE))
                readings.put(observation(mode.name, reading))
            }
            JSONObject().put("readings", readings)
        }
        test("global_formula_and_repetition") {
            val readings = (0 until 5).map { measure(MeteringMode.AVERAGE) }
            readings.forEach { reading ->
                val result = checkNotNull(reading.evaluative)
                val expected = result.highlightEv100?.let { (it - result.baseEv100 - 2.5).coerceIn(0.0, .5) } ?: 0.0
                check(abs(result.exposureReductionEv - expected) < 1e-9)
                check(abs(result.recommendedEv100 - result.baseEv100 - expected) < 1e-9)
            }
            JSONObject().put("readings", JSONArray(readings.map { observation("global", it) }))
                .put("baseSpreadEv", readings.maxOf { it.sceneEv100 } - readings.minOf { it.sceneEv100 })
        }
        test("global_zone_angle_and_shared_batch") {
            main { state.updateSetting(SettingKey.METERING_MODE, MeteringMode.AVERAGE.name) }
            transition(true)
            check(main { state.meteringMode == MeteringMode.ANGLE })
            check(targetContext.getSharedPreferences("raw_light_meter_state", 0).getString("metering_mode", "") == "AVERAGE")
            repeat(2) { index ->
                val before = main { layout.zoneView.session.markers.count { it.ev100 != null } }
                main {
                    val frame = layout.zoneView.calculatePreviewFrame(layout.width, layout.height)
                    val x = frame.left + frame.width() * (if (index == 0) .35f else .65f)
                    invoke(layout.zoneView, "beginMarkerAt", arrayOf(Float::class.javaPrimitiveType!!,
                        Float::class.javaPrimitiveType!!, RectF::class.java), x, frame.centerY(), frame)
                }
                waitFor("Zone point", 25_000) { main {
                    layout.zoneView.session.markers.count { it.ev100 != null } > before && !state.measuring
                } }
                waitIdle()
            }
            val ids = main { layout.zoneView.session.markers.map { it.id } }
            main { layout.listener!!.onZoneRemeasureAllRequested(ids) }
            waitFor("Zone batch", 25_000) { main { !state.measuring && !state.meteringInteractionBusy } }
            val points = main { JSONArray(layout.zoneView.session.markers.map {
                JSONObject().put("id", it.id).put("ev100", it.ev100).put("source", it.source?.name)
            }) }
            transition(false)
            check(main { state.meteringMode == MeteringMode.AVERAGE })
            check(measure(MeteringMode.AVERAGE).evaluative != null)
            JSONObject().put("points", points).put("normalRestored", true)
        }
        test("physical_angle_is_stable_across_display_zoom") {
            main { state.zoom = 1f; layout.listener!!.onControlsChanged(false) }
            val first = measure(MeteringMode.ANGLE)
            main { state.zoom = 2f; layout.listener!!.onControlsChanged(false) }
            SystemClock.sleep(500)
            val second = measure(MeteringMode.ANGLE)
            val global = measure(MeteringMode.AVERAGE)
            check(main { abs(layout.evaluativeMeteringViewport().zoom - 2f) < .001f })
            main { state.zoom = 1f; layout.listener!!.onControlsChanged(false) }
            JSONObject().put("angleAt1x", observation("angle", first)).put("angleAt2x", observation("angle", second))
                .put("angleDifferenceEv", second.sceneEv100 - first.sceneEv100).put("globalAt2x", observation("global", global))
        }
        test("landscape_rotation_preserves_raw_and_global_restore") {
            main { layout.listener!!.onOrientationToggle() }
            waitFor("landscape bounds", 8_000) { main { layout.width > layout.height } }
            SystemClock.sleep(800)
            val reading = measure(MeteringMode.AVERAGE)
            transition(true)
            check(main { state.meteringMode == MeteringMode.ANGLE })
            transition(false)
            check(main { state.meteringMode == MeteringMode.AVERAGE })
            main { layout.listener!!.onOrientationToggle() }
            waitFor("portrait bounds", 8_000) { main { layout.height > layout.width } }
            SystemClock.sleep(600)
            JSONObject().put("reading", observation("global", reading))
        }
        for ((mode, source) in listOf(MeteringPipelineMode.ISOLATED to MeteringSource.YUV_PREVIEW,
            MeteringPipelineMode.FAST to MeteringSource.ISP_PREVIEW)) {
            test("global_${source.name.lowercase()}") {
                pipeline(mode)
                val reading = measure(MeteringMode.AVERAGE)
                check(reading.source == source) { "Expected $source, got ${reading.source}" }
                check(reading.evaluative != null)
                observation("global", reading)
            }
        }
    }

    private fun stressChecks() {
        pipeline(MeteringPipelineMode.AUTO)
        for (mode in listOf(MeteringMode.SPOT, MeteringMode.CENTER_WEIGHTED, MeteringMode.ANGLE, MeteringMode.AVERAGE)) {
            test("controlled_saturation_${mode.name.lowercase()}") {
                val reference = measure(mode)
                // Keep the UI's requested exposure consistent so distance callbacks cannot
                // replace this stress selection with the ordinary neutral preview.
                val referenceProduct = reference.exposureTimeNs.toDouble() * reference.captureIso
                main {
                    state.exposurePreviewMode = ExposurePreviewMode.ON
                    state.sceneEv100 = reference.sceneEv100 - 15
                    invoke(activity, "updateExposurePreviewFromMeter")
                }
                waitFor("manual overexposure metadata", 8_000) {
                    val result: CaptureResult? = field(camera, "latestResult")
                    result?.get(CaptureResult.CONTROL_AE_MODE) == 0 &&
                        (result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.toDouble() ?: 0.0) *
                        (result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0) > referenceProduct * 16
                }
                SystemClock.sleep(700)
                val before = main { state.lastNormalReading }
                val start = SystemClock.elapsedRealtimeNanos()
                val ready = CountDownLatch(1)
                var failure: Throwable? = null
                var initialExposureProduct = 0.0
                var initialRequestId = 0
                val handler: Handler = field(camera, "cameraHandler")
                handler.post {
                    try {
                        val context = invoke(camera, "rawMeteringContext") as RawMeteringContext
                        val result = checkNotNull(context.latestResult)
                        val time = checkNotNull(result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                        val iso = checkNotNull(result.get(CaptureResult.SENSOR_SENSITIVITY))
                        initialExposureProduct = time.toDouble() * iso
                        send("stress input $mode exposureNs=$time iso=$iso")
                        invoke(camera, "pausePreviewForRawCapture")
                        val raw: RawLightMeter = field(camera, "rawMeter")
                        initialRequestId = field(raw, "nextMeasurementId")
                        val roi = main { if (mode == MeteringMode.ANGLE) state.angleMeteringRoiFraction() else null }
                        check(raw.start(context, main { layout.evaluativeMeteringViewport().sensorFrameAspect },
                            1f, mode, roi, null, null, ScreenToSensorCoordinateTransform(0, false)))
                    } catch (error: Throwable) { failure = error }
                    finally { ready.countDown() }
                }
                check(ready.await(5, TimeUnit.SECONDS)); failure?.let { throw it }
                val recovered = awaitReading(before)
                val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                measureTimes[recovered] = elapsed
                main { state.exposurePreviewMode = ExposurePreviewMode.OFF; camera.updateExposurePreview(null) }
                waitIdle()
                SystemClock.sleep(500)
                check(recovered.source == MeteringSource.RAW)
                check(recovered.exposureTimeNs.toDouble() * recovered.captureIso < initialExposureProduct / 8) {
                    "No actual short-exposure recovery occurred"
                }
                val raw: RawLightMeter = field(camera, "rawMeter")
                val recaptures = field<Int>(raw, "nextMeasurementId") - initialRequestId - 1
                check(recaptures > 0) { "No saturation recapture was triggered" }
                if (mode == MeteringMode.AVERAGE) check(recovered.evaluative!!.highlightsConfirmed)
                JSONObject().put("reading", observation(mode.name, recovered)).put("endToEndMs", elapsed)
                    .put("initialExposureProduct", initialExposureProduct).put("recaptures", recaptures)
            }
            main { state.exposurePreviewMode = ExposurePreviewMode.OFF; camera.updateExposurePreview(null) }
            SystemClock.sleep(700)
        }
    }

    private fun recoveryChecks() {
        pipeline(MeteringPipelineMode.AUTO)
        test("pending_measurement_background_and_resume") {
            measure(MeteringMode.AVERAGE)
            main { layout.listener!!.onMeasureRequested() }
            val pending: Boolean = field(camera, "meteringOperationActive")
            check(pending)
            shell("input keyevent KEYCODE_HOME")
            waitFor("background window", 10_000) { main { !activity.hasWindowFocus() } }
            send("BACKGROUND_READY: host must bring MainActivity to the foreground after HOME")
            // A background app cannot reliably launch itself on vendor Android builds.
            // The host performs the return, just as a user would tap the app icon.
            waitFor("host foreground return", 50_000) { main { activity.hasWindowFocus() } }
            waitCameraReady()
            val reading = measure(MeteringMode.AVERAGE)
            check(reading.source == MeteringSource.RAW)
            check(main { state.meteringMode == MeteringMode.AVERAGE && !state.measuring && !state.meteringInteractionBusy })
            JSONObject().put("pendingBeforeHome", pending).put("afterResume", observation("global", reading))
        }
        val descriptors = main { state.availableCameras.toList() }
        for (descriptor in descriptors) {
            test("camera_route_${descriptor.cameraId}") {
                main { layout.listener!!.onCameraSelected(descriptor.cameraId) }
                waitCameraReady()
                dismissInfoNotice()
                val reading = measure(MeteringMode.AVERAGE)
                check(reading.evaluative != null)
                observation(descriptor.cameraId, reading)
                    .put("advertisedRaw", descriptor.rawAvailable)
                    .put("role", descriptor.lensRole.name)
                    .put("focalLengthMm", main { state.cameraInfo.focalLengthMm })
            }
        }
    }

    private fun capabilityChecks() {
        pipeline(MeteringPipelineMode.AUTO)
        test("unconfirmed_raw_failures_do_not_change_the_selected_raw_workflow") {
            check(measure(MeteringMode.SPOT).source == MeteringSource.RAW)
            val confirmation: CameraWorkflowConfirmation = field(camera, "rawWorkflowConfirmation")
            val plan: CameraCombinationPlan = field(camera, "activeCombinationPlan")
            val fallbackReadings = JSONArray()
            val handler: Handler = field(camera, "cameraHandler")
            val raw: RawLightMeter = field(camera, "rawMeter")
            val listener: RawLightMeterListener = field(raw, "listener")
            try {
                setField(camera, "rawWorkflowConfirmation", CameraWorkflowConfirmationPolicy.unverified())
                repeat(3) {
                    val before = main { state.lastNormalReading }
                    val ready = CountDownLatch(1)
                    handler.post {
                        listener.onRawMeteringError("Device test: transient capture failure", MeteringMode.SPOT, null, null)
                        ready.countDown()
                    }
                    check(ready.await(5, TimeUnit.SECONDS))
                    val fallback = awaitReading(before)
                    waitIdle()
                    SystemClock.sleep(800)
                    check(main { state.cameraInfo.rawAvailable })
                    check(field<CameraCombinationPlan>(camera, "activeCombinationPlan").id == plan.id)
                    fallbackReadings.put(fallback.source.name)
                }
                val reading = measure(MeteringMode.SPOT)
                check(reading.source == MeteringSource.RAW)
                JSONObject().put("injectedFailureCallbacks", 3).put("fallbackSources", fallbackReadings)
                    .put("preservedPlan", plan.id).put("nextRealCapture", observation("spot", reading))
            } finally {
                setField(camera, "rawWorkflowConfirmation", confirmation)
            }
        }
    }

    private fun measure(mode: MeteringMode): MeterReading {
        waitIdle()
        val before = main { state.lastNormalReading }
        main { check(state.updateSetting(SettingKey.METERING_MODE, mode.name)); layout.refresh() }
        val start = SystemClock.elapsedRealtimeNanos()
        main { layout.listener!!.onMeasureRequested() }
        val reading = awaitReading(before)
        measureTimes[reading] = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
        check(reading.sceneEv100.isFinite())
        waitIdle()
        return reading
    }

    private fun awaitReading(before: MeterReading?): MeterReading {
        waitFor("meter reading", 25_000) { main { state.lastNormalReading !== before && !state.measuring } }
        return main { checkNotNull(state.lastNormalReading) }
    }

    private fun waitIdle() = waitFor("meter idle", 20_000) { main { !state.measuring && !state.meteringInteractionBusy } }
    private fun waitCameraReady() {
        waitFor("camera preview and workflow probe", 25_000) { main {
            layout.textureView.isAvailable &&
                field<Boolean>(camera, "started") && invoke(camera, "getCaptureSession") != null &&
                field<CaptureResult?>(camera, "latestResult") != null &&
                !field<CameraCombinationWorkflowProbeRunner>(camera, "combinationWorkflowProbe").isActive
        } }
        SystemClock.sleep(800)
    }
    private fun pipeline(mode: MeteringPipelineMode) {
        waitIdle()
        main { state.updateSetting(SettingKey.METERING_PIPELINE, mode.name); layout.listener!!.onControlsChanged(true) }
        SystemClock.sleep(1_700)
        dismissInfoNotice()
    }
    private fun transition(zone: Boolean) {
        waitIdle()
        main { invoke(layout, "animateZoneTransition", arrayOf(Float::class.javaPrimitiveType!!), if (zone) 1f else 0f) }
        waitFor("mode transition", 5_000) { main { layout.isZoneMode == zone } }
        SystemClock.sleep(900)
    }

    private fun observation(mode: String, reading: MeterReading): JSONObject = JSONObject()
        .put("mode", mode).put("source", reading.source.name).put("sceneEv100", reading.sceneEv100)
        .put("clippedFraction", reading.clippedFraction).put("iso", reading.captureIso)
        .put("exposureNs", reading.exposureTimeNs).put("frames", reading.frameCount).put("endToEndMs", measureTimes[reading])
        .apply { reading.evaluative?.let { result ->
            put("highlightEv100", result.highlightEv100).put("reductionEv", result.exposureReductionEv)
            put("recommendedEv100", result.recommendedEv100).put("confirmed", result.highlightsConfirmed)
        } }

    private fun test(name: String, block: () -> JSONObject) {
        send("START $name")
        try { results.put(JSONObject().put("name", name).put("passed", true).put("details", block())); send("PASS $name") }
        catch (error: Throwable) { record(name, error) }
    }
    private fun record(name: String, error: Throwable) {
        failures++
        results.put(JSONObject().put("name", name).put("passed", false).put("error", error.stackTraceToString()))
        send("FAIL $name: $error")
    }
    private fun send(message: String) {
        android.util.Log.i("DeviceValidation", message)
        sendStatus(0, Bundle().apply { putString("stream", message + "\n") })
    }
    private fun waitFor(label: String, timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(30) }
        error("Timed out: $label; ${main { state.transientMessage }}")
    }
    private fun dismissCalibrationNotice() {
        SystemClock.sleep(500)
        uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("下次再说")?.firstOrNull()
            ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun dismissInfoNotice() {
        uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("确定")?.firstOrNull()
            ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun shell(command: String): String = uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().readText()
    }
    private fun restore(controls: Controls) {
        if (main { layout.isZoneMode }) transition(false)
        pipeline(controls.pipeline)
        if (main { state.selectedCameraId != controls.cameraId }) {
            main { layout.listener!!.onCameraSelected(controls.cameraId) }
            SystemClock.sleep(2_000)
        }
        main {
            state.updateSetting(SettingKey.METERING_MODE, controls.mode.name)
            state.zoom = controls.zoom
            state.selectAngleMeteringDegrees(controls.angle)
            state.exposureCompSteps = controls.compensation
            state.exposurePreviewMode = controls.exposurePreview
            camera.updateExposurePreview(null)
            if (state.landscape != controls.landscape) layout.listener!!.onOrientationToggle()
            layout.listener!!.onControlsChanged(true)
        }
    }
    private data class Controls(val mode: MeteringMode, val pipeline: MeteringPipelineMode, val zoom: Float,
        val angle: Int, val landscape: Boolean, val compensation: Int, val cameraId: String,
        val exposurePreview: ExposurePreviewMode)
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        var failure: Throwable? = null
        runOnMainSync { try { value = block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T
    private fun setField(owner: Any, name: String, value: Any) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)
    private fun invoke(owner: Any, name: String, types: Array<Class<*>> = emptyArray(), vararg args: Any): Any? =
        owner.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(owner, *args)
}
