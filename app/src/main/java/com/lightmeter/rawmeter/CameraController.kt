package com.lightmeter.rawmeter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
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
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CameraController(
    private val context: Context,
    private val callback: Callback,
) : TextureView.SurfaceTextureListener {

    interface Callback {
        fun onCameraInfo(info: CameraUiInfo)
        fun onRawUnavailable()
        fun onMeteringStarted(source: MeteringSource)
        fun onMeterReading(reading: MeterReading)
        fun onMeteringError(message: String)
    }

    private data class FrameStat(
        val ev100: Double,
        val luma: Double,
        val clipped: Double,
        val captureIso: Int,
        val exposureTimeNs: Long,
        val aperture: Float,
    )

    private data class PreviewRegionStat(
        val luma: Double,
        val clipped: Double,
    )

    private data class MeasurementAccumulator(
        val id: Int,
        val expectedFrames: Int,
        val frameAspect: Float,
        val zoom: Float,
        val meteringMode: MeteringMode,
        val images: MutableMap<Long, Image> = TreeMap(),
        val results: MutableMap<Long, TotalCaptureResult> = TreeMap(),
        val stats: MutableList<FrameStat> = mutableListOf(),
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val measurementId = AtomicInteger(0)
    private val calibrationPreferences =
        context.getSharedPreferences("raw_meter_calibration", Context.MODE_PRIVATE)
    private val rawCalibrationCache = mutableMapOf<String, Double>()
    private val userCalibrationCache = mutableMapOf<String, Double>()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var started = false
    private var opening = false
    private var textureView: TextureView? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawReader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var cameraInfo = CameraUiInfo()
    private var previewSize: Size? = null
    private var previewFpsRange: Range<Int>? = null
    @Volatile
    private var latestResult: TotalCaptureResult? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var activeMeasurement: MeasurementAccumulator? = null
    private var fallbackMeasuring = false
    private var fallbackMeasurementId = 0

    private var lastViewWidth = 0
    private var lastViewHeight = 0
    private var lastDisplayRotation = Surface.ROTATION_0
    private var lastDisplayZoom = 1f
    private val previewTransformRevision = AtomicInteger(0)

    fun attach(texture: TextureView) {
        textureView = texture
        texture.surfaceTextureListener = this
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
            val matrix = Matrix()
            val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            val centerX = viewRect.centerX()
            val centerY = viewRect.centerY()
            if (displayRotation == Surface.ROTATION_90 ||
                displayRotation == Surface.ROTATION_270
            ) {
                val bufferRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                val scale = max(
                    viewHeight.toFloat() / size.height,
                    viewWidth.toFloat() / size.width,
                )
                matrix.postScale(scale, scale, centerX, centerY)
                matrix.postRotate(
                    if (displayRotation == Surface.ROTATION_90) -90f else 90f,
                    centerX,
                    centerY,
                )
            } else {
                // In portrait the camera buffer is displayed as height × width. TextureView
                // first stretches that oriented buffer to its own arbitrary film-frame bounds.
                // Counter that implicit non-uniform stretch, then center-crop with one physical
                // pixel scale so circles and faces keep their real aspect in every frame format.
                val orientedBufferWidth = size.height.toFloat()
                val orientedBufferHeight = size.width.toFloat()
                val implicitScaleX = viewWidth / orientedBufferWidth
                val implicitScaleY = viewHeight / orientedBufferHeight
                val centerCropScale = max(implicitScaleX, implicitScaleY)
                matrix.postScale(
                    centerCropScale / implicitScaleX,
                    centerCropScale / implicitScaleY,
                    centerX,
                    centerY,
                )
                if (displayRotation == Surface.ROTATION_180) {
                    matrix.postRotate(180f, centerX, centerY)
                }
            }
            matrix.postScale(displayZoom, displayZoom, centerX, centerY)
            texture.setTransform(matrix)
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
            val sensorFrameAspect = screenAspectInSensorCoordinates(screenAspect)
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
        userCalibrationEv(cameraInfo.cameraId.ifBlank { "0" })

    @Synchronized
    fun updateUserCalibration(
        referenceEv100: Double,
        measuredEv100: Double,
    ): Double {
        val cameraId = cameraInfo.cameraId.ifBlank { "0" }
        val key = userCalibrationKey(cameraId)
        val updated = CalibrationMath.updatedUserCorrection(
            currentCorrectionEv = userCalibrationEv(cameraId),
            referenceEv100 = referenceEv100,
            measuredEv100 = measuredEv100,
        )
        userCalibrationCache[key] = updated
        calibrationPreferences.edit().putFloat(key, updated.toFloat()).apply()
        Log.e(
            TAG,
            "User calibration updated: camera=$cameraId reference=$referenceEv100 " +
                "measured=$measuredEv100 correction=$updated",
        )
        return updated
    }

    @Synchronized
    fun resetUserCalibration() {
        val cameraId = cameraInfo.cameraId.ifBlank { "0" }
        val key = userCalibrationKey(cameraId)
        userCalibrationCache.remove(key)
        calibrationPreferences.edit().remove(key).apply()
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
        val samples = mutableListOf<FrameStat>()
        Log.e(
            TAG,
            "ISP preview metering started: frames=$FALLBACK_FRAME_COUNT mode=$meteringMode",
        )
        callback.onMeteringStarted(MeteringSource.ISP_PREVIEW)
        captureProcessedPreviewSample(id, samples, meteringMode)
    }

    private fun captureProcessedPreviewSample(
        id: Int,
        samples: MutableList<FrameStat>,
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
                analyzeProcessedPreview(bitmap, result, meteringMode)
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

    private fun analyzeProcessedPreview(
        bitmap: Bitmap,
        result: TotalCaptureResult,
        meteringMode: MeteringMode,
    ): FrameStat? {
        val chars = characteristics ?: return null
        val spot = analyzePreviewRegion(bitmap, SPOT_ROI_FRACTION) ?: return null
        val region = if (meteringMode == MeteringMode.CENTER_WEIGHTED) {
            val wide = analyzePreviewRegion(bitmap, CENTER_WEIGHTED_ROI_FRACTION) ?: return null
            PreviewRegionStat(
                luma = spot.luma * CENTER_SPOT_WEIGHT + wide.luma * CENTER_WIDE_WEIGHT,
                clipped = spot.clipped * CENTER_SPOT_WEIGHT +
                    wide.clipped * CENTER_WIDE_WEIGHT,
            )
        } else {
            spot
        }
        val luma = region.luma
        if (!luma.isFinite() || luma <= 0.00001) return null

        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val aperture = result.get(CaptureResult.LENS_APERTURE)
            ?: chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
            ?: return null
        val seconds = exposureTime / 1_000_000_000.0
        val cameraEv = log2(aperture * aperture / seconds * 100.0 / sensitivity)
        val sceneEv = cameraEv + log2(luma / RAW_REFERENCE_LEVEL) +
            userCalibrationEv(cameraInfo.cameraId.ifBlank { "0" })
        return FrameStat(
            ev100 = sceneEv,
            luma = luma,
            clipped = region.clipped,
            captureIso = sensitivity,
            exposureTimeNs = exposureTime,
            aperture = aperture,
        )
    }

    private fun analyzePreviewRegion(bitmap: Bitmap, fraction: Float): PreviewRegionStat? {
        val sampleSize = (min(bitmap.width, bitmap.height) * fraction)
            .roundToInt()
            .coerceAtLeast(8)
            .coerceAtMost(min(bitmap.width, bitmap.height))
        val left = (bitmap.width - sampleSize) / 2
        val top = (bitmap.height - sampleSize) / 2
        val pixels = IntArray(sampleSize * sampleSize)
        bitmap.getPixels(pixels, 0, sampleSize, left, top, sampleSize, sampleSize)
        val luminances = DoubleArray(pixels.size)
        var clipped = 0
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = ((color ushr 16) and 0xff) / 255.0
            val green = ((color ushr 8) and 0xff) / 255.0
            val blue = (color and 0xff) / 255.0
            val linearRed = srgbToLinear(red)
            val linearGreen = srgbToLinear(green)
            val linearBlue = srgbToLinear(blue)
            luminances[index] =
                0.2126 * linearRed + 0.7152 * linearGreen + 0.0722 * linearBlue
            if (red >= 0.98 || green >= 0.98 || blue >= 0.98) clipped++
        }
        luminances.sort()
        val luma = if (luminances.size % 2 == 0) {
            val middle = luminances.size / 2
            (luminances[middle - 1] + luminances[middle]) * 0.5
        } else {
            luminances[luminances.size / 2]
        }
        return PreviewRegionStat(
            luma = luma,
            clipped = clipped.toDouble() / pixels.size,
        )
    }

    private fun finishProcessedPreview(id: Int, samples: List<FrameStat>) {
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

    private fun srgbToLinear(value: Double): Double =
        if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)

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
            val selection = selectMainBackCamera()
            if (selection == null) {
                opening = false
                postInfo(CameraUiInfo(status = "没有可用的后置摄像头"))
                return
            }
            val (cameraId, chars) = selection
            characteristics = chars
            val capabilities =
                chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val rawAvailable =
                capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val manualAvailable =
                capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("相机没有输出配置")
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
                cameraId = cameraId,
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
                status = "正在打开主摄",
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
            cameraManager.openCamera(cameraId, cameraStateCallback, handler)
        } catch (error: Exception) {
            opening = false
            postInfo(cameraInfo.copy(status = "相机打开失败：${error.message ?: "未知错误"}"))
        }
    }

    private fun selectMainBackCamera(): Pair<String, CameraCharacteristics>? {
        val candidates = cameraManager.cameraIdList.mapNotNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                id to chars
            } else {
                null
            }
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { (_, chars) ->
            chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        } ?: candidates.first()
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

    private fun createSession(device: CameraDevice) {
        val preview = previewSurface ?: return
        val outputs = mutableListOf(preview)
        rawReader?.surface?.let(outputs::add)
        try {
            device.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) {
                            session.close()
                            return
                        }
                        captureSession = session
                        startPreview(device, session, preview)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        postInfo(cameraInfo.copy(status = "相机输出组合不受支持"))
                    }
                },
                cameraHandler,
            )
        } catch (error: CameraAccessException) {
            postInfo(cameraInfo.copy(status = "无法建立相机会话：${error.message}"))
        }
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
            latestResult = result
        }
    }

    private val rawCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            latestResult = result
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val active = activeMeasurement ?: return
            active.results[timestamp] = result
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
                    analyzeFrame(active, image, result)?.let(active.stats::add)
                }
            } finally {
                image.close()
            }
        }
        if (active.stats.size >= active.expectedFrames) finishMeasurement(active)
    }

    private fun analyzeFrame(
        active: MeasurementAccumulator,
        image: Image,
        result: TotalCaptureResult,
    ): FrameStat? {
        val chars = characteristics ?: return null
        val plane = image.planes.firstOrNull() ?: return null
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?: fixedBlackLevels(chars)
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: return null
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        val buffer = plane.buffer
        val spotValues = analyzeRawRegion(
            image = image,
            bufferOffset = buffer.position(),
            cfa = cfa,
            black = black,
            white = white,
            frameAspect = active.frameAspect,
            zoom = active.zoom,
            roiFraction = SPOT_ROI_FRACTION,
        )
        val values = if (active.meteringMode == MeteringMode.CENTER_WEIGHTED) {
            val wideValues = analyzeRawRegion(
                image = image,
                bufferOffset = buffer.position(),
                cfa = cfa,
                black = black,
                white = white,
                frameAspect = active.frameAspect,
                zoom = active.zoom,
                roiFraction = CENTER_WEIGHTED_ROI_FRACTION,
            )
            blendRawRegions(spotValues, wideValues)
        } else {
            spotValues
        }
        if (values.size < 6 || values[5] < 16.0) return null
        val rawGreen = (values[1] + values[2]) * 0.5
        val legacyLuma = 0.21 * values[0] + 0.72 * rawGreen + 0.07 * values[3]
        val colorGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val colorTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val luma = if (colorGains != null && colorTransform != null) {
            val sensorRed = values[0] * colorGains.red
            val sensorGreen =
                (values[1] * colorGains.greenEven + values[2] * colorGains.greenOdd) * 0.5
            val sensorBlue = values[3] * colorGains.blue
            val linearRed =
                colorTransform.getElement(0, 0).toDouble() * sensorRed +
                    colorTransform.getElement(1, 0).toDouble() * sensorGreen +
                    colorTransform.getElement(2, 0).toDouble() * sensorBlue
            val linearGreen =
                colorTransform.getElement(0, 1).toDouble() * sensorRed +
                    colorTransform.getElement(1, 1).toDouble() * sensorGreen +
                    colorTransform.getElement(2, 1).toDouble() * sensorBlue
            val linearBlue =
                colorTransform.getElement(0, 2).toDouble() * sensorRed +
                    colorTransform.getElement(1, 2).toDouble() * sensorGreen +
                    colorTransform.getElement(2, 2).toDouble() * sensorBlue
            0.2126 * linearRed + 0.7152 * linearGreen + 0.0722 * linearBlue
        } else {
            legacyLuma
        }
        if (!luma.isFinite() || luma <= 0.00001) return null

        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val aperture = result.get(CaptureResult.LENS_APERTURE)
            ?: chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
            ?: return null
        val seconds = exposureTime / 1_000_000_000.0
        val cameraEv = log2(aperture * aperture / seconds * 100.0 / sensitivity)
        val baselineCalibrationEv = rawCalibrationEv(cameraInfo.cameraId)
        val userCalibrationEv = userCalibrationEv(cameraInfo.cameraId)
        val calibrationEv = baselineCalibrationEv + userCalibrationEv
        val sceneEv = cameraEv + log2(luma / RAW_REFERENCE_LEVEL) + calibrationEv
        val postRawBoost =
            result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
        val neutralPoint = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            ?.joinToString(prefix = "[", postfix = "]") { it.toString() }
            ?: "unavailable"
        Log.e(
            TAG,
            "RAW frame diagnostics: r=${values[0]} g1=${values[1]} g2=${values[2]} " +
                "b=${values[3]} legacyLuma=$legacyLuma correctedLuma=$luma " +
                "gains=${colorGains ?: "unavailable"} transform=${colorTransform ?: "unavailable"} " +
                "black=${black.joinToString()} white=$white " +
                "exposureNs=$exposureTime iso=$sensitivity aperture=$aperture " +
                "postRawBoost=$postRawBoost neutral=$neutralPoint cameraEv=$cameraEv " +
                "baselineCalibrationEv=$baselineCalibrationEv " +
                "userCalibrationEv=$userCalibrationEv calibrationEv=$calibrationEv " +
                "sceneEv=$sceneEv mode=${active.meteringMode}",
        )
        return FrameStat(
            ev100 = sceneEv,
            luma = luma,
            clipped = values[4],
            captureIso = sensitivity,
            exposureTimeNs = exposureTime,
            aperture = aperture,
        )
    }

    private fun analyzeRawRegion(
        image: Image,
        bufferOffset: Int,
        cfa: Int,
        black: FloatArray,
        white: Int,
        frameAspect: Float,
        zoom: Float,
        roiFraction: Float,
    ): DoubleArray {
        val plane = image.planes[0]
        val roi = rawMeterRoi(
            imageWidth = image.width,
            imageHeight = image.height,
            frameAspect = frameAspect,
            zoom = zoom,
            roiFraction = roiFraction,
        )
        return RawMeterBridge.analyzeRaw(
            buffer = plane.buffer,
            bufferOffset = bufferOffset,
            width = image.width,
            height = image.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            cfa = cfa,
            blackLevels = black,
            whiteLevel = white,
            roiLeft = roi.left,
            roiTop = roi.top,
            roiWidth = roi.width(),
            roiHeight = roi.height(),
        )
    }

    private fun blendRawRegions(spot: DoubleArray, wide: DoubleArray): DoubleArray {
        if (spot.size < 6) return wide
        if (wide.size < 6) return spot
        return DoubleArray(6) { index ->
            if (index == 5) {
                spot[index] + wide[index]
            } else {
                spot[index] * CENTER_SPOT_WEIGHT + wide[index] * CENTER_WIDE_WEIGHT
            }
        }
    }

    private fun rawMeterRoi(
        imageWidth: Int,
        imageHeight: Int,
        frameAspect: Float,
        zoom: Float,
        roiFraction: Float,
    ): Rect {
        val reported = cameraInfo.activeArray
        val active = if (reported != null &&
            reported.left >= 0 && reported.top >= 0 &&
            reported.right <= imageWidth && reported.bottom <= imageHeight
        ) {
            Rect(reported)
        } else {
            Rect(0, 0, imageWidth, imageHeight)
        }
        var cropWidth = active.width().toFloat()
        var cropHeight = active.height().toFloat()
        val activeAspect = cropWidth / cropHeight
        if (activeAspect > frameAspect) {
            cropWidth = cropHeight * frameAspect
        } else {
            cropHeight = cropWidth / frameAspect
        }
        cropWidth /= zoom
        cropHeight /= zoom
        val shortSide = min(cropWidth, cropHeight)
        val roiSize = (shortSide * roiFraction).roundToInt()
            .coerceIn(32, min(imageWidth, imageHeight))
        var left = active.centerX() - roiSize / 2
        var top = active.centerY() - roiSize / 2
        left = left.coerceIn(0, imageWidth - roiSize)
        top = top.coerceIn(0, imageHeight - roiSize)
        left = left and -2
        top = top and -2
        return Rect(left, top, left + roiSize, top + roiSize)
    }

    private fun screenAspectInSensorCoordinates(screenAspect: Float): Float {
        val sensorOrientation =
            characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: cameraInfo.sensorOrientationDegrees
        val displayDegrees = when (lastDisplayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val relativeRotation = (sensorOrientation - displayDegrees + 360) % 360
        return if (relativeRotation == 90 || relativeRotation == 270) {
            1f / screenAspect
        } else {
            screenAspect
        }
    }

    private fun fixedBlackLevels(chars: CameraCharacteristics): FloatArray {
        val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?: return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(
            pattern.getOffsetForIndex(0, 0).toFloat(),
            pattern.getOffsetForIndex(1, 0).toFloat(),
            pattern.getOffsetForIndex(0, 1).toFloat(),
            pattern.getOffsetForIndex(1, 1).toFloat(),
        )
    }

    private fun rawCalibrationEv(cameraId: String): Double {
        val key = calibrationDeviceKey(cameraId)
        return rawCalibrationCache.getOrPut(key) {
            if (calibrationPreferences.contains(key)) {
                calibrationPreferences.getFloat(key, 0f).toDouble()
            } else {
                val default = knownRawCalibrationEv(cameraId)
                calibrationPreferences.edit().putFloat(key, default.toFloat()).apply()
                default
            }
        }
    }

    @Synchronized
    private fun userCalibrationEv(cameraId: String): Double {
        val key = userCalibrationKey(cameraId)
        return userCalibrationCache.getOrPut(key) {
            calibrationPreferences.getFloat(key, 0f).toDouble()
        }
    }

    private fun userCalibrationKey(cameraId: String): String =
        "user_${calibrationDeviceKey(cameraId)}"

    private fun calibrationDeviceKey(cameraId: String): String = buildString {
            append(Build.MANUFACTURER.lowercase())
            append('_')
            append(Build.MODEL.lowercase())
            append('_')
            append(cameraId)
        }.replace(Regex("[^a-z0-9_.-]"), "_")

    private fun knownRawCalibrationEv(cameraId: String): Double =
        if (Build.MANUFACTURER.equals("vivo", ignoreCase = true) &&
            Build.MODEL.equals("V2405A", ignoreCase = true) &&
            cameraId == "0"
        ) {
            1.074
        } else {
            0.0
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
        previewSurface?.release()
        previewSurface = null
        latestResult = null
        opening = false
    }

    private fun postInfo(info: CameraUiInfo) {
        cameraInfo = info
        Log.e(
            TAG,
            "Camera status: ${info.status}; id=${info.cameraId}; raw=${info.rawAvailable}; " +
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

    private fun log2(value: Double): Double = ln(value) / ln(2.0)

    companion object {
        private const val TAG = "RawLightMeter"
        private const val RAW_REFERENCE_LEVEL = 0.18
        private const val FALLBACK_BITMAP_SIZE = 96
        private const val FALLBACK_FRAME_COUNT = 3
        private const val FALLBACK_SAMPLE_DELAY_MS = 70L
        private const val SPOT_ROI_FRACTION = 0.08f
        private const val CENTER_WEIGHTED_ROI_FRACTION = 0.30f
        private const val CENTER_SPOT_WEIGHT = 0.7
        private const val CENTER_WIDE_WEIGHT = 0.3
    }
}
