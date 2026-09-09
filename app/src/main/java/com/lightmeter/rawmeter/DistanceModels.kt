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
}

enum class DistanceQuality { HIGH, MEDIUM, LOW }

/** A locked physical-distance observation.  Values are metres, never display-rounded values. */
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
)

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
) {
    val effectiveMetersForFlash: Double?
        get() = estimate?.takeIf { it.isFresh && it.meters.isFinite() && it.meters > 0.0 }?.meters
}

internal data class DistanceContext(
    val generation: Int,
    val cameraIdentity: String,
    val physicalIdentityKnown: Boolean,
    val target: NormalizedPoint = NormalizedPoint.CENTER,
)
