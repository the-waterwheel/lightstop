package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Advances only with live preview frames; a RAW pause cannot consume the fade offscreen. */
internal class ExposurePreviewRamp {
    var value = 0.0
        private set
    var running = false
        private set
    private var from = 0.0
    private var target = 0.0
    private var elapsedNs = 0L
    private var durationNs = 0L
    private var previousFrameNs = 0L

    fun start(from: Double, target: Double, frameTimestampNs: Long) {
        this.from = from
        this.target = target
        value = from
        elapsedNs = 0L
        previousFrameNs = frameTimestampNs
        durationNs = ((300.0 + abs(target - from) * 80.0).coerceIn(300.0, 800.0) * 1e6).toLong()
        running = abs(target - from) > .0001
        if (!running) value = target
    }

    fun advance(frameTimestampNs: Long): Double {
        if (!running || frameTimestampNs <= previousFrameNs) return value
        val deltaNs = if (previousFrameNs <= 0L) 0L else (frameTimestampNs - previousFrameNs).coerceAtMost(50_000_000L)
        previousFrameNs = frameTimestampNs
        elapsedNs += deltaNs
        val progress = (elapsedNs.toDouble() / durationNs).coerceIn(0.0, 1.0)
        val eased = progress * progress * (3.0 - 2.0 * progress)
        val desired = from + (target - from) * eased
        val maximumChange = 6.0 * deltaNs / 1e9
        value = desired.coerceIn(value - maximumChange, value + maximumChange)
        if (progress >= 1.0 && abs(value - target) < .0001) {
            value = target
            running = false
        }
        return value
    }

    fun cancel() { running = false }

    companion object {
        fun legalSeed(source: ExposurePreviewManualExposure, aperture: Double, minimumTimeNs: Long,
            maximumTimeNs: Long, minimumIso: Int, maximumIso: Int): ExposurePreviewManualExposure {
            val product = source.exposureTimeNs.toDouble() * source.sensitivity
            var iso = source.sensitivity.coerceIn(minimumIso, maximumIso)
            var time = (product / iso).coerceIn(minimumTimeNs.toDouble(), maximumTimeNs.toDouble()).roundToLong()
            iso = (product / time).roundToInt().coerceIn(minimumIso, maximumIso)
            time = (product / iso).coerceIn(minimumTimeNs.toDouble(), maximumTimeNs.toDouble()).roundToLong()
            return ExposurePreviewManualExposure(time, iso,
                checkNotNull(ExposurePreviewMath.cameraEv100(aperture, time, iso)), source.clamped)
        }

        /** Log interpolation preserves brightness while redistributing shutter/ISO smoothly. */
        fun manualAt(from: ExposurePreviewManualExposure, target: ExposurePreviewManualExposure,
            cameraEv100: Double, aperture: Double): ExposurePreviewManualExposure {
            val distance = target.appliedCameraEv100 - from.appliedCameraEv100
            val fraction = if (abs(distance) < .0001) 1.0 else
                ((cameraEv100 - from.appliedCameraEv100) / distance).coerceIn(0.0, 1.0)
            if (fraction >= 1.0) return target
            if (fraction <= 0.0) return from
            val time = exp(ln(from.exposureTimeNs.toDouble()) * (1 - fraction) + ln(target.exposureTimeNs.toDouble()) * fraction).roundToLong().coerceAtLeast(1L)
            val iso = exp(ln(from.sensitivity.toDouble()) * (1 - fraction) + ln(target.sensitivity.toDouble()) * fraction).roundToInt().coerceAtLeast(1)
            return ExposurePreviewManualExposure(time, iso,
                checkNotNull(ExposurePreviewMath.cameraEv100(aperture, time, iso)), target.clamped)
        }
    }
}
