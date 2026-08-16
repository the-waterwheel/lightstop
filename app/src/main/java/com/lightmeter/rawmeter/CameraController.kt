package com.lightmeter.rawmeter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class CameraController(
    private val context: Context,
    private val callback: CameraControllerCallback,
) : TextureView.SurfaceTextureListener {

    private data class VignettingCapture(
        val id: Int,
        val cameraId: String,
        val images: MutableMap<Long, Image> = java.util.TreeMap(),
        val results: MutableMap<Long, CaptureResult> = java.util.TreeMap(),
    )

    private data class YuvMeasurement(
        val id: Int,
        val meteringMode: MeteringMode,
        var attemptedFrames: Int = 0,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraCatalog = CameraCatalog(cameraManager)
    private val calibrationStore = CameraCalibrationStore(context)
    private val vignettingCalibrationStore = VignettingCalibrationStore(context)
    private val measurementId = AtomicInteger(0)

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var started = false
    private var opening = false
    @Volatile
    private var requestedCameraId: String? = null
    private var textureView: TextureView? = null
    @Volatile
    private var cameraDevice: CameraDevice? = null
    @Volatile
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawReader: ImageReader? = null
    private var trackingReader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var selectedPhysicalCameraId: String? = null
    private var meteringPipelineMode = MeteringPipelineMode.AUTO
    private var sessionProfile: CameraSessionProfile? = null
    private var rawHardwareAvailable = false
    private var trackingHardwareAvailable = false
    private var useLogicalCameraFallback = false
    private val failureAttempts =
        mutableMapOf<Triple<CameraSessionProfile, CameraFailureKind, CameraFailureStage>, Int>()
    private var totalRecoveryAttempts = 0
    private var cameraGeneration = 0
    private var cameraFailureStage = CameraFailureStage.OPENING
    private var cameraInfo = CameraUiInfo()
    private var previewSize: Size? = null
    private var previewFpsRange: Range<Int>? = null
    @Volatile
    private var latestResult: CaptureResult? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    @Volatile
    private var activeMeasurement: MeasurementAccumulator? = null
    private var activeRawRequest: CaptureRequest? = null
    @Volatile
    private var activeVignettingCapture: VignettingCapture? = null
    @Volatile
    private var fallbackMeasuring = false
    private var fallbackMeasurementId = 0
    @Volatile
    private var activeYuvMeasurement: YuvMeasurement? = null
    private var compatibleLumaBuffer = ByteArray(0)
    private var compatibleYuvAvailable = true
    private var downgradeYuvSessionAfterMeasurement = false
    private var rawMeasurementTimeout: Runnable? = null
    private var yuvMeasurementTimeout: Runnable? = null
    private var vignettingMeasurementTimeout: Runnable? = null
    private var consecutiveRawMeasurementFailures = 0
    private var downgradeAfterCompatibleMeasurement = false
    @Volatile
    private var meteringOperationActive = false
    private val zoneCameraFrames = ZoneCameraFramePipeline(
        reserveTracker = callback::tryReserveZoneTrackingFrame,
        cancelTrackerReservation = callback::cancelZoneTrackingFrameReservation,
        deliverToTracker = callback::onZoneTrackingFrame,
    )

    private var lastViewWidth = 0
    private var lastViewHeight = 0
    private var lastDisplayRotation = Surface.ROTATION_0
    private var lastDisplayZoom = 1f
    private val previewTransformRevision = AtomicInteger(0)

    fun attach(texture: TextureView) {
        textureView = texture
        texture.surfaceTextureListener = this
    }

    fun setTrackingFramesEnabled(enabled: Boolean) {
        zoneCameraFrames.setEnabled(enabled, cameraHandler)
    }

    fun setMeteringPipelineMode(mode: MeteringPipelineMode) {
        val handler = cameraHandler
        if (handler == null) {
            if (meteringPipelineMode != mode) {
                meteringPipelineMode = mode
                resetRecoveryState()
            }
            return
        }
        handler.post {
            if (meteringPipelineMode == mode) return@post
            meteringPipelineMode = mode
            resetRecoveryState()
            if (!started) return@post
            closeCamera()
            postInfo(
                cameraInfo.copy(
                    rawAvailable = false,
                    status = localized("正在切换测光方式", "Switching metering mode"),
                ),
            )
            openCamera(textureView?.surfaceTexture)
        }
    }

    fun availableCameras(): List<CameraDescriptor> = cameraCatalog.discover().also { cameras ->
        Log.i(
            TAG,
            "Camera catalog (${cameras.size}): " + cameras.joinToString { camera ->
                "${camera.cameraId}[logical=${camera.logicalCameraId}, " +
                    "physical=${camera.physicalCameraId}, role=${camera.lensRole}, " +
                    "focal=${camera.focalLengthMm}, raw=${camera.rawAvailable}]"
            },
        )
    }

    fun selectCamera(cameraId: String) {
        if (cameraId.isBlank()) return
        requestedCameraId = cameraId
        val handler = cameraHandler ?: return
        handler.post {
            if (!started || cameraInfo.cameraId == cameraId && cameraDevice != null) return@post
            resetRecoveryState()
            closeCamera()
            val pending = cameraCatalog.discover().firstOrNull { it.cameraId == cameraId }
            postInfo(
                CameraUiInfo(
                    cameraId = cameraId,
                    logicalCameraId = pending?.logicalCameraId ?: cameraId,
                    physicalCameraId = pending?.physicalCameraId,
                    status = localized("正在切换摄像头", "Switching camera"),
                ),
            )
            openCamera(textureView?.surfaceTexture)
        }
    }

    fun start() {
        if (started) return
        started = true
        failureAttempts.clear()
        totalRecoveryAttempts = 0
        val thread = HandlerThread("raw-meter-camera").apply { start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
        val texture = textureView
        if (texture?.isAvailable == true) openCamera(texture.surfaceTexture)
    }

    fun stop() {
        started = false
        val handler = cameraHandler
        val thread = cameraThread
        if (handler != null && thread != null) {
            val closed = CountDownLatch(1)
            handler.post {
                closeCamera()
                closed.countDown()
            }
            closed.await(750L, TimeUnit.MILLISECONDS)
            thread.quitSafely()
            thread.join(750L)
        } else {
            closeCamera()
        }
        cameraThread = null
        cameraHandler = null
    }

    fun updatePreviewTransform(
        viewWidth: Int,
        viewHeight: Int,
        displayRotation: Int,
        displayZoom: Float,
    ) {
        lastViewWidth = viewWidth
        lastViewHeight = viewHeight
        lastDisplayRotation = displayRotation
        lastDisplayZoom = displayZoom
        val revision = previewTransformRevision.incrementAndGet()
        val texture = textureView ?: return
        val size = previewSize ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        texture.post {
            if (revision != previewTransformRevision.get() ||
                texture.width != viewWidth ||
                texture.height != viewHeight
            ) {
                return@post
            }
            texture.setTransform(
                CameraPreviewTransform.create(
                    viewWidth = viewWidth,
                    viewHeight = viewHeight,
                    displayRotation = displayRotation,
                    displayZoom = displayZoom,
                    bufferSize = size,
                ),
            )
        }
    }

    fun measure(
        frameFormat: FrameFormat,
        frameLandscape: Boolean,
        displayZoom: Float,
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget? = null,
        requestedSource: MeteringSource? = null,
    ): Boolean {
        if (meteringOperationActive) return false
        meteringOperationActive = true
        if (requestedSource == MeteringSource.ISP_PREVIEW) {
            measureProcessedPreview(meteringMode, target)
            return true
        }
        if (requestedSource == MeteringSource.YUV_PREVIEW ||
            requestedSource == null && !cameraInfo.rawAvailable
        ) {
            val handler = cameraHandler
            if (handler == null) {
                meteringOperationActive = false
                callback.onMeteringError(localized("相机尚未就绪", "Camera is not ready"))
            } else {
                handler.post { measureCompatiblePreview(meteringMode, target) }
            }
            return true
        }
        if (!cameraInfo.rawAvailable) {
            meteringOperationActive = false
            callback.onMeteringError(
                localized(
                    "当前摄像头无法使用高精度测光",
                    "High-accuracy metering is unavailable for this camera",
                ),
            )
            return true
        }
        val displayedPreviewReference = target?.let(::captureDisplayedPreviewReference)
        val handler = cameraHandler ?: run {
            meteringOperationActive = false
            callback.onMeteringError(localized("相机尚未就绪", "Camera is not ready"))
            return true
        }
        handler.post {
            if (activeMeasurement != null || activeVignettingCapture != null) {
                meteringOperationActive = false
                postMeterError(
                    localized("请等待当前操作完成", "Wait for the current operation"),
                )
                return@post
            }
            val device = cameraDevice
            val session = captureSession
            val reader = rawReader
            if (device == null || session == null || reader == null || !cameraInfo.rawAvailable) {
                meteringOperationActive = false
                postMeterError(
                    localized(
                        "高精度测光尚未就绪",
                        "High-accuracy metering is not ready",
                    ),
                )
                return@post
            }

            val captureIso = latestResult?.get(CaptureResult.SENSOR_SENSITIVITY)
            val count = rawFrameCount(captureIso)
            val screenAspect =
                if (frameLandscape) {
                    frameFormat.landscapeAspect
                } else {
                    1f / frameFormat.landscapeAspect
                }
            val sensorOrientation =
                characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)
                    ?: cameraInfo.sensorOrientationDegrees
            val displayDegrees = when (lastDisplayRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val screenToSensorRotation =
                (sensorOrientation - displayDegrees + 360) % 360
            val sensorFrameAspect = CameraPreviewTransform.screenAspectInSensorCoordinates(
                screenAspect = screenAspect,
                sensorOrientationDegrees = sensorOrientation,
                displayRotation = lastDisplayRotation,
            )
            val recentTrackingFrame = zoneCameraFrames.latestFrame(MAX_METERING_REFERENCE_AGE_NS)
            val previewReference = if (target != null && recentTrackingFrame != null) {
                MeteringAnalysis.createPreviewReference(
                    frame = recentTrackingFrame,
                    frameAspect = screenAspect,
                    zoom = displayZoom,
                    target = target,
                ) ?: displayedPreviewReference
            } else {
                displayedPreviewReference
            }
            val accumulator = MeasurementAccumulator(
                id = measurementId.incrementAndGet(),
                expectedFrames = count,
                frameAspect = sensorFrameAspect,
                zoom = displayZoom.coerceAtLeast(1f),
                meteringMode = meteringMode,
                target = target,
                previewReference = previewReference,
                screenToSensorRotationDegrees = screenToSensorRotation,
            )
            activeMeasurement = accumulator
            Log.e(
                TAG,
                "RAW metering started: frames=$count captureIso=${captureIso ?: "unknown"} " +
                    "zoom=${accumulator.zoom} " +
                    "format=${frameFormat.id} sensorAspect=$sensorFrameAspect mode=$meteringMode " +
                    "touchTarget=${target != null} ispReference=${previewReference != null}",
            )
            mainHandler.post { callback.onMeteringStarted(MeteringSource.RAW, count) }
            try {
                activeRawRequest = buildRawRequest(device, reader.surface)
                scheduleRawMeasurementTimeout(accumulator, handler)
                fillRawPipeline(accumulator)
            } catch (error: Exception) {
                finishMeasurementWithError(
                    localized(
                        "无法开始测光，请重试",
                        "Unable to start metering. Please try again",
                    ),
                )
            }
        }
        return true
    }

    private fun scheduleRawMeasurementTimeout(
        accumulator: MeasurementAccumulator,
        handler: Handler,
    ) {
        cancelRawMeasurementTimeout(handler)
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (rawMeasurementTimeout !== timeout) return@Runnable
            rawMeasurementTimeout = null
            if (activeMeasurement?.id == accumulator.id) {
                finishMeasurementWithError(
                    localized("测光超时，请重试", "Metering timed out. Please try again"),
                )
            }
        }
        rawMeasurementTimeout = timeout
        handler.postDelayed(timeout, RAW_METERING_TIMEOUT_MS)
    }

    private fun cancelRawMeasurementTimeout(handler: Handler? = cameraHandler) {
        val timeout = rawMeasurementTimeout ?: return
        rawMeasurementTimeout = null
        handler?.removeCallbacks(timeout)
    }

    private fun scheduleYuvMeasurementTimeout(
        measurement: YuvMeasurement,
        handler: Handler,
    ) {
        cancelYuvMeasurementTimeout(handler)
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (yuvMeasurementTimeout !== timeout) return@Runnable
            yuvMeasurementTimeout = null
            if (activeYuvMeasurement?.id == measurement.id) {
                finishYuvPreviewWithError(
                    measurement.id,
                    localized(
                        "正在切换到更兼容的测光方式",
                        "Switching to a more compatible metering method",
                    ),
                )
            }
        }
        yuvMeasurementTimeout = timeout
        handler.postDelayed(timeout, CompatibleMeteringPolicy.YUV_TIMEOUT_MS)
    }

    private fun cancelYuvMeasurementTimeout(handler: Handler? = cameraHandler) {
        val timeout = yuvMeasurementTimeout ?: return
        yuvMeasurementTimeout = null
        handler?.removeCallbacks(timeout)
    }

    private fun scheduleVignettingMeasurementTimeout(
        capture: VignettingCapture,
        handler: Handler,
    ) {
        cancelVignettingMeasurementTimeout(handler)
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (vignettingMeasurementTimeout !== timeout) return@Runnable
            vignettingMeasurementTimeout = null
            if (activeVignettingCapture?.id == capture.id) {
                finishVignettingWithError(
                    localized(
                        "暗角校准超时，请重试",
                        "Vignetting calibration timed out. Please try again",
                    ),
                )
            }
        }
        vignettingMeasurementTimeout = timeout
        handler.postDelayed(timeout, VIGNETTING_TIMEOUT_MS)
    }

    private fun cancelVignettingMeasurementTimeout(handler: Handler? = cameraHandler) {
        val timeout = vignettingMeasurementTimeout ?: return
        vignettingMeasurementTimeout = null
        handler?.removeCallbacks(timeout)
    }

    fun calibrateVignetting() {
        val handler = cameraHandler ?: run {
            callback.onVignettingCalibrationError(
                localized("相机尚未就绪", "Camera is not ready"),
            )
            return
        }
        handler.post {
            if (activeMeasurement != null || fallbackMeasuring ||
                activeVignettingCapture != null
            ) {
                postVignettingError(
                    localized("请等待当前操作完成", "Wait for the current operation"),
                )
                return@post
            }
            val device = cameraDevice
            val session = captureSession
            val reader = rawReader
            if (!cameraInfo.rawAvailable || device == null || session == null || reader == null) {
                postVignettingError(
                    localized(
                        "当前镜头不支持暗角校准",
                        "This lens does not support vignetting calibration",
                    ),
                )
                return@post
            }
            val capture = VignettingCapture(
                id = measurementId.incrementAndGet(),
                cameraId = cameraInfo.cameraId,
            )
            activeVignettingCapture = capture
            mainHandler.post { callback.onVignettingCalibrationStarted() }
            try {
                val request = buildRawRequest(device, reader.surface)
                scheduleVignettingMeasurementTimeout(capture, handler)
                session.capture(request, vignettingCaptureCallback, handler)
            } catch (error: Exception) {
                finishVignettingWithError(
                    localized(
                        "无法读取校准画面，请重试",
                        "Unable to read the calibration image. Please try again",
                    ),
                )
            }
        }
    }

    private fun captureDisplayedPreviewReference(
        target: ZoneMeteringTarget,
    ): PreviewLumaReference? {
        val texture = textureView ?: return null
        if (Looper.myLooper() != Looper.getMainLooper() ||
            !texture.isAvailable || texture.width <= 1 || texture.height <= 1
        ) return null
        val scale = PREVIEW_REFERENCE_LONG_EDGE.toFloat() /
            max(texture.width, texture.height)
        val bitmapWidth = (texture.width * scale).roundToInt().coerceAtLeast(2)
        val bitmapHeight = (texture.height * scale).roundToInt().coerceAtLeast(2)
        val bitmap = try {
            texture.getBitmap(bitmapWidth, bitmapHeight)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        return try {
            MeteringAnalysis.createPreviewReference(bitmap, target)
        } finally {
            bitmap.recycle()
        }
    }

    @Synchronized
    fun currentUserCalibrationRecord(): CameraCalibrationRecord? =
        calibrationStore.record(cameraInfo.cameraId.ifBlank { requestedCameraId ?: "0" })

    @Synchronized
    fun isRawMeteringAvailable(): Boolean = cameraInfo.rawAvailable && rawReader != null

    @Synchronized
    fun preferredCompatibleMeteringSource(): MeteringSource =
        if (trackingReader != null) MeteringSource.YUV_PREVIEW else MeteringSource.ISP_PREVIEW

    @Synchronized
    fun updateUserCalibration(
        referenceEv100: Double,
        rawMeasuredEv100: Double?,
        compatibleMeasuredEv100: Double?,
    ): CameraCalibrationRecord {
        val cameraId = cameraInfo.cameraId.ifBlank { requestedCameraId ?: "0" }
        val updated = calibrationStore.updateUserCorrections(
            cameraId = cameraId,
            referenceEv100 = referenceEv100,
            rawMeasuredEv100 = rawMeasuredEv100,
            compatibleMeasuredEv100 = compatibleMeasuredEv100,
        )
        Log.e(
            TAG,
            "User calibration updated: camera=$cameraId reference=$referenceEv100 " +
                "rawMeasured=$rawMeasuredEv100 compatibleMeasured=$compatibleMeasuredEv100 " +
                "rawCorrection=${updated.rawCorrectionEv} " +
                "compatibleCorrection=${updated.compatibleCorrectionEv}",
        )
        return updated
    }

    @Synchronized
    fun resetUserCalibration() {
        val cameraId = cameraInfo.cameraId.ifBlank { requestedCameraId ?: "0" }
        calibrationStore.resetUserCorrection(cameraId)
        Log.e(TAG, "User calibration reset: camera=$cameraId")
    }

    @Synchronized
    fun restoreUserCalibration(updatedAtEpochMs: Long): CameraCalibrationRecord? {
        val cameraId = cameraInfo.cameraId.ifBlank { requestedCameraId ?: "0" }
        val restored = calibrationStore.restore(cameraId, updatedAtEpochMs) ?: return null
        Log.i(
            TAG,
            "User calibration restored: camera=$cameraId updated=${restored.updatedAtEpochMs} " +
                "rawCorrection=${restored.rawCorrectionEv} " +
                "compatibleCorrection=${restored.compatibleCorrectionEv}",
        )
        return restored
    }

    @Synchronized
    fun restoreVignettingCalibration(createdAtEpochMs: Long): VignettingCalibrationInfo? {
        val cameraId = cameraInfo.cameraId.ifBlank { requestedCameraId ?: "0" }
        val restored = vignettingCalibrationStore.restore(cameraId, createdAtEpochMs) ?: return null
        Log.i(
            TAG,
            "Vignetting calibration restored: camera=$cameraId created=${restored.createdAtEpochMs}",
        )
        return restored
    }

    @Synchronized
    fun resetVignettingCalibration() {
        val cameraId = cameraInfo.cameraId.ifBlank { requestedCameraId ?: "0" }
        vignettingCalibrationStore.reset(cameraId)
        Log.i(TAG, "Vignetting calibration reset: camera=$cameraId")
    }

    private fun measureCompatiblePreview(
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget?,
    ) {
        if (target == null && trackingReader != null && compatibleYuvAvailable) {
            measureYuvPreview(meteringMode)
        } else {
            mainHandler.post { measureProcessedPreview(meteringMode, target) }
        }
    }

    private fun measureYuvPreview(meteringMode: MeteringMode) {
        if (fallbackMeasuring || activeMeasurement != null || activeVignettingCapture != null) {
            meteringOperationActive = false
            postMeterError(localized("请等待当前操作完成", "Wait for the current operation"))
            return
        }
        val handler = cameraHandler
        if (trackingReader == null || handler == null || cameraDevice == null ||
            captureSession == null || latestResult == null
        ) {
            mainHandler.post { measureProcessedPreview(meteringMode, null) }
            return
        }
        fallbackMeasuring = true
        val measurement = YuvMeasurement(
            id = ++fallbackMeasurementId,
            meteringMode = meteringMode,
        )
        activeYuvMeasurement = measurement
        Log.e(
            TAG,
            "YUV preview metering started: frames=${CompatibleMeteringPolicy.FRAME_COUNT} " +
                "mode=$meteringMode",
        )
        mainHandler.post {
            callback.onMeteringStarted(
                MeteringSource.YUV_PREVIEW,
                CompatibleMeteringPolicy.FRAME_COUNT,
            )
        }
        scheduleYuvMeasurementTimeout(measurement, handler)
    }

    private fun onYuvMeteringImage(image: Image, measurement: YuvMeasurement) {
        if (activeYuvMeasurement?.id != measurement.id) return
        measurement.attemptedFrames += 1
        val requiredBytes = image.width * image.height
        if (compatibleLumaBuffer.size != requiredBytes) {
            compatibleLumaBuffer = ByteArray(requiredBytes)
        }
        val result = latestResult
        val chars = characteristics
        val stat = if (result != null && chars != null) {
            MeteringAnalysis.analyzeYuvPreview(
                image = image,
                luma = compatibleLumaBuffer,
                result = result,
                characteristics = chars,
                cameraId = cameraInfo.cameraId,
                meteringMode = measurement.meteringMode,
                calibrationStore = calibrationStore,
            )
        } else {
            null
        }
        if (activeYuvMeasurement?.id != measurement.id) return
        if (stat != null) {
            val reading = MeteringFusion.fuse(listOf(stat), MeteringSource.YUV_PREVIEW)
            if (reading == null) {
                finishYuvPreviewWithError(
                    measurement.id,
                    localized(
                        "兼容测光数据无效，正在使用预览重试",
                        "Compatible metering data was invalid. Retrying from the preview",
                    ),
                )
                return
            }
            cancelYuvMeasurementTimeout()
            activeYuvMeasurement = null
            fallbackMeasuring = false
            meteringOperationActive = false
            mainHandler.post { callback.onMeterReading(reading) }
            Log.e(
                TAG,
                "YUV preview metering completed: frames=${reading.frameCount} " +
                "ev100=${reading.sceneEv100}",
            )
            afterCompatibleMeasurement()
        } else if (CompatibleMeteringPolicy.decide(
                attemptedFrames = measurement.attemptedFrames,
                frameValid = false,
            ) == CompatibleFrameDecision.USE_PREVIEW
        ) {
            finishYuvPreviewWithError(
                measurement.id,
                localized(
                    "正在切换到更兼容的测光方式",
                    "Switching to a more compatible metering method",
                ),
            )
        }
    }

    private fun finishYuvPreviewWithError(id: Int, message: String) {
        val measurement = activeYuvMeasurement?.takeIf { it.id == id } ?: return
        cancelYuvMeasurementTimeout()
        activeYuvMeasurement = null
        fallbackMeasuring = false
        compatibleYuvAvailable = false
        if (sessionProfile == CameraSessionProfile.COMPATIBLE) {
            downgradeYuvSessionAfterMeasurement = true
        }
        Log.w(TAG, message)
        mainHandler.post { measureProcessedPreview(measurement.meteringMode, null) }
    }

    private fun measureProcessedPreview(
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget?,
    ) {
        if (fallbackMeasuring || activeMeasurement != null || activeVignettingCapture != null) {
            meteringOperationActive = false
            callback.onMeteringError(
                localized("请等待当前操作完成", "Wait for the current operation"),
            )
            return
        }
        val texture = textureView
        if (texture?.isAvailable != true || cameraDevice == null || captureSession == null) {
            meteringOperationActive = false
            callback.onMeteringError(
                localized("相机预览尚未就绪", "Camera preview is not ready"),
            )
            return
        }
        if (latestResult == null) {
            meteringOperationActive = false
            callback.onMeteringError(
                localized(
                    "正在等待相机曝光参数",
                    "Waiting for camera exposure data",
                ),
            )
            return
        }
        fallbackMeasuring = true
        val id = ++fallbackMeasurementId
        val samples = mutableListOf<MeteringFrameStat>()
        Log.e(
            TAG,
            "ISP preview metering started: frames=${CompatibleMeteringPolicy.FRAME_COUNT} " +
                "mode=$meteringMode",
        )
        callback.onMeteringStarted(
            MeteringSource.ISP_PREVIEW,
            CompatibleMeteringPolicy.FRAME_COUNT,
        )
        captureProcessedPreviewSample(id, samples, meteringMode, target)
    }

    private fun captureProcessedPreviewSample(
        id: Int,
        samples: MutableList<MeteringFrameStat>,
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget?,
    ) {
        if (!fallbackMeasuring || id != fallbackMeasurementId) return
        val texture = textureView
        val result = latestResult
        val handler = cameraHandler
        if (texture?.isAvailable != true || result == null || handler == null) {
            finishProcessedPreviewWithError(
                id,
                localized("无法读取当前预览画面", "Unable to read the current preview"),
            )
            return
        }
        val bitmap = try {
            texture.getBitmap(FALLBACK_BITMAP_SIZE, FALLBACK_BITMAP_SIZE)
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) {
            finishProcessedPreviewWithError(
                id,
                localized("无法读取当前预览画面", "Unable to read the current preview"),
            )
            return
        }
        val accepted = handler.post {
            val stat = try {
                characteristics?.let { chars ->
                    MeteringAnalysis.analyzePreview(
                        bitmap = bitmap,
                        result = result,
                        characteristics = chars,
                        cameraId = cameraInfo.cameraId,
                        meteringMode = meteringMode,
                        calibrationStore = calibrationStore,
                        target = target,
                    )
                }
            } finally {
                bitmap.recycle()
            }
            mainHandler.post finishSample@{
                if (!fallbackMeasuring || id != fallbackMeasurementId) return@finishSample
                if (stat == null) {
                    finishProcessedPreviewWithError(
                        id,
                        localized(
                            "预览亮度或曝光参数不可用",
                            "Preview brightness or exposure data is unavailable",
                        ),
                    )
                    return@finishSample
                }
                samples += stat
                finishProcessedPreview(id, samples)
            }
        }
        if (!accepted) {
            bitmap.recycle()
            finishProcessedPreviewWithError(
                id,
                localized("相机预览已关闭", "The camera preview has closed"),
            )
        }
    }

    private fun finishProcessedPreview(id: Int, samples: List<MeteringFrameStat>) {
        if (!fallbackMeasuring || id != fallbackMeasurementId || samples.isEmpty()) return
        val reading = MeteringFusion.fuse(samples, MeteringSource.ISP_PREVIEW)
        if (reading == null) {
            finishProcessedPreviewWithError(
                id,
                localized(
                    "无法计算兼容测光结果",
                    "Unable to calculate a compatible reading",
                ),
            )
            return
        }
        fallbackMeasuring = false
        meteringOperationActive = false
        callback.onMeterReading(reading)
        Log.e(
            TAG,
            "ISP preview metering completed: frames=${samples.size} ev100=${reading.sceneEv100}",
        )
        afterCompatibleMeasurement()
    }

    private fun afterCompatibleMeasurement() {
        cameraHandler?.post {
            when {
                downgradeYuvSessionAfterMeasurement -> {
                    downgradeYuvSessionAfterMeasurement = false
                    if (started && cameraDevice != null) {
                        scheduleRecovery(
                            CameraSessionProfile.PREVIEW_ONLY,
                            localized(
                                "已启用更兼容的测光方式",
                                "Using a more compatible metering method",
                            ),
                            SESSION_RECOVERY_DELAY_MS,
                        )
                    }
                }
                downgradeAfterCompatibleMeasurement -> {
                    downgradeAfterCompatibleMeasurement = false
                    if (!started || cameraDevice == null) return@post
                    val compatible = if (trackingHardwareAvailable && compatibleYuvAvailable) {
                        CameraSessionProfile.COMPATIBLE
                    } else {
                        CameraSessionProfile.PREVIEW_ONLY
                    }
                    scheduleRecovery(
                        compatible,
                        localized(
                            "已切换到兼容测光",
                            "Switched to compatible metering",
                        ),
                        SESSION_RECOVERY_DELAY_MS,
                    )
                }
            }
        }
    }

    private fun finishProcessedPreviewWithError(id: Int, message: String) {
        if (id != fallbackMeasurementId) return
        fallbackMeasuring = false
        meteringOperationActive = false
        Log.e(TAG, "ISP preview metering failed: $message")
        callback.onMeteringError(message)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (started) openCamera(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        updatePreviewTransform(width, height, lastDisplayRotation, lastDisplayZoom)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        cameraHandler?.post { closeCamera() }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    @SuppressLint("MissingPermission")
    private fun openCamera(surfaceTexture: SurfaceTexture?) {
        if (!started || opening || cameraDevice != null || surfaceTexture == null) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            postInfo(
                cameraInfo.copy(
                    status = localized("等待相机权限", "Waiting for camera permission"),
                ),
            )
            return
        }
        val handler = cameraHandler ?: return
        val generation = ++cameraGeneration
        cameraFailureStage = CameraFailureStage.OPENING
        opening = true
        try {
            val discovered = cameraCatalog.discover()
            val selected = discovered.firstOrNull { it.cameraId == requestedCameraId }
                ?: cameraCatalog.preferredCamera(discovered)
            val selection = selected?.let { descriptor ->
                val logicalChars = cameraManager.getCameraCharacteristics(descriptor.logicalCameraId)
                val effectivePhysicalId = descriptor.physicalCameraId
                    ?.takeUnless { useLogicalCameraFallback }
                val streamChars = effectivePhysicalId?.let {
                    cameraManager.getCameraCharacteristics(it)
                } ?: logicalChars
                Triple(descriptor, effectivePhysicalId, streamChars)
            }
            if (selection == null) {
                opening = false
                postInfo(
                    CameraUiInfo(
                        status = localized("没有可用的摄像头", "No camera is available"),
                    ),
                )
                return
            }
            val (descriptor, effectivePhysicalId, chars) = selection
            val activeCameraId = if (useLogicalCameraFallback) {
                descriptor.logicalCameraId
            } else {
                descriptor.cameraId
            }
            requestedCameraId = activeCameraId
            selectedPhysicalCameraId = effectivePhysicalId
            characteristics = chars
            val capabilities =
                chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val rawCapability =
                capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val manualAvailable =
                capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException(
                    localized("相机没有输出配置", "Camera has no output configuration"),
                )
            rawHardwareAvailable = rawCapability &&
                !map.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()
            val chosenPreview = CameraStreamSelector.choosePreviewSize(chars)
                ?: throw IllegalStateException(
                    localized("没有合适的预览尺寸", "No suitable preview size is available"),
                )
            previewSize = chosenPreview
            val chosenRange = CameraStreamSelector.chooseFpsRange(chars, chosenPreview)
            previewFpsRange = chosenRange
            val trackingSize = CameraStreamSelector.chooseTrackingSize(map, chosenPreview)
            trackingHardwareAvailable = trackingSize != null
            val profile = CameraRecoveryPolicy.normalize(
                sessionProfile ?: CameraRecoveryPolicy.initialProfile(
                    mode = meteringPipelineMode,
                    rawSupported = rawHardwareAvailable,
                    trackingSupported = trackingHardwareAvailable,
                ),
                rawSupported = rawHardwareAvailable,
                trackingSupported = trackingHardwareAvailable,
            )
            sessionProfile = profile
            val rawAvailable = rawHardwareAvailable && profile.usesRaw

            val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: 0f
            val aperture = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull() ?: 0f
            val maxZoom =
                (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 5f)
                    .coerceIn(1f, 5f)
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                ?: chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

            cameraInfo = CameraUiInfo(
                cameraId = activeCameraId,
                logicalCameraId = descriptor.logicalCameraId,
                physicalCameraId = effectivePhysicalId,
                rawAvailable = rawAvailable,
                manualSensorAvailable = manualAvailable,
                focalLengthMm = focal,
                aperture = aperture,
                sensorWidthMm = physicalSize?.width ?: 0f,
                sensorHeightMm = physicalSize?.height ?: 0f,
                sensorOrientationDegrees =
                    chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                maxDisplayZoom = maxZoom,
                previewSize = chosenPreview,
                previewFps = chosenRange?.upper ?: 30,
                activeArray = activeArray,
                status = localized("正在打开摄像头", "Opening camera"),
            )
            postInfo(cameraInfo)

            surfaceTexture.setDefaultBufferSize(chosenPreview.width, chosenPreview.height)
            previewSurface?.release()
            previewSurface = Surface(surfaceTexture)

            rawReader?.close()
            rawReader = if (rawAvailable) {
                val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                val rawSize = rawSizes?.minByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: throw IllegalStateException(
                        localized(
                            "RAW 能力存在，但没有 RAW_SENSOR 尺寸",
                            "RAW is reported but no RAW_SENSOR size is available",
                        ),
                    )
                ImageReader.newInstance(
                    rawSize.width,
                    rawSize.height,
                    ImageFormat.RAW_SENSOR,
                    RAW_READER_MAX_IMAGES,
                ).also { imageReader ->
                    imageReader.setOnImageAvailableListener(
                        { reader -> onRawImageAvailable(reader) },
                        handler,
                    )
                }
            } else {
                null
            }
            trackingReader?.close()
            trackingReader = if (profile.usesTracking) {
                trackingSize?.let { size ->
                ImageReader.newInstance(
                    size.width,
                    size.height,
                    ImageFormat.YUV_420_888,
                    ZoneLumaBufferPool.DEFAULT_CAPACITY,
                ).also { imageReader ->
                    imageReader.setOnImageAvailableListener(
                        { reader -> onTrackingImageAvailable(reader) },
                        handler,
                    )
                }
                }
            } else {
                null
            }
            Log.i(
                TAG,
                "Opening selection=${descriptor.cameraId}, logical=${descriptor.logicalCameraId}, " +
                    "physical=$effectivePhysicalId, profile=$profile, focal=$focal, " +
                    "raw=$rawAvailable",
            )
            cameraManager.openCamera(
                descriptor.logicalCameraId,
                createCameraStateCallback(generation),
                handler,
            )
        } catch (error: Exception) {
            opening = false
            handlePreparationFailure(error, generation)
        }
    }

    private fun createCameraStateCallback(generation: Int) =
        object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!started || generation != cameraGeneration) {
                    camera.close()
                    return
                }
                opening = false
                cameraDevice = camera
                cameraFailureStage = CameraFailureStage.CONFIGURING
                createSession(camera, generation)
            }

            override fun onDisconnected(camera: CameraDevice) {
                if (generation != cameraGeneration) {
                    camera.close()
                    return
                }
                val stage = cameraFailureStage
                camera.close()
                if (cameraDevice === camera) cameraDevice = null
                opening = false
                handleCameraFailure(
                    CameraFailureKind.DISCONNECTED,
                    stage,
                    generation,
                )
            }

            override fun onError(camera: CameraDevice, error: Int) {
                if (generation != cameraGeneration) {
                    camera.close()
                    return
                }
                val stage = cameraFailureStage
                camera.close()
                if (cameraDevice === camera) cameraDevice = null
                opening = false
                val failure = CameraFailureKind.fromDeviceError(error)
                Log.e(
                    TAG,
                    "Camera device callback error=$error kind=$failure stage=$stage " +
                        "profile=$sessionProfile generation=$generation",
                )
                handleCameraFailure(failure, stage, generation)
            }
        }

    private fun createSession(device: CameraDevice, generation: Int) {
        val preview = previewSurface ?: return
        val handler = cameraHandler ?: return
        val outputs = mutableListOf(preview)
        rawReader?.surface?.let(outputs::add)
        trackingReader?.surface?.let(outputs::add)
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (generation != cameraGeneration || cameraDevice !== device) {
                    session.close()
                    return
                }
                captureSession = session
                cameraFailureStage = CameraFailureStage.RUNNING
                startPreview(device, session, preview, generation)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close()
                if (generation != cameraGeneration) return
                Log.w(TAG, "Camera session configuration failed for profile=$sessionProfile")
                handleSessionFailure(generation)
            }
        }
        try {
            val physicalId = selectedPhysicalCameraId
            val outputConfigurations = outputs.map { surface ->
                OutputConfiguration(surface).apply {
                    if (physicalId != null) setPhysicalCameraId(physicalId)
                }
            }
            val executor = Executor { runnable -> handler.post(runnable) }
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigurations,
                    executor,
                    stateCallback,
                ),
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to create camera session for profile=$sessionProfile", error)
            if (generation == cameraGeneration) handleSessionFailure(generation)
        }
    }

    private fun handlePreparationFailure(error: Exception, generation: Int) {
        if (generation != cameraGeneration) return
        Log.e(TAG, "Unable to prepare camera profile=$sessionProfile", error)
        val failure = (error as? CameraAccessException)?.let { access ->
            when (access.reason) {
                CameraAccessException.CAMERA_IN_USE -> CameraFailureKind.IN_USE
                CameraAccessException.MAX_CAMERAS_IN_USE -> CameraFailureKind.RESOURCE_LIMIT
                CameraAccessException.CAMERA_DISABLED -> CameraFailureKind.DISABLED
                CameraAccessException.CAMERA_DISCONNECTED -> CameraFailureKind.DISCONNECTED
                CameraAccessException.CAMERA_ERROR -> CameraFailureKind.SERVICE
                else -> CameraFailureKind.UNKNOWN
            }
        } ?: CameraFailureKind.UNKNOWN
        handleCameraFailure(failure, CameraFailureStage.OPENING, generation)
    }

    private fun handleSessionFailure(generation: Int) {
        if (generation != cameraGeneration) return
        val current = sessionProfile ?: CameraSessionProfile.PREVIEW_ONLY
        val next = CameraRecoveryPolicy.nextProfile(
            current,
            rawSupported = rawHardwareAvailable,
            trackingSupported = trackingHardwareAvailable,
        )
        if (next != null) {
            scheduleRecovery(
                next,
                localized(
                    "当前方式无法启动，正在尝试兼容方式",
                    "This mode could not start. Trying a compatible mode",
                ),
                SESSION_RECOVERY_DELAY_MS,
            )
        } else if (!tryLogicalCameraFallback()) {
            finishCameraFailure(
                localized(
                    "相机无法正常启动，请尝试兼容模式或重启手机",
                    "The camera could not start. Try compatible mode or restart the phone",
                ),
            )
        }
    }

    private fun handleCameraFailure(
        failure: CameraFailureKind,
        stage: CameraFailureStage,
        generation: Int,
    ) {
        if (generation != cameraGeneration) return
        val current = sessionProfile ?: CameraSessionProfile.PREVIEW_ONLY
        val key = Triple(current, failure, stage)
        val attempt = (failureAttempts[key] ?: 0) + 1
        failureAttempts[key] = attempt
        val decision = CameraRecoveryPolicy.decide(
            failure = failure,
            stage = stage,
            current = current,
            attempt = attempt,
            rawSupported = rawHardwareAvailable,
            trackingSupported = trackingHardwareAvailable,
        )
        when (decision.action) {
            CameraRecoveryAction.RETRY,
            CameraRecoveryAction.DOWNGRADE,
            -> scheduleRecovery(
                decision.profile ?: current,
                recoveryMessage(failure),
                decision.delayMs,
            )

            CameraRecoveryAction.STOP -> {
                val canChangeCameraRoute = failure == CameraFailureKind.DEVICE ||
                    failure == CameraFailureKind.SERVICE ||
                    failure == CameraFailureKind.DISCONNECTED ||
                    failure == CameraFailureKind.UNKNOWN
                if (!canChangeCameraRoute || !tryLogicalCameraFallback()) {
                    finishCameraFailure(finalFailureMessage(failure))
                }
            }
        }
    }

    private fun tryLogicalCameraFallback(): Boolean {
        if (useLogicalCameraFallback || selectedPhysicalCameraId == null) return false
        useLogicalCameraFallback = true
        sessionProfile = CameraSessionProfile.PREVIEW_ONLY
        scheduleRecovery(
            CameraSessionProfile.PREVIEW_ONLY,
            localized(
                "所选镜头暂时不可用，正在切换主摄",
                "The selected lens is unavailable. Switching to the main camera",
            ),
            SESSION_RECOVERY_DELAY_MS,
        )
        return true
    }

    private fun scheduleRecovery(
        profile: CameraSessionProfile,
        message: String,
        delayMs: Long,
    ) {
        val handler = cameraHandler ?: return
        if (!started) return
        if (totalRecoveryAttempts >= MAX_TOTAL_RECOVERY_ATTEMPTS) {
            finishCameraFailure(
                localized(
                    "相机多次启动失败，请稍后重试或重启手机",
                    "The camera failed repeatedly. Try again later or restart the phone",
                ),
            )
            return
        }
        totalRecoveryAttempts += 1
        sessionProfile = profile
        notifyInterruptedOperations()
        closeCamera()
        postInfo(
            cameraInfo.copy(
                rawAvailable = profile.usesRaw && rawHardwareAvailable,
                physicalCameraId = if (useLogicalCameraFallback) null else cameraInfo.physicalCameraId,
                status = message,
            ),
        )
        val recoveryGeneration = cameraGeneration
        handler.postDelayed(
            {
                if (!started || recoveryGeneration != cameraGeneration ||
                    cameraDevice != null || opening
                ) return@postDelayed
                val texture = textureView
                if (texture?.isAvailable == true) openCamera(texture.surfaceTexture)
            },
            delayMs,
        )
    }

    private fun finishCameraFailure(message: String) {
        notifyInterruptedOperations()
        closeCamera()
        postInfo(cameraInfo.copy(rawAvailable = false, status = message))
    }

    private fun notifyInterruptedOperations() {
        if (meteringOperationActive || activeMeasurement != null || fallbackMeasuring) {
            meteringOperationActive = false
            postMeterError(
                localized(
                    "本次测光已中止，请重试",
                    "This measurement was interrupted. Try again",
                ),
            )
        }
        if (activeVignettingCapture != null) {
            postVignettingError(
                localized(
                    "本次校准已中止，请重试",
                    "This calibration was interrupted. Try again",
                ),
            )
        }
    }

    private fun recoveryMessage(failure: CameraFailureKind): String = when (failure) {
        CameraFailureKind.IN_USE -> localized(
            "相机正被其他应用使用，正在重试",
            "Another app is using the camera. Retrying",
        )
        CameraFailureKind.RESOURCE_LIMIT -> localized(
            "相机暂时不可用，正在重试",
            "The camera is temporarily unavailable. Retrying",
        )
        CameraFailureKind.DISABLED -> localized(
            "相机已被系统停用",
            "The camera has been disabled by the system",
        )
        CameraFailureKind.DEVICE -> localized(
            "相机运行异常，正在尝试兼容方式",
            "The camera stopped unexpectedly. Trying a compatible mode",
        )
        CameraFailureKind.SERVICE -> localized(
            "相机暂时无响应，正在重试",
            "The camera is not responding. Retrying",
        )
        CameraFailureKind.DISCONNECTED -> localized(
            "相机连接中断，正在重新连接",
            "The camera was disconnected. Reconnecting",
        )
        CameraFailureKind.UNKNOWN -> localized(
            "相机暂时无法使用，正在重试",
            "The camera is temporarily unavailable. Retrying",
        )
    }

    private fun finalFailureMessage(failure: CameraFailureKind): String = when (failure) {
        CameraFailureKind.IN_USE -> localized(
            "相机正被其他应用使用，请关闭其他相机应用后重试",
            "Another app is using the camera. Close it and try again",
        )
        CameraFailureKind.RESOURCE_LIMIT -> localized(
            "相机资源不足，请关闭其他相机应用后重试",
            "Camera resources are busy. Close other camera apps and try again",
        )
        CameraFailureKind.DISABLED -> localized(
            "相机已被系统停用，请检查隐私或管理设置",
            "The camera is disabled. Check privacy or device management settings",
        )
        CameraFailureKind.DEVICE -> localized(
            "相机无法正常启动，请尝试兼容模式或重启手机",
            "The camera could not start. Try compatible mode or restart the phone",
        )
        CameraFailureKind.SERVICE -> localized(
            "相机服务无法恢复，请重启手机后重试",
            "The camera could not recover. Restart the phone and try again",
        )
        CameraFailureKind.DISCONNECTED -> localized(
            "相机连接已中断，请稍后重试",
            "The camera was disconnected. Try again later",
        )
        CameraFailureKind.UNKNOWN -> localized(
            "相机暂时无法使用，请稍后重试",
            "The camera is unavailable. Try again later",
        )
    }

    private fun resetRecoveryState() {
        sessionProfile = null
        useLogicalCameraFallback = false
        failureAttempts.clear()
        totalRecoveryAttempts = 0
        consecutiveRawMeasurementFailures = 0
        downgradeAfterCompatibleMeasurement = false
    }

    private fun startPreview(
        device: CameraDevice,
        session: CameraCaptureSession,
        preview: Surface,
        generation: Int,
    ) {
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewBuilder = builder
            builder.addTarget(preview)
            trackingReader?.surface?.let(builder::addTarget)
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            previewFpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            setSupportedAutoFocus(builder)
            session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
            val readyInfo = cameraInfo.copy(
                status = if (cameraInfo.rawAvailable) {
                    localized(
                        "${cameraInfo.previewSize?.width}×${cameraInfo.previewSize?.height} · 高精度测光",
                        "${cameraInfo.previewSize?.width}×${cameraInfo.previewSize?.height} · High-accuracy metering",
                    )
                } else {
                    localized(
                        "${cameraInfo.previewSize?.width}×${cameraInfo.previewSize?.height} · 兼容测光",
                        "${cameraInfo.previewSize?.width}×${cameraInfo.previewSize?.height} · Compatible metering",
                    )
                },
            )
            cameraInfo = readyInfo
            postInfo(readyInfo)
            if (!readyInfo.rawAvailable && meteringPipelineMode == MeteringPipelineMode.AUTO) {
                mainHandler.post { callback.onRawUnavailable() }
            }
            updatePreviewTransform(
                lastViewWidth,
                lastViewHeight,
                lastDisplayRotation,
                lastDisplayZoom,
            )
            cameraHandler?.postDelayed(
                {
                    if (generation == cameraGeneration && captureSession === session) {
                        failureAttempts.clear()
                        totalRecoveryAttempts = 0
                    }
                },
                STABLE_PREVIEW_RESET_DELAY_MS,
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start preview for profile=$sessionProfile", error)
            if (generation == cameraGeneration) handleSessionFailure(generation)
        }
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val effectiveResult = effectiveCaptureResult(result)
            latestResult = effectiveResult
            updateDynamicLensInfo(effectiveResult)
        }
    }

    private val rawCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val effectiveResult = effectiveCaptureResult(result)
            latestResult = effectiveResult
            updateDynamicLensInfo(effectiveResult)
            val timestamp = effectiveResult.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: result.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: return
            val active = activeMeasurement ?: return
            active.results[timestamp] = effectiveResult
            pairRawFrames(active)
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            finishMeasurementWithError(
                localized(
                    "相机未能完成本次测光，请重试",
                    "The camera could not complete this measurement. Please try again",
                ),
            )
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            finishMeasurementWithError(
                localized(
                    "本次测光被相机中止，请重试",
                    "This measurement was stopped by the camera. Please try again",
                ),
            )
        }
    }

    private val vignettingCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val effectiveResult = effectiveCaptureResult(result)
            latestResult = effectiveResult
            val timestamp = effectiveResult.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: result.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: return
            val active = activeVignettingCapture ?: return
            active.results[timestamp] = effectiveResult
            pairVignettingFrame(active)
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            finishVignettingWithError(
                localized(
                    "相机未能完成暗角校准，请重试",
                    "The camera could not complete vignetting calibration. Please try again",
                ),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun effectiveCaptureResult(result: TotalCaptureResult): CaptureResult {
        val physicalId = selectedPhysicalCameraId ?: return result
        return result.physicalCameraResults[physicalId] ?: result
    }

    private fun updateDynamicLensInfo(result: CaptureResult) {
        val focal = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: cameraInfo.focalLengthMm
        val aperture = result.get(CaptureResult.LENS_APERTURE) ?: cameraInfo.aperture
        if (focal <= 0f ||
            abs(focal - cameraInfo.focalLengthMm) < 0.01f &&
            abs(aperture - cameraInfo.aperture) < 0.01f
        ) return
        postInfo(cameraInfo.copy(focalLengthMm = focal, aperture = aperture))
    }

    private fun buildRawRequest(
        device: CameraDevice,
        rawSurface: Surface,
    ): CaptureRequest {
        val last = latestResult
        val exposureTime = last?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val sensitivity = last?.get(CaptureResult.SENSOR_SENSITIVITY)
        val frameDuration = last?.get(CaptureResult.SENSOR_FRAME_DURATION)
        val useManual =
            cameraInfo.manualSensorAvailable && exposureTime != null && sensitivity != null
        return device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(rawSurface)
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
                setSupportedAutoFocus(this)
            } else {
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                setSupportedAutoFocus(this)
            }
        }.build()
    }

    /** Keep only one full-size RAW Image in flight to bound native camera-buffer memory. */
    private fun fillRawPipeline(active: MeasurementAccumulator) {
        while (activeMeasurement?.id == active.id &&
            active.submittedFrames < active.expectedFrames &&
            active.submittedFrames - active.completedFrames < RAW_PIPELINE_DEPTH
        ) {
            val submittedBefore = active.submittedFrames
            submitNextRawFrame(active)
            if (active.submittedFrames == submittedBefore) return
        }
    }

    /** Submit one RAW request without allowing the rolling window to grow unbounded. */
    private fun submitNextRawFrame(active: MeasurementAccumulator) {
        if (activeMeasurement?.id != active.id) return
        if (active.submittedFrames >= active.expectedFrames) return
        val session = captureSession
        val request = activeRawRequest
        val handler = cameraHandler
        if (session == null || request == null || handler == null) {
            finishMeasurementWithError(
                localized("本次测光已失效，请重试", "This measurement expired. Please try again"),
            )
            return
        }
        active.submittedFrames += 1
        try {
            session.capture(request, rawCaptureCallback, handler)
        } catch (error: Exception) {
            finishMeasurementWithError(
                localized(
                    "无法继续测光，请重试",
                    "Unable to continue metering. Please try again",
                ),
            )
        }
    }

    private fun setSupportedAutoFocus(builder: CaptureRequest.Builder) {
        val modes =
            characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
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

    private fun onTrackingImageAvailable(reader: ImageReader) {
        val yuvMeasurement = activeYuvMeasurement
        if (yuvMeasurement != null) {
            val image = try {
                reader.acquireLatestImage()
            } catch (_: IllegalStateException) {
                null
            } ?: return
            try {
                onYuvMeteringImage(image, yuvMeasurement)
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to analyze compatible camera frame", error)
            } finally {
                image.close()
            }
            return
        }
        zoneCameraFrames.onImageAvailable(
            reader,
            cameraInfo.sensorOrientationDegrees,
            lastDisplayRotation,
        )
    }

    private fun onRawImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        val vignetting = activeVignettingCapture
        if (vignetting != null) {
            vignetting.images[image.timestamp] = image
            pairVignettingFrame(vignetting)
            return
        }
        val active = activeMeasurement
        if (active == null) {
            image.close()
            return
        }
        active.images[image.timestamp] = image
        pairRawFrames(active)
    }

    private fun pairVignettingFrame(active: VignettingCapture) {
        val timestamp = active.images.keys.intersect(active.results.keys).firstOrNull() ?: return
        val image = active.images.remove(timestamp) ?: return
        val result = active.results.remove(timestamp)
        val chars = characteristics
        try {
            if (result == null || chars == null) {
                finishVignettingWithError(
                    localized(
                        "暗角校准数据无效，请重试",
                        "Vignetting calibration data was invalid. Please try again",
                    ),
                )
                return
            }
            val calibration = MeteringAnalysis.createVignettingCalibrationMap(
                image = image,
                result = result,
                characteristics = chars,
                activeArray = cameraInfo.activeArray,
            )
            if (calibration == null) {
                finishVignettingWithError(
                    localized(
                        "画面过暗或过亮，请调整均匀画面后重试",
                        "The frame is too dark, too bright, or invalid. Adjust the uniform scene and try again",
                    ),
                )
                return
            }
            vignettingCalibrationStore.save(active.cameraId, calibration)
            cancelVignettingMeasurementTimeout()
            activeVignettingCapture = null
            closePendingVignettingImages(active)
            val info = vignettingCalibrationStore.info(active.cameraId) ?: return
            Log.i(
                TAG,
                "Vignetting calibration saved camera=${active.cameraId} " +
                    "grid=${info.gridWidth}x${info.gridHeight} " +
                    "gain=${info.minimumGain}..${info.maximumGain}",
            )
            mainHandler.post { callback.onVignettingCalibrationCompleted(info) }
        } finally {
            image.close()
        }
    }

    private fun pairRawFrames(active: MeasurementAccumulator) {
        val common = active.images.keys.intersect(active.results.keys).toList()
        for (timestamp in common) {
            val image = active.images.remove(timestamp) ?: continue
            val result = active.results.remove(timestamp)
            try {
                if (result != null) {
                    val chars = characteristics
                    if (chars != null) {
                        if (active.target != null && active.rawMeterPoint == null) {
                            active.rawMeterPoint = MeteringAnalysis.resolveRawMeteringPoint(
                                image = image,
                                result = result,
                                characteristics = chars,
                                cameraInfo = cameraInfo,
                                frameAspect = active.frameAspect,
                                zoom = active.zoom,
                                target = active.target,
                                reference = active.previewReference,
                                screenToSensorRotationDegrees =
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
                            image = image,
                            result = result,
                            characteristics = chars,
                            cameraInfo = cameraInfo,
                            frameAspect = active.frameAspect,
                            zoom = active.zoom,
                            meteringMode = active.meteringMode,
                            calibrationStore = calibrationStore,
                            rawMeterPoint = active.rawMeterPoint,
                            vignettingStore = vignettingCalibrationStore,
                            applyVignettingCalibration = active.target != null,
                        )?.let(active.stats::add)
                    }
                }
            } finally {
                image.close()
                active.completedFrames += 1
            }
            if (active.stats.size < active.expectedFrames &&
                active.completedFrames < active.expectedFrames
            ) {
                // Submit the next frame only after this full-size Image has been closed.
                fillRawPipeline(active)
            }
        }
        when {
            active.stats.size >= active.expectedFrames -> finishMeasurement(active)
            active.completedFrames >= active.expectedFrames -> finishMeasurementWithError(
                localized(
                    "测光数据无效，请重试",
                    "Metering data was invalid. Please try again",
                ),
            )
            else -> fillRawPipeline(active)
        }
    }

    private fun finishMeasurement(active: MeasurementAccumulator) {
        if (activeMeasurement?.id != active.id) return
        val reading = MeteringFusion.fuse(active.stats, MeteringSource.RAW)
        if (reading == null) {
            finishMeasurementWithError(
                localized("测光数据无效，请重试", "Metering data was invalid. Please try again"),
            )
            return
        }
        cancelRawMeasurementTimeout()
        activeMeasurement = null
        activeRawRequest = null
        meteringOperationActive = false
        consecutiveRawMeasurementFailures = 0
        closePendingImages(active)
        val elapsedMs = (System.nanoTime() - active.startedAtNs) / 1_000_000.0
        Log.e(
            TAG,
            "RAW metering completed: frames=${reading.frameCount} ev100=${reading.sceneEv100} " +
                "luma=${reading.rawLuma} clipped=${reading.clippedFraction} " +
                "elapsedMs=${"%.1f".format(elapsedMs)} pipelineDepth=$RAW_PIPELINE_DEPTH",
        )
        mainHandler.post { callback.onMeterReading(reading) }
    }

    private fun finishMeasurementWithError(message: String) {
        val active = activeMeasurement ?: return
        cancelRawMeasurementTimeout()
        activeMeasurement = null
        activeRawRequest = null
        closePendingImages(active)
        Log.e(TAG, "RAW metering failed: $message")
        val canUsePreview = textureView?.isAvailable == true &&
            cameraDevice != null && captureSession != null
        if (canUsePreview) {
            consecutiveRawMeasurementFailures += 1
            downgradeAfterCompatibleMeasurement =
                meteringPipelineMode == MeteringPipelineMode.AUTO &&
                consecutiveRawMeasurementFailures >= RAW_FAILURES_BEFORE_DOWNGRADE
            measureCompatiblePreview(active.meteringMode, active.target)
        } else {
            meteringOperationActive = false
            postMeterError(message)
        }
    }

    private fun finishVignettingWithError(message: String) {
        val active = activeVignettingCapture ?: run {
            postVignettingError(message)
            return
        }
        cancelVignettingMeasurementTimeout()
        activeVignettingCapture = null
        closePendingVignettingImages(active)
        Log.e(TAG, "Vignetting calibration failed: $message")
        postVignettingError(message)
    }

    private fun postVignettingError(message: String) {
        mainHandler.post { callback.onVignettingCalibrationError(message) }
    }

    private fun closePendingImages(active: MeasurementAccumulator) {
        active.images.values.forEach { image ->
            try {
                image.close()
            } catch (_: Exception) {
                Unit
            }
        }
        active.images.clear()
        active.results.clear()
    }

    private fun closePendingVignettingImages(active: VignettingCapture) {
        active.images.values.forEach { image ->
            try {
                image.close()
            } catch (_: Exception) {
                Unit
            }
        }
        active.images.clear()
        active.results.clear()
    }

    private fun closeCamera() {
        cameraGeneration += 1
        cameraFailureStage = CameraFailureStage.OPENING
        cancelRawMeasurementTimeout()
        cancelYuvMeasurementTimeout()
        cancelVignettingMeasurementTimeout()
        fallbackMeasurementId++
        fallbackMeasuring = false
        activeYuvMeasurement = null
        compatibleYuvAvailable = true
        downgradeYuvSessionAfterMeasurement = false
        meteringOperationActive = false
        downgradeAfterCompatibleMeasurement = false
        activeMeasurement?.let(::closePendingImages)
        activeMeasurement = null
        activeRawRequest = null
        activeVignettingCapture?.let(::closePendingVignettingImages)
        activeVignettingCapture = null
        try {
            captureSession?.close()
        } catch (_: Exception) {
            Unit
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
            Unit
        }
        cameraDevice = null
        try {
            rawReader?.close()
        } catch (_: Exception) {
            Unit
        }
        rawReader = null
        try {
            trackingReader?.close()
        } catch (_: Exception) {
            Unit
        }
        trackingReader = null
        zoneCameraFrames.reset()
        previewSurface?.release()
        previewSurface = null
        previewSize = null
        previewFpsRange = null
        characteristics = null
        selectedPhysicalCameraId = null
        latestResult = null
        opening = false
    }

    private fun postInfo(info: CameraUiInfo) {
        cameraInfo = info
        Log.e(
            TAG,
            "Camera status: ${info.status}; id=${info.cameraId}; " +
                "logical=${info.logicalCameraId}; physical=${info.physicalCameraId}; " +
                "focal=${info.focalLengthMm}; raw=${info.rawAvailable}; " +
                "manual=${info.manualSensorAvailable}; preview=${info.previewSize}",
        )
        mainHandler.post { callback.onCameraInfo(info) }
    }

    private fun postMeterError(message: String) {
        mainHandler.post { callback.onMeteringError(message) }
    }

    private fun localized(chinese: String, english: String): String =
        callback.localized(chinese, english)

    private fun rawFrameCount(captureIso: Int?): Int =
        if ((captureIso ?: 0) > HIGH_ISO_THRESHOLD) {
            HIGH_ISO_RAW_FRAME_COUNT
        } else {
            LOW_ISO_RAW_FRAME_COUNT
        }

    companion object {
        private const val TAG = "lightstop"
        private const val RAW_PIPELINE_DEPTH = 1
        private const val MAX_METERING_REFERENCE_AGE_NS = 350_000_000L
        private const val PREVIEW_REFERENCE_LONG_EDGE = 384
        private const val RAW_READER_MAX_IMAGES = 1
        private const val HIGH_ISO_THRESHOLD = 800
        private const val LOW_ISO_RAW_FRAME_COUNT = 3
        private const val HIGH_ISO_RAW_FRAME_COUNT = 5
        private const val FALLBACK_BITMAP_SIZE = 96
        private const val RAW_METERING_TIMEOUT_MS = 8_000L
        private const val VIGNETTING_TIMEOUT_MS = 8_000L
        private const val SESSION_RECOVERY_DELAY_MS = 300L
        private const val STABLE_PREVIEW_RESET_DELAY_MS = 10_000L
        private const val MAX_TOTAL_RECOVERY_ATTEMPTS = 6
        private const val RAW_FAILURES_BEFORE_DOWNGRADE = 2
    }
}
