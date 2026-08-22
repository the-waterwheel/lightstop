package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.view.TextureView

/** Live camera dependencies read by [CompatibleLightMeter] during one measurement. */
internal data class CompatibleMeteringContext(
    val textureView: TextureView?,
    val cameraHandler: Handler?,
    val trackingReaderAvailable: Boolean,
    val cameraReady: () -> Boolean,
    val latestResult: () -> CaptureResult?,
    val characteristics: () -> CameraCharacteristics?,
    val cameraId: () -> String,
)

internal interface CompatibleLightMeterListener {
    fun onCompatibleMeteringStarted(source: MeteringSource, frameCount: Int)
    fun onCompatibleMeteringReading(reading: MeterReading)
    fun onCompatibleMeteringError(message: String)
    fun onCompatibleMeteringCompleted(requiresPreviewOnlySession: Boolean)
}

/**
 * Runs the fast, ISP-processed metering pipeline.
 *
 * The preferred path consumes one YUV frame already produced by the preview session. If YUV is
 * absent or invalid, it takes one small TextureView bitmap instead. This component owns the YUV
 * timeout, reusable luma buffer and all compatible-measurement cancellation state; it never owns
 * the camera device, session, ImageReader or TextureView.
 *
 * YUV callbacks and analysis run on the camera handler. TextureView access and listener callbacks
 * run on the main handler. [cancel] may be called during camera shutdown to invalidate both paths.
 */
internal class CompatibleLightMeter(
    private val mainHandler: Handler,
    private val calibrationStore: CameraCalibrationStore,
    private val localized: (zh: String, en: String) -> String,
    private val listener: CompatibleLightMeterListener,
) {
    private data class YuvMeasurement(
        val id: Int,
        val meteringMode: MeteringMode,
        val meteringRoiFraction: Float?,
        val context: CompatibleMeteringContext,
        var attemptedFrames: Int = 0,
    )

    @Volatile
    var isMeasuring: Boolean = false
        private set

    @Volatile
    var yuvAvailable: Boolean = true
        private set

    @Volatile
    private var measurementId = 0
    @Volatile
    private var activeYuvMeasurement: YuvMeasurement? = null
    private val yuvFramePairer = TimestampedResultPairer<Image, CaptureResult>(
        releaseImage = Image::close,
        toleranceNs = YUV_PAIRING_TOLERANCE_NS,
    )
    private var lumaBuffer = ByteArray(0)
    private var yuvTimeout: Runnable? = null
    private var downgradeYuvSessionAfterSuccess = false

    fun preferredSource(trackingReaderAvailable: Boolean): MeteringSource =
        if (trackingReaderAvailable && yuvAvailable) {
            MeteringSource.YUV_PREVIEW
        } else {
            MeteringSource.ISP_PREVIEW
        }

    fun measure(
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget?,
        context: CompatibleMeteringContext,
        forceProcessedPreview: Boolean = false,
        meteringRoiFraction: Float? = null,
    ): Boolean {
        if (isMeasuring) return false
        isMeasuring = true
        val id = ++measurementId
        if (!forceProcessedPreview && target == null &&
            context.trackingReaderAvailable && yuvAvailable
        ) {
            startYuv(id, meteringMode, meteringRoiFraction, context)
        } else {
            mainHandler.post {
                startProcessedPreview(id, meteringMode, target, meteringRoiFraction, context)
            }
        }
        return true
    }

    /** Returns true when this callback belonged to a pending YUV measurement. */
    fun onImageAvailable(reader: ImageReader): Boolean {
        val measurement = activeYuvMeasurement ?: return false
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return true
        yuvFramePairer.offerImage(image.timestamp, image)?.let { pair ->
            processYuvFramePair(pair, measurement)
        }
        return true
    }

    /** Pairs preview metadata with the YUV buffer that has the identical sensor timestamp. */
    fun onCaptureResult(result: CaptureResult, fallbackSensorTimestamp: Long? = null) {
        val measurement = activeYuvMeasurement ?: return
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            ?: fallbackSensorTimestamp
            ?: return
        yuvFramePairer.offerResult(timestamp, result)?.let { pair ->
            processYuvFramePair(pair, measurement)
        }
    }

    /** Invalidates callbacks and releases only state owned by this component. */
    fun cancel(handler: Handler?, resetYuvAvailability: Boolean = true) {
        measurementId += 1
        activeYuvMeasurement = null
        yuvFramePairer.clear()
        isMeasuring = false
        cancelYuvTimeout(handler)
        if (resetYuvAvailability) {
            yuvAvailable = true
            downgradeYuvSessionAfterSuccess = false
        }
    }

    private fun startYuv(
        id: Int,
        meteringMode: MeteringMode,
        meteringRoiFraction: Float?,
        context: CompatibleMeteringContext,
    ) {
        val handler = context.cameraHandler
        if (handler == null || !context.cameraReady()) {
            mainHandler.post {
                startProcessedPreview(id, meteringMode, null, meteringRoiFraction, context)
            }
            return
        }
        val measurement = YuvMeasurement(id, meteringMode, meteringRoiFraction, context)
        yuvFramePairer.clear()
        activeYuvMeasurement = measurement
        Log.i(TAG, "YUV compatible metering started: mode=$meteringMode")
        mainHandler.post {
            if (isCurrent(id)) {
                listener.onCompatibleMeteringStarted(
                    MeteringSource.YUV_PREVIEW,
                    CompatibleMeteringPolicy.FRAME_COUNT,
                )
            }
        }
        scheduleYuvTimeout(measurement, handler)
    }

    private fun processYuvFramePair(
        pair: TimestampedResultPair<Image, CaptureResult>,
        measurement: YuvMeasurement,
    ) {
        try {
            analyzeYuv(pair.image, pair.result, measurement)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to analyze timestamp-paired compatible camera frame", error)
            fallbackToProcessedPreview(measurement)
        } finally {
            pair.image.close()
        }
    }

    private fun analyzeYuv(
        image: Image,
        result: CaptureResult,
        measurement: YuvMeasurement,
    ) {
        if (activeYuvMeasurement?.id != measurement.id || !isCurrent(measurement.id)) return
        measurement.attemptedFrames += 1
        val requiredBytes = image.width * image.height
        if (lumaBuffer.size != requiredBytes) lumaBuffer = ByteArray(requiredBytes)
        val characteristics = measurement.context.characteristics()
        val stat = if (characteristics != null) {
            MeteringAnalysis.analyzeYuvPreview(
                image,
                lumaBuffer,
                result,
                characteristics,
                measurement.context.cameraId(),
                measurement.meteringMode,
                calibrationStore,
                measurement.meteringRoiFraction,
            )
        } else {
            null
        }
        if (activeYuvMeasurement?.id != measurement.id || !isCurrent(measurement.id)) return
        if (stat != null) {
            val reading = MeteringFusion.fuse(listOf(stat), MeteringSource.YUV_PREVIEW)
            if (reading != null) {
                cancelYuvTimeout(measurement.context.cameraHandler)
                activeYuvMeasurement = null
                yuvFramePairer.clear()
                finishWithReading(measurement.id, reading)
                return
            }
        }
        if (CompatibleMeteringPolicy.decide(measurement.attemptedFrames, false) ==
            CompatibleFrameDecision.USE_PREVIEW
        ) {
            fallbackToProcessedPreview(measurement)
        }
    }

    private fun fallbackToProcessedPreview(measurement: YuvMeasurement) {
        if (activeYuvMeasurement?.id != measurement.id || !isCurrent(measurement.id)) return
        cancelYuvTimeout(measurement.context.cameraHandler)
        activeYuvMeasurement = null
        yuvFramePairer.clear()
        yuvAvailable = false
        downgradeYuvSessionAfterSuccess = true
        Log.w(TAG, "YUV compatible metering unavailable; falling back to displayed preview")
        mainHandler.post {
            startProcessedPreview(
                measurement.id,
                measurement.meteringMode,
                target = null,
                meteringRoiFraction = measurement.meteringRoiFraction,
                context = measurement.context,
            )
        }
    }

    private fun startProcessedPreview(
        id: Int,
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget?,
        meteringRoiFraction: Float?,
        context: CompatibleMeteringContext,
    ) {
        if (!isCurrent(id)) return
        val texture = context.textureView
        if (texture?.isAvailable != true || !context.cameraReady()) {
            finishWithError(id, localized("相机预览尚未就绪", "Camera preview is not ready"))
            return
        }
        if (context.latestResult() == null) {
            finishWithError(
                id,
                localized("正在等待相机曝光参数", "Waiting for camera exposure data"),
            )
            return
        }
        Log.i(TAG, "Displayed-preview compatible metering started: mode=$meteringMode")
        listener.onCompatibleMeteringStarted(
            MeteringSource.ISP_PREVIEW,
            CompatibleMeteringPolicy.FRAME_COUNT,
        )
        captureProcessedPreview(id, meteringMode, target, meteringRoiFraction, context)
    }

    private fun captureProcessedPreview(
        id: Int,
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget?,
        meteringRoiFraction: Float?,
        context: CompatibleMeteringContext,
    ) {
        if (!isCurrent(id)) return
        val texture = context.textureView
        val result = context.latestResult()
        val handler = context.cameraHandler
        if (texture?.isAvailable != true || result == null || handler == null) {
            finishWithError(id, localized("无法读取当前预览画面", "Unable to read the current preview"))
            return
        }
        val bitmap = try {
            texture.getBitmap(FALLBACK_BITMAP_SIZE, FALLBACK_BITMAP_SIZE)
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) {
            finishWithError(id, localized("无法读取当前预览画面", "Unable to read the current preview"))
            return
        }
        val accepted = handler.post {
            val stat = try {
                context.characteristics()?.let { characteristics ->
                    MeteringAnalysis.analyzePreview(
                        bitmap,
                        result,
                        characteristics,
                        context.cameraId(),
                        meteringMode,
                        calibrationStore,
                        target,
                        meteringRoiFraction,
                    )
                }
            } finally {
                bitmap.recycle()
            }
            mainHandler.post finishAnalysis@{
                if (!isCurrent(id)) return@finishAnalysis
                val reading = stat?.let {
                    MeteringFusion.fuse(listOf(it), MeteringSource.ISP_PREVIEW)
                }
                if (reading == null) {
                    finishWithError(
                        id,
                        localized(
                            "预览亮度或曝光参数不可用",
                            "Preview brightness or exposure data is unavailable",
                        ),
                    )
                } else {
                    finishWithReading(id, reading)
                }
            }
        }
        if (!accepted) {
            bitmap.recycle()
            finishWithError(id, localized("相机预览已关闭", "The camera preview has closed"))
        }
    }

    private fun finishWithReading(id: Int, reading: MeterReading) {
        if (!isCurrent(id)) return
        isMeasuring = false
        Log.i(
            TAG,
            "Compatible metering completed: source=${reading.source} " +
                "frames=${reading.frameCount} ev100=${reading.sceneEv100}",
        )
        val requiresPreviewOnly = downgradeYuvSessionAfterSuccess
        downgradeYuvSessionAfterSuccess = false
        mainHandler.post {
            if (id != measurementId) return@post
            listener.onCompatibleMeteringReading(reading)
            listener.onCompatibleMeteringCompleted(requiresPreviewOnly)
        }
    }

    private fun finishWithError(id: Int, message: String) {
        if (!isCurrent(id)) return
        isMeasuring = false
        activeYuvMeasurement = null
        yuvFramePairer.clear()
        Log.e(TAG, "Compatible metering failed: $message")
        mainHandler.post {
            if (id == measurementId) listener.onCompatibleMeteringError(message)
        }
    }

    private fun scheduleYuvTimeout(measurement: YuvMeasurement, handler: Handler) {
        cancelYuvTimeout(handler)
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (yuvTimeout !== timeout) return@Runnable
            yuvTimeout = null
            if (activeYuvMeasurement?.id == measurement.id) {
                fallbackToProcessedPreview(measurement)
            }
        }
        yuvTimeout = timeout
        handler.postDelayed(timeout, CompatibleMeteringPolicy.YUV_TIMEOUT_MS)
    }

    private fun cancelYuvTimeout(handler: Handler?) {
        val timeout = yuvTimeout ?: return
        yuvTimeout = null
        handler?.removeCallbacks(timeout)
    }

    private fun isCurrent(id: Int): Boolean = isMeasuring && id == measurementId

    private companion object {
        private const val TAG = "CompatibleLightMeter"
        private const val FALLBACK_BITMAP_SIZE = 96
        // Half the app's 30 fps preview ceiling: tolerates small buffer/metadata timestamp
        // offsets without pairing a frame with its neighbor's exposure metadata.
        private const val YUV_PAIRING_TOLERANCE_NS = 16_000_000L
    }
}
