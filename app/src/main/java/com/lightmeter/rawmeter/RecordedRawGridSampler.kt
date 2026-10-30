package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.graphics.RectF
import java.nio.ByteOrder

/** Creates a compact RAW-derived luminance map for later point placement without loading DNG. */
internal object RecordedRawGridSampler {
    private const val GRID_WIDTH = 64
    private const val GRID_HEIGHT = 48

    fun sample(
        image: Image,
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        sensorFrameAspect: Float,
        zoom: Float,
        screenToSensorTransform: ScreenToSensorCoordinateTransform,
        sensorCrop: NormalizedSensorViewport? = null,
        rawCorrectionEv: Double? = null,
    ): RecordedRawGrid? {
        val plane = image.planes.firstOrNull() ?: return null
        if (image.width <= 0 || image.height <= 0 || plane.pixelStride < 2) return null
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val blackPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val dynamicBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        if (blackPattern == null && dynamicBlack == null) return null
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toFloat()
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat()
            ?: return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?.takeIf(RawSensorFormatPolicy::isBayerCfa)
            ?: return null
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val coefficients = EvaluativeColorMath.coefficients(gains?.let { doubleArrayOf(it.red.toDouble(), it.greenEven.toDouble(), it.greenOdd.toDouble(), it.blue.toDouble()) },
            transform?.let { matrix -> DoubleArray(9) { matrix.getElement(it / 3, it % 3).toDouble() } })
        // Keep R/G1/G2/B ordering identical to the native RAW meter.
        val channels = arrayOf(intArrayOf(0, 1, 2, 3), intArrayOf(1, 0, 3, 2), intArrayOf(1, 3, 0, 2), intArrayOf(3, 1, 2, 0))[cfa]
        val totals = FloatArray(4)
        val counts = IntArray(4)
        val bufferOffset = plane.buffer.position()
        val values = FloatArray(GRID_WIDTH * GRID_HEIGHT)
        for (row in 0 until GRID_HEIGHT) {
            val sourceY = ((row + 0.5f) / GRID_HEIGHT * image.height).toInt().coerceIn(0, image.height - 1)
            for (column in 0 until GRID_WIDTH) {
                val sourceX = ((column + 0.5f) / GRID_WIDTH * image.width).toInt().coerceIn(0, image.width - 1)
                totals.fill(0f); counts.fill(0)
                for (dy in -2..1) for (dx in -2..1) {
                    val px = (sourceX + dx).coerceIn(0, image.width - 1)
                    val py = (sourceY + dy).coerceIn(0, image.height - 1)
                    val offset = bufferOffset + py * plane.rowStride + px * plane.pixelStride
                    if (offset < 0 || offset + 1 >= buffer.limit()) continue
                    val raw = buffer.getShort(offset).toInt() and 0xffff
                    val black = dynamicBlack?.getOrNull((py and 1) * 2 + (px and 1))
                        ?: blackPattern?.getOffsetForIndex(px and 1, py and 1)?.toFloat()
                        ?: continue
                    val channel = channels[(py and 1) * 2 + (px and 1)]
                    totals[channel] += ((raw - black) / (white - black).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    counts[channel] += 1
                }
                if (counts.all { it > 0 }) values[row * GRID_WIDTH + column] = (0..3)
                    .sumOf { coefficients[it] * totals[it] / counts[it] }.toFloat().coerceAtLeast(0f)
            }
        }
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val bounds = if (active != null && active.left >= 0 && active.top >= 0 &&
            active.right <= image.width && active.bottom <= image.height
        ) RectF(active) else RectF(0f, 0f, image.width.toFloat(), image.height.toFloat())
        val crop = sensorCrop?.let { ViewfinderGeometry.rect(bounds, it) }
            ?: centeredCrop(bounds, sensorFrameAspect, zoom)
        val normalizedCrop = RectF(
            crop.left / image.width,
            crop.top / image.height,
            crop.right / image.width,
            crop.bottom / image.height,
        )
        val center = values.filterIndexed { index, value ->
            if (value <= 0f) return@filterIndexed false
            val x = ((index % GRID_WIDTH) + 0.5f) / GRID_WIDTH
            val y = ((index / GRID_WIDTH) + 0.5f) / GRID_HEIGHT
            x in (normalizedCrop.left + normalizedCrop.width() * 0.375f)..
                (normalizedCrop.left + normalizedCrop.width() * 0.625f) &&
                y in (normalizedCrop.top + normalizedCrop.height() * 0.375f)..
                (normalizedCrop.top + normalizedCrop.height() * 0.625f)
        }
        val reference = center.average().toFloat().takeIf { it > 0f }
            ?: values.filter { it > 0f }.average().toFloat().takeIf { it > 0f }
            ?: return null
        val referenceEv100 = rawCorrectionEv?.let { correction ->
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0 } ?: return null
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 } ?: return null
            val aperture = (result.get(CaptureResult.LENS_APERTURE)
                ?: characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull())?.takeIf { it > 0f } ?: return null
            ExposureMath.log2(aperture.toDouble() * aperture / (exposure / 1e9) * 100.0 / iso) +
                ExposureMath.log2(reference.toDouble() / .18) + correction
        }
        return RecordedRawGrid(
            GRID_WIDTH,
            GRID_HEIGHT,
            values,
            reference,
            screenToSensorRotationDegrees = screenToSensorTransform.rotationDegrees,
            screenToSensorMirrored = screenToSensorTransform.mirrored,
            normalizedCrop.left,
            normalizedCrop.top,
            normalizedCrop.right,
            normalizedCrop.bottom,
            referenceEv100 = referenceEv100,
        )
    }

    private fun centeredCrop(bounds: RectF, aspect: Float, zoom: Float): RectF {
        val safeAspect = aspect.coerceAtLeast(0.01f)
        var cropWidth = bounds.width()
        var cropHeight = bounds.height()
        if (cropWidth / cropHeight > safeAspect) cropWidth = cropHeight * safeAspect
        else cropHeight = cropWidth / safeAspect
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
}
