package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.util.Log
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class MeteringFrameStat(
    val ev100: Double,
    val luma: Double,
    val clipped: Double,
    val captureIso: Int,
    val exposureTimeNs: Long,
    val aperture: Float,
)

internal data class RawColorSample(
    val red: Double,
    val green: Double,
    val blue: Double,
    val clippedFraction: Double,
    val sampleCount: Int,
)

/** CPU-only metering math kept outside CameraController's session lifecycle. */
internal object MeteringAnalysis {
    private data class PreviewRegionStat(val luma: Double, val clipped: Double)

    fun analyzePreview(
        bitmap: Bitmap,
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        cameraId: String,
        meteringMode: MeteringMode,
        calibrationStore: CameraCalibrationStore,
        target: ZoneMeteringTarget? = null,
        meteringRoiFraction: Float? = null,
    ): MeteringFrameStat? {
        val centerX = target?.previewX ?: 0.5f
        val centerY = target?.previewY ?: 0.5f
        val lumaDecoder = ProcessedLumaMetadata.decoder(result)
        val spot = analyzePreviewRegion(
            bitmap,
            spotRoiFraction(meteringMode, meteringRoiFraction),
            centerX,
            centerY,
            lumaDecoder,
        ) ?: return null
        val region = if (meteringMode == MeteringMode.CENTER_WEIGHTED) {
            val wide = analyzePreviewRegion(
                bitmap,
                CENTER_WEIGHTED_ROI_FRACTION,
                centerX,
                centerY,
                lumaDecoder,
            ) ?: return null
            PreviewRegionStat(
                luma = spot.luma * CENTER_SPOT_WEIGHT + wide.luma * CENTER_WIDE_WEIGHT,
                clipped = spot.clipped * CENTER_SPOT_WEIGHT +
                    wide.clipped * CENTER_WIDE_WEIGHT,
            )
        } else {
            spot
        }
        if (!region.luma.isFinite() || region.luma <= 0.00001) return null
        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val aperture = result.get(CaptureResult.LENS_APERTURE)
            ?: characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull()
            ?: return null
        val seconds = exposureTime / 1_000_000_000.0
        val cameraEv = log2(aperture * aperture / seconds * 100.0 / sensitivity)
        val sceneEv = cameraEv + log2(region.luma / RAW_REFERENCE_LEVEL) +
            calibrationStore.userCorrection(
                cameraId.ifBlank { "0" },
                MeteringSource.ISP_PREVIEW,
            )
        return MeteringFrameStat(
            ev100 = sceneEv,
            luma = region.luma,
            clipped = region.clipped,
            captureIso = sensitivity,
            exposureTimeNs = exposureTime,
            aperture = aperture,
        )
    }

    fun analyzeYuvPreview(
        image: Image,
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        cameraId: String,
        meteringMode: MeteringMode,
        calibrationStore: CameraCalibrationStore,
        meteringRoiFraction: Float? = null,
    ): MeteringFrameStat? {
        if (image.format != android.graphics.ImageFormat.YUV_420_888) return null
        if (image.planes.size < 3) return null
        val lumaDecoder = ProcessedLumaMetadata.decoder(result)
        val yuvEncoding = ProcessedLumaMetadata.yuvEncoding(image)
        val spot = analyzeYuvRegion(
            image,
            spotRoiFraction(meteringMode, meteringRoiFraction),
            lumaDecoder,
            yuvEncoding,
        ) ?: return null
        val region = if (meteringMode == MeteringMode.CENTER_WEIGHTED) {
            val wide = analyzeYuvRegion(
                image,
                CENTER_WEIGHTED_ROI_FRACTION,
                lumaDecoder,
                yuvEncoding,
            ) ?: return null
            PreviewRegionStat(
                luma = spot.luma * CENTER_SPOT_WEIGHT + wide.luma * CENTER_WIDE_WEIGHT,
                clipped = spot.clipped * CENTER_SPOT_WEIGHT +
                    wide.clipped * CENTER_WIDE_WEIGHT,
            )
        } else {
            spot
        }
        if (!region.luma.isFinite() || region.luma <= 0.00001) return null
        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val aperture = result.get(CaptureResult.LENS_APERTURE)
            ?: characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull()
            ?: return null
        val seconds = exposureTime / 1_000_000_000.0
        val cameraEv = log2(aperture * aperture / seconds * 100.0 / sensitivity)
        val sceneEv = cameraEv + log2(region.luma / RAW_REFERENCE_LEVEL) +
            calibrationStore.userCorrection(cameraId.ifBlank { "0" }, MeteringSource.YUV_PREVIEW)
        return MeteringFrameStat(
            ev100 = sceneEv,
            luma = region.luma,
            clipped = region.clipped,
            captureIso = sensitivity,
            exposureTimeNs = exposureTime,
            aperture = aperture,
        )
    }

    fun analyzeRaw(
        image: Image,
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        cameraInfo: CameraUiInfo,
        frameAspect: Float,
        zoom: Float,
        meteringMode: MeteringMode,
        calibrationStore: CameraCalibrationStore,
        rawMeterPoint: RawMeterPoint? = null,
        vignettingStore: VignettingCalibrationStore? = null,
        applyVignettingCalibration: Boolean = false,
        meteringRoiFraction: Float? = null,
    ): MeteringFrameStat? {
        val plane = image.planes.firstOrNull() ?: return null
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?: fixedBlackLevels(characteristics)
            ?: return null
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?.takeIf(RawSensorFormatPolicy::isBayerCfa)
            ?: return null
        val bufferOffset = plane.buffer.position()
        val spotValues = analyzeRawRegion(
            image = image,
            bufferOffset = bufferOffset,
            cfa = cfa,
            black = black,
            white = white,
            activeArray = cameraInfo.activeArray,
            frameAspect = frameAspect,
            zoom = zoom,
            roiFraction = spotRoiFraction(meteringMode, meteringRoiFraction),
            rawMeterPoint = rawMeterPoint,
            preservePhysicalAngle = meteringMode == MeteringMode.ANGLE,
        )
        val values = if (meteringMode == MeteringMode.CENTER_WEIGHTED) {
            blendRawRegions(
                spotValues,
                analyzeRawRegion(
                    image = image,
                    bufferOffset = bufferOffset,
                    cfa = cfa,
                    black = black,
                    white = white,
                    activeArray = cameraInfo.activeArray,
                    frameAspect = frameAspect,
                    zoom = zoom,
                    roiFraction = CENTER_WEIGHTED_ROI_FRACTION,
                    rawMeterPoint = rawMeterPoint,
                    preservePhysicalAngle = false,
                ),
            )
        } else {
            spotValues
        }
        if (values.size < 6 || values[5] < 16.0) return null

        val rawGreen = (values[1] + values[2]) * 0.5
        val legacyLuma = 0.21 * values[0] + 0.72 * rawGreen + 0.07 * values[3]
        val colorGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val colorTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val baseLuma = if (colorGains != null && colorTransform != null) {
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
        val vignettingGain = if (applyVignettingCalibration && rawMeterPoint != null) {
            vignettingStore?.gainAt(
                cameraId = cameraInfo.calibrationCameraId,
                sensorX = rawMeterPoint.sensorX,
                sensorY = rawMeterPoint.sensorY,
                imageWidth = image.width,
                imageHeight = image.height,
            ) ?: 1.0
        } else {
            1.0
        }
        val luma = baseLuma * vignettingGain
        if (!luma.isFinite() || luma <= 0.00001) return null

        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val aperture = result.get(CaptureResult.LENS_APERTURE)
            ?: characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull()
            ?: return null
        val seconds = exposureTime / 1_000_000_000.0
        val cameraEv = log2(aperture * aperture / seconds * 100.0 / sensitivity)
        val userCalibrationEv = calibrationStore.userCorrection(
            cameraInfo.calibrationCameraId,
            MeteringSource.RAW,
        )
        val calibrationEv = calibrationStore.totalCorrection(
            cameraInfo.calibrationCameraId,
            MeteringSource.RAW,
        )
        val baselineCalibrationEv = calibrationEv - userCalibrationEv
        val sceneEv = cameraEv + log2(luma / RAW_REFERENCE_LEVEL) + calibrationEv
        val postRawBoost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
        val neutralPoint = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            ?.joinToString(prefix = "[", postfix = "]") { it.toString() }
            ?: "unavailable"
        Log.e(
            TAG,
            "RAW frame diagnostics: r=${values[0]} g1=${values[1]} g2=${values[2]} " +
                "b=${values[3]} legacyLuma=$legacyLuma colorLuma=$baseLuma " +
                "vignettingGain=$vignettingGain correctedLuma=$luma " +
                "gains=${colorGains ?: "unavailable"} " +
                "transform=${colorTransform ?: "unavailable"} " +
                "black=${black.joinToString()} white=$white " +
                "exposureNs=$exposureTime iso=$sensitivity aperture=$aperture " +
                "postRawBoost=$postRawBoost neutral=$neutralPoint cameraEv=$cameraEv " +
                "baselineCalibrationEv=$baselineCalibrationEv " +
                "userCalibrationEv=$userCalibrationEv calibrationEv=$calibrationEv " +
                "sceneEv=$sceneEv mode=$meteringMode",
        )
        return MeteringFrameStat(
            ev100 = sceneEv,
            luma = luma,
            clipped = values[4],
            captureIso = sensitivity,
            exposureTimeNs = exposureTime,
            aperture = aperture,
        )
    }

    /** Reads a centered, black-level-corrected RAW patch without applying AWB gains. */
    fun analyzeRawColorSample(
        image: Image,
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        activeArray: Rect?,
        frameAspect: Float,
        zoom: Float,
        roiFraction: Float = 0.24f,
    ): RawColorSample? {
        val plane = image.planes.firstOrNull() ?: return null
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?: fixedBlackLevels(characteristics)
            ?: return null
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?.takeIf(RawSensorFormatPolicy::isBayerCfa)
            ?: return null
        val values = analyzeRawRegion(
            image = image,
            bufferOffset = plane.buffer.position(),
            cfa = cfa,
            black = black,
            white = white,
            activeArray = activeArray,
            frameAspect = frameAspect,
            zoom = zoom,
            roiFraction = roiFraction.coerceIn(0.08f, 0.5f),
            rawMeterPoint = null,
        )
        if (values.size < 6 || values[5] < 64.0) return null
        val green = (values[1] + values[2]) * 0.5
        if (!values[0].isFinite() || !green.isFinite() || !values[3].isFinite()) return null
        return RawColorSample(
            red = values[0],
            green = green,
            blue = values[3],
            clippedFraction = values[4].coerceIn(0.0, 1.0),
            sampleCount = values[5].roundToInt(),
        )
    }

    fun createPreviewReference(
        frame: ZoneTrackingFrame,
        frameAspect: Float,
        zoom: Float,
        target: ZoneMeteringTarget,
    ): PreviewLumaReference? = RawPreviewRegistration.createReference(
        frame = frame,
        frameAspect = frameAspect,
        zoom = zoom,
        target = target,
    )

    fun createPreviewReference(
        bitmap: Bitmap,
        target: ZoneMeteringTarget,
    ): PreviewLumaReference? = RawPreviewRegistration.createReference(bitmap, target)

    fun resolveRawMeteringPoint(
        image: Image,
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        cameraInfo: CameraUiInfo,
        frameAspect: Float,
        zoom: Float,
        target: ZoneMeteringTarget,
        reference: PreviewLumaReference?,
        screenToSensorTransform: ScreenToSensorCoordinateTransform,
    ): RawMeterPoint = RawPreviewRegistration.resolve(
        image = image,
        result = result,
        characteristics = characteristics,
        cameraInfo = cameraInfo,
        zoom = zoom,
        target = target,
        reference = reference,
        screenToSensorTransform = screenToSensorTransform,
    )

    fun createVignettingCalibrationMap(
        image: Image,
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        activeArray: Rect?,
    ): VignettingCalibrationMap? {
        val plane = image.planes.firstOrNull() ?: return null
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?: fixedBlackLevels(characteristics)
            ?: return null
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?.takeIf(RawSensorFormatPolicy::isBayerCfa)
            ?: return null
        val active = validatedActiveRect(image.width, image.height, activeArray)
        val (gridWidth, gridHeight) = if (active.width() >= active.height()) {
            VIGNETTING_GRID_LONG_EDGE to
                (VIGNETTING_GRID_LONG_EDGE * active.height().toFloat() / active.width())
                    .roundToInt()
                    .coerceAtLeast(VIGNETTING_GRID_MIN_SHORT_EDGE)
        } else {
            (VIGNETTING_GRID_LONG_EDGE * active.width().toFloat() / active.height())
                .roundToInt()
                .coerceAtLeast(VIGNETTING_GRID_MIN_SHORT_EDGE) to
                VIGNETTING_GRID_LONG_EDGE
        }
        val sampler = RawGreenSampler(
            image = image,
            bufferOffset = plane.buffer.position(),
            cfa = cfa,
            black = black,
            white = white,
        )
        val grid = FloatArray(gridWidth * gridHeight)
        val cellWidth = active.width().toFloat() / gridWidth
        val cellHeight = active.height().toFloat() / gridHeight
        for (row in 0 until gridHeight) {
            for (column in 0 until gridWidth) {
                val samples = FloatArray(VIGNETTING_CELL_SAMPLES * VIGNETTING_CELL_SAMPLES)
                var sampleCount = 0
                for (sampleY in 0 until VIGNETTING_CELL_SAMPLES) {
                    val fractionY = (sampleY + 0.5f) / VIGNETTING_CELL_SAMPLES
                    for (sampleX in 0 until VIGNETTING_CELL_SAMPLES) {
                        val fractionX = (sampleX + 0.5f) / VIGNETTING_CELL_SAMPLES
                        val sensorX = active.left +
                            (column + 0.12f + fractionX * 0.76f) * cellWidth
                        val sensorY = active.top +
                            (row + 0.12f + fractionY * 0.76f) * cellHeight
                        sampler.sample(sensorX, sensorY)?.let { value ->
                            samples[sampleCount++] = value
                        }
                    }
                }
                if (sampleCount < VIGNETTING_MIN_VALID_CELL_SAMPLES) return null
                grid[row * gridWidth + column] = medianFloat(samples, sampleCount)
            }
        }
        val blurred = gaussianBlurGrid(
            grid,
            gridWidth,
            gridHeight,
            VIGNETTING_GAUSSIAN_RADIUS,
            VIGNETTING_GAUSSIAN_SIGMA,
        )
        val centerValues = ArrayList<Float>()
        val centerLeft = (gridWidth * (0.5f - VIGNETTING_CENTER_FRACTION / 2f)).toInt()
        val centerRight = (gridWidth * (0.5f + VIGNETTING_CENTER_FRACTION / 2f)).toInt()
        val centerTop = (gridHeight * (0.5f - VIGNETTING_CENTER_FRACTION / 2f)).toInt()
        val centerBottom = (gridHeight * (0.5f + VIGNETTING_CENTER_FRACTION / 2f)).toInt()
        for (row in centerTop..centerBottom) {
            for (column in centerLeft..centerRight) {
                centerValues += blurred[
                    row.coerceIn(0, gridHeight - 1) * gridWidth +
                        column.coerceIn(0, gridWidth - 1)
                ]
            }
        }
        val reference = centerValues.map(Float::toDouble).sorted().let { values ->
            if (values.isEmpty()) Double.NaN else values[values.size / 2]
        }
        if (!reference.isFinite() || reference !in VIGNETTING_MIN_REFERENCE..VIGNETTING_MAX_REFERENCE) {
            return null
        }
        val gains = FloatArray(blurred.size) { index ->
            (reference / blurred[index].toDouble().coerceAtLeast(VIGNETTING_MIN_REFERENCE))
                .toFloat()
                .coerceIn(VIGNETTING_MIN_GAIN, VIGNETTING_MAX_GAIN)
        }
        return VignettingCalibrationMap(
            gridWidth = gridWidth,
            gridHeight = gridHeight,
            activeLeft = active.left.toFloat() / image.width,
            activeTop = active.top.toFloat() / image.height,
            activeRight = active.right.toFloat() / image.width,
            activeBottom = active.bottom.toFloat() / image.height,
            gains = gains,
        )
    }

    private fun analyzePreviewRegion(
        bitmap: Bitmap,
        fraction: Float,
        centerX: Float = 0.5f,
        centerY: Float = 0.5f,
        lumaDecoder: ProcessedLumaDecoder,
    ): PreviewRegionStat? {
        val sampleSize = (min(bitmap.width, bitmap.height) * fraction)
            .roundToInt()
            .coerceAtLeast(8)
            .coerceAtMost(min(bitmap.width, bitmap.height))
        val left = (centerX.coerceIn(0f, 1f) * bitmap.width - sampleSize / 2f)
            .roundToInt()
            .coerceIn(0, bitmap.width - sampleSize)
        val top = (centerY.coerceIn(0f, 1f) * bitmap.height - sampleSize / 2f)
            .roundToInt()
            .coerceIn(0, bitmap.height - sampleSize)
        val pixels = IntArray(sampleSize * sampleSize)
        bitmap.getPixels(pixels, 0, sampleSize, left, top, sampleSize, sampleSize)
        val luminances = DoubleArray(pixels.size)
        var clipped = 0
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = ((color ushr 16) and 0xff) / 255.0
            val green = ((color ushr 8) and 0xff) / 255.0
            val blue = (color and 0xff) / 255.0
            luminances[index] = lumaDecoder.linearLuma(red, green, blue)
            if (red >= 0.98 || green >= 0.98 || blue >= 0.98) clipped++
        }
        luminances.sort()
        val luma = if (luminances.size % 2 == 0) {
            val middle = luminances.size / 2
            (luminances[middle - 1] + luminances[middle]) * 0.5
        } else {
            luminances[luminances.size / 2]
        }
        return PreviewRegionStat(luma, clipped.toDouble() / pixels.size)
    }

    private fun analyzeYuvRegion(
        image: Image,
        roiFraction: Float,
        lumaDecoder: ProcessedLumaDecoder,
        yuvEncoding: YuvColorEncoding,
    ): PreviewRegionStat? {
        val width = image.width
        val height = image.height
        if (width <= 1 || height <= 1 || image.planes.size < 3) return null
        val yPlane = YuvPlaneReader(image.planes[0])
        val uPlane = YuvPlaneReader(image.planes[1])
        val vPlane = YuvPlaneReader(image.planes[2])
        val roiWidth = (width * roiFraction).roundToInt().coerceIn(2, width)
        val roiHeight = (height * roiFraction).roundToInt().coerceIn(2, height)
        val left = (width - roiWidth) / 2
        val top = (height - roiHeight) / 2
        val luminances = DoubleArray(roiWidth * roiHeight)
        var clipped = 0
        var count = 0
        for (row in top until top + roiHeight) {
            for (column in left until left + roiWidth) {
                val yCode = yPlane.sample(column, row) ?: continue
                val uCode = uPlane.sample(column / 2, row / 2) ?: continue
                val vCode = vPlane.sample(column / 2, row / 2) ?: continue
                val rgb = ProcessedLumaMath.yuvToEncodedRgb(yCode, uCode, vCode, yuvEncoding)
                luminances[count] = lumaDecoder.linearLuma(rgb.red, rgb.green, rgb.blue)
                if (rgb.clipped) clipped += 1
                count += 1
            }
        }
        val luma = ProcessedLumaMath.median(luminances, count) ?: return null
        return PreviewRegionStat(luma, clipped.toDouble() / count)
    }

    /** Absolute reads preserve each vendor plane's initial buffer offset and row/pixel padding. */
    private class YuvPlaneReader(plane: Image.Plane) {
        private val buffer = plane.buffer.duplicate()
        private val start = buffer.position()
        private val rowStride = plane.rowStride
        private val pixelStride = plane.pixelStride

        fun sample(column: Int, row: Int): Int? {
            if (column < 0 || row < 0 || rowStride <= 0 || pixelStride <= 0) return null
            val offset = start.toLong() + row.toLong() * rowStride + column.toLong() * pixelStride
            if (offset < start || offset >= buffer.limit()) return null
            return buffer.get(offset.toInt()).toInt() and 0xff
        }
    }

    private fun analyzeRawRegion(
        image: Image,
        bufferOffset: Int,
        cfa: Int,
        black: FloatArray,
        white: Int,
        activeArray: Rect?,
        frameAspect: Float,
        zoom: Float,
        roiFraction: Float,
        rawMeterPoint: RawMeterPoint?,
        preservePhysicalAngle: Boolean = false,
    ): DoubleArray {
        val plane = image.planes[0]
        val roi = rawMeterRoi(
            image.width,
            image.height,
            activeArray,
            frameAspect,
            if (preservePhysicalAngle) 1f else zoom,
            roiFraction,
            rawMeterPoint,
            preservePhysicalAngle,
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
            if (index == 5) spot[index] + wide[index]
            else spot[index] * CENTER_SPOT_WEIGHT + wide[index] * CENTER_WIDE_WEIGHT
        }
    }

    private fun spotRoiFraction(
        meteringMode: MeteringMode,
        meteringRoiFraction: Float?,
    ): Float = if (meteringMode == MeteringMode.ANGLE) {
        meteringRoiFraction?.coerceIn(MIN_ROI_FRACTION, 1f) ?: SPOT_ROI_FRACTION
    } else {
        SPOT_ROI_FRACTION
    }

    private fun rawMeterRoi(
        imageWidth: Int,
        imageHeight: Int,
        reportedActiveArray: Rect?,
        frameAspect: Float,
        zoom: Float,
        roiFraction: Float,
        rawMeterPoint: RawMeterPoint?,
        preservePhysicalAngle: Boolean,
    ): Rect {
        val pointCrop = rawMeterPoint?.let {
            RectF(it.visibleCropLeft, it.visibleCropTop, it.visibleCropRight, it.visibleCropBottom)
        }?.takeIf {
            it.left.isFinite() && it.top.isFinite() && it.right.isFinite() &&
                it.bottom.isFinite() && it.width() > 1f && it.height() > 1f
        }
        val crop = if (preservePhysicalAngle) {
            rawMeterCrop(
                imageWidth,
                imageHeight,
                reportedActiveArray,
                frameAspect,
                zoom = 1f,
            )
        } else pointCrop ?: rawMeterCrop(
            imageWidth,
            imageHeight,
            reportedActiveArray,
            frameAspect,
            zoom,
        )
        val maximumRoiSize = min(
            min(imageWidth, imageHeight),
            min(crop.width(), crop.height()).roundToInt(),
        ).coerceAtLeast(2)
        val minimumRoiSize = min(32, maximumRoiSize)
        val roiSize = (min(crop.width(), crop.height()) * roiFraction).roundToInt()
            .coerceIn(minimumRoiSize, maximumRoiSize)
        val centerX = rawMeterPoint?.sensorX ?: crop.centerX()
        val centerY = rawMeterPoint?.sensorY ?: crop.centerY()
        val minLeft = crop.left.roundToInt().coerceIn(0, imageWidth - roiSize)
        val minTop = crop.top.roundToInt().coerceIn(0, imageHeight - roiSize)
        val maxLeft = (crop.right - roiSize).roundToInt().coerceAtLeast(minLeft)
        val maxTop = (crop.bottom - roiSize).roundToInt().coerceAtLeast(minTop)
        var left = (centerX - roiSize / 2f).roundToInt().coerceIn(minLeft, maxLeft)
        var top = (centerY - roiSize / 2f).roundToInt().coerceIn(minTop, maxTop)
        left = left and -2
        top = top and -2
        return Rect(left, top, left + roiSize, top + roiSize)
    }

    private fun rawMeterCrop(
        imageWidth: Int,
        imageHeight: Int,
        reportedActiveArray: Rect?,
        frameAspect: Float,
        zoom: Float,
    ): RectF {
        val active = RectF(validatedActiveRect(imageWidth, imageHeight, reportedActiveArray))
        return centeredCrop(active, frameAspect, zoom)
    }

    private fun validatedActiveRect(
        imageWidth: Int,
        imageHeight: Int,
        reportedActiveArray: Rect?,
    ): Rect = if (reportedActiveArray != null &&
        reportedActiveArray.left >= 0 && reportedActiveArray.top >= 0 &&
        reportedActiveArray.right <= imageWidth && reportedActiveArray.bottom <= imageHeight &&
        reportedActiveArray.width() > 0 && reportedActiveArray.height() > 0
    ) {
        Rect(reportedActiveArray)
    } else {
        Rect(0, 0, imageWidth, imageHeight)
    }

    private fun gaussianBlurGrid(
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        sigma: Double,
    ): FloatArray {
        val kernel = DoubleArray(radius * 2 + 1) { index ->
            val distance = index - radius
            kotlin.math.exp(-(distance * distance) / (2.0 * sigma * sigma))
        }
        val kernelTotal = kernel.sum().coerceAtLeast(1e-9)
        kernel.indices.forEach { kernel[it] /= kernelTotal }
        val horizontal = FloatArray(source.size)
        val output = FloatArray(source.size)
        for (row in 0 until height) {
            for (column in 0 until width) {
                var value = 0.0
                for (offset in -radius..radius) {
                    val sourceColumn = (column + offset).coerceIn(0, width - 1)
                    value += source[row * width + sourceColumn] * kernel[offset + radius]
                }
                horizontal[row * width + column] = value.toFloat()
            }
        }
        for (row in 0 until height) {
            for (column in 0 until width) {
                var value = 0.0
                for (offset in -radius..radius) {
                    val sourceRow = (row + offset).coerceIn(0, height - 1)
                    value += horizontal[sourceRow * width + column] * kernel[offset + radius]
                }
                output[row * width + column] = value.toFloat()
            }
        }
        return output
    }

    private fun medianFloat(values: FloatArray, count: Int): Float {
        val valid = values.copyOf(count)
        valid.sort()
        val middle = valid.size / 2
        return if (valid.size % 2 == 0) {
            (valid[middle - 1] + valid[middle]) * 0.5f
        } else {
            valid[middle]
        }
    }

    private fun centeredCrop(bounds: RectF, aspect: Float, zoom: Float): RectF {
        val safeAspect = aspect.coerceAtLeast(0.01f)
        var cropWidth = bounds.width()
        var cropHeight = bounds.height()
        if (cropWidth / cropHeight > safeAspect) {
            cropWidth = cropHeight * safeAspect
        } else {
            cropHeight = cropWidth / safeAspect
        }
        val safeZoom = zoom.coerceAtLeast(1f)
        cropWidth /= safeZoom
        cropHeight /= safeZoom
        return RectF(
            bounds.centerX() - cropWidth / 2f,
            bounds.centerY() - cropHeight / 2f,
            bounds.centerX() + cropWidth / 2f,
            bounds.centerY() + cropHeight / 2f,
        )
    }

    private fun fixedBlackLevels(characteristics: CameraCharacteristics): FloatArray? {
        val pattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?: return null
        return floatArrayOf(
            pattern.getOffsetForIndex(0, 0).toFloat(),
            pattern.getOffsetForIndex(1, 0).toFloat(),
            pattern.getOffsetForIndex(0, 1).toFloat(),
            pattern.getOffsetForIndex(1, 1).toFloat(),
        )
    }

    private fun log2(value: Double): Double = ln(value) / ln(2.0)

    private const val TAG = "lightstop"
    private const val RAW_REFERENCE_LEVEL = 0.18
    private const val SPOT_ROI_FRACTION = 0.08f
    private const val MIN_ROI_FRACTION = 0.001f
    private const val CENTER_WEIGHTED_ROI_FRACTION = 0.30f
    private const val CENTER_SPOT_WEIGHT = 0.7
    private const val CENTER_WIDE_WEIGHT = 0.3
    private const val VIGNETTING_GRID_LONG_EDGE = 64
    private const val VIGNETTING_GRID_MIN_SHORT_EDGE = 32
    private const val VIGNETTING_CELL_SAMPLES = 7
    private const val VIGNETTING_MIN_VALID_CELL_SAMPLES = 24
    private const val VIGNETTING_GAUSSIAN_RADIUS = 7
    private const val VIGNETTING_GAUSSIAN_SIGMA = 3.6
    private const val VIGNETTING_CENTER_FRACTION = 0.14f
    private const val VIGNETTING_MIN_REFERENCE = 0.0015
    private const val VIGNETTING_MAX_REFERENCE = 0.96
    private const val VIGNETTING_MIN_GAIN = 0.5f
    private const val VIGNETTING_MAX_GAIN = 4f
}
