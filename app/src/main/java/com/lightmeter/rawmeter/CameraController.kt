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
import kotlin.math.min

class CameraController(
    private val context: Context,
    private val callback: CameraControllerCallback,
) : TextureView.SurfaceTextureListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraCatalog = CameraCatalog(cameraManager)
    private val calibrationStore = CameraCalibrationStore(context)
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
    private var fallbackMeasuring = false
    private var fallbackMeasurementId = 0
    @Volatile
    private var trackingFramesEnabled = false
    private var trackingFrameLogged = false

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
        trackingFramesEnabled = enabled
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
                    status = "正在切换摄像头",
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
    ) {
        if (!cameraInfo.rawAvailable) {
            measureProcessedPreview(meteringMode)
            return
        }
        val handler = cameraHandler ?: run {
            callback.onMeteringError("相机尚未就绪")
            return
        }
        handler.post {
            if (activeMeasurement != null) return@post
            val device = cameraDevice
            val session = captureSession
            val reader = rawReader
            if (device == null || session == null || reader == null || !cameraInfo.rawAvailable) {
                postMeterError("主摄无法输出 RAW")
                return@post
            }

            val count = 5
            val screenAspect =
                if (frameLandscape) {
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
            val accumulator = MeasurementAccumulator(
                id = measurementId.incrementAndGet(),
                expectedFrames = count,
                frameAspect = sensorFrameAspect,
                zoom = displayZoom.coerceAtLeast(1f),
                meteringMode = meteringMode,
            )
            activeMeasurement = accumulator
            Log.e(
                TAG,
                "RAW metering started: frames=$count zoom=${accumulator.zoom} " +
                    "format=${frameFormat.id} sensorAspect=$sensorFrameAspect mode=$meteringMode",
            )
            mainHandler.post { callback.onMeteringStarted(MeteringSource.RAW) }
            try {
                val requests = buildRawBurst(device, reader.surface, count)
                session.captureBurst(requests, rawCaptureCallback, handler)
                handler.postDelayed({
                    val active = activeMeasurement
                    if (active?.id == accumulator.id) {
                        finishMeasurementWithError("RAW 测光超时，请重试")
                    }
                }, 8_000L)
            } catch (error: Exception) {
                finishMeasurementWithError("无法启动 RAW 连拍：${error.message ?: "未知错误"}")
            }
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

    private fun measureProcessedPreview(meteringMode: MeteringMode) {
        if (fallbackMeasuring || activeMeasurement != null) return
        val texture = textureView
        if (texture?.isAvailable != true || cameraDevice == null || captureSession == null) {
            callback.onMeteringError("相机预览尚未就绪")
            return
        }
        if (latestResult == null) {
            callback.onMeteringError("正在等待相机曝光参数")
            return
        }
        fallbackMeasuring = true
        val id = ++fallbackMeasurementId
        val samples = mutableListOf<MeteringFrameStat>()
        Log.e(
            TAG,
            "ISP preview metering started: frames=$FALLBACK_FRAME_COUNT mode=$meteringMode",
        )
        callback.onMeteringStarted(MeteringSource.ISP_PREVIEW)
        captureProcessedPreviewSample(id, samples, meteringMode)
    }

    private fun captureProcessedPreviewSample(
        id: Int,
        samples: MutableList<MeteringFrameStat>,
        meteringMode: MeteringMode,
    ) {
        if (!fallbackMeasuring || id != fallbackMeasurementId) return
        val texture = textureView
        val result = latestResult
        val handler = cameraHandler
        if (texture?.isAvailable != true || result == null || handler == null) {
            finishProcessedPreviewWithError(id, "无法读取当前预览画面")
            return
        }
        val bitmap = try {
            texture.getBitmap(FALLBACK_BITMAP_SIZE, FALLBACK_BITMAP_SIZE)
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) {
            finishProcessedPreviewWithError(id, "无法读取当前预览画面")
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
                    )
                }
            } finally {
                bitmap.recycle()
            }
            mainHandler.post finishSample@{
                if (!fallbackMeasuring || id != fallbackMeasurementId) return@finishSample
                if (stat == null) {
                    finishProcessedPreviewWithError(id, "预览亮度或曝光参数不可用")
                    return@finishSample
                }
                samples += stat
                if (samples.size >= FALLBACK_FRAME_COUNT) {
                    finishProcessedPreview(id, samples)
                } else {
                    mainHandler.postDelayed(
                        { captureProcessedPreviewSample(id, samples, meteringMode) },
                        FALLBACK_SAMPLE_DELAY_MS,
                    )
                }
            }
        }
    }

    private fun finishProcessedPreview(id: Int, samples: List<MeteringFrameStat>) {
        if (!fallbackMeasuring || id != fallbackMeasurementId || samples.isEmpty()) return
        fallbackMeasuring = false
        val sortedEv = samples.map { it.ev100 }.sorted()
        val sortedLuma = samples.map { it.luma }.sorted()
        val representative = samples[samples.size / 2]
        callback.onMeterReading(
            MeterReading(
                sceneEv100 = median(sortedEv),
                rawLuma = median(sortedLuma),
                clippedFraction = samples.map { it.clipped }.average(),
                frameCount = samples.size,
                captureIso = representative.captureIso,
                exposureTimeNs = representative.exposureTimeNs,
                aperture = representative.aperture,
                source = MeteringSource.ISP_PREVIEW,
            ),
        )
        Log.e(
            TAG,
            "ISP preview metering completed: frames=${samples.size} ev100=${median(sortedEv)}",
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
            postInfo(cameraInfo.copy(status = "等待相机权限"))
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
                postInfo(CameraUiInfo(status = "没有可用的摄像头"))
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
                ?: throw IllegalStateException("相机没有输出配置")
            val rawAvailable = rawCapability &&
                !map.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()
            val chosenPreview = choosePreviewSize(chars)
                ?: throw IllegalStateException("没有合适的预览尺寸")
            previewSize = chosenPreview
            val chosenRange = chooseFpsRange(chars, chosenPreview)
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
                status = "正在打开摄像头",
            )
            postInfo(cameraInfo)

            surfaceTexture.setDefaultBufferSize(chosenPreview.width, chosenPreview.height)
            previewSurface?.release()
            previewSurface = Surface(surfaceTexture)

            rawReader?.close()
            rawReader = if (rawAvailable) {
                val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                val rawSize = rawSizes?.minByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: throw IllegalStateException("RAW 能力存在，但没有 RAW_SENSOR 尺寸")
                ImageReader.newInstance(
                    rawSize.width,
                    rawSize.height,
                    ImageFormat.RAW_SENSOR,
                    7,
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
            trackingReader = chooseTrackingSize(map, chosenPreview)?.let { trackingSize ->
                ImageReader.newInstance(
                    trackingSize.width,
                    trackingSize.height,
                    ImageFormat.YUV_420_888,
                    3,
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
            postInfo(cameraInfo.copy(status = "相机打开失败：${error.message ?: "未知错误"}"))
        }
    }

    private fun choosePreviewSize(chars: CameraCharacteristics): Size? {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val all = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val bounded = all.filter {
            max(it.width, it.height) <= 1920 && min(it.width, it.height) <= 1080
        }.ifEmpty {
            all.filter { max(it.width, it.height) <= 2560 }.ifEmpty { all }
        }
        if (bounded.isEmpty()) return null
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorAspect = if (active != null && active.height() > 0) {
            active.width().toDouble() / active.height()
        } else {
            4.0 / 3.0
        }
        val has60Range = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.any { it.lower <= 60 && it.upper >= 60 } == true
        val fast = if (has60Range) {
            bounded.filter { size ->
                val duration = map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
                val area = size.width.toLong() * size.height.toLong()
                area >= 1280L * 720L && (duration == 0L || duration <= 20_000_000L)
            }
        } else {
            emptyList()
        }
        val pool = fast.ifEmpty { bounded }
        return pool.minWithOrNull(
            compareBy<Size> {
                abs(it.width.toDouble() / it.height.toDouble() - sensorAspect)
            }.thenByDescending {
                it.width.toLong() * it.height.toLong()
            },
        )
    }

    private fun chooseTrackingSize(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        preview: Size,
    ): Size? {
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (sizes.isEmpty()) return null
        val previewAspect = preview.width.toDouble() / preview.height.coerceAtLeast(1)
        val compact = sizes.filter { size ->
            max(size.width, size.height) <= TRACKING_MAX_LONG_EDGE &&
                min(size.width, size.height) >= TRACKING_MIN_SHORT_EDGE
        }.ifEmpty {
            sizes.filter { max(it.width, it.height) <= 1280 }.ifEmpty { sizes }
        }
        return compact.minWithOrNull(
            compareBy<Size> {
                abs(it.width.toDouble() / it.height.coerceAtLeast(1) - previewAspect)
            }.thenBy {
                abs(max(it.width, it.height) - TRACKING_TARGET_LONG_EDGE)
            }.thenBy { it.width.toLong() * it.height.toLong() },
        )
    }

    private fun chooseFpsRange(
        chars: CameraCharacteristics,
        size: Size,
    ): Range<Int>? {
        val ranges =
            chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
                .orEmpty()
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val duration = map?.getOutputMinFrameDuration(SurfaceTexture::class.java, size) ?: 0L
        if (duration == 0L || duration <= 20_000_000L) {
            ranges.firstOrNull { it.lower <= 60 && it.upper >= 60 }?.let {
                return Range(60, 60)
            }
        }
        return ranges
            .filter { it.lower <= 30 && it.upper >= 30 }
            .minByOrNull { abs(it.lower - 30) + abs(it.upper - 30) }
            ?: ranges.maxByOrNull { it.upper }
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
            postInfo(cameraInfo.copy(status = "相机已断开"))
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (cameraDevice === camera) cameraDevice = null
            opening = false
            postInfo(cameraInfo.copy(status = "相机错误 $error"))
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
                postInfo(cameraInfo.copy(status = "相机输出组合不受支持"))
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
            postInfo(cameraInfo.copy(status = "无法建立相机会话：${error.message}"))
        }
    }

    private fun downgradeRawForCurrentCamera() {
        rawReader?.close()
        rawReader = null
        postInfo(
            cameraInfo.copy(
                rawAvailable = false,
                status = "该摄像头的 RAW 输出组合不受支持，正在启用兼容测光",
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
                    "${cameraInfo.previewSize?.width}×${cameraInfo.previewSize?.height} · 兼容测光"
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
            postInfo(cameraInfo.copy(status = "预览启动失败：${error.message}"))
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
            finishMeasurementWithError("RAW 帧捕获失败：${failure.reason}")
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            finishMeasurementWithError("RAW 连拍被相机中止")
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

    private fun buildRawBurst(
        device: CameraDevice,
        rawSurface: Surface,
        count: Int,
    ): List<CaptureRequest> {
        val last = latestResult
        val exposureTime = last?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val sensitivity = last?.get(CaptureResult.SENSOR_SENSITIVITY)
        val frameDuration = last?.get(CaptureResult.SENSOR_FRAME_DURATION)
        val useManual =
            cameraInfo.manualSensorAvailable && exposureTime != null && sensitivity != null
        return List(count) {
            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
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
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        try {
            if (!trackingFramesEnabled) return
            val plane = image.planes.firstOrNull() ?: return
            val width = image.width
            val height = image.height
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val luma = ByteArray(width * height)
            if (pixelStride == 1 && rowStride == width && buffer.remaining() >= luma.size) {
                buffer.get(luma)
            } else {
                for (row in 0 until height) {
                    val rowOffset = row * rowStride
                    val destinationOffset = row * width
                    for (column in 0 until width) {
                        val sourceOffset = rowOffset + column * pixelStride
                        if (sourceOffset < buffer.limit()) {
                            luma[destinationOffset + column] = buffer.get(sourceOffset)
                        }
                    }
                }
            }
            val displayDegrees = when (lastDisplayRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val rotation = (cameraInfo.sensorOrientationDegrees - displayDegrees + 360) % 360
            if (!trackingFrameLogged) {
                Log.i(
                    TAG,
                    "Zone tracking YUV ${width}x$height rotation=$rotation display=$displayDegrees",
                )
                trackingFrameLogged = true
            }
            callback.onZoneTrackingFrame(
                ZoneTrackingFrame(width, height, luma, rotation),
            )
        } finally {
            image.close()
        }
    }

    private fun onRawImageAvailable(reader: ImageReader) {
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
        active.images[image.timestamp] = image
        pairRawFrames(active)
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
                        MeteringAnalysis.analyzeRaw(
                            image = image,
                            result = result,
                            characteristics = chars,
                            cameraInfo = cameraInfo,
                            frameAspect = active.frameAspect,
                            zoom = active.zoom,
                            meteringMode = active.meteringMode,
                            calibrationStore = calibrationStore,
                        )?.let(active.stats::add)
                    }
                }
            } finally {
                image.close()
            }
        }
        if (active.stats.size >= active.expectedFrames) finishMeasurement(active)
    }

    private fun finishMeasurement(active: MeasurementAccumulator) {
        if (activeMeasurement?.id != active.id) return
        activeMeasurement = null
        closePendingImages(active)
        val sortedEv = active.stats.map { it.ev100 }.sorted()
        val sortedLuma = active.stats.map { it.luma }.sorted()
        val representative = active.stats[active.stats.size / 2]
        val reading = MeterReading(
            sceneEv100 = median(sortedEv),
            rawLuma = median(sortedLuma),
            clippedFraction = active.stats.map { it.clipped }.average(),
            frameCount = active.stats.size,
            captureIso = representative.captureIso,
            exposureTimeNs = representative.exposureTimeNs,
            aperture = representative.aperture,
            source = MeteringSource.RAW,
        )
        Log.e(
            TAG,
            "RAW metering completed: frames=${reading.frameCount} ev100=${reading.sceneEv100} " +
                "luma=${reading.rawLuma} clipped=${reading.clippedFraction}",
        )
        mainHandler.post { callback.onMeterReading(reading) }
    }

    private fun finishMeasurementWithError(message: String) {
        val active = activeMeasurement ?: return
        activeMeasurement = null
        closePendingImages(active)
        Log.e(TAG, "RAW metering failed: $message")
        postMeterError(message)
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

    private fun closeCamera() {
        fallbackMeasurementId++
        fallbackMeasuring = false
        activeMeasurement?.let(::closePendingImages)
        activeMeasurement = null
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
        trackingFrameLogged = false
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

    private fun median(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return Double.NaN
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) * 0.5
        } else {
            sorted[middle]
        }
    }

    companion object {
        private const val TAG = "RawLightMeter"
        private const val TRACKING_TARGET_LONG_EDGE = 640
        private const val TRACKING_MAX_LONG_EDGE = 720
        private const val TRACKING_MIN_SHORT_EDGE = 240
        private const val FALLBACK_BITMAP_SIZE = 96
        private const val FALLBACK_FRAME_COUNT = 3
        private const val FALLBACK_SAMPLE_DELAY_MS = 70L
    }
}
