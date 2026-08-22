package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.util.Log
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
        val spot = analyzePreviewRegion(
            bitmap,
            spotRoiFraction(meteringMode, meteringRoiFraction),
            centerX,
            centerY,
        ) ?: return null
        val region = if (meteringMode == MeteringMode.CENTER_WEIGHTED) {
            val wide = analyzePreviewRegion(
                bitmap,
                CENTER_WEIGHTED_ROI_FRACTION,
                centerX,
                centerY,
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
        luma: ByteArray,
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        cameraId: String,
        meteringMode: MeteringMode,
        calibrationStore: CameraCalibrationStore,
        meteringRoiFraction: Float? = null,
    ): MeteringFrameStat? {
        if (image.format != android.graphics.ImageFormat.YUV_420_888) return null
        if (luma.size < image.width * image.height) return null
        if (!ZoneYuvLumaCopier.copy(image, luma)) return null
        val spot = analyzeYuvRegion(
            luma,
            image.width,
            image.height,
            spotRoiFraction(meteringMode, meteringRoiFraction),
        ) ?: return null
        val region = if (meteringMode == MeteringMode.CENTER_WEIGHTED) {
            val wide = analyzeYuvRegion(
                luma,
                image.width,
                image.height,
                CENTER_WEIGHTED_ROI_FRACTION,
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
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
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
                cameraId = cameraInfo.cameraId,
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
            cameraInfo.cameraId,
            MeteringSource.RAW,
        )
        val calibrationEv = calibrationStore.totalCorrection(
            cameraInfo.cameraId,
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
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        if (cfa == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO ||
            cfa == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR
        ) return null
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
    ): PreviewLumaReference? {
        if (frame.width <= 1 || frame.height <= 1 ||
            frame.luma.size < frame.width * frame.height
        ) return null
        val rotation = normalizedRotation(frame.clockwiseRotationDegrees)
        val rotated = rotation == 90 || rotation == 270
        val orientedWidth = if (rotated) frame.height else frame.width
        val orientedHeight = if (rotated) frame.width else frame.height
        val crop = centeredCrop(
            RectF(0f, 0f, orientedWidth.toFloat(), orientedHeight.toFloat()),
            frameAspect,
            zoom,
        )
        val gridSize = RAW_MATCH_GRID_SIZE
        val halfSpan = referenceHalfSpan(target.frameX, target.frameY)
        val samples = FloatArray(gridSize * gridSize)
        for (row in 0 until gridSize) {
            val offsetY = normalizedGridOffset(row, gridSize) * halfSpan
            for (column in 0 until gridSize) {
                val offsetX = normalizedGridOffset(column, gridSize) * halfSpan
                val screenX = (target.frameX + offsetX).coerceIn(0f, 1f)
                val screenY = (target.frameY + offsetY).coerceIn(0f, 1f)
                val orientedX = crop.left + screenX * crop.width()
                val orientedY = crop.top + screenY * crop.height()
                samples[row * gridSize + column] = sampleRotatedLuma(
                    frame,
                    orientedX,
                    orientedY,
                    orientedWidth,
                    orientedHeight,
                    rotation,
                )
            }
        }
        return if (standardDeviation(samples) >= MIN_ISP_REFERENCE_STD_DEV) {
            PreviewLumaReference(samples, gridSize, halfSpan)
        } else {
            null
        }
    }

    fun createPreviewReference(
        bitmap: Bitmap,
        target: ZoneMeteringTarget,
    ): PreviewLumaReference? {
        if (bitmap.width <= 1 || bitmap.height <= 1) return null
        val gridSize = RAW_MATCH_GRID_SIZE
        val halfSpan = referenceHalfSpan(target.frameX, target.frameY)
        val samples = FloatArray(gridSize * gridSize)
        for (row in 0 until gridSize) {
            val frameOffsetY = normalizedGridOffset(row, gridSize) * halfSpan
            for (column in 0 until gridSize) {
                val frameOffsetX = normalizedGridOffset(column, gridSize) * halfSpan
                val previewX = (target.previewX +
                    frameOffsetX * target.previewFrameWidthFraction).coerceIn(0f, 1f)
                val previewY = (target.previewY +
                    frameOffsetY * target.previewFrameHeightFraction).coerceIn(0f, 1f)
                val pixel = bitmap.getPixel(
                    (previewX * (bitmap.width - 1)).roundToInt(),
                    (previewY * (bitmap.height - 1)).roundToInt(),
                )
                samples[row * gridSize + column] =
                    Color.red(pixel) * 0.2126f +
                    Color.green(pixel) * 0.7152f +
                    Color.blue(pixel) * 0.0722f
            }
        }
        return if (standardDeviation(samples) >= MIN_ISP_REFERENCE_STD_DEV) {
            PreviewLumaReference(samples, gridSize, halfSpan)
        } else {
            null
        }
    }

    fun resolveRawMeteringPoint(
        image: Image,
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        cameraInfo: CameraUiInfo,
        frameAspect: Float,
        zoom: Float,
        target: ZoneMeteringTarget,
        reference: PreviewLumaReference?,
        screenToSensorRotationDegrees: Int,
    ): RawMeterPoint {
        val plane = image.planes.firstOrNull()
        val crop = rawMeterCrop(
            image.width,
            image.height,
            cameraInfo.activeArray,
            frameAspect,
            zoom,
        )
        val approximate = rawPointForScreenCoordinate(
            target.frameX,
            target.frameY,
            crop,
            screenToSensorRotationDegrees,
        )
        if (plane == null || reference == null ||
            reference.gridSize <= 1 ||
            reference.samples.size != reference.gridSize * reference.gridSize
        ) {
            return approximate
        }
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?: fixedBlackLevels(characteristics)
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: return approximate
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        val sampler = RawGreenSampler(
            image = image,
            bufferOffset = plane.buffer.position(),
            cfa = cfa,
            black = black,
            white = white,
        )

        var bestX = target.frameX
        var bestY = target.frameY
        var bestCorrelation = Double.NEGATIVE_INFINITY
        var bestAdjusted = Double.NEGATIVE_INFINITY

        fun consider(candidateX: Float, candidateY: Float) {
            if (candidateX !in 0f..1f || candidateY !in 0f..1f) return
            val rawSamples = sampleRawFeature(
                sampler,
                crop,
                candidateX,
                candidateY,
                reference,
                screenToSensorRotationDegrees,
            ) ?: return
            val correlation = normalizedCorrelation(reference.samples, rawSamples)
            if (!correlation.isFinite()) return
            val distanceX = candidateX - target.frameX
            val distanceY = candidateY - target.frameY
            val normalizedDistanceSquared =
                (distanceX * distanceX + distanceY * distanceY) /
                    (RAW_MATCH_SEARCH_RADIUS * RAW_MATCH_SEARCH_RADIUS)
            val adjusted = correlation - RAW_MATCH_DISTANCE_PENALTY * normalizedDistanceSquared
            if (adjusted > bestAdjusted) {
                bestAdjusted = adjusted
                bestCorrelation = correlation
                bestX = candidateX
                bestY = candidateY
            }
        }

        for (row in -RAW_MATCH_COARSE_STEPS..RAW_MATCH_COARSE_STEPS) {
            for (column in -RAW_MATCH_COARSE_STEPS..RAW_MATCH_COARSE_STEPS) {
                consider(
                    target.frameX + column * RAW_MATCH_COARSE_STEP,
                    target.frameY + row * RAW_MATCH_COARSE_STEP,
                )
            }
        }
        val coarseX = bestX
        val coarseY = bestY
        for (row in -RAW_MATCH_FINE_STEPS..RAW_MATCH_FINE_STEPS) {
            for (column in -RAW_MATCH_FINE_STEPS..RAW_MATCH_FINE_STEPS) {
                consider(
                    coarseX + column * RAW_MATCH_FINE_STEP,
                    coarseY + row * RAW_MATCH_FINE_STEP,
                )
            }
        }
        if (bestCorrelation < MIN_RAW_MATCH_CORRELATION) return approximate
        val resolved = rawPointForScreenCoordinate(
            bestX,
            bestY,
            crop,
            screenToSensorRotationDegrees,
        )
        return resolved.copy(matchScore = bestCorrelation)
    }

    fun createVignettingCalibrationMap(
        image: Image,
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        activeArray: Rect?,
    ): VignettingCalibrationMap? {
        val plane = image.planes.firstOrNull() ?: return null
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?: fixedBlackLevels(characteristics)
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
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
            luminances[index] =
                0.2126 * srgbToLinear(red) +
                    0.7152 * srgbToLinear(green) +
                    0.0722 * srgbToLinear(blue)
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
        luma: ByteArray,
        width: Int,
        height: Int,
        roiFraction: Float,
    ): PreviewRegionStat? {
        if (width <= 1 || height <= 1 || luma.size < width * height) return null
        val roiWidth = (width * roiFraction).roundToInt().coerceIn(2, width)
        val roiHeight = (height * roiFraction).roundToInt().coerceIn(2, height)
        val left = (width - roiWidth) / 2
        val top = (height - roiHeight) / 2
        var sum = 0.0
        var clipped = 0
        var count = 0
        for (row in top until top + roiHeight) {
            val rowOffset = row * width
            for (column in left until left + roiWidth) {
                val value = luma[rowOffset + column].toInt() and 0xff
                sum += srgbToLinear(value / 255.0)
                if (value >= YUV_CLIP_LEVEL) clipped += 1
                count += 1
            }
        }
        if (count == 0) return null
        return PreviewRegionStat(sum / count, clipped.toDouble() / count)
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
    ): DoubleArray {
        val plane = image.planes[0]
        val roi = rawMeterRoi(
            image.width,
            image.height,
            activeArray,
            frameAspect,
            zoom,
            roiFraction,
            rawMeterPoint,
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
    ): Rect {
        val crop = rawMeterCrop(
            imageWidth,
            imageHeight,
            reportedActiveArray,
            frameAspect,
            zoom,
        )
        val roiSize = (min(crop.width(), crop.height()) * roiFraction).roundToInt()
            .coerceIn(32, min(imageWidth, imageHeight))
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

    private fun rawPointForScreenCoordinate(
        screenX: Float,
        screenY: Float,
        crop: RectF,
        rotationDegrees: Int,
    ): RawMeterPoint {
        val x = screenX.coerceIn(0f, 1f)
        val y = screenY.coerceIn(0f, 1f)
        val (sensorX, sensorY) = when (normalizedRotation(rotationDegrees)) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }
        return RawMeterPoint(
            sensorX = crop.left + sensorX * crop.width(),
            sensorY = crop.top + sensorY * crop.height(),
            matchScore = Double.NaN,
        )
    }

    private fun sampleRawFeature(
        sampler: RawGreenSampler,
        crop: RectF,
        centerX: Float,
        centerY: Float,
        reference: PreviewLumaReference,
        rotationDegrees: Int,
    ): FloatArray? {
        val samples = FloatArray(reference.samples.size)
        for (row in 0 until reference.gridSize) {
            val offsetY = normalizedGridOffset(row, reference.gridSize) * reference.halfSpan
            for (column in 0 until reference.gridSize) {
                val offsetX = normalizedGridOffset(column, reference.gridSize) * reference.halfSpan
                val screenX = (centerX + offsetX).coerceIn(0f, 1f)
                val screenY = (centerY + offsetY).coerceIn(0f, 1f)
                val point = rawPointForScreenCoordinate(
                    screenX,
                    screenY,
                    crop,
                    rotationDegrees,
                )
                samples[row * reference.gridSize + column] =
                    sampler.sample(point.sensorX, point.sensorY) ?: return null
            }
        }
        return samples
    }

    private class RawGreenSampler(
        image: Image,
        private val bufferOffset: Int,
        private val cfa: Int,
        private val black: FloatArray,
        private val white: Int,
    ) {
        private val plane = image.planes[0]
        private val buffer = plane.buffer
        private val imageWidth = image.width
        private val imageHeight = image.height

        fun sample(sensorX: Float, sensorY: Float): Float? {
            if (plane.pixelStride < 2 || imageWidth < 2 || imageHeight < 2) return null
            val cellX = sensorX.roundToInt().coerceIn(0, imageWidth - 2) and -2
            val cellY = sensorY.roundToInt().coerceIn(0, imageHeight - 2) and -2
            var total = 0f
            var count = 0
            for (dy in 0..1) {
                for (dx in 0..1) {
                    val x = cellX + dx
                    val y = cellY + dy
                    val position = ((y and 1) shl 1) or (x and 1)
                    val channel = rawChannel(cfa, position)
                    if (channel != 1 && channel != 2) continue
                    val offset = bufferOffset + y * plane.rowStride + x * plane.pixelStride
                    if (offset < 0 || offset + 1 >= buffer.limit()) continue
                    val raw = (buffer.get(offset).toInt() and 0xff) or
                        ((buffer.get(offset + 1).toInt() and 0xff) shl 8)
                    val blackLevel = black.getOrElse(position) { 0f }
                    val denominator = max(1f, white - blackLevel)
                    total += ((raw - blackLevel) / denominator).coerceIn(0f, 1f)
                    count += 1
                }
            }
            return if (count > 0) total / count else null
        }
    }

    private fun rawChannel(cfa: Int, position: Int): Int = when (cfa) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB ->
            position
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG ->
            when (position) {
                0 -> 1
                1 -> 0
                2 -> 3
                else -> 2
            }
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG ->
            when (position) {
                0 -> 1
                1 -> 3
                2 -> 0
                else -> 2
            }
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR ->
            when (position) {
                0 -> 3
                1 -> 1
                2 -> 2
                else -> 0
            }
        else -> position
    }

    private fun sampleRotatedLuma(
        frame: ZoneTrackingFrame,
        orientedX: Float,
        orientedY: Float,
        orientedWidth: Int,
        orientedHeight: Int,
        rotationDegrees: Int,
    ): Float {
        val x = orientedX.coerceIn(0f, (orientedWidth - 1).toFloat()) /
            (orientedWidth - 1).coerceAtLeast(1)
        val y = orientedY.coerceIn(0f, (orientedHeight - 1).toFloat()) /
            (orientedHeight - 1).coerceAtLeast(1)
        val (sourceX, sourceY) = when (rotationDegrees) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }
        return bilinearLuma(
            frame,
            sourceX * (frame.width - 1),
            sourceY * (frame.height - 1),
        )
    }

    private fun bilinearLuma(frame: ZoneTrackingFrame, x: Float, y: Float): Float {
        val left = x.toInt().coerceIn(0, frame.width - 1)
        val top = y.toInt().coerceIn(0, frame.height - 1)
        val right = min(left + 1, frame.width - 1)
        val bottom = min(top + 1, frame.height - 1)
        val fractionX = (x - left).coerceIn(0f, 1f)
        val fractionY = (y - top).coerceIn(0f, 1f)
        fun value(column: Int, row: Int): Float =
            (frame.luma[row * frame.width + column].toInt() and 0xff).toFloat()
        val topValue = value(left, top) * (1f - fractionX) + value(right, top) * fractionX
        val bottomValue =
            value(left, bottom) * (1f - fractionX) + value(right, bottom) * fractionX
        return topValue * (1f - fractionY) + bottomValue * fractionY
    }

    private fun normalizedCorrelation(first: FloatArray, second: FloatArray): Double {
        if (first.size != second.size || first.isEmpty()) return Double.NaN
        val firstMean = first.average()
        val secondMean = second.average()
        var numerator = 0.0
        var firstEnergy = 0.0
        var secondEnergy = 0.0
        for (index in first.indices) {
            val firstCentered = first[index] - firstMean
            val secondCentered = second[index] - secondMean
            numerator += firstCentered * secondCentered
            firstEnergy += firstCentered * firstCentered
            secondEnergy += secondCentered * secondCentered
        }
        val denominator = sqrt(firstEnergy * secondEnergy)
        return if (denominator > 1e-9) numerator / denominator else Double.NaN
    }

    private fun standardDeviation(values: FloatArray): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.sumOf { value ->
            val difference = value - mean
            difference * difference
        } / values.size
        return sqrt(variance)
    }

    private fun normalizedGridOffset(index: Int, size: Int): Float =
        index.toFloat() / (size - 1).coerceAtLeast(1) * 2f - 1f

    private fun referenceHalfSpan(x: Float, y: Float): Float {
        val edgeDistance = min(min(x, 1f - x), min(y, 1f - y)).coerceAtLeast(0f)
        return min(RAW_MATCH_PATCH_HALF_SPAN, max(MIN_RAW_MATCH_PATCH_HALF_SPAN, edgeDistance * 0.8f))
    }

    private fun normalizedRotation(rotationDegrees: Int): Int =
        ((rotationDegrees % 360) + 360) % 360

    private fun fixedBlackLevels(characteristics: CameraCharacteristics): FloatArray {
        val pattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?: return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(
            pattern.getOffsetForIndex(0, 0).toFloat(),
            pattern.getOffsetForIndex(1, 0).toFloat(),
            pattern.getOffsetForIndex(0, 1).toFloat(),
            pattern.getOffsetForIndex(1, 1).toFloat(),
        )
    }

    private fun srgbToLinear(value: Double): Double =
        if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)

    private fun log2(value: Double): Double = ln(value) / ln(2.0)

    private const val TAG = "lightstop"
    private const val RAW_REFERENCE_LEVEL = 0.18
    private const val YUV_CLIP_LEVEL = 250
    private const val SPOT_ROI_FRACTION = 0.08f
    private const val MIN_ROI_FRACTION = 0.001f
    private const val CENTER_WEIGHTED_ROI_FRACTION = 0.30f
    private const val CENTER_SPOT_WEIGHT = 0.7
    private const val CENTER_WIDE_WEIGHT = 0.3
    private const val RAW_MATCH_GRID_SIZE = 11
    private const val RAW_MATCH_PATCH_HALF_SPAN = 0.026f
    private const val MIN_RAW_MATCH_PATCH_HALF_SPAN = 0.006f
    private const val MIN_ISP_REFERENCE_STD_DEV = 2.5
    private const val RAW_MATCH_SEARCH_RADIUS = 0.085f
    private const val RAW_MATCH_COARSE_STEPS = 6
    private const val RAW_MATCH_COARSE_STEP = 0.014f
    private const val RAW_MATCH_FINE_STEPS = 3
    private const val RAW_MATCH_FINE_STEP = 0.003f
    private const val RAW_MATCH_DISTANCE_PENALTY = 0.06
    private const val MIN_RAW_MATCH_CORRELATION = 0.12
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
