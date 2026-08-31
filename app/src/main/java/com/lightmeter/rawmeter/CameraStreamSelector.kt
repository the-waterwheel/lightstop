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
 * Frame-rate selection stays within the user's ceiling, the advertised AE ranges, and the
 * configured streams' minimum frame durations. The optional high-rate preference is capped at
 * 60 fps; constrained high-speed sessions are deliberately outside this app's metering pipeline.
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
        val selected = choosePreviewDimensions(
            sizes = bounded.map { it.width to it.height },
            sensorAspect = sensorAspect,
        ) ?: return null
        return bounded.firstOrNull { it.width == selected.first && it.height == selected.second }
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
            requestedCeiling = min(
                MAX_REGULAR_PREVIEW_FPS,
                min(requestedCeiling, streamCeiling),
            ),
        )
    }

    /** Keeps an FPS rejection independent from RAW/YUV/ISP workflow fallback. */
    fun nextFallbackFpsCeiling(currentCeiling: Int?): Int? = when {
        currentCeiling == null -> null
        currentCeiling > LOW_PREVIEW_FPS_CEILING -> LOW_PREVIEW_FPS_CEILING
        currentCeiling > CONSERVATIVE_PREVIEW_FPS_CEILING ->
            CONSERVATIVE_PREVIEW_FPS_CEILING
        else -> null
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

    /**
     * Keeps logical and explicitly routed physical cameras on the same common preview shape.
     *
     * Some multi-camera HALs describe the logical camera with a 16:9 active array while each
     * physical lens exposes a 4:3 array. Selecting only by that metadata makes "Automatic camera"
     * use 1920x1080 and "Main camera" use 1440x1080, so switching between two routes backed by the
     * same lens changes the viewport and can look like a stretched preview. Prefer an advertised
     * 4:3 stream on both routes; fall back to the camera's own sensor aspect when 4:3 is absent.
     */
    fun choosePreviewDimensions(
        sizes: List<Pair<Int, Int>>,
        sensorAspect: Double,
    ): Pair<Int, Int>? {
        if (sizes.isEmpty()) return null
        val fourThirds = 4.0 / 3.0
        val commonShape = sizes.filter { (width, height) ->
            width > 0 && height > 0 &&
                abs(max(width, height).toDouble() / min(width, height) - fourThirds) <=
                ASPECT_TOLERANCE
        }
        val candidates = commonShape.ifEmpty { sizes }
        val normalizedSensorAspect = if (sensorAspect in 0.0..1.0 && sensorAspect > 0.0) {
            1.0 / sensorAspect
        } else {
            sensorAspect
        }
        val targetAspect = if (commonShape.isNotEmpty()) fourThirds else normalizedSensorAspect
        return candidates.minWithOrNull(
            compareBy<Pair<Int, Int>> { (width, height) ->
                if (width > 0 && height > 0) {
                    abs(max(width, height).toDouble() / min(width, height) - targetAspect)
                } else {
                    Double.MAX_VALUE
                }
            }.thenByDescending { (width, height) -> width.toLong() * height.toLong() },
        )
    }

    private const val TRACKING_TARGET_LONG_EDGE = 640
    private const val TRACKING_MAX_LONG_EDGE = 720
    private const val TRACKING_MIN_SHORT_EDGE = 240
    private const val ASPECT_TOLERANCE = 0.03
    private const val MAX_REGULAR_PREVIEW_FPS = 60
    private const val LOW_PREVIEW_FPS_CEILING = 30
    private const val CONSERVATIVE_PREVIEW_FPS_CEILING = 24
}
