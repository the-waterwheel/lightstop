package com.lightmeter.rawmeter

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.media.Image
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Matches a displayed preview marker to the corresponding RAW sensor coordinate. */
internal object RawPreviewRegistration {
    fun createReference(
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
        val halfSpan = referenceHalfSpan(target.frameX, target.frameY)
        val samples = FloatArray(GRID_SIZE * GRID_SIZE)
        for (row in 0 until GRID_SIZE) {
            val offsetY = normalizedGridOffset(row, GRID_SIZE) * halfSpan
            for (column in 0 until GRID_SIZE) {
                val offsetX = normalizedGridOffset(column, GRID_SIZE) * halfSpan
                val screenX = (target.frameX + offsetX).coerceIn(0f, 1f)
                val screenY = (target.frameY + offsetY).coerceIn(0f, 1f)
                val orientedX = crop.left + screenX * crop.width()
                val orientedY = crop.top + screenY * crop.height()
                samples[row * GRID_SIZE + column] = sampleRotatedLuma(
                    frame,
                    orientedX,
                    orientedY,
                    orientedWidth,
                    orientedHeight,
                    rotation,
                )
            }
        }
        return if (standardDeviation(samples) >= MIN_REFERENCE_STD_DEV) {
            PreviewLumaReference(samples, GRID_SIZE, halfSpan)
        } else {
            null
        }
    }

    fun createReference(
        bitmap: Bitmap,
        target: ZoneMeteringTarget,
    ): PreviewLumaReference? {
        if (bitmap.width <= 1 || bitmap.height <= 1) return null
        val halfSpan = referenceHalfSpan(target.frameX, target.frameY)
        val samples = FloatArray(GRID_SIZE * GRID_SIZE)
        for (row in 0 until GRID_SIZE) {
            val frameOffsetY = normalizedGridOffset(row, GRID_SIZE) * halfSpan
            for (column in 0 until GRID_SIZE) {
                val frameOffsetX = normalizedGridOffset(column, GRID_SIZE) * halfSpan
                val previewX = (target.previewX +
                    frameOffsetX * target.previewFrameWidthFraction).coerceIn(0f, 1f)
                val previewY = (target.previewY +
                    frameOffsetY * target.previewFrameHeightFraction).coerceIn(0f, 1f)
                val pixel = bitmap.getPixel(
                    (previewX * (bitmap.width - 1)).roundToInt(),
                    (previewY * (bitmap.height - 1)).roundToInt(),
                )
                samples[row * GRID_SIZE + column] =
                    Color.red(pixel) * 0.2126f +
                    Color.green(pixel) * 0.7152f +
                    Color.blue(pixel) * 0.0722f
            }
        }
        return if (standardDeviation(samples) >= MIN_REFERENCE_STD_DEV) {
            PreviewLumaReference(samples, GRID_SIZE, halfSpan)
        } else {
            null
        }
    }

    fun resolve(
        image: Image,
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        cameraInfo: CameraUiInfo,
        zoom: Float,
        target: ZoneMeteringTarget,
        reference: PreviewLumaReference?,
        screenToSensorTransform: ScreenToSensorCoordinateTransform,
    ): RawMeterPoint {
        val plane = image.planes.firstOrNull()
        val active = RectF(validatedActiveRect(image.width, image.height, cameraInfo.activeArray))
        val visibleCrop = rawVisiblePreviewCrop(
            active = active,
            target = target,
            zoom = zoom,
            transform = screenToSensorTransform,
        )
        val approximate = rawPointForFrameCoordinate(
            target.frameX,
            target.frameY,
            active,
            visibleCrop,
            target,
            zoom,
            screenToSensorTransform,
        )
        if (plane == null || reference == null ||
            reference.gridSize <= 1 ||
            reference.samples.size != reference.gridSize * reference.gridSize
        ) {
            return approximate
        }
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?: fixedBlackLevels(characteristics)
            ?: return approximate
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: return approximate
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?.takeIf(RawSensorFormatPolicy::isBayerCfa)
            ?: return approximate
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
                active,
                candidateX,
                candidateY,
                reference,
                target,
                zoom,
                screenToSensorTransform,
            ) ?: return
            val correlation = normalizedCorrelation(reference.samples, rawSamples)
            if (!correlation.isFinite()) return
            val distanceX = candidateX - target.frameX
            val distanceY = candidateY - target.frameY
            val normalizedDistanceSquared =
                (distanceX * distanceX + distanceY * distanceY) /
                    (SEARCH_RADIUS * SEARCH_RADIUS)
            val adjusted = correlation - DISTANCE_PENALTY * normalizedDistanceSquared
            if (adjusted > bestAdjusted) {
                bestAdjusted = adjusted
                bestCorrelation = correlation
                bestX = candidateX
                bestY = candidateY
            }
        }

        for (row in -COARSE_STEPS..COARSE_STEPS) {
            for (column in -COARSE_STEPS..COARSE_STEPS) {
                consider(
                    target.frameX + column * COARSE_STEP,
                    target.frameY + row * COARSE_STEP,
                )
            }
        }
        val coarseX = bestX
        val coarseY = bestY
        for (row in -FINE_STEPS..FINE_STEPS) {
            for (column in -FINE_STEPS..FINE_STEPS) {
                consider(
                    coarseX + column * FINE_STEP,
                    coarseY + row * FINE_STEP,
                )
            }
        }
        if (bestCorrelation < MIN_CORRELATION) return approximate
        return rawPointForFrameCoordinate(
            bestX,
            bestY,
            active,
            visibleCrop,
            target,
            zoom,
            screenToSensorTransform,
        ).copy(matchScore = bestCorrelation)
    }

    private fun rawPointForFrameCoordinate(
        frameX: Float,
        frameY: Float,
        active: RectF,
        visibleCrop: RectF,
        target: ZoneMeteringTarget,
        zoom: Float,
        transform: ScreenToSensorCoordinateTransform,
    ): RawMeterPoint {
        val (previewX, previewY) = frameToPreviewCoordinate(frameX, frameY, target, zoom)
        val (sensorX, sensorY) = transform.map(previewX, previewY)
        return RawMeterPoint(
            sensorX = active.left + sensorX * active.width(),
            sensorY = active.top + sensorY * active.height(),
            matchScore = Double.NaN,
            visibleCropLeft = visibleCrop.left,
            visibleCropTop = visibleCrop.top,
            visibleCropRight = visibleCrop.right,
            visibleCropBottom = visibleCrop.bottom,
        )
    }

    private fun rawVisiblePreviewCrop(
        active: RectF,
        target: ZoneMeteringTarget,
        zoom: Float,
        transform: ScreenToSensorCoordinateTransform,
    ): RectF {
        val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (x, y) ->
            val (previewX, previewY) = frameToPreviewCoordinate(x, y, target, zoom)
            transform.map(previewX, previewY)
        }
        return RectF(
            active.left + corners.minOf { it.first } * active.width(),
            active.top + corners.minOf { it.second } * active.height(),
            active.left + corners.maxOf { it.first } * active.width(),
            active.top + corners.maxOf { it.second } * active.height(),
        )
    }

    private fun frameToPreviewCoordinate(
        frameX: Float,
        frameY: Float,
        target: ZoneMeteringTarget,
        zoom: Float,
    ): Pair<Float, Float> {
        val geometricX = target.previewX +
            (frameX - target.frameX) * target.previewFrameWidthFraction
        val geometricY = target.previewY +
            (frameY - target.frameY) * target.previewFrameHeightFraction
        val safeZoom = zoom.coerceAtLeast(1f)
        return (0.5f + (geometricX - 0.5f) / safeZoom).coerceIn(0f, 1f) to
            (0.5f + (geometricY - 0.5f) / safeZoom).coerceIn(0f, 1f)
    }

    private fun sampleRawFeature(
        sampler: RawGreenSampler,
        active: RectF,
        centerX: Float,
        centerY: Float,
        reference: PreviewLumaReference,
        target: ZoneMeteringTarget,
        zoom: Float,
        transform: ScreenToSensorCoordinateTransform,
    ): FloatArray? {
        val samples = FloatArray(reference.samples.size)
        for (row in 0 until reference.gridSize) {
            val offsetY = normalizedGridOffset(row, reference.gridSize) * reference.halfSpan
            for (column in 0 until reference.gridSize) {
                val offsetX = normalizedGridOffset(column, reference.gridSize) * reference.halfSpan
                val point = rawPointForFrameCoordinate(
                    (centerX + offsetX).coerceIn(0f, 1f),
                    (centerY + offsetY).coerceIn(0f, 1f),
                    active,
                    active,
                    target,
                    zoom,
                    transform,
                )
                samples[row * reference.gridSize + column] =
                    sampler.sample(point.sensorX, point.sensorY) ?: return null
            }
        }
        return samples
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
        val bottomValue = value(left, bottom) * (1f - fractionX) + value(right, bottom) * fractionX
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

    private fun normalizedGridOffset(index: Int, size: Int): Float =
        index.toFloat() / (size - 1).coerceAtLeast(1) * 2f - 1f

    private fun referenceHalfSpan(x: Float, y: Float): Float {
        val edgeDistance = min(min(x, 1f - x), min(y, 1f - y)).coerceAtLeast(0f)
        return min(MAX_PATCH_HALF_SPAN, max(MIN_PATCH_HALF_SPAN, edgeDistance * 0.8f))
    }

    private fun normalizedRotation(rotationDegrees: Int): Int =
        ((rotationDegrees % 360) + 360) % 360

    private const val GRID_SIZE = 11
    private const val MAX_PATCH_HALF_SPAN = 0.026f
    private const val MIN_PATCH_HALF_SPAN = 0.006f
    private const val MIN_REFERENCE_STD_DEV = 2.5
    private const val SEARCH_RADIUS = 0.085f
    private const val COARSE_STEPS = 6
    private const val COARSE_STEP = 0.014f
    private const val FINE_STEPS = 3
    private const val FINE_STEP = 0.003f
    private const val DISTANCE_PENALTY = 0.06
    private const val MIN_CORRELATION = 0.12
}

/** Lightweight Bayer-green sampler shared by registration and vignetting calibration. */
internal class RawGreenSampler(
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

    private fun rawChannel(cfa: Int, position: Int): Int = when (cfa) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> position
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> when (position) {
            0 -> 1
            1 -> 0
            2 -> 3
            else -> 2
        }
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> when (position) {
            0 -> 1
            1 -> 3
            2 -> 0
            else -> 2
        }
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> when (position) {
            0 -> 3
            1 -> 1
            2 -> 2
            else -> 0
        }
        else -> -1
    }
}
