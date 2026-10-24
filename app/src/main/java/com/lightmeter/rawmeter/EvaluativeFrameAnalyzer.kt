package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Immutable visible-frame evidence; ISP coordinates already contain the display zoom. */
data class EvaluativeMeteringViewport(
    val sensorFrameAspect: Float,
    val zoom: Float,
    val displayCrop: NormalizedSensorViewport,
    val screenFrameAspect: Float = sensorFrameAspect,
    val displayRotation: Int = 0,
)

/** Synchronous borrowed-frame analysis; Camera2 resource ownership remains with the caller. */
internal object EvaluativeFrameAnalyzer {
    private class PixelSample(var luma: Double = 0.0, var clipped: Boolean = false)
    fun raw(
        image: Image, result: CaptureResult, characteristics: CameraCharacteristics,
        cameraInfo: CameraUiInfo, frameAspect: Float, zoom: Float,
        calibrationStore: CameraCalibrationStore, vignettingStore: VignettingCalibrationStore?,
    ): MeteringFrameStat? {
        val plane = image.planes.firstOrNull() ?: return null
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) ?: characteristics
            .get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { pattern ->
                FloatArray(4) { pattern.getOffsetForIndex(it % 2, it / 2).toFloat() }
            } ?: return null
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?.takeIf(RawSensorFormatPolicy::isBayerCfa) ?: return null
        val crop = MeteringAnalysis.rawMeterCrop(image.width, image.height, cameraInfo.activeArray, frameAspect, zoom)
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val coefficients = if (gains != null && transform != null) {
            val wb = doubleArrayOf(gains.red.toDouble(), gains.greenEven.toDouble(), gains.greenOdd.toDouble(), gains.blue.toDouble())
            val matrix = DoubleArray(9) { transform.getElement(it / 3, it % 3).toDouble() }
            EvaluativeColorMath.coefficients(wb, matrix)
        } else EvaluativeColorMath.coefficients(null, null)
        val shading = vignettingStore?.measurementMap(cameraInfo.calibrationCameraId)
        val values = RawMeterBridge.sampleEvaluativeRaw(
            plane.buffer, plane.buffer.position(), plane.buffer.limit(), image.width, image.height,
            plane.rowStride, plane.pixelStride, cfa, black, white,
            crop.left.roundToInt(), crop.top.roundToInt(), crop.right.roundToInt(), crop.bottom.roundToInt(),
            coefficients, shading?.gridWidth ?: 0, shading?.gridHeight ?: 0,
            shading?.let { floatArrayOf(it.activeLeft, it.activeTop, it.activeRight, it.activeBottom) } ?: floatArrayOf(),
            shading?.gains ?: floatArrayOf(),
        )
        if (values.size < 19 || values[2].toInt() != values.size - 3) return null
        val correction = calibrationStore.totalCorrection(cameraInfo.calibrationCameraId, MeteringSource.RAW)
        return frame(values.copyOfRange(3, values.size), values[0], values[1], result, characteristics) { correction }
    }

    fun preview(
        bitmap: Bitmap, result: CaptureResult, characteristics: CameraCharacteristics,
        cameraId: String, calibrationStore: CameraCalibrationStore, viewport: EvaluativeMeteringViewport,
    ): MeteringFrameStat? {
        val crop = viewport.displayCrop
        val bounds = Rect(
            (crop.left * bitmap.width).roundToInt(), (crop.top * bitmap.height).roundToInt(),
            (crop.right * bitmap.width).roundToInt(), (crop.bottom * bitmap.height).roundToInt(),
        )
        val decoder = ProcessedLumaMetadata.decoder(result)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return processed(bounds, bitmap.width, bitmap.height, result, characteristics,
            calibrationStore.responseCorrectionSnapshot(cameraId, MeteringSource.ISP_PREVIEW)) { x, y, sample ->
            val color = pixels[y * bitmap.width + x]
            val r = ((color ushr 16) and 255) / 255.0
            val g = ((color ushr 8) and 255) / 255.0
            val b = (color and 255) / 255.0
            sample.luma = decoder.linearLuma(r, g, b)
            sample.clipped = r >= 0.98 || g >= 0.98 || b >= 0.98
            true
        }
    }

    fun yuv(
        image: Image, result: CaptureResult, characteristics: CameraCharacteristics,
        cameraId: String, calibrationStore: CameraCalibrationStore, viewport: EvaluativeMeteringViewport,
        previewGeometry: PreviewStreamGeometry? = null,
    ): MeteringFrameStat? {
        if (image.width < 2 || image.height < 2 || image.planes.size < 3) return null
        val planes = image.planes.map { YuvPlaneReader(it.buffer, it.rowStride, it.pixelStride) }
        val decoder = ProcessedLumaMetadata.decoder(result)
        val encoding = ProcessedLumaMetadata.yuvEncoding(image)
        val crop = EvaluativeMeteringGeometry.yuvCrop(image.width, image.height, viewport, previewGeometry)
            ?: return null
        val bounds = Rect(crop.left.roundToInt(), crop.top.roundToInt(), crop.right.roundToInt(), crop.bottom.roundToInt())
        return processed(bounds, image.width, image.height, result, characteristics,
            calibrationStore.responseCorrectionSnapshot(cameraId, MeteringSource.YUV_PREVIEW)) { x, y, sample ->
            val yc = planes[0].sample(x, y)
            val uc = planes[1].sample(x / 2, y / 2)
            val vc = planes[2].sample(x / 2, y / 2)
            if (yc < 0 || uc < 0 || vc < 0) false else {
                ProcessedLumaMath.withEncodedRgb(yc, uc, vc, encoding) { r, g, b ->
                    sample.luma = decoder.linearLuma(r, g, b)
                    sample.clipped = r >= 0.98 || g >= 0.98 || b >= 0.98
                }
                true
            }
        }
    }

    private inline fun processed(
        bounds: Rect, width: Int, height: Int, result: CaptureResult,
        characteristics: CameraCharacteristics, noinline correction: (Double) -> Double,
        sample: (Int, Int, PixelSample) -> Boolean,
    ): MeteringFrameStat? {
        if (bounds.left < 0 || bounds.top < 0 || bounds.right > width || bounds.bottom > height || bounds.isEmpty) return null
        val nx = minOf(bounds.width(), maxOf(1, sqrt(MAX_SAMPLES.toDouble() * bounds.width() / bounds.height()).toInt()))
        val ny = minOf(bounds.height(), maxOf(1, MAX_SAMPLES / nx))
        val lumas = DoubleArray(nx * ny)
        var clipped = 0
        var dark = 0
        val value = PixelSample()
        for (sy in 0 until ny) for (sx in 0 until nx) {
            val x = bounds.left + ((sx + 0.5) * bounds.width() / nx).toInt()
            val y = bounds.top + ((sy + 0.5) * bounds.height() / ny).toInt()
            // A missing plane byte invalidates the frame, not just that area's representation.
            if (!sample(x, y, value) || !value.luma.isFinite()) return null
            if (value.clipped) clipped++
            if (value.luma <= EvaluativeMeteringAlgorithm.SIGNAL_FLOOR) dark++
            lumas[sy * nx + sx] = value.luma.coerceAtLeast(EvaluativeMeteringAlgorithm.SIGNAL_FLOOR)
        }
        return frame(lumas, clipped.toDouble() / lumas.size, dark.toDouble() / lumas.size,
            result, characteristics, correction)
    }

    private fun frame(
        lumas: DoubleArray, clipped: Double, dark: Double,
        result: CaptureResult, characteristics: CameraCharacteristics, correction: (Double) -> Double,
    ): MeteringFrameStat? {
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0 } ?: return null
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 } ?: return null
        val aperture = (result.get(CaptureResult.LENS_APERTURE) ?: characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull())
            ?.takeIf { it.isFinite() && it > 0 } ?: return null
        val cameraEv = log2(aperture * aperture / (exposure / 1e9) * 100.0 / iso)
        val samples = DoubleArray(lumas.size) { i -> cameraEv + log2(lumas[i] / 0.18) + correction(lumas[i]) }
        val stats = EvaluativeMeteringAlgorithm.summarize(samples, clipped, dark) ?: return null
        return MeteringFrameStat(
            ev100 = stats.baseEv100,
            luma = 0.18 * 2.0.pow(stats.baseEv100 - cameraEv),
            clipped = clipped, captureIso = iso, exposureTimeNs = exposure, aperture = aperture,
            sampleCount = lumas.size, evaluative = stats,
        )
    }

    private fun log2(value: Double) = ln(value) / ln(2.0)
    private const val MAX_SAMPLES = 32768
}

/** Matrix entries use the same Camera2 column/row ordering as the legacy RAW analyzer. */
internal object EvaluativeColorMath {
    fun coefficients(gains: DoubleArray?, matrix: DoubleArray?): DoubleArray {
        if (gains == null || matrix == null) return doubleArrayOf(0.21, 0.36, 0.36, 0.07)
        require(gains.size == 4 && matrix.size == 9)
        val y = doubleArrayOf(0.2126, 0.7152, 0.0722)
        fun channel(column: Int) = (0..2).sumOf { row -> y[row] * matrix[column * 3 + row] }
        return doubleArrayOf(channel(0) * gains[0], channel(1) * gains[1] / 2,
            channel(1) * gains[2] / 2, channel(2) * gains[3])
    }
}

internal object EvaluativeMeteringGeometry {
    /** Map the displayed stream's visible crop to YUV, retaining the preview geometry domain. */
    fun yuvCrop(width: Int, height: Int, viewport: EvaluativeMeteringViewport,
                geometry: PreviewStreamGeometry?): RectF? {
        if (width < 2 || height < 2 || !viewport.zoom.isFinite() || viewport.zoom < 1f ||
            !viewport.sensorFrameAspect.isFinite() || viewport.sensorFrameAspect <= 0f) return null
        val active = geometry?.activeArray?.takeIf { it.width() > 0 && it.height() > 0 }
            ?: return MeteringAnalysis.rawMeterCrop(width, height, null, viewport.sensorFrameAspect, viewport.zoom)
        val aspect = CameraPreviewTransform.screenAspectInSensorCoordinates(viewport.screenFrameAspect,
            geometry.sensorOrientationDegrees, viewport.displayRotation, geometry.lensFacing)
        val preview = geometry.sensorViewport
        val previewSensorRect = RectF(active.left + preview.left * active.width(),
            active.top + preview.top * active.height(), active.left + preview.right * active.width(),
            active.top + preview.bottom * active.height())
        val visible = MeteringAnalysis.centeredCrop(previewSensorRect, aspect, viewport.zoom)
        val yuv = MeteringAnalysis.centeredCrop(RectF(active), width.toFloat() / height, 1f)
        if (visible.left < yuv.left - 1f || visible.top < yuv.top - 1f ||
            visible.right > yuv.right + 1f || visible.bottom > yuv.bottom + 1f) return null
        return RectF(((visible.left - yuv.left) / yuv.width() * width).coerceIn(0f, width.toFloat()),
            ((visible.top - yuv.top) / yuv.height() * height).coerceIn(0f, height.toFloat()),
            ((visible.right - yuv.left) / yuv.width() * width).coerceIn(0f, width.toFloat()),
            ((visible.bottom - yuv.top) / yuv.height() * height).coerceIn(0f, height.toFloat()))
    }
}
