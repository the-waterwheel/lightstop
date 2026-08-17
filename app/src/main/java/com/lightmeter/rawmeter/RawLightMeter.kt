package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.view.Surface
import kotlin.math.max

/** Immutable camera/session inputs used for one RAW measurement. */
internal data class RawMeteringContext(
    val device: CameraDevice,
    val session: CameraCaptureSession,
    val rawSurface: Surface,
    val handler: Handler,
    val latestResult: CaptureResult?,
    val characteristics: CameraCharacteristics,
    val cameraInfo: CameraUiInfo,
    val selectedPhysicalCameraId: String?,
)

internal interface RawLightMeterListener {
    fun onRawCaptureResult(result: CaptureResult)
    fun onRawMeteringStarted(frameCount: Int)
    fun onRawMeteringReading(reading: MeterReading)
    fun onRawMeteringError(
        message: String,
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget?,
    )
}

/** Device-independent RAW burst size limits. */
internal object RawMeteringPolicy {
    fun frameCount(captureIso: Int?): Int = when {
        captureIso == null -> MEDIUM_ISO_FRAME_COUNT
        captureIso < LOW_ISO_LIMIT -> LOW_ISO_FRAME_COUNT
        captureIso < HIGH_ISO_LIMIT -> MEDIUM_ISO_FRAME_COUNT
        else -> HIGH_ISO_FRAME_COUNT
    }

    private const val LOW_ISO_LIMIT = 500
    private const val HIGH_ISO_LIMIT = 1200
    private const val LOW_ISO_FRAME_COUNT = 1
    private const val MEDIUM_ISO_FRAME_COUNT = 2
    private const val HIGH_ISO_FRAME_COUNT = 3
}

/** Mutable state owned by [RawLightMeter] for exactly one RAW burst. */
private data class MeasurementAccumulator(
    val id: Int,
    val expectedFrames: Int,
    val frameAspect: Float,
    val zoom: Float,
    val meteringMode: MeteringMode,
    val target: ZoneMeteringTarget? = null,
    val previewReference: PreviewLumaReference? = null,
    val screenToSensorRotationDegrees: Int = 0,
    val startedAtNs: Long = System.nanoTime(),
    val framePairer: TimestampedResultPairer<Image, CaptureResult> =
        TimestampedResultPairer(Image::close),
    val stats: MutableList<MeteringFrameStat> = mutableListOf(),
    var submittedFrames: Int = 0,
    var completedFrames: Int = 0,
    var rawMeterPoint: RawMeterPoint? = null,
)

/**
 * Owns one high-accuracy RAW measurement from request submission through fused result.
 *
 * Only one full-size RAW request is kept in flight. An Image remains owned by the timestamp pairer
 * until matching metadata arrives, and is closed before the next request is submitted. This bounds
 * native camera-buffer memory on devices with large sensors.
 *
 * All methods except the read-only [isMeasuring] property are confined to the camera handler.
 * [cancel] must be called before its session or ImageReader is closed.
 */
internal class RawLightMeter(
    private val calibrationStore: CameraCalibrationStore,
    private val vignettingCalibrationStore: VignettingCalibrationStore,
    private val localized: (zh: String, en: String) -> String,
    private val listener: RawLightMeterListener,
) {
    @Volatile
    var isMeasuring: Boolean = false
        private set

    private var nextMeasurementId = 0
    private var activeMeasurement: MeasurementAccumulator? = null
    private var activeRequest: CaptureRequest? = null
    private var activeContext: RawMeteringContext? = null
    private var timeout: Runnable? = null

    fun start(
        context: RawMeteringContext,
        frameAspect: Float,
        zoom: Float,
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget?,
        previewReference: PreviewLumaReference?,
        screenToSensorRotationDegrees: Int,
    ): Boolean {
        if (isMeasuring) return false
        val count = RawMeteringPolicy.frameCount(
            context.latestResult?.get(CaptureResult.SENSOR_SENSITIVITY),
        )
        val accumulator = MeasurementAccumulator(
            id = ++nextMeasurementId,
            expectedFrames = count,
            frameAspect = frameAspect,
            zoom = zoom.coerceAtLeast(1f),
            meteringMode = meteringMode,
            target = target,
            previewReference = previewReference,
            screenToSensorRotationDegrees = screenToSensorRotationDegrees,
        )
        activeMeasurement = accumulator
        activeContext = context
        isMeasuring = true
        Log.i(
            TAG,
            "RAW metering started: frames=$count " +
                "captureIso=${context.latestResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: "unknown"} " +
                "zoom=${accumulator.zoom} sensorAspect=$frameAspect mode=$meteringMode " +
                "touchTarget=${target != null} ispReference=${previewReference != null}",
        )
        listener.onRawMeteringStarted(count)
        return try {
            activeRequest = buildCaptureRequest(context)
            scheduleTimeout(accumulator, context.handler)
            fillPipeline(accumulator)
            true
        } catch (error: Exception) {
            finishWithError(
                localized("无法开始测光，请重试", "Unable to start metering. Please try again"),
            )
            true
        }
    }

    /** Builds the same single-RAW request used by metering and vignetting calibration. */
    fun buildCaptureRequest(context: RawMeteringContext): CaptureRequest {
        val exposureTime = context.latestResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val sensitivity = context.latestResult?.get(CaptureResult.SENSOR_SENSITIVITY)
        val frameDuration = context.latestResult?.get(CaptureResult.SENSOR_FRAME_DURATION)
        val useManual = context.cameraInfo.manualSensorAvailable &&
            exposureTime != null && sensitivity != null
        return context.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(context.rawSurface)
            set(
                CaptureRequest.CONTROL_CAPTURE_INTENT,
                CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE,
            )
            set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            if (useManual) {
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
                set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity)
                frameDuration?.let {
                    set(CaptureRequest.SENSOR_FRAME_DURATION, max(it, exposureTime!!))
                }
            } else {
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_LOCK, true)
            }
            setSupportedAutoFocus(this, context.characteristics)
        }.build()
    }

    /** Acquires and either pairs or immediately closes the next full-size RAW image. */
    fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        val active = activeMeasurement
        if (active == null) {
            image.close()
            return
        }
        active.framePairer.offerImage(image.timestamp, image)?.let { pair ->
            processPair(active, pair)
        }
    }

    fun cancel(handler: Handler?) {
        nextMeasurementId += 1
        cancelTimeout(handler)
        activeMeasurement?.framePairer?.clear()
        activeMeasurement = null
        activeRequest = null
        activeContext = null
        isMeasuring = false
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val context = activeContext ?: return
            val effectiveResult = effectiveCaptureResult(result, context.selectedPhysicalCameraId)
            listener.onRawCaptureResult(effectiveResult)
            val timestamp = effectiveResult.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: result.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: return
            val active = activeMeasurement ?: return
            active.framePairer.offerResult(timestamp, effectiveResult)?.let { pair ->
                processPair(active, pair)
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            finishWithError(
                localized(
                    "相机未能完成本次测光，请重试",
                    "The camera could not complete this measurement. Please try again",
                ),
            )
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            finishWithError(
                localized(
                    "本次测光被相机中止，请重试",
                    "This measurement was stopped by the camera. Please try again",
                ),
            )
        }
    }

    private fun processPair(
        active: MeasurementAccumulator,
        pair: TimestampedResultPair<Image, CaptureResult>,
    ) {
        val context = activeContext
        try {
            if (context != null) {
                if (active.target != null && active.rawMeterPoint == null) {
                    active.rawMeterPoint = MeteringAnalysis.resolveRawMeteringPoint(
                        pair.image,
                        pair.result,
                        context.characteristics,
                        context.cameraInfo,
                        active.frameAspect,
                        active.zoom,
                        active.target,
                        active.previewReference,
                        active.screenToSensorRotationDegrees,
                    ).also { point ->
                        Log.i(
                            TAG,
                            "RAW touch target sensor=(${"%.1f".format(point.sensorX)}," +
                                "${"%.1f".format(point.sensorY)}) " +
                                "match=${if (point.matchScore.isFinite()) "%.3f".format(point.matchScore) else "geometry"}",
                        )
                    }
                }
                MeteringAnalysis.analyzeRaw(
                    pair.image,
                    pair.result,
                    context.characteristics,
                    context.cameraInfo,
                    active.frameAspect,
                    active.zoom,
                    active.meteringMode,
                    calibrationStore,
                    active.rawMeterPoint,
                    vignettingCalibrationStore,
                    applyVignettingCalibration = active.target != null,
                )?.let(active.stats::add)
            }
        } finally {
            pair.image.close()
            active.completedFrames += 1
        }
        when {
            active.stats.size >= active.expectedFrames -> finishWithReading(active)
            active.completedFrames >= active.expectedFrames -> finishWithError(
                localized("测光数据无效，请重试", "Metering data was invalid. Please try again"),
            )
            else -> fillPipeline(active)
        }
    }

    /** Keeps the rolling request window at one until all frames are processed. */
    private fun fillPipeline(active: MeasurementAccumulator) {
        while (activeMeasurement?.id == active.id &&
            active.submittedFrames < active.expectedFrames &&
            active.submittedFrames - active.completedFrames < PIPELINE_DEPTH
        ) {
            val submittedBefore = active.submittedFrames
            submitNextFrame(active)
            if (active.submittedFrames == submittedBefore) return
        }
    }

    private fun submitNextFrame(active: MeasurementAccumulator) {
        if (activeMeasurement?.id != active.id ||
            active.submittedFrames >= active.expectedFrames
        ) return
        val context = activeContext
        val request = activeRequest
        if (context == null || request == null) {
            finishWithError(
                localized("本次测光已失效，请重试", "This measurement expired. Please try again"),
            )
            return
        }
        active.submittedFrames += 1
        try {
            context.session.capture(request, captureCallback, context.handler)
        } catch (error: Exception) {
            finishWithError(
                localized("无法继续测光，请重试", "Unable to continue metering. Please try again"),
            )
        }
    }

    private fun finishWithReading(active: MeasurementAccumulator) {
        if (activeMeasurement?.id != active.id) return
        val reading = MeteringFusion.fuse(active.stats, MeteringSource.RAW)
        if (reading == null) {
            finishWithError(
                localized("测光数据无效，请重试", "Metering data was invalid. Please try again"),
            )
            return
        }
        val handler = activeContext?.handler
        cancelTimeout(handler)
        activeMeasurement = null
        activeRequest = null
        activeContext = null
        isMeasuring = false
        active.framePairer.clear()
        val elapsedMs = (System.nanoTime() - active.startedAtNs) / 1_000_000.0
        Log.i(
            TAG,
            "RAW metering completed: frames=${reading.frameCount} ev100=${reading.sceneEv100} " +
                "luma=${reading.rawLuma} clipped=${reading.clippedFraction} " +
                "elapsedMs=${"%.1f".format(elapsedMs)} pipelineDepth=$PIPELINE_DEPTH",
        )
        listener.onRawMeteringReading(reading)
    }

    private fun finishWithError(message: String) {
        val active = activeMeasurement ?: return
        val handler = activeContext?.handler
        cancelTimeout(handler)
        activeMeasurement = null
        activeRequest = null
        activeContext = null
        isMeasuring = false
        active.framePairer.clear()
        Log.e(TAG, "RAW metering failed: $message")
        listener.onRawMeteringError(message, active.meteringMode, active.target)
    }

    private fun scheduleTimeout(active: MeasurementAccumulator, handler: Handler) {
        cancelTimeout(handler)
        lateinit var timeoutTask: Runnable
        timeoutTask = Runnable {
            if (timeout !== timeoutTask) return@Runnable
            timeout = null
            if (activeMeasurement?.id == active.id) {
                finishWithError(localized("测光超时，请重试", "Metering timed out. Please try again"))
            }
        }
        timeout = timeoutTask
        handler.postDelayed(timeoutTask, METERING_TIMEOUT_MS)
    }

    private fun cancelTimeout(handler: Handler?) {
        val timeoutTask = timeout ?: return
        timeout = null
        handler?.removeCallbacks(timeoutTask)
    }

    @Suppress("DEPRECATION")
    private fun effectiveCaptureResult(
        result: TotalCaptureResult,
        selectedPhysicalCameraId: String?,
    ): CaptureResult = selectedPhysicalCameraId?.let(result.physicalCameraResults::get) ?: result

    private fun setSupportedAutoFocus(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
    ) {
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: intArrayOf()
        when {
            modes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                )
            modes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            else ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        }
    }

    private companion object {
        private const val TAG = "RawLightMeter"
        private const val PIPELINE_DEPTH = 1
        private const val METERING_TIMEOUT_MS = 8_000L
    }
}
