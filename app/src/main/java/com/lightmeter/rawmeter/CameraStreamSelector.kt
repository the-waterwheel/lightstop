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
 * Frame-rate selection intentionally stays at or below 30 fps. Some vendor HALs advertise a
 * 60 fps preview range but cannot sustain it once a YUV or RAW output belongs to the session.
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

    fun chooseFpsRange(
        characteristics: CameraCharacteristics,
        previewSize: Size,
        trackingSize: Size?,
        requestedCeiling: Int,
    ): Range<Int>? {
        val ranges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList()
            .orEmpty()
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val durations = buildList {
            map?.getOutputMinFrameDuration(SurfaceTexture::class.java, previewSize)
                ?.takeIf { it > 0L }
                ?.let(::add)
            trackingSize?.let { size ->
                map?.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)
                    ?.takeIf { it > 0L }
                    ?.let(::add)
            }
        }
        val streamCeiling = durations.maxOrNull()?.let { duration ->
            (1_000_000_000L / duration).toInt().coerceAtLeast(1)
        } ?: requestedCeiling
        return selectFpsRange(
            ranges = ranges,
            requestedCeiling = min(30, min(requestedCeiling, streamCeiling)),
        )
    }

    /** Selects an advertised AE range without synthesizing a range the HAL may reject. */
    fun selectFpsRange(
        ranges: List<Range<Int>>,
        requestedCeiling: Int,
    ): Range<Int>? {
        val selected = selectFpsRangeBounds(
            ranges = ranges.map { it.lower to it.upper },
            requestedCeiling = requestedCeiling,
        ) ?: return null
        return ranges.firstOrNull { it.lower == selected.first && it.upper == selected.second }
    }

    /** Pure counterpart used by JVM tests where android.util.Range is only a stub. */
    fun selectFpsRangeBounds(
        ranges: List<Pair<Int, Int>>,
        requestedCeiling: Int,
    ): Pair<Int, Int>? {
        if (ranges.isEmpty()) return null
        val target = requestedCeiling.coerceAtLeast(1)
        return ranges
            .filter { it.first <= target && it.second <= target }
            .maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
    }

    private const val TRACKING_TARGET_LONG_EDGE = 640
    private const val TRACKING_MAX_LONG_EDGE = 720
    private const val TRACKING_MIN_SHORT_EDGE = 240
}
