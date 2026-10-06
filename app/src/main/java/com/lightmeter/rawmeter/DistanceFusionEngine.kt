package com.lightmeter.rawmeter

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max

/** Conservative fusion of independent metric observations of the SAME target and camera. */
internal object DistanceFusionEngine {
    const val CONFLICT_EV = 1.0 / 3.0
    const val MAX_FLASH_UNCERTAINTY_EV = 0.7

    fun flashDifferenceEv(first: Double, second: Double): Double =
        if (first.isFinite() && second.isFinite() && first > 0 && second > 0)
            2.0 * abs(log2(first / second)) else Double.POSITIVE_INFINITY

    fun uncertaintyEv(value: DistanceEstimate): Double {
        val lower = value.lowerMeters ?: return Double.POSITIVE_INFINITY
        val upper = value.upperMeters ?: return Double.POSITIVE_INFINITY
        if (lower > value.meters || upper < value.meters) return Double.POSITIVE_INFINITY
        return max(flashDifferenceEv(value.meters, lower), flashDifferenceEv(value.meters, upper))
    }

    fun usableForFlash(value: DistanceEstimate): Boolean =
        value.meters.isFinite() && value.meters > 0 && value.confidence.isFinite() &&
            value.confidence >= (if (value.source == DistanceSource.FOCUS_APPROXIMATE) 0.20 else 0.45) &&
            (value.quality != DistanceQuality.LOW || value.source == DistanceSource.FOCUS_APPROXIMATE) &&
            value.source != DistanceSource.FOCUS_ESTIMATED &&
            uncertaintyEv(value) <= MAX_FLASH_UNCERTAINTY_EV

    /** Stable AF readings can still drive an explicitly approximate result on older devices. */
    fun usableForEstimatedFlash(value: DistanceEstimate): Boolean {
        if (!value.meters.isFinite() || value.meters !in 0.05..30.0 || value.sampleCount < 5 ||
            !value.confidence.isFinite() || value.confidence <= 0.0) return false
        if (value.source == DistanceSource.FOCUS_ESTIMATED) return value.quality == DistanceQuality.LOW
        if (value.source !in setOf(DistanceSource.FOCUS_CALIBRATED, DistanceSource.FOCUS_APPROXIMATE) ||
            usableForFlash(value)) return false
        // Wide or open far bounds describe low precision; malformed bounds describe bad data.
        val lower = value.lowerMeters ?: return false
        val upper = value.upperMeters
        return lower.isFinite() && lower > 0.0 && lower <= value.meters &&
            (upper == null || (upper.isFinite() && upper >= value.meters))
    }

    fun weight(value: DistanceEstimate): Double {
        if (value.source == DistanceSource.FOCUS_ESTIMATED) return 0.0
        val error = uncertaintyEv(value)
        if (!error.isFinite() || !value.confidence.isFinite()) return 0.0
        // Smoothly fade the near-distance preference around 5 m rather than jumping at 5.000 m.
        val nearFocus = if (value.source == DistanceSource.FOCUS_CALIBRATED)
            1.0 + 0.5 * ((6.0 - value.meters) / 2.0).coerceIn(0.0, 1.0) else 1.0
        return nearFocus * value.confidence.coerceIn(0.0, 1.0).let { it * it } /
            max(0.04, error * error)
    }

    fun fuse(
        focus: DistanceEstimate?,
        motion: DistanceEstimate?,
        nowNs: Long,
        exposureDifference: (Double, Double) -> Double = ::flashDifferenceEv,
    ): DistanceMeasurementState? {
        val observations = listOfNotNull(focus, motion).filter {
            it.isCurrent(nowNs) && weight(it) > 0.0 && it.meters.isFinite() && it.meters > 0
        }
        if (observations.isEmpty()) return listOfNotNull(focus, motion).firstOrNull {
            it.isCurrent(nowNs) && usableForEstimatedFlash(it)
        }?.let(::available)
        if (observations.size == 1) return available(observations.single())
        val a = observations[0]
        val b = observations[1]
        // Timestamp is the physical observation time, not the delayed worker delivery time.
        if (a.cameraIdentity != b.cameraIdentity || a.target != b.target) return available(a)
        if (abs(a.timestampNs - b.timestampNs) > 500_000_000L)
            return available(observations.maxBy { it.timestampNs })
        val wa = weight(a)
        val wb = weight(b)
        val disagreement = exposureDifference(a.meters, b.meters)
            .takeIf { it.isFinite() && it >= 0.0 } ?: Double.POSITIVE_INFINITY
        if (disagreement > CONFLICT_EV) {
            val best = if (wa >= wb) a else b
            val other = if (wa >= wb) b else a
            // Do not manufacture a middle distance when exposure predictions disagree.
            // Selection requires both an absolute quality floor and decisive evidence.
            if (best.confidence >= 0.65 && usableForFlash(best) &&
                weight(best) >= 3.0 * weight(other)
            ) return available(best.copy(diagnosticReason = "Exposure conflict: stronger source selected"))
            return DistanceMeasurementState(
                estimate = best.copy(isFresh = false, diagnosticReason = "Distance sources disagree in flash EV"),
                status = DistanceMeasurementStatus.STALE,
                diagnosticReason = "Distance sources disagree in flash EV",
            )
        }
        val total = wa + wb
        val meters = total / (wa / a.meters + wb / b.meters)
        // Keep systematic uncertainty: no 1/sqrt(N) bonus for correlated frames or sensors.
        val lower = total / (wa / a.lowerMeters!! + wb / b.lowerMeters!!)
        val upper = total / (wa / a.upperMeters!! + wb / b.upperMeters!!)
        val confidence = ((wa * a.confidence + wb * b.confidence) / total *
            (1.0 - 0.25 * disagreement / CONFLICT_EV)).coerceIn(0.0, 0.90)
        return available(a.copy(
            meters = meters,
            lowerMeters = minOf(lower, a.meters, b.meters),
            upperMeters = maxOf(upper, a.meters, b.meters),
            confidence = confidence,
            quality = if (confidence >= 0.65) DistanceQuality.MEDIUM else DistanceQuality.LOW,
            source = DistanceSource.FUSED,
            // Fusion cannot renew either input's lifetime.
            timestampNs = minOf(a.timestampNs, b.timestampNs),
            receivedAtNs = minOf(a.receivedAtNs, b.receivedAtNs),
            sampleCount = a.sampleCount + b.sampleCount,
            diagnosticReason = null,
        ))
    }

    private fun available(value: DistanceEstimate) =
        DistanceMeasurementState(value, DistanceMeasurementStatus.AVAILABLE)
}
