package com.lightmeter.rawmeter

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Camera2 stream-selection policy.
 *
 * Keeping this outside [CameraController] makes device-capability decisions independently
 * reviewable and prevents the camera lifecycle class from accumulating more sizing policy.
 * The selected sizes and frame-rate preferences intentionally match the original behavior.
 */
internal object CameraStreamSelector {
    fun choosePreviewSize(characteristics: CameraCharacteristics): Size? {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val all = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val bounded = all.filter {
            max(it.width, it.height) <= 1920 && min(it.width, it.height) <= 1080
        }.ifEmpty {
            all.filter { max(it.width, it.height) <= 2560 }.ifEmpty { all }
        }
        if (bounded.isEmpty()) return null

        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorAspect = if (active != null && active.height() > 0) {
            active.width().toDouble() / active.height()
        } else {
            4.0 / 3.0
        }
        val has60Range = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
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
        return fast.ifEmpty { bounded }.minWithOrNull(
            compareBy<Size> {
                abs(it.width.toDouble() / it.height.toDouble() - sensorAspect)
            }.thenByDescending {
                it.width.toLong() * it.height.toLong()
            },
        )
    }

    fun chooseTrackingSize(map: StreamConfigurationMap, preview: Size): Size? {
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

    fun chooseFpsRange(characteristics: CameraCharacteristics, size: Size): Range<Int>? {
        val ranges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList()
            .orEmpty()
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
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

    private const val TRACKING_TARGET_LONG_EDGE = 640
    private const val TRACKING_MAX_LONG_EDGE = 720
    private const val TRACKING_MIN_SHORT_EDGE = 240
}
