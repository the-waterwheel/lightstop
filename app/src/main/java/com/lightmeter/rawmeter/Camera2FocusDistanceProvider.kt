package com.lightmeter.rawmeter

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.max

internal data class FocusDistanceCapability(
    val minimumDiopters: Float,
    val calibration: Int?,
    val resultKeyAvailable: Boolean,
    val physicalIdentityKnown: Boolean,
    val targetConfidence: Double = 1.0,
)

/** Capability checks and frame acceptance for metric or explicitly estimated focus distances. */
internal object Camera2FocusDistancePolicy {
    fun supportReason(capability: FocusDistanceCapability): String? = when {
        !capability.targetConfidence.isFinite() -> "Invalid target confidence"
        !capability.minimumDiopters.isFinite() || capability.minimumDiopters <= 0f -> "Fixed-focus lens"
        !capability.resultKeyAvailable -> "CaptureResult has no focus distance"
        else -> null
    }

    fun usesEstimatedScale(capability: FocusDistanceCapability): Boolean =
        !capability.physicalIdentityKnown || capability.calibration !in setOf(
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED,
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE,
        )

    fun acceptsFrame(afState: Int?, lensState: Int?, diopters: Float?, minimumDiopters: Float): Boolean {
        if (afState != CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED &&
            afState != CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED
        ) return false
        if (lensState != null && lensState != CameraMetadata.LENS_STATE_STATIONARY) return false
        if (diopters == null || !diopters.isFinite() || diopters <= MIN_DIOPTERS) return false
        // HALs may round a little beyond their advertised near limit, but not by orders of magnitude.
        return diopters <= max(minimumDiopters * 1.15f, minimumDiopters + 0.25f)
    }

    private const val MIN_DIOPTERS = 0.0001f
}

/**
 * Collects Camera2 focus observations in dioptre space.  It deliberately has no Camera2 session
 * ownership: CameraController forwards results and retains request/session lifecycle ownership.
 */
internal class Camera2FocusDistanceProvider(
    private val ttlNs: Long = DEFAULT_TTL_NS,
    private val clock: () -> Long = System::nanoTime,
) {
    private var context: DistanceContext? = null
    private var capability: FocusDistanceCapability? = null
    private var samples = mutableListOf<FocusSample>()
    private var locked: DistanceEstimate? = null
    private var lastTimestampNs = Long.MIN_VALUE

    fun start(context: DistanceContext, capability: FocusDistanceCapability): DistanceMeasurementState {
        val unsupportedReason = Camera2FocusDistancePolicy.supportReason(capability)
        val reusableEstimate = locked?.takeIf {
            unsupportedReason == null &&
                this.context == context &&
                this.capability == capability &&
                it.isCurrent(clock())
        }
        val previousTimestampNs = lastTimestampNs
        this.context = context
        this.capability = capability
        samples.clear()
        locked = reusableEstimate
        lastTimestampNs = if (reusableEstimate != null) previousTimestampNs else Long.MIN_VALUE
        return when {
            unsupportedReason != null -> DistanceMeasurementState(
                status = DistanceMeasurementStatus.UNSUPPORTED,
                diagnosticReason = unsupportedReason,
            )

            reusableEstimate != null -> DistanceMeasurementState(
                estimate = reusableEstimate,
                status = DistanceMeasurementStatus.AVAILABLE,
            )

            else -> DistanceMeasurementState(status = DistanceMeasurementStatus.WAITING_FOR_FOCUS)
        }
    }

    fun stop(): DistanceMeasurementState {
        context = null
        capability = null
        samples.clear()
        locked = null
        lastTimestampNs = Long.MIN_VALUE
        return DistanceMeasurementState()
    }

    fun invalidate(reason: String): DistanceMeasurementState {
        samples.clear()
        locked = null
        context = null
        capability = null
        return DistanceMeasurementState(status = DistanceMeasurementStatus.STALE, diagnosticReason = reason)
    }

    fun onFrame(
        context: DistanceContext,
        timestampNs: Long,
        afState: Int?,
        lensState: Int?,
        diopters: Float?,
        motionConfidence: Double = 1.0,
    ): DistanceMeasurementState? {
        val expected = this.context ?: return null
        val currentCapability = capability ?: return null
        if (Camera2FocusDistancePolicy.supportReason(currentCapability) != null) return null
        if (expected != context) return invalidate("Camera route or session changed")
        if (timestampNs <= lastTimestampNs) return null
        lastTimestampNs = timestampNs
        val expired = locked?.takeIf { timestampNs - it.timestampNs > ttlNs || !it.isCurrent(clock()) }
        if (expired != null) { locked = null; samples.clear() }
        if (!Camera2FocusDistancePolicy.acceptsFrame(
                afState, lensState, diopters, currentCapability.minimumDiopters,
            )
        ) {
            // A new scan or a moving lens must not share a statistical window with the old lock.
            samples.clear()
            return expired?.let {
                DistanceMeasurementState(it.copy(isFresh = false), DistanceMeasurementStatus.STALE)
            }
        }
        samples += FocusSample(timestampNs, diopters!!,
            (motionConfidence.takeIf { it.isFinite() } ?: 0.1).coerceIn(0.1, 1.0) *
                if (lensState == null) 0.7 else 1.0)
        // Legacy previews may run at only 3–5 fps. Allow five stable samples without demanding
        // a high frame rate, while scans, lens movement and receipt TTL still reset the window.
        val oldestAllowed = timestampNs - SAMPLE_WINDOW_NS
        samples = samples.filter { it.timestampNs >= oldestAllowed }.takeLast(MAX_SAMPLES).toMutableList()
        if (samples.size < MIN_SAMPLES) {
            return DistanceMeasurementState(locked, if (locked != null) DistanceMeasurementStatus.AVAILABLE
                else DistanceMeasurementStatus.SAMPLING)
        }
        val candidate = stableEstimate(expected, currentCapability, samples) ?: return null
        // The business value and its uncertainty always advance together. Display hysteresis
        // must never freeze a distance while renewing its freshness or sample count.
        locked = candidate
        return DistanceMeasurementState(locked, DistanceMeasurementStatus.AVAILABLE)
    }

    private fun stableEstimate(
        context: DistanceContext,
        capability: FocusDistanceCapability,
        samples: List<FocusSample>,
    ): DistanceEstimate? {
        val values = samples.map { it.diopters }.sorted()
        val median = median(values)
        val mad = median(values.map { abs(it - median) }.sorted())
        val estimatedScale = Camera2FocusDistancePolicy.usesEstimatedScale(capability)
        val allowedMad = median * if (estimatedScale) ESTIMATED_MAX_MAD_RATIO else MAX_MAD_RATIO
        if (mad > allowedMad || median <= 0.0) return null
        val meters = 1.0 / median
        if (estimatedScale) {
            // Best effort, like the original implementation. Stable numeric readings do not
            // establish physical units, so do not invent a calibrated uncertainty interval.
            if (meters !in ESTIMATED_MIN_METERS..ESTIMATED_MAX_METERS) return null
            return DistanceEstimate(
                meters = meters, lowerMeters = null, upperMeters = null,
                confidence = (0.30 * (1.0 - mad / allowedMad).coerceIn(0.1, 1.0) *
                    samples.minOf { it.motionConfidence } * capability.targetConfidence.coerceIn(0.0, 1.0))
                    .coerceIn(0.01, 0.30),
                quality = DistanceQuality.LOW, source = DistanceSource.FOCUS_ESTIMATED,
                timestampNs = samples.last().timestampNs, cameraIdentity = context.cameraIdentity,
                target = context.target, sampleCount = samples.size, isFresh = true,
                diagnosticReason = "Uncalibrated focus estimate; physical scale is unverified",
                receivedAtNs = clock(),
            )
        }
        val calibrated = capability.calibration ==
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED
        // Engineering uncertainty envelope, NOT a calibrated statistical confidence interval.
        // An absolute dioptre floor accounts for far-distance quantisation; repetition cannot
        // average away HAL calibration bias. Approximate metadata has a larger systematic floor.
        val sigma = max(mad * 3.0, max(if (calibrated) 0.02 else 0.08,
            median * if (calibrated) 0.06 else 0.20))
        val lower = 1.0 / (median + sigma)
        val upper = if (median > sigma) 1.0 / (median - sigma) else null
        val relativeSpread = mad / median
        val quality = when {
            !calibrated || meters >= 10.0 -> DistanceQuality.LOW
            // Stable focus metadata alone does not establish high absolute accuracy.
            else -> DistanceQuality.MEDIUM
        }
        return DistanceEstimate(
            meters = meters,
            lowerMeters = lower,
            upperMeters = upper,
            confidence = ((if (calibrated) 0.85 else 0.45) *
                (1.0 - relativeSpread / MAX_MAD_RATIO).coerceIn(0.1, 1.0) *
                (5.0 / meters).coerceAtMost(1.0) *
                samples.minOf { it.motionConfidence } *
                capability.targetConfidence.coerceIn(0.0, 1.0) *
                (samples.size / 8.0).coerceAtMost(1.0)).coerceIn(0.05, 0.85),
            quality = quality,
            source = if (calibrated) DistanceSource.FOCUS_CALIBRATED else DistanceSource.FOCUS_APPROXIMATE,
            timestampNs = samples.last().timestampNs,
            cameraIdentity = context.cameraIdentity,
            target = context.target,
            sampleCount = samples.size,
            isFresh = true,
            receivedAtNs = clock(),
        )
    }

    private fun <T : Number> median(sorted: List<T>): Double {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1].toDouble() + sorted[middle].toDouble()) / 2.0
        } else {
            sorted[middle].toDouble()
        }
    }

    private data class FocusSample(val timestampNs: Long, val diopters: Float, val motionConfidence: Double)

    private companion object {
        const val MIN_SAMPLES = 5
        const val MAX_SAMPLES = 10
        const val SAMPLE_WINDOW_NS = 2_000_000_000L
        const val DEFAULT_TTL_NS = 2_000_000_000L
        const val MAX_MAD_RATIO = 0.12
        const val ESTIMATED_MAX_MAD_RATIO = 0.20
        const val ESTIMATED_MIN_METERS = 0.05
        const val ESTIMATED_MAX_METERS = 30.0
    }
}
