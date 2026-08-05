package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.util.Log
import java.util.TreeMap
import kotlin.math.ln
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

internal data class MeasurementAccumulator(
    val id: Int,
    val expectedFrames: Int,
    val frameAspect: Float,
    val zoom: Float,
    val meteringMode: MeteringMode,
    val images: MutableMap<Long, Image> = TreeMap(),
    val results: MutableMap<Long, CaptureResult> = TreeMap(),
    val stats: MutableList<MeteringFrameStat> = mutableListOf(),
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
    ): MeteringFrameStat? {
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
            calibrationStore.userCorrection(cameraId.ifBlank { "0" })
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
            roiFraction = SPOT_ROI_FRACTION,
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
            ?: characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull()
            ?: return null
        val seconds = exposureTime / 1_000_000_000.0
        val cameraEv = log2(aperture * aperture / seconds * 100.0 / sensitivity)
        val userCalibrationEv = calibrationStore.userCorrection(cameraInfo.cameraId)
        val calibrationEv = calibrationStore.totalCorrection(cameraInfo.cameraId)
        val baselineCalibrationEv = calibrationEv - userCalibrationEv
        val sceneEv = cameraEv + log2(luma / RAW_REFERENCE_LEVEL) + calibrationEv
        val postRawBoost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
        val neutralPoint = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            ?.joinToString(prefix = "[", postfix = "]") { it.toString() }
            ?: "unavailable"
        Log.e(
            TAG,
            "RAW frame diagnostics: r=${values[0]} g1=${values[1]} g2=${values[2]} " +
                "b=${values[3]} legacyLuma=$legacyLuma correctedLuma=$luma " +
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
    ): DoubleArray {
        val plane = image.planes[0]
        val roi = rawMeterRoi(
            image.width,
            image.height,
            activeArray,
            frameAspect,
            zoom,
            roiFraction,
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

    private fun rawMeterRoi(
        imageWidth: Int,
        imageHeight: Int,
        reportedActiveArray: Rect?,
        frameAspect: Float,
        zoom: Float,
        roiFraction: Float,
    ): Rect {
        val active = if (reportedActiveArray != null &&
            reportedActiveArray.left >= 0 && reportedActiveArray.top >= 0 &&
            reportedActiveArray.right <= imageWidth && reportedActiveArray.bottom <= imageHeight
        ) Rect(reportedActiveArray) else Rect(0, 0, imageWidth, imageHeight)
        var cropWidth = active.width().toFloat()
        var cropHeight = active.height().toFloat()
        if (cropWidth / cropHeight > frameAspect) cropWidth = cropHeight * frameAspect
        else cropHeight = cropWidth / frameAspect
        cropWidth /= zoom
        cropHeight /= zoom
        val roiSize = (min(cropWidth, cropHeight) * roiFraction).roundToInt()
            .coerceIn(32, min(imageWidth, imageHeight))
        var left = (active.centerX() - roiSize / 2).coerceIn(0, imageWidth - roiSize)
        var top = (active.centerY() - roiSize / 2).coerceIn(0, imageHeight - roiSize)
        left = left and -2
        top = top and -2
        return Rect(left, top, left + roiSize, top + roiSize)
    }

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

    private const val TAG = "RawLightMeter"
    private const val RAW_REFERENCE_LEVEL = 0.18
    private const val SPOT_ROI_FRACTION = 0.08f
    private const val CENTER_WEIGHTED_ROI_FRACTION = 0.30f
    private const val CENTER_SPOT_WEIGHT = 0.7
    private const val CENTER_WIDE_WEIGHT = 0.3
}
