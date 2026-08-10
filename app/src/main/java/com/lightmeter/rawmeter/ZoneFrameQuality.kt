package com.lightmeter.rawmeter

import kotlin.math.max
import kotlin.math.sqrt

internal data class ZoneFrameQuality(
    val usable: Boolean,
    val range: Int,
    val standardDeviation: Double,
)

/** Fast sampled validation for vendor YUV streams before they enter OpenCV. */
internal object ZoneFrameQualityEvaluator {
    fun evaluate(frame: ZoneTrackingFrame): ZoneFrameQuality {
        val sampleStep = max(1, frame.luma.size / SAMPLE_COUNT)
        var minimum = 255
        var maximum = 0
        var sum = 0.0
        var squaredSum = 0.0
        var count = 0
        var index = 0
        while (index < frame.luma.size) {
            val value = frame.luma[index].toInt() and 0xff
            minimum = kotlin.math.min(minimum, value)
            maximum = max(maximum, value)
            sum += value
            squaredSum += value.toDouble() * value
            count += 1
            index += sampleStep
        }
        if (count == 0) return ZoneFrameQuality(false, 0, 0.0)
        val mean = sum / count
        val variance = (squaredSum / count - mean * mean).coerceAtLeast(0.0)
        val standardDeviation = sqrt(variance)
        val range = maximum - minimum
        return ZoneFrameQuality(
            usable = range >= MIN_LUMA_RANGE && standardDeviation >= MIN_STANDARD_DEVIATION,
            range = range,
            standardDeviation = standardDeviation,
        )
    }

    private const val SAMPLE_COUNT = 4096
    private const val MIN_LUMA_RANGE = 10
    private const val MIN_STANDARD_DEVIATION = 2.5
}
