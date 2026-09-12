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
)

/** Static and frame-level safety gates for the only Camera2 distance value with metre semantics. */
internal object Camera2FocusDistancePolicy {
    fun supportReason(capability: FocusDistanceCapability): String? = when {
        !capability.physicalIdentityKnown -> "Active physical camera is unknown"
        capability.minimumDiopters <= 0f -> "Fixed-focus lens"
        capability.calibration !in setOf(
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED,
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE,
        ) -> "Focus distance is not calibrated"
        !capability.resultKeyAvailable -> "CaptureResult has no focus distance"
        else -> null
    }

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
                it.isFresh
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
    ): DistanceMeasurementState? {
        val expected = this.context ?: return null
        val currentCapability = capability ?: return null
        if (expected != context) return invalidate("Camera route or session changed")
        if (locked?.let { timestampNs - it.timestampNs > ttlNs } == true) {
            locked = locked?.copy(isFresh = false)
            return DistanceMeasurementState(locked, DistanceMeasurementStatus.STALE, "Distance estimate expired")
        }
        if (!Camera2FocusDistancePolicy.acceptsFrame(
                afState, lensState, diopters, currentCapability.minimumDiopters,
            ) || timestampNs <= lastTimestampNs
        ) return null
        lastTimestampNs = timestampNs
        samples += FocusSample(timestampNs, diopters!!)
        val oldestAllowed = timestampNs - SAMPLE_WINDOW_NS
        samples = samples.filter { it.timestampNs >= oldestAllowed }.takeLast(MAX_SAMPLES).toMutableList()
        if (samples.size < MIN_SAMPLES) {
            return DistanceMeasurementState(status = DistanceMeasurementStatus.SAMPLING)
        }
        val candidate = stableEstimate(expected, currentCapability, samples) ?: return null
        val previous = locked
        locked = when {
            previous == null -> candidate
            materiallyDifferent(previous.meters, candidate.meters) -> candidate
            else -> previous.copy(timestampNs = candidate.timestampNs, isFresh = true, sampleCount = candidate.sampleCount)
        }
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
        val allowedMad = max(0.03, median * MAX_MAD_RATIO)
        if (mad > allowedMad || median <= 0.0) return null
        val meters = 1.0 / median
        val sigma = max(mad * 1.4826, median * 0.03)
        val lower = 1.0 / (median + sigma)
        val upper = if (median > sigma) 1.0 / (median - sigma) else null
        val calibrated = capability.calibration ==
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED
        val relativeSpread = mad / median
        val quality = when {
            !calibrated || meters >= 10.0 -> DistanceQuality.LOW
            samples.size >= 8 && relativeSpread <= 0.025 && meters <= 5.0 -> DistanceQuality.HIGH
            else -> DistanceQuality.MEDIUM
        }
        return DistanceEstimate(
            meters = meters,
            lowerMeters = lower,
            upperMeters = upper,
            confidence = (1.0 - relativeSpread / MAX_MAD_RATIO).coerceIn(0.1, 0.95),
            quality = quality,
            source = if (calibrated) DistanceSource.FOCUS_CALIBRATED else DistanceSource.FOCUS_APPROXIMATE,
            timestampNs = samples.last().timestampNs,
            cameraIdentity = context.cameraIdentity,
            target = context.target,
            sampleCount = samples.size,
            isFresh = true,
        )
    }

    private fun materiallyDifferent(old: Double, new: Double): Boolean =
        abs(old - new) > max(0.15, old * 0.08)

    private fun <T : Number> median(sorted: List<T>): Double {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1].toDouble() + sorted[middle].toDouble()) / 2.0
        } else {
            sorted[middle].toDouble()
        }
    }

    private data class FocusSample(val timestampNs: Long, val diopters: Float)

    private companion object {
        const val MIN_SAMPLES = 5
        const val MAX_SAMPLES = 10
        const val SAMPLE_WINDOW_NS = 1_000_000_000L
        const val DEFAULT_TTL_NS = 2_000_000_000L
        const val MAX_MAD_RATIO = 0.12
    }
}
