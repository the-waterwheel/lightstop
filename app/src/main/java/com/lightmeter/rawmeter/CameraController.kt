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
import android.hardware.camera2.DngCreator
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Public camera facade used by MainActivity.
 *
 * This class selects capabilities and coordinates lifecycle, calibration and fallback. Camera2
 * resources, RAW metering, compatible metering, result pairing and recovery history are owned by
 * dedicated internal components below this facade.
 */
class CameraController(
    private val context: Context,
    private val callback: CameraControllerCallback,
) : TextureView.SurfaceTextureListener {

    private data class VignettingCapture(
        val id: Int,
        val cameraId: String,
        val framePairer: TimestampedResultPairer<Image, CaptureResult> =
            TimestampedResultPairer(
                releaseImage = Image::close,
                toleranceNs = VIGNETTING_PAIRING_TOLERANCE_NS,
            ),
    )

    private data class RawRecordCapture(
        val id: Int,
        val outputFile: File,
        val screenAspect: Float,
        val zoom: Float,
        val callback: (Result<RawRecordArtifact>) -> Unit,
        val framePairer: TimestampedResultPairer<Image, CaptureResult> =
            TimestampedResultPairer(
                releaseImage = Image::close,
                toleranceNs = RAW_RECORD_PAIRING_TOLERANCE_NS,
            ),
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sessionCoordinator = CameraSessionCoordinator(
        cameraManager = cameraManager,
        onRawImageAvailable = ::onRawImageAvailable,
        onTrackingImageAvailable = ::onTrackingImageAvailable,
        listener = object : CameraSessionCoordinatorListener {
            override fun onCameraOpened(generation: Int) {
                if (!started || generation != cameraGeneration) return
                cameraFailureStage = CameraFailureStage.CONFIGURING
            }

            override fun onSessionConfigured(
                device: CameraDevice,
                session: CameraCaptureSession,
                previewSurface: Surface,
                generation: Int,
            ) {
                if (!started || generation != cameraGeneration) return
                cameraFailureStage = CameraFailureStage.RUNNING
                startPreview(device, session, previewSurface, generation)
            }

            override fun onSessionConfigurationFailed(generation: Int, error: Exception?) {
                if (generation != cameraGeneration) return
                if (error == null) {
                    Log.w(TAG, "Camera session configuration failed for profile=$sessionProfile")
                } else {
                    Log.e(TAG, "Unable to create camera session for profile=$sessionProfile", error)
                }
                handleSessionFailure(generation)
            }

            override fun onCameraDisconnected(generation: Int) {
                if (generation != cameraGeneration) return
                handleCameraFailure(
                    CameraFailureKind.DISCONNECTED,
                    cameraFailureStage,
                    generation,
                )
            }

            override fun onCameraError(error: Int, generation: Int) {
                if (generation != cameraGeneration) return
                val failure = CameraFailureKind.fromDeviceError(error)
                Log.e(
                    TAG,
                    "Camera device callback error=$error kind=$failure " +
                        "stage=$cameraFailureStage profile=$sessionProfile generation=$generation",
                )
                handleCameraFailure(failure, cameraFailureStage, generation)
            }
        },
    )
    private val cameraCatalog = CameraCatalog(cameraManager)
    private val calibrationStore = CameraCalibrationStore(context)
    private val vignettingCalibrationStore = VignettingCalibrationStore(context)
    private val measurementId = AtomicInteger(0)
    private val compatibleMeter = CompatibleLightMeter(
        mainHandler = mainHandler,
        calibrationStore = calibrationStore,
        localized = ::localized,
        listener = object : CompatibleLightMeterListener {
            override fun onCompatibleMeteringStarted(source: MeteringSource, frameCount: Int) {
                callback.onMeteringStarted(source, frameCount)
            }

            override fun onCompatibleMeteringReading(reading: MeterReading) {
                meteringOperationActive = false
                callback.onMeterReading(reading)
            }

            override fun onCompatibleMeteringError(message: String) {
                meteringOperationActive = false
                finishCompatibleYuvRequest()
                callback.onMeteringError(message)
            }

            override fun onCompatibleMeteringCompleted(requiresPreviewOnlySession: Boolean) {
                afterCompatibleMeasurement(requiresPreviewOnlySession)
            }
        },
    )
    private val rawMeter = RawLightMeter(
        calibrationStore = calibrationStore,
        vignettingCalibrationStore = vignettingCalibrationStore,
        localized = ::localized,
        listener = object : RawLightMeterListener {
            override fun onRawCaptureResult(result: CaptureResult) {
                latestResult = result
                updateDynamicLensInfo(result)
            }

            override fun onRawMeteringStarted(frameCount: Int) {
                mainHandler.post { callback.onMeteringStarted(MeteringSource.RAW, frameCount) }
            }

            override fun onRawMeteringReading(reading: MeterReading) {
                resumePreviewAfterRawCapture()
                meteringOperationActive = false
                recoveryState.recordRawMeasurementSucceeded()
                mainHandler.post { callback.onMeterReading(reading) }
            }

            override fun onRawMeteringError(
                message: String,
                meteringMode: MeteringMode,
                target: ZoneMeteringTarget?,
                meteringRoiFraction: Float?,
            ) {
                resumePreviewAfterRawCapture()
                val canUsePreview = textureView?.isAvailable == true &&
                    cameraDevice != null && captureSession != null
                if (canUsePreview) {
                    downgradeAfterCompatibleMeasurement =
                        recoveryState.recordRawMeasurementFailed(meteringPipelineMode)
                    measureCompatiblePreview(meteringMode, target, meteringRoiFraction)
                } else {
                    meteringOperationActive = false
                    postMeterError(message)
                }
            }
        },
    )

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var started = false
    private val opening: Boolean
        get() = sessionCoordinator.isOpening
    @Volatile
    private var requestedCameraId: String? = null
    private var textureView: TextureView? = null
    private val cameraDevice: CameraDevice?
        get() = sessionCoordinator.device
    private val captureSession: CameraCaptureSession?
        get() = sessionCoordinator.session
    private val rawReader: ImageReader?
        get() = sessionCoordinator.rawReader
    private val trackingReader: ImageReader?
        get() = sessionCoordinator.trackingReader
    private var characteristics: CameraCharacteristics? = null
    private var selectedPhysicalCameraId: String? = null
    private var meteringPipelineMode = MeteringPipelineMode.AUTO
    private var rawHardwareAvailable = false
    private var trackingHardwareAvailable = false
    private val recoveryState = CameraRecoveryStateMachine(
        maxRecoveryAttempts = MAX_TOTAL_RECOVERY_ATTEMPTS,
        rawFailuresBeforeDowngrade = RAW_FAILURES_BEFORE_DOWNGRADE,
    )
    private val sessionProfile: CameraSessionProfile?
        get() = recoveryState.profile
    private val useLogicalCameraFallback: Boolean
        get() = recoveryState.usesLogicalCameraFallback
    private var cameraGeneration = 0
    private var cameraFailureStage = CameraFailureStage.OPENING
    private var cameraInfo = CameraUiInfo()
    private var previewSize: Size? = null
    private var previewFpsRange: Range<Int>? = null
    private var fpsRequestCeiling: Int? = DEFAULT_PREVIEW_FPS_CEILING
    @Volatile
    private var latestResult: CaptureResult? = null
    private var trackingFramesEnabled = false
    private var compatibleYuvRequestActive = false
    private var previewPausedForRawCapture = false
    @Volatile
    private var activeVignettingCapture: VignettingCapture? = null
    private var vignettingMeasurementTimeout: Runnable? = null
    @Volatile
    private var activeRawRecordCapture: RawRecordCapture? = null
    private var rawRecordTimeout: Runnable? = null
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
        val handler = cameraHandler
        if (handler == null) {
            trackingFramesEnabled = enabled
            return
        }
        handler.post {
            if (trackingFramesEnabled == enabled) return@post
            trackingFramesEnabled = enabled
            updatePreviewRepeatingRequest()
        }
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
        recoveryState.markPreviewStable()
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
        meteringAngleDegrees: Int = AngleMeteringMath.DEFAULT_DEGREES,
        requestedSource: MeteringSource? = null,
    ): Boolean {
        if (meteringOperationActive) return false
        meteringOperationActive = true
        val screenAspect = if (frameLandscape) {
            frameFormat.landscapeAspect
        } else {
            1f / frameFormat.landscapeAspect
        }
        val sensorOrientation =
            characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: cameraInfo.sensorOrientationDegrees
        val sensorFrameAspect = CameraPreviewTransform.screenAspectInSensorCoordinates(
            screenAspect = screenAspect,
            sensorOrientationDegrees = sensorOrientation,
            displayRotation = lastDisplayRotation,
        )
        val meteringRoiFraction = if (meteringMode == MeteringMode.ANGLE) {
            AngleMeteringMath.roiFraction(
                angleDegrees = meteringAngleDegrees,
                focalLengthMm = cameraInfo.focalLengthMm.toDouble(),
                sensorWidthMm = cameraInfo.sensorWidthMm.toDouble(),
                sensorHeightMm = cameraInfo.sensorHeightMm.toDouble(),
                sensorFrameAspect = sensorFrameAspect.toDouble(),
                zoom = displayZoom.toDouble(),
            )
        } else {
            null
        }
        if (requestedSource == MeteringSource.ISP_PREVIEW) {
            measureProcessedPreview(meteringMode, target, meteringRoiFraction)
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
                handler.post {
                    measureCompatiblePreview(meteringMode, target, meteringRoiFraction)
                }
            }
            return true
        }
        if (!cameraInfo.rawAvailable) {
            meteringOperationActive = false
            callback.onMeteringError(
                localized(
                    "当前摄像头无法读取 RAW 流",
                    "The RAW stream is unavailable for this camera",
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
            if (rawMeter.isMeasuring || activeVignettingCapture != null) {
                meteringOperationActive = false
                postMeterError(
                    localized("请等待当前操作完成", "Wait for the current operation"),
                )
                return@post
            }
            val rawContext = rawMeteringContext()
            if (rawContext == null || !cameraInfo.rawAvailable) {
                meteringOperationActive = false
                postMeterError(
                    localized(
                        "RAW 流尚未就绪",
                        "The RAW stream is not ready",
                    ),
                )
                return@post
            }

            val displayDegrees = when (lastDisplayRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val screenToSensorRotation =
                (sensorOrientation - displayDegrees + 360) % 360
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
            Log.i(
                TAG,
                "Starting RAW metering for frame format=${frameFormat.id} " +
                    "mode=$meteringMode angle=$meteringAngleDegrees roi=$meteringRoiFraction",
            )
            pausePreviewForRawCapture()
            val accepted = rawMeter.start(
                context = rawContext,
                frameAspect = sensorFrameAspect,
                zoom = displayZoom.coerceAtLeast(1f),
                meteringMode = meteringMode,
                meteringRoiFraction = meteringRoiFraction,
                target = target,
                previewReference = previewReference,
                screenToSensorRotationDegrees = screenToSensorRotation,
            )
            if (!accepted) {
                resumePreviewAfterRawCapture()
                meteringOperationActive = false
                postMeterError(localized("请等待当前操作完成", "Wait for the current operation"))
            }
        }
        return true
    }

    /** Captures one full-resolution DNG and a compact RAW sample map for history point metering. */
    fun captureRawRecord(
        outputFile: File,
        screenAspect: Float,
        zoom: Float,
        completion: (Result<RawRecordArtifact>) -> Unit,
    ) {
        val handler = cameraHandler
        if (handler == null) {
            completion(Result.failure(IllegalStateException(localized("相机尚未就绪", "Camera is not ready"))))
            return
        }
        handler.post {
            if (meteringOperationActive || rawMeter.isMeasuring || compatibleMeter.isMeasuring ||
                activeVignettingCapture != null || activeRawRecordCapture != null
            ) {
                mainHandler.post {
                    completion(Result.failure(IllegalStateException(localized("请等待当前操作完成", "Wait for the current operation"))))
                }
                return@post
            }
            val context = rawMeteringContext()
            if (!cameraInfo.rawAvailable || context == null) {
                mainHandler.post {
                    completion(Result.failure(UnsupportedOperationException(localized("当前摄像头不支持 RAW 记录", "This camera does not support RAW recording"))))
                }
                return@post
            }
            val capture = RawRecordCapture(
                measurementId.incrementAndGet(),
                outputFile,
                screenAspect,
                zoom,
                completion,
            )
            activeRawRecordCapture = capture
            meteringOperationActive = true
            try {
                outputFile.parentFile?.mkdirs()
                pausePreviewForRawCapture()
                scheduleRawRecordTimeout(capture, handler)
                context.session.capture(rawMeter.buildCaptureRequest(context), rawRecordCaptureCallback, handler)
            } catch (error: Exception) {
                finishRawRecord(Result.failure(error))
            }
        }
    }

    private fun rawMeteringContext(): RawMeteringContext? {
        val device = cameraDevice ?: return null
        val session = captureSession ?: return null
        val reader = rawReader ?: return null
        val handler = cameraHandler ?: return null
        val chars = characteristics ?: return null
        return RawMeteringContext(
            device = device,
            session = session,
            rawSurface = reader.surface,
            handler = handler,
            latestResult = latestResult,
            characteristics = chars,
            cameraInfo = cameraInfo,
            selectedPhysicalCameraId = selectedPhysicalCameraId,
        )
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
            if (rawMeter.isMeasuring || compatibleMeter.isMeasuring ||
                activeVignettingCapture != null
            ) {
                postVignettingError(
                    localized("请等待当前操作完成", "Wait for the current operation"),
                )
                return@post
            }
            val rawContext = rawMeteringContext()
            if (!cameraInfo.rawAvailable || rawContext == null) {
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
                val request = rawMeter.buildCaptureRequest(rawContext)
                scheduleVignettingMeasurementTimeout(capture, handler)
                pausePreviewForRawCapture()
                rawContext.session.capture(request, vignettingCaptureCallback, handler)
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
        if (meteringPipelineMode == MeteringPipelineMode.ISOLATED) {
            MeteringSource.ISP_PREVIEW
        } else {
            compatibleMeter.preferredSource(trackingReader != null)
        }

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
        meteringRoiFraction: Float? = null,
        forceProcessedPreview: Boolean = false,
    ) {
        if (compatibleMeter.isMeasuring || rawMeter.isMeasuring ||
            activeVignettingCapture != null
        ) {
            meteringOperationActive = false
            postMeterError(localized("请等待当前操作完成", "Wait for the current operation"))
            return
        }
        val useProcessedPreview = forceProcessedPreview ||
            meteringPipelineMode == MeteringPipelineMode.ISOLATED
        val needsYuvRequest = !useProcessedPreview && target == null &&
            trackingReader != null && compatibleMeter.yuvAvailable
        val accepted = compatibleMeter.measure(
            meteringMode = meteringMode,
            target = target,
            context = compatibleMeteringContext(),
            forceProcessedPreview = useProcessedPreview,
            meteringRoiFraction = meteringRoiFraction,
        )
        if (!accepted) {
            meteringOperationActive = false
            postMeterError(localized("请等待当前操作完成", "Wait for the current operation"))
        } else if (needsYuvRequest) {
            compatibleYuvRequestActive = true
            updatePreviewRepeatingRequest()
        }
    }

    private fun compatibleMeteringContext() = CompatibleMeteringContext(
        textureView = textureView,
        cameraHandler = cameraHandler,
        trackingReaderAvailable = trackingReader != null,
        cameraReady = { cameraDevice != null && captureSession != null },
        latestResult = { latestResult },
        characteristics = { characteristics },
        cameraId = { cameraInfo.cameraId },
    )

    private fun measureProcessedPreview(
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget?,
        meteringRoiFraction: Float?,
    ) {
        measureCompatiblePreview(
            meteringMode,
            target,
            meteringRoiFraction,
            forceProcessedPreview = true,
        )
    }

    private fun afterCompatibleMeasurement(requiresPreviewOnlySession: Boolean) {
        cameraHandler?.post {
            finishCompatibleYuvRequest()
            when {
                requiresPreviewOnlySession &&
                    sessionProfile == CameraSessionProfile.COMPATIBLE -> {
                    if (started && cameraDevice != null) {
                        scheduleRecovery(
                            CameraSessionProfile.PREVIEW_ONLY,
                            localized(
                                "已切换到更稳定的预览方式",
                                "Using a more stable preview method",
                            ),
                            SESSION_RECOVERY_DELAY_MS,
                        )
                    }
                }
                downgradeAfterCompatibleMeasurement -> {
                    downgradeAfterCompatibleMeasurement = false
                    if (!started || cameraDevice == null) return@post
                    val compatible = if (trackingHardwareAvailable && compatibleMeter.yuvAvailable) {
                        CameraSessionProfile.COMPATIBLE
                    } else {
                        CameraSessionProfile.PREVIEW_ONLY
                    }
                    scheduleRecovery(
                        compatible,
                        localized(
                            "RAW 流不可用，已改用预览流",
                            "RAW is unavailable; using the preview stream",
                        ),
                        SESSION_RECOVERY_DELAY_MS,
                    )
                }
            }
        }
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
        try {
            val discovered = cameraCatalog.discover()
            val selected = discovered.firstOrNull { it.cameraId == requestedCameraId }
                ?: cameraCatalog.preferredCamera(discovered)
            val selection = selected?.let { descriptor ->
                val logicalChars = cameraManager.getCameraCharacteristics(descriptor.logicalCameraId)
                val syncType = logicalChars.get(
                    CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE,
                )
                val approximateSync = syncType ==
                    CameraMetadata.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE
                val routePhysical = descriptor.physicalCameraId != null &&
                    !useLogicalCameraFallback && !approximateSync
                if (descriptor.physicalCameraId != null && approximateSync) {
                    Log.i(
                        TAG,
                        "Logical camera ${descriptor.logicalCameraId} reports APPROXIMATE " +
                            "physical sync; using the logical route",
                    )
                }
                val effectivePhysicalId = if (routePhysical) descriptor.physicalCameraId else null
                val streamChars = effectivePhysicalId?.let {
                    cameraManager.getCameraCharacteristics(it)
                } ?: logicalChars
                Triple(descriptor, effectivePhysicalId, streamChars)
            }
            if (selection == null) {
                postInfo(
                    CameraUiInfo(
                        status = localized("没有可用的摄像头", "No camera is available"),
                    ),
                )
                return
            }
            val (descriptor, effectivePhysicalId, chars) = selection
            val activeCameraId = if (effectivePhysicalId != null) {
                descriptor.cameraId
            } else {
                descriptor.logicalCameraId
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
            val hardwareLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            // LEGACY devices guarantee neither RAW_SENSOR output nor per-frame control; treat a
            // stray RAW advertisement on a LEGACY HAL as unusable instead of letting the session
            // fail later.
            rawHardwareAvailable = rawCapability &&
                !map.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty() &&
                hardwareLevel != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
            val chosenPreview = CameraStreamSelector.choosePreviewSize(chars)
                ?: throw IllegalStateException(
                    localized("没有合适的预览尺寸", "No suitable preview size is available"),
                )
            previewSize = chosenPreview
            val trackingSize = CameraStreamSelector.chooseTrackingSize(map, chosenPreview)
            trackingHardwareAvailable = trackingSize != null
            val profile = recoveryState.resolveProfile(
                mode = meteringPipelineMode,
                rawSupported = rawHardwareAvailable,
                trackingSupported = trackingHardwareAvailable,
            )
            val rawAvailable = rawHardwareAvailable && profile.usesRaw
            val rawSize = if (rawAvailable) {
                val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                rawSizes?.minByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: throw IllegalStateException(
                        localized(
                            "RAW 能力存在，但没有 RAW_SENSOR 尺寸",
                            "RAW is reported but no RAW_SENSOR size is available",
                        ),
                    )
            } else {
                null
            }
            val configuredTrackingSize = trackingSize.takeIf { profile.usesTracking }
            val chosenRange = fpsRequestCeiling?.let { ceiling ->
                CameraStreamSelector.chooseFpsRange(
                    characteristics = chars,
                    previewSize = chosenPreview,
                    trackingSize = configuredTrackingSize,
                    requestedCeiling = ceiling,
                )
            }
            previewFpsRange = chosenRange

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

            sessionCoordinator.configureOutputs(
                surfaceTexture = surfaceTexture,
                previewSize = chosenPreview,
                rawSize = rawSize,
                trackingSize = configuredTrackingSize,
                handler = handler,
            )
            Log.i(
                TAG,
                "Opening selection=${descriptor.cameraId}, logical=${descriptor.logicalCameraId}, " +
                    "physical=$effectivePhysicalId, profile=$profile, focal=$focal, " +
                    "raw=$rawAvailable, hardwareLevel=$hardwareLevel",
            )
            sessionCoordinator.open(
                logicalCameraId = descriptor.logicalCameraId,
                physicalCameraId = effectivePhysicalId,
                generation = generation,
                handler = handler,
            )
        } catch (error: Exception) {
            handlePreparationFailure(error, generation)
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
        val next = recoveryState.nextProfile(
            mode = meteringPipelineMode,
            rawSupported = rawHardwareAvailable,
            trackingSupported = trackingHardwareAvailable,
        )
        if (next != null) {
            scheduleRecovery(
                next,
                localized(
                    "当前方式无法启动，正在尝试更稳定的方式",
                    "This method could not start. Trying a more stable one",
                ),
                SESSION_RECOVERY_DELAY_MS,
            )
        } else if (!tryLogicalCameraFallback()) {
            finishCameraFailure(
                localized(
                    "相机无法正常启动，请尝试稳定模式或兼容模式，或重启手机",
                    "The camera could not start. Try Stable or Compatibility mode, or restart the phone",
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
        val decision = recoveryState.decideFailure(
            failure = failure,
            stage = stage,
            mode = meteringPipelineMode,
            rawSupported = rawHardwareAvailable,
            trackingSupported = trackingHardwareAvailable,
        )
        when (decision.action) {
            CameraRecoveryAction.RETRY,
            CameraRecoveryAction.DOWNGRADE,
            -> scheduleRecovery(
                decision.profile ?: sessionProfile ?: CameraSessionProfile.PREVIEW_ONLY,
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
        if (!recoveryState.enableLogicalCameraFallback(selectedPhysicalCameraId != null)) {
            return false
        }
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
        if (!recoveryState.beginRecovery(profile)) {
            finishCameraFailure(
                localized(
                    "相机多次启动失败，请稍后重试或重启手机",
                    "The camera failed repeatedly. Try again later or restart the phone",
                ),
            )
            return
        }
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
        if (meteringOperationActive || rawMeter.isMeasuring || compatibleMeter.isMeasuring) {
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
            "相机运行异常，正在尝试更稳定的方式",
            "The camera stopped unexpectedly. Trying a more stable method",
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
            "相机无法正常启动，请尝试稳定模式或兼容模式，或重启手机",
            "The camera could not start. Try Stable or Compatibility mode, or restart the phone",
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
        recoveryState.reset()
        downgradeAfterCompatibleMeasurement = false
        fpsRequestCeiling = DEFAULT_PREVIEW_FPS_CEILING
    }

    private fun startPreview(
        device: CameraDevice,
        session: CameraCaptureSession,
        preview: Surface,
        generation: Int,
    ) {
        try {
            submitPreviewRepeatingRequest(device, session, preview)
            val readyInfo = cameraInfo.copy(
                status = readyCameraStatus(),
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
                        recoveryState.markPreviewStable()
                    }
                },
                STABLE_PREVIEW_RESET_DELAY_MS,
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start preview for profile=$sessionProfile", error)
            if (generation == cameraGeneration) handlePreviewRequestFailure(generation)
        }
    }

    /** Rebuilds the request so YUV is targeted only while tracking or one sample needs it. */
    private fun submitPreviewRepeatingRequest(
        device: CameraDevice,
        session: CameraCaptureSession,
        preview: Surface,
    ) {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(preview)
        val includeYuv = trackingReader != null &&
            (trackingFramesEnabled || compatibleYuvRequestActive)
        if (includeYuv) trackingReader?.surface?.let(builder::addTarget)
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        previewFpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        setSupportedAutoFocus(builder)
        session.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
        Log.i(
            TAG,
            "Preview request submitted: fps=$previewFpsRange yuv=$includeYuv " +
                "profile=$sessionProfile",
        )
    }

    private fun updatePreviewRepeatingRequest() {
        if (previewPausedForRawCapture) return
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val preview = sessionCoordinator.previewSurface ?: return
        try {
            submitPreviewRepeatingRequest(device, session, preview)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to update preview request", error)
            handlePreviewRequestFailure(cameraGeneration)
        }
    }

    /** Freezes the last displayed frame and drains repeating preview/YUV before RAW captures. */
    private fun pausePreviewForRawCapture() {
        if (previewPausedForRawCapture) return
        val session = captureSession ?: return
        try {
            session.stopRepeating()
            previewPausedForRawCapture = true
        } catch (error: Exception) {
            Log.w(TAG, "Unable to pause preview before RAW capture", error)
        }
    }

    private fun resumePreviewAfterRawCapture() {
        if (!previewPausedForRawCapture) return
        previewPausedForRawCapture = false
        updatePreviewRepeatingRequest()
    }

    private fun finishCompatibleYuvRequest() {
        val handler = cameraHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post(::finishCompatibleYuvRequest)
            return
        }
        if (!compatibleYuvRequestActive) return
        compatibleYuvRequestActive = false
        updatePreviewRepeatingRequest()
    }

    private fun handlePreviewRequestFailure(generation: Int) {
        if (generation != cameraGeneration) return
        val currentCeiling = fpsRequestCeiling
        if (previewFpsRange != null && currentCeiling != null) {
            fpsRequestCeiling = if (currentCeiling > CONSERVATIVE_PREVIEW_FPS_CEILING) {
                CONSERVATIVE_PREVIEW_FPS_CEILING
            } else {
                null
            }
            scheduleRecovery(
                sessionProfile ?: CameraSessionProfile.PREVIEW_ONLY,
                localized(
                    "正在使用更稳定的预览设置",
                    "Trying a more stable preview setting",
                ),
                SESSION_RECOVERY_DELAY_MS,
            )
        } else {
            handleSessionFailure(generation)
        }
    }

    private fun readyCameraStatus(): String {
        val size = "${cameraInfo.previewSize?.width}×${cameraInfo.previewSize?.height}"
        return when (meteringPipelineMode) {
            MeteringPipelineMode.AUTO -> if (cameraInfo.rawAvailable) {
                localized("$size · 高精度测光", "$size · High-accuracy metering")
            } else {
                localized("$size · 预览流测光", "$size · Preview-stream metering")
            }
            MeteringPipelineMode.ISOLATED ->
                localized("$size · 稳定模式", "$size · Stable mode")
            MeteringPipelineMode.FAST ->
                localized("$size · 兼容模式", "$size · Compatibility mode")
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
            // A few physical-camera HALs omit SENSOR_TIMESTAMP from the physical result even
            // though the logical TotalCaptureResult carries the timestamp for the same frame.
            compatibleMeter.onCaptureResult(
                effectiveResult,
                result.get(CaptureResult.SENSOR_TIMESTAMP),
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
            active.framePairer.offerResult(timestamp, effectiveResult)?.let { pair ->
                processVignettingFramePair(active, pair)
            }
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
        if (compatibleMeter.onImageAvailable(reader)) return
        zoneCameraFrames.onImageAvailable(
            reader,
            cameraInfo.sensorOrientationDegrees,
            lastDisplayRotation,
        )
    }

    private fun onRawImageAvailable(reader: ImageReader) {
        val rawRecord = activeRawRecordCapture
        if (rawRecord != null) {
            val image = try {
                reader.acquireNextImage()
            } catch (_: IllegalStateException) {
                null
            } ?: return
            rawRecord.framePairer.offerImage(image.timestamp, image)?.let { pair ->
                processRawRecordPair(rawRecord, pair)
            }
            return
        }
        val vignetting = activeVignettingCapture
        if (vignetting != null) {
            val image = try {
                reader.acquireNextImage()
            } catch (_: IllegalStateException) {
                null
            } ?: return
            vignetting.framePairer.offerImage(image.timestamp, image)?.let { pair ->
                processVignettingFramePair(vignetting, pair)
            }
            return
        }
        rawMeter.onImageAvailable(reader)
    }

    private fun processRawRecordPair(
        active: RawRecordCapture,
        pair: TimestampedResultPair<Image, CaptureResult>,
    ) {
        val chars = characteristics
        try {
            if (chars == null) {
                finishRawRecord(Result.failure(IllegalStateException(localized("RAW 元数据无效", "RAW metadata is invalid"))))
                return
            }
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: cameraInfo.sensorOrientationDegrees
            val displayDegrees = when (lastDisplayRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val screenToSensorRotation = (sensorOrientation - displayDegrees + 360) % 360
            val sensorAspect = CameraPreviewTransform.screenAspectInSensorCoordinates(
                active.screenAspect,
                sensorOrientation,
                lastDisplayRotation,
            )
            val grid = RecordedRawGridSampler.sample(
                pair.image,
                pair.result,
                chars,
                sensorAspect,
                active.zoom,
                screenToSensorRotation,
            )
                ?: throw IllegalStateException(localized("RAW 数据无效", "RAW data is invalid"))
            FileOutputStream(active.outputFile).use { stream ->
                DngCreator(chars, pair.result).use { creator -> creator.writeImage(stream, pair.image) }
            }
            finishRawRecord(Result.success(RawRecordArtifact(active.outputFile.absolutePath, grid)))
        } catch (error: Exception) {
            finishRawRecord(Result.failure(error))
        } finally {
            pair.image.close()
        }
    }

    private val rawRecordCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val effective = effectiveCaptureResult(result)
            latestResult = effective
            val timestamp = effective.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: result.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: return
            val active = activeRawRecordCapture ?: return
            active.framePairer.offerResult(timestamp, effective)?.let { pair -> processRawRecordPair(active, pair) }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            finishRawRecord(Result.failure(IllegalStateException(localized("RAW 记录失败", "RAW recording failed"))))
        }
    }

    private fun scheduleRawRecordTimeout(active: RawRecordCapture, handler: Handler) {
        cancelRawRecordTimeout(handler)
        lateinit var timeout: Runnable
        timeout = Runnable {
            if (rawRecordTimeout !== timeout || activeRawRecordCapture?.id != active.id) return@Runnable
            rawRecordTimeout = null
            finishRawRecord(Result.failure(IllegalStateException(localized("RAW 记录超时", "RAW recording timed out"))))
        }
        rawRecordTimeout = timeout
        handler.postDelayed(timeout, RAW_RECORD_TIMEOUT_MS)
    }

    private fun cancelRawRecordTimeout(handler: Handler? = cameraHandler) {
        rawRecordTimeout?.let { handler?.removeCallbacks(it) }
        rawRecordTimeout = null
    }

    private fun finishRawRecord(result: Result<RawRecordArtifact>) {
        val active = activeRawRecordCapture ?: return
        activeRawRecordCapture = null
        cancelRawRecordTimeout()
        active.framePairer.clear()
        meteringOperationActive = false
        resumePreviewAfterRawCapture()
        if (result.isFailure) active.outputFile.delete()
        mainHandler.post { active.callback(result) }
    }

    private fun processVignettingFramePair(
        active: VignettingCapture,
        pair: TimestampedResultPair<Image, CaptureResult>,
    ) {
        val chars = characteristics
        try {
            if (chars == null) {
                finishVignettingWithError(
                    localized(
                        "暗角校准数据无效，请重试",
                        "Vignetting calibration data was invalid. Please try again",
                    ),
                )
                return
            }
            val calibration = MeteringAnalysis.createVignettingCalibrationMap(
                image = pair.image,
                result = pair.result,
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
            resumePreviewAfterRawCapture()
            val info = vignettingCalibrationStore.info(active.cameraId) ?: return
            Log.i(
                TAG,
                "Vignetting calibration saved camera=${active.cameraId} " +
                    "grid=${info.gridWidth}x${info.gridHeight} " +
                    "gain=${info.minimumGain}..${info.maximumGain}",
            )
            mainHandler.post { callback.onVignettingCalibrationCompleted(info) }
        } finally {
            pair.image.close()
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
        resumePreviewAfterRawCapture()
        Log.e(TAG, "Vignetting calibration failed: $message")
        postVignettingError(message)
    }

    private fun postVignettingError(message: String) {
        mainHandler.post { callback.onVignettingCalibrationError(message) }
    }

    private fun closePendingVignettingImages(active: VignettingCapture) {
        active.framePairer.clear()
    }

    private fun closeCamera() {
        cameraGeneration += 1
        cameraFailureStage = CameraFailureStage.OPENING
        cancelVignettingMeasurementTimeout()
        activeRawRecordCapture?.let { capture ->
            activeRawRecordCapture = null
            cancelRawRecordTimeout()
            capture.framePairer.clear()
            capture.outputFile.delete()
            mainHandler.post {
                capture.callback(Result.failure(IllegalStateException(localized("RAW 记录已中止", "RAW recording was interrupted"))))
            }
        }
        rawMeter.cancel(cameraHandler)
        compatibleMeter.cancel(cameraHandler, resetYuvAvailability = true)
        meteringOperationActive = false
        downgradeAfterCompatibleMeasurement = false
        compatibleYuvRequestActive = false
        previewPausedForRawCapture = false
        activeVignettingCapture?.let(::closePendingVignettingImages)
        activeVignettingCapture = null
        sessionCoordinator.close()
        zoneCameraFrames.reset()
        previewSize = null
        previewFpsRange = null
        characteristics = null
        selectedPhysicalCameraId = null
        latestResult = null
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

    companion object {
        private const val TAG = "lightstop"
        private const val MAX_METERING_REFERENCE_AGE_NS = 350_000_000L
        private const val PREVIEW_REFERENCE_LONG_EDGE = 384
        private const val VIGNETTING_TIMEOUT_MS = 8_000L
        private const val RAW_RECORD_TIMEOUT_MS = 8_000L
        private const val RAW_RECORD_PAIRING_TOLERANCE_NS = 40_000_000L
        private const val SESSION_RECOVERY_DELAY_MS = 300L
        private const val STABLE_PREVIEW_RESET_DELAY_MS = 10_000L
        private const val MAX_TOTAL_RECOVERY_ATTEMPTS = 6
        private const val RAW_FAILURES_BEFORE_DOWNGRADE = 2
        private const val DEFAULT_PREVIEW_FPS_CEILING = 30
        private const val CONSERVATIVE_PREVIEW_FPS_CEILING = 24
        private const val VIGNETTING_PAIRING_TOLERANCE_NS = 16_000_000L
    }
}
