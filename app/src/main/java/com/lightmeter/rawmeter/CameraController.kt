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
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawReader: ImageReader? = null
    private var trackingReader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var selectedPhysicalCameraId: String? = null
    private var cameraInfo = CameraUiInfo()
    private var previewSize: Size? = null
    private var previewFpsRange: Range<Int>? = null
    @Volatile
    private var latestResult: CaptureResult? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var activeMeasurement: MeasurementAccumulator? = null
    private var activeRawRequest: CaptureRequest? = null
    private var activeVignettingCapture: VignettingCapture? = null
    private var fallbackMeasuring = false
    private var fallbackMeasurementId = 0
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
    ) {
        if (!cameraInfo.rawAvailable) {
            measureProcessedPreview(meteringMode, target)
            return
        }
        val displayedPreviewReference = target?.let(::captureDisplayedPreviewReference)
        val handler = cameraHandler ?: run {
            callback.onMeteringError(localized("相机尚未就绪", "Camera is not ready"))
            return
        }
        handler.post {
            if (activeMeasurement != null || activeVignettingCapture != null) return@post
            val device = cameraDevice
            val session = captureSession
            val reader = rawReader
            if (device == null || session == null || reader == null || !cameraInfo.rawAvailable) {
                postMeterError(
                    localized(
                        "当前摄像头无法输出 RAW",
                        "The current camera cannot output RAW",
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
                fillRawPipeline(accumulator)
                handler.postDelayed({
                    val active = activeMeasurement
                    if (active?.id == accumulator.id) {
                        finishMeasurementWithError(
                            localized(
                                "RAW 测光超时，请重试",
                                "RAW metering timed out. Please try again",
                            ),
                        )
                    }
                }, 8_000L)
            } catch (error: Exception) {
                finishMeasurementWithError(
                    localized(
                        "无法启动 RAW 测光：${error.message ?: "未知错误"}",
                        "Unable to start RAW metering: ${error.message ?: "unknown error"}",
                    ),
                )
            }
        }
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
                    localized("无法输出 RAW，无需校准", "RAW output is unavailable; no calibration is needed"),
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
                session.capture(request, vignettingCaptureCallback, handler)
                handler.postDelayed({
                    if (activeVignettingCapture?.id == capture.id) {
                        finishVignettingWithError(
                            localized(
                                "RAW 暗角校准超时，请重试",
                                "RAW vignetting calibration timed out. Please try again",
                            ),
                        )
                    }
                }, 8_000L)
            } catch (error: Exception) {
                finishVignettingWithError(
                    localized(
                        "无法读取暗角校准 RAW：${error.message ?: "未知错误"}",
                        "Unable to capture calibration RAW: ${error.message ?: "unknown error"}",
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
    fun currentUserCalibrationEv(): Double =
        calibrationStore.userCorrection(cameraInfo.cameraId.ifBlank { requestedCameraId ?: "0" })

    @Synchronized
    fun updateUserCalibration(
        referenceEv100: Double,
        measuredEv100: Double,
    ): Double {
        val cameraId = cameraInfo.cameraId.ifBlank { requestedCameraId ?: "0" }
        val updated = calibrationStore.updateUserCorrection(
            cameraId = cameraId,
            referenceEv100 = referenceEv100,
            measuredEv100 = measuredEv100,
        )
        Log.e(
            TAG,
            "User calibration updated: camera=$cameraId reference=$referenceEv100 " +
                "measured=$measuredEv100 correction=$updated",
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
                "correction=${restored.correctionEv}",
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

    private fun measureProcessedPreview(
        meteringMode: MeteringMode,
        target: ZoneMeteringTarget?,
    ) {
        if (fallbackMeasuring || activeMeasurement != null || activeVignettingCapture != null) return
        val texture = textureView
        if (texture?.isAvailable != true || cameraDevice == null || captureSession == null) {
            callback.onMeteringError(
                localized("相机预览尚未就绪", "Camera preview is not ready"),
            )
            return
        }
        if (latestResult == null) {
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
            "ISP preview metering started: frames=$FALLBACK_FRAME_COUNT mode=$meteringMode",
        )
        callback.onMeteringStarted(MeteringSource.ISP_PREVIEW, FALLBACK_FRAME_COUNT)
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
        handler.post {
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
                if (samples.size >= FALLBACK_FRAME_COUNT) {
                    finishProcessedPreview(id, samples)
                } else {
                    mainHandler.postDelayed(
                        { captureProcessedPreviewSample(id, samples, meteringMode, target) },
                        FALLBACK_SAMPLE_DELAY_MS,
                    )
                }
            }
        }
    }

    private fun finishProcessedPreview(id: Int, samples: List<MeteringFrameStat>) {
        if (!fallbackMeasuring || id != fallbackMeasurementId || samples.isEmpty()) return
        fallbackMeasuring = false
        val reading = MeteringFusion.fuse(samples, MeteringSource.ISP_PREVIEW) ?: return
        callback.onMeterReading(reading)
        Log.e(
            TAG,
            "ISP preview metering completed: frames=${samples.size} ev100=${reading.sceneEv100}",
        )
    }

    private fun finishProcessedPreviewWithError(id: Int, message: String) {
        if (id != fallbackMeasurementId) return
        fallbackMeasuring = false
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
        opening = true
        try {
            val discovered = cameraCatalog.discover()
            val selected = discovered.firstOrNull { it.cameraId == requestedCameraId }
                ?: cameraCatalog.preferredCamera(discovered)
            val selection = selected?.let { descriptor ->
                val logicalChars = cameraManager.getCameraCharacteristics(descriptor.logicalCameraId)
                val streamChars = descriptor.physicalCameraId?.let {
                    cameraManager.getCameraCharacteristics(it)
                } ?: logicalChars
                Triple(descriptor, logicalChars, streamChars)
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
            val (descriptor, _, chars) = selection
            requestedCameraId = descriptor.cameraId
            selectedPhysicalCameraId = descriptor.physicalCameraId
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
            val rawAvailable = rawCapability &&
                !map.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()
            val chosenPreview = CameraStreamSelector.choosePreviewSize(chars)
                ?: throw IllegalStateException(
                    localized("没有合适的预览尺寸", "No suitable preview size is available"),
                )
            previewSize = chosenPreview
            val chosenRange = CameraStreamSelector.chooseFpsRange(chars, chosenPreview)
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
                cameraId = descriptor.cameraId,
                logicalCameraId = descriptor.logicalCameraId,
                physicalCameraId = descriptor.physicalCameraId,
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
            trackingReader = CameraStreamSelector.chooseTrackingSize(map, chosenPreview)
                ?.let { trackingSize ->
                ImageReader.newInstance(
                    trackingSize.width,
                    trackingSize.height,
                    ImageFormat.YUV_420_888,
                    ZoneLumaBufferPool.DEFAULT_CAPACITY,
                ).also { imageReader ->
                    imageReader.setOnImageAvailableListener(
                        { reader -> onTrackingImageAvailable(reader) },
                        handler,
                    )
                }
            }
            Log.i(
                TAG,
                "Opening selection=${descriptor.cameraId}, logical=${descriptor.logicalCameraId}, " +
                    "physical=${descriptor.physicalCameraId}, focal=$focal, raw=$rawAvailable",
            )
            cameraManager.openCamera(descriptor.logicalCameraId, cameraStateCallback, handler)
        } catch (error: Exception) {
            opening = false
            postInfo(
                cameraInfo.copy(
                    status = localized(
                        "相机打开失败：${error.message ?: "未知错误"}",
                        "Unable to open camera: ${error.message ?: "unknown error"}",
                    ),
                ),
            )
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            opening = false
            cameraDevice = camera
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (cameraDevice === camera) cameraDevice = null
            opening = false
            postInfo(cameraInfo.copy(status = localized("相机已断开", "Camera disconnected")))
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (cameraDevice === camera) cameraDevice = null
            opening = false
            postInfo(cameraInfo.copy(status = localized("相机错误 $error", "Camera error $error")))
        }
    }

    private fun createSession(
        device: CameraDevice,
        includeTrackingStream: Boolean = true,
        includeRawStream: Boolean = true,
    ) {
        val preview = previewSurface ?: return
        val handler = cameraHandler ?: return
        val outputs = mutableListOf(preview)
        if (includeRawStream) rawReader?.surface?.let(outputs::add)
        if (includeTrackingStream) trackingReader?.surface?.let(outputs::add)
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cameraDevice == null) {
                    session.close()
                    return
                }
                captureSession = session
                startPreview(device, session, preview)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (includeTrackingStream && trackingReader != null) {
                    Log.w(TAG, "Tracking YUV stream unsupported; retrying without it")
                    session.close()
                    trackingReader?.close()
                    trackingReader = null
                    handler.post {
                        if (cameraDevice === device) {
                            createSession(device, false, includeRawStream)
                        }
                    }
                    return
                }
                if (includeRawStream && rawReader != null) {
                    Log.w(TAG, "RAW physical stream combination unsupported; using preview ISP")
                    session.close()
                    downgradeRawForCurrentCamera()
                    handler.post {
                        if (cameraDevice === device) createSession(device, false, false)
                    }
                    return
                }
                postInfo(
                    cameraInfo.copy(
                        status = localized(
                            "相机输出组合不受支持",
                            "This camera output combination is not supported",
                        ),
                    ),
                )
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
            if (includeTrackingStream && trackingReader != null) {
                Log.w(TAG, "Tracking YUV session failed; retrying without it", error)
                trackingReader?.close()
                trackingReader = null
                createSession(device, false, includeRawStream)
                return
            }
            if (includeRawStream && rawReader != null) {
                Log.w(TAG, "RAW session failed; using preview ISP", error)
                downgradeRawForCurrentCamera()
                createSession(device, false, false)
                return
            }
            postInfo(
                cameraInfo.copy(
                    status = localized(
                        "无法建立相机会话：${error.message}",
                        "Unable to create camera session: ${error.message}",
                    ),
                ),
            )
        }
    }

    private fun downgradeRawForCurrentCamera() {
        rawReader?.close()
        rawReader = null
        postInfo(
            cameraInfo.copy(
                rawAvailable = false,
                status = localized(
                    "该摄像头的 RAW 输出组合不受支持，正在启用兼容测光",
                    "This camera's RAW output combination is unsupported; enabling compatible metering",
                ),
            ),
        )
    }

    private fun startPreview(
        device: CameraDevice,
        session: CameraCaptureSession,
        preview: Surface,
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
                    "${cameraInfo.previewSize?.width}×${cameraInfo.previewSize?.height} · ${cameraInfo.previewFps} fps · RAW"
                } else {
                    localized(
                        "${cameraInfo.previewSize?.width}×${cameraInfo.previewSize?.height} · 兼容测光",
                        "${cameraInfo.previewSize?.width}×${cameraInfo.previewSize?.height} · Compatible metering",
                    )
                },
            )
            cameraInfo = readyInfo
            postInfo(readyInfo)
            if (!readyInfo.rawAvailable) mainHandler.post { callback.onRawUnavailable() }
            updatePreviewTransform(
                lastViewWidth,
                lastViewHeight,
                lastDisplayRotation,
                lastDisplayZoom,
            )
        } catch (error: CameraAccessException) {
            postInfo(
                cameraInfo.copy(
                    status = localized(
                        "预览启动失败：${error.message}",
                        "Unable to start preview: ${error.message}",
                    ),
                ),
            )
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
                    "RAW 帧捕获失败：${failure.reason}",
                    "RAW frame capture failed: ${failure.reason}",
                ),
            )
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            finishMeasurementWithError(
                localized(
                    "RAW 测光序列被相机中止",
                    "The RAW metering sequence was aborted by the camera",
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
                    "暗角校准 RAW 捕获失败：${failure.reason}",
                    "Vignetting calibration RAW capture failed: ${failure.reason}",
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

    /** Keep a small rolling capture window so sensor capture overlaps CPU analysis. */
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
                localized("RAW 测光会话已失效", "The RAW metering session is no longer available"),
            )
            return
        }
        active.submittedFrames += 1
        try {
            session.capture(request, rawCaptureCallback, handler)
        } catch (error: Exception) {
            finishMeasurementWithError(
                localized(
                    "无法提交 RAW 帧：${error.message ?: "未知错误"}",
                    "Unable to submit RAW frame: ${error.message ?: "unknown error"}",
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
                    localized("RAW 暗角数据无效，请重试", "Invalid RAW vignetting data. Please try again"),
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
                        "画面过暗、过亮或 RAW 数据无效，请调整均匀画面后重试",
                        "The frame is too dark, too bright, or invalid. Adjust the uniform scene and try again",
                    ),
                )
                return
            }
            vignettingCalibrationStore.save(active.cameraId, calibration)
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
                // Refill immediately after closing this full-size Image. If another paired frame
                // is waiting below, the camera can already capture its replacement while JNI
                // analyzes that queued frame.
                fillRawPipeline(active)
            }
        }
        when {
            active.stats.size >= active.expectedFrames -> finishMeasurement(active)
            active.completedFrames >= active.expectedFrames -> finishMeasurementWithError(
                localized(
                    "RAW 数据无效，请重试",
                    "RAW data was invalid. Please try again",
                ),
            )
            else -> fillRawPipeline(active)
        }
    }

    private fun finishMeasurement(active: MeasurementAccumulator) {
        if (activeMeasurement?.id != active.id) return
        activeMeasurement = null
        activeRawRequest = null
        closePendingImages(active)
        val reading = MeteringFusion.fuse(active.stats, MeteringSource.RAW) ?: return
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
        activeMeasurement = null
        activeRawRequest = null
        closePendingImages(active)
        Log.e(TAG, "RAW metering failed: $message")
        postMeterError(message)
    }

    private fun finishVignettingWithError(message: String) {
        val active = activeVignettingCapture ?: run {
            postVignettingError(message)
            return
        }
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
        fallbackMeasurementId++
        fallbackMeasuring = false
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
        private const val TAG = "RawLightMeter"
        private const val RAW_PIPELINE_DEPTH = 2
        private const val MAX_METERING_REFERENCE_AGE_NS = 350_000_000L
        private const val PREVIEW_REFERENCE_LONG_EDGE = 384
        private const val RAW_READER_MAX_IMAGES = 2
        private const val HIGH_ISO_THRESHOLD = 800
        private const val LOW_ISO_RAW_FRAME_COUNT = 3
        private const val HIGH_ISO_RAW_FRAME_COUNT = 5
        private const val FALLBACK_BITMAP_SIZE = 96
        private const val FALLBACK_FRAME_COUNT = 3
        private const val FALLBACK_SAMPLE_DELAY_MS = 70L
    }
}
