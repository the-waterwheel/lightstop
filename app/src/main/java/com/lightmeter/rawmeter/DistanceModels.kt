package com.lightmeter.rawmeter

/** A point in the visible metering frame, independent from any particular camera stream. */
data class NormalizedPoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f)
    }

    companion object {
        val CENTER = NormalizedPoint(0.5f, 0.5f)
    }
}

enum class DistanceSource {
    FOCUS_CALIBRATED,
    FOCUS_APPROXIMATE,
    MANUAL,
    MOTION_PARALLAX,
    FUSED,
    /** Reciprocal focus heuristic; the HAL has not guaranteed a metric scale. */
    FOCUS_ESTIMATED,
}

enum class DistanceQuality { HIGH, MEDIUM, LOW }

/** Distance observation or explicitly marked heuristic. Values are never display-rounded. */
data class DistanceEstimate(
    val meters: Double,
    val lowerMeters: Double?,
    val upperMeters: Double?,
    val confidence: Double,
    val quality: DistanceQuality,
    val source: DistanceSource,
    val timestampNs: Long,
    val cameraIdentity: String,
    val target: NormalizedPoint,
    val sampleCount: Int,
    val isFresh: Boolean,
    val diagnosticReason: String? = null,
    /** Receipt clock, deliberately separate from the camera sensor's timestamp domain. */
    val receivedAtNs: Long = 0L,
) {
    val isApproximate: Boolean
        get() = source == DistanceSource.FOCUS_ESTIMATED || source == DistanceSource.FOCUS_APPROXIMATE ||
            (source == DistanceSource.FOCUS_CALIBRATED && !DistanceFusionEngine.usableForFlash(this))

    fun isCurrent(nowNs: Long = System.nanoTime()): Boolean = isFresh &&
        (receivedAtNs == 0L || nowNs - receivedAtNs in 0..DISTANCE_TTL_NS)
}

internal const val DISTANCE_TTL_NS = 2_000_000_000L

enum class DistanceMeasurementStatus {
    IDLE,
    UNSUPPORTED,
    WAITING_FOR_FOCUS,
    SAMPLING,
    AVAILABLE,
    STALE,
}

/** UI/business state.  A stale estimate is deliberately not usable for flash compensation. */
data class DistanceMeasurementState(
    val estimate: DistanceEstimate? = null,
    val status: DistanceMeasurementStatus = DistanceMeasurementStatus.IDLE,
    val diagnosticReason: String? = null,
    /** Unfused observations permit re-evaluation when flash/ambient settings change. */
    val focusObservation: DistanceEstimate? = null,
    val motionObservation: DistanceEstimate? = null,
) {
    val effectiveMetersForFlash: Double?
        get() = estimate?.takeIf {
            status == DistanceMeasurementStatus.AVAILABLE && it.isCurrent() &&
                (DistanceFusionEngine.usableForFlash(it) || DistanceFusionEngine.usableForEstimatedFlash(it))
        }?.meters

    internal fun estimateForFlash(
        nowNs: Long = System.nanoTime(),
        exposureDifference: (Double, Double) -> Double,
    ): DistanceEstimate? {
        val resolved = if (focusObservation != null || motionObservation != null) {
            DistanceFusionEngine.fuse(focusObservation, motionObservation, nowNs, exposureDifference)
        } else this
        return resolved?.estimate?.takeIf {
            resolved.status == DistanceMeasurementStatus.AVAILABLE && it.isCurrent(nowNs) &&
                (DistanceFusionEngine.usableForEstimatedFlash(it) ||
                    (DistanceFusionEngine.usableForFlash(it) &&
                        maxOf(exposureDifference(it.meters, it.lowerMeters!!),
                            exposureDifference(it.meters, it.upperMeters!!)) <= DistanceFusionEngine.MAX_FLASH_UNCERTAINTY_EV))
        }
    }
}

internal data class DistanceContext(
    val generation: Int,
    val cameraIdentity: String,
    val physicalIdentityKnown: Boolean,
    val target: NormalizedPoint = NormalizedPoint.CENTER,
)
