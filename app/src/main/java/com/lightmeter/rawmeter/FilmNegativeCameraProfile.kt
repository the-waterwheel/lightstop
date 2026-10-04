package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.TonemapCurve

/** Explicit sRGB output keeps the inverse transfer used by the density model consistent. */
internal object FilmNegativeCameraProfile {
    /** Compare actual encoded output values, not a vendor curve's exact float hash. */
    fun signature(curve: TonemapCurve?): List<Float> {
        if (curve == null) return emptyList()
        val result = ArrayList<Float>(96)
        for (channel in 0..2) {
            val count = curve.getPointCount(channel)
            val points = FloatArray(count * 2)
            curve.copyColorCurve(channel, points, 0)
            var point = 0
            for (index in 0..31) {
                val t = index / 31f
                val x = t * t * t
                while (point + 1 < count && points[(point + 1) * 2] < x) point++
                val next = (point + 1).coerceAtMost(count - 1)
                val span = points[next * 2] - points[point * 2]
                val fraction = if (span > 0f) ((x - points[point * 2]) / span).coerceIn(0f, 1f) else 0f
                result.add(points[point * 2 + 1] + fraction * (points[next * 2 + 1] - points[point * 2 + 1]))
            }
        }
        return result
    }
    private val points = FloatArray(64 * 2) { index ->
        val t = (index / 2) / 63.0
        val x = t * t * t
        if (index % 2 == 0) x.toFloat() else FilmNegativeMath.encode(x)
    }
    val curve: TonemapCurve by lazy { TonemapCurve(points, points, points) }

    fun supported(chars: CameraCharacteristics?): Boolean =
        chars?.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
            ?.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) == true &&
            (chars.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0) >= 64

    fun controlled(result: CaptureResult): Boolean =
        result.get(CaptureResult.TONEMAP_MODE) == CaptureResult.TONEMAP_MODE_CONTRAST_CURVE &&
            result.get(CaptureResult.TONEMAP_CURVE) == curve
}
