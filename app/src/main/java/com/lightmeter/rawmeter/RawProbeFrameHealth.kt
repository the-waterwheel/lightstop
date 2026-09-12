package com.lightmeter.rawmeter

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.util.Size

/** O(1) transport/metadata checks required before a probed RAW stream is considered usable. */
internal data class RawProbeFrameDescriptor(
    val format: Int,
    val width: Int,
    val height: Int,
    val expectedWidth: Int?,
    val expectedHeight: Int?,
    val imageTimestampNs: Long,
    val resultTimestampNs: Long?,
    val planeCount: Int,
    val directBuffer: Boolean,
    val bufferPosition: Int,
    val bufferLimit: Int,
    val bufferCapacity: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val exposureTimeNs: Long?,
    val sensitivityIso: Int?,
    val aperture: Float?,
    val bayerCfa: Boolean,
    val validBlackLevel: Boolean,
    val whiteLevel: Int?,
)

internal data class RawProbeFrameHealth(
    val usable: Boolean,
    val reason: String? = null,
)

/**
 * Deliberately validates only hard RAW usability. It does not scan pixels, judge scene brightness,
 * or reject legitimate dark/highlight scenes, so its cost is far below capturing another frame.
 */
internal object RawProbeFrameHealthPolicy {
    fun evaluate(frame: RawProbeFrameDescriptor): RawProbeFrameHealth {
        fun invalid(reason: String) = RawProbeFrameHealth(false, reason)
        if (frame.format != ImageFormat.RAW_SENSOR) return invalid("Unexpected RAW image format")
        if (frame.width < 2 || frame.height < 2) return invalid("Invalid RAW dimensions")
        if ((frame.expectedWidth != null && frame.width != frame.expectedWidth) ||
            (frame.expectedHeight != null && frame.height != frame.expectedHeight)
        ) {
            return invalid("RAW dimensions differ from the configured output")
        }
        if (frame.imageTimestampNs <= 0L || frame.resultTimestampNs != frame.imageTimestampNs) {
            return invalid("RAW Image and CaptureResult timestamps do not match")
        }
        if (frame.planeCount != 1) return invalid("RAW buffer must expose exactly one plane")
        if (!frame.directBuffer) return invalid("RAW plane is not backed by a direct buffer")
        if (frame.bufferPosition < 0 || frame.bufferLimit <= frame.bufferPosition ||
            frame.bufferCapacity < frame.bufferLimit
        ) {
            return invalid("RAW buffer range is empty")
        }
        if (frame.pixelStride < 2 || frame.rowStride <= 0) {
            return invalid("RAW plane stride is invalid")
        }
        val minimumRowBytes = (frame.width - 1L) * frame.pixelStride + 2L
        if (frame.rowStride < minimumRowBytes) return invalid("RAW row stride is too short")
        val requiredEnd = frame.bufferPosition.toLong() +
            (frame.height - 1L) * frame.rowStride + minimumRowBytes
        if (requiredEnd > frame.bufferLimit.toLong()) {
            return invalid("RAW buffer range is smaller than its declared layout")
        }
        if (frame.exposureTimeNs == null || frame.exposureTimeNs <= 0L) {
            return invalid("RAW exposure metadata is unavailable")
        }
        if (frame.sensitivityIso == null || frame.sensitivityIso <= 0) {
            return invalid("RAW sensitivity metadata is unavailable")
        }
        if (frame.aperture == null || !frame.aperture.isFinite() || frame.aperture <= 0f) {
            return invalid("RAW aperture metadata is unavailable")
        }
        if (!frame.bayerCfa) return invalid("RAW CFA is not a supported Bayer layout")
        if (!frame.validBlackLevel) return invalid("RAW black-level metadata is unavailable")
        if (frame.whiteLevel == null || frame.whiteLevel <= 0) {
            return invalid("RAW white-level metadata is unavailable")
        }
        return RawProbeFrameHealth(true)
    }

    fun evaluate(
        image: Image,
        result: CaptureResult,
        characteristics: CameraCharacteristics,
        expectedSize: Size?,
    ): RawProbeFrameHealth {
        val plane = image.planes.firstOrNull()
        val planeBuffer = plane?.buffer
        val dynamicBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val fixedBlack = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val whiteLevel = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        val validDynamicBlack = dynamicBlack?.size == 4 && dynamicBlack.all {
            it.isFinite() && it >= 0f && (whiteLevel == null || it < whiteLevel)
        }
        val validFixedBlack = fixedBlack != null && listOf(
            fixedBlack.getOffsetForIndex(0, 0),
            fixedBlack.getOffsetForIndex(1, 0),
            fixedBlack.getOffsetForIndex(0, 1),
            fixedBlack.getOffsetForIndex(1, 1),
        ).all { level ->
            level >= 0 && (whiteLevel == null || level < whiteLevel)
        }
        val aperture = result.get(CaptureResult.LENS_APERTURE)
            ?: characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull()
        return evaluate(
            RawProbeFrameDescriptor(
                format = image.format,
                width = image.width,
                height = image.height,
                expectedWidth = expectedSize?.width,
                expectedHeight = expectedSize?.height,
                imageTimestampNs = image.timestamp,
                resultTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP),
                planeCount = image.planes.size,
                directBuffer = planeBuffer?.isDirect == true,
                bufferPosition = planeBuffer?.position() ?: -1,
                bufferLimit = planeBuffer?.limit() ?: 0,
                bufferCapacity = planeBuffer?.capacity() ?: 0,
                rowStride = plane?.rowStride ?: 0,
                pixelStride = plane?.pixelStride ?: 0,
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                aperture = aperture,
                bayerCfa = RawSensorFormatPolicy.isBayerCfa(
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
                ),
                validBlackLevel = validDynamicBlack || validFixedBlack,
                whiteLevel = whiteLevel,
            ),
        )
    }
}
